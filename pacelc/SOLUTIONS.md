# PACELC — Solutions

Rungs 7–8 are worked design discussions, not single correct answers.

---

## Rung 1 — Explain

**1.1** CAP only speaks about the moment the network stops delivering
messages: then you must pick between refusing to answer and answering
possibly-wrong. PACELC adds: *and when the network is healthy — which is
almost always — do you make requests wait for cross-replica coordination, or
answer immediately and accept that some answers are stale?* It mattered
because teams justified everyday staleness (a latency choice they were
making) with a partition argument (a constraint that wasn't operating),
which made the real tradeoff untunable and undiscussable.

**1.2** "CAP constrains us only while a partition is happening, and we
haven't had one; our reads are stale because we chose not to make writers or
readers wait for replication — a latency-versus-consistency choice. That
choice may well be right, but it's ours, it has a measurable window, and we
should defend it as a latency decision, not blame a theorem that's off duty."

**1.3** *Someone waits — the writer, the reader, or nobody; and if nobody
waits, somebody gets stale.* "Someone" = the writer (coordinated acks:
majority/all/home-forwarded writes), the reader (quorum or leader reads), or
nobody (local everything — EL).

**1.4** The first user lost **read-your-writes**: they were served by a copy
that had not yet received their own change. The second lost **monotonic
reads**: successive answers came from copies at different points in the same
timeline, so a value they had already been shown un-happened. Neither is
answered by "eventually consistent", because that sentence is a promise
about *the system converging*, made to nobody in particular — it says
nothing about one person's own history, which is the only thing either user
is complaining about. Both are fixable for roughly nothing (a token and, at
worst, one catch-up wait); neither requires paying the coordination bill on
every operation.

**1.5** A stale read that *informs* tells you something you can be told
again — a price, a count on a dashboard — so the next refresh repairs it.
A stale read that *authorizes* is used to justify an act that happens
outside the database: goods ship, money moves, a seat is given away. The
data can be corrected afterwards; the act cannot. Refreshing repairs the
first because the only wrong thing was a copy; it cannot repair the second
because the wrong thing is now in the world.

**1.6** Because it names a requirement (never sell the eleventh) and then
jumps to one particular mechanism (coordinate every sale) without checking
whether cheaper ones satisfy the same requirement. The question to ask
first is: **can this invariant be divided?** A stock count, a spend limit,
a rate, a budget — anything that is a *quantity* — can be split into
per-region allowances whose local rules imply the global one, which keeps
1 ms local writes and survives partitions. Coordination becomes genuinely
necessary only when the invariant is about an *identity* ("this username is
mine", "exactly one winner"), because you cannot give a region 40% of a
username.

## Rung 2 — Identify

**2.1** Big colored dots are value-carrying messages (the `repl` copies of
the price, colored by the writing region); small hollow dots are
confirmations and read queries (`ack`, `rreq`, `rrep`). The write becomes
official at the arrival of the **first `ack` back from another region** —
that's when acks reach 2 of 3 (the coordinator's own counts as the first).

**2.2** `P → C` and `E → C` are lit. Evidence for E→C: the write-ack latency
readout (~110 ms from Mumbai — the coordination bill) and zero stale reads.
Evidence for P→C: cut a region off and its operations end in REFUSED (the
refused counter), while the other two continue.

**2.3** Home region's writes are ~1 ms (master is local); the other two pay
their full RTT to home (Frankfurt 110 ms, Virginia 190 ms with home=Mumbai).
The number is in each client box's "last write ack" readout.

**2.4** (a) Asha's client box shows `session token: write #N (t = 0 ms)` —
the stamp of the newest write she was told was saved. (b) The ⏳ marker sits
on **Frankfurt**, the city serving her read, because that copy is behind the
token. (c) The wait is however long remains of Frankfurt's 55 ms one-way
delay — 45 ms if she reads 10 ms after writing. Meanwhile the **stale reads**
counter keeps climbing on other clients' reads while "your own change
vanished" stays 0: the system is still returning out-of-date answers to
everyone else, and it is *still* an EL system. What was fixed was one
client's view of its own history, not the system's freshness.

**2.5** Sell-now-tell-later: **nowhere.** There is no place in the design
that could refuse the eleventh sale, which is why no failure is needed to
break the rule — that is the point of running it once. Head-office
approval: **the head office's book** — one machine's counter, consulted
before every sale, and the single thing whose failure or unreachability
stops sales entirely. Split-the-stock: **each branch's own allowance
check** — three independent places, none of which needs the other two to be
reachable, correct, or even alive.

**2.6** **Nobody.** Frankfurt decrements the moment it sends, Mumbai
increments only on arrival, and for the 55 ms in between the two necklaces
are unsellable. That is the only safe direction: if both ends counted the
allowance as theirs during the flight, the same two necklaces could be sold
twice — the transfer would have *created* stock. Losing 55 ms of
sellability is a cost paid in utilization; the alternative is paid in
oversells, which are not recoverable. (Same asymmetry as a lease handover:
always leave a gap, never an overlap.)

## Rung 3 — Predict

**3.1** First read: **stale**, `staleBy = 39 ms` (read began t=40; the
write's ack completed t=1). Second read: **fresh** — Virginia applied the
update at t=95, so by t=200 the window has closed. EL staleness is a
window, not a permanent state.

**3.2** **90 ms.** Omar's coordinator (Virginia) needs one more region;
Frankfurt (RTT 90) answers before Mumbai (RTT 190).

**3.3** **Two degraded acks** (Omar's at 1/3, Asha's at 2/3 — both hit the
1000 ms give-up), and **Omar's** acknowledged write is discarded: at heal,
last-write-wins keeps Asha's later timestamp, and Omar's write was never
seen by the winner. The "acked writes lost" counter reads 1.

**3.4** Answer-locally: Asha sees **₹50,000** — her own change, missing from
her own screen — and two counters move, "stale reads" (the recency rule) and
"your own change vanished" (her own history). Session design, same script:
her read **parks at Frankfurt and returns ₹54,000 at t = 55 ms**, a 45 ms
wait paid by her alone; Priya's local read at t = 20 ms returns **₹50,000**
immediately and is correctly judged **stale**, with no session violation —
Priya has no token to violate. That pair is the whole point: session
guarantees and recency are different promises, and the screen shows both.

**3.5** t = 60 ms → **₹54,000** (Frankfurt applied the update at 55, so the
read is fresh); t = 70 ms via Virginia → **₹50,000** (Virginia applies at
95). The guarantee that breaks is **monotonic reads**, and Priya wrote
nothing at all — which is why "just read your own writes from the primary"
is only half a fix. The counter that moves is "price went backwards".

**3.6**

| Design | sold | oversold | turned away | turned away with stock left |
|---|---|---|---|---|
| (a) sell-now-tell-later | **11** | **1** | 0 | 0 |
| (b) head-office approval | **6** | 0 | **5** | **5** |
| (c) split 4/3/3 | **7** | 0 | **4** | **4** |
| (d) split 6/2/2 | **8** | 0 | **3** | **3** |

The number that is identical in (c) and (d) — and in (b) — is **oversold =
0**. Correctness is not what the split is trading; the split trades
*utilization*. That is the whole lesson of the pair: once the invariant is
divided, it holds unconditionally under every division, and the only thing
your choice of division changes is how many customers you turn away with
stock on a shelf somewhere else. Note also that (b) and (c) turn away
similar numbers for entirely different reasons: (b) refuses because a
region cannot reach the authority (a partition-time refusal, PC), while (c)
refuses on a perfectly healthy network (a locality cost, and it would have
refused those same customers with no cut at all).

**3.7** Yes, it still oversells — 11 again. Mumbai's news reaches Virginia
in **95 ms** one way, and customers arrive every **20 ms**, so by the time
Virginia hears about Mumbai's first sale, four more have happened. The
partition made the divergence total, but the staleness window alone was
already wide enough: F1 and F11 are the same physics, with different
consequences attached.

## Rung 4 — Modify

**4.1** At 10×, the Mumbai↔Frankfurt round trip becomes 1100 ms — above the
1000 ms timeout — so the majority write is **refused with no partition
anywhere**. Identical in kind to a partition because the system's only
partition detector *is* the timeout: "partition" operationally means
"latency above my configured threshold". The boundary is the 1000 ms
constant, a config value, not a physical event. (This is F13 and §16's
unification: a partition is the far end of the latency dial.)

**4.2** Home = Frankfurt: Frankfurt writes ~1 ms; Mumbai 110 ms; Virginia
90 ms. A per-record master should live **where that record's writes
originate** — write locality. (PNUTS migrated a record's master toward its
observed writers for exactly this reason.)

**4.3** Up. Majority of 5 is 3, so a write needs **two** remote acks — you
wait for your *second*-nearest neighbor, not your nearest, and the tail gets
worse because more independent slow-downs can catch you (the quorums lab's
tail measurement: the more replicas you wait on, the more often one of them
is having a bad moment). More regions buy durability and read locality, and
*raise* coordinated-write latency.

**4.4** Her read parks at Virginia, Virginia can never receive Mumbai's
update while it is cut off, and at 1000 ms the read is **refused**. The rule:
**every guarantee you add is also an availability liability.** A PA/EL system
that promises nothing can always answer; the moment it promises a client
something ("you will never see your own past"), there exist situations where
the only honest move is to refuse — a promise that cannot be kept has to be
declined, not quietly broken. This is the same shape as F13 at a smaller
scale: the refusal appears when a wait crosses the configured timeout.

**4.5** The ack falls to **12 ms** (each one-way hop is 55 × 0.1 ≈ 6 ms).
Re-answer: "strong consistency is too slow" is mostly a claim about *how far
apart you put the voting replicas*, not about consistency — the consensus
tax is one round trip between them, and if they are 6 ms apart it is 12 ms.
What you gave up to make it cheap: **independence of failures** (three
replicas within one continent share power grids, network backbones, provider
region-groups and weather), and the fact that distant users now pay the trip
to reach that quorum — the first term of the decomposition, which no
consistency setting can remove.

**4.6** Leader in Mumbai: 95 (Virginia → Mumbai) + 110 (Mumbai's quorum
round trip to Frankfurt) + 95 (back to Omar) = **300 ms**. Leader in
Virginia: ~0 + 90 (Virginia's quorum round trip to Frankfurt) + ~0 =
**90 ms**. The consensus tax barely moved (110 → 90); what changed is the
**first and third terms — 190 ms of pure travel to reach the coordinator**,
a cost that appears in no consistency setting, no query plan, and no
profiler. Placement, not consistency level, is where that latency lived.

**4.7** Sales coordinated: **zero** — every sale is local, including the
two after the top-up. Transfers coordinated: **one**. At 200 sales/day with
blocks of 20, that is **10 coordinated operations per 200 sales, a ratio of
1:20**, and none of them is on a customer's critical path — a slow transfer
delays a *refill*, not a *sale*. Head-office approval coordinates **200
times per 200 sales, 1:1, every one of them in front of a waiting
customer.** That ratio — coordination *per key per node per interval*
versus coordination *per request* — is exactly the economics your
[distributed-rate-limiting](../distributed-rate-limiting/) lab measured,
and it is why the two designs are cheap in opposite regimes: escrow wins
when a key is hot (blocks amortize), per-request coordination wins for the
cold long tail (a block would sit stranded).

**4.8**
(a) `L = λW`: before, 1,500 × 0.003 = **4.5 in flight**; after,
1,500 × 0.090 = **135 in flight**. A 30× multiplication of every
per-request resource, in a change that mentions none of them.
(b) `c / W`: before, 60 / 0.003 = **20,000 req/s**; after, 60 / 0.090 =
**667 req/s**.
(c) ρ = 1,500 / 667 = **2.25**. The queue does not settle at a higher
latency — it **grows without bound**, linearly, for as long as the traffic
lasts; throughput pins at 667/s and every request beyond that waits, then
times out.
(d) `c ≥ λW / ρ_target` = 1,500 × 0.09 / 0.8 = **169 connections** (and now
169 concurrent database sessions is a conversation with the DBA, not a
properties file).
(e) **In-flight count against pool capacity** (equivalently, pool
utilization / queue depth). p99 latency is not that metric because it is a
*lagging* one: it looks fine until saturation, then goes vertical — it tells
you the collapse has already started. In-flight count moves the instant W
moves, which is at deploy time.

## Rung 5 — Trace

| t (ms) | Event |
|---|---|
| 0 | Priya's write ₹54,000 at Frankfurt: applied locally (1 of 2 acks). `repl` departs to Virginia (arrives 45) and Mumbai (arrives 55). |
| 45 | Virginia applies ₹54,000; `ack` departs (arrives 90). |
| 55 | Mumbai applies; `ack` departs (arrives 110). |
| **90** | Virginia's ack reaches Frankfurt: 2 of 3 — **Priya acked, latency 90 ms** (Virginia, not Mumbai, is Frankfurt's nearest neighbor). |
| 60 | Omar's quorum read at Virginia: local reply (₹54,000, applied at 45) is reply #1; `rreq` to Frankfurt (arrives 105) and Mumbai (arrives 155). |
| 105 | Frankfurt replies; `rrep` arrives back at **150** — reply #2 completes the read. |
| **150** | Read answers **₹54,000**, latency 90 ms. Verdict: **fresh** — no write was acked before t=60 (Priya's ack landed at 90), and the value returned is the newest anyway. |
| 110 | (Mumbai's write-ack arrives — ignored, the write is already acked.) |

**5.2 — the beacon trace.**

| t (ms) | Event |
|---|---|
| 0 | Master (Mumbai) applies ₹54,000, stamped t = 0. |
| 95 | Virginia receives and applies it — but cannot yet *prove* it is caught up. |
| 100 | Master publishes a beacon: "closed through t = 0" (everything stamped ≤ 0 has been sent to everyone). |
| 195 | Virginia receives that beacon. It may now answer as of t = 0, ₹54,000, locally. |
| 200 / 295 | Next beacon published / received: "closed through t = 100". |
| 300 | Omar reads. Virginia answers **as of t = 100 ms**, in ~1 ms, with the ₹54,000 value — 200 ms old, and provably not older. |

(a) Worst-case age = **closing margin + beacon interval + one-way delay** =
100 + 100 + 95 = **295 ms**. The replica's known closed timestamp stays put
between beacons while the clock keeps moving, so the age sawtooths up to that
sum. (b) A replica cannot make the promise alone because **no incoming
updates is indistinguishable from no writes happening** — silence carries no
information, exactly as in the distributed-locking lab. Only the write path
knows what it has sent, so only the write path can certify what is missing.

**5.3 — the escrow ledger.** Split 6/2/2, arrivals every 20 ms, Mumbai and
Virginia alternating (Mumbai at t = 20, 60, 100, …; Virginia at t = 40, 80,
…):

| t (ms) | Branch | Decision | Allowance left | Sold so far |
|---|---|---|---|---|
| 20 | Mumbai | sell | 5 | 1 |
| 40 | Virginia | sell | 1 | 2 |
| 60 | Mumbai | sell | 4 | 3 |
| 80 | Virginia | sell | 0 | 4 |
| 100 | Mumbai | sell | 3 | 5 |
| 120 | Virginia | **turn away** | 0 | 5 |
| 140 | Mumbai | sell | 2 | 6 |
| 160 | Virginia | **turn away** | 0 | 6 |
| 180 | Mumbai | sell | 1 | 7 |
| 200 | Virginia | **turn away** | 0 | 7 |
| 220 | Mumbai | sell | 0 | 8 |

(a) The first refusal is at **t = 120 ms, in Virginia**, whose allowance of
two is exhausted while eight necklaces are still unsold. (b) When it
settles: **8 sold, 2 unsold** — Frankfurt's two, which no customer ever
asked for. (c) Every "turn away" line would be described dishonestly by a
*sold out* message: at t = 120 there were five necklaces left in the
company. The honest message is "not available here", and the honest metric
is refusals-while-global-stock-remains.

## Rung 6 — Spot the flaw

Concrete run: at t=0 Virginia's inbound link is congested (or cut — the sim's
partition control). Asha writes ₹54,000 at t=0; Frankfurt's `repl` lands at
55 and its ack completes the majority at **t=110: write acknowledged**
— {Mumbai, Frankfurt} have it; Virginia does not, and nothing in the
protocol waits for it. At t=200 Omar reads *locally* in Virginia: returns
**₹50,000**, while a write acknowledged 90 ms before his read began says
₹54,000. Recency rule violated.

The false step is "**since writes are majority-committed, reads are
consistent**": a majority write guarantees *two* replicas have the value —
it says nothing about the third, and a local read has no way to know whether
it's standing on one of the two. Consistency needs the read to *intersect*
the write set (R+W>N — the overlap argument), or to be routed to a leader.
As written, the system's read path is **EL** (with an unbounded window under
partition): it is PC/E(mixed) at best, and its marketing is writing checks
its reads don't cash. This is exactly why the sim's majority design
quorum-reads, and why ZooKeeper — majority writes, local reads — documents
that its reads are stale unless you `sync()` (linearizability lab).

**6.2** Wrong thing one: **"consistent" with no answer to "for whom?"**.
Session guarantees are promises to one client about its own history; two
users looking at the same account can still see different values, and a
client that has never written gets nothing beyond not moving backwards. The
document silently upgrades a per-client promise into a global one.

Wrong thing two, which is the dangerous one: **the fraud engine's decision
depends on somebody else's write.** Concrete run — the customer's other
device authorizes ₹40,000 in Mumbai at t = 0, acknowledged at t = 1 ms. At
t = 40 ms the checkout in Virginia asks the fraud engine, which reads the
local Virginia replica; the debit lands there at t = 95 ms, so the engine
sees the pre-debit balance and approves. Both payments are approved against
the same money. The session token was never violated — the checkout session
made no writes — and that is precisely why it protects nothing here.

Smallest change to the **requirement**: stop asking for "the balance" and
ask for "a balance at most Δ old, with Δ larger than the one-way delay",
accepting the bounded exposure that implies (a soft limit, reconciled after
the fact). Then a local bounded-stale read is legitimate and cheap. The real
invariant stays where it belongs — a conditional write at the ledger, which
rejects the second debit whatever the fraud engine believed (the
optimistic-locking lab's rule: the guarantee lives at the resource).

**6.3** *First half — the CRDT claim.* A CRDT guarantees **convergence**:
every replica that has seen the same set of operations computes the same
value, with no lost updates and no arbitrary conflict-resolution
casualties. It guarantees nothing whatsoever about an **invariant**,
because each replica applies its own operation locally before it knows
about the others'. Smallest run: stock is 1; Mumbai and Virginia each apply
`−1` at the same instant; both are legal against every local view; the
merge is a perfectly converged, perfectly agreed **−1**. The design
document has confused "no updates are lost" with "no rule is broken" — and
it is precisely because nothing is lost that the oversell is undeniable.
(Escrow is the fix, and note what it really does: it stops the *decision*
being local while keeping the *write* local.)

*Second half — the rebalancer.* "Quiet for 30 seconds" is a **timeout**,
and a timeout cannot distinguish a region with no customers from a region
that is slow, garbage-collecting, or partitioned from the job (F13, and the
[flp-impossibility](../flp-impossibility/) lab's whole point). Run: Virginia
holds an allowance of 3 and is partitioned from the rebalancer but *not*
from its own customers; at 30 s the job reassigns Virginia's 3 to Mumbai;
Mumbai sells them; Virginia sells them too. Three necklaces sold twice, no
error anywhere. The missing mechanism is **fencing**: an ownership epoch on
each allowance, so a transfer bumps the epoch and any sale stamped with the
old epoch is rejected — [distributed-locking](../distributed-locking/)'s
fencing token and
[partitioning-and-rebalancing](../partitioning-and-rebalancing/)'s ownership
epoch, both of which exist for exactly this run. The weaker, cheaper
alternative: make allowances **leases with an expiry**, so the recipient
waits out the old holder's lease before selling — safety from the clock
instead of from a token, at the cost of the wait.

## Rung 7 — Handle failure (worked design)

The root defect of the stock PA/EC degrade is that partition-era writes
enter the same last-write-wins register as everything else, so heal-time
merge *silently kills acknowledged promises*. Redesign:

1. **The degraded ack changes meaning and shape.** On timeout, the
   coordinator does not pretend the write is globally committed. It durably
   appends the order to a local, partition-scoped **queue** (an outbox — the
   transactional-outbox lab's shape) with a client **idempotency key**
   (idempotency lab), and acks as "**accepted**", a documented weaker state.
   Orders are *appends with fresh identities*, never overwrites of a shared
   register — concurrent adds on two sides then have no conflict to lose
   (the eventual-consistency lab's cart lesson: model it as a set/log, and
   two sides' additions union instead of fighting).
2. **What the isolated side may promise:** "your order is accepted and will
   be confirmed" — acceptance, not fulfillment. Anything that requires the
   global invariant (inventory guarantees, payment capture) is deferred or
   done at-most-once with reconciliation.
3. **At heal, no LWW.** The queue is replayed through the normal ordered
   path; idempotency keys make replay harmless; a replay that now violates a
   business rule (out of stock) becomes a *surfaced compensation* (saga
   lab), not a silent discard. Discard count target: zero, structurally.
4. **Two paging signals:** (a) **degraded-ack rate > 0** — the degrade must
   be an alarmed event, never a silent `if`; (b) **oldest unreconciled
   partition-era order AGE** — the reaper-style age alarm (idempotency lab):
   a queue that isn't draining after heal is the real emergency.

What this redesign concedes: during the partition you are no longer PA/EC
for the *invariant* — you are PA with an explicit, bounded reconciliation
debt. That is the honest version of "we never stop taking orders."

**7.2** The shape is a **metastable failure**: a fallback that transfers
load onto the very component whose slowness triggered the fallback, so the
system stays broken after the original trigger is gone. It is the caching
lab's thundering herd with a feedback loop — and structurally the same
runaway as the gossip lab's failure-detector meltdown, with reads as the
payload.

Three mechanisms, all already yours: (1) **single-flight** at the primary —
one in-flight fetch per key, everyone else waits on it, so N replicas'
worth of duplicate reads collapse to one; (2) a **fallback budget with
jittered backoff** — only a capped fraction of reads may promote to the
primary, the rest get a stale answer with a visible "as of" marker or an
honest refusal (load shedding beats collapse, and admission control should
prioritise the *replication* traffic, because that is what ends the
incident); (3) **rate-limit the batch job itself** — it is the actual
input, and the distributed-rate-limiting lab is the tool.

Δ should have been set from the **measured p99.9 replication lag under
peak load, plus headroom** — not from a round number. If the batch pushes
lag to 30 s every Monday, a 2 s bound guarantees a weekly cliff; either
raise Δ above the real peak or fix the peak, but do not discover it at
09:00. The dashboard that warns days earlier is **lag as a percentage of
Δ (headroom)**, alarmed at 80% — a number that would have been visibly
creeping up for weeks. Raw lag in milliseconds hides this; the ratio is
what matters.

**7.3**
(a) At 09:14, W for that endpoint goes from a few ms to ~90 ms. λ is
unchanged, so `L = λW` rises by the same factor — every in-flight request
now holds its connection ~30× longer. Within seconds the pool is fully
occupied; new requests queue *for a connection* rather than for the
database. Queue time (invisible in the database's metrics, because those
requests have not reached it) becomes most of the response time. Callers
hit their 600 ms timeout, retry twice, and the offered load triples on a
component already at ρ > 1.
(b) Because flat throughput with a rising error rate is the signature of
**saturation, not failure**. A broken dependency makes throughput fall; a
capacity ceiling holds it exactly level while the backlog grows behind it —
which is why the graph looks reassuring and is the worst news on the page.
Goodput, meanwhile, is collapsing: most of the work being completed is for
callers who have already given up.
(c) Because the system is now **metastable** (the
[queueing-theory](../queueing-theory/) lab's F3): the retry backlog is
self-sustaining, so removing the original trigger leaves the loop running.
What restores it is breaking the loop — shed load hard (to ~0.85 of
capacity, not 0.95: a timid shed took 18 minutes where a hard one took 6),
or drain traffic entirely and restart, which is why "turn it off and on
again" genuinely works here.
(d) Guardrails: (1) treat the consistency flag as a **capacity change** —
compute `λW` and resize the pool (or a concurrency limiter) in the same
change, and roll it out to a fraction of traffic first; (2) **bulkhead** the
coordinated path into its own pool so it cannot consume the connections the
other 95% of traffic needs. And the one that makes the rollback work: a
**retry budget** plus deadline propagation, so that when the path is slow
the system stops manufacturing its own load and a rollback is enough.

## Rung 8 — Design (worked discussion)

| Flow | P | E | Mechanism | Latency | On-call metric |
|---|---|---|---|---|---|
| A. Price display | PA | EL | Local replica / cache (sim design 1) | ~1 ms | Replication lag AGE (staleness window) |
| B. Add-to-cart | PA | EL | Local writes on a merge-friendly cart (OR-set — crdts lab), async spread | ~1 ms | Merge-conflict/sibling count; lag AGE |
| C. Payment auth + ledger | PC | EC | Majority/leader commit for the *authorization write*; conditional write + idempotency key at the ledger | 90–110 ms budgeted into checkout | Refused-op rate per region; write p99 vs the RTT floor |
| D. Fraud score | PA | EL | Local read of an async-computed score (staleness is intrinsic — minutes old by construction) | ~1 ms | Score AGE vs model cadence |

**Which flow justifies the EC bill:** only **C**. A stale price display
costs a discount; a stale cart merges; a stale fraud score was already
minutes old. A double-spent ledger is money. (The decision test from §17's
flowchart: "would acting on a seconds-stale answer lose money or break an
invariant?" — only C says yes.)

**Cheapest correct place to pay it:** *at the single authorization/append
write, per operation — not platform-wide.* Two refinements stack: (1) the
per-operation dial — checkout's one coordinated write pays 110 ms while the
page around it renders from EL replicas; (2) **home-region mastering per
account** (sim design 3, sharded): an account's ledger writes route to its
home region, so the common case (customer transacting near home) pays ~local
latency and only travelers pay a cross-region RTT — and the invariant is
still guarded by a single ordered timeline per account plus conditional
writes at the resource (optimistic-locking lab: the guarantee lives at the
resource, whatever the topology). Reads of the ledger for *display* stay EL;
reads that *decide* (available balance before authorizing) go through the
same home master — the eventual-consistency lab's F7 rule closing the loop.

**8.2 — placement (worked discussion).**

(a) With the ledger's leader for every account in **Mumbai**, using the
three-term decomposition: a Mumbai customer pays 0 + 110 + 0 = **110 ms**; a
Frankfurt customer 55 + 110 + 55 = **220 ms**; a Virginia customer
95 + 110 + 95 = **300 ms**. Same code, same consistency level, 2.7× spread —
and the spread is invisible in the database's own metrics.

(b) Rule: **each account's leader lives in the account holder's home
region** (v4's per-record master, applied as a placement policy — this is
CockroachDB's `REGIONAL BY ROW` and Spanner's leader placement). The common
case collapses to 0 + nearest-peer RTT + 0: **110 ms** for Mumbai-led,
**90 ms** for Frankfurt- and Virginia-led accounts. What gets *worse*: the
traveller (a Mumbai account holder transacting in Virginia still pays the
full 300 ms), and — more seriously — **a transfer between two accounts in
different regions now spans two leaders**, which is a distributed
transaction, priced in the two-phase-commit and distributed-transactions
labs, not a single ledger write.

(c) Under data residency, an account's replicas may not leave its region, so
a three-region majority for that account is simply illegal. You move to
**three replicas inside the region** (or region-group): EC gets *cheaper*
(single-digit ms — Rung 4.5's lesson arriving as a compliance requirement),
and you lose cross-region durability for that account, which is now a
business decision rather than an engineering one. What becomes impossible to
promise: **a globally atomic, linearizable cross-region transfer**. The best
available promise is atomic-per-side with an idempotent, reconciled,
visibly in-flight transfer — the saga shape, with the in-flight state
exposed to the customer rather than hidden.

**8.3 — the checkout page (worked discussion).**

| Element | Honest freshness requirement | Cheapest mechanism (§11's table) | Why |
|---|---|---|---|
| Price | ≤ a few seconds | Local read, bounded (row 3) | Δ ≫ 95 ms one-way, so async replication already satisfies it — coordination would buy nothing |
| Cart | The user's own edits, always; never backwards | Session token (row 2) | It is *their* history; strangers' views are irrelevant |
| Fraud score | Minutes by construction | Local read of the async score (row 1/3) | The score is already minutes old; a coordinated read would deliver a stale number *slowly* |
| Balance shown right after "Pay" | Must include this user's own just-completed write | Session token (row 2) — **not** a coordinated read | The money already moved through a coordinated write at the ledger; the *display* only needs this user's own token |

The one that must not be coordinated: the **fraud score** (and the price).
Their requirement is measured in minutes and seconds, and the network's
one-way delay is 95 ms — the requirement is thousands of times looser than
the thing coordination would fix, so paying for coordination buys a slower
version of the same answer. That comparison — *requirement versus one-way
delay* — is the whole decision, and it is the sentence to take out of this
lab.

**8.4 — the three questions, applied (worked discussion).**

*Promotional stock, 10,000 units sold globally.*
Q1: it **authorizes** — a unit ships. Q2: it is a **quantity**, so it
divides. Stop at question 2. Mechanism: allocate blocks per region sized to
about 10–20 minutes of that region's expected sales (say 3,000 / 4,000 /
3,000, refilled in blocks of 500 from a central pool), each region enforcing
"units sold from my block ≤ my block"; the sum of the blocks plus the
unallocated pool never exceeds 10,000, which is the argument that the local
rules imply the global one. At zero, the region does *not* silently fail:
it requests a refill (fast path) and, if the pool is unreachable, refuses —
this is the one moment coordination is on the critical path, and it should
be a few percent of sales, not all of them. Failure it can still have:
**stranding** — a region holding 800 units nobody wants while another
refuses customers (F12); the detecting metric is refusals-while-global-pool
> 0, plus per-region block headroom. And if the requirement flips to *never
under-sell*, the design inverts: stranding is now the primary risk rather
than an inefficiency, so you shrink blocks (more frequent, smaller refills
— coordination traded back in), add aggressive reclaim of idle blocks with
**fenced** transfers, and near the end of the campaign switch to
coordinated allocation from the central pool. Never-over-sell and
never-under-sell pull the block size in opposite directions, and no single
size satisfies both — which is the honest answer to give the business.

*Per-customer daily spend limit.*
Q1: authorizes. Q2: also a quantity — but the natural division is not
geographic, it is **per customer**, and a customer's spend limit divides
badly across regions (giving Mumbai ₹40,000 of Asha's ₹100,000 limit strands
most of it, because one customer is usually active in one place at a time).
The better move is the one the [distributed-rate-limiting](../distributed-rate-limiting/)
lab reached: **route the key to an owner**. Give each customer a home region
that holds the whole limit; authorization for that customer coordinates only
by going to their home region — one trip, no quorum, and the invariant is
enforced in a single place. That is v4's per-record master used as an
invariant-enforcement strategy rather than a replication strategy: the flow
becomes PC/EL (refuse when the home region is unreachable, and note that
this is the *correct* answer for a spend limit — refusing to authorize is
survivable, double-spending a limit is not). What makes it work is that
spend limits are a **cold, long-tail** key space: almost every customer is
inactive almost always, so blocks would strand and a per-request trip to one
owner is cheap. The general lesson worth extracting: the division that
matters is not always by region — divide by whatever axis the *demand*
naturally partitions along, and if it does not partition at all, own it in
one place.
