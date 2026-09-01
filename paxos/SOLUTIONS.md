# Paxos — Solutions

Attempt each exercise before reading its answer.

Where an answer states a concrete outcome — `chosen = [...]`, a state line, a
count — that outcome was produced by driving this lab's simulator, not by
reasoning about what it would probably do. Where an answer is a design
discussion there is no single right answer, and the worked reasoning below is
something to compare yours against, not a key.

**Notation.** `A[p=2 wrote=Zoo@1]` = acceptor A has promised 2 and accepted
`Zoo` under proposal number 1. Five acceptors A–E, majority 3.

---

## Part 0 — Explain & identify

**0.1** One honest version: *Several machines must end up believing the same
single answer even though any of them can be off at any moment and their
messages can vanish. Just announcing an answer fails because two announcers
can each convince a different part of the group, and nobody can even tell
whether "the" answer exists yet. The fix: before pushing your answer, ask
more than half the group what they have already written down — because any
two more-than-half groups must share someone, you are guaranteed to meet a
witness of any earlier answer, and the rule is that you must then push THAT
answer instead of yours. So the first answer to reach more than half can
never be displaced — everyone later just ends up spreading it further.*

**0.2** *Accepted*: one acceptor wrote the value in its notebook — a local
fact, and one that may never be joined by enough others. *Chosen*: a majority
wrote the same proposal — a global, permanent fact that no one may even know
yet. **Experiment 3** exists purely for this gap.

**0.3** (a) The stars sit on the three teachers of the first deciding majority
(A, B, C) — members of the quorum that made the decision. (b) C has the amber
witness outline: it is the overlap member that saw the Zoo decided and reported
it to Rep 2. (c) On screen: "promised #n" and "wrote: value / under slot #n" —
in the engineering model: `promised_n` and `(accepted_n, accepted_value)`.

**0.4** **Rule A, the value rule:** with a majority of promises in hand, propose
the reported value carrying the **highest `accepted_n`**; only if no promise
reported any value may you propose your own. Glossary term: **adoption**.
Delete it and experiment 2 breaks — measured outcome `chosen = ["Zoo",
"Museum"]`, with witness C reporting the Zoo and being ignored.

**Rule B, the decision rule:** a value is chosen when a majority of acceptors
have accepted the **same `(n, v)` pair**. Glossary term: **chosen**. Weaken it
to "a majority hold the same value" and you would declare decisions that no
single proposal ever achieved.

**0.5** Evidence the second proposer ran: C, D and E all show `p=2` — a promise
number only a second round could have set — and C's value has been rewritten
under `@2`. Evidence it did not get what it wanted: the value everywhere is
`Zoo`, and P2 wanted `Museum`. The acceptor responsible is **C**, the only
member of both `{A,B,C}` and `{C,D,E}`. It reported `(1,"Zoo")` in its promise
and Rule A did the rest.

## Part 1 — Predict

**1.1** `A -> P1  PROMISE(n=9)  [already accepted (n=5, "Zoo")]`, and A becomes
`A[p=9 wrote=Zoo@5]`.

It reports the **accepted** pair `(5,"Zoo")`, not the promise `7`. Those are two
different facts. `promised_n = 7` means "I told someone they could have number
7", which may have gone nowhere — the proposer holding 7 might have crashed
before phase 2 ever happened. `accepted = (5,"Zoo")` is the last thing actually
written down, and it is the only thing that could be part of a decision. A
proposer needs to know what might already have been chosen; an unused promise
never was.

**1.2** **Accepted** — A replies `ACCEPTED(7, "Museum")` and becomes
`A[p=7 wrote=Museum@7]`.

`>=` is necessary because granting a promise sets `promised_n = n`. With a
strict `>`, an acceptor would reject the phase-2 message from the very proposer
whose phase-1 request it just granted — `n == promised_n` is the *expected*
case for a well-behaved proposer. The measured consequence in stage 12:
`chosen = []` with **5 NACKs**, one proposer, no faults, all five acceptors
reachable. Paxos could not complete a single round, ever.

**1.3** With **A, D, E**: P2 proposes **"Zoo"**. With **C, D, E**: P2 proposes
**"Museum"**.

