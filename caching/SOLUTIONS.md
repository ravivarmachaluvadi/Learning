# Solutions

Every number here was produced by running the code in this folder. Where a
solution required a variant of the lab (a changed parameter), the command
that produces it is given so you can reproduce the figure yourself.

---

## Rung 1 — Explain

**1.1** A website is fast when it stops re-answering questions it has
already answered. The menu page asks the database for the same twelve
dishes fifty thousand times a lunchtime; if the app keeps the answer in
memory after the first ask, the other 49,999 visitors get it in microseconds
instead of milliseconds, and the database is left free to do work that
actually differs.

The trouble is that the kept answer is a *copy*, and copies do not find out
when the original changes. When the kitchen marks a dish sold out, the
database knows immediately and the copy does not — so for as long as that
copy is still being handed out, the site is confidently showing something
untrue. Everything difficult about caching comes from that one gap, and the
engineering job is to make the gap short and known rather than long and
accidental.

**1.2** The test: **can you delete the whole thing and lose nothing but
speed?** If yes, it is a cache. If deleting it loses data, it is a database
you are running without a database's care (no backups, no durability, an
eviction policy silently discarding rows). It matters operationally because
it decides what you are allowed to do during an incident: you may flush a
cache to resolve corruption, fail it over casually, and let it lose
everything on restart. Do that to something that was secretly a database and
you have caused a data-loss incident. This is also exactly what makes
write-behind (stage 10.4d) dangerous: it turns a cache into the only copy
for a window.

**1.3** Because people do not use software evenly. Out of a hundred thousand
dishes, the same twelve get looked at all lunchtime; out of a million
customers, the ones ordering right now are a tiny fraction. Demand is
concentrated, so a small store of the currently-popular answers covers most
of the asking. If every item were equally likely to be requested, keeping
10% of them would satisfy only 10% of requests and the whole idea would
collapse — as exercise 3.1 measures.

## Rung 2 — Identify

**2.1** With LRU, the evicted entry is the one you touched longest ago — not
the oldest-inserted, and not the least useful. Under LFU the answer usually
differs: an entry you inserted long ago but have hit many times survives,
while a recently-inserted, rarely-hit entry goes. That divergence is the
whole content of the policy debate.

**2.2** `LruCache.get`:

```java
unlink(n); linkAtHead(n);   // every hit promotes to most-recent
```

`FifoCache.get` deliberately does not do this. It is worth ~4.5 points
(measured: LRU 67.3% vs FIFO 62.8% on workload A) because it changes what
the cache is *measuring*. FIFO measures age since insertion, which is
uncorrelated with future usefulness; LRU measures time since last use, which
is a genuine predictor. One line converts a meaningless signal into a
meaningful one.

**2.3** In `runStampede`:

```java
CompletableFuture<Integer> existing = inFlight.putIfAbsent(1, mine);
if (existing != null) return existing.join();
```

`putIfAbsent` returns non-null only for threads that lost the race. Those
199 losers do not call the database; they block on the winner's future and
receive the winner's result. One load, 200 satisfied callers.

**2.4** Write-behind. The giveaway is "acknowledge immediately, flush to the
database asynchronously" — between the acknowledgement and the flush, the
cache holds the only copy of a write the user was already told succeeded, so
a crash in that window loses acknowledged data.

## Rung 3 — Predict, then run

**3.1** Under uniform access, holding 10% of keys gives a **~10%** hit
ratio — the cache is nearly worthless, because there is no such thing as a
popular key. Measured under realistic skew: **67.3%**. The gap between 10%
and 67.3% is the working set doing its job, and it is the entire business
case for caching: *caching converts skew into speed, and where there is no
skew there is nothing to convert.*

**3.2** The predictable ranking is LRU ahead of FIFO and Random; the
interesting question is LFU. Measured (`java CachingLab.java eviction`,
column C — the hot set moves halfway through):

```
FIFO 66.7%   LRU 74.9%   LFU 48.9%   Random 66.6%
```

LFU is last, and by a wide margin — 26 points behind LRU, and worse than
Random. It wins columns A (75.2%) and B (75.1%) and loses C decisively,
because accumulated counts from the old hot set block the new one from ever
being admitted: **cache squatting**. If you predicted "LFU wins because it
won the other two columns," this is the instructive failure — a policy is a
bet about how the future resembles the past, and a shifting workload
breaks that bet.

**3.3** Measured: **200 database calls without protection, 1 with
single-flight.** If you predicted a number between 1 and 200 you were
reasoning about timing; the honest answer is that *without* coordination it
is exactly as many as there are concurrent missers, because nothing tells
any of them that another is already loading.

**3.4** Yes — the cache can hold the old price despite a correct delete.
Measured: cache says ₹100, truth says ₹120. The interleaving:

