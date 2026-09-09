import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Executable documentation. Each test is named after a situation a rider or a
 * driver could describe in one sentence, and it asserts the guarantee the README
 * claims for that situation.
 *
 * Run: java src/Tests.java
 */
public class Tests {

    static int passed = 0, failed = 0;
    /** `java src/Tests.java broken` swaps in the planted-bug index (exercise 6). */
    static boolean broken = false;
    static Index newIndex(boolean ring) { return broken ? new BrokenIndex(500) : new CellIndex(500, ring); }
    static final Loc HOME = new Loc(12.9716, 77.5946);

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.printf("  PASS  %-52s %s%n", name, detail); }
        else    { failed++; System.out.printf("  FAIL  %-52s %s%n", name, detail); }
    }

    static Dispatch fresh(AtomicLong clock, boolean ring) {
        return new Dispatch(newIndex(ring), clock::get);
    }

    public static void main(String[] args) throws Exception {
        broken = args.length > 0 && args[0].equals("broken");
        System.out.println("\nRUNNING TESTS" + (broken ? "  [BrokenIndex - exercise 6]" : "") + "\n");

        nearest_by_eta_beats_nearest_by_distance();
        own_cell_search_misses_a_driver_across_the_edge();
        ring_search_agrees_with_the_full_scan_oracle();
        a_driver_is_offered_to_at_most_one_trip_under_concurrency();
        expired_offer_returns_the_driver_and_rematches_the_trip();
        an_offer_from_an_old_popup_cannot_start_a_trip();
        a_driver_who_stopped_pinging_is_not_dispatched();
        double_tapping_request_creates_one_trip();
        finishing_a_trip_returns_the_driver_to_supply();
        completing_the_same_trip_twice_charges_once();
        a_moving_driver_changes_cells_and_stays_findable();
        surge_rises_when_demand_outruns_supply();
        going_offline_removes_the_driver_from_the_index();
        an_offline_driver_disappears_from_every_cell_it_ever_visited();

        System.out.printf("%n%d passed, %d failed%n%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ---------------------------------------------------------------------

    static void nearest_by_eta_beats_nearest_by_distance() {
        AtomicLong clock = new AtomicLong(1000);
        Dispatch d = fresh(clock, true);
        d.goOnline(new Driver("near-but-slow", HOME.moved(200, 0), 4.0));   // 200 m, 800 m of road
        d.goOnline(new Driver("far-but-fast",  HOME.moved(600, 0), 1.0));   // 600 m, 600 m of road
        Trip t = d.requestRide("r", HOME, HOME.moved(3000, 0), "k1");
        check("nearest_by_eta_beats_nearest_by_distance",
                "far-but-fast".equals(t.pendingOfferDriver),
                "offered to " + t.pendingOfferDriver);
    }

    static void own_cell_search_misses_a_driver_across_the_edge() {
        CellIndex ownCell = new CellIndex(500, false);
        // Put a driver 30 m away, but on the far side of a cell boundary.
        Loc rider = new Loc(12.9716000, 77.5946000);
        double cellDeg = 500 / Loc.M_PER_DEG_LAT;
        double boundary = Math.ceil(rider.lat() / cellDeg) * cellDeg;      // next line north
        Loc across = new Loc(boundary + 0.00001, rider.lon());
        ownCell.update("across-the-line", across);
        List<String> found = ownCell.near(rider, 3000);
        check("own_cell_search_misses_a_driver_across_the_edge", found.isEmpty(),
                String.format("driver %.0f m away, own-cell search found %d",
                        rider.distanceTo(across), found.size()));
    }

    static void ring_search_agrees_with_the_full_scan_oracle() {
        Random r = new Random(4);
        NaiveIndex truth = new NaiveIndex();
        CellIndex ring = new CellIndex(500, true);
        Map<String, Loc> loc = new HashMap<>();
        for (int i = 0; i < 2000; i++) {
            Loc l = HOME.moved(r.nextInt(20000) - 10000, r.nextInt(20000) - 10000);
            loc.put("D" + i, l); truth.update("D" + i, l); ring.update("D" + i, l);
        }
        int mismatches = 0;
        for (int q = 0; q < 500; q++) {
            Loc p = HOME.moved(r.nextInt(20000) - 10000, r.nextInt(20000) - 10000);
            Set<String> a = new HashSet<>(truth.near(p, 2000));
            Set<String> b = new HashSet<>(ring.near(p, 2000));
            if (!a.equals(b)) mismatches++;
        }
        check("ring_search_agrees_with_the_full_scan_oracle", mismatches == 0,
                mismatches + " mismatched result sets over 500 queries");
    }

    /** THE headline invariant: one car, one rider. */
    static void a_driver_is_offered_to_at_most_one_trip_under_concurrency() throws Exception {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        Dispatch d = fresh(clock, true);
        Random r = new Random(9);
        for (int i = 0; i < 100; i++)
            d.goOnline(new Driver("D" + i, HOME.moved(r.nextInt(1000) - 500, r.nextInt(1000) - 500), 1.0));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final int n = i;
            fs.add(pool.submit(() -> {
                try { go.await(); } catch (InterruptedException e) { return; }
                d.requestRide("R" + n, HOME, HOME.moved(3000, 0), "k" + n);
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get();
        pool.shutdown();

        List<String> promised = d.trips.values().stream()
                .map(t -> t.pendingOfferDriver).filter(Objects::nonNull).toList();
        check("a_driver_is_offered_to_at_most_one_trip_under_concurrency",
                promised.size() == new HashSet<>(promised).size() && d.doubleAssignments.get() == 0,
                promised.size() + " riders promised a car, " + new HashSet<>(promised).size() + " distinct cars");
    }

    static void expired_offer_returns_the_driver_and_rematches_the_trip() {
        AtomicLong clock = new AtomicLong(1000);
        Dispatch d = fresh(clock, true);
        d.goOnline(new Driver("A", HOME.moved(100, 0), 1.0));
        d.goOnline(new Driver("B", HOME.moved(500, 0), 1.0));
        Trip t = d.requestRide("r", HOME, HOME.moved(3000, 0), "k1");
        String first = t.pendingOfferDriver;
        clock.addAndGet(d.offerTtlMs + 1000);
        d.expireOffers();
        check("expired_offer_returns_the_driver_and_rematches_the_trip",
                !first.equals(t.pendingOfferDriver) && d.drivers.get(first).state.get() == DriverState.AVAILABLE,
                first + " released and back to AVAILABLE; trip moved on to " + t.pendingOfferDriver);
    }

    static void an_offer_from_an_old_popup_cannot_start_a_trip() {
        AtomicLong clock = new AtomicLong(1000);
        Dispatch d = fresh(clock, true);
        d.goOnline(new Driver("A", HOME.moved(100, 0), 1.0));
        Trip t = d.requestRide("r", HOME, HOME.moved(3000, 0), "k1");
        boolean stale = d.accept("A", "O-stale-from-a-previous-request");
        boolean good = d.accept("A", d.drivers.get("A").offer.offerId());
        check("an_offer_from_an_old_popup_cannot_start_a_trip", !stale && good,
                "stale accept rejected=" + !stale + ", current accept accepted=" + good
                        + ", trip=" + t.state);
    }

    static void a_driver_who_stopped_pinging_is_not_dispatched() {
        AtomicLong clock = new AtomicLong(1000);
        Dispatch d = fresh(clock, true);
        d.goOnline(new Driver("ghost", HOME.moved(50, 0), 1.0));
        d.goOnline(new Driver("live",  HOME.moved(900, 0), 1.0));
        clock.addAndGet(d.pingTtlMs + 5000);
        d.ping("live", d.drivers.get("live").loc);
        Trip t = d.requestRide("r", HOME, HOME.moved(3000, 0), "k1");
        check("a_driver_who_stopped_pinging_is_not_dispatched",
                "live".equals(t.pendingOfferDriver),
                "ghost was 50 m away and skipped; offered to " + t.pendingOfferDriver);
    }

    static void double_tapping_request_creates_one_trip() {
        AtomicLong clock = new AtomicLong(1000);
        Dispatch d = fresh(clock, true);
        d.goOnline(new Driver("A", HOME.moved(100, 0), 1.0));
        d.goOnline(new Driver("B", HOME.moved(200, 0), 1.0));
        Trip a = d.requestRide("r", HOME, HOME.moved(3000, 0), "same-key");
        Trip b = d.requestRide("r", HOME, HOME.moved(3000, 0), "same-key");
        check("double_tapping_request_creates_one_trip", a == b && d.trips.size() == 1,
                "trips created = " + d.trips.size() + ", same object = " + (a == b));
    }

    static void finishing_a_trip_returns_the_driver_to_supply() {
        AtomicLong clock = new AtomicLong(1000);
        Dispatch d = fresh(clock, true);
        d.goOnline(new Driver("A", HOME.moved(100, 0), 1.0));
        Trip t = d.requestRide("r", HOME, HOME.moved(3000, 0), "k1");
        d.accept("A", d.drivers.get("A").offer.offerId());
        d.startTrip(t.id);
        d.completeTrip(t.id, 200.0);
        check("finishing_a_trip_returns_the_driver_to_supply",
                d.drivers.get("A").state.get() == DriverState.AVAILABLE && t.state == TripState.COMPLETED,
                "driver state = " + d.drivers.get("A").state.get() + ", trip = " + t.state);
    }

    static void completing_the_same_trip_twice_charges_once() {
        AtomicLong clock = new AtomicLong(1000);
        Dispatch d = fresh(clock, true);
        d.goOnline(new Driver("A", HOME.moved(100, 0), 1.0));
        Trip t = d.requestRide("r", HOME, HOME.moved(3000, 0), "k1");
        d.accept("A", d.drivers.get("A").offer.offerId());
        d.startTrip(t.id);
        d.completeTrip(t.id, 200.0);
        double once = t.fareRupees;
        d.completeTrip(t.id, 200.0);        // the retry a flaky network guarantees
        check("completing_the_same_trip_twice_charges_once", t.fareRupees == once,
                "fare after one call Rs" + once + ", after the retry Rs" + t.fareRupees);
    }

    static void a_moving_driver_changes_cells_and_stays_findable() {
        Index idx = newIndex(true);
        idx.update("A", HOME);
        idx.update("A", HOME.moved(2000, 0));            // four cells north
        boolean goneFromOld = idx.near(HOME, 200).isEmpty();
        boolean foundAtNew = idx.near(HOME.moved(2000, 0), 200).contains("A");
        check("a_moving_driver_changes_cells_and_stays_findable",
                goneFromOld && foundAtNew,
                "still findable at the old position=" + !goneFromOld
                        + ", found at the new one=" + foundAtNew);
    }

    static void surge_rises_when_demand_outruns_supply() {
        AtomicLong clock = new AtomicLong(1000);
        Dispatch d = fresh(clock, true);
        d.goOnline(new Driver("A", HOME.moved(100, 0), 1.0));
        double calm = d.surgeAt(HOME);
        for (int i = 0; i < 6; i++) d.requestRide("r" + i, HOME, HOME.moved(3000, 0), "k" + i);
        double busy = d.surgeAt(HOME);
        check("surge_rises_when_demand_outruns_supply", busy > calm,
                String.format("x%.1f with 1 request, x%.1f with 6 chasing 1 car", calm, busy));
    }

    /** A driver who has moved around all evening must leave no trace anywhere. */
    static void an_offline_driver_disappears_from_every_cell_it_ever_visited() {
        Index idx = newIndex(true);
        Loc a = HOME, b = HOME.moved(3000, 0), c = HOME.moved(3000, 2500);
        idx.update("A", a); idx.update("A", b); idx.update("A", c);
        idx.remove("A");
        int leftBehind = idx.near(a, 300).size() + idx.near(b, 300).size() + idx.near(c, 300).size();
        check("an_offline_driver_disappears_from_every_cell_it_ever_visited", leftBehind == 0,
                leftBehind + " stale copies still findable after going offline");
    }

    static void going_offline_removes_the_driver_from_the_index() {
        AtomicLong clock = new AtomicLong(1000);
        Dispatch d = fresh(clock, true);
        d.goOnline(new Driver("A", HOME.moved(100, 0), 1.0));
        d.goOffline("A");
        Trip t = d.requestRide("r", HOME, HOME.moved(3000, 0), "k1");
        check("going_offline_removes_the_driver_from_the_index",
                t.state == TripState.NO_DRIVERS && t.pendingOfferDriver == null,
                "trip state = " + t.state);
    }
}
