# Solutions — multithreading in Java

Worked answers. Rungs 7 and 8 are design discussions, not single correct outputs —
compare shape and reasoning, not wording. Figures cited as *verified* come from
`./validate-visualization.sh` (42 engine checks + 12 render checks, all passing).

---

## Rung 1 — Explain

**1.1** Each person's job is really three separate trips: read the number, change it
at their own desk, come back and write it. If the second person reads before the
first one writes, both of them are working from the same starting number, and the
second write simply covers the first. Nobody made a mistake; the tally is wrong
anyway.

The key on the door changes *which sequences of events are possible*: it deletes the
ones where the second person reads while the first is mid-job. It does **not** make
the three trips into one trip, and it does not make anyone faster. Everything is
still three trips — there just can no longer be a second person in the middle of
them.

**1.2** *"You fixed whether other threads can see the value, which was a real
problem. You did not fix that `count++` is a read, an add and a write with a gap
between them — two threads can both read the same number and both write the same
result, and `volatile` allows that."* Their instinct is exactly right for a **flag
one thread writes and others only read**, which is scenario 2 — there is no
read-modify-write, so there is no gap to protect.

**1.3** Safety: nothing incorrect ever happens. Liveness: something correct
eventually happens.

- Lost update — **safety**. Waiting cannot help; the data is already wrong.
- Deadlock — **liveness**. Waiting cannot help either, but nothing is corrupted; a
  restart recovers fully.
- Buffer at −1 — **safety**. An invariant is broken.
- Missed stop flag — **liveness**. Nothing is wrong, nothing will ever progress.

The pattern worth keeping: waiting never fixes either kind. The difference is what
you have to do afterwards — a liveness failure needs a restart, a safety failure
needs a reconstruction.

---

## Rung 2 — Identify

**2.1** After A, B, A: `count` in memory is **0**; A's `r` is **1**; B's `r` is
**0**. `count` is a field, so it lives on the heap and every thread can reach it.
Both `r`s are local variables, so they live on their thread's own stack — there is
one `r` *per thread*, and neither thread can see or affect the other's. That is why
the visualization draws them inside the thread cards and `count` outside them.

**2.2** `buffer` is **held by nobody** (both consumers gave the lock back when they
called `wait()`); the **entry set is empty**; the **wait set holds both consumers**.

The two ways out are different in kind, and this is the point of the question:

- To leave the *wait set*, a consumer needs **another thread to call `notify` or
  `notifyAll`**. Nothing it does itself can help. Time passing does not help.
- To leave the *entry set* (where it lands after being notified), it needs only
  **the lock to become free**. That happens on its own as other threads finish.

`WAITING` depends on someone else's cooperation; `BLOCKED` resolves itself.

**2.3** Thread B is disabled because Thread A owns the `lock` monitor, so B is
`BLOCKED` — and a blocked thread cannot execute any instruction at all, so there is
no "one step of B" to run. It becomes enabled the moment the Locks panel shows
`lock` **held by nobody**, which happens when A executes its `UNLOCK`.

**2.4** The two places are (a) the **Shared memory** panel, which reads
`stop = true`, and (b) the Worker's **private copy** row, which reads
`stop = false — memory says true`. Together they show the write completed *before*
the read, and the read still returned the old value. A timing failure would require
the read to have happened first; here it did not. Verified: memory holds `true`,
the worker has looped 13 more times, and every read returned `false`.

---

## Rung 3 — Predict

**3.1** **{1, 2, 3}.**
*Highest, 3:* each thread runs to completion before the next starts — every write is
based on a fresh read.
*Lowest, 1:* all three read 0, all three compute 1, all three write 1. Two
increments vanish.
There is no way to reach 0 (someone writes at least 1) or 4 (nobody ever writes more
than what they read plus one).

**3.2** Both threads attempt their second `LOCK`, both are refused, and neither
program counter moves. Final state: Thread A `BLOCKED` on `accountB`; Thread B
`BLOCKED` on `accountA`; `accountA` held by A with B in its entry set; `accountB`
held by B with A in its entry set; nothing `RUNNABLE`. Verified: `run = STUCK`, both
threads `BLOCKED`, and each monitor owned by the thread the other one wants.

