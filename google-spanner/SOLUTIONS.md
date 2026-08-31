# Google Spanner — solutions

Every number quoted here was produced by the simulator and checked before this file
was written. Where a number is from the 2012 paper instead, it says so.

---

## Rung 1 — Explain

**1.1** Something like this:

> A database that lives on machines in several countries still has to look, to your
> code, like one machine doing one thing at a time. The usual way to make that
> illusion is to put a time on every change and sort by it. But the machines'
> clocks disagree by a few milliseconds, so a change that genuinely happened second
> can get an earlier time than one that happened first — and then a query returns a
> version of history that never occurred. You cannot fix this by synchronising the
> clocks better, because *better* is not *exact*, and the machine has no idea how
> far off it currently is. So instead of asking the clock for a moment, you ask it
> for a range it is willing to guarantee the moment lies inside — and then you know
> exactly how much caution to buy.

**1.2** *"The atomic clocks are not there to make the timestamps right — nothing can
do that. They are there to make the clocks' **admitted uncertainty** small. Spanner's
correctness comes from a rule that says: stamp with the most pessimistic reading, then
wait until your own clock proves that moment has passed before telling anyone. The
hardware just makes that wait about four milliseconds instead of hundreds."*

**1.3**

| Who pays | Mechanism | Consequence |
|---|---|---|
| The writer | Spanner's commit wait, ≈ 2ε on every acknowledgement | reads need no locks, no retries, no token, no leader round trip |
| The reader | CockroachDB's uncertainty restarts — a read whose snapshot lands in the window retries | works on commodity clocks with ε ≈ 500 ms, at the cost of an unpredictable retry rate |
| The client | MongoDB's signed `clusterTime` carried between operations | free for the database; only helps sessions that carry the token, and gives strangers nothing |

Spanner's choice makes reads cheap because a reader arriving at *any* replica already
knows that everything acknowledged before it started carries a smaller timestamp. It
therefore needs to do nothing but pick a timestamp and read versions below it.

**1.4** Nothing. That is the literal answer and it is the point. It is not waiting for
a message, a disk, or a quorum — all of that has already finished. It is holding its
locks and holding back the acknowledgement until its own clock's pessimistic *lower*
edge has passed the timestamp it already chose.

---

## Rung 2 — Identify

**2.1** At real time 108 in scenario 2: Northgate's band is `[101, 115]`, Southgate's
is `[107, 109]`, Singapore's is `[104, 112]`. **All three contain 108** — that is
TrueTime's entire promise, and every correctness argument in the lab rests on it.
Northgate's `TT.now().latest` is **115**. G1's `s_max` is **107**, which is larger
than any moment that has yet occurred because it was taken from the *pessimistic* end
of a 14 ms-wide band eight milliseconds ago. A leader routinely holds an `s_max` in
the future; that is exactly why it must wait before abdicating.

**2.2** T1 arrives at 100, Paxos finishes at **102**, and the acknowledgement is at
**114** — so the shaded block runs 102 → 114, twelve milliseconds. It does not begin
at 100 because the wait *did* begin at 100; for its first two milliseconds it was
hidden underneath the Paxos round trip that had to happen anyway. Only the part that
outlasts replication costs anything, which is why the paper's measured figure is about
5 ms rather than 2ε̄ ≈ 8 ms.

**2.3** `t_safe_TM` is the smaller. It sits at **103.999**, one tick below **104**,
which is the prepare timestamp Southgate's leader chose when it logged its prepare
record at real time 100 — taken, like every other timestamp, from `TT.now().latest`.
`t_safe_paxos` meanwhile is up around real time minus 4, far above it. The group is
readable only up to 103.999 until the coordinator decides.

**2.4** `s_read` = **115**. When it arrives at 111 the Singapore replica has applied
**nothing at all** — T1 committed at real time 108 and, with 45 ms of lag, does not
reach Singapore until 153. The read answers at **164**, having waited **53 ms**, and
it returns T1. It never returned a stale answer; it converted staleness into latency.

