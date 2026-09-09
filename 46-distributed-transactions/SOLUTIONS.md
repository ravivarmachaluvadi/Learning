# Solutions — distributed transactions

## Rung 1 — Explain

**1.1** 2PC promises that both shards' changes *end up* applied or neither
does — a promise about the final state of the disks. It says nothing about
what other people see while it happens: the two shards commit at two
different moments, and any reader arriving between them sees money that has
left one account and not reached the other. The auditor notices; so does
every report, fraud check, and balance API. The missing promise is atomic
*visibility*: nobody, ever, sees half a transaction. Measured: with perfect
2PC and zero crashes, 16 of the lab's audits were wrong.

**1.2** *Time:* give every transaction one number in a single global order,
commit "at" a number, read "as of" a number — then any transaction is
entirely before your snapshot or entirely after it, which kills fractured
reads. *Place:* make the commit one local durable write (the primary's
commit record) — then the verdict can be learned by anyone even when the
coordinator is dead, which kills the blocking problem.

## Rung 2 — Identify

**2.1** `alice` — the first key the transaction writes. Nothing deeper: some
deterministic choice must appoint the verdict's home, and "first write"
is Percolator's.

**2.2** The lock (with startTs=2), the staged value 150, and the pointer
`primary=alice` inside the lock. A reader follows the pointer — the lock's
`primaryKey` — to alice's shard and looks up `commitRecord[2]`.

**2.3** `COMMIT PRIMARY on S0: ... commitRecord{2->3}`. At that moment S1
still holds only the lock and the staged 150 — it has not heard anything —
and yet the transaction is committed: any reader from now on will roll bob
forward using the record.

## Rung 3 — Predict

**3.1** Yes. A transaction's commit-ts is always greater than its start-ts,
so a lock born at ts=12 can only ever commit above 12 — strictly above the
reader's snapshot of 10. Whether it commits or aborts, it is invisible at
ts=10 by definition; the newest version ≤10 is the correct answer either
way. This branch is why readers don't stall on the future.

**3.2** 281 vs 0. In lock-based 2PC the verdict lives in the dead
coordinator's memory/log, so a reader waiting on a lock has nobody to ask;
in percolator it lives in the primary's commit record, so any reader
resolves the lock itself (rolling forward 47 times and back 272 in that
experiment).

**3.3** 0 — and that is the danger. The design has *no* external-consistency
guarantee, yet at small skew it produces zero violations, making it
indistinguishable from a correct system in every test that doesn't inject
skew. Same lesson as the linearizability lab's calm-network row: a green
result without injected asymmetry is close to worthless.

## Rung 4 — Modify

