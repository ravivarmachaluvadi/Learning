# Exercises — the eight-rung ladder

Attempt each rung before opening [`SOLUTIONS.md`](SOLUTIONS.md). Rungs 3–6
are runnable against `CachingLab.java` and `BrokenCache.java` in this
folder — predict first, then run; being wrong before you run is where the
learning is.

---

## Rung 1 — Explain (your own words, no notation)

**1.1** Explain to a non-engineer why a cache makes a website fast, and why
the same trick is what makes a website occasionally show you something that
is no longer true. Two short paragraphs.

**1.2** What is the one test that tells you whether the thing you built is a
cache or a database? Why does that test matter operationally?

**1.3** Why does caching work *at all*? Answer without using the word
"cache" — the answer is a fact about how people use software, not about
software.

## Rung 2 — Identify

**2.1** Open `visualization.html`. Send enough reads to fill the cache, then
send one more. Which entry was evicted, and which rule chose it? Switch the
policy to LFU and repeat: does the same entry go?

**2.2** In `CachingLab.java`, find the single line that makes `LruCache`
behave differently from `FifoCache`. Why is that one line worth ~4.5 points
of hit ratio?

**2.3** In the stampede scenario, find the line that makes 199 of 200
threads *not* call the database. What does the loser of that race do
instead?

**2.4** Look at the four write strategies in stage 10.4. Which one can lose
data that was already acknowledged to the user, and which line of its
description tells you that?

## Rung 3 — Predict, then run

**3.1** A cache holds 1,000 of 10,000 keys — 10% of the keyspace. Predict
the hit ratio if access were perfectly uniform (every key equally likely).
Then predict it for realistic skewed access. Run `java CachingLab.java
hit-rate` and compare with the measured 67.3%. Explain the gap in one
sentence.

**3.2** A workload's popular keys change every few hours (a news site, a
menu that rotates). Predict the ranking of FIFO / LRU / LFU / Random. Run
`java CachingLab.java eviction` and read column C. Were you right about
LFU?

**3.3** 200 requests arrive for one just-expired key. Predict the number of
database calls with no protection, and with single-flight. Run
`java CachingLab.java stampede`.

**3.4** A writer updates a price and correctly deletes the cache key.
Predict: can the cache still end up holding the old price? Run
`java CachingLab.java stale-race` and explain the interleaving that caused
it.

## Rung 4 — Modify

**4.1** In `runHitRate`, change the skew parameter of `SkewedKeys` from
`1.0` to `0.5` (flatter — popularity spread more evenly) and re-run. What
happens to the hit ratios, and what does that tell you about which
*workloads* deserve a cache at all?

**4.2** Change the eviction scenario's cache size from 1,000 to 100 and
re-run. Which policy's advantage grows, and why does cache size interact
with policy choice?

**4.3** In `runStampede`, remove the `inFlight.remove(1)` in the `finally`
block. What breaks — immediately, or only on the *second* expiry of that
key? Which failure mode from stage 11 have you just created?

## Rung 5 — Implement

**5.1** Add a `TtlCache` to `CachingLab.java`: entries carry an expiry
timestamp; `get` returns `null` for an expired entry (and removes it);
capacity and LRU eviction still apply. Then add tests that must pass:
`ttl_entry_expires_after_deadline`, `ttl_expired_entry_is_not_a_hit`, and
`ttl_does_not_break_capacity_bound`.

**5.2** Extend it with **jitter**: given a base TTL of 60 s, each entry gets
`60 s ± 10%`. Write a test proving that 1,000 entries created in the same
millisecond do not all expire in the same second.

## Rung 6 — Debug

**6.1** `BrokenCache.java` in this folder contains an LRU implementation
with **one** planted bug. Run `java BrokenCache.java` to see the failing
check. Then answer, before fixing: which invariant from stage 12 does it
violate, what would the production symptom be (be specific — what would a
customer or an on-call engineer actually see?), and would a hit-ratio
dashboard catch it?

## Rung 7 — Handle failure

**7.1** Your invalidation is delivered as an event ("dish 42 changed") from
the [outbox](../48-transactional-outbox/) relay. Events are **at-least-once**
and can be **delayed**. Design the cache invalidation so that: a duplicate
invalidation is harmless, an invalidation delayed by 10 seconds does not
resurrect stale data, and a *dropped* invalidation cannot leave the cache
wrong forever. Name every mechanism you use and what specifically each one
protects against.

**7.2** TiffinBox has 3 app servers, each with an in-process cache, plus a
shared Redis. A price changes. Walk through exactly what must happen for
all three servers to stop serving the old price, and state the worst-case
staleness of your design. Then answer: what breaks if Redis is down for 30
seconds — and what should the app do during those 30 seconds?

## Rung 8 — Design

**8.1** Design the caching tier for TiffinBox at 3M orders/day, 40 cities,
lunchtime peak 20× baseline. The data: menus (change ~daily, read
constantly), dish availability (changes every few minutes, must be nearly
correct or customers order sold-out food), customer profiles (rarely
change), rider GPS positions (2,000 writes/sec, read by the tracking
screen), and prices (change rarely, must never be shown wrong at checkout).

For **each** of those five, decide: cache or not; where (in-process, Redis,
both); TTL; invalidation strategy; eviction policy; and what happens on a
miss storm. Then answer the three questions that make it a design and not a
list: (a) which of the five would you refuse to cache, and why; (b) what is
your worst-case staleness for *price at checkout*, and how do you defend
that number to Meera; (c) the cache tier goes down entirely at 12:30 — what
happens, and what did you build in advance so the answer isn't "everything
falls over"?

**8.2** (Reverse) A team reports: "we added Redis and p99 latency got
*worse*." Give at least four distinct mechanisms that could produce that
result, and for each, the single measurement that would confirm or rule it
out.
