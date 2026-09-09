# Solutions — the Java Memory Model

Worked answers. Rungs 7 and 8 are design discussions, not single correct outputs
— compare the shape and the reasoning, not the wording.

Every figure quoted here comes from the runs recorded in README section 22: one
machine, one run per command, OpenJDK 25.0.4.1 on amd64. Where an exercise asks
you to predict something that was never run, the answer says so instead of giving
you a number.

---

## Rung 1 — Explain

**1.1** The worker is not looking at the signal. Somebody decided that since the
worker's own loop never changes the signal, there was no point walking over to
check it every time, so the value was copied once at the start and the worker has
been checking that copy ever since. The real signal did change. Nothing is ever
going to go and look again.

`-Xint` switched off the part of the system that made that decision. Every pass
of the loop now genuinely goes and reads the signal, so the change is picked up
immediately. On one run it exited after 147,698,840 passes.

What it did **not** change is the program's correctness. The code is still
broken; it is just being run by something too simple to exploit the breakage.
Move it back to a normal JVM, or to a different chip, or to next year's compiler,
and it is a hang again. `-Xint` removed one optimiser's permission to use a right
the language had already granted.

**1.2** *"You fixed whether other threads can ever see the value, which was a real
problem. You did not fix that `count++` is a read, an add and a write with a gap
in between — two threads can both read 41, both compute 42, and both write 42,
and `volatile` allows exactly that."*

Their instinct is right for a **flag that one thread writes and others only
read**. That is section 3's experiment, and `volatile` is the correct and
complete fix there. The difference is that a flag has no read-modify-write: there
is no gap to protect, because nothing is computed from the old value.

Worth adding, because it lands harder than the argument: on one run the volatile
counter still lost 5,762,264 increments *and* took 328 ms against the plain
version's 69 ms. It paid the whole bill and bought nothing.

**1.3**

- **Visibility** — whether one thread's write is ever seen by another thread's
  read at all.
- **Atomicity** — whether another thread can observe an operation half-done.
- **Ordering** — whether two writes become visible in the order they were
  written.

The classification:

| Symptom | Which one failed |
|---|---|
| spinning worker that never stops | visibility |
| counter ending on 1,954,887 of 8,000,000 | atomicity |
| the 0-and-0 outcome | ordering |
| non-null reference with zero fields | ordering, which is what makes publication unsafe |

The fourth is the interesting one. It is not an atomicity failure: nobody caught
the constructor mid-write. The four writes each completed. They just became
*visible* in the wrong order.

**1.4** "Random" would mean the answer is drawn from a distribution each time you
run. That is not what happens. What happens is that section 5's shuffle table has
three rows in your head and four rows in the specification.

Your program is not wrong 6% of the time. Your *reasoning* is wrong 100% of the
time, and 94% of the time that does not show. That is why the fix is never "make
it happen less often" and always "delete the extra row".

---

## Rung 2 — Identify

**2.1** After one press of *Core 0: run next line*:

- **shared memory** says `x = 0`,
- **Core 0** believes `x = 1`, because it can read its own store buffer,
- **Core 1** believes `x = 0`, because the buffer is private.

The three places one value can live at that instant are: shared memory, a core's
private store buffer, and a core's register. Only the first is shared. That is
the entire lab in one sentence.

**2.2** The experiment is about **partially-visible objects observed**. That is
the count of times the reader held a reference to an object whose fields were not
the ones the writer had just put in it. On this hardware it read 0 in all three
modes.

**Publication never seen at all** is a different symptom: the reader spun 5,000
times and never saw a non-null reference in that round. The write never became
visible in time, or the read was hoisted. That belongs to README section 3, not
section 13.

The contrast is worth quoting anyway. Plain reference: 18,048 rounds missed out of
200,000. Volatile reference: **4**. The tearing never appeared, and the visibility
difference appeared immediately.

**2.3** The rule is: **`[ASSERT]` for things the model forbids, `[OBSERVE]` for
things the model permits but does not require.**

