# Redis — solutions

Try the exercises first. Rungs 7–8 are worked discussions, not single answers.

---

## Rung 1 — Explain

**1.1** Redis exists to hold **shared, hot, mutable state** for many machines at once.

- A `ConcurrentHashMap` inside your app is fast and correct — *for one process*. The
  moment you run two app servers they each have their own map and neither can see the
  other's. Your rate limiter now allows 2× its limit, and 20× when you autoscale to
  twenty. Thread-safety was never the problem; **process boundaries** were.
- A Postgres table is shared and correct, but every read-modify-write is a round trip
  through machinery designed to make facts permanent forever: WAL, MVCC, indexes,
  row locks. For a counter changing 10,000 times a second that is the wrong price,
  and worse, under contention your twenty servers **serialise on the row lock** — you
  have bought a distributed queue you did not want.

Redis is the middle: shared like the database, fast like the in-process map, and
explicit about what it gives up (durability, and queries beyond the key).

**1.2** Without the forbidden words: *only one thing ever happens at a time.* A
command is not a sequence of steps that other commands can get between — while one
command runs, no other command is running at all. So "read 5, add 1, write 6" has no
gap in it, because during those three steps nothing else in the entire system is
executing. There is nothing to exclude, because nothing else is there.

If you found yourself wanting to say "it's like an implicit lock around every
command" — that is the right intuition for the *effect* and the wrong one for the
*mechanism*, and the difference matters when you predict performance. A lock costs
something and can be contended. This costs nothing and cannot.

**1.3** Because "add one to the samosa jar" is only a sentence you can *say* if the
listener knows the jar holds a number. A shopkeeper who only knows "here is a jar,
here is a different jar" cannot do anything for you but hand it over and take it
back — which is exactly three trips and exactly the gap Arjun and Bina fell into.

Memcached is the shopkeeper who does not know what is in the jars. That is the whole
difference. The types are not sugar on top of the atomicity guarantee; **they are the
delivery mechanism for it.**

**1.4** Cache: a rendered product page under `page:sku:123` with a 60-second TTL.
Not a cache: a user's shopping basket, or the fencing counter `fence:invoice:42`.

The test, from the caching lab: **delete the whole store and see what you lost.**
Lose the cache and you lost speed — the pages regenerate. Lose the baskets and you
lost baskets; nothing else in the world knows what was in them. This is a decision
you make with `appendfsync`, `maxmemory-policy` and your replication setup — it is
not a property of the software.

---

## Rung 2 — Identify

**2.1** Whatever the screen says — the point is that it says it. The verdict band
reads *"N clients are stuck waiting. The thread is listing every key in the database,
and it will not start anything else until it finishes"*, and the log gives you the
sentence *"Client A finally gets served after waiting 23 ticks — it was ready the
whole time; the thread was busy."* If you had to guess at any of that, the page
failed.

**2.2** `Commands.incrBy()`, and the load-bearing part is **four lines**: read the
value, parse it, add, store. **Zero** of them mention concurrency. That absence is
the answer to 1.2 made physical — go and look at it, because it is genuinely
startling to see a lock-free multi-client counter with nothing in it.

**2.3** From `INFO`: `db0:keys=N` (resident), `aof_written_bytes` (handed to the OS),
`aof_fsynced_bytes` (actually durable).

The one that lies is `db0:keys` — like real Redis's `DBSIZE`, it counts keys that are
**resident**, including ones whose deadline has passed and which no longer exist as
far as any client is concerned. `aof_written_bytes` also misleads if you read it as
"safe": it is safe from `kill -9` and not from a power cut. Only the third number is
a durability statement.

**2.4** The header says `*3` — three arguments — and `HSET user:1 alice` is indeed
three. But `HSET` needs a field *and* a value, so this sets field `alice` to... 
nothing; there is no fourth argument. Real Redis replies
`-ERR wrong number of arguments for 'hset' command`. The framing is valid; the
command is not. **Well-formed and correct are different checks**, and RESP only does
the first.