1. Reader misses, fetches ₹100 from the database.
2. Reader stalls (GC pause, slow network — anything at all).
3. Writer sets ₹120, commits, deletes the cache key. *Correct invalidation.*
4. Reader wakes and writes its ₹100 into the now-empty cache.

The delete happened *before* the fill it needed to prevent, so it
accomplished nothing. No line of code is wrong; the ordering is. This is the
**dual write** from your [outbox](../transactional-outbox/) lab: two stores,
no shared transaction, therefore an interleaving where they disagree.

## Rung 4 — Modify

**4.1** Change the skew from `1.0` to `0.5` (flatter popularity). Measured:

| cache size | skew 1.0 | skew 0.5 |
|---|---|---|
| 100 | 38.8% | 2.4% |
| 1000 | 67.3% | **17.8%** |
| 5000 | 88.7% | 61.0% |

At 1,000 entries the hit ratio collapses from 67.3% to 17.8%, and average
latency goes from 1.670 ms to 4.117 ms — against 5 ms with no cache at all.
The cache has become nearly pointless while still costing memory, a network
hop, invalidation complexity, and a new failure mode.

The lesson is a selection rule: **the workload decides whether a cache is
worth having, and skew is the property to measure.** Before adding a cache,
look at your access distribution — if the top 1% of keys is not taking a
large share of traffic, you are buying complexity for a rounding error.
(Uniform-access workloads — random UUID lookups, one-shot report queries —
are the classic case where caching disappoints.)

**4.2** Change the eviction scenario's `size` from 1000 to 100. Measured:

| policy | A (size 1000) | A (size 100) |
|---|---|---|
| FIFO | 62.8% | 34.1% |
| LRU | 67.3% | 38.8% |
| LFU | 75.2% | 52.6% |

LFU's advantage over LRU **grows from 7.9 to 13.8 points**. The general
rule: policy choice matters most when the cache is *small relative to the
working set*, because that is when eviction decisions are frequent and
consequential. When the cache comfortably holds the working set, almost
nothing gets evicted and every policy converges to the same (high) number —
which is why "which policy?" is often the wrong first question. The right
first question is "is the cache big enough to hold the working set?", and
only if the answer is no does policy become decisive.

(Note column C at size 100: everything collapses to ~8%, because the moving
hot set is 1,000 keys and the cache holds 100 — no policy can cache a
working set that does not fit. Another way to see that sizing dominates.)

**4.3** Removing `inFlight.remove(1)` leaves the completed future in the
in-flight map forever. The *first* expiry still works perfectly — this is
what makes the bug nasty. On the **second** expiry of that key, every
arriving request finds the stale future via `putIfAbsent`, joins it, and
receives **the value loaded during the previous expiry cycle** — instantly,
forever, with a 100% "hit rate" on a value that never refreshes again. You
have created a permanently stale entry that no TTL can rescue (the TTL
expires the cache entry, but the loader never runs again), plus an unbounded
map of dead futures. This is stage 11's **F3 (unbounded growth)** and a
worst-case **F2 (staleness)** in one line — and note that it would look
*excellent* on a hit-ratio dashboard, which is why staleness needs its own
detection, not just performance metrics.

## Rung 5 — Implement

**5.1** A workable shape (LRU with per-entry deadlines):

```java
static class TtlCache {
    record Entry(int value, long expiresAtNanos) {}
    final int capacity; final long ttlNanos;
    final LinkedHashMap<Integer, Entry> map = new LinkedHashMap<>(16, 0.75f, true); // access-order = LRU
    TtlCache(int capacity, long ttlMillis) {
        this.capacity = capacity;
        this.ttlNanos = ttlMillis * 1_000_000L;
    }
    Integer get(int key) {
        Entry e = map.get(key);
        if (e == null) return null;
        if (System.nanoTime() >= e.expiresAtNanos()) {   // expiry is about AGE (correctness)…
            map.remove(key);
            return null;                                  // an expired entry is a MISS, never a stale hit
        }
        return e.value();
    }
    void put(int key, int value) {
        map.put(key, new Entry(value, System.nanoTime() + ttlNanos));
        if (map.size() > capacity) {                      // …eviction is about SPACE, and still applies
            Iterator<Integer> it = map.keySet().iterator();
            it.next(); it.remove();                       // LinkedHashMap access-order: eldest = LRU victim
        }
    }
}
```

The three tests should assert: a `get` after the deadline returns `null`
(use a 50 ms TTL and sleep 60 ms); an expired entry does not count as a hit
(check your hit counter, not just the return value — this is the bug people
actually ship); and inserting `capacity + 10` fresh entries leaves
`map.size() == capacity`. The point of the third test is that TTL and
eviction are *independent* mechanisms — adding expiry must not quietly break
the capacity invariant.

