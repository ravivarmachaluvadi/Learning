# PACELC — Exercises

Pencil-and-paper plus the visualization ([`visualization.html`](visualization.html)).
Attempt each rung before opening [`SOLUTIONS.md`](SOLUTIONS.md). The RTT matrix
used throughout (same as the doc and the sim):

| Link | RTT | One-way |
|---|---|---|
| Mumbai ↔ Frankfurt | 110 ms | 55 ms |
| Frankfurt ↔ Virginia | 90 ms | 45 ms |
| Mumbai ↔ Virginia | 190 ms | 95 ms |

---

## Rung 1 — Explain

1.1 In your own words, no acronyms: what question does PACELC ask that CAP
cannot even express — and why did the missing question matter in practice?

1.2 A colleague says: "Our reads are stale because of the CAP theorem."
The network has had no partition all quarter. Write the two-sentence correction.

1.3 State the one-sentence version of the else-clause tradeoff (the "someone
waits" sentence), and say who "someone" can be.

1.4 A user writes: *"I changed the price, refreshed, and my change was
gone."* Another writes: *"The price showed ₹54,000 and then went back to
₹50,000."* In plain words, what is broken in each case — and why is neither
of them answered by the sentence "our reads are eventually consistent"?

1.5 Two sentences, no jargon: what is the difference between a stale read
that *informs* and a stale read that *authorizes*, and why does only one of
them get fixed by refreshing the page?

1.6 Explain to a colleague why "we need strong consistency, because we must
never sell the eleventh necklace" is not a finished argument — and what
question you would ask before agreeing to pay for coordination.

## Rung 2 — Identify

Open the visualization, pick the **majority** design, press a write in Mumbai,
and step through it.

2.1 Which messages are the big colored dots and which are the small hollow
ones? Which single message arrival makes Asha's write "official"?

2.2 In the verdict panel, which two cells of the PACELC meter are lit, and
what observed evidence on screen backs each lit cell?

2.3 Switch to the **home-office** design (home = Mumbai). Which region's
writes are cheap and which are expensive, and where do you see that number?

2.4 Pick the **session** design ("never show a client its own past"). Write
in Mumbai, then set Asha's "reads served by" to Frankfurt and press her Read
button. Find on screen: (a) Asha's session token and what is in it, (b) the
⏳ marker and which city carries it, (c) how long the read waited. Then say
which *other* counter kept climbing while "your own change vanished" stayed
at 0, and what that difference means.

2.5 Open **Tab 2** and run each design once. For each of the three, say in
one line **where the rule is enforced** — name the exact place that would
have to fail for an eleventh necklace to be sold. Then say which design has
no such place at all.

2.6 Still on Tab 2, escrow design: press **Frankfurt → Mumbai** under *Move
allowance* and watch the two branch cards plus the log. For the 55 ms the
transfer is in flight, who may sell those two necklaces? Why is that the
only safe answer, and what would go wrong with the alternative?

## Rung 3 — Predict

Answer first, then check with the named scenario.

3.1 Design: answer-locally. Asha writes at t=0 (acked t=1 ms). Omar reads
Virginia at t=40 ms and again at t=200 ms. Verdict of each read, and the exact
`staleBy` of the first? (Scenario 1.)

3.2 Design: majority. Omar (Virginia) writes on a healthy network. Predict his
ack latency from the RTT table — which region supplies the second ack?
(Free-play: press his write button.)

3.3 Design: wait-for-everyone-with-give-up. Virginia is cut off; Omar writes
at t=10, Asha at t=20; heal at t=1500. Predict: how many degraded acks, and
whose acknowledged write is discarded at heal? (Scenario 6.)

3.4 Design: answer-locally. Asha writes at t=0 (acked t=1 ms) and her next
read at t=10 ms is served by Frankfurt. Predict the value she sees and which
two counters move. Then predict the same script under the **session** design:
what does her read return, at what time, and what does Priya's own local read
at t=20 ms return? (Scenarios 7 and 8.)

3.5 Design: answer-locally, nobody roaming yet. Priya reads Frankfurt at
t=60 ms, then her next read at t=70 ms is served by Virginia. Predict both
values and name the guarantee that breaks — noting that Priya has written
nothing at all. (Scenario 9.)

3.6 **Tab 2, before running anything.** Eleven customers (six in Mumbai,
five in Virginia), ten necklaces, Virginia cut off. Predict for each design,
as four numbers — sold / oversold / turned away / turned away while stock
remained:
(a) sell-now-tell-later, (b) head-office approval, (c) split 4/3/3,
(d) split 6/2/2. Then run Scenarios 10–13 and reconcile every number you
got wrong. (One of them is the same in (c) and (d) — say which, and why
that is the point.)

3.7 Same eleven customers, **no cut at all**, sell-now-tell-later design.
Predict whether it still oversells, and justify your answer in terms of two
numbers from the RTT table and one number from the page.

## Rung 4 — Modify

Use the controls; predict before each change.

4.1 Majority design: drag "network distance" to 10×. Write from Mumbai. What
happens, and why is this outcome identical in kind to a partition — with no
partition set? Which configured number defines the boundary?

4.2 Home-office design: move home from Mumbai to Frankfurt. Recompute every
region's write-ack latency from the RTT table, then verify. What decides where
a per-record master *should* live in a real system?

4.3 Majority design at 3 regions has write latency ≈ nearest-neighbor RTT.
Your team proposes 5 regions (adding Tokyo and São Paulo) "for durability",
keeping majority acks. Which direction does write latency move, and why?

4.4 Session design. Set Asha's reads to be served by Virginia, cut Virginia
off, then write in Mumbai and read as Asha. Predict what happens to that
read, and then answer: the session design is tagged PA/EL — so where did a
refusal come from? State the general rule this illustrates about any promise
a system makes.

4.5 Majority design. Drag "network distance" down to **0.1×** (replicas
about 10 ms apart, the way three datacentres on one continent are) and write
from Mumbai. Note the ack. Now re-answer, in two sentences, the claim
"strong consistency is too slow for us" — and say what you gave up to make
it cheap.

4.6 Using the RTT table only (no simulator): Omar in Virginia writes a
record whose leader is in Mumbai, in a majority-of-3 system. Compute the
total, split into the three terms of §12's decomposition. Then move the
leader to Virginia and recompute. Which term changed, and by how much?

4.7 Tab 2, escrow, split 4/3/3, no partition. Mumbai has sold its four.
Move two from Frankfurt, then send two more customers into Mumbai. Now
answer: how many *sales* were coordinated, how many *transfers* were, and
what is the ratio if a branch sells 200 necklaces a day with an allowance
topped up in blocks of 20? Compare that ratio with head-office approval,
where every sale coordinates.

4.8 **The capacity change nobody wrote down.** A read endpoint takes
1,500 requests/s. Reads are local at 3 ms; a config change makes them
majority reads at 90 ms. The service holds one connection per in-flight
request and the pool has 60.
(a) Compute the in-flight count before and after (`L = λW`).
(b) Compute the throughput ceiling before and after (`c / W`).
(c) At 1,500 req/s, what is ρ after the change, and what does the queue do?
(d) What pool size would the EC path actually need to run at 80% busy?
(e) Name the metric that would have shown this coming, and say why p99
latency is *not* that metric.

## Rung 5 — Trace

Design: majority. Healthy network. By hand, one line per event with a time
column: Priya (Frankfurt) writes at t=0; Omar (Virginia) issues a quorum read
at t=60. Derive: when each `repl` arrives, when each `ack` returns, Priya's
ack time, which two replies Omar's read collects, its completion time, its
answer, and the verdict under the recency rule. Then run it in free-play and
compare.

**5.2 — the beacon trace (paper only; the simulator does not implement it).**
A home-office design, master in Mumbai, publishes a beacon every 100 ms
saying "everything stamped at or before (now − 100 ms) has been sent to
everyone". Virginia is 95 ms away. Asha writes at t = 0. Write the timeline
of: when Virginia holds the value, when the first beacon that covers it is
published, when Virginia receives that beacon, and what Virginia may answer
at t = 300 ms. Then give (a) the worst-case age of an answer Virginia serves
this way, as a formula in the three quantities involved, and (b) the reason a
replica cannot make this promise on its own.

**5.3 — the escrow ledger, by hand.** Split 6/2/2, Virginia cut off at
t = 0. Customers arrive every 20 ms: Mumbai, Virginia, Mumbai, Virginia, …
until six have arrived in Mumbai and five in Virginia. Write the table —
time, branch, decision, allowance remaining, running total sold — and then
state (a) the exact moment the first customer is turned away, (b) how many
necklaces are unsold when the dust settles, and (c) the one line in your
table that a "sold out" error message would have described dishonestly.

## Rung 6 — Spot the flaw

A design document says:

> "Our store is PC/EC: writes commit through a majority quorum (2 of 3
> regions). For read performance, every region serves reads from its local
> replica. Since writes are majority-committed, reads are consistent."