**2.5** You cannot predict it, and that is the lesson. The policy is
`allkeys-lru`, but Redis does not maintain a true LRU order — it samples a handful of
keys at random and evicts the oldest *of that sample*. With 4 keys and a sample of 3
you will usually but not always get the genuinely-oldest one. Anyone who tells you
Redis "evicts the least recently used key" is describing the intent, not the
algorithm.

---

## Rung 3 — Predict

**3.1** `DBSIZE` returns **1**. `GET k` returns **nil**.

Both are correct simultaneously, and that is the whole point. Passing the deadline
makes a key **invisible**, not **absent**. With active expiry off, nothing has gone
looking for it, so it still occupies memory; `DBSIZE` reports residency. `GET` checks
the deadline first (lazy expiry), reports nil — *and that read is what finally frees
the memory*. Run `GET` then `DBSIZE` again and you get 0.

**3.2** The write **fails with an OOM error**, exactly as under `noeviction`.
`volatile-lru` will only ever consider keys that have a deadline; with no candidates
there is nothing to evict, so there is no way to make room.

It is nastier than `noeviction` because `noeviction` is what you *asked for* — you
chose it and you expect refusal. `volatile-lru` looks like an eviction policy, so the
instance appears configured to shed load, and it silently is not. The failure arrives
the day someone stops setting TTLs on new keys, which is a code change nobody
associates with a Redis outage.

**3.3** Replies: `1`, an error, `2`. And `good` is **2**.

The middle command failed and the ones around it committed anyway. **Redis has no
rollback.** `MULTI`/`EXEC` guarantees only that nothing else interleaved — it is a
batch, not a transaction in the ACID sense, and this is Redis's deliberate choice: an
error inside `EXEC` is treated as a programming bug, and supporting rollback would
tax every transaction to defend against one.

If you predicted `[1, error]` and an aborted batch, you were applying database
intuition. That intuition is exactly what makes this a production bug rather than a
trivia question.

**3.4** With `WATCH`: **one** succeeds, one gets nil and must retry. With `DECRBY`:
**both** succeed, and they are both correct — the command is atomic, so the second
operates on the first's result.

Both approaches are correct. The difference is cost, measured in experiment 9:

```
WATCH/MULTI/EXEC : 694 ms, 11,606 aborted attempts (4.8 per success)
INCR             :  23 ms,      0 aborted attempts
```

**30× slower, and it gets worse as contention rises**, because every loser redoes its
work. The rule: if one command already expresses what you want, use it — it cannot
lose a race and can never need a retry.

**3.5** All four cells, measured in experiment 6:

| | `kill -9` | power loss |
|---|---|---|
| `appendfsync always` | **0 lost** | **0 lost** |
| `appendfsync no` | **0 lost** | **everything since the last OS flush** (4,500 ms of traffic measured) |

The surprising cell is bottom-left. `kill -9` with fsync off loses nothing, because
Redis already called `write()` and the data sits in the **operating system's page
cache** — killing a process does not empty the kernel's memory. `appendfsync` defends
against the *machine* stopping, not the *process* stopping. Two very different events
with very different frequencies, and conflating them is how people end up paying for
`always` to solve a problem they did not have.

**3.6** You know: **one** replica definitely has the write, so a failover to *that
one* would preserve it.

You do not know: which replica, whether the other two will ever get it, or — most
importantly — whether the failover machinery will pick the one that has it. Sentinel
picks by replication offset, so it usually will, but "usually" is the operative word.

What you can do: nothing retroactive. Your client already has its `OK` and Redis has
no rollback. `WAIT` is a **measurement**, not a mechanism. What you can do
*prospectively* is set `min-replicas-to-write 2` so the master refuses writes it
cannot protect — which is you choosing CP for that instance, and accepting the
write-unavailability that comes with it.

---

## Rung 4 — Modify

