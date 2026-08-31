# Solutions — Uber high-level system design

Try [`EXERCISES.md`](EXERCISES.md) first. For rungs 7–8 these are worked
discussions, not single right answers.

---

## Rung 1 — Explain

**1.1** You are looking for a car that is moving, among a hundred thousand cars
that are all moving, and the answer has to arrive before the person gets bored —
about a second. Two things make it hard. First, "who is near me?" is a question
about *every* car unless you have organised them by where they are in advance.
Second, the answer goes stale while you are computing it: by the time you say
"that one", it has moved, or somebody else has already been promised it.

**1.2** Because between *finding* the free car and *marking* it busy there is a
gap, and another request fits inside that gap perfectly — so both requests find
the same car free and both mark it busy. The fix is not a faster check; it is
making the check and the mark one indivisible action that only one request can
win.

**1.3** Caching addresses problem 1 (the scan) and only partly: it makes reads
cheap, and creates a new correctness question about how stale the cached position
is. It does nothing for problem 2 (two callers, one car — a cache makes this
*worse*, because now several machines each hold their own copy of "car 47 is
free"), nothing for problem 3 (a pin that lies — a cache faithfully caches the
lie), and nothing for problem 4 (a reservation nobody releases).

---

## Rung 2 — Identify

**2.1** From a fresh console, ranking by soonest arrival, the reserved car is
**K3** while the closest car is **K2** — K2 is roughly 200 m away with a roads
factor of 3.4×, so it is about 700 m of driving. The log line names both.

**2.2** The inspector shows: `state = OFFERED`, its cell, its position in metres,
how many seconds ago it reported, its roads factor, distance and arrival time to
the rider, and `reserved for T1 (expires t+15s)`.

**2.3** *Everything within 1.5 km* shades 49 squares (a 7×7 block). *Widen until
3 cars found* usually shades 9 (rings 0 and 1), sometimes 1 or 25 depending on
where you stand. What you gave up: the guarantee that no better car exists
slightly further out. What you bought: a search cost that depends on nearby
supply rather than on the size of the search radius.

**2.4** The claim:

```java
return d.state.compareAndSet(DriverState.AVAILABLE, DriverState.OFFERED);
```

The cheap write:

```java
if (oldKey != null && oldKey == newKey) return;    // moved, but not out of its cell
```

---

## Rung 3 — Predict

**3.1** **44.9%** of requests find nobody at all, and **13.3%** return a worse
driver than the true nearest (116 m worse on average, 445 m at worst). The second
number is the dangerous one: those requests succeed, log nothing, and simply make
every rider wait a little longer forever.

**3.2** **About 98 of 200** — it lands near half every run, and the exact number
moves with thread scheduling. Not a rare race — the common case, because
every rider on the same corner ranks the cars in the same order and reaches for
the same one.

**3.3** **15–25×** (112 µs → 2,442 µs per query in one run), with 6,399 cars in the single
busiest 500 m square. Nothing is broken; the index simply stopped narrowing,
because narrowing to "nearby" only helps when nearby is a small set.

**3.4** **D1 gets the offer**, at 316 m, even though D4's pin is 71 m away — D4
last reported 65 seconds ago and the freshness limit is 30. With
`ignorePingFreshness = true`, D4 wins and the rider is sent a car that may be
anywhere; `ghostDispatches` counts it.

---

## Rung 4 — Modify

**4.1 — 50 m cells.** Measured, 100,000 drivers, 3 km radius:

```
   50m cells:   4.32 ms/query,  14,641 cells visited,  2,129 drivers measured
  200m cells:   0.71 ms/query,     961 cells visited,  2,221 drivers measured
  500m cells:   0.50 ms/query,     169 cells visited,  2,425 drivers measured
 2000m cells:   0.91 ms/query,      25 cells visited,  5,586 drivers measured
```

A finer index is *slower* because the cost of a search has two halves: the cells
you visit and the drivers you measure. Shrinking cells trades the second for the
first, and at 50 m you visit 14,641 mostly-empty squares to save measuring 300
drivers. Too coarse is bad in the other direction — at 2 km you visit 25 squares
and measure 5,586 drivers. The curve is U-shaped and its minimum depends on
density, which is precisely the argument for multi-resolution grids (H3/S2): one
size cannot be right for an airport and a highway at the same time.