Construct a concrete run (times, regions, values, using the RTT table) where
a read violates the recency rule, name the false step in the quoted argument,
and say which PACELC letter the system actually has on the read path.

**6.2** A second design document says:

> "We added session tokens, so each user always sees their own writes and
> never sees data move backwards. Our reads are therefore consistent, and
> the fraud engine can safely read the account balance from the nearest
> replica before approving a payment."

Two separate things are wrong. Name both, and for the second one construct a
concrete run — two users, real times from the RTT table — where the fraud
engine approves something it should not. Then state the smallest change to
the *requirement* (not the mechanism) that would make a local replica read
legitimate.

**6.3** A third design document says:

> "We store the stock count as a conflict-free replicated counter (a CRDT).
> Increments and decrements from different regions merge without conflict
> and every replica converges on the same total, so we cannot lose an update
> and we cannot oversell. We also auto-rebalance regional allowances: when a
> region has been quiet for 30 seconds, a background job reassigns its
> unused allowance to the busiest region."

There are two independent errors, one in each half. For the first, say
precisely what a CRDT guarantees and what it does not, and give the
smallest run that ends with a negative stock. For the second, give the run
where two regions sell the same necklace, and name the mechanism from an
earlier lab that the rebalancer is missing.

## Rung 7 — Handle failure