**2.5** It joins the **acknowledgement** (the right-hand end of the bar, a real-time
event) to the **commit timestamp** (the diamond, a number the system assigned). Its
length is the distance between when a transaction actually finished and where the
database says it sits in history. When that dotted line points *left*, the timestamp
is in the past and everything is fine; when it points *right*, the stamp is still in
the future for somebody, and that is precisely the window commit wait exists to close.

---

## Rung 3 — Predict

**3.1** With ε(Northgate) = 20: `s₁` = **120**, T1's acknowledgement moves to
**140**, of which **38 ms** is commit wait beyond replication. T2 then gets
`s₂` = **143** and the verdict stays **holding** — *zero* violations. This is the
lesson: ε does not appear anywhere in the correctness argument. It appears only in the
bill. Note also that the timeline stretches to fit; a twenty-millisecond ε makes the
whole story roughly four times longer.

**3.2** The read reports the mortgage without the sale at exactly
**snapTs = 105 and 106** — the half-open interval `[s₂, s₁)` = `[105, 107)`. Below
105 it sees neither document; at 107 and above it sees both, in the wrong order but
without a visible tear. Two integer values out of a 45 ms window is why this bug does
not reproduce in your test environment.

**3.3** Raising ε to 6 while leaving the offset at +6 makes the clock **honest**
(`|6| ≤ 6`), not accurate — it is still running six milliseconds fast. `s₁` becomes
**112**, the acknowledgement moves to **112**, `s₂` becomes **115**, and the verdict
returns to holding. TrueTime never asked the clock to be right. It asked the clock to
be *right about how wrong it is*.

**3.4** Both changes give the same answer: the read answers at **119**, having waited
**8 ms**, down from 53. That is the surprise worth sitting with. Removing the lag, or
going to the leader, removes the *replication* component of the wait — but a strong
read still asks for "now", "now" is `TT.now().latest`, and that is ε ahead of any
write that has actually happened. The residual 8 ms is the price of the question, not
of the topology. It is exactly why §10.7's `LastTS()` shortcut exists: for a
single-group read the leader can answer with the timestamp of its *last commit*
instead, which is already safe and never waits.

**3.5** The lease expires at **151** (killed at 111, 40 ms compressed lease), so the
earliest the write can be stamped is **151**; it receives `s` = **155** and is
acknowledged at **159**, from a new leader in Southgate. The invariant forcing it is
**disjointness**: no two leaders of a group may hold overlapping lease intervals, and
a leader may only assign timestamps inside its own lease. Everyone can see the old
leader is dead; the rule does not care, because "everyone can see" is not something a
distributed system can act on.

---

## Rung 4 — Modify

**4.1** The violation disappears — at ε = 7/7, 3/3 and 12/12 the simulator reports
**zero** ordering violations with commit wait off. The condition derived in 5.2 says
why: inversion needs `g < ε₁ − ε₂ − P`, and equal ε makes the right-hand side
negative.

It is not a fix, for two reasons. First, **ε is not a setting**. It is a *report*: how
long since this machine last polled a time master, plus how fast its oscillator is
allowed to drift, plus the network delay to the master. In production it is a sawtooth
climbing from about 1 ms to about 7 ms over each 30-second poll interval, and every
machine is at a different point on its own sawtooth at every instant. Second, even if
you could force them equal, doing so would require synchronising the machines — which
is the original problem. Safety here was restored by coincidence, and a guarantee that
holds by coincidence is the most dangerous kind, because it is indistinguishable from
a correct one until the weather changes. Your
[linearizability](../linearizability/) and
[hybrid-logical-clocks](../hybrid-logical-clocks/) labs both measured that same trap.