Nothing here is a safety loss. It is pure liveness/latency.

**4.2 — offer TTL.** At 2 seconds, real drivers lose offers they were reaching
for: the offer expires, the trip re-matches, and the first driver sees a popup
vanish. Acceptance rate drops, offers-per-trip rises, and riders wait longer —
liveness, degraded by trying too hard to be quick. At 5 minutes, a crashed
matcher takes a car out of supply for five minutes, and a driver who ignores a
popup is unreachable for five minutes. Neither setting is unsafe; the TTL is a
straight trade between *driver reaction time* and *how long a failure strands a
car*.

**4.3 — ping TTL of 2 seconds.** Almost the whole fleet becomes unmatchable,
because drivers ping every ~4 seconds and normal network jitter puts most of them
over the limit at any instant. Real-world trigger: an ingest slowdown of a few
seconds — a redeploy, a GC pause, a slow shard. **This is the self-inflicted
outage to design against**: the freshness TTL must be comfortably larger than
normal ping interval plus normal ingest lag, or a small blip un-matches your
entire supply. Liveness, lost catastrophically, from a rule that exists to
protect quality.

**4.4 — remove the `triedDrivers` guard.** Measured: the declining driver is
immediately re-offered the same trip, forever.

```
first offer: A
after decline 1: offered to A
after decline 2: offered to A
...
```

The general name is **livelock** (or a retry loop with no progress): the system
is busy, nothing is wrong with any individual step, and no work completes. In
production it also becomes an accidental denial-of-service against one driver's
phone.

---

## Rung 5 — Implement

**5.1** A correct `cancelTrip`:

```java
void cancelTrip(String tripId) {
    Trip t = trips.get(tripId);
    if (t == null || t.state == TripState.COMPLETED || t.state == TripState.CANCELLED) return;
    String held = t.pendingOfferDriver;
    if (held != null) {
        Driver d = drivers.get(held);
        // compareAndSet, not set: the driver may have accepted a millisecond ago.
        if (d != null && d.state.compareAndSet(DriverState.OFFERED, DriverState.AVAILABLE)) {
            d.offer = null;
        }
        t.pendingOfferDriver = null;
    }
    t.state = TripState.CANCELLED;
    log("%s cancelled by the rider; %s released", tripId, held);
}
```

**Why `compareAndSet` and not `set`:** the concrete race is *cancel racing
accept*. The rider taps cancel at the same moment the driver taps accept. The
accept moves the driver `OFFERED → ON_TRIP`; if cancel then blindly writes
`AVAILABLE`, you have a driver who is driving to a cancelled pickup **and** is in
the pool to be offered to someone else — the double-booking bug, re-entering
through the cancel path. With `compareAndSet(OFFERED, AVAILABLE)` the cancel
simply fails to release, sees the driver is already on a trip, and the cancel
becomes a "cancel after accept", which is a different (and paid) business case.

**5.2 — cap the ranking.** Sorting is not where the cost is; the candidate list
is. Experiment 1's *drivers measured per request* is unchanged (the index still
returned them), while ranking time drops — so with `nearProgressive` already
stopping early, the cap changes very little. It matters most in exactly one
place: dense areas, where the ring search returns hundreds and you would rank
them all. The quality you lose is the tail — the best of 200 candidates is
slightly better than the best of 10 — and you lose the most in dense
neighbourhoods, which is also where the difference between the 1st and 10th best
car is smallest. That asymmetry is why the cap is a good trade.

---

## Rung 6 — Debug

**6.1** Two tests fail:

```
FAIL  a_moving_driver_changes_cells_and_stays_findable    still findable at the old position=true
FAIL  an_offline_driver_disappears_from_every_cell_it_ever_visited   2 stale copies still findable
```

The cause is one **missing** line in `BrokenIndex.update`: it adds the driver to
its new cell but never removes it from the old one.

