# Idempotency — exercises

All pencil-and-paper or visualization work; nothing to code. Attempt each
before opening [`SOLUTIONS.md`](SOLUTIONS.md). "The viz" =
[`visualization.html`](visualization.html); designs A–D are §10's
v-nothing/v1/v2/v4.

## Rung 1 — Explain

1.1 In your own words, no jargon: why is a client that never retries
unreliable, and why is a client that retries dangerous? What single piece of
knowledge does the idempotency key move from the client to the server?

1.2 A colleague says: "We deduplicate requests, so we're idempotent."
Explain the two things a dedup check alone does not give you (one is about
the answer the caller gets, one is about crashes).

## Rung 2 — Identify

Open the viz, design **D**, run scenario 1 to the end, then answer from the
screen alone:

2.1 Which row in the server's key table is the claim, and what state is it
in when the verdict turns green?

2.2 When Asha's retry is answered, which component did the answer come
from — the bank, the orders table, or the key table? How can you tell from
the event log?

2.3 Find the request fingerprint on screen. What two inputs produced it?

## Rung 3 — Predict

3.1 Design **B** (check-then-act). Asha double-taps: two requests with key
`ck_1` arrive 1 step apart. Predict the final charge count and *the exact
step* at which the outcome became inevitable. Check with scenario 2.

3.2 Design **C** (atomic claim, single transaction), effect set to
**external bank call**. The server crashes after the bank confirms but
before commit. Predict: what does the key table contain after the crash,
and what does Asha's retry do? Check with scenario 4.

3.3 Design **D**. Same crash as 3.2. Predict what the reaper's re-drive
does at the bank, and the final charge count. Check with scenario 5.

## Rung 4 — Modify

4.1 In design **D**, suppose duplicates arriving on a RUNNING key *wait*
for the outcome instead of getting 409. Which property improves, which
resource is newly at risk, and which prior lab's measured failure does the
waiting version reenact?

4.2 Change the client so it mints the idempotency key when the Pay button
is *pressed* instead of when the payment screen is *rendered*. Construct
the user story where this one-line change causes a double charge.

4.3 Shorten key retention from 24 hours to 60 seconds. Name a concrete,
realistic client behavior (numbers: timeouts, attempt counts, redelivery
delays) that now produces a duplicate charge that 24 h would have absorbed.

## Rung 5 — Trace

Design **D** semantics, by hand. Starting state: empty key table, empty
orders, empty bank ledger. Events, in order:

```
e1  request (key ck_9, fp aa11, amount 2000) arrives
e2  server commits claim txn
e3  server calls bank (bank_key ck_9-charge); bank charges, replies OK c_501
e4  SERVER CRASHES
e5  request (key ck_9, fp aa11) arrives again           ← Asha's retry
e6  server restarts
e7  request (key ck_9, fp bb22, amount 1900) arrives    ← app re-priced, reused key!
e8  reaper runs (threshold exceeded), re-drives ck_9
e9  request (key ck_9, fp aa11) arrives again
```

For each event write: key-table row(s) after it, bank ledger, and the HTTP
response (if any) sent to the client. Then state the final charge count and
which event was answered "incorrectly" from the *user's* point of view even
though the server followed the contract.

## Rung 6 — Spot the flaw

Each design below contains one planted mistake. For each: construct a
concrete run (named requests, interleaved steps) where it produces a wrong
outcome, and name the violated guarantee.

6.1 *"We INSERT the key row with a unique constraint — atomic claim, done
right — then do the local business writes. For speed, the claim INSERT uses
its own small transaction that commits immediately, and the business writes
follow in a second transaction."* (Effect is 100% local database writes.)

6.2 *"Keys live in Redis: `SET key outcome NX EX 86400`. On duplicate we
return the cached outcome. The order writes go to Postgres. Redis is
configured with appendonly no — it's just dedup data."*

6.3 *"To keep the table small we store the key row only on success. Failed
attempts store nothing, so the client can simply retry a failure with the
same key."* (Effect includes the external bank call; "failed" is judged by
the server's own handler catching any exception.)

## Rung 7 — Handle failure

The bottom hop strikes back: your payout service must call a partner bank
whose API has **no idempotency support** — a bare `POST /transfer` that
moves money every time it is called. You cannot change the partner.

Design, on paper, the safest achievable payout pipeline: what your claim
row looks like, when you call the partner relative to your transactions,
what the reaper is and is not allowed to do with a stuck RUNNING payout,
what the reconciliation job compares, and what number you put on a
dashboard as the honest residual. State explicitly which requirement from
§5 you are giving up and which failure (over-pay or under-pay/late-pay) you
chose to prefer, and why that is the right preference for payouts.

## Rung 8 — Design

Ticket checkout at production scale: `POST /checkout` must (a) charge the
card via Stripe, (b) reserve a seat in your seats database, (c) email the
ticket via a legacy SMTP relay (no dedup of any kind), at ~500 checkouts/s,
clients are flaky mobile apps, and finance requires provable
at-most-one-charge per checkout.

Produce: the key flow end to end (who mints what, which hops carry which
derived keys); the key-table schema including recovery points and the step
order (justify which of charge/reserve comes first); the concurrent-
duplicate and crash-at-every-step behavior; retention choices per store;
the box-G treatment for the email hop; and the five dashboard metrics with
the one you page on. Close with the two failure modes your design still
permits, stated honestly.