The difference is who was asked. Only A had written "Zoo" down, so a quorum
containing A discovers it and a quorum avoiding A does not. This is the honest
and slightly uncomfortable truth about Paxos: when nothing has been *chosen*,
the outcome genuinely depends on who you ask — and that is fine, because there
is no decision to protect. The moment a majority has accepted, no quorum can
avoid all of them, and the dependence on "who you ask" disappears. **That
transition is the entire safety property.**

**1.4** The light never turns red and the decision field never leaves `none`.
**Safety** is preserved (at most one value is ever chosen — zero is at most
one); **liveness** is violated (nothing is chosen despite no crashes and no
lost messages). The end state, verified:

```
  A[p=8 wrote=-]  B[p=8 wrote=-]  C[p=8 wrote=-]  D[p=0 wrote=-]  E[p=0 wrote=-]
```

Every `wrote=` field is empty. Eight rounds of perfectly healthy networking
produced not one written value.

**1.5** Quorum for 4 is 3, tolerating **1** failure — exactly the same as a
cluster of 3 (quorum 2, tolerates 1). A cluster of 5 has quorum 3 and tolerates
2. The fourth machine bought you **nothing**: no extra fault tolerance, one more
machine that can fail, one more to pay for, and a new failure mode where a 2–2
split leaves *neither* side able to form a quorum. This is why real deployments
are 3, 5 or 7.

**1.6** First set `{A:(4,"Zoo"), C:(2,"Museum"), D:(2,"Museum")}` → propose
**Zoo**. Second set `{A:(1,"Zoo"), C:(2,"Museum"), D:(2,"Museum")}` → propose
**Museum**.

The count is a trap and it is worth saying out loud: **the number of acceptors
reporting a value has nothing to do with the answer.** Both sets have two
reports of Museum and one of Zoo. The answer flips purely because `accepted_n`
moved from 4 to 1. The reason is that a higher `accepted_n` means a *later*
round got that value accepted somewhere, and that later round might have
completed as a majority at acceptors you did not talk to. Older news is
provably not the decision; newer news might be.

## Part 2 — Modify

**2.1** Smallest schedule: P1 gets `Zoo` chosen by `{A,B,C}` at `n=1`; P2 then
runs at `n=2` with `{C,D,E}`. C reports `(1,"Zoo")`, P2 ignores it, proposes
`Museum`, and C, D and E accept. Measured: `chosen = ["Zoo", "Museum"]`.

The invalidated sentence is the last step of the stage-11 argument: *"The
proposer is then forced by the phase-2 rule to propose `v`."* Quorum overlap
still happened — the overlapping acceptor still **reported** the chosen value —
but the proposer ignored the report. **Overlap only buys you safety if you act
on what it tells you.**

**2.2** With `quorum = 2` on five acceptors, `{A,B}` and `{D,E}` are both
"quorums" and they share nobody. Measured: `{A,B}` accept Zoo, `{D,E}` accept
Museum, `chosen = ["Zoo", "Museum"]`, neither group ever hearing of the other.

`N/2 + 1` is the smallest number that **forces intersection**: two sets each
larger than half of `N` cannot be disjoint, because their sizes would sum to
more than `N`. It is not a safety margin someone chose conservatively — it is
the exact threshold at which the pigeonhole argument starts working, and one
below it the argument does not weaken, it disappears.

**2.3** **Safety** breaks, and it is the same bug as 2.1 seen from the other
end. In 2.1 the proposer refuses to listen; in 2.3 the acceptor refuses to
speak. Safety needs the information to be both reported *and* acted on, so
breaking either half breaks the same guarantee.

It is arguably *worse* than 2.1 in practice, for two reasons. The proposer is
correct and cannot detect the problem — a promise reporting "nothing accepted"
is indistinguishable from a genuinely fresh acceptor. And a single misbehaving
acceptor is enough, whereas 2.1 requires the proposer implementation itself to
be wrong. This is also the closest thing in this lab to a Byzantine failure: an
acceptor that does not lie about *its promise* but does lie about *its history*.

**2.4** On a NACK, wait a random number of rounds (exponential backoff with
jitter is the usual shape) before issuing the next `Prepare`. The duel ends as
soon as one proposer happens to get through both phases before the other's next
prepare; with independent random delays that typically takes a handful of
rounds, and the probability of continuing decays geometrically.