**4.2** On the fixed cadence the read in scenario 7 answers at **200** instead of
**164** — a wait of 89 ms rather than 53. Nothing incorrect happens; the answer is
identical. The loss is entirely to **liveness**. In production this is the 8-second
`MinNextTS` cadence, and it is why a strong read against a Paxos group that nobody has
written to for a while can sit there for seconds: `t_safe_paxos` is *the timestamp of
the highest applied write*, and an idle group has no new writes to raise it.

**4.3** Line one — `s₁ < t_abs(commit₁)` — is exactly the line commit wait supplies,
and `TT.after(TT.now().earliest)` becomes true essentially immediately, so no waiting
occurs and the line fails. The run it permits is scenario 1 verbatim: `s₁` = 107,
acknowledged at 102, `s₂` = 105.

**4.4** Choosing `s = TT.now().earliest` breaks a different line — line four,
`t_abs(server₂) ≤ s₂`, which is what guarantees a transaction's stamp is not in its
own past. Concretely, with ε = 7 at Northgate and ε = 1 at Southgate:

- T₀ reaches Southgate's leader at 94.000. `TT.now() = [93, 95]`, so
  `s₀ = earliest = 93`. It is acknowledged at 96.
- T₁ reaches Northgate's leader at 100.000, well after T₀ finished.
  `TT.now() = [93, 107]`, so `s₁ = earliest = 93` — **the same stamp as a transaction
  that finished four milliseconds ago**, and below anything Southgate assigns next.

Commit wait cannot save this, because commit wait only proves the stamp is in the
*past*; a stamp that is too far in the past satisfies it trivially. Both rules are
needed, and they defend different lines.

---

## Rung 5 — Trace

**5.1** ε(NG) = 5, ε(SG) = 2, Paxos = 3, commit wait on.

| Real time | Step | Result |
|---|---|---|
| 100.000 | T₁ reaches Northgate's leader; asks the clock | `TT.now() = [95, 105]` → **s₁ = 105** |
| 100 → 103 | Paxos replicates the commit record | applied at 103 |
| 100 → 110 | Commit wait: `earliest = t − 5 > 105` first holds at 110 | 10 ms = 2ε, 3 of them hidden by Paxos |
| 110.000 | The client is told | **ack₁ = 110** |
| 111.000 | The client telephones | — |
| 112.000 | T₂ reaches Southgate's leader | `TT.now() = [110, 114]` → **s₂ = 114** |

`s₁ = 105 < s₂ = 114`. External consistency **holds**, with 7 ms of visible commit
wait as the price. The simulator reproduces every number.

**5.2** Let `a` be T₁'s arrival at its leader, `P` the Paxos latency, `ε₁` and `ε₂`
the two leaders' error bounds, and `g` the gap between T₁'s acknowledgement and T₂'s
arrival. With commit wait off:

```
  s₁ = a + ε₁
  ack₁ = a + P
  T₂ arrives at a + P + g   →   s₂ = a + P + g + ε₂

  inversion  ⇔  s₂ < s₁  ⇔  P + g + ε₂ < ε₁  ⇔  g < ε₁ − ε₂ − P
```

Applied to scenario 1: `g < 7 − 1 − 2 = 4`. The actual gap is 2, so the ordering
inverts — which is what the simulator shows.

Three things fall out of that one inequality, and they are the reason this bug has the
reputation it has. **(i)** Only the *difference* in the two admissions matters, not
their size — two machines both admitting 20 ms are safer than one admitting 7 and one
admitting 1. **(ii)** Paxos latency subtracts from the window, so making replication
*faster* makes this bug *more* likely. **(iii)** The window is a handful of
milliseconds wide, so the transactions have to be close together in time to hit it —
which happens constantly at ten thousand operations per second and never in a test.

**5.3**
(a) `t_safe_paxos` = **180** (the highest applied write). `t_safe_TM` = **149.999**
(one tick below the single prepare timestamp). `t_safe = min(...)` = **149.999**.
(b) Reads at **130** and **149** proceed. Reads at **160** and **179** block — even
though the writes at 140 and 180 are applied and sitting right there, and even if the
read touches keys the prepared transaction never mentions. Until the coordinator
decides, that transaction could still commit anywhere at or above 150.
(c) After it commits at 190: `t_safe_paxos` = **190**, `t_safe_TM` = **∞**,
`t_safe` = **190**. Both blocked reads unblock immediately, and now see the
transaction if their timestamp is at or above 190.