```java
public void update(String driverId, Loc loc) {
    long newKey = pack(ixOf(loc), iyOf(loc));
    // MISSING: Long oldKey = driverCell.get(driverId);
    //          if (oldKey != null && oldKey != newKey) cells.get(oldKey).remove(driverId);
    cells.computeIfAbsent(newKey, k -> new ConcurrentHashMap<>()).put(driverId, loc);
    driverCell.put(driverId, newKey);
}
```

`remove()` then only cleans the *current* cell, so every cell the driver ever
visited keeps a copy — at the position it had when it was there.

**6.2** Invariant **I1**: a driver is filed under exactly one cell. Note that I2
(one rider per car) still holds — the claim is unaffected. This is a *quality*
bug, not a safety bug, which is exactly why it survives review.

**6.3** After a week: the index has grown to hold every cell every driver has
ever passed through, so memory climbs steadily and search gets slower as ghosts
accumulate. Riders complain that the app "sent a car that was miles away" and
that ETAs are wrong. The dashboards that look fine: error rate (zero), dispatch
success rate (fine — a car is always found), CPU (gradual). The dashboards that
move first: index memory, candidates examined per query, and *offer acceptance
rate* — drivers decline offers for pickups they are nowhere near.

**6.4** The smallest catching test:

```java
Index idx = newIndex(true);
idx.update("A", HOME);
idx.update("A", HOME.moved(2000, 0));
check("moving leaves nothing behind", idx.near(HOME, 200).isEmpty(), "...");
```

Two updates and one query. Note what makes it work: it asserts on **absence**.
Tests that only assert the driver is findable *at its new position* pass happily
against the bug.

---

## Rung 7 — Handle a failure

**7.1 — crash between the claim and the answer.**

What happens today, in order: `requestRide` writes the idempotency key → creates
the trip → claims a driver → returns. If dispatch dies *after* the claim and
before the reply, the retry finds the key, returns the same trip, and does **not**
match again — so the outcome is correct. That is guaranteed *because the key is
written before the claim*. Reverse those two lines and the retry re-enters
matching and claims a second car for the same rider, and now you are holding two
cars for one trip.

What is merely likely rather than guaranteed: that the trip and its key survived
the crash at all. In this lab both live in memory, so a real dispatch crash loses
them and the retry creates a genuinely new trip while the first driver sits in
`OFFERED` until the lease expires. The fix is that the idempotency key and the
trip must be written **durably, together, before the claim** — one transaction —
and the claim must be recoverable from the trip record. Then the retry is
harmless no matter where the crash landed.

The general rule worth taking away: *the record of intent must be durable before
the side effect that acts on it.* Same shape as the outbox.

**7.2 — the driver's app dies mid-trip.** Already covered: `completeTrip` is
idempotent (I6), so a replayed completion charges once. What is missing:

- **The trip must be resumable.** On reinstall, the app asks "what am I doing?"
  and the server answers from the durable trip record — the client cannot be the
  source of truth for an in-progress trip.
- **The completion must be reconstructable without the app.** If the phone never
  comes back, the trip is stuck `IN_PROGRESS` forever. Real systems have a
  sweeper: trips in progress with no telemetry for N minutes go to a resolution
  path (auto-complete with an estimated fare, or a support queue).
- **Fare inputs must be durable as they accrue**, not computed from a final
  client message — otherwise a dead phone loses the distance travelled.

**7.3 — the ETA service is down.**

- **Rank by:** straight-line distance, with a per-area correction factor cached
  from the last known good ETAs — degraded, still ordered sensibly.
