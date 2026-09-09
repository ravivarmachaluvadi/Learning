# JVM Performance Tuning — Solutions

Numbers marked **[MODEL]** come from the simulator in `visualization.html`, run
headlessly. **[HAND]** means worked from stated rules — check the arithmetic
yourself. **[JVM]** is documented real-JVM behaviour, not measured here.

Your numbers on the page will match these exactly: the engine is deterministic,
so the same settings always produce the same run.

---

## Rung 1 — Explain

**1.1** Performance tuning is finding out **where a request's time actually
goes** and then changing the one thing that dominates it. "Add some JVM flags"
is usually wrong because a flag can only change how the JVM spends time it is
already spending — and in most services the largest block is something no flag
touches (waiting on a downstream call, waiting for a free thread, waiting for
one lock). You have to know which block is biggest before any flag can be the
right answer, and once you know, the answer is often not a flag at all.

**1.2** Suppose 97 customers are served in 40 ms and 3 wait 2 seconds. The
average is about 99 ms, which describes nobody: not the 97, and certainly not
the 3. Worse, the average is *stable* — you can double the number of 2-second
waits and the average barely twitches, so an alert on it fires long after the
service has become unusable. Alert on a **percentile** instead: p99 says "one
customer in a hundred waits at least this long", which is a sentence about a
real person. Keep the mean only for capacity arithmetic, where it is exactly
the right number.

**1.3** *At any moment there is exactly one bottleneck, and every change to
anything else buys nothing.* It implies that "add more memory and see if it
helps" is not a plan but a lottery ticket — and one you can check for free
before buying, by looking at how much of the request is currently spent on
memory-related work. On the lab's scenario 1 that share was **0.0 ms out of
117.6 ms**, so quadrupling the heap moved p99 from 1,327 ms to 1,327 ms [MODEL].

**1.4** A CPU profiler samples threads **while they are executing on a core**.
A thread asleep in a socket read is not on a core and produces no samples at
all. So for a handler doing 6 ms of work around a 180 ms database call — **4.5%
of its elapsed time on-CPU** [MODEL] — the profiler's output is a perfectly
accurate description of 4.5% of the problem, and nothing in the output says so.

---

## Rung 2 — Identify

**2.1** [MODEL] On the defaults the widest bucket is **waiting on the database,
25.0 ms**, with *running your code* at 19.9 ms just behind it. Median **41 ms**,
p95 42 ms, p99 **248 ms**.

**2.2** [MODEL] On **Slowest 5%** it flips: *running your code* is **93.7 ms**
and the database is still 25.0 ms. The sentence: **the typical request is slow
because it waits; the slow requests are slow because they compute** — a small
share of requests need several times the usual CPU, and that population, not
the queue and not the collector, is what makes this p99. Two different
diagnoses from one run, which is why the toggle exists.

**2.3** The **Handler threads** row: `busy / total` on the right, the bar
showing what share are busy. Read it together with the row beneath it, *Waiting
for a thread*, which is the queue depth. The pool is too small when **all
threads are busy and the queue is non-zero and growing** — either number alone
proves nothing. All threads busy with an empty queue is a pool being used
efficiently.

**2.4** Whatever it says — the point is that the sentence names a phase and
gives you the per-bucket split for that one request. Off-CPU is everything
except *running your code* and *code not yet compiled*: the database wait, the
lock wait, the queue wait, the frozen time, and ready-to-run-with-no-core.

**2.5** (a) The *Allocation → collections* row, and the line under the resource
sheet: "A collection every N s". (b) "Longest freeze so far" on that same line.
(c) "Total time frozen: X% of the clock", also there. All three are on the
resource sheet because all three are properties of a resource, not of a request.

---

## Rung 3 — Predict

**3.1** [MODEL] p99 goes from **1,327 ms to 1,266 ms** — a 4.6% change, which
is nothing. Doubling again to 128 threads gives the same 1,266 ms. The lock
bucket actually goes *up* slightly (87.3 → 92.0 ms), which is the tell: more
threads means a longer line for the same single-file door. **Threads are not
what is scarce here; permission to enter the one-at-a-time section is.**