The refinement that makes this survivable in production is fine-grained safe time:
prepared timestamps are kept per key range in the lock table, and a read is checked
only against the ranges it actually touches.

**5.4**
(a) **1100** — the moment L1's lease interval ends. Not 1050, when L1 died; death is
not an event the invariant is defined over.
(b) **1100.** Two constraints bind: a leader may only assign timestamps inside its own
lease, and monotonicity requires beating `s_max = 1098`. The lease constraint is the
tighter one, and it implies the other — which is the elegance of the design.
(c) If L2 could start at 1051 and assign 1055, the group's history reads:

```
  write A   timestamp 1098   committed at real time ≈ 1049
  write B   timestamp 1055   committed at real time ≈ 1051   ← later, smaller stamp
```

A snapshot read at 1056 then returns **B and not A**, although A finished before B
began. The invariant broken is **monotonicity within a Paxos group**, and with it
external consistency. Note that this needs no partition and no split brain in the
usual sense — it is caused purely by timestamps from two eras overlapping.

---

## Rung 6 — Spot the flaw

**6.1 "Skip commit wait for single-group transactions."**

The claim is half right, and the half that is right is what makes it dangerous. Within
one Paxos group, monotonicity *does* hold without commit wait: the leader forces every
new timestamp above `s_max`, so two transactions on the same group can never invert.
Test it that way and it passes.

It fails **across** groups, which is the case the proposal never mentions:

- T₁ on G1 (Northgate, ε = 7) arrives at 100.000, gets `s₁ = 107`, and — commit wait
  skipped — is acknowledged at 102.
- The client telephones. T₂ on G2 (Southgate, ε = 1) arrives at 104.000 and gets
  `s₂ = 105`.

`s₂ < s₁`, and a snapshot read at 106 sees the second without the first. The violated
guarantee is **external consistency**.

The general lesson is worth keeping: *monotonicity is what orders transactions inside
a group; commit wait is what orders them across groups.* They are not alternatives,
and since groups never communicate, the second cannot be delegated to the first.

**6.2 "A replica may serve a read at `t` once it has applied everything up to `t`."**

- G1 has applied everything up to 204. A transaction is prepared at prepare
  timestamp 200, undecided.
- A read arrives at timestamp **203**. Ignoring prepared transactions, it answers now,
  reporting the state *without* that transaction.
- The coordinator then commits it at **202** — legal, since 202 ≥ 200.
- A second, identical read at timestamp 203 now returns the state *with* it.

Two reads at the same timestamp returned different histories. The violated guarantee
is the immutability of a snapshot — the property that makes consistent backups,
consistent analytics and repeatable reads possible at all. And note the shape of the
failure: nothing was lost, nothing was late, and neither read was individually
detectable as wrong.

**6.3 "A read-only transaction can use its own machine's wall clock."**

Commit wait guarantees the commit timestamp is in the past *of true time*. It says
nothing about any particular machine's *reading* of the time. A reader whose clock
runs slow picks a read timestamp below the commit timestamp of a transaction that was
acknowledged before the read even began, and misses it — which is precisely the
86-out-of-86 stale reads your [hybrid-logical-clocks](../hybrid-logical-clocks/) lab
measured. Concretely: T₁ acknowledged at real 114 with `s₁ = 107`; a reader whose
clock is 5 ms slow starts at 116 and picks 111; 111 < 107 is false, so it happens to
see it — but at ε = 7 on the writer and −5 on the reader, a `s₁ = 121` case misses
cleanly.

The fix is not exotic: the read timestamp must come from `TT.now().latest` too, or —
better, for a single-group read — from the group leader's `LastTS()`. Both are
statements the *system* is willing to stand behind. A bare wall clock is not.

