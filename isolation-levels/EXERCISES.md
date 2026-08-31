# Exercises — isolation levels

Eight rungs. Rungs 1–3 need only the README and the visualization. Rungs 4–7 need
the code. Rung 8 needs a whiteboard.

Answers in [SOLUTIONS.md](SOLUTIONS.md). Attempt first — especially the
predictions, where being wrong is the point.

Shorthand: `T1 read alice → 100` means transaction 1 read Alice's balance and
got 100. Levels are abbreviated `RU · RC · RR · SNAP · SER`.

---

## Rung 1 — Explain

**1.1** A colleague wraps the deposit code in `BEGIN … COMMIT` and closes the
bug. In three sentences, explain what they have fixed and what they have not.

**1.2** Explain, with no notation, what "serializable" means and why the answer
is not simply "run the transactions one at a time".

**1.3** One sentence each, separating:
isolation from atomicity · isolation from linearizability ·
snapshot isolation from serializable · optimistic locking from
first-committer-wins.

**1.4** Why is write skew harder to prevent than lost update? Answer in terms of
what the database can *see*, not in terms of isolation levels.

**1.5** Your database refuses a transaction with SQLSTATE `40001`. Is that an
error, a warning, or a feature? Justify it in one sentence you could say to a
product manager.

---

## Rung 2 — Identify

**2.1** Name the anomaly in each schedule:

```
(a) T1 read alice → 100 · T2 set alice = 200 · T2 COMMIT · T1 read alice → 200 · T1 COMMIT
(b) T1 read alice → 100 · T2 read alice → 100 · T1 set alice = 110 · T2 set alice = 110 · both COMMIT
(c) T1 count on-call → 2 · T2 count on-call → 2 · T1 sets doc1 off · T2 sets doc2 off · both COMMIT
(d) T1 read alice → 100 · T2 transfer 50 alice→bob · T2 COMMIT · T1 read bob → 150 · T1 COMMIT
(e) T1 set alice = 999 · T2 read alice → 999 · T1 ROLLBACK
```

**2.2** Open the visualization on *Both doctors go off call* at **Snapshot**.
Point at the two versions each clerk is reading. Then answer: the engine's
first-committer-wins check runs at commit — why does it not fire for either
clerk?

**2.3** In [`src/MiniDb.java`](src/MiniDb.java), find:
- the single expression that decides read committed versus a snapshot,
- the loop that implements first-committer-wins,
- the only place in the whole engine where a **read** is written down,
- the two-line rule that decides who gets refused under SSI.

**2.4** In the H2 matrix, list every cell marked `safe(err)`. What one thing must
the application have, for every one of those cells, before the guarantee is real?

---

## Rung 3 — Predict

Write your answer down before running anything.

**3.1** You keep `READ COMMITTED` and change
`SELECT balance; UPDATE SET balance = <v+10>` into the single statement
`UPDATE SET balance = balance + 10`. Out of 320 increments across 8 threads,
how many survive, and how many retries does it cost?
Check: `java -cp ../lib/h2.jar src/Experiments.java increment`

**3.2** Predict the whole `write-skew` row of the H2 matrix — all five levels —
before running `java -cp ../lib/h2.jar src/Matrix.java`. If you got it wrong,
write one sentence about what that means for reading vendor documentation.

**3.3** In experiment 2, the same 320 units of work are spread across more and
more rows, at a fixed isolation level. Predict the retry count at 1 row and at
16 rows, to the nearest order of magnitude.

**3.4** Predict the verdict of the *double-booking* scenario at all five toy
levels, then run `java src/MiniLab.java double-booking`. Which level first stops
it, and what is it doing that the level below cannot?

**3.5** In the visualization, load *A deposit that vanishes* at read committed,
then use ▲ to move `T1 COMMIT` above `T2`'s read. Predict the verdict before you
release the button.

---

## Rung 4 — Modify

**4.1** In `MiniDb.noteReadWriteConflicts`, add `if (true) return;` as the first
line, then run `java src/MiniLab.java`. Exactly one cell of the matrix changes.
Predict which one first. What has `SERIALIZABLE(SSI)` become?

**4.2** In `MiniDb.readTs`, replace the body with `return t.startTs;` — so read
committed now reads from its own start timestamp. Run the matrix. Three cells
change and one conspicuously does not. Which, and why does that one not move?

**4.3** In the read-skew scenario the transfer is *already* one atomic
transaction, and read skew happens anyway. Explain why making the writer more
atomic can never fix it, and name the two things that can.