**3.2** [MODEL] With no database call and 40 ms of CPU work, the bottleneck
becomes **running your code** (44.3 ms of a 51 ms average). Median falls to
**33 ms** — the database wait is gone — but **p99 rises to 536 ms**, because the
heavy requests now cost 40 ms × their multiplier with nothing to dilute it, and
CPU utilisation has climbed to 38%. Note what happened: the median improved and
the tail got worse. If you had only been watching the median you would have
called this a win.

**3.3** [HAND] Little's Law: `L = λW = 200 × 0.043 s = 8.6 threads busy on
average`. That is a **floor**, not a setting. [MODEL] measured:

| Pool | Throughput | p50 | p99 |
|---:|---:|---:|---:|
| 8 | **174.7 /s** — cannot keep up | 2,871 ms | 2,978 ms |
| 9 | 196.7 /s | 100 ms | 226 ms |
| 10 | 192.7 /s | 49 ms | 177 ms |
| 12 | 192.7 /s | 41 ms | 173 ms |
| 16 | 192.7 /s | 41 ms | 173 ms |
| 24 | 192.7 /s | 41 ms | 173 ms |

Eight threads — just below the floor — does not make the service *slower*, it
makes it a **smaller service**: throughput is pinned at 174.7/s and the queue
does the rest. Nine barely works but with a p99 of 226 ms, because the average
being 8.6 means the *instantaneous* demand is often above 9. From twelve
onwards nothing changes at all: **24 does not beat 16.** The rule from
`queueing-theory`: size to `λS ÷ ρ_target`, so 8.6 ÷ 0.7 ≈ **13 threads**.

**3.4** [MODEL] This one is designed to catch you.

| | p99 | Collections | Longest freeze | Interval |
|---|---:|---:|---:|---:|
| defaults (128 KB/req) | 248 ms | 2 young | 3.7 ms | every 10.24 s |
| 8× the garbage (1 MB/req) | **253 ms** | 22 young + 2 old | 17.0 ms | every 1.28 s |
| …and 8× the heap | 249 ms | 2 young | 4.1 ms | every 10.24 s |

Eight times the garbage produced **eleven times the collections** and moved p99
by **five milliseconds**. If you predicted a disaster, you predicted the
mechanism correctly and the *consequence* wrongly — and the reason is the whole
lesson: garbage collection was never this service's bottleneck. It was 0.0 ms
of the average request. Eight times almost nothing is still almost nothing. The
heap change then undid the frequency exactly, which confirms
`interval = eden ÷ allocation rate` [HAND: 512 × 0.30 = 153.6 MB of allocation
area; at 128 KB × 120/s = 15.0 MB/s that is 10.24 s].

**3.5** [HAND] `s = 10%`, so the ceiling is `1/0.10 = 10×` — for any number of
cores, forever. At 16 cores Amdahl gives **6.40×**; at 128 cores, **9.34×**.
Eight times the hardware buys **1.46×**. The implication: the proposal is
worth at most 46% and costs 8× the machine, so the money belongs in reducing
the 10%, not in buying cores. And this is the *optimistic* number — it ignores
coherence entirely.

---

## Rung 4 — Modify

**4.1** [MODEL] You need three things at once: the code must not be compiled,
there must be no database wait to dilute it, and the service must not be
saturated (or the queue swamps everything). For example *interpreter only*,
database wait 0 ms, 60 requests/second, 32 threads → *code not yet compiled*
**80.2 ms** against *running your code* 22.3 ms. Ticking *Restart cold*, 40
req/s, no database call, 16 cores gives 65.5 ms vs 17.0 ms in the first four
seconds.

It does not happen on the defaults for two reasons. First, the defaults start
**warm** — `fresh` is off, so the handler is already at C2 and the multiplier is
1.0×. Second, even cold, the 25 ms database wait is unaffected by any compiler,
so it dilutes the penalty. **The more of your request is waiting, the less
warmup matters.**

**4.2** [MODEL] The *Share of the work under one lock* slider, at about **30%**:
lock becomes the widest bucket at 25.2 ms and p99 goes to 705 ms.

The interesting part is what happens if you keep going. At 45% the widest
bucket is no longer *lock* but **queue** (p99 1,621 ms), and at 60% more so.
That is the answer to "which resource is a lock": **a lock is a single-server
queue**, so it obeys `queueing-theory` directly — and once its utilisation
passes 1, it stops being a delay inside the service and becomes the capacity
limit *of* the service. The waiting then relocates to in front of the thread
pool, where it is much harder to attribute to the lock that caused it.

**4.3** [MODEL] Attempts, from `cores 16 / quota 2 / pool 32 / 200 req-s`, p99
5,214 ms:

| Change | p99 | Throughput |
|---|---:|---:|
| nothing | 5,214 ms | 74.0 /s |
| remove the database wait | 6,031 ms (worse) | 75.0 /s |
| pool 32 → 8 | 2,661 ms | 104.0 /s |
| offered load 200 → 60 /s | 325 ms | 63.3 /s |
| **work per request 18 ms → 4 ms** | **75 ms** | **196.0 /s** |
| work 4 ms *and* load 120 /s | 80 ms | 109.3 /s |

Only two things worked, and they are the same thing: **reduce the CPU demanded
per second** — either by serving fewer requests or by making each one cheaper.
Shrinking the pool helped a little (less oversubscription) but could not fix
capacity that is not there. Removing the database wait made it *worse*, because
now every thread wants a CPU all the time.

What this tells you: **CPU starvation is a capacity problem, and tuning cannot
create capacity.** The only levers are do-less-work, serve-fewer-requests, or
get-more-CPU. Which is exactly why the real fix is to correct the container
limit, and why F2 is worth being able to recognise in seconds rather than days.

**4.4** [MODEL] With cross-talk at 0.00%, the same sweep:

| Pool | Throughput | p99 | Work inflation |
|---:|---:|---:|---:|
| 8 | 300.0 /s | 1,434 ms | 1.00× |
| 16 | 300.0 /s | 1,445 ms | 1.04× |
| 32 | 300.0 /s | 2,350 ms | 1.11× |
| 64 | 290.0 /s | 4,028 ms | 1.26× |
| 128 | **222.3 /s** | 7,465 ms | 1.56× |

Most of it survives, and that is the finding. At 128 threads the throughput loss
against the 8-thread baseline was 88.7 requests/second with cross-talk and is
still **77.7 without it** — so cross-talk accounted for only about an eighth of
the damage (211.3 → 222.3 /s). The rest comes from the
model's **oversubscription penalty** — the cost of an operating system moving
128 runnable threads across 8 cores. So there are two independent mechanisms and
both are real: coherence traffic between *running* threads, and scheduling churn
from *runnable* ones.

It is **liveness** that is at risk, not safety. Nothing computes a wrong answer;
the system simply does less of it, and eventually so little that requests time
out. That is the same distinction `java-multithreading` drew, one level up.

**4.5** [HAND, tab 2] With `s = 0` and `κ = 0` the curve is the straight line
`speedup = N` — perfect scaling, no ceiling, forever. Raising **only cross-talk**
gives a curve that rises, peaks at `N* = sqrt((1−s)/κ)` and then **falls**;
raising **only the serial share** gives a curve that rises and **flattens** at
`1/s`, never turning down. So: the serial term sets **how high you can ever
get**; the coherence term sets **where you should stop**. They are different
questions, and a system can be limited by either.

---

## Rung 5 — Trace

**5.1** [HAND] Handler needs 24 ms of CPU compiled, plus a 40 ms database call.

```
Request #1      (0 calls so far, interpreted, 5.0x)
  CPU        24.0 x 5.0  = 120.0 ms
  Database                 40.0 ms
  Elapsed                 160.0 ms