**3.3** If the producer runs first, `items` is already 1 when the consumers arrive,
so **neither consumer ever calls `wait()`** — the `if`/`while` distinction never
comes into play. Consumer 1 takes the item (`items` 1 → 0). Consumer 2 finds
`items == 0` and goes to sleep, and stays asleep because the producer has already
finished. Final `items = 0` in **both** versions, with one consumer `WAITING`.

The surprise for most people is that the buggy version looks perfectly correct here.
That is the actual lesson: the `if` bug is invisible on the schedule where the
producer is early, and appears only when both consumers sleep first. The bug is in
the *set of permitted schedules*, not in the code you can point at. Verified: the
schedule where both consumers sleep first gives `items = -1` with `if` and `items = 0`
with `while`.

Second thing to notice: run the producer before anyone sleeps and the log says
**"called `notifyAll()` and woke nobody"** — verified. The notification was
discarded, and it did not matter, because the *condition* (`items == 1`) was still
true when the consumers looked. The condition is the truth; the signal is a nudge.

**3.4** 3 workers → finishes at tick **20**. 5 workers → finishes at tick **15**.

The naive formula (total work ÷ workers = 60/W) predicts 20 for three workers, which
is right, and **12** for five workers, which is wrong. Tasks cannot be split: with
five workers, the twelve tasks run in three rounds of at most five, so the makespan
is `5 ticks × ceil(12/5) = 15`. The fifth worker sits idle for the whole final
round. Verified across the sweep: 60, 30, 20, 15, 15, 10 for one to six workers —
note that **four and five workers finish at the same tick**.

The practical version of this: capacity you add stops buying anything well before
the utilisation graph looks full.

**3.5** Thread B's compare-and-set returns **false** — it expected 0 and memory holds
1. B does not block and does not lose its increment; it loops back, re-reads
(getting 1), computes 2, and its second compare-and-set succeeds. Total attempts:
**3** (A once, B twice). Verified: final count 2, `casFail = 1`, `retries = 1`, and
no thread ever entered `BLOCKED`.

---

## Rung 4 — Modify

**4.1** Without the lock and under the interleaving A, B, A, A, B, B the final count
is **1** — verified. With the lock back on, you cannot drive that interleaving at
all: after A's first step it *holds* the lock, so B's button is disabled, and there
is no sequence of button presses that puts B between A's read and A's write.

"The lock made it slower" is the wrong description because nothing was slowed. The
same six instructions execute. What changed is the **set of executions that exist**:
the interleaving that produced 1 is not a legal execution of the locked program.
Verified exhaustively — enumerating every possible schedule of the locked version
produces 2 every time.

**4.2** The four conditions are mutual exclusion, hold-and-wait, no preemption, and
**circular wait**. Ordering attacks the last one, and only the last one — the other
three are all still true after the fix.

Why ordering is sufficient: a cycle requires at least one thread holding a
higher-ranked lock while requesting a lower-ranked one. If every thread acquires in
ascending order, no thread ever does that, so no cycle can be drawn. Whoever gets
`accountA` will eventually get `accountB`, because everyone waiting for `accountB`
either already holds `accountA` (only one thread can) or is not holding anything
above it. Verified: with ordering on, the same code completes.

**4.3** With `volatile` on, the private copy row stays empty — there is never a
private copy of `stop` to be stale, because every read goes to memory. Verified: the
worker terminates.

A `synchronized` block around the read would also work: acquiring the monitor
discards private copies, so each iteration re-reads from memory, and the writer must
use the same lock. The cost is that you now take and release a lock on **every loop
iteration** — far more expensive than a `volatile` read, and it introduces a lock
where none is needed. `volatile` is the right tool precisely because this is a single
read of a single field with no read-modify-write. (`AtomicBoolean` is equivalent here
and reads better.)

**4.4** Queue 0, no caller-runs: **10 of 12 rejected** (verified). With
`CallerRunsPolicy`: **0 rejected, all 12 completed, 4 run by the submitting thread**
(verified) — and while the submitter is running one, it cannot submit anything else.

- Choose **rejection** when losing work is acceptable and *latency* is not — a
  metrics ping, a best-effort cache warm, anything with a "shed load" story. You
  must handle `RejectedExecutionException`; silently swallowing it is how requests
  disappear.
- Choose **caller-runs** when the work must not be lost and the producer *can* be
  slowed — the classic case is reading from a queue or a file, where slowing the
  reader is exactly right. Do not use it when the caller is an HTTP request thread
  you are trying to keep responsive: you have just made an inbound thread do
  arbitrary background work.