**4.1** Measured: exactly one test fails —
`perco_dead_client_before_flip_gets_rolled_back_by_a_reader`, and on a
telling assertion: the reader was supposed to *wait* when it met a fresh
lock (`blocked == null`), but with TTL=2 no lock is ever "fresh" — the WAIT
branch becomes unreachable and readers instantly bury possibly-live
transactions. In experiment 3's crash storm the reader wait ticks drop 254
→ 0 and roll-backs rise 272 → 277: patience has been replaced by murder.
Real-world face: an over-aggressive lock-resolution timeout killing slow
(not dead) transactions under load spikes. Safety is untouched — money
never goes wrong, because the primary cell arbitrates the race and the
writer's `commitPrimary` discovers it lost — it is *liveness for writers*
that dies. (TiDB's fix: long transactions heartbeat their TTL upward.)

**4.2** Every reader now pays a primary lookup for locks that could not
possibly concern it (transactions that started after its snapshot), and
readers can be made to wait on transactions that began *after* they did —
gratuitous latency and, under a steady write stream, reader starvation.
Correctness survives: the resolution rule still answers correctly in every
case; the early return is purely an efficiency-and-liveness branch.

**4.3** First-committer-wins is the *only* write-write conflict check the
design has — snapshots never see each other, so this per-shard comparison
is what stops two transactions overwriting each other blindly. Delete it
and concurrent writers to the same key both commit, the later commit-ts
silently winning: the **lost update**, straight from isolation-levels.
Measured blast radius: **5 tests fail**, not just
`perco_first_committer_wins_conflict_abort` — the fuzz money checks go red
too, because a transfer stages `value-at-snapshot + delta`, so an
overwritten concurrent debit doesn't just lose an update, it loses *money*
(the two-transfers-from-alice case double-counts her old balance).

## Rung 5 — Implement

The rule, in the shape of `YourResolver.decide`:

```java
if (lockStartTs > snapTs) return Action.READ_BELOW_SNAPSHOT;
if (primaryCommitTs != null)
    return primaryCommitTs <= snapTs ? Action.ROLL_FORWARD_THEN_READ
                                     : Action.READ_BELOW_SNAPSHOT;
if (primaryLockHeld)
    return lockAgeTicks > TTL ? Action.ROLL_BACK_THEN_READ
                              : Action.WAIT_AND_RETRY;
return Action.ROLL_BACK_THEN_READ;   // no record, no primary lock: it aborted
```

Verified 6/6 against the harness. The three hints decoded: the verdict
lives at the *primary* (never guess from the secondary); a lock from a
transaction younger than your snapshot is none of your business; the only
case for patience is a fresh lock whose primary is still held — a live
commit in progress.

## Rung 6 — Debug

| bug | guarantee broken | smallest exposure |
|---|---|---|
| `readerIgnoresLocks` | atomic **visibility** | one commit paused after the primary flip + one audit: sum 150 |
| `commitSecondariesFirst` | **atomicity** itself | crash between secondary-commit and primary-commit: credit committed, debit later rolled back — money 250 |
| `resolveWithoutPrimaryCheck` | atomicity (destroys a **committed** write) | client dies after the flip; a "helpful" reader rolls the secondary back — money 150 |
| `earlyReadLockRelease` | isolation strictness (strict→non-strict 2PL) | reader releases alice's lock, transfer commits in the gap, reader reads bob: sum 250 |

Most dangerous in production: `resolveWithoutPrimaryCheck` — it silently
destroys committed data, and only under the crash+resolution path, so it
passes every happy-path test and every crash test that doesn't audit final
balances. "Most visible" would be `readerIgnoresLocks` (wrong audits all day
under load); danger is inverse to visibility because invisible corruption
compounds before anyone looks.

## Rung 7 — Handle failure

**7.1** Before prewriting, the client durably records its own transaction
intent keyed by a client-chosen idempotency id, including the start-ts it
obtained. On retry, it first asks the primary shard for
`commitRecord[startTs]`: present → the transfer committed, report success
(and optionally finish secondaries); absent with the primary lock gone →
it aborted (or was rolled back), safe to run a fresh transaction; absent
with the lock still held → the original attempt may still be in flight —
resolve via the normal TTL rule before retrying. The re-derived law: **a
timeout is not a failure; retries must be idempotent** — the
transactional-outbox / raft-lab rule, now with the commit record as the
deduplication truth.

**7.2** Order of stoppage: new write transactions stall at start-ts; then
new *reads* stall (they need snapshots too); in-flight transactions that
already hold both timestamps still finish, and lock resolution still works
(it needs no oracle) — so the cluster freezes politely rather than
corrupting. Escapes: (TiDB) make the oracle a Raft-replicated service that
grants timestamps in batches, so its failover is an election (~seconds) and
its throughput is amortized; (Spanner) remove the oracle entirely — clocks
+ ε + commit wait, paying ~2ε on every write ack instead of a round trip
on every transaction.

## Rung 8 — Design (worked discussion, not a single right answer)

**Traffic classes.** In-region transfers (95%): keep both accounts on the
same shard group — single-shard ACID transactions, no distributed protocol
at all (decision-ladder rungs 1–2; this is also why account→region
assignment is a product decision, not just an infra one). Cross-region
transfers: percolator-style snapshot transactions with primary = the
debited account's shard, so the "never negative" check and the verdict live
together — the balance check is a read at start-ts plus first-committer-wins
on the debit row, and *its* strictness matters: "never negative" is a
constraint on a row you WRITE, so snapshot isolation suffices (no write-skew
exposure for that specific invariant; document this reasoning — if the
invariant ever spans rows you only read, you need SSI or locks).

**Timestamps.** 50ms inter-region rules out a naive central oracle for the
read path (every read pays a cross-region hop). Either (a) TiDB-shape:
oracle in one region, co-locate each account's transactions with it, accept
the hop for cross-region commits only, and serve in-region reads from
region-local "safe timestamp" watermarks; or (b) Spanner/Cockroach-shape:
HLC or bounded clocks with commit wait / uncertainty restarts. With ~ms NTP
bounds and no atomic clocks, (b) means hundreds of ms of ε — likely
unacceptable — so (a) with engineering, or rented Spanner, is the honest
answer.

**Fraud pipeline.** Snapshot reads at a slightly stale timestamp
(follower/watermark reads): atomic visibility without touching locks or the
oracle — it must *never* run at read-latest (that reintroduces Omar's 150),
and staleness of a few hundred ms is fine for scoring. The regulator's
requirement ("confirmed ⇒ visible to any subsequent read") is external
consistency and binds only *confirmed-then-read* flows: enforce commit wait
(or read-refresh) on the confirmation path specifically.

**Refuse distributed transactions for:** notification/analytics fan-out
(outbox + CDC), multi-step flows with human time in them — refunds, KYC,
disputes (sagas with compensations; intermediate states are visible *and
that's correct* for these), and anything expressible as a UNIQUE/CHECK
constraint on one shard (isolation-levels' law: a constraint beats a
protocol).

**Monitor:** cross-region commit p99 vs ε / oracle RTT; transaction retry
+ resolve-lock rates (hot accounts show up here first); oldest-running
snapshot & version-GC lag; in-doubt/stranded-lock age; clock-offset alarms
with eviction, not tolerance; and per-region ledger-sum drift checks — the
auditor Omar, productionized, because the whole lab began with him.
