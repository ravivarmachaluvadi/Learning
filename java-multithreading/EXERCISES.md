# Exercises — multithreading in Java

Everything here is pencil-and-paper or work in **The Interleaving Machine**
(`visualization.html` · <https://claude.ai/code/artifact/10d168d6-8a4f-49fe-9b39-471e067a6bc7>).
No code to write and nothing to run. Attempt each before opening `SOLUTIONS.md`.

The rungs climb: explain → identify → predict → modify → trace → spot the flaw →
handle failure → design.

---

## Rung 1 — Explain

**1.1** In your own words, with no Java terms at all, explain to a non-programmer why
two people each adding one box to a stockroom can leave the tally showing one box
added. Then say what the single-key-on-the-door fix actually changes — and what it
does *not* change.

**1.2** A colleague says: *"I made the field `volatile`, so the counter is now
thread-safe."* Write the two-sentence reply that says exactly what they fixed and
exactly what they did not. Then name the one situation where their instinct would
have been right.

**1.3** State the difference between **safety** and **liveness** in one sentence
each, and classify these four as one or the other: a lost update; a deadlock; a
buffer that goes to −1; a thread that never notices a stop flag. For each, say
whether waiting longer could possibly help.

---

## Rung 2 — Identify

**2.1** Open **scenario 1** and run three steps: Thread A, then Thread B, then Thread
A. Now read the screen and answer without stepping further: what is in `count`? What
is in Thread A's `r`? What is in Thread B's `r`? Which of those three numbers is
shared, and why are the other two not?

**2.2** Open **scenario 5** and run its demonstration until both consumers are
asleep. From the Locks panel alone, answer: who holds `buffer`? Who is in the entry
set? Who is in the wait set? Then say what would have to happen for each of the two
sleepers to become RUNNABLE again — the answers are different in kind, not just in
timing.

**2.3** In **scenario 3**, run Thread A once. Look at the "Run one step of" controls.
Why is the Thread B button disabled, and what would have to be true on the screen for
it to become enabled again?

**2.4** In **scenario 2** with `volatile` off, run the demonstration to the end. Point
at the two places on screen that together prove this is a *visibility* failure and
not a *timing* failure.

---

## Rung 3 — Predict

**3.1** Three threads each run `count++` once on a shared `int` starting at 0, with no
synchronization. What is the full set of possible final values? Justify the highest
and the lowest.

**3.2** In **scenario 4**, before touching anything: Thread A has taken `accountA` and
Thread B has taken `accountB`. Both now ask for the other. Predict the exact state of
each thread and each lock after two more steps. Check on screen.

**3.3** In **scenario 5**, suppose the producer runs *completely* before either
consumer takes the lock. Predict the final value of `items` and the final state of
each consumer, for the `if` version. Then predict it for the `while` version. Run
both (use the ribbon to rewind and re-drive the schedule) and say which of your two
predictions was the one that surprised you.

**3.4** Open the **Thread pool** tab. Set 3 workers, queue 12, 12 tasks, 5 ticks.
Before pressing Play, write down the tick at which everything finishes. Then do it
for 5 workers. One of those two numbers is not what the naive formula predicts —
which one, and why?

**3.5** In **scenario 6**, both threads read 0 and Thread A's compare-and-set
succeeds. Predict what Thread B's compare-and-set returns, what B does next, and how
many total compare-and-set attempts the run makes. Check the counters.

---

## Rung 4 — Modify

**4.1** In **scenario 3**, tick *"Remove the `synchronized` block"* and drive the same
interleaving that scenario 1 uses (A, B, A, A, B, B). What is the final count? Now
put the lock back and try to drive that same interleaving. Describe precisely what
stops you — and say why "the lock made it slower" is the wrong description of what
happened.

**4.2** In **scenario 4**, turn on *"lock accounts in the same order"*. The deadlock
disappears. Explain why an ordering rule is enough, using the four conditions for
deadlock — and say which one of the four the rule actually attacks.

**4.3** In **scenario 2**, turn `volatile` on. Watch the Worker's *private copy* row
across the whole run and describe what changed about it. Then answer: would putting
the flag read inside a `synchronized` block have worked too? What would it have cost?

**4.4** In the **Thread pool** tab, set queue capacity to 0 with 2 workers and 12
tasks. Record the rejection count. Now tick *CallerRunsPolicy* and record it again.
Both configurations are "correct". Describe the situation in which you would choose
each, and name the third option and the failure mode it buys you.

**4.5** In the pool tab with nesting on and 2 workers, the pool deadlocks. Raise the
worker count until it completes. Explain why this is a *dangerous* fix rather than a
real one, in terms of what changes under production load.

---

## Rung 5 — Trace

**5.1** By hand, on paper, complete this table for two unsynchronized threads running
`count++` from `count = 5`, under the interleaving A-read, B-read, B-add, B-write,
A-add, A-write. Fill in memory and both registers after every step, then state the
final value and how many increments were lost.

| Step | Who | Instruction | `count` in memory | A's `r` | B's `r` |
|---|---|---|---|---|---|
| 1 | A | `r = count` | | | |
| … | | | | | |

**5.2** Trace **scenario 5** with `if`, by hand, from the moment the producer calls
`notifyAll()`. For each of the next six steps, write which thread runs, its state
before and after, who owns `buffer`, and the value of `items`. Your trace must show
the exact step at which the second consumer's decision becomes wrong, and name what
it is relying on that is no longer true.

**5.3** Trace **scenario 6** by hand from "both threads have read 0" to termination.
Write down every compare-and-set with its expected value, the actual value in memory,
and the outcome. State the total wasted work in units of "additions performed but
thrown away".

---

## Rung 6 — Spot the flaw

Each of these is a described design, not code you can run. For each: construct a
concrete interleaving (with real values, like the traces above) in which it produces
a wrong answer or hangs, and name the guarantee that is violated.

**6.1** *"We removed the lock from `get()` because reads can't corrupt anything —
only writes need synchronizing. All writers still synchronize properly on the same
lock."* What can `get()` return that it should not, and why does the writers'
diligence not help?

**6.2** *"Our cache is a `ConcurrentHashMap`, which is thread-safe, so this is
fine:*
```java
if (!cache.containsKey(k)) cache.put(k, expensiveLoad(k));
```
*"* Both calls really are atomic. Show the interleaving that still breaks it, and say
what the actual invariant was that got violated. What single method call fixes it?

**6.3** *"Double-checked locking without `volatile` is fine on x86 — we tested it
thoroughly on our laptops and in CI."* Name what is wrong with the reasoning, what
the reader can observe that it must not, and why the test evidence is worthless. Which
earlier lab measured this exact thing?

**6.4** *"To avoid deadlock we changed every `synchronized` to `tryLock()` with an
immediate retry on failure. Now nothing can ever block forever."* Construct the run
where this hangs anyway. What is that failure called, and why is it harder to
diagnose than the deadlock it replaced?

**6.5** *"Our worker catches `InterruptedException`, logs it, and continues the loop
so a transient interrupt can't kill the worker."* Describe what breaks at shutdown,
what specific piece of information was destroyed by that catch block, and the
two-line fix.

---

## Rung 7 — Handle failure

**7.1** A `synchronized` method updates an order and then calls a payment provider
over HTTP inside the same block. The provider starts taking 30 seconds. Describe,
step by step, what happens to the other 50 request threads, what CPU and latency
graphs show, what a thread dump shows, and why adding more threads makes it worse.
Then redesign the method so the outage cannot happen, and state precisely which
invariant you must be careful not to break while shrinking the critical section.

**7.2** Extend scenario 5's design so it survives *multiple producers and multiple
consumers of a bounded buffer of capacity N*, on paper. Say what each side waits on,
what each side signals, why `notifyAll()` rather than `notify()` becomes important
once producers and consumers share one monitor, and what the standard library class
is that you should have used instead.

**7.3** Failure 7 in the README (pool starvation deadlock) has no runtime detection.
Design a monitoring rule that would have caught it *before* it became an outage,
using only the four pool metrics named in stage 13. Then design the code-level rule
that would have prevented it, and say what a reviewer should grep for.

**7.4** A pooled request handler puts the tenant id in a `ThreadLocal` and clears it
"at the end of the handler". A rare exception path skips the clear. Describe the
symptom the customer reports, why it is intermittent, why it is worse than a crash,
and give two fixes — one that keeps `ThreadLocal` and one that removes the whole
class of bug.

---

## Rung 8 — Design

**8.1 — The payments worker.** Design the threading for a service that consumes
payment instructions from a queue and calls three downstream providers. Constraints:
8 cores; each provider call waits ~200 ms and computes ~5 ms; provider C fails over
to a 3-second timeout roughly 1% of the time; the same payment must never be sent
twice; and the service must drain cleanly within 30 seconds on deploy.

State: how many pools and why; the size of each and the arithmetic behind it; queue
type and capacity; the rejection policy and what happens to a rejected payment; what
shared mutable state exists and how each piece is protected; every place a lock is
held and proof that none is held across a network call; the shutdown sequence; and
the five things you would put on a dashboard. Then say which parts of this design
`java-virtual-threads` would let you delete outright, and which parts survive
unchanged — and why.

**8.2 — The duplicate that got through.** Reconciliation shows 12 payments in a month
were sent twice, out of 40 million. There is no error in any log. Using this lab's
failure catalogue, list the candidate mechanisms, and for each one name the single
observation that would confirm or eliminate it. Then say which of them cannot be
distinguished by observation alone, and what you would have to add to the system to
tell them apart in future. (Compare your answer with `idempotency` and
`optimistic-vs-pessimistic-locking` — one of them argues the fix does not belong in
the locking layer at all.)

**8.3 — The same problem, three scales.** A lost update can happen between two CPU
registers (this lab), between two database transactions (`isolation-levels`), and
between two HTTP requests with a human in the middle
(`optimistic-vs-pessimistic-locking`). Write the one-paragraph account of what is
identical at all three scales and what is genuinely different. Then explain why the
in-JVM version can use a plain lock while the cross-machine version
(`distributed-locking`) cannot — and what specifically the distributed version has to
add to compensate.
