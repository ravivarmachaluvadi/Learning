# Paxos — Exercises

Work top to bottom. Everything here is reasoning, tracing or simulator work —
there is nothing to install and no code to write. Your tools are
[`README.md`](README.md), the simulator [`visualization.html`](visualization.html),
and a piece of paper.

Answers are in [`SOLUTIONS.md`](SOLUTIONS.md). Write your answer down before
looking; an answer you committed to and got wrong is worth about ten times one
you merely read.

**Notation used throughout.** Five acceptors A–E, majority = 3. An acceptor's
state is written `A[p=2 wrote=Zoo@1]`, meaning A has promised number 2 and has
accepted the value `Zoo` under proposal number 1. `wrote=-` means it has
accepted nothing. Proposer P1 wants `Zoo`; P2 wants `Museum`.

---

## Part 0 — Explain & identify

**0.1 (explain)** In four sentences or fewer, and without using the words
*prepare*, *promise*, *accept*, or *quorum*, explain to a colleague: what
problem Paxos solves, why "just tell everyone" fails, and what the one key
idea is. (If you need protocol vocabulary to say it, the idea hasn't landed
yet — the school-trip story is enough.)

**0.2 (explain)** "Accepted by an acceptor" and "chosen" — state the
difference in one sentence each, and name the experiment in the simulator
that exists purely to demonstrate the gap.

**0.3 (identify)** Open the simulator and run guided experiment 2, answering
its prediction prompt first. When it finishes, read the *screen*, not the
doc: (a) which teachers carry a star, and what does the star mean? (b) which
teacher's box has the amber "witness" outline, and what did it witness?
(c) find, in each teacher's box, the two facts every acceptor must remember
— what are they called on screen, and what are they called in the doc's
engineering model (§7)?

**0.4 (identify)** Stage 12 states two rules. Without looking back at it,
write both from memory: the rule that decides **what a proposer proposes**,
and the rule that decides **when a value is chosen**. Then check yourself, and
for each rule name the glossary term it implements and the one experiment that
would break if you deleted it.

**0.5 (identify)** In experiment 2's final state —
`A[p=1 Zoo@1] B[p=1 Zoo@1] C[p=2 Zoo@2] D[p=2 Zoo@2] E[p=2 Zoo@2]` — point at
the evidence that the *second* proposer ran at all, and the evidence that it
did not get what it wanted. Which single acceptor is the reason it did not?

## Part 1 — Predict

**1.1** Acceptor A is `A[p=7 wrote=Zoo@5]`. It now receives `Prepare(9)`.
Write its reply exactly — including which value it reports, and why it reports
the one from `n=5` rather than mentioning `n=7` as the value's number. Then
write A's state afterwards.

**1.2** Same acceptor `A[p=7 wrote=Zoo@5]`, but it receives `Accept(7,
"Museum")` instead. Accepted or refused? The condition is `n >= promised_n`,
not `n >`. Explain in one sentence why `>=` is necessary — and stage 12
measured what happens with `>`; state that outcome before checking.

**1.3** P1 completes phase 1 with A, B, C at `n=1` and gets `Accept(1,"Zoo")`
written by **A only**. P2 then completes phase 1 at `n=2` with **A, D, E**.
Predict P2's proposed value. Now predict again for the case where P2 uses
**C, D, E** instead. Explain why the answers differ even though nothing was
*chosen* in either case.

**1.4** Simulator, experiment 4 (livelock). Before running it: does the "one
decision only?" light ever turn red? Does the "decision" field ever leave
`none`? State which property is being violated and which is preserved, using
the words *safety* and *liveness*. Then predict the acceptors' `wrote=` fields
at the end, and check against the state printed in stage 13.

**1.5** A cluster of **4** acceptors. What is the quorum? How many failures
can it tolerate? Compare with 3 and with 5, and state what the fourth machine
actually bought you.

**1.6** A proposer collects these three promises:
`A: (4,"Zoo")`, `C: (2,"Museum")`, `D: (2,"Museum")`. What does it propose?
Now change A's report to `(1,"Zoo")` and answer again. Explain what the count
of reports has to do with the answer. (It is a trap; say so explicitly.)

## Part 2 — Modify

For each of these, **write down your predicted outcome first**, then check it
against the measured counterfactual table in stage 12 of the README.

