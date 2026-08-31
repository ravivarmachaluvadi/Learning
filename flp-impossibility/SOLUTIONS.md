# FLP Impossibility — Solutions

Attempt the exercises first. Every number quoted here was produced by the
simulation engine in `visualization.html`; you can reproduce all of them.

---

## Rung 1 — Explain

**1.1** *(model answer, 108 words)*
Ada writes to Bo and waits. Nothing comes back. There are exactly two reasons
that could be true: Bo is dead, or Bo's letter is on a slow boat. Ada's evidence
is the same in both cases — an empty mailbox — and no letter she can write will
tell them apart, because the only difference is how long the future will take.
So she has to guess. Guess too short and she writes off a living colleague, who
is also carrying on, and now two people are running the coast. Guess too long
and she waits past dawn. A timeout does not remove the guess; it just picks a
number for it.

**1.2** FLP does not say agreement is impossible — it says no protocol can
*guarantee* that it always finishes, in a world where messages can take
unbounded time. etcd works because it does not live in that world: it assumes
message delays are usually bounded, and turns that assumption into an election
timeout. When the assumption briefly fails — a long GC pause, a saturated link —
etcd does exactly what the theorem predicts and stops making progress for a
while. It never gives a wrong answer, though; safety never depended on timing.

**1.3** *"Nobody has decided yet"* is about what is written on the slates.
*"The outcome is not yet decided"* is about what is still **reachable**. They
come apart: a situation can have three blank slates and still be **univalent** —
every possible continuation ends in DARK, and the keepers simply have not caught
up yet. The proof needs the second, stronger statement (**bivalent**), because
its whole strategy is *"stay somewhere both endings are still live"*. If it only
tracked blank slates, it would have no way to rule out walking into a situation
whose ending was already sealed. You can see the difference in tab 3: *all three
want DARK* has three blank slates and is univalent; *Ada DARK, Bo LIGHT, Cy
LIGHT* has three blank slates and is bivalent.

**1.4** **Zero.** In the run the proof constructs, all three keepers work the
entire time and every letter is delivered. This surprises people because the
theorem is usually paraphrased as *"with one faulty process"*, which sounds like
a process must fail. What the proof actually uses is the **obligation to
tolerate** a failure: because the protocol must be able to finish without any
one keeper, there always exists a run in which the others carry on without
hearing from that keeper — and that run is indistinguishable from one where the
keeper was simply slow. A protocol that refused to tolerate any crash could
just wait for everyone, would be immune to FLP, and would deadlock the first
time a machine rebooted.

---

## Rung 2 — Identify

**2.1** The very first event of any run must be a **null step** — a step in which
a process receives *nothing*. It cannot be a message delivery, because no message
exists until somebody sends one, and a process only sends messages as part of
taking a step. This is the model's answer to *"what starts the machine?"*, and it
is why a timeout is not something bolted on to consensus — it is the only
possible first move.

**2.2** A representative instance, from the trace in README §10.4:
(a) either remaining `ACCEPT(1, DARK)` letter — the one to Bo or the one to Cy —
would have produced a second acceptance and settled the matter;
(b) the ferryman instead delivered nothing to them, and let **Bo give up
waiting**, which started attempt 2 and sent `PREPARE(2)` to everyone;
(c) once Bo and Cy had promised attempt 2, the `ACCEPT(1, DARK)` letters were
delivered normally and **refused**. The dangerous letter was not withheld — it
was made harmless first, then delivered.

**2.3** For the ferryman to be cheating, either *letters lost* would have to be
non-zero, or *longest a letter has waited* / *longest any keeper sat idle* would
have to grow without bound. Neither happens: measured over 5000 steps, letters
lost stays **0**, longest letter delay stays at **7 steps**, and longest keeper
idle stays at **4 steps**. That lets you make the claim that matters: **the run
is admissible** — it satisfies the fairness contract completely — and still
nobody decides. If the numbers grew, the demonstration would prove nothing,
because an adversary who starves people obviously breaks things.