It is *probabilistic*, not a guarantee, and FLP says it cannot be otherwise: in
an asynchronous system where even one process may crash, **no** deterministic
protocol guarantees termination. Backoff does not defeat the theorem; it makes
the bad case unlikely under network conditions that are usually benign. Your
[`../flp-impossibility/`](../flp-impossibility/README.md) lab is the sharp
version of this: its adversary defeats rotating-coordinator Paxos for 5,000
steps while delivering every single message, so "we added backoff" is not an
answer to it. The stronger practical fix is a leader, which removes the
contention rather than randomizing it — but electing a leader is itself subject
to FLP, so the impossibility is relocated, never removed.

**2.5** Achievable for 2 of 5, impossible for 3 of 5 — and "impossible" here is
not "hard", it is ruled out by the protocol. Once 3 of 5 have accepted the same
proposal number, every future quorum of 3 contains at least one of them, that
acceptor reports the value in its promise, and Rule A forces the new proposer to
re-propose it. The exact step at which the second attempt is forced to change
its mind is **the moment its third promise arrives** — that is when it applies
Rule A and discovers it is a courier, not an author. You will find you cannot
construct the schedule no matter how you order or drop messages, which is
exactly what the proof says.

**2.6** No, gossip does not let you drop phase 1, and the reason is the
interesting part. Gossip can tell an acceptor what other acceptors *have
already accepted*. It cannot tell it what is **in flight** — an `Accept(n,v)`
that has left a proposer and not yet arrived. A proposer that skipped phase 1
on the strength of gossip would be reasoning about a snapshot that a delayed
message can invalidate after the fact.

Phase 1 does not gather information *about the present*; it **changes the
future**, by making every acceptor in a majority refuse everything below `n`.
That is why it cannot be replaced by a read of any kind, however fresh. This is
the same shape as the optimistic-concurrency argument in
[`../optimistic-vs-pessimistic-locking/`](../optimistic-vs-pessimistic-locking/README.md):
checking a value and acting on it are only safe together if something prevents
the value changing in between.

## Part 3 — Trace

**3.1**

```
  start        A[p=0 -]      B[p=0 -]      C[p=0 -]      D[p=0 -]  E[p=0 -]
  step 1       A[p=1 -]      B[p=1 -]      C[p=1 -]      D[p=0 -]  E[p=0 -]
  step 2       (P1 has 3 promises, all reporting nothing -> Rule A: use own value)
  step 3       A[p=1 Zoo@1]  B[p=1 -]      C[p=1 -]      D[p=0 -]  E[p=0 -]
  step 4       A[p=1 Zoo@1]  B[p=1 -]      C[p=2 -]      D[p=2 -]  E[p=2 -]
  step 5       (P2 has 3 promises from C, D, E — all reporting nothing)
  step 6       value = MUSEUM
               A[p=1 Zoo@1]  B[p=1 -]      C[p=2 Museum@2]  D[p=2 Museum@2]  E[p=2 Museum@2]
               chosen = ["Museum"]
```

`Zoo` was **never** chosen. It reached exactly one acceptor. A is left holding
it forever, and that is correct rather than a leak — an acceptor's notebook
records what it was asked to write, not what was decided.

**3.2** At step 3, A, B and C all accept, so `Zoo` reaches 3 of 5 and is
**chosen** the instant the third acceptance lands. At step 4, C now reports
`(1,"Zoo")` in its promise to P2. At step 5, Rule A fires. At step 6 the value
is **Zoo**, and the final state is:

```
  A[p=1 Zoo@1]  B[p=1 Zoo@1]  C[p=2 Zoo@2]  D[p=2 Zoo@2]  E[p=2 Zoo@2]
  chosen = ["Zoo"]
```

**C** forced the difference. It is the only acceptor in both `{A,B,C}` and
`{C,D,E}`, and it could not have been avoided: any three of five must meet any
other three. Note also what happened to D and E — they had never heard of the
Zoo, they were recruited by the proposer who wanted the Museum, and they end up
holding the Zoo. The second round did not fail; it succeeded at carrying
somebody else's value.

