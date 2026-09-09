# CAP Theorem — Exercises

Work top to bottom. Most run against the simulator
([`visualization.html`](visualization.html)). Answers in
[`SOLUTIONS.md`](SOLUTIONS.md) — attempt each one before looking.

## Part 1 — Predict

**1.1** The cluster is healthy (no partition), mode is **AP · one** (W=1,
R=1). Client L writes, then client R immediately reads. Will R's read be
stale? Decide first, then run it in the simulator. Then explain: why does
R+W ≤ N *not* guarantee a stale read here?

**1.2** Mode **CP · quorum** (W=2, R=2), network **cut** ({A} | {B,C}).
Predict the outcome of each of these four requests, in order:
L write, L read, R write, R read. Which island's customers experience the
outage? Run it and check all four.

**1.3** Set the sliders to **W=3, R=1** and cut the network. Predict: can
*either* side write? Can either side read? Is a stale read possible in this
configuration — and does R+W>N still hold? Run it.

**1.4** Set **W=1, R=3**, healthy network. A write happens, then a read. Now
cut the network and repeat on the majority side. What does this configuration
optimize for, and who pays?

## Part 2 — Modify

**2.1** In the simulator, find a slider setting where **both** islands can
keep writing during the partition. What is the *smallest* W that allows it?
What new problem have you just signed up for (run experiment 4 if unsure)?

**2.2** Predict *first*, then check with the **Split** dropdown. Switch the
tear to `{A,B} | {C}` in CP mode (W=2, R=2), leaving L on machine A and R on
machine C. Which customer is refused now, and which one never notices the
storm? Then answer the real question: what actually decided who suffered —
anything about the customer, or purely which machine their connection landed
on?

**2.2b** Keep the tear at `{A} | {B,C}` and instead use the **Customer L is
talking to machine** dropdown to move L onto B. Now cut. L is refused
nothing. Explain in one sentence why real clients are given a list of several
nodes to try rather than a single fixed one — and what that costs you in a
CP system when *every* node the client can reach is on the minority side.

**2.3** Real quorum clusters use 5 nodes, not 3. With N=5, W=3, R=3: how many
nodes can be partitioned away before the majority side stops accepting
writes? Why do operators pick odd N — what specifically goes wrong with N=4,
W=3 under a 2-2 split?

## Part 3 — Debug

**3.1** A teammate proposes: "Our system is CA — we replicate synchronously
to both replicas, so it's consistent, and both replicas serve reads, so it's
available. We just won't have partitions because we use a really good
network." Find the two separate flaws in this claim.

**3.2** Incident report: *"Customer changed their email address; confirmation
page showed the new address. Ten minutes later, password-reset emails were
still going to the OLD address. No errors were logged anywhere."* The system
runs on an AP store with read-repair. Explain the mechanism of this bug in
CAP terms, why *no errors* is the expected signature, and name the two knobs
from this lab that would have prevented it (and their cost).

**3.3** In the simulator, do: AP mode → cut → L write (bal=150) → heal →
read from R. The read returns 150 and is logged OK. A teammate concludes
"see, AP systems are consistent once the partition heals, so nothing was
ever really at risk." Using only actions available in the simulator, produce
the counterexample that proves an **acknowledged write can be permanently
lost** — and state which log line is the smoking gun.