You can see it from the output because the observations report a *number* and no
verdict, while the assertions report pass or fail. A quiet run of an observation
proves nothing, so asserting on it would be a lie, and the file refuses to tell
that lie.

The line that would be a genuine JVM bug if it flipped is any of the five
assertions. The sharpest is
`volatile_write_publishes_the_plain_writes_before_it`: it reported 0 torn reads in
200,000 rounds, and the model allows exactly 0. A single torn read there would be
a specification violation, not bad luck.

**2.4** It is **rule four, the piggyback**. Thread B writes its result into the
plain field `r2` and then writes its `volatile` ticket. Thread A reads the
volatile ticket and then reads `r2`. Because A saw the new ticket value,
everything B did before writing it — including the plain write — is guaranteed
visible.

If the tickets were plain, the harness would have no edges at all. The two
threads would not reliably start each trial together, and thread A would be
counting outcomes out of a field it has no guarantee of seeing. The measuring
instrument would have the same bug it is measuring. **The tool that catches the
bug is built out of the fix.**

**2.5** The JIT compiler kept a flag in a register, which Experiment 1 (section 3)
exposed. The CPU core let a store sit in its private buffer, which Experiment 2
(section 5) exposed.

The one-sentence field test: **does the thread ever see the new value, at any
point, ever?** A hoisted load never does — it will spin for the rest of the
process's life. A store-buffer delay always resolves; the buffer drains in
nanoseconds. So "stuck for ever" is the compiler, and "briefly saw the old value
and then caught up" is the hardware.

---

## Rung 3 — Predict

**3.1** Predict **greater than zero**, and expect it to be small and erratic.

The confidence caveat is the point of the question, and there are two halves to
it. First, this run was never done, so nothing here is evidence — README section
22 lists ARM behaviour among the things this lab claims but does not demonstrate.
Second, and more importantly, even a real ARM run showing zero would not tell you
the code is safe. That is the same trap as the x86 run in section 13, one chip
further along. The prediction is about hardware; the conclusion has to come from
the rules.

**3.2** Proportional scaling gives roughly 1.2 million, and you should write that
down and then immediately distrust it.

The reason it can be off by five is that the rate is not a property of the
program. README section 5 has three counts of the same phenomenon on the same
machine: 1 in 4 from the `Tests` suite, 1 in 16 from `Reordering plain`, and 1 in
42 from the figure on the visualization page. Anything that changes how tightly
the two threads are coupled — core scheduling, JIT warm-up, another process on
the box — moves the rate by more than the trial count does.

**3.3** With **2 threads** the percentage lost falls sharply. Fewer workers means
fewer chances that another one lands in the gap between your read and your write.

With **64 threads** on a 16-core box the percentage rises further, and the reason
is worth stating carefully: it is not that more threads run at once — only 16 can
— but that each thread is descheduled far more often, and every deschedule
lengthens the window between its read and its write.

Neither variant was run. The direction is safe to assert; a number is not.

**3.4** After the first call, `Eager` and `HolderIdiom` are fastest: both reduce
to a plain read of a `static final` field, which the JIT can constant-fold
outright. `VolatileDcl` is slowest, because the fast path still performs one
volatile read on every call.

And now the important half: **that fact does not matter.** The performance gap
between a volatile read and a plain read is nanoseconds on a path that is called
after a lock-free check. Section 15 recommends the holder idiom because it is
*simpler and impossible to get wrong*, not because it is faster. If the ranking
changed your answer, you have optimised the wrong axis.

**3.5** **Zero.** None of the 200 threads can see a half-built `Service`.

The rule is class initialisation. The JVM initialises a class exactly once, under
a lock it manages itself, and every thread that touches the class either performs
that initialisation or waits for it. That gives a happens-before edge from the
static initialiser to every subsequent read of the field. It is a guarantee in
the specification, not a probability, and it is the reason the holder idiom in
section 15 works.

---

## Rung 4 — Modify