**6.4 "Leases exist because of partitions; inside one datacenter a new leader can take
over immediately."**

No partition is required. A stop-the-world garbage collection pause, a stalled disk,
or a descheduled VM is enough, and all three are ordinary events inside a single
datacenter.

- L1 holds a lease to 1100 and has assigned `s_max = 1098`.
- At 1050 L1 pauses. The others see silence.
- Under the proposal, L2 takes over at 1051 and assigns 1055 to a new write.
- At 1054 L1 wakes, still believing — correctly, by its own lease — that it may assign
  timestamps up to 1100.

The overlap is the bug, and it exists whether or not L1's writes still reach a quorum,
because the damage is to the *timestamp ranges*, not to the log. This is your
[split-brain](../split-brain/) lab's conclusion in its exact form: you cannot stop a
machine believing it is in charge, you can only arrange that its effects do not count
— and here the effect that must not count is a stamp from the next leader's era.

**6.5 "Use the midpoint; it halves the wait."**

It does halve the wait, and it breaks line four of the proof —
`t_abs(server₂) ≤ s₂`. The midpoint is the clock's *reading*, which for a slow clock
is in the past of the true instant, so a transaction can receive a stamp earlier than
the moment it actually started.

- Northgate: offset +5, ε = 7. T₁ arrives at 100 → midpoint `s₁ = 105`.
  Commit wait until `earliest > 105`, i.e. `t + 5 − 7 > 105` → 107. Acknowledged 107.
- Southgate: offset −6, ε = 7 (honest: |−6| ≤ 7). T₂ arrives at 109 → midpoint
  `s₂ = 103`.

`s₂ = 103 < s₁ = 105`, and T₂ started two milliseconds after T₁ finished. External
consistency violated, with commit wait fully enabled and both clocks honest.

The moral: `latest` is not a conservative flourish that can be tuned. It is the second
of the two rules, and it is what makes a stamp an *upper* bound on the moment the
transaction began.

---

## Rung 7 — Handle failure

**7.1 The lost acknowledgement.**

**(a)** Spanner guarantees the transaction either committed entirely or not at all, and
that the outcome is durable and knowable — the decision was logged through Paxos, so a
coordinator crash does not lose it. What Spanner does *not* give you is any notion of
"this is the same request as the one before". The retry is a new transaction.

**(b)** A read-modify-write is naturally harmless: the retried transaction re-reads
current state and recomputes, so applying it twice is the same as applying it once.
`UPDATE Accounts SET Balance = Balance − 50` is *not* in that class if it is issued
blind; `SET Balance = <value computed from a fresh read>` is. The unsafe shapes are
blind deltas, and inserts with a server-generated key — the retry cheerfully creates a
second row.

**(c)** Add an **idempotency key**, and — this is the part people get wrong — write it
**inside the same transaction as the effect**. A unique row keyed by the client's
request identifier, inserted alongside the balance update, means the retry's insert
collides and the transaction can detect that its own earlier attempt already
succeeded. Put it in a separate transaction and you have reintroduced the two-writes
problem the database was solving for you. This is your
[idempotency](../idempotency/) lab's rule, and the reason your
[transactional-outbox](../transactional-outbox/) lab insists on the same
single-transaction discipline.

**7.2 The dashboard.**

| Row | Metric | Shape that identifies it |
|---|---|---|
| Clock | TrueTime ε, 99th percentile per zone | a step up across a whole zone at once, with CPU, disk and network flat — and write latency rising in step with it |
| Key space | Key Visualizer heat over the primary key range | a single bright band, usually marching steadily downward as a timestamp-prefixed key advances |
| Transactions | count of prepared-but-undecided transactions, and lock-wait time by key range | **read** latency rising while write latency is normal — the confusing one |

The tell that separates them in a glance: an ε incident moves **writes** everywhere; a
hot split moves **one key range** and leaves CPU pinned on a fraction of your nodes
while the rest idle; a stuck prepared transaction moves **reads**.