- The third option is an **unbounded queue** (what `Executors.newFixedThreadPool`
  gives you). It never rejects and never blocks the producer — and it buys you
  unbounded latency and eventually `OutOfMemoryError` under sustained overload. It
  does not remove the choice; it defers it until the heap makes it for you.

**4.5** With nesting on and 2 workers the pool deadlocks at tick 4 (verified); at 8
workers the identical configuration completes (verified).

That is dangerous because nothing was fixed — the *dependency* is still "a task
blocks on work that needs a worker from the same pool". A larger pool only means it
takes more simultaneous parent tasks to consume every worker. Under production load
— a traffic spike, a slow downstream that makes tasks last longer, a retry storm —
the number of concurrently blocked parents rises, and the pool deadlocks then, at
peak, permanently, with no code change to blame. You have converted a deterministic
bug that fails in the first test run into a load-dependent one that waits for your
worst day. The real fix is structural: a separate pool for sub-tasks, or not
blocking at all.

---

## Rung 5 — Trace

**5.1**

| Step | Who | Instruction | `count` | A's `r` | B's `r` |
|---|---|---|---|---|---|
| 1 | A | `r = count` | 5 | 5 | — |
| 2 | B | `r = count` | 5 | 5 | 5 |
| 3 | B | `r = r + 1` | 5 | 5 | 6 |
| 4 | B | `count = r` | **6** | 5 | 6 |
| 5 | A | `r = r + 1` | 6 | 6 | 6 |
| 6 | A | `count = r` | **6** | 6 | 6 |

Final: **6**. Two increments were performed; one was lost. Note step 6: A's write is
not wrong in itself — it writes exactly what it computed. It is wrong because what it
computed came from a read that is now stale, and nothing in the program checks that.

**5.2** From the producer's `notifyAll()`:

| Step | Who | State before → after | Owner of `buffer` | `items` |
|---|---|---|---|---|
| 1 | Producer | RUNNABLE → RUNNABLE | Producer | 1 |
| | | `notifyAll()` moves **both** consumers from the wait set to the **entry set**, not to running | | |
| 2 | Producer | RUNNABLE → TERMINATED (after `UNLOCK`) | nobody | 1 |
| 3 | Consumer 1 | BLOCKED → RUNNABLE | Consumer 1 | 1 |
| | | re-acquires the lock and returns from `wait()` | | |
| 4 | Consumer 1 | with `if`, **does not re-check**; reads 1, writes 0 | Consumer 1 | **0** |
| 5 | Consumer 1 | releases, terminates | nobody | 0 |
| 6 | Consumer 2 | BLOCKED → RUNNABLE, re-acquires, does not re-check | Consumer 2 | 0 |
| 7 | Consumer 2 | reads 0, writes −1 | Consumer 2 | **−1** |

The decision becomes wrong at **step 6**. Consumer 2 is relying on the condition
`items > 0` that it verified *before* it went to sleep. Being woken told it that
something changed — it did not tell it that its condition is true, and between the
notification and its own re-acquisition, Consumer 1 took the only item. Verified:
`items = -1`, with two "took an item" events for one produced item.

**5.3**

| Attempt | Who | Expected | Actual in memory | Outcome | `count` after |
|---|---|---|---|---|---|
| 1 | A | 0 | 0 | **succeeds** | 1 |
| 2 | B | 0 | 1 | **refused** | 1 |
| 3 | B | 1 | 1 | **succeeds** | 2 |