**4.1** Three that work, and they are not equally honest:

1. **Run with `-Xint`.** Changes the rule for that process: no compiler, no
   hoisting. Correct, and roughly a hundred times slower.
2. **Put a `System.out.println` in the loop.** Changes the rule *by accident* —
   the stream is synchronized, so the print is a real happens-before edge. It
   also stops the loop being simple enough to optimise. Correct for reasons you
   did not intend, and it will stop being correct the moment somebody removes the
   log line.
3. **Call `Thread.sleep(1)` in the loop.** Changes only the odds. `sleep` creates
   no edge whatsoever. In practice the loop is no longer hot enough to be
   compiled the same way, so it usually works, and it is the worst of the three
   because it looks deliberate.

The grading is the answer: one changes the rule on purpose, one changes it by
side effect, and one changes nothing but the probability. Only the first is a
fix, and it is not one you can ship.

**4.2** To get 1-and-1 you must press *flush* on both cores after the two writes
and before the two reads. The full sequence is: Core 0 run, Core 1 run, Core 0
flush, Core 1 flush, Core 0 run, Core 1 run.

The flush models a store buffer draining to the shared cache line. In real
hardware nothing in your program triggers it. It happens when the buffer fills,
when a fence forces it, or whenever the core gets round to it. **Java says
nothing at all about when it happens**, which is exactly why the page makes it a
button you press rather than a timer.

**4.3** Three correct fixes, ranked:

| Rank | Fix | Why it ranks there |
|---|---|---|
| 1 | the holder idiom | lazy, free after the first call, and there is nothing to get wrong |
| 2 | `static final`, eager | bulletproof and simplest, but you pay the cost even if it is never used |
| 3 | `volatile` on the field | correct since Java 5, and still four lines a reviewer must read carefully |

The justification that is not taste: rank by **how many ways a future edit can
break it**. Someone deleting the `volatile` keyword from option 3 breaks it
silently, and no test on x86 will notice. There is no keyword to delete in
options 1 and 2.

**4.4** `LongAdder` keeps a per-thread cell and sums them only when you ask for
the total. Under heavy contention that removes the compare-and-set retry storm,
so it should be substantially faster than `AtomicLong` and the final total should
still be exact.

Not run, so no number is given here. The `LostUpdate atomic` figure that *was*
run is 8,000,000 exact in 302 ms, against `synchronized` at 1,254 ms.

What `LongAdder` gives up: a meaningful instantaneous read. `AtomicLong.get()`
returns one value at one moment; `LongAdder.sum()` walks the cells while they are
still being written, so it is not a snapshot of any instant. It also has no
compare-and-set, so you cannot build a conditional update on it.

**4.5** In volatile mode the *What the JIT runs* panel stops showing a single
load before the loop and starts showing a real load from memory inside the loop,
on every pass.

In one sentence about registers: **the optimiser is no longer allowed to keep the
field in a register across loop iterations**, so the value the loop tests is
always the value in memory rather than a copy taken once.

---

## Rung 5 — Trace

**5.1** The sequence that produces 0-and-0, with the trays shown:

| Step | Who does what | `x` and `y` in memory | What is in the two out-trays | What each has read so far |
|---|---|---|---|---|
| 1 | Ana runs `x = 1` | 0, 0 | Ana `x←1` · Ben empty | nothing yet |
| 2 | Ben runs `y = 1` | 0, 0 | Ana `x←1` · Ben `y←1` | nothing yet |
| 3 | Ana reads `y` | 0, 0 | Ana `x←1` · Ben `y←1` | Ana got **0** |
| 4 | Ben reads `x` | 0, 0 | Ana `x←1` · Ben `y←1` | Ana **0**, Ben **0** |
| 5 | Ana's tray drains | 1, 0 | Ana empty · Ben `y←1` | Ana 0, Ben 0 |
| 6 | Ben's tray drains | 1, 1 | both empty | Ana 0, Ben 0 |

