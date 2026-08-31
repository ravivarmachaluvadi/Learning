# ACID — solutions

Attempt `EXERCISES.md` first. Rungs 7–8 are worked discussions, not single
answers — judge yours by whether it names the same tradeoffs, not by wording.

---

## Rung 1 — Explain

**1.1** A program writing to a file can be interrupted halfway and leave the
file in a state that looks perfectly normal but is wrong, with nothing left
anywhere to say what was in progress. A database has to survive that, because
the thing it's changing is often two separate numbers that must always agree.
The idea that solves it: **write down what you're about to do, somewhere that
survives, before you do it** — then whoever comes along after the interruption
can either finish the job or take it back.

**1.2** Being fast changes how *often* you land in the gap between the two
updates; it never removes the gap. At any real write rate, "rare" happens
several times a day — and each occurrence is silent.

**1.3** Because "committed" doesn't mean "the data is in its final place". It
means the *record of what to do* is somewhere that survives. The tidy version
of the data gets written later; if the machine dies first, the record is enough
to reproduce it exactly. It's the difference between "the cheque has cleared"
and "the money has been physically moved to the right shelf".

## Rung 2 — Identify

**2.1** (a) `kill -9`: everything except the buffer pool and the log buffer —
so the OS page cache, the device cache and the disk all survive. (b) power cut:
only the disk band survives.

**2.2** The stamp is the page's **pageLSN** — the LSN of the most recent log
record already reflected in that page. Recovery uses it for exactly one
decision: *apply this log record, or skip it* (`record.LSN > page.pageLSN`).
That single comparison is what makes redo safe to run any number of times.

**2.3** The **after-image** finishes the job (redo). The **before-image** takes
it back (undo). Both are on the same record, which is why steal + no-force is
affordable.

**2.4** Recovery wrote the `CLR` line(s) and the `END` line. They must be in the
log because recovery can itself be interrupted: the CLR is the only evidence
that a particular piece of undo already happened, and `END` is the only evidence
that a rollback finished. Without them, the next recovery would start the
rollback again from the beginning.

## Rung 3 — Predict

**3.1** **Analysis only** changes anything internally; redo and undo each do
nothing visible. Redo checks both pages, sees each page's stamp is already at
or beyond the record's LSN, and skips. Undo has no losers. This is the fastest
possible recovery, and it is what a clean checkpoint just before the crash buys
you.

**3.2** It is completely legal, and it is the *whole point of steal*: page 5
carries an uncommitted change. Recovery's analysis marks T1 a loser (no commit
record), redo re-applies #102 (skipped here — the stamp already matches), and
undo reverts page 5 to ₹700 using the before-image, writing a CLR. Total
afterwards: **₹1000**.

**3.3** **Yes, it re-applies them** — and then undoes them moments later. The
reason is not efficiency, it's simplicity: by *repeating history*, the undo pass
always begins from a state identical to the pre-crash state, which is exactly
the state ordinary runtime rollback begins from. One undo implementation serves
both cases with no special-casing. Skipping losers in redo would force undo to
ask "is this change even here?" on every record.

**3.4** **Process kill:** the transfer survives. The commit record was handed
to the out-tray (`write()`), and the out-tray is the kernel's, not the process's
— so recovery finds it. The lying safe is irrelevant here because nothing had to
be sealed. **Power cut:** the transfer is lost, and the verdict says
*Durability broken*, because the client was told "done". Two switches, both
"unsafe", and only one of them bites in each failure — which is the entire
argument of README §12.3.

## Rung 4 — Modify

**4.1 Day-book off.** A crash between the two page writes leaves ₹500 and no
trace that the two changes belonged together. **Atomicity**, lost **silently** —
no error at crash time, none at startup, none at read time. The books are simply
wrong forever. This is §3's naive design and it is why the log exists.

**4.2 Stealing off.** Nothing breaks *in this simulation*, and that is the
lesson: correctness is fine, capacity is not. A transaction can now never modify
more data than fits in the buffer pool, and a long transaction makes its pages
unevictable for its whole lifetime, starving everyone else. No production engine
chooses it because "your `UPDATE` must fit in RAM" is not a database.

**4.3 Force on.** It buys the ability to **delete the redo pass entirely** —
every committed change is already on disk, so there is nothing to finish. It
costs random I/O across every touched page inside the commit path, before the
client hears anything, and a hot page gets rewritten once per transaction
instead of once per checkpoint. That is why real engines pay for redo instead:
one sequential append plus one fsync beats scattered page writes, every time.

**4.4 Full page copies off.** Redo can repair an *out-of-date* page because such
a page is still internally coherent — the row offsets, headers and slot
directory all agree, they're just older. A torn page is *incoherent*: half its
structure is from before and half from after. A redo record saying "set the
balance field of the row in slot 3" has no valid target. Neither is the page's
own stamp trustworthy, since it may have come from either half. **Durability
and Atomicity**, lost **silently at crash time** and discovered much later as a
checksum error at read time.