**4.1** Measured on this machine (same workload as experiment 5):

| `maxmemory-samples` | hit ratio | gap to exact LRU |
|---|---|---|
| 2 | 70.4% | −5.3 |
| 3 | 72.3% | −3.4 |
| **5 (Redis default)** | **73.8%** | **−1.9** |
| 10 | 74.9% | −0.8 |
| 20 | 75.4% | −0.3 |
| 50 | 75.6% | −0.1 |
| exact LRU | 75.7% | — |

Redis defaults to 5 because the curve is steeply diminishing and **every sample is
CPU spent on the one thread that is also serving your traffic**. Going 5→10 buys 1.1
points; 10→50 buys 0.7 for five times the work. On an instance evicting thousands of
keys a second, sampling 50 keys per eviction is real latency for every client — you
would be spending the single thread, the scarcest resource in the whole design, to
chase a fraction of a percentage point.

Note also where the curve starts: even **2** samples gets you 70.4% against random's
66.6%. Almost all the value is in having *any* preference at all.

**Nothing about correctness changed** in any row. Eviction never returns a wrong
answer; it changes *which* answers require a trip to the source. This is a pure
efficiency dial, which is exactly why it is safe to leave alone.

**4.2** Roughly 4–5× more sweeps, because the "go round again if the sample was
mostly dead" rule is what lets one sweep clear a large backlog quickly. Experiment 4
needs 157 invocations with the rule; without it each invocation does one round of 20.

On a real instance running the sweep ten times a second, that is the difference
between clearing a 50,000-key backlog in seconds and in minutes — during which those
dead keys still count against `maxmemory` and can force **live** keys to be evicted.
Again: **liveness, not safety.** Nothing is ever wrong; things are late.

**4.3** **No.** This is the single most important "careful" answer in the lab.

Setting the lag to zero removes the *simulated* delay. It does not make replication
synchronous. The master still replies to the client and streams to the replica as two
separate acts in that order, so there is always a window — now microseconds instead
of 1,500 milliseconds, but never zero, and it widens under load, GC, network
congestion or a slow replica exactly when you are least able to notice.

The honest statement is: **the write is now very likely to survive, and still not
guaranteed to.** "Very likely" is a fine basis for a session and a poor one for a
payment. If you wrote "yes, it's fixed", re-read F5 — the difference between
narrowing a window and closing it is the entire content of that section.

**4.4** Experiment 6 **Part A** changes: with `flushAof()` running rarely, a `kill -9`
can now lose data, because bytes are still sitting in Redis's own in-process buffer
and have never reached the kernel. Part B is **unchanged** — power loss was already
losing everything past the last `fsync`, and the buffer was never the binding
constraint there.

This is the three-moments distinction made concrete: *buffer → `write()` → `fsync()`*.
Your change moved the first boundary and left the second alone. Real Redis flushes
its buffer every event-loop iteration precisely so that the first boundary is never
the one that hurts you.

---

## Rung 5 — Implement

**5.1 `GETEX`.** The interleaving that breaks `GET`-then-`PEXPIRE`:

```
A: GET session:9            -> "alice"        (still alive, 5 ms left)
                B: (the key's deadline passes; a reader reaps it)
                B: SET session:9 "bob" PX 30000
A: PEXPIRE session:9 30000                    <- A just extended BOB's session
```

A read a value, and then set a deadline on a *different* value it never saw. `GETEX`
closes it by making the read and the deadline one indivisible act. The general shape:
**any time you read a value and then act on the key based on what you read, you have
a gap** — and the gap is only safe if a single command spans it, or `WATCH` guards it.

**5.2 `INCRCAP`.** `GET` then `INCR` fails for the same reason as everything else in
§4: two clients both read 99 against a cap of 100 and both increment, and you are at
101 with both believing they were under.

