# Solutions

Attempt each rung first — [EXERCISES.md](EXERCISES.md).

## Rung 1

**1.1** Two writers each read a record, think, and write back — and the
think time crosses transaction boundaries (a form, an API call-out, a
human). Each writer's transactions are individually harmless: the write
transaction is a lone UPDATE that read nothing. Isolation levels order
*transactions*; here every ordering of these transactions produces the same
loss, so even a perfectly serializable database executes the overwrite
faithfully. The read-write link the database would need to see exists only
in the application's memory — so the application must carry it (a version)
or prevent the interleaving (a lock it manages itself).

**1.2** A number that changes on every write — carried out of the read as
plain data. It answers exactly one question: *is the row still the one I
read?* (Not "what changed", not "who changed it" — no history, one bit.)

**1.3** MVCC is the engine keeping multiple versions so readers don't block
writers — it's invisible plumbing and protects nothing across requests.
Optimistic locking is an application protocol: your schema, your WHERE
clause, your retry. Related only in spirit: snapshot isolation's
first-committer-wins applies *within* concurrent transactions, while your
version column enforces the same rule across *conversations* — Postgres
gives you the first automatically and cannot give you the second.

## Rung 2

**2.1** (a) [Store.java:96-97](src/Store.java#L96-L97) — the update-count
check (with the atomicity supplied by the conditional UPDATE being a single
statement). (b) `c.commit()` at the end of `pessimisticUpdate` — the lock
dies there; until then the transaction is alone. (c) The same lines as (a):
`if (count == 0) throw …` — bug #1 is its absence.

**2.2** The sleeper holds it (padlock on the row, holder's colour). The
other five are in the row's lock queue (or erroring out at their timeout) —
their state is "waiting", not "failed" until the budget runs out. The
neighbour-cost figure is the timeout/zero-saves counter — 15 timeouts, 0
saves in the measured run.

**2.3** Because the sharing happened *serially*. Each worker's claim
blocked until the current holder finished *processing* (the lock was held
through the 10ms of work), then the next waiter got the next job. Fair
distribution, single-file execution: 27–33 each, but one at a time — the
definition of a convoy. Throughput, not fairness, is what SKIP LOCKED buys.

## Rung 3

**3.1** Corrupt — in the worst way. The raw UPDATE didn't bump `version`
(there is no trigger), so Meera's stale save still matches `version=0`:
it *succeeds* and silently reverts the data-fix's price. This is bug #2
performed by hand, and it's why §12's last table row exists: the protocol
only binds writers who follow it. (Mitigations: a trigger bumping version
on every write, or admin tooling that goes through the same repository
code.)

**3.2** ~280 retries × 15ms ≈ 4.2 *seconds* of redone decisions inside a
~1.2-second wall clock — the spare cores run the doomed attempts in
parallel with the doomed attempts of others. Measured: `301 retries =
~4515ms redone`, wall 1214ms vs pessimistic 1209ms. The bill is CPU, not
latency — until the machine has no spare CPU.

**3.3** Same order on A and B → no deadlock between those two ever: whoever
holds A wins B's race too; a cycle needs each side holding something the
other wants. Adding C (touched only by transaction 1, *before* A) changes
nothing between these two — the cycle still requires opposite ordering on
some *shared* pair. (It can matter with a third transaction touching C and
A in reverse — deadlock freedom is a property of the *global* order over
shared resources.)

**3.4** Correctness: perfect (320/320). Retries: zero — there is nothing
to retry; the statement reads *after* taking the row lock, so it cannot act
on stale data. Time: near the floor. The isolation-levels lab measured
exactly this: `UPDATE SET n = n + 1` at 320/320, 0 retries, 26ms — the
"neither strategy" arrow in §15's flowchart. Both locking strategies exist
for decisions that *don't* fit in one statement.

## Rung 4

**4.1** Saves during the pause stay ~0 (the lock is simply held — no
timeout setting manufactures access to a locked row); timeouts drop to ~0
because 6000ms outlives the 2s pause — the waiters queue silently and land
*after* the sleeper commits. The knob chose between **erroring at 500ms and
waiting the full pause** — i.e., which bad experience the five users get,
error page vs frozen page. (Availability shifted shape, not size.)

**4.2** Mostly — workers scatter across ~120 pending rows, collisions
become occasional, throughput approaches skip-locked's (minus the cost of
`RAND()` sorting a scan). Paid: any pretense of FIFO — old jobs can
languish (unbounded unfairness, not just reordering). A real queue refuses
when jobs have deadlines/SLAs or ordering semantics (per-key sequencing, a
payment's steps). SKIP LOCKED keeps *approximate* order for free, which is
why it's the answer rather than randomization.

**4.3** `optimistic_race_8x40_converges_to_320` would catch it — with
first-committer-wins broken, concurrent incrementers overwrite each other
and the total lands well below 320. The broken mode uses the two-writer
script because it *isolates the mechanism*: it shows the second stale save
being *accepted* (staleAccepted=true) — a precise witness of "the version
no longer advances" — where the race would only show a wrong total that
all three bugs (and twenty other mistakes) can produce.

## Rung 5

Working bodies (verified 6/6 in this lab):

```java
static void yourOptimisticSave(Connection c, Store.Product p) throws SQLException {
    try (PreparedStatement u = c.prepareStatement(
            "UPDATE product SET description=?, price=?, version=version+1 " +
            "WHERE id=? AND version=?")) {
        u.setString(1, p.description());
        u.setInt(2, p.price());
        u.setInt(3, p.id());
        u.setLong(4, p.version());
        if (u.executeUpdate() == 0) throw new Store.StaleVersionException(p.id(), p.version());
    }
}

static int yourClaimJob(Connection c, String worker) throws SQLException {
    while (true) {
        int id;
        try (ResultSet r = c.createStatement().executeQuery(
                "SELECT id FROM job WHERE status='PENDING' ORDER BY id LIMIT 1")) {
            if (!r.next()) { c.commit(); return -1; }
            id = r.getInt(1);
        }
        int n = c.createStatement().executeUpdate(
            "UPDATE job SET status='RUNNING', worker='" + worker +
            "' WHERE id=" + id + " AND status='PENDING'");
        c.commit();
        if (n == 1) return id;   // count 0: lost the race — try the NEXT candidate
    }
}
```

The signals: `optimisticUpdate`'s is a **version mismatch** on the same
row (someone changed *the data I'm editing* — retry the same row);
the claim loop's is a **state-transition miss** (`status` left `PENDING` —
someone took *this job*, so retry means *a different row*). Same
conditional-write mechanic, opposite retry targets — the status column is a
version column whose values have names.

## Rung 6

- **no-count-check** — diff: "simplify: we don't use the return value" on
  `executeUpdate()`. Breaks: *conflict is detected* (row 1 of §12 holds
  only "if count is checked"). Single-user tests: no concurrent writer, so
  count is always 1 and behaviour is identical.
- **no-version-bump** — diff: "perf: drop the pointless self-increment" or
  a hand-written UPDATE that forgot it. Breaks: first-committer-wins — all
  stale committers win. Single-user: your own saves never race, versions
  never *need* to advance.
- **stale-reapply** — diff: a "retry helper" that caches the computed
  entity and re-saves it after refreshing the version (often born as a fix
  for "version conflict spam" in logs). Breaks: re-decide — the write
  contains decisions made against dead data. Single-user: retries never
  trigger.

Longevity ranking (defensible version): **no-version-bump** longest — it
needs *two* stale writers to overlap to do damage, and its symptom (an
overwritten edit) looks exactly like a user mistake; **no-count-check**
next — damage from any single conflict, but the symptom ("my save didn't
stick") at least generates support tickets; **stale-reapply** shortest —
it needs contention to fire, but where it fires it mangles data
*repeatedly and mechanically* (40/320 survivors), which shows up in sums
and audits fast. Any ranking is fine if it argues from (frequency of
trigger) × (visibility of symptom).

## Rung 7 (worked design)

Schema: `claims(article_id PK, owner, expires_at TIMESTAMP, generation BIGINT)`.

- `claim(a, o)`: one conditional write —
  `UPDATE claims SET owner=?, expires_at=NOW()+INTERVAL '15' MINUTE,
  generation=generation+1 WHERE article_id=? AND (owner IS NULL OR
  expires_at < NOW())` (insert the row on first use). Count 1 → you hold
  the claim **and its new generation**, which you keep client-side.
- `save(a, o, g)`: `UPDATE articles SET … WHERE id=? AND EXISTS (SELECT 1
  FROM claims WHERE article_id=? AND owner=? AND generation=? AND
  expires_at >= NOW())` — or check the claim row first with the same
  conditions, in the same transaction.
- **The A-returns-late story**: A claimed at generation 7; expiry passed; B
  claimed → generation 8. A's Save presents (owner=A, generation=7): the
  condition fails on *generation*, regardless of any timestamp trickery or
  whether A re-claims later. A gets "your lock expired and Sara took over —
  here's a diff of your text to merge by hand." The generation is a
  **fencing token** (redis lab: the number the *resource* checks so a
  paused ex-holder can't act on a stale claim; linearizability lab: same
  device). `expires_at` alone is insufficient — A's save could race the
  expiry clock; the generation makes stale authority *structurally*
  unusable.
- Internals: **optimistic** conditional writes — a claims table managed
  with FOR UPDATE would reintroduce §4 (the claim transaction would have
  to span the edit). The whole point of the offline lock is that the
  "lock" is ordinary data; the only real locks are the row locks inside
  each single-statement claim/save, held for microseconds.

## Rung 8 (worked discussion)

- **Dashboard forms** — optimistic offline: `@Version` round-tripped
  through the form; conflict → merge UI. Accepted failure: occasional
  "someone else edited" friction. 3am metric: conflict rate per entity —
  a spike on one product = a bot or an integration writing raw UPDATEs
  (rung 3.1's corruption, live).
- **Ledger** — expected-version append (optimistic; the event-sourcing
  shape). Conflict = re-derive the decision, never re-send. Accepted:
  retries under bursts on one account. Metric: append-retry tail per
  stream; a hot stream's growing tail is §11f starvation and wants
  escalation or stream sharding.
- **Wallet debits** — neither strategy where a statement suffices:
  `UPDATE wallet SET balance = balance - ? WHERE id = ? AND balance >= ?`,
  count 0 = insufficient funds, plus `CHECK (balance >= 0)` as the
  constitutional backstop. If the decision outgrows one statement
  (multi-row risk checks), pessimistic `FOR UPDATE` in a *short*
  transaction with a global lock order (wallet id ascending), never
  spanning any external call. Accepted: hot-wallet serialization —
  by design. Metric: lock wait time on wallet rows; a cliff with idle CPU
  = a hold-time regression (someone added an API call inside the lock).
- **Payout workers** — `FOR UPDATE SKIP LOCKED`, claim + mark RUNNING in
  one short transaction, process *outside* it, idempotency key on the
  transfer (transactional-outbox lab: a timeout is not a failure).
  Accepted: approximate ordering, and crashed-worker jobs stuck RUNNING —
  so a reaper re-pends stale RUNNING rows, and the idempotency key makes
  the reaper safe. Metric: oldest PENDING age + RUNNING-without-heartbeat
  count.
- **(1) Constraints replace strategy** wherever the rule is about the
  *data*: non-negative balances, `UNIQUE (merchant, idempotency_key)` on
  payouts — those hold against every writer including the migration
  script. Strategies remain for rules about *decisions* (approvals, risk
  checks) the schema can't state.
- **(2) The replica** — edit pages now read stale rows *with stale
  versions*: every save against a row that changed since replication lag
  ago fails, support tickets say "it never saves". (Data stays safe —
  false conflicts, not corruption: the conditional write still executes on
  the primary. The failure is availability/UX, plus rung-3.1-style danger
  only if someone "fixes" it by dropping the check.) Smallest fix: pin
  edit-page loads (reads that feed writes) to the primary — read-your-writes
  routing, the quorums lab's lesson that staleness fixes are *routing*
  fixes.
