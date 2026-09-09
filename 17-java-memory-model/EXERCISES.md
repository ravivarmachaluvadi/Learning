# Exercises — the Java Memory Model

Everything here is pencil-and-paper, work in **Store Buffer Theatre**
(`visualization.html`), or a command from the README that you already have.
Nothing new to install. Attempt each rung before opening `SOLUTIONS.md`.

The rungs climb: explain → identify → predict → modify → trace → spot the flaw →
handle failure → design.

The five experiments and the test suite are the ones in `src/`:

```
./run.sh Visibility plain | volatile          (and -Xint in front)
./run.sh Reordering plain | volatile          (and -Dtrials=N)
./run.sh LostUpdate plain | volatile | synchronized | atomic
./run.sh Publication unsafe | finalfields | volatileref
./run.sh DoubleCheckedLocking
./run.sh Tests
```

---

## Rung 1 — Explain

**1.1** In your own words, with no Java terms at all, explain to a non-programmer
why a worker can keep checking a signal for ever when the signal was switched on
several seconds ago. Then say what `-Xint` changed about that program, and — this
is the harder half — say exactly what it did *not* change.

**1.2** A colleague says: *"I made the counter `volatile`, so it's thread-safe
now."* Write the two-sentence reply that says precisely what they fixed and
precisely what they did not. Then name the one situation where their instinct
would have been exactly right, and say why that situation is different.

**1.3** Define **visibility**, **atomicity** and **ordering** in one sentence
each, with no overlap between the three. Then classify each of these as a failure
of one of them: a spinning worker that never stops; a counter that ends on
1,954,887 instead of 8,000,000; the 0-and-0 outcome; a reference that is non-null
while its fields are still zero.

**1.4** Section 12 says a data race makes the set of permitted answers *larger*,
rather than making the program random. Explain the difference to someone who
thinks those are the same statement, using the outcome table in section 5.

---

## Rung 2 — Identify

**2.1** Open the visualization, tab **The impossible outcome**. Press *Core 0: run
next line* once, and stop. Without pressing anything else, answer from the screen
alone: what is the value of `x` in shared memory, what is the value of `x` as far
as Core 0 is concerned, and what is the value of `x` as far as Core 1 is
concerned? Name the three places a single value can be living at that moment.

**2.2** `./run.sh Publication unsafe` prints two counters: *partially-visible
objects observed* and *publication never seen at all*. Which of the two is the
experiment actually about? Say what the other one is measuring, and which earlier
section of the README that second symptom belongs to.

**2.3** Run `./run.sh Tests`. Every line is tagged `[ASSERT]` or `[OBSERVE]`. From
the output alone, without reading the source, explain the rule that decides which
tag a check gets. Then name the one line in the output that would be a genuine JVM
bug if it ever flipped.

**2.4** `Reordering.java` measures a race using plain fields `r1` and `r2`, but
its two coordination fields `ticketA` and `ticketB` are `volatile`. Identify which
of the four things `volatile` buys (section 11) is the one making the harness
correct, and say what would go wrong if the tickets were plain.

**2.5** In the README's section 6 table, two different pieces of the system each
produced a bug. Say which experiment exposed each one, and give the one-sentence
test that tells you which of the two you are looking at in a real incident.

---

## Rung 3 — Predict

**3.1** You take this folder to an Apple Silicon Mac and run
`./run.sh Publication unsafe`. Predict the *partially-visible objects* count.
Then state clearly how confident you are and why your confidence here is lower
than for any other prediction in this rung.

**3.2** Section 5 saw 61,354 occurrences of 0-and-0 in 1,000,000 trials. Predict
the count for `-Dtrials=20000000`. Then give the reason your prediction could be
wrong by a factor of five without anything being broken, and name the two other
counts in the README that make that point.

**3.3** `LostUpdate` runs 8 threads. Predict what happens to the *percentage*
lost if you drop it to 2 threads, and what happens if you raise it to 64. Predict
directions and a reason, not numbers.

**3.4** Predict which of the four `DoubleCheckedLocking` versions is slowest on
the hot path after the first call, and which is fastest. Then say which of those
two facts actually matters for the decision in section 15, and why.

**3.5** A class has `private static final Service INSTANCE = new Service(...)`.
Two hundred threads call `get()` concurrently with no synchronization anywhere.
Predict how many of them can see a half-built `Service`, and name the exact rule
that makes your answer a guarantee rather than a probability.

---

## Rung 4 — Modify

**4.1** Make `Visibility` in `plain` mode terminate reliably, three different
ways, without changing the flag's declaration and without adding `volatile`,
`synchronized` or an atomic anywhere. For each of the three, say what it actually
changed: the rule, or only the odds. (Do this on paper — the source in `src/` is
not to be edited.)

**4.2** In the visualization, produce the 0-and-0 outcome. Reset, and now produce
1-and-1 using the same two *run next line* buttons in the same order. Describe the
extra button you had to press, what it models in real hardware, and why nothing in
Java says when it happens.

**4.3** Take `BrokenDcl` from section 15 and fix it in three different ways, each
of which is correct by the rules. Rank them by how much you would like to see them
in a code review, and justify the ranking with something other than taste.

**4.4** `LostUpdate atomic` uses `AtomicLong`. Predict what changes if it used
`LongAdder` instead, in both the final total and the elapsed time, and explain the
mechanism. Then name the one thing `LongAdder` gives up that `AtomicLong` has.

**4.5** In the visualization, switch tab 2 to `volatile boolean stop;` and drive
it. Describe what changed in the *What the JIT runs* panel, and translate that
change into one sentence about registers.

---

## Rung 5 — Trace

**5.1** By hand, complete this table for the two threads of section 5 running the
sequence that produces 0-and-0. Fill in every column after every step, then state
the final outcome and the exact step at which it became unavoidable.

