import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The matcher: turn "I want a ride from here" into "driver D7 is coming".
 *
 * Three rules do all the work, and each one exists because a simpler version
 * broke (the doc walks each break in stage 10):
 *
 *  1. NARROW BEFORE YOU RANK.  Ask the index for candidates near the pickup;
 *     never look at drivers who cannot possibly win.
 *
 *  2. A MATCH IS A CLAIM, NOT A QUERY RESULT.  "Nearest available driver" is
 *     a fact that stops being true the instant somebody else acts on it.  So the
 *     match is committed with compareAndSet(AVAILABLE -> OFFERED): exactly one
 *     caller can win, and the losers are told they lost and move to the next
 *     candidate.  THE INVARIANT: a driver is offered to at most one trip at a time.
 *
 *  3. EVERY RESERVATION HAS A DEADLINE.  A driver who never taps accept must not
 *     freeze supply forever, and a matcher process that dies mid-offer must not
 *     strand a driver.  Offers expire; expiry is the recovery path for both.
 */
final class Dispatch {

    final Map<String, Driver> drivers = new ConcurrentHashMap<>();
    final Map<String, Trip> trips = new ConcurrentHashMap<>();
    /** idempotency key -> trip id. A double-tapped request must not create two trips. */
    private final Map<String, String> idempotency = new ConcurrentHashMap<>();

    private final Index index;
    private final LongSupplier clock;
    private final AtomicLong seq = new AtomicLong();

    /** Tunables. Every one of them is a real production dial. */
    double searchRadiusM = 3_000;
    long pingTtlMs      = 30_000;   // older than this and the driver is a ghost
    long offerTtlMs     = 15_000;   // driver has this long to tap accept
    double speedMps     = 8.33;     // 30 km/h, city average

    /** Experiment knobs -- flip these to reproduce the failures in the doc. */
    boolean unsafeClaim = false;    // true = read-then-write instead of CAS
    boolean ignorePingFreshness = false;
    /** true = widen ring by ring and stop early (cheap, slightly worse matches). */
    boolean progressiveSearch = false;
    int minCandidates = 5;

    /** Counters the experiments assert on. */
    final AtomicLong doubleAssignments = new AtomicLong();
    final AtomicLong ghostDispatches   = new AtomicLong();
    final AtomicLong offersExpired     = new AtomicLong();

    final List<String> events = Collections.synchronizedList(new ArrayList<>());
    boolean verbose = false;

    Dispatch(Index index, LongSupplier clock) { this.index = index; this.clock = clock; }

    long now() { return clock.getAsLong(); }

    void log(String fmt, Object... args) {
        String s = String.format(fmt, args);
        events.add(s);
        if (verbose) System.out.println("    " + s);
    }

    // ---------------------------------------------------------------- supply

    void goOnline(Driver d) {
        drivers.put(d.id, d);
        d.lastPingMs = now();
        d.state.set(DriverState.AVAILABLE);
        index.update(d.id, d.loc);
    }

    void goOffline(String driverId) {
        Driver d = drivers.get(driverId);
        if (d == null) return;
        d.state.set(DriverState.OFFLINE);
        index.remove(driverId);   // out of the index = invisible to matching
    }

    /**
     * The firehose. In a real city this is the highest-volume write in the system:
     * every driver, every few seconds, forever. It must be O(1) and it must not
     * touch the durable store on the critical path.
     */
    void ping(String driverId, Loc loc) {
        Driver d = drivers.get(driverId);
        if (d == null) return;
        d.loc = loc;
        d.lastPingMs = now();
        if (d.state.get() != DriverState.OFFLINE) index.update(driverId, loc);
    }

    // ---------------------------------------------------------------- demand

    /**
     * @param idemKey the rider app's request id. Retries and double taps carry the
     *                SAME key, which is what makes "at-least-once delivery" safe.
     */
    Trip requestRide(String riderId, Loc pickup, Loc dropoff, String idemKey) {
        String existing = idempotency.get(idemKey);
        if (existing != null) {
            log("request %s is a REPLAY of %s -- returning the same trip, no second match",
                    idemKey, existing);
            return trips.get(existing);
        }
        Trip t = new Trip("T" + seq.incrementAndGet(), riderId, pickup, dropoff);
        trips.put(t.id, t);
        String won = idempotency.putIfAbsent(idemKey, t.id);
        if (won != null) {           // lost a genuine race with a concurrent retry
            trips.remove(t.id);
            return trips.get(won);
        }
        t.surgeMultiplier = surgeAt(pickup);
        log("rider %s requests %s at %s (surge x%.1f)", riderId, t.id, pickup, t.surgeMultiplier);
        matchOnce(t);
        return t;
    }