`INCR` then `DECR`-if-over is the tempting fix and it is worse, not better. It is
*eventually* consistent to the right number, but in between the counter genuinely
exceeds the cap — so **any other client reading it during that window sees an
over-limit value and acts on it**. You have converted a lost update into a transient
lie, which is harder to reason about. If the cap is a rate limit, you have just
admitted a request you were supposed to reject.

The only correct answer is to do the comparison and the increment in the same
indivisible step — which is what `RATELIMIT` in `Commands.java` does, and what a Lua
script does in real Redis.

**5.3 `LMOVE`.** The reliable-queue property: the job is never in *neither* list. A
crash after `LMOVE` returns leaves it in `processing`, where a reclaimer can find it.

What is still missing before this is a production queue:
- **Reclaim by age**, and therefore a heartbeat or a timestamp per in-flight job — and
  once you have a timeout you have F7 again: a *slow* worker is indistinguishable from
  a dead one, and reclaiming its job means the job runs twice.
- **Therefore idempotent consumers.** At-least-once delivery is what you actually
  built; make the work safe to repeat.
- **A dead-letter path** for jobs that fail repeatedly, or one poison message loops
  forever.
- **Fairness and backpressure** across workers.

Which is a good moment to notice you are re-deriving Redis Streams (`XADD`,
`XREADGROUP`, `XACK`, `XAUTOCLAIM`) — they exist because everyone builds this and
everyone gets the reclaim wrong.

---

## Rung 6 — Debug

| bug | symptom | caught by | invariant broken (§12) |
|---|---|---|---|
| 1 — `EXPIRE` sent verbatim | master and replica disagree about when a key dies; measured **907 ms** of drift after one hop | `expire_is_propagated_as_an_absolute_pexpireat` | #6, propagation is deterministic |
| 2 — `INCR` skips the expiry check | `GET` says the key is gone, `INCR` resurrects it | **nothing catches it** | #3, an expired key is never visible |
| 3 — `SET` logged verbatim | live server says the lock is held by B; the recovered server says A | `set_is_propagated_without_nx_and_with_an_absolute_deadline` | #5 and #6, propagate the effect not the request |

**Why bug 1 is hard to find in production:** it is invisible until a failover, and
then it presents as "a few sessions expired at slightly the wrong time" — which
nobody files a ticket about, and which is indistinguishable from clock skew.

**Why bug 3 is hard to find:** the live server is completely correct. The divergence
exists only in a file, and only manifests on restart or failover — so it appears
during an incident, as a *second* incident, when nobody is looking for it.

**6.4 — why bug 2 is the quietest.** Because every command, examined alone, behaves
perfectly. `GET` correctly reports the key as gone. `INCR` correctly increments a key
it can see. There is no wrong answer anywhere to grep for, no error, no exception —
the defect exists only in the *disagreement between two correct-looking commands*,
and no single-command test can see a disagreement.

That is also why it does the most damage. A rate-limit counter that never resets
throttles a customer permanently, the `TTL` command reports the key as gone so the
first thing you check exonerates it, and every debugging session begins by ruling out
the wrong component.

**6.5 — the one the suite misses.** It is **bug 2**, and it passes all 25 tests.

Why the suite missed it: every test asserts *one command's* behaviour. Nothing asserts
that **two different commands agree with each other about the same key**. That is a
shape of test the suite does not contain at all, so no amount of adding more tests of
the existing shape would have found it.

The test to write:

```java
static void every_command_agrees_that_an_expired_key_is_gone(MiniRedis s) throws Exception {
    try (Cli c = new Cli(s.port)) {
        c.cmd("DEBUG", "SET-ACTIVE-EXPIRE", "0");
        c.cmd("SET", "k", "41", "PX", "50");
        c.cmd("DEBUG", "ADVANCE", "60000");
        eq("GET agrees it is gone",    null, c.cmd("GET", "k"));
        eq("EXISTS agrees",              0L, c.cmd("EXISTS", "k"));
        eq("TTL agrees",                -2L, c.cmd("TTL", "k"));
        eq("TYPE agrees",            "none", c.cmd("TYPE", "k"));
        eq("and INCR agrees -- it must start from zero, not resurrect 41",
                                         1L, c.cmd("INCR", "k"));
    }
}
```