The one that is not a Spanner problem is the hot split. Spanner is doing exactly what
it was asked to do; the primary key is wrong. It is also, by a distance, the most
common real incident — which is worth saying out loud, because it means most Spanner
performance work is schema work.

**7.3 Strong reads in a read-only region.**

Read-only replicas hold full data and serve reads but do not vote, so they are never
leaders. `t_safe` is computable locally — `t_safe_paxos` from the writes they have
applied, `t_safe_TM` from prepare metadata carried on the log — so the *serving* half
is fine.

The problem is the other half: **choosing the timestamp**. `s_read = TT.now().latest`
can be produced locally, but then the read must block until `t_safe` reaches it, and
`t_safe` in a distant read-only region trails by the wide-area replication delay. The
cheaper choice, `LastTS()`, is only known at the **leader** — so a genuinely strong,
non-blocking read from that region costs a round trip to the leader region and is
bounded below by that latency, exactly as if the replica were not there.

What to offer the application instead: **bounded-stale reads**. Name a staleness
window, and Spanner picks the newest timestamp inside it that the local replica can
serve without blocking — no leader contact, no wait. Google's guidance is that a
window under about 10 seconds does not buy you anything, because it is not comfortably
larger than the replication delay you are trying to hide. So the honest offer to the
product team is: *"strong reads cost you a transatlantic round trip; ten-second-stale
reads are local and free; which does this screen actually need?"* — and for most
screens, the answer is the second.

---

## Rung 8 — Design

These are arguments, not answers. What follows is one defensible version of each.

**8.1 The ledger.**

*Keys.* `Accounts` keyed by `(AccountId)` with `AccountId` a UUIDv4 — never a
sequence, never a timestamp. `Entries` (the immutable double-entry lines) keyed by
`(AccountId, EntryId)` and **interleaved in `Accounts`**, so an account and its recent
entries live in one split and one Paxos group.

*Why interleaving is the load-bearing decision.* The hard rule — never show a debit
without its credit — is a cross-account invariant, and the two accounts in a transfer
are on different splits. That is a two-participant transaction, which the paper's
Table 4 prices at about 24.5 ms mean versus 17.0 ms for one participant. Acceptable.
What is *not* acceptable is a design where a transfer touches fifty splits because
balances, entries and audit rows were all keyed independently. Interleaving is how you
keep the participant count at two.

*Instance configuration.* Multi-region, for the 99.999% SLA and regional-failure
survival, with the **leader region placed where the writes originate** — not where the
company is headquartered. Writes always go to the leader first even when a closer
replica exists, so leader placement is the single biggest latency lever you have. Add
read-only replicas in the other two regions: they scale reads without enlarging the
write quorum.

*Read paths.* Strong read-only transactions for anything in the money path — a balance
check before authorising, a post-transfer confirmation screen. Bounded-stale reads
(15–30 s) for statements, dashboards, exports and analytics; these are the bulk of
traffic by volume and none of it by risk, and moving them off the leader is what keeps
the leader region under its CPU target.

*Monitor.* (1) CPU against the 45% multi-region target, because that headroom *is* the
failover plan. (2) Transaction abort rate — rising aborts with flat CPU means
contention, which means the schema, which means a hot account. (3) Key Visualizer, for
the hot split you will eventually introduce.

*What to give up, deliberately.* Cross-account uniqueness constraints enforced in the
database for anything other than the account key itself, and any "global counter" of
any kind. Both are single-key hotspots wearing a business-logic costume.

*What survives from this lab, and what does not.* All of the mechanism survives — you
get external consistency without doing anything. What does not survive is the
simplification that transactions are cheap: at 20,000 writes/second across an 80 ms
triangle, the Paxos quorum latency dominates ε entirely, and commit wait's ~5 ms is
noise. **The lab's central cost is not your central cost**, and knowing which one
dominates your workload is the actual skill.

