# Google Spanner — exercises

Pencil, paper, and the simulator. Nothing to install, nothing to run.

Worked answers are in [`SOLUTIONS.md`](SOLUTIONS.md). Attempt each rung before
reading them — particularly the traces, which are what convert *"I followed that"*
into *"I could rebuild that."*

Where an exercise says **check it**, open
[`visualization.html`](visualization.html) (or the hosted
[The Honest Clock](https://claude.ai/code/artifact/10030776-7351-463a-b99a-0209680dc341))
and confirm your answer against the run.

---

## Rung 1 — Explain

No notation, no diagrams. Say it out loud.

**1.1** Explain to a backend engineer who has never heard of Spanner, in five
sentences and without using the words *linearizable*, *serializable* or *quorum*,
why a globally distributed database might want a clock that reports a range instead
of a moment.

**1.2** A colleague says: *"Spanner works because Google has atomic clocks, so their
timestamps are always right."* Correct them in three sentences. Your correction must
name the thing that is actually true about Google's hardware.

**1.3** The same clock-uncertainty cost, ε, is paid by Spanner, CockroachDB and
MongoDB in three different places. Name who is billed in each, and say in one
sentence why Spanner's choice makes reads cheap.

**1.4** What, exactly, is the machine doing during commit wait?

---

## Rung 2 — Identify

Open the simulator. Every answer here is on the screen.

**2.1** Load **scenario 2** and step until real time reaches 108. Which regions'
clock bands contain the dashed true-time cursor? Click **Northgate** in the
inspector: what is its `TT.now().latest`? Click **G1**: what is `s_max`, and why is
it larger than any moment that has actually happened?

**2.2** Still in scenario 2. At what real time does T1's shaded commit-wait block
begin, and at what real time does it end? Why does it not begin at the moment T1
arrived at the leader?

**2.3** Load **scenario 5** and run it to the end. Click **G2** in the inspector at a
moment while the transfer is still prepared. Which of `t_safe_paxos` and `t_safe_TM`
is the smaller, what number is it one tick below, and where did that number come
from?

**2.4** Load **scenario 7** and click **R1**. What is its read timestamp, what is the
highest timestamp the Singapore replica has applied at the moment the read arrives,
and how long does the read end up waiting?

**2.5** In any scenario, find the dotted line joining a bar's right-hand end to its
diamond. What are the two quantities it connects, and what does its length mean?

---

## Rung 3 — Predict

Write the answer down **before** you touch the controls.

**3.1** Scenario 2, commit wait on. Set Northgate's ε to 20. Predict three numbers:
T1's timestamp, T1's acknowledgement time, and how much of that is commit wait.
Then predict whether the verdict banner changes. Check it.

**3.2** Scenario 1 (commit wait off). Drag **“analytics read as of”** from 104 up to
108, one millisecond at a time. Predict the exact range of values at which the read
reports a mortgage against a property that has not been sold. Check it.

**3.3** Scenario 4 — ε = 1 ms, Northgate's real offset = +6 ms. Predict what happens
if you raise ε to 6 without touching the offset. Does that make the clock *more
accurate*, or something else? Check it.

**3.4** Scenario 7. Predict R1's wait if you set the Singapore lag to 0. Then predict
it if you leave the lag at 45 but change **“reads are served by”** to the Northgate
leader. Check both.

**3.5** Scenario 6. The Northgate zone dies at real time 111 and a write arrives at
114. Predict, to the millisecond, the earliest real time at which that write can be
given a timestamp — and say which invariant forces that answer.

---

## Rung 4 — Modify

Change something, then say precisely which property broke and whether the loss is to
*safety* (something wrong happened) or *liveness* (nothing wrong happened, but
progress stopped).

**4.1** Set both regions' ε to the same value with commit wait **off**. Does the
ordering violation disappear? Now explain why *"just make everyone's ε equal"* is
not a fix, in terms of what ε actually is.

**4.2** Switch **“idle groups advance their safe time”** to the fixed cadence and run
scenario 7. What changes, and which of safety or liveness is affected?

**4.3** Suppose commit wait waited until `TT.after(TT.now().earliest)` instead of
`TT.after(s)`. Which of the four lines of the proof in §10.2 stops holding, and what
run does that permit?

**4.4** Suppose a leader chose `s = TT.now().earliest` instead of `.latest`, with
commit wait still on and honest clocks everywhere. Construct a concrete failing run
with real numbers.

---

## Rung 5 — Trace

Execute the rules by hand. Write one line per step. Then check against the simulator.

**5.1** Northgate has ε = 5 ms and a true offset of 0. Southgate has ε = 2 ms and a
true offset of 0. Paxos takes 3 ms. Commit wait is **on**.
T₁ reaches Northgate's leader at real time **100.000**.
The client is told; 1 ms later it telephones; 1 ms after that T₂ reaches
Southgate's leader.
Compute: `s₁`, T₁'s acknowledgement time, T₂'s arrival time, `s₂`. Does external
consistency hold? **Check it:** load scenario 2 and set ε(Northgate) = 5,
ε(Southgate) = 2, Paxos round trip = 3.

**5.2** Same setup, commit wait **off**. Let `g` be the gap between T₁'s
acknowledgement and T₂'s arrival at its leader. Derive the condition on `g`, ε₁, ε₂
and the Paxos latency `P` under which the ordering inverts. Then apply your formula
to scenario 1's numbers (ε₁ = 7, ε₂ = 1, P = 2, g = 2) and confirm it predicts the
violation the simulator shows.

**5.3** A Paxos group has applied writes with timestamps 100, 140 and 180, and has one
prepared but undecided transaction whose prepare timestamp is 150.
(a) What are `t_safe_paxos`, `t_safe_TM` and `t_safe`?
(b) Which of these reads proceed and which block: at 130, at 149, at 160, at 179?
(c) The prepared transaction now commits at 190. Recompute all three, and say which
reads unblock.

**5.4** Leader L1 of a group holds a lease from 1000 to 1100 and has assigned
timestamps up to `s_max = 1098`. L1's machine dies at 1050.
(a) Earliest real time L2 may begin its lease?
(b) Smallest timestamp L2 may legally assign?
(c) If L2 were allowed to start at 1051 and assign 1055, write down the two-line
history that a snapshot read would then report, and name the invariant broken.

---

## Rung 6 — Spot the flaw

Each of these is a proposal a reasonable engineer might make. For each: construct a
concrete run with real numbers where it gives a wrong answer, and name the guarantee
it violates.

**6.1** *"Skip commit wait for single-group transactions. Paxos already orders writes
within a group, so the timestamps are monotonic anyway."*

**6.2** *"A replica may serve a read at `t` as soon as it has applied every write up
to `t`. Prepared transactions haven't committed, so they can be ignored."*

**6.3** *"Commit wait guarantees the commit timestamp is in the past. So a read-only
transaction can just use its own machine's wall clock as the read timestamp."*

**6.4** *"Leader leases exist because of network partitions. Inside a single
datacenter with a reliable network, a new leader can take over the instant the old one
stops responding."*

**6.5** *"To reduce commit wait, choose `s` as the midpoint of the interval rather
than the latest endpoint — it's the most likely value of the true time, and it halves
the wait."*

---

## Rung 7 — Handle failure

Extend the design on paper to survive something it currently does not.

**7.1** A client issues a read-write transaction. The coordinator commits it and the
acknowledgement is lost in the network. The client retries.
(a) What does Spanner itself guarantee about that retry?
(b) For which shape of transaction is the retry naturally harmless, and for which is
it not?
(c) What must the application add so that the unsafe shape becomes safe, and in which
transaction must that addition be written?

**7.2** Design the top of an on-call dashboard that would let someone distinguish,
within sixty seconds and without reading any code, between: an elevated-ε incident, a
hot-split incident, and a stuck-prepared-transaction incident. Name one metric per
row and the shape you would look for in it. Say which of the three is *not* a Spanner
problem at all.

**7.3** You need strong reads served inside a region that contains only read-only
(non-voting) replicas. Working from `t_safe`, say what such a read must still touch,
what latency that implies, and what you would offer the application instead.

---

## Rung 8 — Design

No single correct answer. Write the argument.

**8.1 The ledger.** Design the core tables and the read/write paths for a payments
ledger on Cloud Spanner. Constraints: three regions roughly 80 ms apart, 20,000
writes per second at peak, and one hard rule — *a customer must never see a debit
without the matching credit*, on any device, at any moment. Cover: primary keys and
why, what is interleaved into what, the instance configuration and where you put the
leader region, which reads are strong and which are stale and why, the top three
things you monitor, and one thing you would deliberately give up.

**8.2 No atomic clocks.** You must provide the same guarantee on commodity hardware
where the honest bound is ε ≈ 250 ms. Price all three options — writer pays, reader
pays, client pays — against a workload that is 95% reads, and recommend one.

**8.3 The counter-proposal.** A team wants to replace Spanner with sharded Postgres
plus a saga, arguing that Spanner is expensive and that eventual consistency is fine.
Write the one-page response. It must contain the single question whose answer decides
it, and it must concede the strongest point on their side.

**8.4 The uncomfortable one.** Spanner's entire correctness argument assumes hardware
that drifts no faster than its stated bound. Write the paragraph you would put in a
design document acknowledging this, in a way that is honest without being alarmist —
and name the one operational control you would insist on because of it.
