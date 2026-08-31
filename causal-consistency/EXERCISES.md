# Exercises — causal consistency

Eight rungs, easiest first. Attempt each before opening
[`SOLUTIONS.md`](SOLUTIONS.md). Rungs 7 and 8 have no single right answer; the
solution is a worked discussion to compare yours against.

```bash
java src/Sim.java · java src/Tests.java · java src/Tests.java broken
java src/Experiments.java [1-10] · java src/YourCheck.java
```

---

## Rung 1 — Explain

**1.1** In three sentences, with no jargon, explain to a colleague why a
comment can show up without the post it is a comment on, when nothing has
crashed and no replica is misbehaving.

**1.2** Your team already stores a version vector per key and resolves
conflicts with it. Explain in two sentences why that does not fix 1.1.

**1.3** Someone says "causal consistency resolves conflicts between concurrent
writes". Correct them in one sentence: what does causality actually say about
two concurrent writes?

**1.4** A causal read returns a value that is two minutes out of date. Is that
a violation? Answer, and say which model *would* be violated by it.

**1.5** Name the four session guarantees and, for each, say in a few words what
a user would notice if it were missing.

---

## Rung 2 — Identify

Open [`visualization.html`](visualization.html), leave the rule on **Show it
the moment it lands**, and play the first situation, *The comment with no post*.

**2.1** Click Amira's filled circle. Which circles turn amber, which stay grey,
and what does grey mean?

**2.2** Now switch to **One counter per region** and replay. A thick dashed
line appears in the Singapore lane. What is it, what is at each end of it, and
what does the "holding back" line on the Singapore card say it is waiting for?

**2.3** In `src/Cluster.java`, find the single method whose body *is* the
property this lab is about. Which one is it, and which of its branches is the
one COPS implements?

**2.4** In `src/Write.java`, which field is the subject of this lab? Which two
fields are compressed encodings of the same information, and which one is not
an encoding of it at all?

**2.5** Run `java src/Sim.java` and find the line in scene 1 beginning
`t=6 cara [CAUSALITY]`. Which write is the effect, which is the cause, and how
does the checker know they are related?

---

## Rung 3 — Predict

Commit to an answer, then run the command.

**3.1** Ordering writes by a Lamport counter guarantees that if `a` happened
before `b`, then `a`'s counter is smaller. Does ordering by that counter give
you causal consistency? (`java src/Experiments.java 1`, rows 3 and 4.)

**3.2** A vector clock is 3 numbers; a pruned dependency list averages 1.4
entries. Which of the two holds writes back for **longer**, and why?
(`java src/Experiments.java 4`, then `9`.)

**3.3** Causal delivery is switched on and working perfectly. Amira and Ben set
the same key at the same instant, neither having seen the other. Do the three
regions end up showing the same value? (`java src/Experiments.java 7`.)

**3.4** Amira writes in London; her next request is routed to Frankfurt before
the copy arrives. The regions are running full causal delivery. Does she see her
own write? (`java src/Experiments.java 6`.)

**3.5** In `Experiments.workload()`, delete the `c.get(...)` that precedes each
`c.put(...)`, then re-run experiment 1. Apply-on-arrival scores 160/300 today.
Predict the new number **before running**, then explain the number you get.

**3.6** A partition heals. Predict whether the writes made during the outage
become visible on the other side. (`java src/Tests.java`, the two checks under
*What it buys*.)

---

## Rung 4 — Modify

**4.1** Set `fifo = true` in the two Lamport rows of `e1_anomaliesByDesign` — it
is already a parameter. Which row changes and which do not? What does that tell
you about which designs depend on your transport?

**4.2** In `Cluster.deliverable`, case `VV`, delete the line
`if (w.vv()[src] != r.vv[src] + 1) return false;`. Run
`java src/Tests.java`. Then run `java src/Tests.java broken` and find which
planted bug you just recreated. Why does the deterministic comment-before-post
story *not* reproduce it, while the random workload does?

**4.3** In `e2_theCalmNetworkLie`, the design never changes between rows — only
`slowLink`. Write down, in one sentence, the rule this gives you for every
consistency test you will ever write.

**4.4** Change `Rule.causalVv()` to return `sticky = false` and run
`java src/Tests.java`. Which named tests fail, and are they failures of the
*store* or of the *client*?

---