(`DEBUG ADVANCE` moves the clock forward, and each assertion needs a fresh key
because the failing read reaps it — the second subtlety this exercise teaches.)

**This test is deliberately not in `src/Tests.java`.** The hole is the exercise;
shipping the patch would remove it. But it is not unverified — running it both ways
gives:

```
--- with the bug OFF ---   PASS  every_command_agrees_that_an_expired_key_is_gone   26 passed, 0 failed
--- with the bug ON  ---   FAIL  every_command_agrees_that_an_expired_key_is_gone   25 passed, 1 failed
```

That second line is the part that matters. **A test you have not watched fail is not
evidence of anything** — it may be asserting something that was never possible to
break, and a suite full of those is worse than no suite, because it buys confidence
without paying for it.

The transferable lesson: a green suite is a statement about your tests, not about
your code. The bugs that survive are the ones whose *shape* your tests do not have,
and you find those by asking "what kind of wrongness could not possibly show up
here?" rather than by writing more of what you already have.

---

## Rung 7 — Handle failure

### 7.1 — Making the lock safe

The correct release, which must be one atomic step (in real Redis, a Lua script;
here, a small native command):

```
if redis.call("GET", KEYS[1]) == ARGV[1] then
    return redis.call("DEL", KEYS[1])
else
    return 0
end
```

The interleaving that `GET`-then-`DEL` allows:

```
A: GET lock -> "A-token"    (A still owns it)
            A stalls 400 ms; the lock expires; B acquires it
A: DEL lock                 <- A has just deleted B's lock
```

A now believes it released its own lock, B believes it holds one, and the next
acquirer will collide with B. Note this is a *second* failure on top of F7: even a
client that correctly gives up can corrupt the system.

**The hard part — which resources can be fenced?** A resource can reject a stale
writer only if it can *remember the highest token it has honoured* and compare
atomically:

- **Can be fenced:** a Postgres row (`UPDATE ... WHERE fence < :token`), a
  DynamoDB conditional write, an object store with conditional-put/ETag semantics, any
  service you wrote that will accept a token parameter.
- **Cannot be fenced:** sending an email, charging a card through a provider with no
  idempotency key, `POST`ing to a third-party webhook, writing to a filesystem with no
  compare-and-set, calling a legacy SOAP service.

For the ones that cannot: **stop trying to make the lock correct, and make the
operation idempotent instead.** Give the work a stable identity — an idempotency key —
and have the far side deduplicate. Payment providers offer exactly this because they
know their callers cannot be trusted to hold a lock. If the far side offers nothing,
you record your own intent transactionally before acting and check it on retry, which
is the transactional-outbox pattern wearing a different hat. Redis's lock then reverts
to what it is genuinely good at: making the duplicate *rare*, so the deduplication
path is cold.

### 7.2 — The job queue that survives a dead worker

```
take:      LMOVE queue processing RIGHT LEFT      -- atomic; never in neither list
succeed:   LREM processing 1 <job>
reclaim:   anything in processing older than T goes back to queue
```

The design is correct for a **dead** worker and unavoidably wrong for a **slow** one,
because nothing can tell them apart from the outside. When the reclaimer moves a job
that is merely slow, that job now runs twice — concurrently, on two machines.

This is F7 exactly: *a timeout converts an unknown into a decision, and the decision
is sometimes wrong.* And it has the same resolution: you cannot fix it in the queue,
so make the job idempotent, or fence the resource it writes to. Choosing T only moves
the failure between "jobs stall for a long time" and "jobs run twice more often" — it
never removes it.

Production versions of this are Redis Streams' consumer groups with `XAUTOCLAIM`,
which give you the reclaim machinery and the delivery counter, and still leave
idempotency to you.