- **The rider sees:** a car, and a range instead of a precise ETA ("about 5–8
  min"). Never a spinner, never a failure.
- **Log:** a distinct `degraded_ranking` counter, so the quality loss is visible
  in the same dashboards as everything else, and so post-hoc match quality can be
  compared.
- **Avoid the storm:** a circuit breaker around the ETA client (fail fast while
  it is open rather than waiting for timeouts on every request), bounded retries
  with jitter, and a timeout budget shorter than the rider's patience.

Toolkit: [`software-architecture-patterns`](../software-architecture-patterns/)
(breaker, bulkhead, graceful degradation) and
[`distributed-rate-limiting`](../distributed-rate-limiting/) (shedding,
fail-open vs fail-closed — here, **fail-open with degraded quality**, because a
worse car now beats no car at all).

---

## Rung 8 — Design

**8.1 — the airport.** What survives: the claim, the lease, the freshness rule,
the trip state machine. What must change:

- **Indexing.** One 500 m cell holding 6,399 cars is not an index. Either
  sub-divide (a finer resolution inside hot cells — H3 exists for this) or stop
  treating an airport as geography at all and treat it as a **queue**: drivers
  join a FIFO on arrival and the matcher pops rather than searches. This is the
  right answer, and it is also the fair one — the airport's real problem is not
  latency but that "nearest" is meaningless when 2,000 cars are within 200 m of
  each other, so some other ordering has to be chosen deliberately.
- **Matching.** Batched, not greedy: arrivals come in bursts as flights land, and
  a burst is exactly the case where batching wins most.
- **The driver sees** their queue position — otherwise the ordering feels
  arbitrary and drivers game it by circling.
- **Monitor:** queue length and wait time per terminal, join/leave rate, and
  matches from the queue versus from geographic search.

**8.2 — three cities.** Shard by **city/region**, because rides are local: a
pickup and a drop-off are almost always in the same region, so this keeps the hot
path inside one region and one failure domain.

- **Routing key:** the geographic cell of the *pickup* — it is known at request
  time and it is what the index is keyed by anyway.
- **Boundary trips** (rare, real: airport runs between metro areas): the trip
  belongs to the region that owns the pickup, for its whole life. Do not migrate
  a live trip between regions; you would be inventing distributed transactions
  for the sake of tidiness.
- **Consistency posture, per piece of state:**
  - *Geo index* — **AP**. Stale or partial is a worse match, not a wrong one.
  - *Driver state (the claim)* — **CP**, single owner per driver id. A driver
    whose owner is unreachable is unmatchable, and that is the correct answer.
  - *Trip record* — **CP** within its region, durable, replicated.
  - *Payments* — **CP**, and idempotent end to end; money never gets AP.
- **A region outage** takes down dispatch in that city and nothing else, provided
  the shared services (identity, payments, maps) are not single-region. The
  design goal is that the blast radius equals the region.

**8.3 — the 10× day.** Ranked by effect, best first:

1. **Surge** — the only lever that changes *demand*. Everything else rearranges a
   queue; this shortens it. Cost: unpopular, needs caps and communication.
2. **Load shedding at the gateway** — protects the system from the retry storm
   that a 10× day guarantees. Cost: some riders get a clear "try again" instead
   of a spinner, which is the honest failure.
3. **Batching window**, widened from ~2 s to ~5 s — 10× demand means many
   candidates per batch, so batching pays more than usual. Cost: seconds added to
   everyone's wait.
4. **Ping interval**, lengthened from 4 s to 8 s — halves the firehose at the
   cost of position accuracy. Cheap insurance if ingest lag rises.
5. **Search radius**, *reduced* — counter-intuitively. When supply is scarce, a
   wide search sends cars on long empty drives, removing them from where they
   were needed. Cost: more "no cars available", sooner and more honestly.
6. **Offer TTL**, shortened a little — recovers unanswered offers faster when
   every car counts. Cost: more missed offers for slow drivers; do not overdo it.

First lever: **surge**, and the evidence it worked is the *supply/demand ratio
per area* moving back toward 1 — not revenue, and not the multiplier itself.

**8.4 — the one metric.** I would put up **the fraction of ride requests that get
an accepted driver within 60 seconds** (a "good match rate"), sliced by area.

- Why not dispatch p99 latency: it can be beautiful while nobody gets a car.
- Why not match rate alone: it counts a match to a ghost car as a success.
- Why not average wait: the average is dominated by the healthy majority and
  hides the airport and the outer suburbs entirely.
- What it misses: **match quality** — it says nothing about whether the rider got
  the *best* car, so a matcher that quietly picks the second-best forever (the
  cell-edge bug, 13.3% of requests) looks perfectly healthy. That is why offer
  acceptance rate and mean pickup ETA sit next to it on the same screen.