**3.3** At step 3 all three acceptors **refuse** P1's `Accept(1,"Zoo")`, because
each has `promised_n = 2` and `1 >= 2` is false. Each replies NACK. Verified end
state:

```
  A[p=2 wrote=-]  B[p=2 wrote=-]  C[p=2 wrote=-]   chosen = []
```

P1's view of the world is stale: it holds a majority of promises that are no
longer worth anything, because a promise is a commitment to ignore older
numbers and someone newer has since arrived. What P1 should do is **abandon
number 1, pick a number higher than anything it has seen in the NACKs, and
start again from phase 1** — not retry the same `Accept`, which will be refused
forever.

There is no failure anywhere in this trace: no crash, no lost message, no
misbehaviour. This is the ordinary case that makes proposal numbers necessary,
and it is also exactly the mechanism that produces livelock when it happens
repeatedly. The same rule that makes a late message harmless is the rule that
can stop progress — which is why safety and liveness pull in opposite
directions here.

## Part 4 — Spot the flaw

**4.1** The bug is **replying before the state is durable**. It breaks the
durability obligation from stage 11 and, through it, the safety invariant: an
acceptor that acknowledges a promise or an acceptance and then reboots before
the write lands comes back believing it never voted. The quorum-overlap
argument then fails silently, because the overlapping acceptor no longer
remembers what it must report. Reproduced deliberately by **experiment 5**:

```
  A[p=2 Museum@2]  B[p=2 Museum@2]  C[p=2 Museum@2]  D[p=0 -]  E[p=0 -]
  chosen = ["Zoo", "Museum"]        <-- SAFETY VIOLATED
```

CI never caught it because the tests never crash a node *between* the reply and
the flush — the window is invisible unless the harness models durability
explicitly.

And "we ran a chaos test and it was green" is not a defence, which is the part
worth internalising. Stage 11 ran 500 randomised schedules with acceptors that
forget everything on restart — the bug present, deliberately — and found
**zero** safety violations, identical to the durable run. Breaking safety this
way needs *two* decisions in one run, and decisions are rare under packet loss;
random chaos almost never reaches the state where durability matters. The bug
is trivial to reproduce when aimed at (six deliberate steps) and nearly
invisible when not. A green soak is evidence about your soak's coverage, not
about your durability.

The fix is a synchronous flush before every reply. Yes, it costs a disk round
trip per message; that cost *is* the algorithm. Batching many proposals into
one flush is the legitimate optimization. Replying early is not.

**4.2** Measured counterexample:

```
  blind Accept(5, "Zoo")    to {A, B, C}  ->  chosen = ["Zoo"]
  blind Accept(9, "Museum") to {C, D, E}  ->  chosen = ["Zoo", "Museum"]
  C ends at [p=9 wrote=Museum@9]           (C accepted because 9 >= 5)
```

C accepts the Museum because 9 is not below its promise, and `{C,D,E}` is a
second legitimate majority. Two different values chosen, no faults, no
misbehaviour.

**Phase 1 is not for permission — it is for discovery.** The promise is almost
a side effect; the payload that matters is the acceptor telling the proposer
*what has already been accepted*. Skip phase 1 and a proposer chooses a value
while blind to the past, which is the broken "Attempt 3" from stage 4 wearing a
proposal number.

The subtlety worth carrying: **Multi-Paxos genuinely does skip phase 1** for
later slots — but only after a leader has run it once for that ballot, so the
discovery has already happened and the promise already covers those slots. It
is an amortization, not a removal. If you remember phase 1 as "permission" you
will not be able to tell the legitimate optimization from this broken one.

**4.3** The bug is waiting for **all** acceptors' promises instead of a
majority. Safety is untouched — a stricter requirement never chooses more — but
liveness now requires every single acceptor to be alive and reachable.

This is **"Attempt 2" from stage 4**: require all five to agree, and one absent
teacher stops the school. It is a realistic mistake because "wait for everyone"
*looks* safer, and it is: it is also useless, because the entire point of a
majority is that it is the largest quorum you can demand while still tolerating
failures. One crashed acceptor out of five now stalls the cluster forever, and
the failure is silent — no error, no NACK, just a proposer waiting.