## Rung 5 — Implement

**5.1** `src/YourCheck.java` contains the delivery rule, blanked out. Implement
`safeToShow`.

```bash
java src/YourCheck.java     # starts at 2/6; you are done at 6/6
```

Two hints, escalating — try without them first:
- Everything you need is on the write itself.
- If only check 4 fails, you filtered the dependencies by key. Re-read failure
  10 in the README.

**5.2** Add a method to `Cluster` that reports, for a given replica, the
**oldest blocked dependency**: the write id that the largest number of held
writes are waiting on. Use it to find the culprit in `java
src/Experiments.java 10` at a slow route of 32. (§15 names this the single
most useful alarm for a causal store — build it.)

**5.3** Implement `Rule.Ordering.LAMPORT` correctly for a system with FIFO
links: change `allHeardAtLeast` so it stops requiring news from a replica that
has sent nothing at all. What new problem does that create, and which of §11's
failures is it?

---

## Rung 6 — Debug

`java src/Tests.java broken` runs three plausible, wrong versions of the
delivery rule. Answer each **before** looking at the output.

**6.1** `same-replica-only` waits only for dependencies written by the same
replica as the write itself. What real-world belief does this encode, and why is
that belief nearly true? Construct the smallest three-event story it breaks.

**6.2** `same-key-only` waits only for dependencies on the same key. Which
earlier lab teaches you to build exactly this, and what is it correct *for*?
Why is this the most dangerous of the three?

**6.3** `vv-no-gap-check` drops the "exactly the next write from its author"
clause. It produces anomalies in only 10 of 200 runs, versus ~99 for the other
two. Explain the low number — and say why "rarer" makes it worse to have, not
better.

**6.4** A colleague proposes a fourth design: attach each write's *wall-clock
time* and have replicas apply in timestamp order. Give the two independent
reasons this fails, and name the lab where you already measured the first one.

---

## Rung 7 — Handle a failure the lab does not

**7.1 — The session token.** Experiment 6 shows 334 of 500 roaming clients
failing to see their own write, and the regions are ordered correctly the whole
time. Implement the fix: give the client a position in history, present it with
every request, and have the replica wait until it has caught up before
answering.

State precisely: what the token has to contain, what the replica does when it is
behind, what happens when the replica can *never* catch up (the write went to a
region that is now partitioned away), and what the client should be told then.
Then measure: roaming read-your-writes failures should go from 334/500 to 0.

**7.2 — Dependency garbage collection.** Experiment 3 shows full dependency
lists reaching 102.5 entries per write and still growing. Nearest-only takes it
to 1.4. Design what removes the rest: what fact must be true before a dependency
can be dropped from a list entirely, what mechanism establishes it, and what
breaks if you drop one too early. (You solved a structurally identical problem
in the [`crdts`](../crdts/) lab.)

**7.3 — The slowdown cascade.** Experiment 10 shows hidden write-ticks going
from 351 to 31,659 as one route slows. Propose two mitigations that keep the
guarantee, and one that abandons it in a controlled way. For each, say what you
would have to measure to know it was working, and what the user experiences.

---

## Rung 8 — Design

**8.1 — A multi-region social product.** Three regions, 150 ms apart. Posts,
comments, likes, follows, blocks, direct messages, and a "who can see this"
privacy setting per post.

Produce: which data needs causal ordering and which does not; where the
session token lives and what happens on a load-balancer failover; what must
*not* be causal and what it uses instead; what the user sees when a region is
behind; and the three dashboard metrics you would require before launch.

Be explicit about the one item on that list where getting it wrong is a
security incident rather than a bug.

**8.2 — Turning it on in a real stack.** Your service is Spring Boot on
MongoDB, currently reading from secondaries for speed and getting complaints
about users not seeing their own edits. Write the six-sentence proposal: what
you would change, what it costs, what you would measure before and after, and
why "just read from the primary" is and is not the answer.

**8.3 — Where causal consistency is the wrong choice.** Pick one of: a seat
reservation system, a bank ledger, a rate limiter. Argue in two paragraphs why
causal consistency cannot be the answer for the core operation, and what part of
the product it *is* right for. (Two of the three have an obvious answer and one
is genuinely interesting — the rate limiter appears in your
[`distributed-rate-limiting`](../distributed-rate-limiting/) lab.)
