# Idempotency — solutions

## Rung 1

**1.1** A client that never retries loses work every time the network eats a
message — and silence tells it nothing, so it cannot even know *which* work
was lost. A client that retries turns every lost *answer* into a possible
duplicate *request*, because the server cannot tell "attempt two of one
intention" from "a second intention" by looking at the bytes. The key moves
exactly one piece of knowledge: **"these sends are the same intention"** —
knowledge that exists only at the client, at the moment of the first send,
and nowhere else in the system.

**1.2** (a) *The answer:* a dedup layer that says "duplicate, discarded"
leaves the caller still ignorant of the outcome — the caller retried
precisely because it doesn't know what happened; only replaying the stored
original response answers the question actually being asked. (b) *Crashes:*
dedup marks "seen" either before the work (crash after marking → the retry
is suppressed and the work never happens) or after it (crash between work
and marking → the retry re-executes). Only a claim that is atomic with the
work — or recoverable evidence plus keyed downstream hops — survives both.

## Rung 2

**2.1** The `ck_1` row; state `SUCCEEDED` (green) with the stored response
beside it. **2.2** The key table — the log says the retry was answered by
replay without contacting the bank or touching orders; no new bank/order
activity appears at that step. **2.3** The request body (amount + item) —
the fingerprint shown is a hash over the request content; the two requests
with identical bodies show identical fingerprints.

## Rung 3

**3.1** 2 charges. Inevitable at the moment the *second* request's check
completes while the first has not yet recorded — from then on both threads
are committed to charging; everything after is bookkeeping. The gap is
between check and act, so the fatal step is the second check, not either
charge.

**3.2** The key table is **empty** — the claim was uncommitted, so the
crash rolled it back; the bank ledger has 1 charge. The retry finds no
row, claims fresh, and charges again → 2 charges. The rollback destroyed
the evidence but not the effect.

**3.3** The claim row survived (committed first), state RUNNING. The
re-drive re-calls the bank *with the same bank-side key*; the bank
recognizes it and **replays** its earlier "OK c_501" instead of charging;
the server completes the outcome transaction. Final: 1 charge.

## Rung 4

**4.1** Improves: the duplicate's client gets its answer in one round trip
(better latency for that caller, no 409 handling needed). Newly at risk:
server threads/connections — every waiting duplicate holds one for the full
duration of the original work, unbounded if the bank is slow. This reenacts
the optimistic-locking lab's **lunch problem**: one slow actor converting
its delay into everyone's resource starvation. (With bounded pools, a burst
of duplicates on a slow key can occupy the whole pool — the networking
lab's green-dashboard death.)

**4.2** Asha taps Pay → request sent with key `ck_a` → response lost → app
shows "Try again" → she taps Pay again → *the press mints a fresh key*
`ck_b` → server sees two intentions → two charges, all correct by contract.
Minting at render time makes both taps of the same screen carry the same
key; a fresh key is minted only when a *new* payment screen (new intention)
is shown.

**4.3** Example: client timeout 30 s, 5 attempts with exponential backoff
(30 s, 60 s, 120 s, 240 s…) — attempt 4 arrives ~3.5 minutes after attempt
1. Or: the request rode a queue that redelivers after a 2-minute visibility
timeout. With 60 s retention the key was purged before the late attempt
arrives → treated as new → executed again. 24 h absorbs any sane retry
horizon. Retention must exceed (max attempts × max backoff + redelivery
delays) with a wide margin.

## Rung 5

| after | key table (`ck_9`) | bank | response sent |
|---|---|---|---|
| e1 | — (arriving) | — | — |
| e2 | RUNNING, fp aa11 | — | — |
| e3 | RUNNING, fp aa11 | c_501 ₹2,000 | — (crash imminent) |
| e4 | RUNNING, fp aa11 (durable) | c_501 | none — connection died |
| e5 | RUNNING, fp aa11 | c_501 | **409 in progress, retry later** |
| e6 | unchanged | c_501 | — |
| e7 | RUNNING, fp aa11 | c_501 | **422 fingerprint mismatch** (fp bb22 ≠ aa11; nothing executes) |
| e8 | **SUCCEEDED**, response stored (re-drive: bank replays c_501; outcome txn commits) | c_501 (still 1) | — (reaper has no client to answer) |
| e9 | SUCCEEDED | c_501 | **200 replay** of the stored response |

Final charge count: **1**. The "incorrect-feeling" answer is **e7**: the
user changed the cart to ₹1,900 and got an error — but the server is right;
`ck_9` names the ₹2,000 intention, which was in fact executed. The app's
bug is reusing a key across intentions; the 422 is the contract catching it
before money moves twice.

## Rung 6

**6.1 Claim committed separately from the local effect.** Run: request A
claims `ck_1` (txn 1 commits), starts business writes, **crashes** before
txn 2. Retry arrives: claim exists → treated as duplicate → 409 forever /
replay of nothing; the effect never happened and never will. The guarantee
violated is §5's requirement 2 — an attempt never learns a real outcome —
and re-driving is now mandatory *even for a purely local effect*, which the
single-transaction v2 got for free. Splitting the claim from a local effect
buys nothing and reintroduces the stuck state; claim-first is only for
effects that *cannot* share the transaction.