    /**
     * One matching attempt: narrow, rank, then CLAIM the best candidate we can win.
     * Returns true if an offer is now outstanding.
     */
    boolean matchOnce(Trip t) {
        if (t.state != TripState.REQUESTED && t.state != TripState.NO_DRIVERS) return false;

        List<String> nearby = (progressiveSearch && index instanceof CellIndex ci)
                ? ci.nearProgressive(t.pickup, searchRadiusM, minCandidates)
                : index.near(t.pickup, searchRadiusM);

        record Cand(Driver d, double etaSec) {}
        List<Cand> ranked = new ArrayList<>();
        long now = now();
        for (String id : nearby) {
            Driver d = drivers.get(id);
            if (d == null || t.triedDrivers.contains(id)) continue;
            if (d.state.get() != DriverState.AVAILABLE) continue;
            boolean fresh = now - d.lastPingMs <= pingTtlMs;
            if (!fresh && !ignorePingFreshness) continue;
            if (!fresh) ghostDispatches.incrementAndGet();   // counted only when we ignore the rule
            // Rank by TIME, not distance: 400 m the wrong side of a river beats
            // 900 m down a clear road, and only the road factor knows that.
            double etaSec = d.loc.distanceTo(t.pickup) * d.roadFactor / speedMps;
            ranked.add(new Cand(d, etaSec));
        }
        ranked.sort(Comparator.comparingDouble(Cand::etaSec));

        for (Cand c : ranked) {
            if (tryClaim(c.d())) {
                Offer o = new Offer("O" + seq.incrementAndGet(), t.id, c.d().id, now + offerTtlMs);
                Offer prev = c.d().offer;
                // DUPLICATE DETECTOR: we just took a driver whose previous offer is
                // still live and belongs to a different, still-waiting trip. Two riders
                // are now being told the same car is coming. Under compareAndSet this
                // branch is unreachable; under read-then-write it is not.
                if (prev != null && prev.expiresAtMs() > now && !prev.tripId().equals(t.id)) {
                    Trip other = trips.get(prev.tripId());
                    if (other != null && other.state == TripState.REQUESTED) doubleAssignments.incrementAndGet();
                }
                c.d().offer = o;
                t.pendingOfferDriver = c.d().id;
                t.triedDrivers.add(c.d().id);
                log("%s -> offered to %s (eta %.0fs, %.0fm away), expires in %ds",
                        t.id, c.d().id, c.etaSec(), c.d().loc.distanceTo(t.pickup), offerTtlMs / 1000);
                return true;
            }
            // Losing the CAS is normal, not an error: somebody else got there first.
            log("%s wanted %s but lost the claim -- trying the next candidate", t.id, c.d().id);
            t.triedDrivers.add(c.d().id);
        }
        t.state = TripState.NO_DRIVERS;
        log("%s: no claimable driver within %.0fm (%d nearby, %d already tried)",
                t.id, searchRadiusM, nearby.size(), t.triedDrivers.size());
        return false;
    }

    /**
     * THE LOAD-BEARING LINE OF THE WHOLE LAB.
     *
     * safe path: one atomic transition AVAILABLE -> OFFERED. Two threads that both
     * read AVAILABLE cannot both succeed; the hardware decides, and it decides once.
     *
     * unsafe path: read, then write -- with a scheduling gap in between, which is
     * exactly what a real network hop or a GC pause inserts for free. Both threads
     * see AVAILABLE, both write OFFERED, and two riders are told the same car is
     * coming. Experiments.java measures how often.
     */
    private boolean tryClaim(Driver d) {
        if (unsafeClaim) {
            if (d.state.get() != DriverState.AVAILABLE) return false;
            // The gap. 50 us is generous to the buggy code: a round trip to the
            // driver-state store is 20x longer, and a GC pause is 1000x longer.
            java.util.concurrent.locks.LockSupport.parkNanos(50_000);
            d.state.set(DriverState.OFFERED);
            return true;
        }
        return d.state.compareAndSet(DriverState.AVAILABLE, DriverState.OFFERED);
    }

    // ------------------------------------------------------------ driver acts

    /** Driver taps ACCEPT. Fences on the offer id: a stale offer cannot start a trip. */
    boolean accept(String driverId, String offerId) {
        Driver d = drivers.get(driverId);
        if (d == null) return false;
        Offer o = d.offer;
        if (o == null || !o.offerId().equals(offerId)) {
            log("%s tried to accept stale offer %s -- rejected", driverId, offerId);
            return false;
        }
        if (now() > o.expiresAtMs()) { log("%s accepted %s too late", driverId, offerId); return false; }
        if (!d.state.compareAndSet(DriverState.OFFERED, DriverState.ON_TRIP)) return false;
        Trip t = trips.get(o.tripId());
        t.driverId = driverId;
        t.state = TripState.MATCHED;
        log("%s ACCEPTED %s -- %s is matched", driverId, o.offerId(), t.id);
        return true;
    }