Request #500    (past 300 calls, C1, 1.9x)
  CPU        24.0 x 1.9  =  45.6 ms
  Database                 40.0 ms
  Elapsed                  85.6 ms

Request #5,000  (past 3,000 calls, C2, 1.0x)
  CPU        24.0 x 1.0  =  24.0 ms
  Database                 40.0 ms
  Elapsed                  64.0 ms
```

Bucket split for #1: of every CPU millisecond at a 5.0× multiplier, `1/5` is
work that would have happened anyway and `4/5` is the un-compiled penalty.

```
Running your code    120.0 x (1/5)  =  24.0 ms
Code not yet compiled 120.0 x (4/5)  =  96.0 ms
Waiting on the database             =  40.0 ms
                                       -------
                                        160.0 ms   = elapsed. Nothing unaccounted for.
```

Note that the compiler only ever attacks 120 of those 160 ms; the 40 ms is
immune. That is why the same warmup is a 2.5× problem here and a 5× problem in
a handler with no database call.

**5.2** [HAND] Allocation rate = `256 KB × 300/s = 76,800 KB/s = 75.0 MB/s`.
Allocation area = `1,024 MB × 0.30 = 307.2 MB`.

- Young collection every `307.2 ÷ 75.0 = **4.096 s**`
- Old-generation collection every `8 × 4.096 = **32.77 s**`

Halve the allocation per request → 37.5 MB/s → young every **8.192 s**, old
every **65.54 s**. **All three numbers scale exactly linearly**, because
`interval = area ÷ rate` and only the rate changed. That is the whole reason
allocation rate is the first lever: it is the one term you can change by a
factor of ten with a code review, and it moves frequency proportionally.

**5.3** [HAND] Retaining 800 MB:

| | pause work | + ttsp 2 ms | + ttsp 150 ms |
|---|---:|---:|---:|
| Serial old-gen | `14.0 + 0.42 × 800 = 350.0` | **352.0 ms** | **500.0 ms** |
| ZGC old-gen | `0.6 + 0.004 × 800 = 3.8` | **5.8 ms** | **153.8 ms** |
| ZGC's advantage | | **60.7×** | **3.25×** |

The sentence: **with a 150 ms time-to-safepoint, 96% of ZGC's advantage
evaporates**, because you are no longer paying for collection, you are paying to
stop the threads — and both collectors pay that identically. So if your
time-to-safepoint is bad, fixing it comes *before* changing collector, and
choosing a low-pause collector without checking it is buying a sports car for a
road with a 20 mph limit.

**5.4** [HAND] `s = 0.08` (92% parallelisable).

| Threads | 1 | 4 | 16 | 64 | 1,024 | ceiling |
|---|---:|---:|---:|---:|---:|---:|
| Amdahl | 1.000× | 3.226× | 7.273× | 10.596× | 12.361× | **12.5×** |
| USL, κ = 0.0005 | 1.000× | 3.210× | 6.897× | 7.944× | — | — |

USL peak: `N* = sqrt((1 − 0.08) ÷ 0.0005) = sqrt(1,840) = 42.9`, so **43
threads**, giving **8.170×**. USL at 128 is 6.636× and at 256 is 4.737×.

The last thread that still improves things is the **42nd** (going to 43 is still
an improvement; going to 44 is not). So this program's honest answer to "how
many threads?" is **43**, its honest ceiling is **8.17×** — not the 12.5×
Amdahl promised — and 1,024 threads would be catastrophic.

---

## Rung 6 — Spot the flaw

**6.1** The timer starts **after the request has already waited**. Concretely:
40 pods, a pool of 16 threads each, and a traffic spike takes arrivals past
capacity. Requests now sit in the executor's queue for four seconds and are
then handled in 40 ms. The `Timer` records 40 ms. p99 stays green all hour.
[MODEL] at 340 requests/second against a pool of 16: in-handler p99 **180 ms**,
arrival-to-answer p99 **379 ms** — and the gap widens as fast as the queue does
(at the load in the README's scenario 8 it was 120 ms against 880 ms, 7.3×).
The excluded time is **`time_in_queue`**, everything between arrival and
dispatch. This is **coordinated omission**. Fix: measure at the edge, and add
`time_in_queue` as its own metric.

**6.2** The hidden assumption is that **CPU samples are a proxy for elapsed
time**. They are not; they are a proxy for CPU time. [HAND] If the service is
genuinely CPU-bound then 61% halved gives `1/((1−0.61) + 0.61/2) = 1.439×`, a
44% improvement, so the "~30%" is at least the right order. But in a service
where only 4.5% of elapsed time is on-CPU, that method is `0.61 × 4.5% = 2.7%`
of the request, and halving it gives `1/((1−0.027) + 0.027/2) = 1.0137×` — a
**1.4%** improvement. Same profile, same 61%, same optimisation, a factor of 32
difference in the outcome, decided entirely by a number the profiler never
printed.

**6.3** Any of:
1. **Dead-code elimination** — the return value is unused, so the JIT proved the
   whole call has no effect and deleted it. Rule it out by consuming the result
   (JMH's `Blackhole`) and checking the number changes.
2. **Constant folding** — `input` never changes, so the result was computed once
   at compile time. Rule it out by varying the input from state the compiler
   cannot see through.
3. **Warmup / profile pollution** — the loop was measured across the interpreted
   phase, or C2 compiled it with the knowledge that there is exactly one call
   site and one implementation, which will not be true in production. Rule it out
   with warmup iterations and forked JVMs.

A fourth worth knowing: **escape analysis** may have removed the very
allocation you were trying to measure. All four are what JMH exists for.

**6.4** F6. Run ZGC, whose collection pauses are genuinely under a millisecond,
and have one thread inside a tight counted loop over a large primitive array
that the JIT compiled with no safepoint check in it. Every pause now costs the
collector's ~1 ms **plus** however long that loop takes to finish — [MODEL]
longest freeze 181.4 ms and p99 2,189 ms, with the collector's own work
unchanged. The GC log shows sub-millisecond pauses throughout and is telling
the truth. The runbook should have named **`-Xlog:safepoint*`**, which reports
*reaching* the safepoint separately from being *at* it. (Non-GC operations
cause safepoints too — thread dumps, class redefinition — so the freeze may not
be the collector's fault at all.)

**6.5** Two failures:
1. **`MaxGCPauseMillis=5`** is a goal G1 pursues mainly by shrinking the young
   generation. Smaller young generation → more frequent collections → more total
   collector CPU → more premature promotion → a **worse p99** than they started
   with, while the metric they asked for looks satisfied.
2. **`-Xmx` equal to the container's memory limit** leaves nothing for thread
   stacks, metaspace, the code cache, GC structures and direct buffers. The
   process exceeds the limit, the kernel's OOM killer terminates it, and the
   container exits with **code 137 and no stack trace at all** — that is the one
   with no diagnostics. Use `-XX:MaxRAMPercentage` instead, and turn on
   `-XX:+HeapDumpOnOutOfMemoryError` for the case where it really is the heap.

**6.6** Little's Law gives the **average** number of busy threads, and an average
is not a maximum. Demand fluctuates around 8.6, so a pool of 9 spends a large
share of its time with every thread busy and a queue forming — the pool is
running at `ρ ≈ 1`, which `queueing-theory` showed is exactly where latency
stops being finite. [MODEL] a pool of 8 pinned throughput at **174.7/s** (never
reaching 200), and a pool of 9 reached 196.7/s but with a p99 of **226 ms**
against the 173 ms available from 12 threads. The rule: **`L = λW` gives the
floor; the setting is `λS ÷ ρ_target`** — at ρ 0.7 that is `8.6 ÷ 0.7 ≈ 13`
threads. Then verify there is no *upper* cliff (F11) before going much beyond it.

---

## Rung 7 — Handle failure

These are design discussions. What follows is one good answer, not the only one.

**7.1 Retries turning a refusal into a collapse.**
Two retries with no delay means a refused request is offered **three times**, so
your offered load triples exactly when you had least capacity. `queueing-theory`
measured this precisely: a one-instant burst into a healthy 85%-utilised system,
with clients retrying twice on a 600 ms timeout, produced a queue of 3,249 then
9,983 then **37,115 jobs after 20 minutes and still growing** — while the
identical burst *without* retries was absorbed inside four minutes. It also
measured that **99% of server time went to clients that had already given up**.
The system does not recover when the trigger goes away, which is why turning
traffic off and on is the standard remedy.

**Server side, smallest change:** drop requests whose deadline has already
passed **at dequeue time** — check before you start work, not after. This costs
one comparison and immediately stops you spending capacity on answers nobody
will read. Second: refuse *fast* and *hard* rather than gently (that lab
measured a shed to 0.85 recovering in 6 minutes where a timid shed to 0.95 took
18).

**Client side, smallest change:** a **retry budget** — cap retries at a small
percentage of total requests, so retries can never multiply offered load — plus
exponential backoff with jitter. And make the operation idempotent so retrying
is safe at all (`idempotency`).

**Which first: the server.** You control it, it protects you from every client
including ones you do not own, and it works immediately. The client fix is
better but slower to land and only covers clients you can change.

**7.2 Deployments that saturate a cold instance.**
The instance is slow for a compilation-count reason, not a time reason (F1), so
**time-based warmup waits are the wrong shape** — a low-traffic instance stays
cold much longer than a busy one.

- (a) **Ramp by weight, driven by the instance's own readiness.** Start at a
  small share and increase as it earns it. Better still, use a load balancer
  with **least-outstanding-requests** rather than round-robin: it naturally
  sends less to an instance that is responding slowly, with no configuration at
  all. That single change fixes most of this.
- (b) To keep the rollout fast, run the ramp **in parallel across instances**
  rather than serially, and cap the total fraction of the fleet in ramp state.
- (c) A broken build is still detected quickly because health and error-rate
  checks are separate from latency checks — you gate on *errors* immediately and
  on *latency* only after the ramp.

**The readiness signal:** not "60 seconds elapsed". Use either the compiler's
own state (JFR compilation events levelling off) or, more practically, a
self-test: on startup, run a fixed number of synthetic requests through the real
handler and report ready when their latency has stabilised. That measures the
thing you actually care about and works regardless of traffic shape. What you
trade: startup time, and the risk that your synthetic requests warm the wrong
paths.

**7.3 Hiding a 400 ms pause without shortening it.**
Make the pause happen somewhere the user is not waiting. Options:

- **Take the instance out of rotation before it collects.** Some systems do this
  explicitly, and it is the cleanest version of the idea.
- **Hedged requests**: send the request to a second replica if the first has not
  answered within, say, p95. A pause on one replica is then invisible unless
  both pause together. You trade **extra load** (a few percent of duplicated
  requests) for tail latency, and you require the operation to be idempotent.
- **Stagger the pauses across the fleet** so they are never simultaneous —
  otherwise hedging does nothing.

**What breaks if the pause rate doubles:** the probability that both the
original and the hedge hit a pause rises roughly with the square of the pause
probability, so hedging degrades gracefully at first and then suddenly. And the
extra load from hedging raises utilisation, which — `queueing-theory` — raises
latency, which triggers more hedges. There is a feedback loop here; cap hedges
as a percentage of traffic, exactly like a retry budget.

**7.4 A downstream with p50 20 ms and p99 2,000 ms, against your 300 ms SLA.**
Cheapest first:

1. **Set a timeout well below your SLA** (say 200 ms) and have a fallback:
   cached value, default, or a degraded answer. Cheap, immediate, and it converts
   a latency failure into an availability decision you control. Gives up:
   completeness of the answer.
2. **Cache it** (`caching`), if the data tolerates staleness. Removes most calls
   entirely. Gives up: freshness, and you inherit that lab's invalidation
   problems.
3. **Hedge** — a second call at p95. Gives up: extra load on a service that is
   already struggling. Only do this if they can take it.
4. **Make the calls concurrent** if you make several. Turns a sum of latencies
   into a maximum. Gives up: burstier load downstream.
5. **Move it off the request path** entirely — precompute, or make it
   asynchronous and return a reference. The largest win and the largest change.

**The option that makes it worse: retrying on timeout.** Their p99 is 2 seconds
because they are struggling; a retry adds load to a struggling service and can
tip it into the metastable failure in 7.1. If you must retry, use a budget.

---

## Rung 8 — Design

Sketches. Yours should differ; it should not *omit* what is below.

**8.1 — Observability for the payments service.**

*Measured at the edge (ingress), per route:* request rate, error rate,
**latency percentiles p50/p95/p99/p99.9** — this is the SLA number and it is the
only one that counts as truth. *Rules out:* nothing on its own; it tells you
whether there is a problem, which is a different job.

*Measured in the application:*
- **`time_in_queue`** (arrival → dispatch). *Rules in or out:* the *waiting for
  a thread* bucket, and by subtraction separates "too small" from "too slow".
  Highest value per line of code of anything on this list.
- **Pool gauges** — active threads, queue depth, rejection count. *Rules in:*
  the same bucket, with the cause.
- **Downstream call duration and count, per dependency, per request.** *Rules
  in:* the *waiting on the database* bucket, and catches N+1 patterns.
- **Lock/monitor contention** from JFR. *Rules in:* the *waiting for a lock*
  bucket.
- **Allocation rate, collection count and pause duration, resident set size
  against the container limit.** *Rules in:* the *frozen* bucket and F3.
- **Container CPU throttling counters.** *Rules in:* the *ready to run, no core
  free* bucket, and F2. Direct measurement, not inference.

*Logs on permanently:* `-Xlog:gc*` to a rotating file (negligible cost),
`-XX:+HeapDumpOnOutOfMemoryError`. *On demand:* `-Xlog:safepoint*`, and
async-profiler.

*Always-on JFR* with a low-overhead profile, plus **automatic capture triggered
by symptom**: a rolling recording dumped when p99 exceeds threshold for N
seconds, or on OOM. The recording you did not take is the incident you
investigate twice.

*Alerts:* on **p99 against the SLA**, on **error rate**, on **queue-depth
derivative** (growing, not merely large), on **spare capacity**, and on
**throttling**. Not on the mean; not on CPU utilisation alone.

*At 3am, read in this order:* (1) is it errors or latency? (2) `time_in_queue`
— is the service too small or too slow? (3) the bucket decomposition — which
block grew? Everything else follows from those three.

**8.2 — Capacity and pool design.**

Per request: 12 ms CPU + 3 × 30 ms sequential downstream = **102 ms** holding a
thread, of which 12 ms is CPU.

- **CPU limit per pod:** 4 CPUs ÷ 12 ms = **333 requests/second** of CPU
  capacity, absolute ceiling.
- **Pod count:** 3,000 ÷ 333 = 9 pods at 100% — which is the `ρ = 1` cliff, so
  target ρ ≈ 0.65: **≈ 14 pods**, round up for failure domains. Per pod that is
  ~215 requests/second.
- **Pool size:** Little's Law `L = 215 × 0.102 = 21.9` threads busy on average.
  That is the floor; at ρ_target 0.7, **≈ 32 threads**. On 4 CPUs this is fine
  precisely *because* threads are mostly asleep — 32 threads demanding 12 ms of
  CPU each is well inside 4 cores.
- **Queue capacity:** `queueing-theory`'s rule, `maxQueue ≈ deadline/S − c` =
  `400 ms / 102 ms − 32` — which is **negative**, and that is the answer, not a
  mistake: at this service time there is no useful queue at a 400 ms deadline.
  Set it small (8–16) purely to absorb jitter, and shed beyond it.
- **Timeout:** below the SLA, and **propagate the deadline** so the downstream
  calls inherit what is left of it.

*If downstream latency triples* (30 → 90 ms): hold time becomes 282 ms. **CPU
demand is unchanged**, so pod count is unchanged — the CPU was never the
constraint. **Little's Law re-derives itself**: `L = 215 × 0.282 = 60.6`, so the
pool must roughly triple to ~87 threads or throughput collapses. The **queue
capacity is now definitely zero** and the **timeout is now impossible** — three
sequential 90 ms calls at p50 cannot fit a 400 ms p99 SLA at all, so the
sequential design itself has to change (parallelise the independent calls).

*The general rule this illustrates:* **a concurrency limit re-derives itself
when service time changes; a rate limit is correct at exactly one downstream
latency.** Prefer the former.

*Platform vs virtual threads.* **Changes:** the pool sizing arithmetic largely
goes away — you can run thread-per-request and stop maintaining a number that
is wrong whenever downstream latency moves. That is a real and large win here,
because the tripling case above breaks a platform-thread design and does not
break a virtual-thread one. **Does not change:** the CPU ceiling (still 333/s
per pod), the pod count, the deadline arithmetic, the fact that three sequential
90 ms calls do not fit, or any lock contention. **New obligation:** the pool was
providing backpressure for free; with virtual threads you must add a
`Semaphore` or a concurrency limiter explicitly, or the queue simply becomes
invisible. And check for pinning.

**8.3 — The reply to the tuning proposal.**

> Thanks — worth taking seriously, and I want to make sure we spend the effort
> where it pays. Taking the four separately:
>
> **ZGC everywhere.** Right when a service has a large heap and a strict tail
> requirement, and it genuinely does cut pause times enormously. It is not free:
> it does its collecting while our code runs, so it costs throughput, and the
> bill scales with how much garbage we make. Before switching I'd want, per
> service: what share of p99 is currently collector pauses? If it is under about
> 10%, we cannot win more than 10% and we may lose more than that in throughput.
>
> **8 GB heaps everywhere.** A bigger heap reduces how *often* we collect and
> does nothing about how *long* each one takes — and it raises our memory bill
> across the fleet. I'd want the current allocation rate and collection interval
> first; if we are collecting every ten seconds, there is nothing to buy.
>
> **`MaxGCPauseMillis=5`.** This one I'd push back on. It is a goal, not a
> guarantee, and G1 meets it mainly by shrinking the young generation — more
> collections, more promotion, and usually a *worse* p99. It also fights ZGC's
> own tuning if we do both.
>
> **Tripling every thread pool.** Depends entirely on whether the pools are the
> constraint. Where a service is CPU-bound this makes it measurably slower — we
> have a case where 128 threads did 30% less work than 8 on the same hardware.
> I'd want queue depth and thread-busy numbers per service first.
>
> **What I'd suggest instead, in order.** (1) Turn on GC logging and JFR
> everywhere and add `time_in_queue` — a week, and it tells us which of these
> four is even relevant. (2) Decompose p99 on the two worst services. (3) Then
> change **one** thing on **one** service and measure. My guess, and it is only a
> guess until we have the data, is that allocation rate on the ingestion path is
> the biggest single win and needs no flags at all.
>
> Happy to be wrong on any of this — the measurements will settle it faster than
> we can argue.

**8.4 — Three regions.**

*Survives:* the seven buckets, the method, the CPU arithmetic, Little's Law,
Amdahl, the pool sizing, the deployment ramp, the whole of 8.1's in-process
instrumentation.

*Must change:* the deadline budget — 50 ms each way means a cross-region call
costs 100 ms of your 400 ms before anything happens, so deadline propagation
stops being good practice and becomes structural. Timeouts must be per-region.
Caching becomes much more valuable and much more dangerous. Routing must be
locality-aware, and "which region served this" becomes a required dimension on
every metric — an aggregate p99 across three regions can hide one region being
entirely broken.

*The new bucket:* **waiting for consensus / cross-region replication** — time
spent waiting not for computation or for one downstream call, but for enough
replicas to agree. It behaves differently from the other seven: it does not
shrink with more CPU, more threads or less garbage, and its tail is set by the
*slowest* required participant.

*Labs that already cover the new failures:* `quorums-and-replication` and
`raft`/`paxos` for the consensus latency itself; `pacelc` for the fact that even
with no partition you are trading latency for consistency; `cap-theorem` and
`split-brain` for what happens during one; `google-spanner` and
`hybrid-logical-clocks` for the clock side; `tail-at-scale` (not yet learned)
for why a request touching many services is slow even when every service is
fast.