Wasted work: **one addition** (B's first `0 + 1 = 1`, discarded). Verified: final 2,
`casFail = 1`, `retries = 1`, three attempts total, no thread ever `BLOCKED`.

The generalisation worth carrying: the wasted work is proportional to contention, and
it is *invisible in latency* as long as there are spare cores to absorb it — which is
exactly the measurement `optimistic-vs-pessimistic-locking` made one level up (862
retries, 2124 ms burned against 400 ms of useful work, wall clock nearly unchanged).

---

## Rung 6 — Spot the flaw

**6.1** `get()` can return a **stale value indefinitely**, or — for a `long`/`double`
on a 32-bit JVM, or for a reference published unsafely — a value that never existed.

Concrete run: writer takes the lock, sets `value = 42`, releases. Reader (no lock)
reads `value` and gets 0. There is no rule broken: the reader has no
*happens-before* edge to the writer's release, so it is permitted to see the old
value, and permitted to keep seeing it forever.

The writers' diligence does not help because **the guarantee is per-lock and
two-sided**. Releasing a monitor publishes your writes *to threads that subsequently
acquire the same monitor*. A reader that never acquires it never collects the
publication. Violated guarantee: visibility. The cheap fix if `get()` really is a
single field read is to make the field `volatile`; the correct fix if `get()` reads
two related fields is to take the lock, because otherwise you can see one field's new
value and the other's old one.

**6.2** Interleaving: thread 1 calls `containsKey(k)` → false. Thread 2 calls
`containsKey(k)` → false. Both call `expensiveLoad(k)`. Both call `put`. The second
`put` overwrites the first.

Both calls really are individually atomic; the problem is that **the invariant spans
both of them** — "load exactly once per key" is a property of the pair, and nothing
holds any lock between them. This is the same **check-then-act** shape as
`count++`; `ConcurrentHashMap` protects its own internal structure, not your
business rule. Violated guarantee: the at-most-once loading invariant (and, if
`expensiveLoad` has side effects, you have just done them twice).

Fix: one atomic call — `cache.computeIfAbsent(k, this::expensiveLoad)`, which holds
the bin lock across the check and the insert. (`putIfAbsent` fixes the overwrite but
still calls `expensiveLoad` twice, so it is the wrong choice when the load is the
expensive part.)

**6.3** Three separate problems with the reasoning.

*What is wrong:* "thread-safe on x86" is not a thing you are allowed to conclude. The
Java Memory Model, not the CPU, defines what is legal, and the **compiler** is a
reordering agent in its own right — the JIT can publish the reference before the
constructor's field writes are complete regardless of how strongly ordered the
hardware is. x86's store ordering hides the hardware half of the problem and none of
the compiler half.

*What the reader can observe:* a non-null reference to a **partially constructed
object** — fields still at their default values. Worse than a crash, because it looks
like valid data.

*Why the test evidence is worthless:* the failure requires a specific interleaving
plus a specific JIT compilation decision that only occurs after enough invocations to
trigger optimisation. Laptop and CI runs are short, single-tenant and lightly loaded
— precisely the conditions under which it does not reproduce. And most Apple-silicon
and Graviton deployments are *weakly* ordered, so the hardware half comes back in
production and not on the x86 CI box.

*Measured where:* the `java-memory-model` lab, whose `DoubleCheckedLocking` and
`Publication` experiments are exactly this. Fix: `private static volatile Instance
instance;` — or, better, a holder class or an `enum`, which get the guarantee from
class initialisation for free.

**6.4** This is **livelock**. Concrete run: threads A and B both need locks X and Y.
A gets X, B gets Y. A's `tryLock(Y)` fails, so A releases X and retries; B's
`tryLock(X)` fails, so B releases Y and retries. They retry in lockstep, each
grabbing the lock the other just dropped, forever.

It is harder to diagnose than the deadlock it replaced because both threads are
`RUNNABLE` and the CPU is busy. A thread dump shows threads doing work. Automated
deadlock detection reports nothing, because there is no cycle at any instant — the
cycle is in the *pattern over time*. You detect it by taking two dumps a few seconds
apart and noticing the same stacks are still churning with no progress metric moving.

The fix is the one the `raft` lab used for split votes, for the same reason:
**randomised back-off** before retrying, so the lockstep breaks. Plus a bounded retry
count so it eventually gives up loudly instead of quietly.

**6.5** At shutdown, `shutdownNow()` interrupts every worker; each one logs the
exception and carries straight on. The pool never terminates, `awaitTermination`
times out, and the deploy hangs or the container is killed mid-work.

The destroyed information is the **interrupt flag itself**. When a blocking call
throws `InterruptedException`, it *clears* the flag as it throws — the exception
object is the only remaining record that cancellation was requested. Catching it and
continuing throws away the sole copy, so every later check of
`Thread.currentThread().isInterrupted()` says "nobody asked me to stop".

Two-line fix:

```java
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();   // put the evidence back
    return;                               // and actually stop
}
```

This is precisely the mechanism `structured-concurrency`'s cooperative cancellation
is built on, which is why that lab insisted cancellation is cooperative and cannot be
imposed.

---

## Rung 7 — Handle failure

**7.1 — The lock held across HTTP.**

*What happens.* Thread 1 enters, holds the monitor, and blocks in a socket read for
30 s. Threads 2…51 arrive, hit the same `synchronized`, and go to `BLOCKED` in the
monitor's entry set. They are not doing work and not consuming CPU. Every request
now takes up to 30 s, and the pool is exhausted, so even requests that never touch
this code are rejected or queued behind it. One slow dependency has become a total
outage.

*What the graphs show.* Latency vertical; **CPU near zero**; error rate initially
*calm*, then a cliff when the pool queue fills. The near-zero CPU is the diagnostic
signature — a busy service looks busy, a blocked one does not.

*What a thread dump shows.* Fifty threads in `BLOCKED (on object monitor)` all
waiting on the same lock identity, and exactly one thread in `RUNNABLE` inside
`SocketInputStream.read`. That single thread is your answer and the dump names it.

*Why more threads make it worse.* They all queue on the same monitor. You have added
memory and context switches and zero throughput — the serial section is the ceiling,
which is Amdahl's law with a very unfriendly *s*.

*The redesign.* Shrink the critical section to the state transition only:

1. Under the lock: check the order is in a legal state and mark it `PENDING`, then
   release.
2. Outside any lock: call the provider, with a **timeout** — a call with no timeout
   is the actual root cause and must be fixed regardless.
3. Under the lock again: apply the result.

*The invariant to be careful about.* Between steps 1 and 3 the order is observable in
a half-finished state by other threads. You must ensure `PENDING` is a state the rest
of the system understands and refuses to double-process — which means the state
machine, not the lock, is now carrying the safety property. That is the same
conclusion `distributed-locking` reached: **the guarantee has to live at the resource,
and the lock is only an optimisation on top.**

**7.2 — Bounded buffer, many producers, many consumers.**

Producers wait while `count == capacity`; consumers wait while `count == 0`. Both
wait on the same monitor guarding the buffer. Both signal after changing `count`.

Why **`notifyAll()`** becomes essential once both kinds share one monitor: `notify()`
wakes one arbitrary thread from the wait set, and the wait set contains both
producers and consumers. A producer finishing can wake another *producer*, which
re-checks, finds the buffer still full, and goes back to sleep — while the consumers
that could have made progress are never woken. The signal is consumed and lost, and
the system can wedge with work available and everybody asleep. `notifyAll()` wakes
everyone; the `while` loops send the wrong ones back to sleep harmlessly.

The cheaper structural fix is **two `Condition` objects on one `ReentrantLock`**
(`notFull`, `notEmpty`), so you can signal precisely the right group — this is the
main reason `Condition` exists and `synchronized`'s single wait set is a limitation.

And the answer you should actually give in a code review: **use
`ArrayBlockingQueue`.** It is this design, correct, tested, and with `put`/`take`
that cannot miss a signal. Hand-rolling it is how the `if`/`while` bug gets written.

**7.3 — Catching pool starvation.**

*Monitoring rule.* Alert when, for a sustained window (say 60 s): **active threads =
pool size** AND **queue depth > 0** AND **completed-task count is flat**. Any two of
those are normal saturation; all three together mean nothing is finishing while
everything is occupied, which is the signature of blocked-not-working. Add the
strongest single signal: **queue wait time rising while execution time is flat** —
that separates "the dependency got slow" from "the workers are not running".

*Prevention.* A rule with a name: **a task may not block on a future produced by its
own executor.** Enforce it structurally — sub-work goes to a different, explicitly
named pool, and the two pools' names encode the layering.

*What a reviewer greps for.* `.get(`, `.join(`, `.invokeAll(`, `.parallelStream()`
and `CompletableFuture.…().join()` inside anything submitted to an executor —
especially `parallelStream()`, which silently uses the shared
`ForkJoinPool.commonPool` and therefore can starve code you have never read.

**7.4 — The `ThreadLocal` that outlived the request.**

*Symptom.* A customer occasionally sees another tenant's data. It is a security
incident, not a bug report.

*Why intermittent.* It requires the exception path, followed by that specific pooled
thread being reused for a request that reads the value before setting it. Under low
load the pool is large relative to traffic and reuse is rare; under high load it is
constant.

*Why worse than a crash.* A crash is loud, local and self-limiting. This returns a
plausible, well-formed, wrong answer, and every downstream system treats it as
genuine — it propagates into caches, logs, exports and audit trails, and the blast
radius keeps growing after the original bug is fixed.

*Fix 1, keeping `ThreadLocal`:* set it in a `try` and clear it in a `finally` in a
single filter that wraps everything, so no application code can skip it. `finally`
runs on the exception path; "at the end of the handler" does not.

*Fix 2, removing the class of bug:* `ScopedValue.where(TENANT, id).run(...)` — the
binding is immutable, it is scoped to the block, and it is unbound on **every** exit
path including exceptions, because unbinding is the runtime's job rather than yours.
Your `scoped-values` lab called `ThreadLocal`'s two diseases *write-from-anywhere*
and *lifetime-until-remove*; this incident is the second one, and `finally` is the
manual discipline `ScopedValue` replaces with a guarantee.

---

## Rung 8 — Design

**8.1 — The payments worker.** A defensible answer.

*Pools — three, not one.* One per provider (the bulkhead), so provider C's 3-second
timeouts cannot consume the threads that talk to A and B. A single shared pool means
C's bad day is an outage for all three.

*Sizing.* Each call waits ~200 ms and computes ~5 ms, so wait/compute ≈ 40. The
I/O-bound formula `cores × utilisation × (1 + wait/compute)` gives
`8 × 1.0 × 41 ≈ 328` threads to saturate 8 cores. That number is the point of the
exercise: it is absurd on platform threads (~328 MB of reserved stack, heavy
scheduling) and it is exactly why people historically went reactive. Choose
deliberately: cap each pool at something you can actually afford (say 48 per
provider, 144 total), accept that you will not saturate the CPU, and let the queue
absorb bursts. Give provider C's pool a *smaller* cap and a *shorter* queue than A
and B — you want its failures contained, not buffered.

*Queues.* Bounded, always. Capacity ≈ a few seconds of arrival rate, so a brief
downstream hiccup is absorbed and a sustained one is refused rather than silently
buffered into unbounded latency.

*Rejection.* Not `AbortPolicy` with a swallowed exception, and not caller-runs (the
caller is a queue consumer whose job is flow control — making it run a 200 ms
provider call destroys that). Reject, and on rejection **negatively-acknowledge the
message back to the source queue** so it is retried later by whoever has capacity.
The source queue is the buffer; the in-process queue must not try to be one.

*Shared mutable state, and how each piece is protected.*
- Per-payment status: **not shared** — confine each payment to one task. This is
  option 1 from stage 12 and it removes most of the problem for free.
- The de-duplication record ("has this payment been sent?"): shared, and it is the
  one that must be right. Protect it with a **conditional write in the database**
  (a unique constraint on the instruction id, or `UPDATE … WHERE status = 'NEW'`
  with the update count as the witness) — not an in-JVM lock, because a JVM lock
  protects nothing against the second instance of your service.
- Metrics counters: `LongAdder`, not `synchronized` — high write contention, reads
  only at scrape time, which is precisely what `LongAdder` is for.
- Provider clients and config: **immutable**, built once at startup, shared freely.

*Locks held across a network call: none.* The dedup check is a single database
statement; the provider call happens with nothing held. Write that down explicitly in
review, because it is the failure that causes outages (rung 7.1).

*Shutdown, inside 30 s.* Stop consuming from the source queue first; then
`shutdown()` each pool (not `shutdownNow()` — let in-flight payments finish);
`awaitTermination(20s)`; then `shutdownNow()` and log whatever is still running by
name. Every provider client must have a request timeout shorter than that budget, or
the drain cannot possibly meet it. And every worker must handle `InterruptedException`
by restoring the flag and returning (rung 6.5), or `shutdownNow()` does nothing.

*Dashboard — five things.* Per pool: active threads, queue depth, rejection rate, and
task latency **split into queue wait versus execution time**; plus end-to-end
duplicate-send count from reconciliation. The wait/execution split is the one that
tells you whether to add capacity or to fix the dependency.

*What `java-virtual-threads` deletes.* The sizing arithmetic and the three pool caps
go away: `Executors.newVirtualThreadPerTaskExecutor()` and one task per payment, with
328 concurrent waits costing almost nothing. Failure 7 (pool starvation deadlock)
becomes impossible because there is no pool to starve.

*What survives unchanged.* All of it that matters: bounded **admission** (you still
must not accept unbounded work — the bound simply moves from a queue to a semaphore
or to the source queue's prefetch), the dedup conditional write, "no lock across a
network call", timeouts on every call, interrupt handling, and the bulkhead —
provider C can still exhaust a shared *downstream* resource such as a connection
pool. Cheap threads changed the cost of waiting, not the rules of sharing.

**8.2 — 12 duplicates in 40 million.**

Candidates, each with the observation that settles it:

| Mechanism | Confirming / eliminating observation |
|---|---|
| Lost update on a `status` flag (two threads both read `NEW`, both send) | Is the send guarded by a **conditional** write whose update count is checked? If the code does read-then-write with no condition, this is live. If it uses `UPDATE … WHERE status='NEW'` and checks the count, eliminated. |
| Retry after an ambiguous timeout (the first send actually succeeded) | Correlate duplicate timestamps with client-timeout events on the provider side. Two sends seconds apart with the first timing out points here. |
| At-least-once redelivery from the source queue | Look for redelivery counts / duplicate message ids in broker metrics for those 12. |
| Two service instances processing the same instruction | Are the 12 split across two hosts at the same instant? Host tags in the send log answer it immediately. |
| Pool task submitted twice on an exception path | Is there a retry wrapper *and* a queue redelivery on the same failure? Count send attempts logged per instruction id. |
| `ThreadLocal`/context bleed causing the wrong id to be sent | The duplicates would be of *different* payments with a shared thread, not the same payment twice — check whether the pairs share an instruction id. |

**What observation alone cannot separate:** a lost update and a same-instant
double-delivery both look like *two sends, same id, same millisecond, different
threads*. Nothing in the logs distinguishes "both threads read `NEW`" from "two
copies of the message arrived". To tell them apart in future you must **record the
decision, not just the action**: log the update count (or the constraint violation)
of the guard write with each send attempt. A send whose guard write matched **zero
rows** and happened anyway is a lost update; two attempts each matching one row means
two distinct message deliveries reached the guard.

That instrumentation point is the practical lesson, and it is the same one
`optimistic-vs-pessimistic-locking` reached: **the update count is the only
witness** — a conflicting conditional write is not an error, it is an `UPDATE`
matching zero rows, and if you do not check it you cannot tell the difference
afterwards.

**And the argument that the fix does not belong here at all:** `idempotency` says
stop trying to make the send happen exactly once — make it *safe to happen twice*.
Give every instruction a key the provider deduplicates on, and all six mechanisms
above become harmless. Locking reduces the probability of a duplicate; idempotency
removes the consequence. The first is a race you can lose, the second is arithmetic.

**8.3 — The same problem at three scales.**

*Identical at all three.* The shape is always **read, decide, write**, with a gap in
the middle that the writer cannot see into, and a second party whose write lands in
that gap. In every case the losing write is not detected by anything: no exception,
no error, no log. And in every case the cure is one of exactly two things — make the
gap impossible to enter (a lock), or make the write conditional on what you read (a
version, a `compareAndSet`, an `If-Match`).

*Genuinely different.* The **size of the gap** and therefore what is affordable
inside it. Between registers the gap is nanoseconds, so blocking is cheap and a lock
is the obvious answer. Between transactions it is milliseconds and the gap contains
I/O, so holding a lock starts costing other people throughput
(`optimistic-vs-pessimistic-locking`'s `lunch`: 0 saves and 15 lock timeouts for the
neighbours of one 2-second pause). Between HTTP requests the gap contains **a human**
— seconds to minutes — and no lock is tolerable at all, so the conditional write is
the only option.

*Why in-JVM can use a plain lock and cross-machine cannot.* Because a JVM lock rests
on a fact the network destroys: **the runtime knows when a thread has died**, so it
can release its monitors. Across machines, death and slowness are indistinguishable
from outside — silence is the only evidence and it has two meanings needing opposite
responses. So a distributed lock must be a **lease with a TTL**, which immediately
creates the stale holder: a process paused longer than its TTL wakes up still
believing it holds the lock.

*What the distributed version must add.* A **fencing token** — a strictly increasing
grant number checked on every operation at the resource, so a stale holder's writes
are refused. Note what that admits: fencing does not restore mutual exclusion —
two holders still exist — it makes the overlap *harmless* by refusing stale effects.
The safety comes from arithmetic (the token comparison, which has no timing
assumptions); only the liveness comes from the timeout. Which is the same shape as
the third scale's conditional write, and the same shape as `compareAndSet` at the
first — one primitive wearing three costumes.