## Rung 5 — Trace

*(Verified against the simulator: the same run there numbers the checkpoint #101
rather than #100, so every LSN below is one lower than the page shows. Nothing
else differs.)*

**5.1 Analysis.** Start at #100.

| Read | Effect |
|---|---|
| #101 T1 BEGIN | T1 running |
| #102 T1 p5 | p5 first dirtied at #102 |
| #103 T1 p9 | p9 first dirtied at #103 |
| #104 T1 COMMIT | T1 committed → **winner** |
| #105 T2 BEGIN | T2 running |
| #106 T2 p5 | (p5 already listed) |
| #107 T2 p9 | (p9 already listed) |

**Losers = {T2}**, its last record is **#107**. **Redo starts at #102.**

**5.2 Redo.** Disk starts at p5 = ₹300 stamp #106, p9 = ₹300 stamp #100.

| Record | Stamp on page | Decision | Page after |
|---|---|---|---|
| #102 p5 → ₹200 | #106 ≥ #102 | **skip** | p5 = ₹300 |
| #103 p9 → ₹800 | #100 < #103 | **apply** | p9 = ₹800, stamp #103 |
| #106 p5 → ₹300 | #106 ≥ #106 | **skip** | p5 = ₹300 |
| #107 p9 → ₹700 | #103 < #107 | **apply** | p9 = ₹700, stamp #107 |

**Total after redo: ₹300 + ₹700 = ₹1000.** Note the trap: the total is *right*
and the database is still *wrong* — a loser's changes are fully present. A
balanced total is not evidence of correctness.

**5.3 Undo.** Walk T2's chain backward from #107.

| Undoing | Compensation record written | Page after |
|---|---|---|
| #107 (p9 ₹800→₹700) | **#108 CLR** — undoes #107, p9 = ₹800, `undoNext = #106` | p9 = ₹800, stamp #108 |
| #106 (p5 ₹200→₹300) | **#109 CLR** — undoes #106, p5 = ₹200, `undoNext = #105` | p5 = ₹200, stamp #109 |
| #105 is T2's BEGIN | **#110 T2 END** | — |

**5.4** p5 = ₹200, p9 = ₹800, **total ₹1000**. **T1 survived**; T2 is gone
entirely.

**5.5 Crash again after #108 was sealed.** Assume the redo pass's page changes
were still in the buffer pool, so the disk is back to p5 = ₹300 stamp #106,
p9 = ₹300 stamp #100 — only the log survived, now including CLR #108.

- **Analysis:** #108 is a CLR belonging to T2, so T2's last record is now #108.
  Losers = {T2}. Redo still starts at #102.
