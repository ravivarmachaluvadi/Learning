# Solutions

## Tier 1 — Predict

**P1.** At the instant the NO arrives: alice's row is locked at Bank A
(it prepared first); bob's row is NOT locked (B refused — a NO acquires
nothing durable). Bank A's log: `⟨txn, PREPARED, alice, −500⟩`. B's log:
`⟨txn, VOTED-NO⟩`. Next appends: the coordinator logs `⟨txn, ABORT⟩`
(the decision — an abort gets recorded at the same commit-point location,
it's just the other verdict), then A logs `⟨txn, ABORTED⟩` as its lock is
released. Note the asymmetry the run shows: YES is expensive (lock + flush
+ obligation); NO is cheap and final.

**P2.** alice reads **500** (A was told, applied, unlocked); bob reads
**BLOCKED** (B is prepared, in doubt, lock held). This is **not** an
atomicity violation — it is the *isolation doing its job during an
unusually long window*. The transaction IS committed (the verdict is on
C's disk); B just hasn't heard. Nobody can observe "debit happened, credit
didn't" — the credit's absence is hidden behind the lock. The state is
ugly (half-announced), but never wrong. Atomicity would be violated only
if bob's row were readable at 200 *after* alice's showed 500 — exactly
what the lock forbids.

**P3.** The rule destroys money in **WORLD 1** (coordinator logged COMMIT
before dying): A times out and aborts — alice keeps ₹1000; the recovered
coordinator's log says COMMIT, so B (which waited) commits — bob gains
₹500. ₹500 created from nothing; global state: alice 1000, bob 700.
The symmetric rule ("timeout ⇒ COMMIT") fails identically in WORLD 2
(alice 500, bob 200 — money destroyed). No timeout rule at a prepared
participant can be safe, because — as the experiment proves — both worlds
present identical local evidence. The only safe unilateral timeout in all
of 2PC is the **coordinator's** timeout *before the decision is logged*,
because at that point no verdict exists to contradict.

**P4.** Count: C's `BEGIN` (1) + A's `PREPARED` (2) + B's `PREPARED` (3) +
C's `VOTED-NO` is B's log entry — careful, with three participants A and B
log PREPARED (2 writes), C logs VOTED-NO (1 write), coordinator logs BEGIN
and then ABORT (2 writes) = **5 total**. The wasted work: A's and B's
PREPARED flushes — two synchronous disk writes (plus locks held) for a
transaction that was doomed the moment C looked at its data. Unavoidable
because votes are collected in parallel ignorance: nobody knows the
outcome until the last vote is in — unanimity means the cost of a veto is
paid by everyone who said yes. (This "pay even to lose" property is part
of 2PC's latency reputation.)

## Tier 2 — Modify

**M1.** `test_participant_recovers_in_doubt_and_asks_coordinator` fails:
after the crash, A's log contains no `PREPARED`, so `Bank.recover` finds
nothing in doubt — no lock, no pending entry. The killer timing: A says
YES (message sent), crashes before logging; coordinator has unanimous YES,
logs COMMIT; recovered A has no memory of any promise — its vote
evaporated. When the commit arrives (or A asks), A has no undo/redo info
and no lock; meanwhile it may have already accepted other transactions
touching alice. The verdict says debit; A can't honor it or may apply it
over changed state — money wrong either way. The rule broken: **promise
durably, then promise out loud** — the log write must precede the message
that makes it binding. Same law everywhere in your labs: the outbox
commits the message *with* the order before anything is sent (outbox lab
§5); the saga coordinator acts and *then* logs but leans on idempotency
precisely because act-and-tell isn't atomic (saga lab D1: record-then-act
vs act-then-record). In 2PC's phase 1 the participant has no idempotency
to lean on — the log flush before the YES is the whole promise.

**M2.** Decision table for in-doubt asker:

| Peer's state | Asker may safely… | Why |
|---|---|---|
| never heard of txn | ABORT | peer never voted ⇒ unanimity was impossible ⇒ no COMMIT verdict can exist |
| VOTED-NO | ABORT | a NO exists ⇒ verdict is ABORT |
| COMMITTED | COMMIT | the verdict has been seen: it was COMMIT |
| ABORTED | ABORT | verdict seen: ABORT |
| PREPARED (in doubt too) | **nothing — keep asking / keep blocking** | both are inside the uncertainty window; the verdict may be either |

The last row is scenario (e): all peers prepared, coordinator gone —
cooperative termination spreads knowledge but cannot create it.

**M3.** Run: coordinator logs COMMIT after A's YES; B then votes NO (bob's
account frozen). The decision is already durable — by the commit-point
rule it *is* committed — but B refused and will abort locally. A commits,
B aborts: atomicity gone. The broken property: the decision write may
happen **only after unanimity is proven** — the commit point's authority
comes entirely from the fact that every participant has already made both
futures possible. Logging early is writing a verdict about promises not
yet given.

**M4.** Sequence: scenario (e) (coordinator logged COMMIT, died);
operator, tired of nine-hour locks, runs `heuristicAbort` on Bank B —
bob's lock released, B logs `ABORTED`. Coordinator recovers, re-sends
COMMIT; A commits (alice 500). Auditor finds: C's log `⟨COMMIT⟩`, A's log
`⟨PREPARED, COMMITTED⟩`, B's log `⟨PREPARED, ABORTED⟩` — a written
confession of a half-committed transaction (₹500 vanished). Real
databases ship it anyway because *indefinite* blocking can be the worse
business outcome (an entire batch pipeline stopped for hours); heuristic
decisions convert an availability catastrophe into a bounded,
*documented* consistency debt that reconciliation can repay. The XA spec
even standardizes the resulting states (`HEURRB`, `HEURCOM`) precisely so
the damage is at least visible.

## Tier 3 — Debug

**D1.** `test_prepared_account_blocks_other_customers` (and its sibling
assertion inside the blocking test) fails — `readBalance` no longer
throws. The anomaly: transfer T1 (alice→bob) prepares at both banks, locks
dropped after YES. Reader R now sees alice 1000 *and* bob 200 — fine so
far. T1's verdict COMMIT arrives: alice 500, bob 700. But suppose instead
a second transfer T2 (alice→carol, ₹800) starts in the gap: alice is
unlocked, balance still 1000, so A votes YES on T2 as well. Both verdicts
COMMIT: alice = 1000 − 500 − 800 = **−300** — a state the prepare-time
check explicitly forbade, reached with every individual step "validated."
The locks were carrying **isolation** — and, as this shows, the
*validity* of every YES vote: a vote is a promise about a world that must
be frozen until the verdict, or the promise silently rots.

**D2.** Options: (1) wait for a coordinator that will never return —
blocks forever; (2) `ROLLBACK FORCE` (heuristic abort) — unblocks now;
wrong if the lost verdict was COMMIT, and with the recovery log gone,
*nobody will ever know* — reconcile with the other bank's books by hand;
(3) `COMMIT FORCE` — mirror-image risk; (4) reconstruct the verdict from
surviving participants' logs (cooperative termination by grep): if ANY
participant shows COMMITTED/ABORTED for the txn, follow it — this is the
only risk-free option and should be tried first; if all show PREPARED,
you're back to guessing. The architectural decision that prevents it all:
the coordinator's decision log must be **replicated / survivable
infrastructure** (HA transaction-manager storage — or 2PC over a
consensus group, Spanner-style), and decommissioning anything holding
recovery logs must be gated on `no in-doubt transactions reference it`.
A transaction manager's log is data, not config — the migration treated
it as config.

**D3.** Run: A and B both prepare; in-memory coordinator decides COMMIT,
tells A (A commits, unlocks); coordinator restarts (memory gone); B, in
doubt, re-asks; coordinator finds *no decision* for the txn and — by
presumed abort, which is only sound when decisions are durable — answers
ABORT. B aborts. A committed, B aborted, both banks behaved perfectly.
The missing property: the commit point must be a **single durable,
crash-surviving fact** — "one write, one truth, forever". In-memory, the
verdict is Schrödinger's: it can be *observed* (by A) and then *cease to
have ever existed* (for B). Presumed abort turns that amnesia into an
actively wrong answer, because its whole logic is "no durable record ⇒ no
one can have been told" — an invariant the restart just broke.
