# FLP Impossibility — Exercises

Eight rungs, easiest first. Everything here is pencil-and-paper or work in
`visualization.html` (**The Ferryman's Choice** —
<https://claude.ai/code/artifact/59306f2a-8884-4ffa-b06c-81faf73ad1d5>).
There is no code to write.

Answers in `SOLUTIONS.md`. Rungs 7 and 8 have no single right answer — the
solution there is a worked discussion, not a key.

**Short on time?** Do rung 1 question 2, all of rung 3, and rung 6 question 2.
Those three tell you whether the model actually landed.

---

## Rung 1 — Explain

**1.1** In under 120 words and with no technical vocabulary, explain to a
colleague why *"just add a timeout"* does not solve the problem this lab is
about. You may use the lighthouse story.

**1.2** A teammate says: *"FLP proves consensus is impossible, so how does etcd
work at all?"* Answer in three sentences.

**1.3** Explain the difference between these two statements, using the
lighthouses:
- "Nobody has decided yet."
- "The outcome is not yet decided."

Why does the proof need the second one and not the first?

**1.4** The proof constructs a run in which nobody ever decides. In that run,
how many keepers actually fall ill? Explain why the answer surprises people, and
what role the *possibility* of illness plays instead.

---

## Rung 2 — Identify

Open tab 2 of the visualization, **Play the ferryman**, and press **Reset**.

**2.1** Before anything has happened, the satchel is empty and the only thing you
can do is let a keeper give up waiting. What does that tell you about where the
very first event of *any* run must come from? Which technical term names that
kind of event?

**2.2** Press **"Ferryman plays to stall ×40"** once, then scroll the event log
back to find a step where a keeper had **1 of the 2 acceptances needed**. Write
down: (a) which single letter would have settled the matter, (b) what the
ferryman delivered instead, and (c) what that made happen to the dangerous
letter.

**2.3** Look at the **fairness audit** panel. Which of those numbers would have
to grow without bound for the ferryman to be cheating? Which ones stay small no
matter how long you run it? What claim do the small ones let you make?

**2.4** On an island that has not decided, the lamp is drawn **half dark, half
lit**. Which technical term is that picture of? What would the lamp look like in
a *univalent* situation where nobody has decided yet — and why can the page not
draw that?

**2.5** In the verdict box during a stall, two numbers move: the step count and
the attempt number. Which one is the interesting one, and why?

---

## Rung 3 — Predict

Write your prediction down *before* running anything.

**3.1** Set the preferences to **all three want LIGHT**. Can the ferryman still
keep the matter open forever? Predict yes or no, and say why. Then run it.

**3.2** Set **Bo falls ill**. Predict what happens under (a) the ordinary
oldest-letter-first ferry, and (b) the stalling ferryman. Then run both.

**3.3** In tab 4, compare a delivery deadline of **Δ = 2** against **Δ = 25**.
Predict which settles in fewer steps, and predict how many *attempts* each one
needs. Then run them.

**3.4** In tab 4, turn on the **leadership oracle** with **no delivery
deadline**. Predict whether the ferryman wins, and how many attempts get used.
Then run it.

**3.5** Ada has just collected agreements from a majority for attempt 7, and is
about to name a value. Bo accepted `(4, LIGHT)` earlier, but Bo's agreement
letter reporting that fact is still at sea; the two agreements Ada actually
received reported nothing. What value does Ada propose? Is that safe? Explain
which rule makes it safe — or find the run where it is not.

---

## Rung 4 — Modify

**4.1** In tab 4, turn on **randomised waiting times** with **no delivery
deadline**, and run it. Which word of the theorem were you attacking? Why did it
not help? Now add **Δ = 4** and run again — what changed, and what did the
randomisation contribute that Δ alone did not?

**4.2** Change the protocol on paper: a keeper who refuses a request now sends an
explicit *"no — I have promised a higher attempt"* instead of staying silent.
Does that defeat the ferryman? Which property of the system improves, and which
one is unaffected? Be precise about which.

**4.3** Change the protocol on paper: instead of rotating attempt numbers
(Ada owns 1, 4, 7…; Bo owns 2, 5, 8…), let every keeper pick any number they
like, as long as it is higher than anything they have seen. Does the ferryman's
job get easier, harder, or stay the same?

**4.4** Suppose the three keepers agree to take turns strictly: only Ada may
start attempts on Mondays, only Bo on Tuesdays, only Cy on Wednesdays. Nobody
has a clock. What exactly goes wrong with this plan?

---

## Rung 5 — Trace

**5.1** Starting from: Ada wants DARK, Bo wants LIGHT, Cy wants LIGHT, nothing
has happened. Execute this schedule by hand and write the state of all three
keepers after each step:

```
1. Ada gives up waiting
2. deliver PREPARE(1) to Bo
3. deliver PREPARE(1) to Cy
4. deliver PROMISE(1) from Bo to Ada
5. deliver PROMISE(1) from Cy to Ada
6. deliver ACCEPT(1, ?) to Bo
7. Cy gives up waiting
8. deliver PREPARE(3) to Bo
9. deliver ACCEPT(1, ?) to Cy
```

At step 6, what value is in the ACCEPT letter, and why? At step 9, what does Cy
do, and what does Ada now believe about attempt 1?

**5.2** You are the ferryman. The current situation is:

```
Ada:  promised 4, accepted (4, DARK), running attempt 4, has 1 of 2 acceptances
Bo:   promised 4, accepted nothing, idle
Cy:   promised 4, accepted nothing, idle
at sea: ACCEPT(4, DARK) → Bo ,  ACCEPT(4, DARK) → Cy
```

Write down the next **three** events you would choose, in order, and one sentence
each saying why. Then say what the situation looks like afterwards.

**5.3** Verify Fact B by hand. Take this situation:

```
Ada:  promised 1, idle
Bo:   promised 1, idle
Cy:   promised 1, idle
at sea: PROMISE(1) Bo→Ada ,  ACCEPT(1, DARK) Ada→Cy
```

Apply the two pending events in both orders and write out the resulting state of
all three keepers plus the letters at sea. Are they the same? Now construct a
pair of events for which the answer is *no*, and say what is different about
that pair.

---

## Rung 6 — Spot the flaw

Each of these is a real thing people propose. Find the error, construct a
concrete run or scenario that exposes it, and name the guarantee that is
actually at stake.

**6.1** *"The livelock is caused by proposers interrupting each other. So we add
a rule: a proposer may not start a new attempt until it has waited at least
three round trips since its last one. That removes the livelock."*

**6.2** *"Our coordinator waits 30 seconds for a participant to reply. If nothing
comes back in 30 seconds we declare that participant failed and proceed without
it. Thirty seconds is 300× our observed p99 round-trip time, so this is safe."*

**6.3** *"We made our leader election randomised — each node waits a random
150–300 ms before becoming a candidate. FLP requires a deterministic protocol,
so FLP no longer applies to us."*

**6.4** *"We run 7 replicas instead of 3, so we tolerate 3 failures instead of 1.
That makes us much more resistant to the FLP problem."*

**6.5** *"Our cluster is inside a single rack on a dedicated switch with
sub-millisecond RTT and no shared tenancy. It is effectively a synchronous
system, so the theorem doesn't apply."*

---

## Rung 7 — Handle failure

Design work on paper. There is no single right answer; there are better and
worse ones.

**7.1** Your Raft cluster elects a new leader **40 times an hour**. No machine
has crashed. Writes mostly succeed but tail latency is terrible. Work out: what
you would measure first, what the three most likely causes are, what change you
would make for each, and — for each change — **which word of the theorem it
attacks**. Then say which of your changes would *not* help and why.

**7.2** Extend the lighthouse design so that a keeper who was merely *slow* — not
dead — cannot cause a wrong outcome when they wake up and carry on with stale
beliefs. What must the design add, and what must the *resource* being protected
do? (Your `split-brain` and `distributed-locking` labs have the machinery.)

**7.3** Design a way to detect **grey failure** in a consensus cluster: a leader
that heartbeats healthily but has stopped committing anything. What do you
measure, what threshold do you use, what do you do about it automatically, and
what is the risk of your automatic action?

**7.4** Your on-call runbook currently says: *"if the cluster has no leader for
more than 60 seconds, restart the nodes one at a time."* Critique this. When is
it right, when is it actively harmful, and what would you replace it with?

---

## Rung 8 — Design

**8.1** Design the configuration store for a payments platform running in **three
datacenters, 50 ms apart**, that must survive the loss of any one datacenter.
Address, explicitly:

- which parts of this lab's model survive the move to three datacenters and
  which must change;
- what value of Δ you are assuming, how you chose it, and how you defend it
  operationally;
- what you monitor, and which single metric you would keep if you could only
  keep one;
- your documented, tested behaviour when the timing assumption breaks;
- what you tell the product team about write-latency guarantees;
- whether you would put this on 3 nodes or 5, and why the answer has nothing to
  do with FLP.

**8.2** A product manager asks for *"a hard 500 ms upper bound on transfer
confirmation, guaranteed"*, for a transfer that requires agreement across
replicas. Write the reply you would actually send: what you can promise, what
you cannot, why, and what you would offer instead. Two hundred words maximum,
written for someone who has never heard of any of this.

**8.3** You are reviewing a design that proposes a **single leader with no
election** — a human operator promotes a new leader when the old one dies.
Argue both sides: what does this buy with respect to FLP, what does it cost, and
under what circumstances is it the right call?