**4.4** Smallest disagreeing promise set: `{A:(4,"Zoo"), C:(2,"Museum"),
D:(2,"Museum")}`. Most-common says Museum; highest-`n` says Zoo.

Full schedule where the wrong rule resurrects a dead value (measured):

```
  1. P1 at n=1 with {A,B,C}; Accept(1,"Zoo") reaches A ONLY   -> chosen = []
       (Zoo is on one acceptor: a minority, never chosen)
  2. P2 at n=2 with {C,D,E}; all three accept Museum          -> chosen = ["Museum"]
  3. A third attempt at n=3 asks {A,C,D} and sees
       A:(1,"Zoo")  C:(2,"Museum")  D:(2,"Museum")
     highest-n  -> Museum   (correct: Museum is the chosen value)
     most-common-> Zoo      (wrong)
  4. With the wrong rule: proposes Zoo, {A,C,D} accept        -> chosen = ["Museum", "Zoo"]
```

The value that was **never chosen** is written on top of the one that **was**.
Note the shape of the trap: the wrong rule agrees with the right one in the
overwhelming majority of real schedules, so a test suite that does not
deliberately construct this shape will pass.

**4.5** Yes, easily — for example:

```
  A[p=2 wrote=-]  B[p=1 wrote=-]  C[p=1 Zoo@1]  D[p=2 Museum@2]  E[p=2 Museum@2]
  chosen = []     safety holds
```

C holds Zoo, D and E hold Museum, and nothing is wrong. **Replicas disagreeing
is the normal, expected state of a Paxos cluster** — an acceptor's notebook is a
record of what it was asked to write, not a claim about what was decided. Only
a majority holding the same proposal number constitutes a decision.

It becomes a violation the moment **two different values each reach a majority
at some point in time** — 3 of 5 for Zoo and, at any later moment, 3 of 5 for
Museum. Note "at some point": it is not about the final state. Even if the
cluster later converges to a single value, if two different values were each
chosen at any instant, consensus was broken and something downstream may
already have acted on the wrong one.

## Part 5 — Handle a failure

**5.1** (a) Exactly three fields: `promised_n`, `accepted_n`, `accepted_value`.
(b) Flushed **before** the reply leaves — not after, not concurrently, not
"shortly". (c) On restart, the acceptor must reload all three and must not
answer any message until it has; an acceptor answering from a blank slate while
its disk read is still in flight is the same bug with a smaller window.

The write barrier matters more than the fields. Reply-then-flush means a crash
can land in the gap where the outside world holds a `Promise` or an `Accepted`
that the acceptor itself no longer remembers — experiment 5's amnesia, shrunk
from "reboot without a disk" to "reboot inside a two-millisecond window". With
flush-before-reply, a restarted acceptor rejoins knowing every promise it ever
made, and experiment 5's schedule replayed against it ends with **one**
decision: A, B and C come back still remembering the Zoo, and Rep 2's phase 1
meets a witness instead of a blank room. That control run is verified —
identical crash, durability on, `chosen = ["Zoo"]`.

Why neither a unit test nor a random soak reliably catches it: a unit test does
not model the crash window at all unless someone thought to write it, and the
random soak was measured in stage 11 finding zero violations with the bug
present. You catch this with an *aimed* fault — crash between reply and flush,
restart, run a second proposer, assert one value — which is the entire premise
of [`../jepsen/`](../jepsen/README.md).

**5.2** The retry must propose **the same request, carrying the same
client-chosen request id** — never a "fresh" attempt, because the first attempt
may already be chosen. The system may never assume the first attempt failed:
**no reply means unknown, not no.**

The only honest resolution is a **read**: consult the protocol (or a learner)
and see what was actually chosen. If it is your request id, your write
happened, the retry is a duplicate, and idempotency absorbs it. If a different
value won, your write lost and the client must be told the truth rather than
have the system quietly pretend.

Note what is doing the work: Paxos guarantees one value is chosen; it says
nothing about *whose* value. Making the retry safe is the request id — that is
[`../idempotency/`](../idempotency/README.md)'s job, not consensus's. A great
many systems get consensus right and then lose money on this exact seam.