**5.2** Jitter: `expiresAt = now + ttl ± random(0, 0.1 × ttl)`. The test
creates 1,000 entries in a tight loop and asserts that their expiry
timestamps span a range of at least (say) 5% of the TTL — i.e. they do not
all land in the same instant. Why it matters: identical TTLs set at the same
moment (a deploy, a cache warm, a bulk import) expire together and produce a
self-inflicted stampede on every one of those keys simultaneously. Same
reasoning as jittered retries in the
[architecture lab](../software-architecture-patterns/) — synchronized
clients are the danger, and randomness is the cure.

## Rung 6 — Debug

**The bug:** in `BuggyLruCache.put`, the eviction branch calls
`unlink(victim)` but never `map.remove(victim.key)`. The fix is one line:

```java
Node victim = tail.prev;
unlink(victim);
map.remove(victim.key);   // ← missing
```

**Which invariant:** the **capacity invariant** (`size ≤ capacity`) from
stage 12 — the one property that makes a cache bounded rather than a memory
leak.

**Why the damage is worse than one leaked node** (the second clue in the
program's output): the eviction branch is guarded by
`if (map.size() == capacity)`. Once the map grows past capacity without
being trimmed, that equality is **never true again**, so eviction stops
entirely. Measured with capacity 3 and 100 inserts: the map holds 100
entries and the list holds 99. The cache degenerates into an unbounded map
after a single failed eviction.

**Production symptom:** the process's heap grows steadily under normal
traffic. Long before an `OutOfMemoryError`, GC pauses lengthen, so the
service's p99 latency climbs and periodically spikes — engineers usually
chase this as "a GC problem" or "a memory leak somewhere," because the cache
looks healthy by every cache metric. Customers see occasional slow requests,
then a restart-and-recover cycle every few hours.

**Would a hit-ratio dashboard catch it?** **No — and this is the exercise's
real lesson.** The hit ratio would look *fantastic and improving*, because
nothing is ever evicted: every key ever requested stays cached forever. The
metric that catches it is **cache size / entry count** (or heap usage), and
the alert you want is on the *invariant* (entries ≤ configured maximum), not
on performance. Performance metrics cannot detect a bug whose symptom is
"too much caching."

## Rung 7 — Handle failure

**7.1** Invalidation over an at-least-once, possibly-delayed, possibly-lost
event stream. Four mechanisms, each protecting against one named thing:

- **Duplicates are harmless by construction — use delete, not update.**
  "Delete key `dish:42`" is idempotent: applying it twice is identical to
  once. (Had you published "set dish:42 = ₹120," two deliveries could land
  out of order and leave the older value resident permanently. This is stage
  10.4's *prefer invalidate to update*, and it is why the delivery guarantee
  from your outbox lab shapes the cache design.)
- **Delayed events cannot resurrect stale data — version the payload.**
  Carry the row version in the event and in the cache entry; a fill or
  delete whose version is older than what the cache holds is ignored. This
  is exactly the F2 mitigation, applied to the invalidation path rather than
  the fill path.
- **A dropped event cannot be wrong forever — TTL.** This is the load-
  bearing reason every entry gets a TTL even with "perfect" invalidation:
  it converts *silently wrong forever* into *wrong for at most N seconds*.
  Invalidation optimizes the common case; the TTL bounds the worst case.
- **Detect the drops — reconcile.** A periodic job samples cached keys
  against the source of truth and reports disagreements as a metric
  (the drift/reconciliation idea from your
  [database-landscape](../database-landscape/) lab). Without it, dropped
  invalidations are invisible; with it, you can alert on a rising
  disagreement rate before customers do.

**7.2** Three app servers with in-process caches plus a shared Redis. For a
price change to take effect everywhere:

1. The writer commits the price to the database (source of truth).
2. It deletes the key in Redis — one delete, one shared copy handled.
3. Each app server's **local** copy must also go. Local caches cannot be
   deleted by the writer, so you need either (a) a pub/sub invalidation
   message all three servers subscribe to, or (b) — much more common and
   more honest — a **very short TTL on the local tier** (a few seconds),
   accepting that L1 is deliberately allowed to be slightly stale.

**Worst-case staleness** = local TTL + pub/sub delivery delay (if used), or
just the local TTL if you rely on expiry. With a 5-second L1 TTL, a customer
can see the old price for up to 5 seconds after the change — say that number
out loud and get it agreed, because it is a product decision.

**If Redis is down for 30 seconds:** with cache-aside, every miss falls
through to the database, so the system stays *correct* and gets *slow* — and
the database receives roughly the full uncached load (stage 11's F6). What
the app should do: (a) keep serving from L1 where it can, (b) apply a
timeout to Redis calls so a slow Redis does not become slower than the
database itself (a hung cache call is worse than no cache — you pay it
*before* the database call), (c) use a circuit breaker so calls to a dead
Redis fail instantly instead of timing out per request, and (d) rely on
single-flight so the database sees one load per key rather than one per
request. That combination is the resilience family from your
[architecture lab](../software-architecture-patterns/) applied to the cache
tier — and it is why "cache down" should be a degraded mode, not an outage.

## Rung 8 — Design

**8.1** A defensible design. Judge yours on the *reasoning*, not on matching
these choices.

| Data | Cache? | Where | TTL | Invalidation | Notes |
|---|---|---|---|---|---|
| Menus | Yes — the ideal case | L1 (in-process) + L2 Redis | L1 5 s, L2 5 min | On kitchen publish | Tiny, hot, changes ~daily: this is what caches are for |
| Dish availability | Yes, carefully | L2 Redis only | 10–30 s | Event on every stock change | Short TTL because wrongness has a direct customer cost (ordering sold-out food) |
| Customer profiles | Yes | L2 Redis | 15 min | On profile write | Big keyspace, moderate skew; per-customer keys, watch memory |
| Rider GPS | **No** | — | — | — | See (a) below |
| Prices at checkout | **Not on the checkout path** | Cache for *display*, read from source of truth at charge time | Display 30 s | On price change | See (b) below |

**(a) What I would refuse to cache: rider GPS positions.** Every write
invalidates the value, so the hit ratio approaches zero while you pay full
cost — a cache is only worth having when reads-per-write is high, and here
it is near 1. Worse, the whole value of that data is *freshness*: a cached
position is a wrong position. Write-heavy, low-reuse, freshness-critical
data is the canonical do-not-cache profile. (Use a store built for the write
pattern instead — the time-series direction from your
[database-landscape](../database-landscape/) lab.)

**(b) Worst-case staleness for price at checkout: zero, by design.** The
defense to Meera is not a number, it is a *split*: cached prices are fine
for browsing (a 30-second-old price on the menu page is harmless and saves
enormous load), but the price used to **charge a card must be read from the
source of truth inside the same transaction that creates the order.** A
cache is a replica, a replica can be stale, and a stale price at checkout is
a billing dispute — an incident class with legal and support cost, not just
an unhappy refresh. This is the general rule the exercise is testing:
**cache for display, never for the authoritative check** — the same rule
that made the flash-sale design in the architecture lab wrong.

**(c) The cache tier dies at 12:30.** What happens: every read falls through
to the database, which is receiving roughly 3× its accustomed load (from the
67.3% hit ratio — the database has been sized against the *cached* load all
along). What must exist in advance: single-flight so simultaneous misses
collapse to one load per key; a Redis timeout short enough that a *slow*
Redis doesn't add latency on top of the database call; a circuit breaker so
a dead cache is skipped instantly; load shedding by priority (browsing sheds
before checkout); an L1 in-process cache that keeps serving the hottest keys
with no Redis at all; and honest capacity planning — know what fraction of
uncached load the database can survive, because "the cache is never down" is
not a plan. Warm caches before returning traffic; never flush at peak.

**8.2** "We added Redis and p99 got worse." Four mechanisms, each with its
confirming measurement:

1. **Low hit ratio** — nearly every request now pays cache latency *plus*
   database latency. The stage-8 equation with a 5% hit ratio and a 1 ms
   cache is strictly worse than no cache at all. *Measure:* hit ratio. If it
   is low, the workload has no skew (exercise 4.1) or the TTL is too short.
2. **Serialization cost** — the cached objects are large, and
   serialize/deserialize costs more than the query did. Common with fat
   object graphs; the cache "hit" is slower than the database "miss."
   *Measure:* time the serialize/deserialize step separately from the
   network round trip, and compare payload size against query cost.
3. **Cache latency in the tail** — Redis p99 is far worse than its p50
   (a noisy neighbor, a slow `KEYS`-style command blocking the single
   thread, network jitter, or an undersized connection pool causing
   queueing). Because the cache is on *every* request path, its tail becomes
   your tail. *Measure:* Redis-side p99 latency and connection-pool wait
   time, not just averages.
4. **Stampedes / hot key** — periodic expiry of hot keys produces bursts
   where many requests queue behind one load (or one Redis node saturates).
   The p50 looks fine, the p99 is made of those bursts. *Measure:* database
   load correlated with TTL boundaries; per-node cache CPU for skew.

A fifth, worth naming because it is common: **the cache became a network
hop for data that was already in memory** — replacing a local in-process
cache with a remote one always adds latency, and only pays off if the shared
hit ratio is much higher. *Measure:* compare L1-only against L1+L2.