The outcome is 0-and-0, and it became unavoidable at **step 2**. Once both writes
are sitting in private buffers and neither has drained, both of the reads that
follow are going to find zeros in memory. Steps 5 and 6 are too late to matter;
the registers already hold their answers.

Note what steps 1 to 4 look like in a debugger: each thread executed its write
before its read, in source order, exactly as written. Nothing was reordered in any
sense you could observe by stepping. The write had simply not arrived where the
other thread was looking.

**5.2** The chain, with what Dev is guaranteed at each point:

| Step | What happens | What Dev is guaranteed once this link exists |
|---|---|---|
| 1 | Ana writes three plain numbers | nothing yet |
| 2 | Ana writes the volatile flag | nothing yet — the edge needs a reader |
| 3 | Ben reads the flag and sees it set | Ben is guaranteed Ana's three numbers |
| 4 | Ben works, then releases the lock | nothing yet — the edge needs an acquirer |
| 5 | Dev acquires the **same** lock | Dev sees Ben's work, and by transitivity Ana's three numbers |

Break link 5 by giving Dev a *different* lock and the chain snaps there.
Everything up to step 4 still holds: Ben still sees Ana's numbers, because that
edge is independent. What vanishes is every guarantee Dev had. Dev sees neither
Ben's work nor Ana's, and no amount of Ben having been careful helps, because a
monitor edge exists only between threads acquiring **the same** monitor.

This is the failure people find hardest to believe, because on the screen both
threads visibly took a lock.

**5.3** The order that breaks it, and what the second thread sees:

| Step | The writing thread does | What a fast-path reader sees at this moment |
|---|---|---|
| 1 | allocate raw memory for a `Service` | `instance` is still null; the reader takes the slow path |
| 2 | publish: `instance` = that address | `instance` is non-null — the reader returns it immediately |
| 3 | write `name = "broken"` | too late; the reader already has the object |
| 4 | write `id = 1` | too late |

Between steps 2 and 3 the reader holds a fully-typed, non-null `Service` whose
`name` is `null` and whose `id` is `0`. It never took the lock, so it
synchronised with nothing, so there is no rule saying it must wait for steps 3
and 4.

The reader does not crash. It gets a valid object with default field values and
carries on. That is why this bug reaches production as a `NullPointerException`
somewhere else entirely, hours later.

**5.4** Side by side, the worker's first three passes:

| Pass | With the JIT | With `-Xint` |
|---|---|---|
| — | one load of the flag into a register, before the loop | no such load |
| 1 | test the register | load the flag from memory, then test it |
| 2 | test the register | load the flag from memory, then test it |
| 3 | test the register | load the flag from memory, then test it |

They diverge before pass 1 and never converge. The instruction present in one and
absent in the other is **the load inside the loop body**. That is the whole
difference between a program that exits after 147,698,840 passes and one that
spins for ever.

---

## Rung 6 — Spot the flaw

**6.1** What is safe: seeing the reference, and seeing the object's state as it
was when it was published. The volatile write publishes everything the builder
did before it (section 11, rule four).

What is not safe: the counter. Once the object is shared, a plain field inside it
that several threads increment is section 7's bug, and the volatile reference is
in completely the wrong place to help. The reference orders *how you got to the
object*. It says nothing about what happens to the object's insides afterwards.

The sequence: two threads both read the counter as 41, both compute 42, both
write 42. One increment is gone. The reference was volatile throughout.

**6.2** The reasoning is wrong in one specific way: it treats absence of evidence
as evidence of absence, on a machine that is structurally incapable of producing
the evidence.

A reader can legally observe a non-null `instance` whose `name` is `null` and
whose `id` is `0`. On x86 it will essentially never happen, because the hardware
does not reorder one store past another and the JIT chose not to either.

The test evidence is worth nothing because CI runs on x86. Ten million passes on
a machine that cannot produce the failure is ten million passes of a test that
was never able to fail.

README **section 15** produced exactly that evidence on purpose: all four
versions, including the broken one, reported **0** half-built objects across 16
threads and 200,000 calls each.

