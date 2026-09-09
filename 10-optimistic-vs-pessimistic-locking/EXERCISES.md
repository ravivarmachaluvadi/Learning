# Exercises — optimistic vs pessimistic locking

Climb in order; each rung uses the previous. Answers in
[SOLUTIONS.md](SOLUTIONS.md) — commit to yours first (write it down —
"I'd have said that" is not a commitment).

## Rung 1 — Explain

1.1 In your own words, without the words "optimistic" or "pessimistic":
what problem do both strategies solve, and why can no isolation level —
including SERIALIZABLE — solve it?

1.2 "Optimistic locking" contains no lock. What does it contain instead,
and what single question can that thing answer?

1.3 Your teammate says: "we use Postgres with MVCC, so we already have
optimistic locking." Untangle this in three sentences.

## Rung 2 — Identify

2.1 In [Store.java](src/Store.java), point at: (a) the one line that makes
two concurrent optimistic saves unable to both succeed, (b) the line where
a pessimistic transaction stops being alone on the row, (c) the line whose
deletion creates planted bug #1.

2.2 In the visualization, run the *lunch break* scenario under pessimistic
locking. Which client holds the lock, what exactly are the other five
doing, and what number on screen is the "cost transferred to neighbours"?

2.3 In experiment `queue`'s `for-update` mode, all four workers show
27–33 jobs each — work was shared almost evenly. So why is it still 4×
slower than `skip-locked`?

## Rung 3 — Predict (then run)

3.1 Meera's app uses `optimisticSave` faithfully. A data-fix script runs
`UPDATE product SET price = 1400 WHERE id = 42` (no version clause). Does
Meera's next save (from a form loaded before the script ran) succeed, fail,
or corrupt? Decide, then reason it out from the UPDATE in §10.1 — then
check with any test DB or by reading `optimisticSave`.

3.2 In `Experiments.java waste`, the work per decision is 15ms and 8
clients fight over one row. Before looking: roughly how much decision-work
gets *redone* in total, and why doesn't the wall clock show it?
Run `java -cp ../lib/h2.jar src/Experiments.java waste` to check.

3.3 Two transactions both run `SELECT … FOR UPDATE` on row A, then on
row B, *in that same order*. Can they deadlock on A and B? Then: transaction
1 also touches row C first, which transaction 2 never touches. Now?

3.4 If every client in the `crossover` experiment used
`UPDATE counter SET n = n + 1 WHERE id = ?` (no read, no version, no
FOR UPDATE) — what happens to correctness, retries, and time? Which lab
already measured this?

## Rung 4 — Modify

4.1 In `Experiments.java lunch`, raise the fast clients' `LOCK_TIMEOUT`
from 500 to 6000. Predict the two numbers in the PESSIMISTIC row, then run.
What did the timeout setting actually choose between (it did not choose
"success")?

4.2 In `Experiments.java queue`, change the `for-update` claim query to
`ORDER BY RAND()` (H2: `ORDER BY RAND()` — leave `skip-locked` alone).
Predict: does the convoy dissolve? What property did you pay, and when
would a real queue refuse to pay it?

4.3 Make bug #2 (`no-version-bump`) visible to a test that currently
passes: which existing test in `Tests.java` would catch it if run with the
bug on, and why does the `broken` mode use a two-writer script instead of
the 8×40 race?

## Rung 5 — Implement

`java -cp ../lib/h2.jar src/YourStrategy.java` starts at 2/6. Implement
`yourOptimisticSave` and `yourClaimJob` (rules in the file: no `FOR UPDATE`)
until 6/6. Then answer: your claim loop and `Store.optimisticUpdate` retry
for different reasons — what is each one's "someone got there first" signal?

## Rung 6 — Debug

Run `java -cp ../lib/h2.jar src/Tests.java broken`. For each planted bug:
(a) say which innocent-looking code-review diff would introduce it,
(b) which guarantee from §12's table it breaks,
(c) why an integration test with a single user would never catch it.
Rank the three by how long each would live in production before being
noticed, and justify the ranking.

## Rung 7 — Handle failure

The §15 flowchart's bottom-right box — the *pessimistic offline lock* (CMS
article checked out for an hour) — doesn't exist in this lab. Design and
implement it on H2:

- a `claims(article_id, owner, expires_at, generation)` table;
- `claim(article, owner)` — succeeds if unclaimed *or expired*;
- `save(article, owner, generation)` — succeeds only for the live claimant;
- the hard part: owner A's claim expires while A is still editing; B
  claims; A returns and hits Save. What exactly must happen, and which
  column makes it happen? (You met this device in the redis and
  linearizability labs under another name.)

Write it as a small `OfflineLock.java` with a `main` that scripts exactly
that story and prints the verdicts. What must claim/save themselves use
internally — optimistic or pessimistic locking — and why?

## Rung 8 — Design

You own concurrency strategy for a payments platform: a merchant dashboard
(humans editing product/settings forms), a ledger (append-only,
expected-version — the event-sourcing lab's shape), hot wallet balances
(thousands of debits/min on a few hundred rows, each debit must check
`balance >= amount`), and a payout worker fleet (10k queued payouts/hour,
20 workers).

For each of the four: choose the mechanism, name the failure mode you
accepted, and name the metric that tells you at 3am that the choice is
going wrong. Then two cross-cutting questions: (1) where, if anywhere, does
a database constraint replace all of it; (2) the platform adds a read
replica and someone routes the dashboard's edit-page loads to it — what
breaks, and what is the smallest fix?
