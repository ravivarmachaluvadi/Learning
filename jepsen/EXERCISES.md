# Jepsen — Exercises

Eight rungs, from saying it back in your own words to designing a test for a real
system. **None of them ask you to write code.** Rungs 2 to 6 are checkable against
[`visualization.html`](visualization.html) — open it and keep it open.

Worked answers are in [SOLUTIONS.md](SOLUTIONS.md). Attempt each rung first. Rungs 7
and 8 are discussions rather than single answers, so compare reasoning rather than
checking a box.

**Simulator reminders.** Tab 1 (*One register*) has eight recorded runs, S1–S8, judged
by three chips that differ only in how they read an `:info`. Tab 2 (*Transactions*) has
six histories E1–E6 and one toggle: **list-append** vs **register**. The register
always starts at **0**; in the Elle tab, keys always start as empty lists.

---

## Rung 1 — Explain

No simulator. Write the answers out; the point is to find the places where you can
only gesture.

**1.1** A colleague says: "we already have integration tests that check the data is
right at the end. Why would we need Jepsen?" Give them **three structurally
different** reasons, not one reason three ways.

**1.2** Explain `:ok`, `:fail`, and `:info` to someone who has never seen them, in a
way that makes clear why two outcomes are not enough. Then say what a checker is
allowed to assume about each.

**1.3** In one sentence each, say what a **red** Jepsen result means and what a
**green** one means. Make the asymmetry between them explicit.

**1.4** The lab claims a checker "searches for an explanation" rather than "compares
against an expected value". Explain the difference to someone who writes ordinary
unit tests, and say why the ordinary approach cannot work here.

**1.5** Why does Jepsen permanently retire a process after it receives an `:info`?
What would break if it did not?

---

## Rung 2 — Identify

Read-off-the-screen questions. Open tab 1.

**2.1** Run **S2**. Which two operations do *not* overlap, and what does that
non-overlap force? Which single operation would you delete to make the history legal?

**2.2** Run **S3**. Look at p1's bar. Why is it drawn dashed with an arrow running off
the right edge rather than ending at t=8? State the modelling rule that arrow encodes.

**2.3** Run **S3** and read all three verdict chips. Which one is red, and which one
does the page mark as Jepsen's? What is the practical consequence of shipping a test
that behaves like the red one?

**2.4** Run **S7**. Both writes are in flight when p3 reads. Which pairs of operations
in this history are actually *constrained* relative to each other, and which are free?
Give the rule you used.

**2.5** Switch to tab 2 and select **E4**. Two edges are shown. One is derived from
something a transaction *read*; the other from something it *did not* read. Which is
which, and what is the second kind called?

---

## Rung 3 — Predict

Commit to an answer before pressing anything.

**3.1** In **S4**, p1's write timed out — the checker has total freedom about whether
and when it took effect. Predict the verdict under Jepsen's reading, then run it. If
your prediction was "valid", say exactly which fact defeats it.

**3.2** In **S6**, the write is recorded `:info` and the later read returns 0. Before
running: which of the three chips will be red? Then explain why the pattern here is
the *mirror image* of S3.

**3.3** Tab 2, **E2** in list-append mode: predict whether Elle finds a cycle. Now
predict what happens when you flip to **register**. Run both.

**3.4** Tab 2, **E5** (write skew): predict whether flipping to register mode hides
this anomaly the way it hid E2's. Run it, then explain the difference in one sentence
about what each anomaly's evidence depends on.

**3.5** S8 with k=8: before dragging, predict roughly what factor separates the pruned
and unpruned columns — 2×, 30×, 3000×? Then predict what happens to that factor at
k=9, and say why you cannot check that on this page.

---

## Rung 4 — Modify

Change one thing and say what breaks.

**4.1** Take **S2** and move p3's read so it starts at t=2 instead of t=5. Is it still
a violation? Answer from the rule, then verify by reasoning through the checker's
search by hand. What general principle about test design does this demonstrate?

**4.2** Take **S5** (a confirmed write, then a read of 0) and change the write's
result from `:ok` to `:info`. You have now built **S6**. Which chip changes, and what
does that tell you about where a Jepsen test's power actually comes from?

**4.3** In **S3**, change p2's read result from 1 to 0. Work out all three verdicts by
hand before checking your reasoning against the rules in the README.

**4.4** Suppose Jepsen recorded only completion times and not invocation times — one
timestamp per operation instead of two. Which scenarios in tab 1 could still be
judged, and which become unjudgeable? What property of linearizability does the
invocation timestamp carry?

**4.5** In **E4**, suppose T2 had read `y = [1]` instead of `y = []`. Redraw the edges.
Is there still a cycle? Name what T2 would then have observed.

---

## Rung 5 — Trace

Do the checker's job by hand. Write the state after each step.

**5.1** Run the linearizability search on **S2** manually. At each step list the
*candidate* operations (those not blocked by the real-time rule), the one you pick,
and the register value afterwards. Show both branches dying.