### 7.3 — Making the write survive

| option | what it actually buys | real cost | verdict |
|---|---|---|---|
| `WAIT n timeout` | tells you how many replicas have it | latency per write; **cannot un-acknowledge**, so the window is narrowed, never closed | necessary, insufficient |
| `min-replicas-to-write` | master refuses writes it cannot protect | write unavailability when replicas are unhealthy — you have chosen CP | genuinely useful, and an availability decision to make deliberately |
| `appendfsync always` | no local loss to power failure | **3.4× lower throughput**, measured | solves the wrong half — the machine is gone, not just powered off |
| AWS MemoryDB | a durable multi-AZ transaction log; writes are acknowledged only once committed | more expensive, higher write latency | the honest answer if you truly need Redis semantics *and* durability |
| don't put it only in Redis | Postgres is the source of truth; Redis is a derived copy | you now own a sync path | **the right answer for a payment** |

**For a payment:** the last row, without hesitation. `order:99 paid` is a fact about
money; it belongs in a store whose acknowledgement means committed. Redis can hold a
fast derived copy, kept in sync by the transactional-outbox pattern, and if that copy
is lost you rebuild it.

**For a session:** `min-replicas-to-write 1` plus asynchronous replication is fine.
The failure mode is "some users are logged out after a failover", which is a cost you
can accept because the recovery — logging in again — is cheap and well understood.

**The generalisation worth keeping:** the question is never "is Redis durable enough?"
It is "what does losing *this specific fact* cost, and is that less than what
protecting it costs?" The same instance can hold facts on both sides of that line —
which is exactly what the `volatile-*` eviction policies are for.

---

## Rung 8 — Design

### 8.1 — Session store, three datacentres

**Sizing.** 50M × 2 KB = 100 GB of payload; with per-key overhead and fragmentation,
budget **~140–160 GB**, plus headroom for `fork()` during snapshots (F4), so
**~200 GB of RAM**. That does not fit one machine comfortably, so this is one of the
genuine cases for **Redis Cluster** — say 6 shards of ~35 GB, each with one replica.
40k reads/sec across 6 shards is unremarkable; a single Redis does far more.

**Cross-DC replication: no.** Sessions are read on almost every request, so a 60 ms
cross-DC hop on the read path is unacceptable. Run **an independent cluster per DC**
and pin users to their nearest one via the load balancer. Do *not* try to replicate
sessions across DCs synchronously — you would be paying 60 ms to protect data whose
loss costs a re-login.

**When a DC goes dark:** its users are routed to another DC, find no session, and log
in again. That is the deliberate trade, and the thing to verify is not Redis but
whether your **login path can absorb a third of your users at once** — which is where
this design actually fails if it fails. Test that, not the Redis failover.

**What survives from this lab unchanged:** the single-threaded execution model, TTLs
as the expiry mechanism (a session TTL is refreshed on each request), lazy plus
sampled expiry, and asynchronous replication within a DC.

**What must change:** multi-key operations are gone (keys land in different hash
slots), so no `MGET` across sessions unless you hash-tag them; you need a
cluster-aware client; and resharding becomes an operation you must have rehearsed.

**Three alerts:** (1) `used_memory_rss` approaching machine memory on any shard —
remember `fork()` can nearly double it; (2) replication offset lag beyond your
tolerated loss window; (3) p99 latency per shard, because one hot shard is invisible
in an average.

### 8.2 — "Top 100 products this hour"

`ZINCRBY leaderboard:2026-08-28T14 1 <product>` is correct and it is one command, so
it cannot lose an update. The wall it hits is **not** memory and **not** the sorted
set's O(log n) — it is that **the whole hour's leaderboard is one key**, so it lives
on one shard, on one thread. 200,000 writes/sec to one key is one core's problem, and
**adding shards does nothing at all**, because sharding distributes keys and there is
only one key. That is F8 exactly.

The fix has two independent halves:

1. **Split the key.** `leaderboard:<hour>:<0..15>`, chosen by `hash(product) % 16`.
   Now 16 shards share the write load, and a read merges 16 sorted sets — which is
   cheap, because you only need the top 100 from each.
2. **Do not send every event.** Aggregate in the application for 1–2 seconds and send
   one `ZINCRBY` per product per window. At 200k events/sec over a few thousand
   distinct products this is a 100× reduction, and it is the change that actually
   matters. Pipelining the batch makes it one round trip.

**What accuracy you trade:** the leaderboard lags by your aggregation window, and a
crashing app instance loses its unflushed local counts. For "top 100 products this
hour" both are irrelevant — you are ranking, and the ranking is stable long before
the counts are exact.

**What to tell the product manager:** not "no". Say that the numbers are exact within
about two seconds and that the *ranking* is what the feature is for; then ask what
decision the exact count would change. If the honest answer is "billing" or
"a payout", this is the wrong store and the events belong in a durable log with a
batch job behind it — which is a different feature with a different cost, and worth
naming as such rather than discovering later.

### 8.3 — The two-paragraph reply

> Moving the basket to Redis would genuinely make it faster, and the reads probably
> are our bottleneck, so I don't want to dismiss it. The thing that worries me is what
> "acknowledged" means in each store. Postgres tells us a write is committed only once
> it is durable; Redis acknowledges immediately and becomes durable up to a second
> later, and on a failover it loses whatever had not yet reached the replica — which
> in our setup is unbounded, because replication is asynchronous. So the failure mode
> is not "the basket is slow", it is "the basket silently loses the item the customer
> just added", during a failover, which is exactly when we are least able to notice.
> For a basket that turns into an order, I don't think we want that.
>
> What I'd suggest instead: keep Postgres as the source of truth and put a
> **read-through cache** in Redis with a short TTL, invalidated on write. That gets us
> the read speed — which is where the pain actually is — without changing what a
> committed basket means. If writes turn out to be the bottleneck too, the next step
> is AWS MemoryDB, which is Redis-compatible with durable multi-AZ writes; it costs
> more and is slower on writes, but it is the one option that gives us both. Shall I
> measure the read/write split first so we know which problem we are actually solving?

The shape worth copying: agree with the part that is right, name **one** specific
failure with its mechanism, quantify it, and end with a concrete proposal and a
question — not a refusal.

### 8.4 — Reading the incident

The `INFO` you were given has already ruled things out: `evicted_keys: 0` and memory
at 60% means this is **not** memory pressure; healthy replication offset means it is
**not** a struggling replica. What is unusual is **1,200 connected clients**.

1. **`SLOWLOG GET 25`** — first, always. If one command is taking hundreds of
   milliseconds, everything else queues behind it (F1) and you have your answer in one
   step. Look for `KEYS`, `HGETALL`, `SMEMBERS`, `LRANGE 0 -1`, a large `DEL`, or a
   slow script. **Rules in:** head-of-line blocking. **Rules out:** everything else,
   if it is empty.
2. **`redis-cli --latency`** and `INFO commandstats` — separates "one command is slow"
   from "everything is uniformly slow". Uniform slowness with a clean SLOWLOG points
   away from Redis and toward the network, a noisy neighbour, or the client side.
   **`INFO clients`** matters here too: 1,200 connections is a lot, and a client
   library that has lost its connection pool and is opening a connection per request
   produces exactly this picture — latency that is real but not Redis's fault.
3. **`INFO persistence`** — is `rdb_bgsave_in_progress` or an AOF rewrite running? A
   `fork()` on a large instance stalls the process for the duration of the copy, and
   it is periodic, which fits "was 2 ms, now 400 ms" better than a code change does.
   Check `latest_fork_usec`.

**The meta-point:** each command should *eliminate* a class of cause, not just gather
information. If your first three commands cannot each rule something out, you are
collecting data rather than diagnosing — and on a 400 ms p99 you do not have time for
that.