**6.3** The four steps are: allocate, write `name`, write `id`, publish the
reference. Doing all four while holding a lock genuinely orders them *with
respect to other threads that take the same lock*.

The reader on the fast path does not take the lock. That is the entire purpose of
the fast path. So the writer's diligence buys an edge to a thread that never
turns up to collect it.

Put the two facts next to each other and the flaw is obvious: a lock creates an
edge between two threads that **both** acquire it, and the design is built around
one of them not acquiring it.

**6.4** The rule broken is **`this` escaping during construction** (section 14).

The case: the constructor registers the object somewhere — a listener list, a
static registry, a thread it starts — *before* it reaches the final line. Another
thread pulls it out of that registry, and now it is holding a reference that never
passed through the `ready` flag at all. The `volatile` write at the end of the
constructor is a fine edge for anybody who reads `ready`. It is no edge at all for
somebody who got the reference by another route.

The general shape: a safe-publication scheme protects the *route*, and it protects
only the route it was built for. Any second route around it is unprotected.

**6.5** Three wrong assumptions:

- **About ordering:** `sleep` creates no happens-before edge. Nothing about
  sleeping makes a write visible.
- **About timing:** "definitely up" is not something 50 milliseconds buys. Under
  load, thread start can take longer than that.
- **The edge used backwards:** `Thread.start()` *is* a real edge, and it is the
  one they should have used — but it orders writes made **before** `start()`, not
  after. Writing the config after starting the worker points the guarantee the
  wrong way round.

The fix is one line: write the config *before* `worker.start()`. Then the edge
they already had does the job, and the sleep can go.

**6.6** The data shows the opposite of progress. Section 7's run:

| Mode | Lost | Elapsed |
|---|---:|---:|
| plain | 6,045,113 | 69 ms |
| `volatile` | 5,762,264 | 328 ms |

The drop from 6.05 million to 5.76 million is noise on a non-deterministic race,
not a trend. The elapsed time nearly five-doubled. They bought a fence and got
nothing for it, and a tighter loop would make it worse rather than better, because
a tighter loop means more contention per second.

The next step was to stop tuning and change the operation: `AtomicLong` gave
8,000,000 exact in 302 ms on the same run. The bug is atomicity, and no amount of
visibility fixes it.

---

## Rung 7 — Handle failure

**7.1** First look: which thread, and is it making progress. A thread dump taken
twice, thirty seconds apart, is the whole diagnosis. A hoisted-flag spin shows the
thread `RUNNABLE`, in the same tiny frame, both times.

Telling it apart from an infinite retry loop: a retry loop moves. It allocates, it
logs, it makes syscalls, and successive dumps show different frames. A spin on a
stale register does none of that — no allocation, no I/O, no GC pressure, one
frame, for ever.

The fix is to make the flag `volatile`. If the flag is also being tested and set,
it needs an atomic instead.

For the postmortem: the tests could not have caught it. The bug requires the JIT
to have compiled the loop, which needs the loop to have run hot for seconds, and
it needs no synchronization to have crept in anywhere in the loop body. A unit
test satisfies none of those. Say that plainly rather than adding a test that
would not have caught it either.

**7.2** The review pass, mapped to the experiments:

| What you do | Which experiment it corresponds to |
|---|---|
| grep for `while (!` and `while (` on plain boolean fields | Experiment 1 — hoisted flags |
| grep for `++` and `+=` on shared non-atomic fields | Experiment 3 — lost updates |
| grep for `static` fields assigned outside a static initialiser | Experiment 4 — unsafe publication |
| grep for `synchronized` blocks containing a null check on a static | Experiment 5 — double-checked locking |
| read by hand every field shared between threads and name its edge | the whole of section 10 |

What you ask the authors: for each shared field, which of section 10's six
handshakes orders it. If they cannot name one, it is unsafe, regardless of what
the tests say.