- **Redo:** #102 skip · #103 apply (p9 = ₹800) · #106 skip · #107 apply
  (p9 = ₹700) · **#108 apply** — a CLR is a redoable log record like any other
  (p9 = ₹800, stamp #108).
- **Undo:** T2's last record is #108, a **CLR** — so its work is not repeated;
  recovery jumps straight to `undoNext = #106`. It undoes #106 (p5 → ₹200,
  CLR #109), reaches #105 BEGIN, writes END.
- **Final: p5 = ₹200, p9 = ₹800, total ₹1000.** Identical.

The thing to notice: **#107 was undone exactly once across two recoveries.**
That is what the CLR bought, and it is why the second recovery was also
*shorter* than the first.

## Rung 6 — Spot the flaw

**6.1 "Re-read the row to roll back."** The data file does not hold the old
value — it holds whatever is there *now*, which may already be your own new
value (the page was stolen to disk), or someone else's. Concrete run: T1 sets
p5 ₹700 → ₹200; the page is written to disk; T1 aborts. Rolling back by
re-reading page 5 reads ₹200 and "restores" ₹200. **Atomicity violated**, and
the money is gone with no error. The before-image exists precisely because the
old value is destroyed by the act of writing the new one (README §4, Failure B).

**6.2 "The pageLSN check is redundant."** Two problems. First, real redo records
are often *changes*, not values ("add ₹500"); applying one twice doubles it.
Second, and fatal even for absolute values: **recovery can crash and re-run**,
and it can also legitimately re-apply records to a page that already has them
because the page was stolen to disk after the change. Concrete run: T1 commits
"p9 += ₹500"; page 9 reaches disk with the change; crash; redo re-applies →
₹1300; total ₹1500. Violated: **Atomicity/Durability's arithmetic**, silently.

**6.3 "Skip undo for changes that never reached disk."** This is exactly what
*repeating history* was invented to avoid, and it breaks on partial page
history. Concrete run: T2 changes p9 twice — ₹800→₹700 (#106) and ₹700→₹650
(#107). Page 9 is stolen to disk after #106 but before #107. The optimisation
inspects page 9, sees ₹700, concludes "#107 never landed, skip it" — correct —
then inspects #106, sees ₹700 present, undoes it to ₹800. That happens to work
here; now reverse the steal order or add a third update and the check has to
reason about *which* of several changes a page reflects, using only one stamp.
The real cost is that undo now needs a different code path from runtime
rollback, and every future refinement has to be correct in both. Redo-then-undo
is more work and less machinery.

**6.4 "Ack from the log buffer, fsync in the background."** The log buffer is
*process memory*. A `kill -9` — not even a power cut — destroys it. Concrete
run: 10 ms of transactions are acked; the process is OOM-killed; those
transactions never existed. **Durability broken**, and worse than the
`synchronous_commit=off` case, which at least survives a process kill because
the bytes reached the kernel. Group commit is *not* this: group commit still
does a real fsync before any ack, it just shares one fsync between many
transactions. The distinction is the entire content of README §7.2's three
moments.

**6.5 "The service layer validates, so we don't need constraints."** The service
layer protects the code paths that remember to ask. The constraint protects the
table. Concrete run: a data migration written by another team, a backfill
script, an admin's psql session, a second service added next quarter, or your
own code path added by someone who didn't know the rule — any of these writes a
negative balance, atomically and durably, and no error is raised. **Consistency
broken**, and the database performed perfectly. See README §12.2: this is what
the C in ACID actually asks of you.

## Rung 7 — Handle failure

**7.1 The lost ack.** Store an **idempotency key** — the client's own request
identifier — in a table with a `UNIQUE` constraint, written **inside the same
transaction as the transfer**. On a retry, the insert violates the constraint,
the transaction aborts, and the service returns the stored original outcome
instead of transferring again.

Why nowhere else fails:
- *In a cache (Redis) instead:* the key and the money are now two commit points,
  so a crash between them re-opens the exact hole you were closing. Your
  [redis](../redis/) lab measured the general version of this.
- *In a separate transaction, before:* crash after writing the key and before
  the transfer, and the retry is rejected for work that never happened.
- *In a separate transaction, after:* crash after the transfer and before the
  key, and the retry duplicates it.
The key must be in the same atomic unit as the effect, which is the whole point
of having atomicity in the first place. See the [idempotency](../idempotency/)
lab for the response-replay contract that goes with it.

**7.2 The crash-looping rollback.** Each undo step writes a CLR *before* it
touches the page, and each CLR names `undoNext`. So after every crash, the next
recovery's analysis finds the transaction's most recent CLR, reads `undoNext`,
and resumes there. The remaining work only ever shrinks, so with each crash the
rollback gets closer to done and eventually finishes even if it is interrupted
hundreds of times. **Without CLRs**, every restart would begin the rollback from
the transaction's last record — a 900,000-row rollback restarting every two
minutes never completes, and the database is permanently unavailable while
being, technically, perfectly correct.

**7.3 The lying storage.** Two mitigations at different rungs:
- *Same machine, lower rung:* battery-backed or capacitor-backed write cache
  (a proper RAID controller with BBU, or an enterprise SSD with power-loss
  protection). This makes the device's acknowledgement honest, so `fsync`
  regains its meaning. Covers power loss on that machine. Does **not** cover the
  machine dying, the disk dying, or firmware that lies about *having* power-loss
  protection.
- *Above the machine, higher rung:* synchronous replication — require a second
  machine to have the commit record before acking (`synchronous_commit` with a
  sync standby, `min-replicas-to-write`, `acks=all` + `min.insync.replicas`).
  Now durability no longer depends on any single device telling the truth.
  Covers the machine and the disk. Does **not** cover a correlated failure
  (identical firmware bug on both), a region loss, or the extra ~1 network round
  trip on every write — which is the [pacelc](../pacelc/) "else" branch, priced.

Both are mitigations, not fixes. The fix is storage that doesn't lie.

**7.4 Crash during analysis.** Nothing needs to be added: analysis **writes
nothing and changes nothing**. It only builds two in-memory tables from the log.
A crash during it loses only that in-memory work, and the next recovery rebuilds
the identical tables from the identical log — the log did not change. It is
easier than surviving a crash during undo for exactly one reason: **undo
mutates**, so it needs a durable record of its own progress (the CLR), whereas
analysis is a pure function of the log.

## Rung 8 — Design

**8.1 Payments across three regions.**

The rule "must never be lost even if a region is destroyed" puts you on the
**quorum-of-replicas** rung, minimum. Local `fsync` and even a local sync
standby both fail the stated requirement.

- **Commit path:** local WAL fsync, then wait for at least one *remote-region*
  replica to have the record durably, then ack. Cost: ~80 ms one way, so
  ≥ 80 ms added to every write, before any local work. Against a 250 ms p99
  budget that is affordable but it is now the dominant term — you have spent a
  third of the budget on durability, deliberately.
- **Shape:** 3,000 writes/s against one sync round trip means the log's group
  commit is doing heavy lifting; check that the engine batches replication
  acknowledgements rather than paying 80 ms serially per transaction.
- **Monitor:** replication lag *in LSN bytes and in seconds* per replica; the
  number of currently-synchronous replicas (a silent demotion to async is the
  failure that looks like a latency improvement); commit latency p50/p99 split
  by "local fsync" and "waiting for remote"; WAL volume and free space on the
  log volume; and time-to-recover after a crash.
- **During failover:** the sync replica becomes primary with no acked data lost
  — that is what you paid for. But the *quorum shrinks*, so either you drop to
  a lower durability rung (faster, weaker) or you block writes until a
  replacement joins (safe, an outage). Decide which **before** the incident;
  the default is usually the one you didn't choose deliberately.
- **What I would least be surprised to be paged about:** the sync replica
  silently falling out of the synchronous set — writes get faster, nothing
  alerts, and durability quietly drops a rung. Alert on the *count* of
  synchronous replicas, not just on lag.

**8.2 Order plus Kafka event.** Walk §13.6: the work spans two datastores; they
cannot live in one; and the event *is* derivable from the order. That lands on
**transactional outbox**: write the order and an outbox row in one local ACID
transaction, and a relay publishes to Kafka afterwards with an idempotent
consumer downstream.

- **Kept:** full A, C, I, D on the order itself — the part that is the source of
  truth.
- **Given up:** atomicity *across* the two systems, and immediacy. The event is
  eventually published, not simultaneously; consumers may see it late, and may
  see it twice.
- **Business consequence:** a customer can be shown "order placed" before any
  downstream service reacts, so anything that must be instantly consistent with
  the order (a stock count on the same page, say) has to read the database, not
  the event stream. The duplicate delivery is harmless only if every consumer is
  idempotent — which is a requirement you now own forever, not a detail.
- **Why not 2PC:** it makes Kafka and your database into each other's
  availability dependency and reintroduces the blocking problem, for a guarantee
  the business did not ask for. Why not a saga: there is no multi-step business
  workflow to compensate here, just one derived effect.

**8.3 The tuning complaint.** Their proposal helps one problem and worsens the
other, and neither is the actual cause of the WAL growth.

- *6-minute startup* is the **redo pass**: the data file was far behind the log
  when the crash happened. **Less frequent checkpoints make this strictly
  worse** — more log to replay. To fix it you want checkpoints *more* frequent
  (or a smaller `max_wal_size`), paying steadier background I/O for a shorter
  outage.
- *WAL volume filling twice a month* is almost never checkpoint frequency. The
  usual cause is that WAL cannot be **recycled**: an abandoned replication slot,
  a failing archive command, or a very long-running transaction. Checkpoint
  frequency affects WAL *generation* a little (via full-page writes, which spike
  right after each checkpoint — so more frequent checkpoints generate *more*
  WAL), but it cannot cause unbounded growth. Something is holding the log.
- **First change:** find and fix what is pinning WAL (inspect replication slots
  and the archiver), and add an alert on the oldest retained WAL position — not
  just on disk free space, which alerts only when it is already an outage.
- **Then:** shorten the checkpoint interval to bring recovery time down, and
  watch background write I/O and WAL generation rate to confirm the cost is
  acceptable. Note the tension you have just made explicit: more frequent
  checkpoints shorten recovery *and* increase WAL volume. That is the real
  tradeoff, and it is not the one they proposed.

**8.4 The invariant that isn't a constraint.** Three places, ranked by how many
writers each protects against:

1. **In the schema, by restructuring so the rule *becomes* declarable** — the
   strongest answer and the one most often missed. Store deposits and
   withdrawals as immutable rows and *derive* balances, or hold the invariant
   as a per-account rule the engine can check. If the rule can be restated as a
   property of rows rather than of a decision, a constraint can hold it, and
   then it protects **every writer that will ever exist**, including migrations
   and a human at a psql prompt.
2. **In the database, in code the database runs** — a trigger or an assertion at
   commit. Protects every writer that goes through the database, which is all of
   them, but costs correctness-critical logic living in a place that is hard to
   test and easy to forget during a schema change.
3. **In one service's application code** — protects only the code paths that
   call it. The moment a second service, a backfill, or an admin writes
   directly, the rule is gone. This is the option most systems actually have,
   which is why the reconciliation job in option 4 exists.
4. **(The one everyone ends up with anyway)** A periodic **reconciliation
   check** that recomputes the sum and alerts on drift. It protects nothing —
   it only tells you the rule has already been broken. Worth having regardless,
   because it is the only one of the four that detects a bug in the other three.