    /** Driver taps DECLINE (or the app never showed the offer). Supply is released. */
    void decline(String driverId) {
        Driver d = drivers.get(driverId);
        if (d == null) return;
        Offer o = d.offer;
        if (o == null || !d.state.compareAndSet(DriverState.OFFERED, DriverState.AVAILABLE)) return;
        d.offer = null;
        Trip declined = trips.get(o.tripId());
        if (declined != null) declined.pendingOfferDriver = null;
        log("%s declined %s -- rematching", driverId, o.tripId());
        Trip t = trips.get(o.tripId());
        if (t != null && t.state == TripState.REQUESTED) matchOnce(t);
    }

    /**
     * Housekeeping tick. Reclaims reservations whose deadline passed -- the SAME code
     * path recovers from "driver ignored the popup" and from "the matcher that made
     * this offer crashed". One mechanism, two failures.
     */
    void expireOffers() {
        long now = now();
        for (Driver d : drivers.values()) {
            Offer o = d.offer;
            if (o == null || d.state.get() != DriverState.OFFERED || now <= o.expiresAtMs()) continue;
            if (d.state.compareAndSet(DriverState.OFFERED, DriverState.AVAILABLE)) {
                d.offer = null;
                offersExpired.incrementAndGet();
                log("offer %s to %s EXPIRED -- driver released back to supply", o.offerId(), d.id);
                Trip t = trips.get(o.tripId());
                if (t != null && t.state == TripState.REQUESTED) matchOnce(t);
            }
        }
    }

    void startTrip(String tripId) { trips.get(tripId).state = TripState.IN_PROGRESS; }

    /** Trip ends: driver returns to supply, fare is computed once. */
    void completeTrip(String tripId, double baseFare) {
        Trip t = trips.get(tripId);
        if (t == null || t.state == TripState.COMPLETED) return;   // idempotent completion
        Driver d = drivers.get(t.driverId);
        t.fareRupees = Math.round(baseFare * t.surgeMultiplier * 100) / 100.0;
        t.state = TripState.COMPLETED;
        if (d != null && d.state.compareAndSet(DriverState.ON_TRIP, DriverState.AVAILABLE)) {
            d.offer = null;
            index.update(d.id, d.loc);
        }
        log("%s COMPLETED, fare Rs%.2f (base Rs%.2f x surge %.1f)",
                tripId, t.fareRupees, baseFare, t.surgeMultiplier);
    }

    // ------------------------------------------------------------------ price

    /**
     * Surge in its simplest honest form: a local ratio of demand to supply.
     * It is a CONTROL SIGNAL, not a fee -- it exists to make the two sides of an
     * unbalanced local market meet, by removing some riders and attracting some
     * drivers. Real pricing adds smoothing, caps, and per-city rules.
     */
    double surgeAt(Loc where) {
        long supply = index.near(where, searchRadiusM).stream()
                .map(drivers::get).filter(Objects::nonNull)
                .filter(d -> d.state.get() == DriverState.AVAILABLE).count();
        long demand = trips.values().stream()
                .filter(t -> t.state == TripState.REQUESTED || t.state == TripState.NO_DRIVERS)
                .filter(t -> t.pickup.distanceTo(where) <= searchRadiusM).count();
        if (demand == 0) return 1.0;
        if (supply == 0) return 3.0;
        double ratio = (double) demand / supply;
        return Math.min(3.0, Math.max(1.0, Math.round(ratio * 10) / 10.0));
    }

    /** What the matcher sees, in ranked order, with the reason each row is in or out. */
    record Candidate(String driverId, double distM, double etaSec, long pingAgeMs,
                     DriverState state, boolean eligible, String reason) {}

    List<Candidate> inspect(Loc pickup) {
        long now = now();
        List<Candidate> out = new ArrayList<>();
        for (String id : index.near(pickup, searchRadiusM)) {
            Driver d = drivers.get(id);
            if (d == null) continue;
            double dist = d.loc.distanceTo(pickup);
            double eta = dist * d.roadFactor / speedMps;
            long age = now - d.lastPingMs;
            boolean avail = d.state.get() == DriverState.AVAILABLE;
            boolean fresh = age <= pingTtlMs;
            String reason = !avail ? "not AVAILABLE (" + d.state.get() + ")"
                    : !fresh ? "STALE: last ping " + (age / 1000) + "s ago"
                    : "eligible";
            out.add(new Candidate(id, dist, eta, age, d.state.get(), avail && fresh, reason));
        }
        out.sort(Comparator.comparingDouble(Candidate::etaSec));
        return out;
    }

    long availableCount() {
        return drivers.values().stream().filter(d -> d.state.get() == DriverState.AVAILABLE).count();
    }

    /** AVAILABLE *and* recently seen. This is the number that can actually pick anyone up. */
    long availableFreshCount() {
        long now = now();
        return drivers.values().stream()
                .filter(d -> d.state.get() == DriverState.AVAILABLE && now - d.lastPingMs <= pingTtlMs).count();
    }
}