Your company runs the PA/EC design (wait-for-all with a 1000 ms degraded-ack
timeout) for a customer-orders table. Management refuses to give up
partition-time availability ("we never stop taking orders"). Redesign the
*degrade path* so that partition-era orders are never silently discarded at
heal. Specify: what the degraded ack must additionally do, what the isolated
side is allowed to promise the customer, what happens at heal instead of
last-write-wins, and the two monitoring signals that page a human. (Your
idempotency and transactional-outbox labs supply the parts.)

**7.2** Your read-replica router serves reads locally as long as the replica
is within a 2-second staleness bound, and falls back to the primary when it
is not. On Monday at 09:00 a large batch job pushes every replica past the
bound at once, and the primary — already busy with that batch — receives the
entire read load of the fleet and starts timing out, which pushes the
replicas further behind. Describe: the name for this shape of failure, three
concrete mechanisms that stop it (your caching and rate-limiting labs supply
all three), what the bound Δ should have been set to in the first place given
a 95 ms one-way delay, and the one dashboard that would have warned you days
earlier.

**7.3** At 09:14 a change enabling strongly-consistent reads reaches
production. By 09:21 the service is returning connection-pool timeouts, the
database's own dashboards look healthy (CPU 30%, its reported query latency
normal), and the error rate is climbing while throughput is flat. Clients
retry twice with a 600 ms timeout.
(a) Explain the mechanism in the order it happened, using `L = λW`.
(b) Say why "throughput is flat" is the most alarming fact on that list.
(c) You roll the change back at 09:26 and the service does **not** recover.
Explain why, and say what actually restores it.
(d) Give the two guardrails that would have made the change safe, and the
one that would have made the rollback work.

## Rung 8 — Design

A payments platform runs in 3 regions (the RTT table above). Four flows:

- **A. Product page price display** — millions of reads/s.
- **B. Add-to-cart** — user-visible, must never block the shopper.
- **C. Payment authorization + ledger append** — money moves; double-spend
  must be impossible to *see* or *keep*.
- **D. Fraud-score lookup** at checkout — reads a model score computed
  minutes ago.

For each flow: choose the partition-clause letter and else-clause letter,
name the mechanism (which of the four sim designs — or a named refinement
from a prior lab — implements it), state the expected latency from the RTT
table, and give the one metric an on-call engineer watches for that flow.
Then answer: which single flow justifies paying the EC bill, and what is the
cheapest correct place in the architecture to pay it?

**8.2 — placement.** Continue the same design. Your ledger writes are
majority-committed across the three regions. (a) Compute the write bill for
a customer in each region, using §12's three-term decomposition. (b) You are
allowed to move where each account's leader lives. Propose a rule, compute
the new bill for the common case, and say exactly which flow gets *worse*.
(c) Your compliance team then requires that no account's data leave its home
region. Which of your choices survive, which die, and what is now impossible
to promise about a cross-region transfer?

**8.3 — the checkout page.** The checkout page shows: the price, the cart,
the fraud score, and — right after the "Pay" button returns — the new
balance. For each of those four, give the freshness requirement in seconds
(be specific), then pick the cheapest mechanism from §11's cost table and
justify it in one line. At least one of them must **not** be a coordinated
read, and you should be able to say why in terms of the one-way delay.

**8.4 — the three questions, applied.** The same payments platform adds two
features: a **promotional stock** of 10,000 discounted units sold globally,
and a **per-customer daily spend limit** enforced at authorization time.
For each of the two, work through §14's three questions in order and produce:
(a) the answer at each question and where you stopped; (b) the mechanism you
would build, with its numbers (allowance sizes, refill interval, what happens
at zero); (c) the invariant each branch enforces locally and the argument
that the local invariants imply the global one; (d) the failure this design
can still have, and the metric that detects it; and (e) what changes if the
business says the promotional stock must **never** be under-sold — every one
of the 10,000 must go — rather than never over-sold.
