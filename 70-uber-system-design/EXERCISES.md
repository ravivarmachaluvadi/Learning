# Exercises — Uber high-level system design

Eight rungs, easiest first. Rungs 1–3 need nothing but the console and your head.
Rungs 4–7 need the code. Rung 8 is an interview-grade design conversation.

Answers are in [`SOLUTIONS.md`](SOLUTIONS.md). Attempt first — especially the
predictions, where being wrong is the point.

---

## Rung 1 — Explain

**1.1** Explain to a non-engineer why finding the nearest available car is hard
once there are a hundred thousand cars, without using the words index, cell,
shard, or database.

**1.2** In two sentences, why is "find the nearest free car, then mark it busy"
wrong? Your answer must not contain the word "slow".

**1.3** A colleague says: "Just cache the driver locations and the problem goes
away." Which of the four problems from Rani's evening does caching address, and
which three does it leave untouched?

---

## Rung 2 — Identify

Open [`visualization.html`](visualization.html) and reset it.

**2.1** Request a ride. Which car was reserved, and which car is physically
closest? If they differ, quote the log line that explains why.

**2.2** Click that reserved car. What is its state, which square is it in, how
long ago did it report, and which trip is holding it?

**2.3** Set the search rule to *Everything within 1.5 km*. How many squares does
the map shade, and how many cars are in them? Now set it to *Widen until 3 cars
found*. How many squares now? What did you give up?

**2.4** In [`src/Dispatch.java`](src/Dispatch.java), find the one line that makes
"two riders, one car" impossible. In [`src/CellIndex.java`](src/CellIndex.java),
find the one line that makes 250,000 location pings per second affordable.

---

## Rung 3 — Predict

Write your answer down *before* running the command.

**3.1** A driver is 40 m away from the rider but just across a cell boundary, and
the matcher searches only the rider's own cell. Out of 5,000 random requests, what
fraction do you think find *no* driver at all? What fraction find a *worse*
driver and report success?
`java src/Experiments.java 2`

**3.2** 200 riders on one corner, 200 free cars, 8 threads, and a matcher that
checks `is_free` and then writes. How many riders are promised a car that
somebody else also got? Guess a number, not a range.
`java src/Experiments.java 3`

**3.3** The whole fleet of 20,000 cars parks at one airport. The index still
works correctly. Within a factor of two, by how much does a nearby query slow
down?
`java src/Experiments.java 5`

**3.4** In `Sim.java` SCENE 7, D4's pin is 71 m from Arjun and every other car is
at least 316 m away. Which car gets the offer, and why? Then predict what changes
if you set `dispatch.ignorePingFreshness = true`.
`java src/Sim.java`

---

## Rung 4 — Modify

Each of these is a one-line change. For each: what breaks, why, and is the
property you lost **safety** (something wrong happens) or **liveness**
(something good stops happening)?

**4.1** In `Sim.java`, build the index as `new CellIndex(50, true)` — 50 m cells
instead of 500 m. Run `java src/Experiments.java 1` with the same change in
`scanVsIndex`. What happens to cells visited, and why does making the index
*finer* make the search *slower*?

**4.2** Set `dispatch.offerTtlMs = 2000` (2 seconds). Run `Sim.java`. What
happens to real drivers? Now set it to `300_000` (5 minutes) and reason about
what a crashed matcher costs you.

**4.3** Set `dispatch.pingTtlMs = 2000`. Run `java src/Experiments.java 4`. What
fraction of your fleet becomes unmatchable, and what real-world event would
trigger exactly this?

**4.4** In `Dispatch.matchOnce`, delete the `t.triedDrivers.contains(id)` check.
Then make a driver decline. What happens, and what is the name of this failure
mode in general?

---

## Rung 5 — Implement

**5.1 — the rider cancels.** Add `void cancelTrip(String tripId)` to `Dispatch`.
It must: release an `OFFERED` driver back to `AVAILABLE`; mark the trip
`CANCELLED`; do nothing at all if the trip is already `COMPLETED`; and be safe to
call twice. Add this test to `Tests.java` and make it pass:

```java
static void cancelling_releases_the_car_and_is_safe_to_repeat() {
    AtomicLong clock = new AtomicLong(1000);
    Dispatch d = fresh(clock, true);
    d.goOnline(new Driver("A", HOME.moved(100, 0), 1.0));
    Trip t = d.requestRide("r", HOME, HOME.moved(3000, 0), "k1");
    d.cancelTrip(t.id);
    d.cancelTrip(t.id);                                     // the retry
    boolean released = d.drivers.get("A").state.get() == DriverState.AVAILABLE;
    check("cancelling_releases_the_car_and_is_safe_to_repeat",
            released && t.state == TripState.CANCELLED, "driver=" + d.drivers.get("A").state.get());
}
```

Then answer: why must the release use `compareAndSet` rather than
`state.set(AVAILABLE)`? Name the concrete race.

**5.2 — cap the ranking.** Add `int maxRanked = 10` to `Dispatch` and stop
`matchOnce` from ranking more than that many candidates. Which experiment's
numbers change, and which stay identical? What quality do you lose, and in which
kind of neighbourhood do you lose the most?

---

## Rung 6 — Debug

[`src/BrokenIndex.java`](src/BrokenIndex.java) contains exactly one planted bug.

```bash
java src/Tests.java broken
```

**6.1** Which tests fail, and what is the single line (or missing line) that
causes both?

**6.2** Which invariant from README §13 does it violate?

**6.3** This bug would pass a code review and a manual test. Describe how it
would present in production after a week — what would riders complain about,
which dashboard would move first, and why would the obvious dashboards look fine?

**6.4** Write the smallest possible test that catches it — smaller than the two
that do.

---

## Rung 7 — Handle a failure the system does not yet survive

**7.1 — the crash between the claim and the answer.** Today, if dispatch claims a
driver and then dies before replying to the rider, the rider's app retries with
the same idempotency key. Trace what happens now, step by step. What is
guaranteed, and what is merely *likely*? Then design the fix: what must the
server store, at what moment, for the retry to be genuinely harmless? (Hint: at
what point in `requestRide` is the idempotency key written, relative to the
claim?)

**7.2 — the driver's app dies mid-trip.** The trip is `IN_PROGRESS`, the driver's
phone dies, the passenger is in the car and arrives 20 minutes later. The driver
reinstalls the app. What must the system do so the trip completes exactly once
and is charged exactly once? Which existing mechanism in this lab already covers
part of it, and what is missing?

**7.3 — degrade, don't fail.** The maps/ETA service becomes unavailable. Design
the fallback: what does the matcher rank by, what does the rider see, what do you
log, and how do you avoid a retry storm while it recovers? Which lab's toolkit
are you reaching into?

---

## Rung 8 — Design

Worked design discussions, not single answers. Write the answer you would give at
a whiteboard.

**8.1 — the airport.** Two thousand drivers wait at one airport; arrivals come in
bursts as flights land. The cell index measurably collapses here (22× slower, one
cell holding 6,399 cars). Design the airport: how are drivers indexed, how are
they matched, what do you show the driver, and what do you monitor? Which parts
of this lab's design survive and which must change?

**8.2 — three cities, one system.** Bengaluru, Mumbai, Delhi. Where do you shard,
what is the routing key, what happens to a trip that starts near a boundary, and
what does a region-wide outage in one city take down elsewhere? Justify the
consistency posture for each piece of state (index, driver state, trip record,
payments) using the CAP vocabulary from [`cap-theorem`](../24-cap-theorem/).

**8.3 — the 10× day.** New Year's Eve: 10× the requests, roughly the same number
of drivers. Rank your levers — surge, batching window, search radius, offer TTL,
load shedding, ping interval — by how much they help and how much they hurt.
Which single lever would you pull first, and what would tell you it worked?

**8.4 — the metric you would defend.** You must pick one number to define whether
dispatch is healthy, and it will be on a screen in front of your director. Which
number, why not the obvious alternatives, and what is the failure it would miss?
