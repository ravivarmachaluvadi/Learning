# Solutions

## Rung 1

**1.1** Linearizability answers: *does each single operation on each single
object look like it happened at one instant, and do those instants respect
the wall clock?* Serializability answers: *can the outcome of my declared
groups of operations be explained by running the groups one at a time in
some order — any order?* They are independent because each ignores the
other's axis: serializability has no clock, so an acknowledged write may be
"explained" as happening after a later read (stale read passes);
linearizability has no groups, so two operations whose combination breaks an
invariant each pass individually (lost update / write skew passes). One
guarantee inspects *time* at operation grain; the other inspects *grouping*
with no time.

**1.2** They confused CAP's C with ACID's I/serializability. Gilbert &
Lynch's proof defines CAP's C as **linearizability** — a single-object,
real-time recency property. CAP says nothing about transactions; a
partitioned system could still offer (non-strict) serializable transactions
within a partition, and a CP system may have no transactions at all.

## Rung 2

**2.1** Red: Linearizability (object `balance`) and Strict serializability.
Green: Serializability, with explanation "order: T2, T1" — the mobile read
placed *before* the deposit. What makes it legal for the S judge and
illegal for the others is the empty **gap of real time** between T1's
response and T2's invocation: the S judge cannot see gaps (no clock); the L
and SS judges treat that gap as a hard ordering constraint.

**2.2** Objects: the two on-call flags, one per doctor. Transactions: T1
(read both flags, unset A's), T2 (read both flags, unset B's). The shared
thing: the **predicate** "at least one flag is on" — a rule spanning both
objects that both transactions *read*. The linearizability judge cannot see
it because it rules per object: each flag's own reads and writes are
perfectly explainable by instants; the predicate exists only across
objects, i.e., at transaction scope.

## Rung 3

**3.1** L(x): **fail** — write acknowledged before the read began;
real-time forces write-then-read; a register can't then return 0. S:
**pass** — order T2, T1. SS: **fail** — the only order S found violates
real time.

**3.2** Grouping OFF: L pass (every operation individually explainable), S
pass (each op its own tiny transaction; order R_A, R_B, W_A, W_B explains
every read), SS pass. Grouping ON: **only S flips red** (T_A→T_B fails
because T_B read 1000 after T_A wrote 200; T_B→T_A symmetric); SS red
follows automatically (stricter than S). L stays green — the bars never
changed.

**3.3** S prints **T2, T3, T1** (the only working order: T3 after T2 to see
new y, before T1 to see old x — legal only because T1 and T2 touch
disjoint keys and never conflict). SS refuses to reorder **T1 and T2**: T1
completed before T2 began, so T1 must precede T2, and then T3 (which must
follow T2 to see y) can no longer precede T1 to justify old x.

## Rung 4

**4.1** The SS (and L) verdict flips to green the moment T2's invocation
slides to or before T1's response — i.e., the instant the bars **overlap**.
Real-time order only constrains **non-overlapping** pairs; concurrent
operations/transactions may be explained in either order under every judge.
That is why "stale" is only meaningful for reads that *begin after* the
write's acknowledgment.

**4.2** Serializability **dies**. T3's read `y→1` still forces T3 after T2
in any serial order. But T2 now also wrote `x=2`, and a serial order runs
whole transactions atomically — so any T3 placed after T2 must read x as 2
(or a later value), never 0. Every candidate order fails; no clock needed.
The freedom the S judge lost: in the original, T1 and T2 were **disjoint**,
so the judge could slide T1 past T3 to the end of the order at no cost.
Welding a second write onto T2 makes T2's writes travel together
(transaction atomicity), and the sliding trick can no longer separate
"T3 sees T2's y" from "T3 sees T2's x". The causal reverse (F5) lives
exclusively in the disjoint-key gap — which is why it is subtle in
production: the keys that expose it are precisely the ones no conflict
detector links.

## Rung 5

**5.1** Judge L, per object. Object y: `wy[3.5,4.5]`, `ry[2.2,3]→0` — the
read *ended before the write began*, so real time forces read-then-write;
a read before the write correctly returns 0. **y: linearizable.** Object
x: `wx[1,2]`, `rx[5,6]→1` — write completed before the read began; the
read returns the written value. **x: linearizable.** Judge S: T1→T2 needs
`ry→1`, saw 0 — fail; T2→T1 needs `rx→0`, saw 1 — fail. **Not
serializable.** SS: stricter, fail. Versus §10: **the L verdict flips from
fail to pass** — same answers, merely read in the other order at friendlier
times — while S/SS stay failed. The lesson: a *torn read* (seeing half of
T1) is a transaction-scope defect that per-object judges can only catch
*by luck of timing*; here every single object's story is impeccable and the
group's story is still nonsense. This is exactly why the
distributed-transactions lab needed "atomic visibility" as its own
requirement on top of perfectly linearizable per-key storage.