Third-party libraries: you cannot review them, so you check versions and release
notes for known ARM issues and you rely on the fact that widely-used concurrency
libraries have been run on ARM by other people for years. This is the weakest item
on the list and you should label it as such.

Before migrating, run the full test suite on an ARM box. That is the highest-value
single action, and it is also the one most likely to give you a false green.

**7.3** The three candidates and the observation that kills each:

| Candidate | Eliminate it by observing |
|---|---|
| lost updates | the drift scales with **concurrency** and not with elapsed time; hold load flat and double the thread count, and the drift should roughly track it |
| visibility failure | the count would be wrong in a *sticky* way — a reader stuck on a stale value stops moving entirely rather than drifting by a fraction |
| logic bug in the business rule | the drift appears at low load too, once you look at enough volume; it does not care about concurrency at all |

The discriminator that does the most work is **low load**. A concurrency bug that
is exact at low load and 0.3% off at peak is a concurrency bug. A logic bug is
0.3% off at every load, and you only noticed it at peak because that is where the
volume is.

**7.4** What it does to the rest of the suite: the stuck daemon thread spins at
full speed on one core for every check that follows. On a 16-core box that is one
core gone, plus cache and scheduler pressure. Every subsequent timing is affected,
and the contended increment tests are affected most because they are exactly the
ones sensitive to how often a thread is descheduled.

Which figures not to quote: the elapsed times of anything after the plain-flag
observation, and — more carefully — the *rates* in the later observations. The
counts of lost updates are still valid as demonstrations that updates are lost.
They are not valid as measurements of how many.

A harness change that keeps the observation: give the flag a second, `volatile`
kill switch that the spinning loop also tests. Set it after the observation is
recorded. The loop then exits on the volatile flag whether or not it ever saw the
plain one, the observation is unchanged, and no thread is left running. This costs
you nothing, because the thing under observation is the *plain* flag, and adding a
separate volatile exit does not give the plain one any new visibility.

**7.5** The reply, in three parts.

*What it would tell you:* if the hang disappears under `-Xint`, you have confirmed
that the optimiser is exploiting a right the language gave it. That is real
information. But it is not a JIT bug; it is your code being incorrect in a way the
JIT is entitled to exploit.

*What it would cost:* interpreting every bytecode costs roughly two orders of
magnitude in throughput. You would need a hundred times the fleet, and the
experiment would be running in front of customers.

*What to do instead, in an hour:* take a thread dump twice, thirty seconds apart.
If the thread is `RUNNABLE` in the same frame both times, with no allocation and
no I/O, you have your answer without touching production. Then read the loop and
find the plain field. The whole diagnosis is in the code, and it is faster to read
it than to run the experiment.

---

## Rung 8 — Design

**8.1 — The ARM migration checklist.**

The search phase, and what each item is for:

| Search | What you are looking for |
|---|---|
| plain `boolean` / `volatile`-less fields tested in a loop | a hoisted flag that will spin for ever |
| `++`, `+=`, `--` on shared fields | lost updates |
| assignment of a shared reference outside a constructor or static block | unsafe publication |
| `synchronized` around a null check on a static field | double-checked locking |
| any `Thread.sleep` used as coordination | a race being hidden by timing |

The read-by-hand phase: for every shared field, name the happens-before edge that
orders it. Section 10 has six. If a field cannot be assigned to one of them, it is
unsafe. This is the only item on the list that is a proof rather than a heuristic.

The question you ask authors: "which edge orders this field?" not "is this
thread-safe?". The second question invites reassurance; the first has a checkable
answer.

Third-party code you cannot review: check versions, check release notes for ARM
issues, and rely on the fact that the widely-used libraries have run on ARM in
other people's production for years. Label this as the weakest item and do not
count it as coverage.

Before the migration: run the full suite on an ARM box, and run any concurrency
stress you have for far longer than usual.

The item that would have caught section 15's bug: **the double-checked-locking
grep**, followed by reading it. Not the ARM test run — a broken singleton can
easily pass on ARM too, because the reorder has to actually happen at the moment
a second thread is on the fast path.