**2.1** Delete Rule A — a proposer always proposes its own value, ignoring
whatever phase 1 reported. Construct the smallest schedule that produces two
decisions, naming the acceptors in each majority. Then state which single
sentence of the safety argument in stage 11 you have invalidated.

**2.2** Change the quorum from `N/2 + 1` to `N/2` — 2 of 5. Name two specific
sets of acceptors that can now each decide, and state the property of
majorities that no longer holds. Why is `N/2 + 1` the *smallest* number that
works, rather than a conservative choice someone picked for safety margin?

**2.3** An acceptor promises correctly but its `Promise` reply always reports
`accepted: nothing`, even when it has accepted something. Safety or liveness —
which breaks, and is this worse or better than 2.1? (Think about who is at
fault and whether the proposer could detect it.)

**2.4** Give proposers a randomized backoff: on a NACK, wait a random number of
rounds before retrying. Reproduce experiment 4's duel mentally and argue that
it now terminates. Then answer the harder half: why is this a *probabilistic*
fix rather than a guarantee, and what does
[`../33-flp-impossibility/`](../33-flp-impossibility/README.md) say about anyone who
claims otherwise?

**2.5** In the simulator, use the `lose` buttons to construct by hand: a value
accepted by exactly **2 of 5**, followed by a *different* value becoming
decided. Then attempt the same thing with **3 of 5** for the first value and
show you cannot. Write down the exact step at which the second attempt is
forced to change its mind.

**2.6** Suppose acceptors could talk to each other and gossip what they have
accepted. Does that let you drop phase 1? Answer carefully — the interesting
part is what gossip cannot tell you about messages still in flight.

## Part 3 — Trace

Do these on paper. Write the full five-acceptor state line after every step.

**3.1** Starting from all-fresh, trace this schedule and give the state after
each numbered step:

1. P1 sends `Prepare(1)` to all five; A, B, C deliver, D and E are lost.
2. A, B, C's promises all reach P1.
3. P1 sends `Accept(1,"Zoo")` to all five; only A delivers.
4. P2 sends `Prepare(2)` to C, D, E; all deliver.
5. C, D, E's promises reach P2.
6. P2 sends `Accept(2, ?)` — **you fill in the value** — and C, D, E deliver.

At step 6, state the value and say whether anything is now chosen. Then answer:
was `Zoo` ever chosen at any point in this trace?

**3.2** Same start, but change step 3 so that **A, B and C** all deliver
`Accept(1,"Zoo")`. Re-run steps 4–6. Give the value at step 6 and the final
state, and name the acceptor that forced the difference.

**3.3** Trace this one, which is subtler:

1. P1 completes phase 1 at `n=1` with A, B, C.
2. P2 completes phase 1 at `n=2` with A, B, C.
3. P1's `Accept(1,"Zoo")` now arrives at A, B and C.

What does each acceptor do at step 3, and why? What is P1's view of the world,
and what should P1 do next? (This is the ordinary case that makes proposal
numbers necessary; there is no failure anywhere in it.)

## Part 4 — Spot the flaw

**4.1** A colleague's acceptor handles `Accept` like this:

```
  on Accept(n, v):
      if n >= promised_n:
          promised_n = n; accepted_n = n; accepted_value = v
          reply Accepted(n, v)              # reply first, for latency
          persist_in_background(n, v)       # flush to disk shortly after
```

It passes every test in CI and corrupts data roughly once a month in
production, always after a machine reboot, always with two different committed
values for the same slot. Name the bug, name the exact invariant it breaks,
explain why the test suite never caught it, and name the experiment in this lab
that reproduces it deliberately. Then explain, using the soak numbers in stage
11, why "we ran a chaos test and it was green" is not a defence.

**4.2** Someone proposes an optimization: *"Phase 1 is a wasted round trip.
Acceptors already refuse anything below their promise, so let's skip `Prepare`
entirely and send `Accept(n, our_value)` with a big `n`."* Construct a concrete
two-proposer schedule, with real acceptor names and numbers, in which this
chooses two different values. Then explain in one sentence what phase 1 is
actually *for* — and note that "permission" is the wrong answer.

**4.3** This proposer is wrong in a way that preserves safety but can lose
liveness:

```
  on Promise(n, ...) matching my current n, while still in phase 1:
      record the promise
      if promises.count == TOTAL_ACCEPTORS:      # <-- waits for ALL, not a majority
          value = pick_by_rule_A(promises)
          send Accept(n, value) to all
```

Find the scenario. Then answer the follow-up: which of the three naive attempts
in stage 4 of the README has this quietly reintroduced?

**4.4** A proposer adopts the value reported by the **most** acceptors rather
than the one with the highest `accepted_n`. Build the smallest promise set
where the two rules disagree, then build the full schedule in which the wrong
rule resurrects a value that was never chosen. (Stage 12 has a measured version
— construct yours before reading it.)

**4.5** In the simulator, produce a state where teacher C shows `wrote: Zoo`
while D and E show `wrote: Museum`, **without** the safety light turning red.
Explain why this disagreement is not a violation, and state precisely what
would have to become true for it to become one.

## Part 5 — Handle a failure

**5.1** Specify the durability contract for an acceptor. Name (a) exactly which
fields must reach stable storage, (b) the exact moment relative to sending each
reply, and (c) what must happen on restart before the acceptor answers anything
at all. Then answer: what breaks if you flush *after* replying instead of
before, and why would neither a unit test nor a random soak reliably catch it?

**5.2** A client sends "set config=blue" to proposer P1 and hears nothing back —
P1 crashed somewhere between maybe-finishing and replying. The client retries
through P2. What must the retry propose, what may the system NEVER assume about
the first attempt, and what is the only honest way for the client to learn the
outcome? (The answer is a read, not a cleverer write. Connect it to
[`../49-idempotency/`](../49-idempotency/README.md): what makes the retry safe is not
Paxos.)

**5.3** A majority of acceptors is lost — three of five machines are gone and
not coming back. State what the surviving two can and cannot do, and why
"just lower the quorum to 2 so we can keep serving" is the most dangerous
sentence anyone will say in that incident. What is the actual recovery
procedure, and what does it cost you?

## Part 6 — Multi-Paxos

**6.1** A leader holds phase-1 promises at `n=5` covering all slots from 10
onward. It sends `Accept(5, cmdA)` for slot 10 and `Accept(5, cmdB)` for slot
11 without waiting. Slot 11 reaches a majority; slot 10's messages are lost.
Describe the log's state, what the state machine may apply, and what the leader
must do next.

**6.2** The leader dies. A new leader takes over at `n=6`. Walk through what it
must do before it can accept a *new* command at slot 12, and explain why it
proposes a no-op into any hole it finds rather than simply skipping the slot.

**6.3** Raft forbids holes; Multi-Paxos permits them. Name one thing each
choice buys and one thing it costs. Then answer the design question: for a
configuration store handling a few writes per second, which restriction would
you rather have, and why?

**6.4** Explain, in terms of the measurement in stage 11, why a leader is a
*throughput* mechanism rather than a *correctness* mechanism — and what
therefore happens (precisely) if your leader-election logic is buggy and
produces two leaders at once.

## Part 7 — Design

**7.1** Design the configuration store for a company with **three datacentres,
50 ms apart**, that must keep serving decisions if any one datacentre is lost.
Decide and justify: (a) how many acceptors and where; (b) where proposals run —
the duel from experiment 4 is now a latency bill, so what kills it? (c) the
latency of one decision in the steady state and during leader failover; (d) the
three graphs on the on-call dashboard, and the one alert; (e) which parts of
this lab's design survive unchanged, and which had to be added. Half a page.

**7.2** Your payments service needs "reserve this seat exactly once". Someone
proposes running basic Paxos per seat, Cassandra-LWT style. Someone else
proposes one Raft group with the seats as a state machine. Compare them on:
cost per operation, blast radius of one slow node, what happens at 10× the
seats, and what the on-call engineer sees when it goes wrong. State which you
would ship and what would change your mind.

**7.3 (counterfactual)** Pick any *one* rule from this lab — quorum overlap,
Rule A, the `>=` in the accept condition, durability before reply, or
monotonically increasing proposal numbers. Assume it does not exist. Write the
smallest concrete scenario, with named acceptors and real numbers, in which the
system produces a wrong answer that a customer would notice. Then state which
of the four measured counterfactuals in stage 12 your scenario matches, and
whether yours is smaller.