**6.2 Redis claim, Postgres effect** — failure 6 embodied, two runs:
(a) claim in Redis succeeds, server crashes before Postgres commit → retry
is suppressed by Redis and replays a stored "outcome" for work that never
happened (requirement 2 violated). (b) Redis restarts (`appendonly no` →
empty): every settled key is forgotten; any late retry re-executes → double
charge (requirement 1 violated). The claim was in a different durable
domain than the effect: the dual-write problem rebuilt at the point of its
own solution.

**6.3 Success-only key rows.** The handler judges "failed" by catching an
exception — but the networking lab's lesson applies to the *bank call
inside* the handler: a timeout is not "did not happen." Run: bank call
times out at 30 s (bank actually charged, reply lost) → handler throws →
stores nothing → client retries with the same key → no row → full
re-execution → **second charge**. Violated: at-most-one-effect, in exactly
the case the mechanism exists for. Failures must settle as FAILED rows
(replayable, like Stripe) — and an *ambiguous* failure (timeout) must not
settle at all until resolved via the keyed downstream or reconciliation;
it stays RUNNING for the reaper.

## Rung 7 (worked design discussion)

- **Claim row:** `payout_id (key), fingerprint, state
  (RUNNING/SENT_UNCONFIRMED/CONFIRMED/HELD), partner_ref?, created_at` — in
  the payouts database, same store as the ledger.
- **Ordering:** commit the claim (RUNNING) first; then the partner call;
  then commit outcome. Immediately before the call, advance state to
  `SENT_UNCONFIRMED` in its own small committed transaction — so the
  evidence distinguishes "never called" from "called, outcome unknown."
- **Reaper rules:** a stuck `RUNNING` (crash before the call) is provably
  unsent → safe to re-drive the call. A stuck `SENT_UNCONFIRMED` is the
  irreducible doubt → the reaper must **not** re-call (that risks
  double-pay); it parks the payout as `HELD` for reconciliation. This is
  the at-most-once choice.
- **Reconciliation:** compare partner statements/reports against
  `SENT_UNCONFIRMED`/`HELD` and the ledger, keyed by amount + account +
  your reference in the transfer narration (the closest thing to a key you
  can smuggle in). Matches promote to CONFIRMED; misses release the HELD
  payout for a fresh, *human-approved* send.
- **Dashboard residual:** count and age of HELD payouts, plus
  reconciliation mismatch count — the honest number that replaces
  pretended exactly-once.
- **What's given up:** §5 requirement 1 is kept *by sacrificing liveness*
  (payouts can stall in HELD awaiting reconciliation) — for payouts,
  under-pay-late is a support ticket and an apology; over-pay is money
  gone to clawback. At-most-once per hop + reconciliation is the correct
  preference. (A marketing-email hop would choose the opposite:
  at-least-once, duplicates shrugged off.)

## Rung 8 (worked design discussion)

- **Keys:** app mints `checkout_key` at checkout-screen render, persists it
  locally with the pending intention, reuses across retries and app
  restarts. Server derives per-step keys: `<key>-charge` → Stripe's
  `Idempotency-Key`; `<key>-seat` → the seat reservation (internal, but
  keyed so the step is re-drivable); email gets no key (none exists).
- **Schema:** `idempotency_keys(key, fingerprint, state, recovery_point
  ∈ {started, seat_reserved, charged, emailed}, response, created_at)` in
  the orders database.
- **Step order — reserve seat before charging.** Both orders can be made
  correct (both steps are keyed and re-drivable), so choose by *cost of the
  interrupted state*: seat-then-charge leaves a seat held for a customer
  whose card then fails — release it (cheap, internal, a compensation in
  the saga-lab sense). Charge-then-seat can take money for a sold-out seat
  → refund (slow, visible, finance-touching). Reserve with a TTL hold
  (uber-lab offer/lease shape), charge, then confirm the hold.
- **Concurrency/crash:** duplicate on RUNNING → 409 + Retry-After;
  duplicate on settled → replay with `Idempotent-Replayed: true`; wrong
  fingerprint → 422. Crash anywhere → reaper re-drives from
  `recovery_point`: seat hold re-asserted by key (idempotent re-reserve),
  Stripe re-called with the same derived key (Stripe replays), email step —
  see below. Every recovery-point advance is its own small committed
  transaction.
- **Retention:** key table 24–48 h (≫ any retry horizon; matches Stripe's
  window so your replay horizon isn't longer than your downstream's);
  Stripe's own 24 h noted as a dependency. Seat holds: minutes (TTL).
- **Email (box G):** choose at-least-once — a duplicate ticket email is
  harmless, a missing one is a support call. Record `emailed` *after* the
  SMTP accept; a crash between send and record re-sends on re-drive.
  Best-effort dedup accelerator (Redis SETNX on `<key>-email`, short TTL)
  cuts routine duplicates without being trusted. Residual duplicate-email
  rate is measured, not denied.
- **Metrics:** replay rate; 409 rate; **oldest-RUNNING age (the page)**;
  fingerprint-mismatch rate; Stripe-replay-on-re-drive count (how often
  crashes actually bit); plus reconciliation delta of Stripe charges vs
  local orders.
- **Honest residual failure modes:** (1) duplicate emails (chosen);
  (2) cross-key duplicates — a user who abandons checkout, re-opens a fresh
  checkout screen, and buys again holds two legitimate keys; only the
  advisory heuristic layer ("you already have a ticket — continue?") can
  catch that, and it is a guess by design.
