# Solutions

## P1 — A: priya has (ord-1, PLACED, 100.0), amit [], revenue 100.0, lag 1 · B: 100.0, lag 2 · C: (ord-1, CANCELLED, 100.0), 50.0, lag 0

A: `batch=1` applied only seq 1, so priya's board is fresh, amit's order
(seq 2) is invisible, revenue counts only the applied event, and one event
waits. B: a *command* changes only the write side — the boards can't move
until the projector runs, so revenue is unchanged and the backlog is now 2
(amit's place + priya's cancel). C: the default batch drains everything:
amit's 50 was added, priya's 100 added-then-subtracted nets out, status
flips to CANCELLED, lag 0. Note the row keeps `total=100.0` — a cancelled
order's board row remembers its value; only *revenue* was reversed. Boards
answer their own question, nothing more.

## P2 — not in the lab; yes in production, and you start watching per-projection lag

In the lab: impossible. Both tables are updated inside the single
`db.transaction(...)` block in `runOnce` (that line — the `transaction` —
settles it): any query sees both effects of an event or neither. In
production with separate projectors, each board has its **own checkpoint**,
so the dashboard can be at seq 120 while revenue is at seq 95 — cross-model
inconsistency is normal and permanent. The new number you care about: **lag
per projection** (and their spread), not one global lag. Screens that combine
two boards must tolerate their disagreement or read both from one projection.

## M1 — a crash can delay a batch; it can never split one

First call: ProjectorCrashed; the rollback erases the applied event AND the
checkpoint stays 0 — the read model is exactly as before (all-or-nothing).
Second call: applies seq 1 only (checkpoint 1). Third: applies seq 2. The
sentence: **a crash can only ever cost you "the current batch never
happened" — the read model always sits exactly at its checkpoint, never
between two states.** Batch size tunes throughput vs. how much work a crash
re-does; it never affects correctness (given the atomic checkpoint).

## M2 — it spins forever; timeout, then be honest

`waitFor` as written busy-loops forever (in the lab it would just loop;
in production it would hold the request thread). Fix shape:

```java
static boolean waitFor(Projector projector, int seq, int attempts) {
    for (int i = 0; i < attempts; i++) {
        if (projector.read.checkpoint() >= seq) return true;
        projector.runOnce();
    }
    return false;       // caller decides what "couldn't confirm" means
}
```

When it returns false the two defensible answers: **(1) degrade honestly** —
show "your order was received and is being processed" built from the
command's own result (you *know* it succeeded; you just can't show the
board yet); cheap, always available, slightly weaker page. **(2) fall back
to the write side** for this one query — perfectly fresh, but it reopens
the shape war for that endpoint and couples the UI to write-model
availability; acceptable for rare, high-value confirmations. (Showing an
error is the one indefensible answer: the order DID succeed.)

## D1 — the subtraction runs before (and regardless of) the guard

In the broken `apply`, the revenue subtraction executes unconditionally,
*then* the status update carries the `AND status!='CANCELLED'` guard. On
first delivery: subtract 80 (fine), mark cancelled (fine) → revenue 0,
manual test passes. On redelivery: subtract 80 **again** (nothing gated
it) → -80, while the guarded status update quietly matches zero rows. The
manual test could never catch it because the bug only exists under
*duplicate delivery* — a condition manual tests never create and real feeds
always eventually do. `test_duplicate_delivery_is_harmless_when_the_projection_is_idempotent`
catches it (swap `CleanedUpProjector` in and it fails with -80 ≠ 0). The
rule violated: **in an idempotent projection, every non-idempotent side
effect must be gated by the same first-time check** — one guard, and
everything conditional on it (the lab's correct version gates the revenue
math on the dashboard update's rowcount).