**4.4** Set `Runner.stepTimeoutMs = 1` and re-run the H2 matrix. Which
distinction becomes unreliable, and which stays completely trustworthy? What
does that tell you about which parts of the table you could quote in a design
review from a run on a loaded laptop?

---

## Rung 5 — Implement

**5.1** Add `SELECT … FOR UPDATE` to the toy engine: a method
`getForUpdate(Tx, key)` that takes an exclusive lock **at every level**, not
just under 2PL. Add a scenario proving it fixes lost update at
`READ_COMMITTED`, and add the expected row to `Tests.MINI_EXPECTED`. Compare
your result with the H2 row `Lost update, with SELECT ... FOR UPDATE`.

**5.2** Add a `UNIQUE`-style constraint to the toy engine: a check at commit
that no two live rows match a given predicate. Add a *double-booking* variant
that uses it, and show it is `safe` at **every** level, including read
uncommitted. Then write two sentences on when a constraint beats an isolation
level.

**5.3** Give `Retry` an exponential backoff with a cap and a jittered first
sleep, and record attempts-per-success. Re-run experiment 2 and say what the
backoff did to the wall-clock time at 1 hot row, and what it did at 16.

---

## Rung 6 — Debug

**6.1** In `MiniDb.commit`, change the first-committer-wins test from
`v.begin > t.startTs` to `v.begin >= t.startTs`. Run the matrix and the tests.
**Nothing changes.** Explain precisely why the altered branch is unreachable,
and then either write a test that would catch it or argue that no such test can
exist in this engine.

**6.2** In `MiniDb.isPivot`, drop the `status != Status.ABORTED` filter from both
lines. Two tests fail. What is the final state of the doctors table now, and why
is this a **waste** rather than a **safety bug**? What would the equivalent
mistake cost in a real database under load?

**6.3** In `MiniDb.blockIfSomeoneElseIsWriting`, change `u.status ==
Status.ACTIVE` to `u.status == Status.COMMITTED`. Three tests fail. Which
anomaly has been unlocked, and why is it the one anomaly that every isolation
standard says must be prevented at every level?

**6.4** A colleague says: *"We switched to SERIALIZABLE and now transactions
randomly fail with 40001 under load. The database is broken — we're rolling
back."* Write the reply. Include what they should measure before changing
anything.

---

## Rung 7 — Handle failure

**7.1** `Retry.inTransaction` retries the **whole** transaction. Someone
proposes retrying only the statement that failed, to save work. Construct a
concrete two-transaction example where that produces a wrong answer, and state
the general rule it violates.

**7.2** Your retry loop has a budget of 20 attempts and a transaction exhausts
it. Raising the budget is the obvious move and it is wrong. Give three better
responses, in the order you would try them, and say what each one costs.

**7.3** A nightly job takes `SELECT … FOR UPDATE` on two accounts per transfer
and deadlocks under load. Fix it **without** changing the isolation level, and
prove your fix with `java -cp ../lib/h2.jar src/Experiments.java deadlock`.

**7.4** You raise a report to `REPEATABLE READ` and it now runs for 40 minutes
over month-end. A week later the DBA says the database has doubled in size and
queries are slower. Explain the connection, and give the two settings you would
look at.

---

## Rung 8 — Design

**8.1 — A payments ledger.** For each of these, choose the isolation level *and*
the mechanism, and justify the cost:

- checking a balance and debiting it, in one request;
- the idempotency-key check that decides whether a payment has already been
  taken;
- the monthly statement, reading tens of thousands of rows;
- the rule *"a customer may have at most one active subscription"*;
- a promotion code valid for its first 1,000 redemptions;
- the fraud model's feature lookup on the hot path.

Then: which of these would you protect with a database **constraint** instead,
and why is that usually the better answer?

**8.2 — Two engines.** Your product must run on PostgreSQL and MySQL/InnoDB.
Which of the assumptions you made in 8.1 stop being true when you move between
them? Name at least three concrete differences and say what you would do about
each.

**8.3 — Not an isolation problem.** Name a case in a payments system where the
right answer is *not* to raise the isolation level at all, even though the
symptom looks exactly like an isolation bug. Say what you would do instead and
what you would give up.

**8.4 — Prove it.** You have inherited a production database and nobody knows
what its isolation behaviour really is. Describe, in five steps, how you would
find out — and what you would put in the runbook afterwards.