**8.2 No atomic clocks, ε ≈ 250 ms.**

*Writer pays (Spanner's choice).* Every write acknowledgement gains roughly 500 ms.
For a 95%-read workload that is 5% of requests becoming unusably slow, and locks are
held throughout, so the remaining 95% queue behind them. **Rejected** — not because it
is incorrect, but because the constant is 60× wrong.

*Reader pays (CockroachDB's choice).* Reads whose snapshot lands inside the 250 ms
uncertainty window restart. The rate depends on how often a read lands within 250 ms
of a write to the same keys — low for cold data, brutal for a hot row. Cost is
statistical rather than fixed, which is harder to reason about but far cheaper on
average for a read-heavy workload. **Recommended**, with the caveat that you must
monitor the restart rate as a first-class metric and treat a rising one as a schema
problem.

*Client pays (MongoDB's choice).* Cheapest of all and the weakest: it only orders
operations for clients that carry the token. Your 95% reads include analytics jobs,
other services and support tools that have no session. **Rejected** as the primary
mechanism; worth adding on top, since it is nearly free.

*The thing to say out loud:* all three pay the same ε. The engineering question is
never "how do I avoid the cost", it is "which of my three populations can absorb it",
and for a read-heavy workload the answer is the writers — except that Spanner's design
is the one that *doesn't* charge readers, so the recommendation inverts precisely
because ε is large.

**8.3 The counter-proposal.**

Concede the strong point first, because it is strong: **Spanner is expensive, and most
data does not need external consistency.** A product catalogue, a session store, a
notification feed, an audit log — none of these are worth a globally distributed
transactional database, and putting them there is a real and common waste.

The question that decides it: **"Which invariants must never be *seen* broken, by
whom, and on how many devices?"**

Not "must never *be* broken" — a saga gets you that eventually, with compensations.
The word doing the work is *seen*. If the answer is "a customer must never see a debit
without its credit, on any device, at any instant", then you need atomic visibility
across shards, which your [distributed-transactions](../distributed-transactions/) lab
measured a well-built 2PC-plus-dual-write failing at **16 fractured audits in 40 runs
with zero crashes**. A saga does not fix that; a saga *chooses* it and adds
compensations. If the answer is "eventually consistent is fine, we reconcile
overnight", then they are right and you should agree quickly.

The honest middle: split the estate. Ledger and balances on Spanner; everything else
on cheaper storage. The decision ladder from your
[distributed-transactions](../distributed-transactions/) lab still holds — one
database > co-locate the keys on one shard > outbox > saga > distributed transaction —
and Spanner is the last rung, reserved for invariants that must never be seen broken.

**8.4 The uncomfortable paragraph.**

> Spanner's external-consistency guarantee is conditional. It holds as long as every
> machine's clock stays inside the error bound that machine publishes — in practice,
> as long as local oscillators drift no faster than 200 µs/s. This is not a
> theoretical caveat that software can close: if a clock's interval stops containing
> the true instant, commit wait waits for the wrong number and the ordering guarantee
> fails silently, with no error, no exception and no log line. Google's defences are
> real and layered — time masters cross-check and evict themselves, daemons poll
> several independent sources and discard outliers, and machines whose measured
> frequency excursion exceeds spec remove themselves from the fleet — and the
> published justification is empirical rather than formal: bad CPUs are roughly six
> times more likely than bad clocks, so TrueTime is about as trustworthy as anything
> else we already depend on. That is a reasonable basis for a decision. It is not a
> proof, and we should not write it down as one.

*The operational control to insist on:* an alert on **TrueTime ε** itself, treated as
a correctness signal and not a performance one. Every other layer of the defence lives
inside Google's infrastructure and is invisible to you; ε is the one number the
platform exposes that moves when the assumption is under strain. A latency incident
with no CPU, no disk and no network anomaly, but a raised ε, is a time incident — and
knowing that in advance is the difference between a twenty-minute diagnosis and a
four-hour one.