**5.2** Do the same for **S7**, and produce the winning order. Show at least one
branch you had to abandon before finding it.

**5.3** Trace Jepsen's handling of **S6** explicitly as two branches: "the `:info`
write took effect" and "it did not". Show why *both* branches are needed to reach the
verdict, and which one succeeds.

**5.4** For **E3** (G1c), write out the two edges as sentences of the form *"X must
have committed before Y, because …"*. Then state the contradiction in one sentence
without using the word "cycle".

**5.5** Here is a history not in the simulator. Register starts at 0.

| process | operation | invoked | completed | result |
|---|---|---|---|---|
| p1 | `write(1)` | 0 | 6 | `:ok` |
| p2 | `write(2)` | 1 | 7 | `:info` |
| p3 | `read()` | 8 | 9 | `:ok` → 2 |
| p4 | `read()` | 10 | 11 | `:ok` → 1 |

Decide the verdict under all three readings of `:info`, showing your work. This one
rewards care.

---

## Rung 6 — Spot the flaw

Each of these is a plausible design with a subtle defect. Find the smallest concrete
scenario where it fails, and name the guarantee or property it violates.

**6.1** A team writes their own checker. To keep it fast, they **drop every `:info`
operation from the history entirely — both the invocation and the completion** —
before checking. They argue this is conservative: "we're just ignoring the ones we're
unsure about." Construct the smallest history where this produces the wrong verdict,
and say in which direction it is wrong.

**6.2** A team's client code is:

```java
try   { db.write(key, value); return Result.ok(); }
catch (Exception e) { return Result.info(); }   // "safe default"
```

Name the specific class of database response that this mishandles, and construct the
smallest history where the mishandling causes a real bug to go unreported.

**6.3** To make the test find bugs faster, a team raises concurrency from 5 client
processes to 500, all hammering the same key. They reason that more concurrency means
more races means more bugs. Explain, using the rule from **2.4**, why this can make
the test *strictly weaker*, and describe the extreme case.

**6.4** A team checks a transactional database using an **rw-register** workload,
finds nothing over two weeks of runs, and writes in their design doc: "the store
provides serializability under partition." Give the two independent reasons this
sentence is unsupported by the evidence, one about the datatype and one about
falsification.

**6.5** A vendor's status page says: "Our clocks are synchronised to within 200ms via
NTP, so we skip the clock-skew nemesis." Give the smallest concrete failure this
reasoning misses. (Your `distributed-locking` and `split-brain` labs both contain the
answer.)

---

## Rung 7 — Handle failure

Design discussions. There is no single right answer; there are answers that survive
scrutiny and answers that do not.

**7.1** Your Jepsen run goes red. Before you file a bug against the vendor, list — in
priority order — the things you must rule out about **your own test**. For each, say
how you would rule it out.

**7.2** Your checker does not finish: it runs for four hours on a 20,000-operation
history and is killed. Someone proposes recording this as a pass, since "no violation
was found." Explain why that is wrong, and then give **three concrete changes** to the
test that would make future histories checkable — for each, say what testing power you
give up in exchange.

**7.3** You are testing a system whose vendor claims *"acknowledged writes are never
lost, and reads never return uncommitted data."* Those are two separate claims. For
each: which checker would you use, which workload, which faults, and what would the
counterexample look like if the claim were false?

**7.4** A real Jepsen run produces a violation that appears only when a partition
heals **during** a leader election, roughly once in forty runs. The vendor cannot
reproduce it. What do you send them, beyond "it failed"? Be specific about what makes
a history persuasive.

---

## Rung 8 — Design

**8.1 — Your own stack.** You run a Spring Boot service on PostgreSQL with a
connection pooler (PgBouncer in transaction mode) and a read replica behind a routing
layer. PostgreSQL itself is serializable and you trust it. Design a test for the
claim *"our application's transactions are serializable end to end."*

Address: what the workload should be and why; where you expect the guarantee to be
weakened and by which component; which checker; whether you need a nemesis at all; and
what you would monitor in production once the test passes.

**8.2 — The counterfactual.** Suppose Jepsen had no `:info` — only `:ok` and `:fail`,
with the client forced to guess on a timeout. Describe how this changes what can be
proven. Give one concrete false positive and one concrete false negative that this
world produces, and say which is worse and why.

**8.3 — Choosing the fault budget.** You have four hours of cluster time per night for
a nightly safety run against a Raft-based key-value store. You cannot run every fault.
Rank the fault catalogue from section 15 of the README by expected bugs-found per hour
for *this* system, and justify your top three and your bottom two. State the
assumption about production that each of your top three encodes.

**8.4 — When not to use Jepsen.** Name two systems or properties where a Jepsen-style
test is the wrong instrument, and say what you would use instead and why. At least one
should be a case where the property is genuinely important — the point is not "it
doesn't matter", it is "this tool cannot see it."