**5.3** The surviving two can **read** whatever they already hold, and they can
tell you honestly that they do not know the current state. They cannot decide
anything — no quorum can form — and they must not pretend otherwise. Liveness
is gone; safety is intact. The cluster is doing precisely what it was built to
do.

"Just lower the quorum to 2 so we can keep serving" is the most dangerous
sentence in the incident because it retroactively breaks every decision the
cluster has ever made. The three lost acceptors may hold a chosen value that
neither survivor knows about; a quorum of 2 among the survivors can decide a
different value for the same slot, and now two different answers were each
chosen. Stage 12's first counterfactual is exactly this, measured:
`chosen = ["Zoo", "Museum"]`. Worse, it fails *silently* — the cluster comes
back up and serves traffic, and the divergence surfaces days later in
somebody's reconciliation report.

The actual recovery is **reconfiguration**: bring machines back with their disks
intact if at all possible (their state is the point), and if the disks are
genuinely gone, perform a deliberate, human-authorised membership change that
is recorded as a decision, accepting explicitly that any decision made by the
lost majority and unknown to the survivors is lost with it. The cost is
downtime and a written acknowledgement of possible data loss. That is the
honest price, and it is much lower than the price of a silent second decision.

## Part 6 — Multi-Paxos

**6.1** The log has slot 11 chosen and slot 10 undecided — a **hole**. The state
machine may apply **nothing at or past slot 10**, because a state machine must
apply commands in order; slot 11's command sits decided but unapplied. The
leader must retry slot 10 — it still holds phase-1 rights at `n=5`, so it can
resend `Accept(5, cmdA)` without redoing phase 1.

This is the everyday case, not an error. Pipelining slots is *why* Multi-Paxos
permits holes, and a lost message costs a retransmission rather than a stall of
everything behind it.

**6.2** Before accepting a new command at slot 12, the new leader must run
**phase 1 at `n=6` over the tail of the log** — every slot from its last known
applied position onward. For each slot the promises tell it one of three things:
a value was accepted (it must re-propose that value, Rule A, per slot); nothing
was accepted (the slot is free); or the slot is already known-decided.

It proposes a **no-op** into any hole rather than skipping it because slot
numbers are the ordering, and skipping one would mean the replicas disagree
about what position 10 contains — one replica applying 9, 11, 12 and another
applying 9, 10, 11, 12 are no longer the same state machine. A no-op is a
command that changes nothing but occupies the slot, letting the log move past
it with every replica agreeing on what happened there. This is the same
mechanism as the no-op a new Raft leader appends on winning an election.

**6.3** Multi-Paxos permits holes: it buys **out-of-order pipelining** — a
leader can have many slots in flight and one slow slot does not block the
others — and it costs a much harder recovery path, since a new leader must run
phase 1 across a range of slots and reason about each independently. Raft
forbids holes: it buys a **contiguous log**, so a single `(index, term)`
comparison certifies unlimited history by induction and log repair is one
truncation, and it costs head-of-line blocking, since a follower missing entry
10 cannot usefully receive 11.

For a configuration store at a few writes per second, take **Raft's
restriction**. You have no throughput problem for pipelining to solve, and the
thing you will actually spend your life on is understanding the system at 3am
during a failover. Trade throughput you do not need for recovery you will
definitely use. (The honest counter-argument: if you are running many
replication groups per machine, per-group simplicity matters less than
aggregate throughput — which is why high-write systems still reach for
Paxos-family designs.)

**6.4** Stage 11 measured the same soak twice: duelling proposers decided 36 of
500 runs, a single non-overlapping leader decided 220 of 500, and **safety
violations were zero in both**. The leader changed how often the system got
anything done and did not touch what it was allowed to do. It is a throughput
mechanism.

So if your leader-election logic is buggy and produces two leaders at once, the
precise consequence is that **the two leaders duel**. Each invalidates the
other's promises, both keep bumping proposal numbers, throughput collapses
toward zero, and the log stops advancing. Nothing is decided twice; nothing
wrong is committed. You will see it as a latency and availability incident —
rising NACK rates, leadership flapping in your metrics — not as data
corruption. That is exactly the shape your
[`../split-brain/`](../split-brain/README.md) lab argues for: the goal is never
to prevent two leaders, it is to make the loser harmless.

## Part 7 — Design