**5.2** Interleaving: T1 executes `write X=debit` at bank X; **T2 then
reads X (sees debit) and reads Y (sees no credit)**; T1 then executes
`write Y=credit`. Bank X's local history: T1's write then T2's read —
serial order T1, T2 ✓. Bank Y's local history: T2's read then T1's write —
serial order T2, T1 ✓. Each bank is locally serializable with those orders;
globally the two orders contradict, and indeed T2 read {X=debit, Y=no
credit} — the fractured audit no single serial order explains.

## Rung 6

**6.1** Counterexample: one key `x=1000`; client A `read x→1000`, client B
`read x→1000`, A `write x=200`, B `write x=200` (two withdrawals of 800).
Every operation touches one key and the history is linearizable — and 1600
left a 1000 account. The silent worker in the vendor's claim: **grouping**.
"Only one key per operation" ignores that *programs* are multi-operation;
correctness of read-then-write needs transaction scope or a conditional
write, which linearizability never promised.

**6.2** Flaw 1 — replicas preserve the *serial order*, not *recency*: a
replica legitimately applies that order with lag, so `T1 deposit commits;
read on replica returns pre-deposit balance` is serializable
(order read-first) yet stale — F1; strictness is destroyed even though
"the same transactions in the same order" is perfectly true. Flaw 2 —
'preserved' conflates the global system with each replica: strictness is a
property of the **whole system's** history (all clients, all endpoints);
even if each replica individually replays a serial order, the union of
primary-writes and replica-reads is the history being judged, and it now
contains real-time inversions. Concrete: T1 commits at primary 10:00; T2
reads replica 10:00:05 → old value; global history not strict (nor
linearizable), regardless of the replica's internal orderliness.

## Rung 7

**7.1** Session/causality token (causal-consistency lab): on every
acknowledged write, the primary returns a monotonically increasing position
(commit index / LSN); the app stores the user's latest token; every read
carries it, and a replica serves the read only once it has applied ≥ that
position (else waits or forwards). Checker: the replica. Cell landed in:
none of the four — this is a **session guarantee** (read-your-writes),
below linearizability: *other* users' newer deposits may still be
invisible, and cross-user real-time order is still unprotected. That's the
point: the incident only required the user's own writes, so buy only that.

**7.2** Mechanism: the optimistic-locking lab's **force-increment on a
shared version row** (JPA `OPTIMISTIC_FORCE_INCREMENT` on the account
aggregate): every transaction whose decision *reads* the invariant also
executes `UPDATE account_version SET v=v+1 WHERE account_id=? AND v=?`
(the read value). Two concurrent rule-readers now collide on a write-write
conflict, which snapshot isolation *does* detect (first-committer-wins);
the second aborts. Trade accepted: false conflicts — *all* rule-dependent
transactions on that account serialize through one row, so throughput on a
hot account drops and you must add the retry loop (and its starvation
risk, per that lab's measurements).

## Rung 8

**8.1 (worked discussion)**
- **(a) Trade execution** — needs the bottom row: atomic multi-object
  transaction (serializable); if cash and shares live on one primary,
  Postgres SERIALIZABLE + retry suffices, and single-primary commit order
  makes it strict in practice. Accepted anomaly: none within the pair —
  but cross-*conversation* races (quote shown, then trade placed) remain
  and need a version check on the quoted price. Buying more (Spanner)
  changes nothing while both rows share a shard.
- **(b) Portfolio screen** — needs the user's own trades visible:
  read-your-writes via session token or route-to-primary for N seconds
  after a trade. Cell: session guarantees, deliberately below top-right.
  Accepted anomaly: another session/device may lag; other users' trades
  may appear late. Linearizable reads for every portfolio view would be
  paying the right-column coordination price for freshness nobody
  demanded.
- **(c) Compliance report** — needs the **left** column done properly: a
  single consistent snapshot (one MVCC snapshot / `AS OF` timestamp), so
  the report is internally coherent — serializable as a read-only
  transaction ordered in the past, *stale on purpose*. Accepted anomaly:
  staleness, which the requirement explicitly permits. This is the
  clearest case where demanding strictness is pure waste.
- **Spanner verdict:** it earns its cost only if execution (a) must span
  shards/regions with the no-fracture requirement intact — cross-shard
  strict serializability is precisely its product. For (b) it's overkill
  wearing a session token's job; for (c) it's irrelevant — the report
  *wants* the past.