Evidence versus reassurance, honestly: the by-hand edge review is evidence. The
greps are triage that tells you where to look. Everything else — the ARM test run,
the library versions, the stress duration — is reassurance. The ratio is roughly
one item of evidence to five of reassurance, and pretending otherwise is how teams
migrate confidently into an outage.

**8.2 — Hot-reloadable configuration.**

Build it as a fully immutable object: every field `final`, every collection copied
and wrapped, nothing mutable reachable from it, and `this` never escaping the
constructor. That is section 14's freeze, and it means the object needs no
synchronization of any kind once it exists.

Publish it through a single `volatile` reference, or an `AtomicReference` if you
ever need compare-and-set on a swap. That is route two of section 13's four.

Readers do one thing: read the reference into a local variable, once, at the top
of the request, and use that local for the whole request.

That local is the answer to "what happens to a request halfway through". It keeps
the old object. The old object is immutable, so it stays internally consistent for
as long as anyone holds it, and it becomes garbage when the last request holding it
finishes. There is no tearing, no lock, and no window.

Why this route and not the others: a static initialiser cannot be reloaded; final
fields alone give you no way to swap the object; a lock would serialise every
request against a change that happens twice a day. The volatile reference is the
only one of the four that supports replacement.

If a *single field* inside it needed independent update, the design breaks, and it
is worth being clear about why. You would lose immutability, so every reader would
need an edge to that field specifically, and you would immediately have a
consistency question the current design does not have: can a request see the new
value of field A and the old value of field B? With whole-object replacement the
answer is structurally no. With per-field updates you have to define an answer and
enforce it, which usually means a lock or a version number, and you are back to
paying on every request.

**8.3 — The same idea at three scales.**

What is identical: all three are answers to "which writes must a reader see?", and
all three answer it by naming an ordering relation rather than by appealing to
time. In the JVM it is *happens-before* between two cores. In
`causal-consistency` it is *happens-before* between two machines, carried by
version vectors. In `redis` and `acid`, "atomic" is the related question of what a
reader may observe half-done. In every case the naive model — one global order,
wall-clock time — is the thing that fails.

What is genuinely different: the JVM knows when a thread has died and can enforce
an edge with a single instruction, because everything is inside one failure
domain. Across machines there is no shared clock, no reliable failure detection,
and no instruction that reaches both ends. So the distributed version has to carry
its ordering **in the data**: version vectors, dependency metadata, causal
delivery buffers. What costs one fence inside a JVM costs a metadata field on
every write and a delivery check on every read.

Why a single instruction is enough here: the fence is executed by hardware that
both parties are physically part of, and cache coherence guarantees the result
propagates. There is no equivalent across a network, which is why
`causal-consistency` spends its whole length on machinery this lab gets for one
keyword.

**8.4 — The test you would actually write.**

What you deliver is two things, and the second is the important one.

*The test:* a stress loop that exercises the fixed path from many threads and
asserts the invariant that was violated. Structure it like section 16's split.
Anything the model **forbids** becomes an assertion — those are real tests, and a
failure is a real bug. Anything the model merely **permits** becomes an
observation that reports a count and never fails the build.

What it can prove: that the invariant held under the schedules this machine
happened to produce. If you also assert something the model forbids, it can prove
a genuine violation the day one appears.

What it cannot prove: that the race is gone. A race that does not appear has not
been shown to be absent — README section 13's zero-anomaly run is exactly that
result, obtained deliberately.

What you name it: something that tells the truth to a future reader, such as
`observes_no_torn_config_under_load`, not `configIsThreadSafe`. The name is the
main defence against over-trust, because the name is what somebody reads at 3am
when it goes green.

What goes in the review checklist instead: the field-by-field edge review from
8.1. "Every shared field names its happens-before edge" is a check that can
actually fail for the right reason, and it is the only one on this page that
scales to code nobody has run yet.