| Step | Who does what | `x` and `y` in memory | What is in the two out-trays | What each has read so far |
|---|---|---|---|---|
| 1 | Ana runs `x = 1` | | | |
| 2 | | | | |
| … | | | | |

**5.2** Trace the transitivity chain in section 10 step by step: Ana's three
plain writes, her volatile write, Ben's volatile read, Ben's lock release, Dev's
lock acquire. At each step, write down what Dev is now guaranteed to see. Then
break exactly one link — Ben takes a *different* lock from Dev — and say precisely
which of Dev's guarantees survive and which vanish.

**5.3** Trace `instance = new Service("broken", 1)` on a machine that is allowed
to reorder one store past another. Write the four machine steps in the order that
breaks it, and then write down, step by step, what a second thread on the fast
path sees at each of those moments.

**5.4** Trace the `-Xint` version of `Visibility` against the JIT version, side by
side, for the first three passes of the worker's loop. Show where the two traces
diverge and name the single instruction that is present in one and absent in the
other.

---

## Rung 6 — Spot the flaw

Each of these is a described design, not code you can run. For each: construct a
concrete sequence in which it produces a wrong answer or hangs, and name the
guarantee that is being assumed but not bought.

**6.1** *"The config object is behind a `volatile` reference, so it's safe to
share. Threads also update a counter inside it as they go."* What is safe, what is
not, and why does the `volatile` reference not help the second half at all?

**6.2** *"Double-checked locking without `volatile` is fine — we ran it in CI ten
million times on every build for two years and it has never once failed."* Name
what is wrong with the reasoning, what a reader can legally observe that it must
not, and why that test evidence is worth nothing. Which section of this README
produced exactly that evidence on purpose?

**6.3** *"We synchronize the write, so the object is definitely fully built before
anyone can see the reference."* Show why writing under a lock does not help a
reader who never takes that lock, using the four steps from section 15.

**6.4** *"The constructor finishes by setting `volatile boolean ready = true`, so
by the time anyone sees `ready` the object is complete."* Construct the case where
a second thread sees a fully-`ready` object that is nonetheless not safely
published, and name the rule from section 14 that was broken.

**6.5** *"We call `worker.start()` and then `Thread.sleep(50)` before writing the
config, so the worker is definitely up and will see it."* Name every wrong
assumption in that sentence. One of them is about ordering, one is about timing,
and one is a genuine happens-before edge being used backwards.

**6.6** *"We fixed the drifting counter by making the field `volatile` and it went
from losing 6 million to losing 5.7 million, so we're on the right track — we just
need to also make the loop tighter."* Say what the data in section 7 actually
shows, and what the next step should have been.

---

## Rung 7 — Handle failure

**7.1** At 02:40 a dashboard shows one thread pinned at 100% CPU with throughput
flat, no errors, and no change in latency. It has been like that for six hours.
Describe what you would look at first, what a thread dump would show, how you
would tell this apart from an infinite retry loop, and what the fix is. Then say
what you would put in the postmortem about why nobody's tests caught it.

**7.2** A service is moving from Intel instances to Graviton next sprint. Write
the review pass you would run over the codebase beforehand: what you grep for,
what you read by hand, what you ask the authors, and what you would put in front
of the load test. Be specific about which of this lab's five experiments each item
corresponds to.

**7.3** A payment counter is drifting about 0.3% low under peak load and is exact
at low load. Design the observations that would distinguish three candidate
causes: lost updates, a visibility failure, and a genuine logic bug in the
business rule. For each cause, name the single observation that would eliminate
it.

**7.4** `Tests.java` starts a daemon thread in its plain-flag observation that may
never stop, and it cannot be killed because `Thread.stop` was removed in Java 20.
Describe what that does to every check that runs after it, say which figures in
section 16 you would therefore not quote, and design a harness change that removes
the problem without removing the observation.

**7.5** A team proposes running production with `-Xint` for a week "to rule out a
JIT bug" after seeing the hang in section 3. Write the reply. It must say what
that experiment would genuinely tell them, what it would cost, and what to do
instead that answers the same question in an hour.

---

## Rung 8 — Design

**8.1 — The ARM migration checklist.** Design the concurrency review that a
service must pass before moving from x86 to ARM. State: which code shapes you
search for and with what; how you decide whether a given shared field is safely
reached; what you do about third-party libraries you cannot review; what you run
before the migration and on what hardware; and the one thing on the list that
would have caught the bug in section 15. Then say which items on your list are
*evidence* and which are only *reassurance*, and be honest about the ratio.

**8.2 — Hot-reloadable configuration.** Design a configuration object that is
built at startup, read on every request by hundreds of threads, and replaced
wholesale a few times a day when an operator pushes a change. State: how the
object is built, how it is published, how readers get it, what happens to a
request that is halfway through reading the old one, and which of section 13's
four safe-publication routes you chose and why. Then say what you would have to
change if a *single field* inside it needed to be updated independently, and why
that is a much harder problem.

**8.3 — The same idea at three scales.** *happens-before* appears in this lab
between two CPU cores, in `causal-consistency` between two machines, and in
`redis` and `acid` as one of three different meanings of the word "atomic". Write
the one-paragraph account of what is identical at all three scales and what is
genuinely different. Then explain why the in-JVM version can be enforced by a
single instruction while the cross-machine version cannot, and what the
distributed version has to carry instead.

**8.4 — The test you would actually write.** You have been asked to add a
regression test for a race that was fixed last month. Design what you would
deliver. State what the test can prove, what it cannot, what you would name it so
that a future reader does not over-trust it, and what you would put in the code
review checklist instead of the test. Use section 16's split between assertions
and observations as your structure.