**2.4** The half-dark, half-lit lamp is **bivalent** — both endings still
reachable. In a univalent situation where nobody has decided, the lamp would look
*exactly the same*, and that is the point: **valency is a property of the whole
configuration, not of anything a single keeper can see.** No island — and no
picture of an island — can display it. The page can only compute it globally, by
searching schedules, which is what tab 3 does.

**2.5** The **attempt number** is the interesting one. The step count only says
time passed. The attempt number climbing (measured: **682 after 5000 steps**)
says the keepers are *working hard* — starting attempt after attempt, each one
reaching one acceptance and dying. It is the difference between a system that is
idle and a system that is busy achieving nothing, and only the second one is a
livelock.

---

## Rung 3 — Predict

**3.1 Yes, the ferryman still wins.** Measured: all three wanting LIGHT, the
stalling ferryman held it open for **1000 steps, reaching attempt 137**, longest
letter delay 7 steps. Under the ordinary ferry the same start settles on LIGHT
at step 15.

This is the most instructive result in the exercise set, because that starting
point is **univalent** — LIGHT is the only reachable answer. Univalence says
*which* value, if one is ever chosen. It says nothing about *whether* one is
ever chosen. **Determined and terminating are different properties**, and FLP
attacks the second.

**3.2** (a) Ordinary ferry with Bo ill: the remaining two settle on **DARK at
step 30** on attempt 1 — slower than the 15 steps with three healthy keepers,
because Ada waits for letters that will never arrive and has to be satisfied by
a bare majority. (b) Stalling ferryman with Bo ill: **still nothing decided after
1000 steps, attempt 189**. Two healthy keepers are quite enough to duel with each
other. *(With Ada ill instead, the ordinary ferry settles on LIGHT at step 30, on
attempt 2 — Bo's attempt. Note the answer changed, and that is legal: Validity
only requires the answer be somebody's preference.)*

**3.3** Δ = 2 settles at **step 19**; Δ = 25 settles at **step 63**. Both use
**exactly one attempt**. The lesson: once the ferryman cannot delay a letter past
the deadline, he cannot manufacture a rival attempt in time, and the very first
attempt runs to completion. **The cost of a loose bound is pure latency, not
extra attempts** — a system with a generous election timeout is not less correct,
it is just slower to recover.

**3.4** The ferryman **loses**, in **13 steps**, using **1–3 attempts**
(specifically: attempt 1 if Ada is the named leader, 2 if Bo, 3 if Cy — each
keeper's first owned attempt number). This is the striking one: **no timing
assumption at all** was needed. Take away the ferryman's ability to manufacture a
rival attempt and he has nothing left. The catch is in README §12.4 — such an
oracle cannot be built in a truly asynchronous world, because building one would
refute FLP.

**3.5** Ada proposes **her own preference**, because neither agreement she
received reported an earlier acceptance. **This is safe**, and the rule that
makes it safe is quorum overlap, not luck: Bo's `(4, LIGHT)` was accepted by
**one** keeper, so LIGHT was never *chosen* — choosing requires a majority. Any
value that *was* chosen would have been accepted by a majority, and Ada's
majority of agreements must intersect it, so at least one of her agreements would
have reported it and forced her hand. A single acceptance is a vote, not a
decision; the protocol is allowed to abandon it. (This is your `paxos` lab's
`P2c` invariant, and this lab re-measured it: **0 agreement violations in 500
random schedules**, one third of them with a crashed keeper.)

---

## Rung 4 — Modify

**4.1** You attacked **deterministic** — and it did not help. Measured: with
randomised waiting times and no delivery deadline, the ferryman held it open in
**60 runs out of 60**. The reason is that the adversary is *patient and
adaptive*: when a keeper's random pause is in effect, he simply delivers other
letters until it expires, then uses the keeper exactly as before. Randomising
*when* somebody retries does nothing against an opponent allowed to wait
arbitrarily long.

Adding Δ = 4 changes everything: **60 out of 60 runs settle, averaging 23.0
steps** — the same as Δ = 4 with no randomisation at all. So in this
three-keeper setup the randomisation contributed *nothing measurable* once Δ was
present. What it buys in a real system is protection against **collisions**:
many nodes timing out simultaneously and cancelling each other. Your `raft` lab
measured that directly — identical timeouts gave *term 40 in 6000 ticks and no
leader ever*, while randomised ones elected a leader in 200/200 cold starts.
**Randomised timeouts cure collisions; they do not cure asynchrony.**

**4.2** It does **not** defeat the ferryman. He controls the delivery of the
refusal letter exactly as he controls every other letter, so he can hold it as
long as he likes. What improves is **expected liveness under partial synchrony**:
a superseded proposer learns quickly that it is wasting its time and can back off
instead of sitting in a doomed attempt, which reduces how often two proposers
duel. What is completely unaffected: **safety** (it was never at risk) and **the
impossibility itself**. Real Paxos does send negative acknowledgements for
exactly this reason — it is a practical optimisation, not a fix.

**4.3** **Essentially no change.** The ferryman needs only that *some* higher
attempt number is always obtainable, and both schemes give him that. Free choice
arguably makes his job marginally easier — any keeper can outbid immediately
rather than waiting for their slot — but "marginally easier than always winning"
is not a meaningful improvement. Rotation exists for a different reason: it makes
"who is the natural leader right now" computable from the attempt number alone,
which is what Multi-Paxos and Raft build on.

**4.4** The plan requires the keepers to agree on **what day it is**, and there
is no clock. Agreeing on the current day *is itself a consensus problem* — the
same problem, with a different name — so the plan assumes the thing it was meant
to provide. This is the general shape of most proposed escapes: they smuggle in a
shared time reference. The honest version of this plan is *"assume a bound Δ and
derive turns from local timers"*, which is escape 1, with all its conditions.

---

## Rung 5 — Trace

**5.1** Executed by the simulator, step by step:

```
1  Ada gives up waiting        → PREPARE(1) to Ada, Bo, Cy
2  PREPARE(1) → Bo             Bo promises 1, replies PROMISE(1, accepted:none)
3  PREPARE(1) → Cy             Cy promises 1, replies PROMISE(1, accepted:none)
4  PROMISE(1) → Ada            Ada has 1 of 2 agreements
5  PROMISE(1) → Ada            Ada has 2 → proposes DARK, sends ACCEPT(1,DARK) ×3
6  ACCEPT(1,DARK) → Bo         Bo accepts (1,DARK), replies ACCEPTED(1)
7  Cy gives up waiting         → PREPARE(3) to all   (3 is Cy's next owned number)
8  PREPARE(3) → Bo             Bo promises 3, reports it accepted (1,DARK)
9  ACCEPT(1,DARK) → Cy         Cy ACCEPTS it
```

**At step 6 the letter carries DARK**, because neither agreement Ada received
reported an earlier acceptance, so she was free to propose her own preference.

**At step 9 Cy accepts** — and this is the subtle part. Cy *started* attempt 3 at
step 7, but Cy's own `PREPARE(3)` to itself is still at sea, so **Cy's promised
attempt is still 1** and the late `ACCEPT(1, DARK)` is perfectly acceptable to
it. A keeper's own letters to itself go through the same post as everyone else's,
and the ferryman may delay them too.

Final state: `Ada{promised 0, accepted none, running attempt 1, 0 acceptances
collected}` — her own `PREPARE(1)` to herself was never delivered, and both
`ACCEPTED(1)` replies are still at sea; `Bo{promised 3, accepted (1,DARK)}`;
`Cy{promised 1, accepted (1,DARK), running attempt 3}`.

**What Ada believes about attempt 1:** nothing has come back, so she cannot
distinguish "two keepers accepted and the replies are in transit" (which is what
actually happened, and means DARK is now **chosen**) from "everyone refused me".
Two keepers accepting *is* a majority — the value is chosen at step 9 — and
**nobody in the system knows it yet.** That gap between *chosen* and *known* is
where every consensus bug lives.

**5.2** The ferryman's three moves, taken from the engine:

```
1  let BO give up waiting     → Bo starts attempt 5, PREPARE(5) to all three
   why: the two ACCEPT(4,DARK) letters at sea are lethal — attempt 4 already
   has one acceptance (Ada's own), so either letter would be the second.
   He needs a higher attempt in existence before he delivers them.
2  deliver PREPARE(5) → Bo    Bo promises 5
3  deliver PREPARE(5) → Cy    Cy promises 5
   why: these are the two islands the lethal letters are addressed to.
```

Afterwards: `Ada{promised 4, accepted (4,DARK)}`, `Bo{promised 5}`,
`Cy{promised 5}`, and at sea: both `ACCEPT(4,DARK)` letters (now certain to be
refused), plus `PREPARE(5)→Ada` and two `PROMISE(5)` replies to Bo. The ferryman
can now deliver the two dangerous letters at his leisure, fairness intact,
knowing they do nothing. Attempt 5 will die the same way.

**5.3** Both orders give the identical result. `PROMISE(1) Bo→Ada` only touches
Ada's state; `ACCEPT(1,DARK) Ada→Cy` only touches Cy's state. Neither can observe
the other, because **the only thing that can change a keeper is a letter arriving
at that keeper**. Whichever order you use, you end with Ada holding one agreement
for attempt 1, Cy having accepted `(1, DARK)` and sent `ACCEPTED(1)`, and Bo
untouched.

For a pair where the answer is **no**, take two events at the *same* island — for
example `PROMISE(1) Bo→Ada` and `PROMISE(1) Cy→Ada`. The second one arrives at an
Ada who has already been changed by the first: after one promise she is still
collecting; after the second she reaches a majority and sends out `ACCEPT` — and
*which* promise arrived second determines which one she was holding when she
crossed the threshold. The engine's audit finds exactly this: **133 of 133 swaps
at different islands identical, 5 of 48 at the same island different.**

---

## Rung 6 — Spot the flaw

**6.1 "Wait three round trips before retrying."** Two errors. First, *"three
round trips"* is not measurable without a clock or a delay bound — the thing the
model denies you. Second, even granting a way to count them, the ferryman decides
*when every step happens*, so he simply arranges for the wait to expire at the
moment that hurts. This is exactly the randomised-backoff escape, measured:
**60 runs out of 60, the ferryman still won.** Violated guarantee:
**Termination**. What the rule genuinely buys is a lower *probability* of
collision under partial synchrony — worth having, but not a fix.

**6.2 "30 seconds is 300× our p99, so it is safe."** The error is reasoning from
a percentile to a bound. p99 says nothing about the tail beyond it, and the tail
is where this problem lives: your `split-brain` lab measured connection p99 going
from **1,011 µs to 191,661 µs — a 190× collapse — on a healthy server**, caused
by garbage collection, which is inside the machine and entirely unaffected by
network quality. A 400 ms pause can become a 40 s pause on a large heap under
pressure. Two guarantees are at stake, not one: **Termination** if the timeout is
too long, and — much worse — **Agreement**, if "declare failed and proceed" is
not paired with fencing, because the declared-dead participant is still running
and its in-flight work will land later. See `split-brain` for the fencing
machinery.

**6.3 "Randomised election timeouts, so FLP does not apply."** Randomising *when*
a node retries is not the same as randomising *what it decides*. The transition
function is still deterministic given its inputs; only a timer draw changed, and
the adversary is adaptive and patient enough to wait it out. Measured in this
lab: **60 runs out of 60 held open** with randomised waits and no delivery bound.
The escape that genuinely breaks "deterministic" is Ben-Or's, where processes
flip coins over the **value** — and it buys termination *with probability 1*,
which is still not a guarantee.

**6.4 "Seven replicas, so more resistant."** FLP needs **one** possible crash and
holds for any number of processes. Going 3 → 7 raises the number of crashes you
survive, which is a real and different benefit — but it does nothing about the
slow-versus-dead ambiguity, and it makes each round require agreement from 4
nodes instead of 2, so the *slowest* member of a larger quorum sets your latency.
On liveness, larger clusters are usually slightly worse, not better.

**6.5 "Single rack, sub-millisecond RTT, effectively synchronous."** You have an
empirical *distribution*, not a *guaranteed bound* — and the theorem's asynchrony
is about the absence of a guarantee. Worse, the dominant sources of long pauses
are not on the wire at all: stop-the-world GC, page-cache stalls, kernel
scheduler preemption, a hypervisor stealing CPU, an fsync behind a full disk
queue. A dedicated switch fixes none of those. The honest version of this claim
is *"our Δ is small and we monitor it"*, which is exactly right and is escape 1
stated properly.

---

## Rung 7 — Handle failure *(worked discussion, not a key)*

**7.1 Forty elections an hour, no crashes.**

*Measure first:* GC pause p99/p999 on every member; fsync p99 on the consensus
data directory; peer-to-peer RTT p99; and the ratio `election timeout ÷ observed
broadcast time`. You are looking for which of these is closest to the timeout.

*Three likely causes and their fixes:*

| Cause | Fix | Word attacked |
|---|---|---|
| GC pauses exceeding the election timeout | raise the election timeout; tune/replace the collector; shrink the heap | **asynchronous** — you are restoring the Δ you assumed |
| a disk whose fsync tail exceeds the heartbeat interval | faster/dedicated disk, or separate the consensus volume | **asynchronous** |
| a partitioned or flapping member repeatedly bumping the term on return | enable **pre-vote** and **check-quorum** | neither — this attacks *disruption*, not the theorem |

*What would not help:* adding replicas (§11 sighting 6 — raises crash tolerance,
does nothing for liveness, enlarges the quorum you must assemble); lowering the
election timeout (makes false positives more frequent, which is the disease);
restarting nodes (see 7.4).

**7.2 Making a slow keeper harmless when it wakes.**

The design must add a **monotonically increasing epoch** attached to every action
— your `raft` term, your `paxos` attempt number, your `distributed-locking`
fencing token — and, critically, **the resource must check it**. The keeper does
not need to know it was slow; the resource refuses work stamped with an epoch
older than the highest it has seen. Your `split-brain` lab's headline applies
directly: if the resource does not check the token, the first two mechanisms
give **zero** protection. Note what this does and does not achieve: it makes the
stale keeper's work **harmless**, which is a safety property. It does not stop
the stall, and nothing can.

**7.3 Detecting grey failure.**

Measure **time since the commit index last advanced**, per group, and compare it
against **whether client writes are arriving**. A leader that is heartbeating but
has not advanced the commit index while writes are queued is the signature.
Threshold: something like 5–10× your normal commit latency, tuned so it never
fires during a routine leader change. Automatic action: have the leader **step
down voluntarily** (most Raft implementations support a leadership transfer),
which is far safer than killing the process. The risk is a feedback loop — if the
underlying cause is cluster-wide (a slow disk model, a network event), automatic
step-downs will just move the problem around and add elections to an already
struggling cluster. So rate-limit it, and never let it fire on more than one
member at a time.

**7.4 "No leader for 60 seconds → restart nodes one at a time."**

*When it is right:* almost never as a first action, but it can clear a genuinely
wedged process — a deadlock, a leaked file descriptor, a pathological GC state.

*When it is actively harmful:* if the cause is load or slow disks, restarting
removes a member from an already-degraded quorum, forces log catch-up on
restart (more IO, on the disk that was already the problem), and can turn a
temporary stall into an outage. If two people run the runbook at once on a
three-node cluster, you lose quorum entirely.

*Replace it with:* (1) check whether this is a stall or a genuine failure —
`time since last commit`, GC p99, disk p99, peer RTT; (2) if the assumption is
merely strained, raise the election timeout at runtime if your system supports
it, and shed load; (3) only restart a member you have positively identified as
wedged, never on a timer, never more than one at a time, and never without
confirming the remaining members form a quorum.

---

## Rung 8 — Design *(worked discussion)*

**8.1 Config store across three datacenters, 50 ms apart.**

*What survives from this lab:* everything about safety. Quorum overlap,
attempt/term ordering, and the requirement that a value accepted by a majority
can never be lost are all independent of geography. Also surviving: the fact that
you cannot guarantee termination, now with a much larger Δ.

*What must change:* the timing constants, by roughly an order of magnitude. With
50 ms inter-DC RTT, a heartbeat interval of 100 ms and an election timeout under
500 ms is asking for permanent elections. Something like heartbeat 250 ms,
election timeout 2.5–5 s, keeping `broadcastTime ≪ electionTimeout ≪ MTBF` with
real margin. Also: leader placement now matters enormously, because every write
costs a cross-DC round trip to the nearest quorum member.

*The Δ you assume, and how you defend it:* Δ is the inter-DC p999 RTT plus the
worst intra-host pause you tolerate (GC, fsync). Defend it by measuring both
continuously and alarming on the *ratio* to your election timeout, not on
absolute values — because the absolutes change every time you move hosts or
resize heaps.

*What to monitor, and the single metric to keep:* keep **time since the commit
index last advanced**. It catches grey failure, leader loss, quorum loss and
disk stalls in one number, and it is the one that maps directly to customer
impact. Everything else — election rate, GC p99, fsync p99, peer RTT — is
diagnosis after that alarm fires.

*Documented behaviour when the assumption breaks:* writes fail with a retryable
error and a clear reason; reads continue from a local replica **with an explicit
staleness bound**, if your consistency model allows it; there is a tested,
rehearsed procedure for a DC loss. Write it down before you need it.

*What to tell the product team:* durability and correctness are unconditional;
**write availability is not**, and there is no upper bound on config-write
latency during a leader change. Give them a realistic p99, an explicit statement
that the tail is unbounded, and a design that does not put config writes on the
critical path of a customer request.

*Three nodes or five:* five, spread 2/2/1 across three DCs so that losing any one
DC leaves a quorum. **This has nothing to do with FLP** — it is purely about
crash tolerance and failure-domain independence. FLP is unaffected by the count.

**8.2 The reply about a 500 ms hard bound.** *(model answer, ~190 words)*

> We can't promise a hard upper bound on confirmation time, and I want to be
> straight about why, because it isn't something we can engineer around with more
> servers.
>
> Confirming a transfer means several machines have to agree it happened. When
> one machine doesn't hear from another, there's no way for it to tell whether
> that machine has died or is just slow — the evidence is identical. So it waits
> a while and then assumes the worst. That waiting period is a guess, and while
> it plays out, confirmation is delayed. This is a proven limit, not a gap in our
> implementation; it applies to every system that works this way, including the
> ones our competitors use.
>
> What we *can* promise: we never confirm a transfer that didn't happen, and we
> never lose a confirmed one. Those hold no matter what the network does.
>
> What I'd offer instead of a hard bound: a 500 ms target at p99.9, published and
> monitored; a clear retryable error rather than a hang when we miss it; and a UI
> that shows "processing" rather than blocking. If the 500 ms is a regulatory
> requirement, let's talk — that changes the design, not the timeout.

**8.3 A single leader with no election, promoted by a human.**

*What it buys with respect to FLP:* quite a lot, honestly. It replaces the
automatic failure detector — the component that cannot be correct — with a human
operator, who can look at dashboards, phone the datacenter, and apply judgement
that no timeout can encode. You have not defeated the theorem; you have moved the
unavoidable guess to somebody who is much better at guessing, and who can be held
accountable for it. In FLP terms it is a very slow, very accurate **oracle**
(escape 4).

*What it costs:* your recovery time is now measured in minutes-to-hours instead
of seconds, and it depends on somebody being awake. You have added a dependency
on a human to your availability story, and humans are unavailable in correlated
ways (holidays, incidents, 3am). You also need a safe promotion procedure —
fencing the old leader — or a human error turns directly into split brain, and
humans under pressure make exactly that error.

*When it is the right call:* when a wrong automatic failover is far more expensive
than a slow manual one. Primary/standby databases holding money, systems where
promotion has side effects that cannot be undone, and small deployments where
nobody is going to operate a consensus cluster correctly anyway. It is also the
right *first* version of a system: manual failover that works beats automatic
failover that has never been tested. The wrong version is automatic failover with
no fencing — that is strictly worse than both.