**7.1** A worked answer to compare against; yours may differ defensibly.

- **(a) Acceptors: 5, spread 2 + 2 + 1.** Losing any one datacentre leaves at
  least 3 — a quorum — alive. Three acceptors (1+1+1) also survives a DC loss,
  but then a single *machine* failure in either surviving DC stalls you; 5 buys
  real headroom. Even counts buy nothing (stage 10's odd-size point). Nine
  (3+3+3, quorum 5) is defensible if you can afford the extra cross-DC latency
  per decision.
- **(b) One stable leader.** Duelling proposers at 50 ms per round is
  experiment 4 with a latency bill attached: every gazump costs a cross-DC round
  trip. Place the leader in the DC nearest the write-heavy clients. Leader
  election is itself a Paxos decision, and the lease it holds is what kills the
  duel.
- **(c) Steady state: roughly one cross-DC round trip, ~50–100 ms** per
  decision — the leader already holds phase-1 rights and needs only phase 2 to
  a quorum, which must include at least one remote DC. Failover: detection
  timeout plus a full two-phase round for the new leader to re-prepare across
  the log tail, so a few hundred milliseconds to seconds, tunable by how
  twitchy you make detection. Too twitchy means spurious leader flapping, which
  is the duel again.
- **(d) Dashboard**: (1) **leader identity over time** — flapping is the system
  showing you experiment 4; (2) **commit latency percentiles** — a creeping p99
  means a slow quorum member; (3) **stalled log slots / holes not yet filled** —
  the thing that blocks the state machine while everything else looks healthy.
  The one **alert**: leader elections per minute above a threshold. It fires
  before users notice and it catches nearly every real problem, because both
  network trouble and machine trouble show up as election churn first.
- **(e) Survives unchanged from this lab**: acceptor state and its
  flush-before-reply rule, Rule A and Rule B, quorum intersection as the entire
  safety argument. **Added for production**: the log of instances
  (Multi-Paxos), leader election and leases, snapshots and compaction,
  membership change, learners and catch-up for lagging replicas, and client
  request ids (your 5.2 answer). That is stage 14's omissions list, and every
  item on it is real work.

**7.2** The comparison that matters:

| | Basic Paxos per seat (LWT-style) | One Raft group, seats in the state machine |
|---|---|---|
| Cost per operation | Two round trips *plus* a read-modify-write; roughly 4× a plain write | One round trip through the leader |
| Blast radius of one slow node | Contained — it only affects quorums for the seats it holds | The leader being slow degrades **everything** |
| At 10× the seats | Scales naturally; each key is independent, no coordination between them | The single group becomes the bottleneck; you must shard into many groups |
| What on-call sees when it breaks | Elevated latency and contention on *specific hot keys* | Leader elections, and one incident that covers all seats at once |

Ship the **Raft group** for a bounded seat inventory — one event, one venue,
tens of thousands of seats. You get one round trip per reservation, one thing
to operate, and the state machine can enforce invariants across seats (no
double-booking, hold limits per customer) that per-key consensus simply cannot
express. Per-key Paxos gives you consensus on each key and no way to say
anything true about two keys at once.

What would change my mind: **contention shape**. If reservations are spread
across a very large key space with almost no cross-key invariants — millions of
independent items, each contended by a few clients — the per-key design's
independence becomes the dominant factor and the single leader becomes the
liability. That is precisely the trade Cassandra's lightweight transactions
make, and it is why they exist despite costing 4×.

**7.3** There is no single answer; the point is that the scenario must be
*small*, *concrete*, and end in something a customer notices. A good answer
names five acceptors, real proposal numbers, an explicit message order, and
finishes with two decisions or a permanent stall — not with "and then it could
get inconsistent".

The four safety-breaking counterfactuals in stage 12 are the calibration set:
quorum below a majority (two disjoint deciders), Rule A deleted (the witness is
ignored), Rule A inverted (a dead value resurrected), and phase 1 skipped
(blind accepts). If your scenario needed more than about six messages, there is
probably a smaller version of it — and finding the smaller version is the
exercise. If it needed *fewer*, you may have skipped a step that the protocol
would actually have caught; walk it again message by message against the
acceptor rules in stage 9.
