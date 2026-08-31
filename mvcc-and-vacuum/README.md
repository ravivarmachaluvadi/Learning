# MVCC and VACUUM — why deleting a row deletes nothing, and why your 2 GB table is 40 GB

> **Lab type:** storage-engine mechanism · **No runnable code** (doc +
> interactive visualization + exercises).
>
> **Builds directly on three labs that each ended by pointing here:**
> [isolation-levels](../isolation-levels/) (`Next: [mvcc-and-vacuum?]`),
> [acid](../acid/) (same), [btree-and-lsm](../btree-and-lsm/) (same).
> You already own the words *MVCC, version, snapshot, snapshot isolation,
> first-committer-wins, bloat, vacuum*. You own them as **names**. This lab is
> where they become **machinery** — and where the operational failures they
> cause become things you can predict at 2 a.m. instead of discover.

---

## Where you are

> **Depth layers in this doc:**
> **BEGINNER MODEL** (§1–§10) → **INTERMEDIATE MODEL** (§11–§17) →
> **ENGINEERING MODEL** (§18–§21) → **PRODUCTION MODEL** (§22–§26)
>
> Checkpoints at each transition tell you what you can already do if you stop
> there. Four prediction points (🔮) are placed where the answer is genuinely
> not obvious. Commit to an answer before reading on — that is most of the
> value.

**What this lab deliberately does not re-teach.** Isolation levels and their
anomalies (you have them). The write-ahead log, crash recovery, ARIES (you have
them). B-tree page mechanics (you have them). SSI, rw-antidependencies, write
skew (you have them; they appear here only as one-line refreshers). This lab
spends all its depth on one narrow, deep question: **where do the old versions
physically live, who is allowed to throw them away, and when.**

---

## 1. WHY — a Tuesday that actually happens

A payments team gets paged at 03:10. The alert is disk usage: the primary
database is at 94% and climbing about 2% an hour. At 100% PostgreSQL stops
accepting writes and the payment API stops taking money.

The on-call engineer looks at the biggest table. `payment_attempts` holds
**8.1 million rows**. Multiply by the average row width and the data is maybe
**2.4 GB**. The table on disk is **41 GB**.

There has been no data import. Nobody added a column. The row count has not
changed materially in a week. Seventeen times more disk than data, and rising.

The engineer does the reasonable thing:

```sql
DELETE FROM payment_attempts WHERE created_at < now() - interval '90 days';
-- DELETE 3102847
```

Three million rows deleted. Disk usage does not fall by a single byte. It goes
**up**.

Then someone runs `VACUUM payment_attempts` by hand. It completes. Disk usage
still does not fall. `pg_stat_user_tables` says the table has **19 million dead
rows** — more dead rows than the table has live ones — and vacuum has just run
successfully and removed almost none of them.

Every one of those four surprises is a direct, predictable consequence of one
design decision made to solve a completely different problem: **letting a report
run for forty seconds without stopping anybody from taking payments.**

By the end of this lab you will be able to look at that incident and name the
cause in one sentence, find the culprit in one query, and know which of the four
possible fixes actually returns the 38 GB.

## 2. THE CONCRETE PROBLEM — Asha writes, Ravi reads, and they are in the same room

Forget databases for ten minutes. We are in a district land registry office in
1970. There is one book: the **register**. Each page holds numbered entries;
each entry records who owns a plot.

Two people need the register right now:

- **Asha**, a clerk. A sale has completed. Plot 12 must change from *Meera* to
  *Vikram*. This takes her about a second.
- **Ravi**, an auditor. He is preparing a statement of every plot in the
  district. He must read all 400 pages, and it takes him **forty minutes**.

There is one book and it is on one table. This is the entire problem.

Two things must be true at once, and they fight:

1. **Ravi's statement must be internally consistent.** If he reads plot 12 as
   *Meera* on page 3 and plot 12's mortgage as belonging to *Vikram* on page
   290, he has produced a document that describes a state of the world that
   never existed. His report has to describe the register **as of one moment**.
2. **Asha must not have to wait forty minutes to record a completed sale.**

Hold that scene. Every design in this lab is an attempt to give both of them
what they want.

> **Naming, once, so the rest reads cleanly.** In this lab a **reader** is any
> transaction that needs a consistent view over time — a report, a `pg_dump`, a
> nightly export, an analytics query, a long `SELECT`. A **writer** is any
> transaction that changes rows. The tension is never reader-vs-reader.

## 3. THE OBVIOUS SOLUTION — put a lock on the book

The first design any reasonable person proposes: **whoever is using the book
holds it.** Asha wants to write, she takes the book. Ravi wants to read, he
takes the book. Nobody else touches it until they put it down.

You have seen this before under a formal name. From
[isolation-levels](../isolation-levels/): this is **two-phase locking (2PL)**,
and its bill was stated there in three words — *2PL pays in waiting*. You even
know the refinement: readers can share (many people may read at once) but a
writer needs the book exclusively, so a writer must wait for every reader to
finish, and a reader must wait for a writer.

Let us actually run it, because the failure is worse than "some waiting".

**09:00** — Ravi opens the register and starts his forty-minute pass. He holds a
shared lock on the book.
**09:03** — Asha arrives with the plot 12 sale. She needs exclusive access. Ravi
has it shared. She waits.
**09:06** — A second clerk arrives with another sale. Waits.
**09:11** — A third. Waits.
**09:40** — Ravi finishes. Asha finally records the sale, 37 minutes after the
customer left.

Now look at what that sentence actually says in production terms:

> **A read-only query stopped the business from taking money for 37 minutes.**

Nothing was wrong. Nobody crashed. No network partitioned. A query that changes
nothing, that has no side effects, that could in principle be run on a
photocopy — halted every writer in the system, because the only way it could
guarantee itself a consistent view was to freeze the world.

That is not a tuning problem. It is structural. And it gets worse in the
direction you would not guess: reverse the order and it is the writers who
starve the reader. If clerks arrive every 20 seconds and each holds the book for
a second, Ravi may never get a clean forty-minute window at all. His report
never runs.

> **🔮 Predict #1.** Before reading on, commit to an answer:
>
> *Ravi is read-only. He changes nothing. Is there any way to give him a
> consistent forty-minute view of the register that does not require him to stop
> Asha — while still using only the one physical book on the table?*
>
> Think about what specifically breaks if Asha writes underneath him. It is not
> that she writes. It is something narrower than that.

<details><summary><b>Answer</b> (open after you have committed)</summary>

Yes — and the narrow thing that breaks is worth naming precisely.

Ravi is not harmed by Asha *adding information*. He is harmed by Asha
**destroying** information: specifically, by her erasing the value he still needs
to see. If Asha writes *Vikram* on top of *Meera*, the value *Meera* is gone from
the universe and Ravi can no longer produce a 09:00-consistent report.

So the requirement is not "stop Asha writing". It is:

> **stop Asha from destroying the past.**

Those sound similar. They are enormously different in cost. Blocking writes costs
you availability. Preserving the past costs you *space* — and space is a bill you
can pay later, in the background, when nobody is watching.

That trade — **latency now for space and cleanup later** — is the entire subject
of this lab. Everything else is consequences.
</details>

## 4. THE REAL REQUIREMENT

State it plainly, because the rest of the lab is the pursuit of this one
sentence:

> **Every reader must be able to see a complete, unchanging picture of the data
> as it stood at one chosen moment, no matter how long that reader takes, and
> without preventing any writer from proceeding.**

Three parts, each load-bearing:

- **"as it stood at one moment"** — internal consistency. Not the newest data;
  *one consistent* data.
- **"no matter how long"** — the guarantee cannot degrade with the reader's
  duration. This is the part that will come back to bite us, hard, in §13.
- **"without preventing any writer"** — the whole point.

## 5. THE KEY IDEA — never overwrite; append and annotate

Here is the invention, in two sentences.

> **When a row changes, do not modify it. Write a brand-new copy of the row
> containing the new values, and mark the old copy with the moment it stopped
> being current.**

Back at the registry, Asha stops erasing. Instead:

- Entry 47 currently reads: *Plot 12 — owner Meera*.
- Asha writes a **new entry**, number 92, on a fresh line: *Plot 12 — owner
  Vikram*.
- Then she goes back to entry 47 and writes in its margin: *superseded at
  09:03*.

She has not destroyed anything. Entry 47 is still legible, still there, with a
note saying when it stopped being true.

Now Ravi, who started at 09:00, has a rule he can apply on his own, without
talking to anybody:

> *Ignore any entry created after 09:00. Ignore any entry that was already
> superseded before 09:00. What is left is the register as of 09:00.*

He reads entry 47, sees "superseded at 09:03", notes that 09:03 is after his
09:00, and **uses entry 47**. He reaches entry 92, sees it was created at 09:03,
after his 09:00, and **skips it**.

Asha did not wait. Ravi got a consistent 09:00 view. Both won, and the only thing
it cost was ink and paper.

This is **Multi-Version Concurrency Control**. The name now decomposes exactly:
*multi-version* — more than one physical copy of each logical row exists at once;
*concurrency control* — that is the mechanism by which concurrent transactions
are kept from interfering.

You knew the acronym coming in. What you now have is the *reason*: MVCC is the
answer to "how do I stop destroying the past", and it exists because
readers-blocking-writers was intolerable.

## 6. TERMINOLOGY — the four words the rest of the lab is written in

Now that you have felt the distinctions, they can have names. These four are
load-bearing; everything else gets defined at first use and collected in the
glossary (§27).

**Tuple.** In PostgreSQL's own source and documentation, a *tuple* means **one
physical version of one row**. This is the single most important vocabulary
correction in the lab, so say it out loud: *row* is the logical thing your
application talks about ("plot 12's owner"); *tuple* is one physical copy of it
sitting in a page on disk. **One row can be many tuples.** In the registry, plot
12 is the row; entries 47 and 92 are two tuples.

*(Warning, because this genuinely confuses people: in the SQL standard and in
most textbooks, "tuple" just means "row". PostgreSQL uses it in the narrower
physical sense above, and so does this lab, because that distinction is the
thing being taught.)*

**Version chain.** The set of tuples that are all copies of the same logical row,
ordered oldest to newest. Plot 12's chain is `entry 47 → entry 92`.

**xmin.** The stamp on a tuple saying **which transaction created it**. Entry
92's xmin is Asha's transaction. Plain English: *the tuple's birth certificate.*

**xmax.** The stamp on a tuple saying **which transaction ended it** — deleted
it, or replaced it with a newer version. Entry 47's xmax is Asha's transaction
too, because the same act that created 92 ended 47. Plain English: *the tuple's
death certificate.* A tuple with no xmax (`xmax = 0`) has not been ended by
anybody; it is the current version.

Two consequences worth noticing right now, because they will do a lot of work
later:

1. **An `UPDATE` is an `INSERT` plus a `DELETE`.** Physically, `UPDATE
   plots SET owner='Vikram' WHERE id=12` writes a whole new tuple and stamps
   xmax on the old one. There is no "modify in place" anywhere in this design.
   Updating a single boolean column on a 4 KB row writes a new 4 KB tuple.
2. **A `DELETE` deletes nothing.** It writes an xmax on the existing tuple and
   returns. The bytes are all still there. This is the sentence in the lab title
   and it is literally true.

> **Illustrative only — do not run this anywhere that matters, and note that
> `xmin`/`xmax` are hidden system columns you can select but never write:**
>
> ```sql
> SELECT ctid, xmin, xmax, id, owner FROM plots WHERE id = 12;
> ```
>
> On PostgreSQL this returns one row per *visible* tuple — so normally one row.
> The value of this snippet is that it is the seam where the physical model
> becomes observable: `ctid` is the tuple's physical address as `(page,
> line-pointer)`, and it changes every time the row is updated, because the row
> moved.

## 7. MECHANISM v1 — birth and death stamps, with real numbers

Time to drop the registry for concrete machine values. We keep the people.

Transactions get an ever-increasing integer identifier. In PostgreSQL it is
called a **transaction ID**, universally abbreviated **XID**. Transaction 700
started before transaction 701. That is all it means, for now (§19 will demolish
"that is all it means", but not yet).

Our table `plots` starts with two rows, both written long ago by transaction
**500**, which committed.

| tuple | xmin | xmax | id | owner |
|---|---|---|---|---|
| A | 500 | 0 | 12 | Meera |
| B | 500 | 0 | 13 | Kavya |

**Event 1 — Ravi begins his report.** He gets XID **700**. He takes a
*snapshot*, which for the moment we will treat as just "the moment 700 began".
He starts reading.

**Event 2 — Asha updates plot 12.** She gets XID **701**. `UPDATE plots SET
owner='Vikram' WHERE id=12`. Physically:

- a new tuple **C** is written: `xmin=701, xmax=0, id=12, owner=Vikram`
- tuple **A** is stamped: `xmax=701`

| tuple | xmin | xmax | id | owner |
|---|---|---|---|---|
| A | 500 | **701** | 12 | Meera |
| B | 500 | 0 | 13 | Kavya |
| **C** | **701** | 0 | 12 | **Vikram** |

**Event 3 — Asha commits.** Transaction 701 is now committed.

**Event 4 — Ravi, still in transaction 700, reads plot 12.** He walks the tuples
for id 12 and finds A and C. Which does he take?

He applies a rule of exactly two clauses:

> A tuple is visible to me if **(a)** its xmin is a transaction I should be able
> to see, **and (b)** its xmax is either absent, or is a transaction I should
> *not* be able to see.

- Tuple **C**: xmin is 701. 701 started *after* Ravi's 700 began. Ravi must not
  see it. → **invisible**.
- Tuple **A**: xmin is 500, long committed before Ravi began → clause (a) passes.
  xmax is 701, which Ravi cannot see → clause (b) passes. → **visible**.

Ravi reads `owner = Meera`. Asha committed her change fourteen minutes ago and it
is not there — **which is exactly right**, because Ravi is producing a 09:00
report. Meanwhile any transaction starting *now* gets XID 702, sees C, and reads
*Vikram*.

Look at what just happened at the physical level: **two different transactions
read two different bytes from two different places on disk for the same logical
row, at the same instant, with no locks and no coordination between them.** That
is the whole payoff. Visibility is a pure function of (tuple stamps, my
snapshot). It requires no communication.

## 8. WHY v1 IS NOT ENOUGH — "later" is not the same as "not committed"

Version 1 quietly assumed something false. It assumed that "a transaction I
should be able to see" can be decided by comparing XID numbers.

Here is the scenario that breaks it. Two writers overlap:

- **XID 710** — Asha begins a long update touching many rows.
- **XID 711** — Vikram begins a quick update, and **commits** at 09:05.
- **XID 712** — Ravi begins a report at 09:06. Asha (710) is *still running*.

Now Ravi looks at a tuple with `xmin = 710`. Under the naive number rule, 710 <
712, so "it started before me, I see it". That is **wrong** — 710 has not
committed. Ravi would be reading uncommitted data. From
[isolation-levels](../isolation-levels/) you know the name for that result: a
**dirty read**, forbidden at every level above read-uncommitted.

And the reverse case. Ravi looks at a tuple with `xmin = 711`. 711 < 712, so the
number rule says visible — and here that happens to be correct, because 711
committed before Ravi started. But now imagine a third transaction 715 that
commits, and then Ravi (712, still running) looks at its tuple: 715 > 712,
invisible. Correct. The number comparison got two of three right by luck.

The actual requirement is:

> I should see a transaction's work if and only if that transaction had
> **already committed** at the instant my snapshot was taken.

Two facts are needed and neither is on the tuple:

1. **Did that transaction commit, abort, or is it still running?** The tuple
   header does not say. It only carries a number.
2. **Was it already committed when I started?** A number cannot answer this,
   because transactions do not commit in the order they start. 710 started before
   711 and will commit after it.

### The two structures that supply the missing facts

**The commit log.** PostgreSQL keeps a separate, tiny array on disk — `pg_xact`,
called `clog` in older versions and in most blog posts — with **two bits per
transaction ID**: in progress / committed / aborted / sub-committed. To ask "did
710 commit?", you index into that array. It is small (two bits per XID) and
almost always in memory.

**The snapshot.** When a transaction needs a view, it captures a snapshot: not a
copy of data, but a **description of which transactions to believe**. In
PostgreSQL it is three fields:

| field | meaning |
|---|---|
| `xmin` | the lowest XID still running when the snapshot was taken. Anything below this is *finished* — committed or aborted — and can be judged by the commit log alone. |
| `xmax` | the next XID that had not yet been assigned. Anything at or above this began after me: **always invisible**. |
| `xip[]` | the list of XIDs that were *in progress* at that instant (between the two bounds): **always invisible**. |

*(Careful: the snapshot's `xmin`/`xmax` fields and a tuple's `xmin`/`xmax`
stamps are different things that unfortunately share names. Snapshot bounds
describe transactions; tuple stamps describe one physical version. When it
matters below, this lab writes "snapshot xmin" or "tuple xmin" in full.)*

So Ravi's snapshot at 09:06 is roughly: `xmin=710, xmax=713, xip=[710]`. Read it
as a sentence: *"Everything below 710 has finished — ask the commit log whether it
committed. 710 was still running, so never believe it. 713 and above did not
exist yet, so never believe them."*

> **🔮 Predict #2.** Asha's transaction 710 has been running for two hours and
> has updated 50,000 rows. She now types `ROLLBACK`. Those 50,000 new tuples are
> sitting in the table's pages on disk, physically written, some of them already
> flushed to disk by the background writer.
>
> **How much work does the rollback do to undo them?** Give an order of
> magnitude: seconds? minutes? proportional to 50,000?

<details><summary><b>Answer</b></summary>

**Essentially none. It is O(1) and it is instant.** The rollback writes a single
entry in the commit log — "transaction 710: aborted" — two bits — and returns.

That is it. Nothing walks back over the 50,000 tuples. Nothing is erased. All
50,000 new tuples are still physically present in the pages, and all 50,000 old
tuples still carry `xmax = 710`.

They are simply now **invisible to everyone forever**, by the same rule that
governs everything: any reader that checks `xmin = 710` will consult the commit
log, learn that 710 aborted, and skip the tuple. Any reader checking an old
tuple's `xmax = 710` will learn 710 aborted, conclude that the deletion never
happened, and treat the tuple as alive. The abort is retroactive and free.

This is a genuinely remarkable property and it is worth pausing on, because it
is the exact opposite of what you learned in [acid](../acid/). There, undo was
real work: before-images, a full undo pass, CLRs, `undoNext`, a recovery process
that could pin a CPU for an hour rolling back one huge aborted transaction. That
was ARIES on an **in-place** storage engine, where the new value physically
overwrote the old one, so getting the old value back meant copying it back.

PostgreSQL's heap never overwrites, so **there is nothing to copy back**. The
undo work is zero.

And now the sentence that is really the thesis of the entire lab:

> **The work did not disappear. It moved.** In an in-place engine you pay for old
> versions at rollback time and at read time. In a no-overwrite engine you pay
> for them **later, in the background, in a completely different process, on a
> schedule you do not control.**
>
> That process is VACUUM. It is not a maintenance chore bolted onto PostgreSQL.
> It is the other half of the design, and you have just watched the bill being
> run up.

(One honest footnote: rollback is not *quite* free of consequence — the aborted
transaction's changes were still written to the WAL, still dirtied pages, and
still left 50,000 dead tuples. Free of *undo work*, not free of cost.)
</details>

## 9. THE VISIBILITY RULE, stated once and precisely

You now have every piece. Here is the actual rule, which PostgreSQL evaluates for
**every tuple it touches, on every scan**. This is the intermediate model's
centrepiece; read it slowly once and it will serve you for years.

For a tuple with stamps `(t.xmin, t.xmax)` and a reader holding snapshot `S`:

**Step 1 — is the tuple's creator visible to me?**

| condition on `t.xmin` | result |
|---|---|
| it is *my own* transaction | visible (with a command-level check — see below) |
| `t.xmin >= S.xmax` | **not visible.** Began after my snapshot. Stop; tuple invisible. |
| `t.xmin ∈ S.xip[]` | **not visible.** Was in flight at my snapshot. Stop; tuple invisible. |
| otherwise → ask the commit log | aborted ⇒ **stop, tuple invisible.** committed ⇒ continue to step 2. |

**Step 2 — has the tuple been ended by someone I can see?**

| condition on `t.xmax` | result |
|---|---|
| `t.xmax = 0` (never ended) | **VISIBLE.** This is the current version. |
| the xmax is only a *lock*, not a delete | **VISIBLE.** (see the note below) |
| `t.xmax >= S.xmax`, or `t.xmax ∈ S.xip[]` | **VISIBLE.** Someone deleted it, but that deletion is in my future. This is exactly Ravi reading *Meera*. |
| otherwise → ask the commit log | aborted ⇒ **VISIBLE** (the delete never happened). committed ⇒ **not visible** (it was deleted before my snapshot). |

Two refinements that matter in practice:

- **xmax is not always a delete.** `SELECT ... FOR UPDATE` and foreign-key checks
  place a *row lock*, and PostgreSQL records row locks in the same xmax field,
  distinguishing them with flag bits in the tuple header (the `infomask`). So a
  tuple can have a non-zero xmax and be perfectly alive and current. If you ever
  inspect raw tuples and see a populated xmax on a row you know was never
  deleted, this is why.
- **Your own changes, within one statement.** Two extra fields, `cmin`/`cmax`,
  record the *command number* inside your transaction, so that `UPDATE t SET x =
  x+1` does not see the rows it is itself producing and loop forever. You will
  never tune this; know it exists so the header layout makes sense.

**The one-sentence version, which is worth memorising:**

> **A tuple is visible to me if it was created by a transaction that had
> committed before my snapshot, and has not been deleted by a transaction that
> had committed before my snapshot.**

Notice what is *not* in that rule: any mention of other readers, any lock, any
coordination, any waiting. Visibility is decided entirely locally. That locality
is why MVCC scales, and it is why "readers don't block writers, writers don't
block readers" is true rather than aspirational.

## 10. TWO MENTAL MODELS, and where the first one lies to you

### The simple model — the register with margin notes

The registry book. Nothing is erased. New entries go on fresh lines. Superseded
entries get a margin note saying when they stopped being true. Every reader
carries a slip of paper with a timestamp and applies the same two-clause rule.
Periodically a clerk goes through with a ruler and strikes out entries that no
reader in the building could possibly still need, freeing those lines for
reuse.

That model gets you a long way and it is the one to keep in your head.

### The engineering model — what is actually on disk

A PostgreSQL table is a **heap**: a sequence of 8 KB **pages** (you met pages in
[btree-and-lsm](../btree-and-lsm/); same object). Inside one page:

```
┌─────────────────────────────────────────────────────────────┐
│ page header (24 B)                                          │
├─────────────────────────────────────────────────────────────┤
│ line pointer array  →  [1][2][3][4][5] ...                  │  grows down ↓
│   each 4 bytes: offset, length, and a 2-bit STATE           │
│   state ∈ { UNUSED · NORMAL · DEAD · REDIRECT }             │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│                  free space                                 │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│ tuple 5 │ tuple 4 │ tuple 3 │ tuple 2 │ tuple 1             │  grows up ↑
│                                                             │
│  each tuple = 23-byte header (xmin, xmax, cid, ctid,        │
│               infomask flags) + null bitmap + your data     │
└─────────────────────────────────────────────────────────────┘
```

Three details from that diagram will each become a section of their own:

- **The line pointer array is a level of indirection.** An index does not point
  at a tuple's byte offset; it points at `(page number, line pointer number)` —
  the **ctid**. The line pointer then says where in the page the tuple actually
  starts. Indirection is what makes §16's HOT updates possible.
- **Line pointers have their own lifecycle** — `NORMAL` (points at a tuple),
  `DEAD` (the tuple is gone but index entries may still point here), `UNUSED`
  (fully reclaimed, reusable), `REDIRECT` (forwards to another line pointer in
  the same page). Two of those four states exist purely because of vacuum.
- **The header is 23 bytes, padded to 24.** On a table of narrow rows — say an
  8-byte integer key and a 4-byte value — the per-version bookkeeping is *twice
  the size of your data*. MVCC is not free even when nothing goes wrong.

### Mapping the two models

| registry | PostgreSQL |
|---|---|
| the book | the heap (the table's data file) |
| a page of the book | an 8 KB page |
| an entry | a tuple = one physical row version |
| the entry number in the margin index | a line pointer, addressed by `ctid` |
| "written by clerk #701" | `xmin` |
| "superseded at 09:03 by clerk #701" | `xmax` |
| the reader's timestamped slip | a snapshot `(xmin, xmax, xip[])` |
| the office's log of which clerks finished their shift properly | `pg_xact`, the commit log |
| the clerk with the ruler | VACUUM |

### Where the analogy breaks — four places, and each one matters

1. **Ravi's slip says a time; a snapshot does not.** A snapshot is not "09:00".
   It is a *set of transaction identities to believe*. This is not pedantry: it
   is why a transaction that starts later can still see *less* than one that
   started earlier, and why wall-clock time never appears anywhere in the
   visibility rule. If you carry "snapshot = timestamp" in your head you will
   mispredict every overlapping-writer case in §8.
2. **The clerk with the ruler cannot make the book thinner.** Striking out an
   entry frees the *line* for a future entry. It does not remove a page from the
   book, and it does not make the book lighter. §14 is entirely about this.
3. **The register has no index; a real table has several, and they are separate
   books.** Every tuple you create needs an entry in every index. Cleaning up a
   tuple means first finding and removing its pointers in every one of those
   books. This is why vacuum's cost is dominated by index maintenance, not by
   the heap.
4. **The clerk's numbers run out.** The registry could use 09:03 forever.
   PostgreSQL's XID is a **32-bit** counter, it wraps, and the consequence is the
   single most feared operational failure in the whole system. That is §19, and
   it is the part of this lab that has woken people up at night.

---

> ### ✅ CHECKPOINT — end of the BEGINNER MODEL
>
> **You can now:** explain what MVCC is and *why* it exists (not "for
> performance" — to stop readers blocking writers); explain why an `UPDATE` is
> physically an insert plus a stamp; state the visibility rule and apply it to
> a two-transaction trace; explain why rollback is free in PostgreSQL and
> expensive in an ARIES-style in-place engine; and read `xmin`, `xmax`, `ctid`
> in a tuple header.
>
> **What the next layer adds:** the bill. Every one of those unremoved tuples is
> still occupying disk and still being read by every sequential scan. We now
> follow the garbage — who is allowed to collect it, why the obvious collection
> rule is wrong, and why the space does not come back.

---

## 11. THE BILL — what "dead tuple" costs, in numbers

A **dead tuple** is a tuple that is invisible to every possible reader, now and
forever. Nobody will ever look at it again. It is garbage.

Let's price it on a realistic table. `sessions` has **1,000,000** rows. Each row
is 200 bytes of data plus the 24-byte header, so 224 bytes; at 8 KB per page,
about **35 tuples per page**, so the table is roughly **28,600 pages ≈ 230 MB**
when clean.

Now the application does what applications do: it updates `last_seen_at` on every
request. Each session is touched **10 times** an hour.

After one hour, no cleanup:

- tuples written: 1,000,000 original + 10,000,000 updates = **11,000,000**
- live tuples: still **1,000,000** — the row count has not changed at all
- dead tuples: **10,000,000**
- table size: **~2.5 GB**, for 230 MB of data

Three costs land, and they are different from each other:

**Cost 1 — disk.** The obvious one, and the least interesting. Disk is cheap.

**Cost 2 — every sequential scan reads all of it.** `SELECT count(*) FROM
sessions` must visit all 11 million tuples and evaluate the visibility rule on
each, to return 1,000,000. Not 230 MB of I/O — **2.5 GB**. Your query got 11×
slower and the query plan did not change, the statistics did not change, and
nothing in your code changed. *This* is what people mean when they say bloat
hurts.

**Cost 3 — cache pollution.** PostgreSQL's shared buffers and the OS page cache
hold *pages*, not rows. If 90% of every page is garbage, then 90% of your RAM is
holding garbage. A working set that comfortably fit in 8 GB of cache now needs
88 GB and does not fit, so you start hitting disk on queries that used to be
served from memory. Latency does not degrade gracefully here — it falls off a
cliff when the working set crosses the cache size.

You met the general shape of costs 1 and 2 in
[btree-and-lsm](../btree-and-lsm/) under the names **space amplification** and
**read amplification**. This is the same scorecard applied to a different
engine, and MVCC's answer to "when do you pay for sortedness" is "not sortedness
— *deadness*, and you pay in a background process".

So: somebody has to collect the garbage. That is VACUUM. Now the interesting
part — the obvious way to write it is wrong.

## 12. VACUUM v1 — and the failure that forces the whole design

**Version 1 of the cleanup rule**, which everybody proposes first:

> *Scan the table. For each tuple, if its xmax is set and that transaction
> committed, the tuple has been deleted — remove it.*

It is wrong, and §7 already contains the counterexample.

Recall: tuple **A** is `(xmin=500, xmax=701)`, Asha's 701 has committed, and Ravi
is still sitting in transaction 700 producing his forty-minute report. Under
rule v1, A qualifies for removal: xmax set, xmax committed, delete it.

Ravi reaches plot 12 and finds only tuple C (`owner = Vikram`, xmin 701 — which
he cannot see). His snapshot says C is invisible; A no longer exists. **Plot 12
has vanished from the register.** Ravi's report is missing a plot. Not stale —
*wrong*, and silently so.

We have violated the exact guarantee we set out to provide in §4: *"no matter
how long that reader takes"*. Version 1 of vacuum silently converts a slow reader
into a corrupt report.

### The refinement: a tuple may only be removed when no snapshot could want it

So the rule must consult not only the tuple, but **every snapshot alive in the
system**:

> **A dead tuple may be removed only when its deletion is visible to every
> snapshot currently held anywhere in the database — that is, only when its xmax
> committed before the *oldest* snapshot still in existence.**

Since snapshots are described by their `xmin` bound, this reduces to a single
number:

> **the horizon** = the minimum `xmin` over all snapshots currently held.
>
> Every transaction with an XID below the horizon is finished and its outcome is
> settled for everyone. A dead tuple whose xmax is below the horizon can never be
> wanted again. **A dead tuple whose xmax is at or above the horizon must be
> kept, no matter how obviously dead it looks.**

PostgreSQL exposes this number; it is often written `OldestXmin` in logs and
`backend_xmin` in `pg_stat_activity`.

Read the horizon definition once more and notice its shape: it is a **global
minimum**. Not an average. Not a percentile. **One** participant, anywhere,
holding an old snapshot pins the horizon for the **entire database** — including
tables that participant has never heard of.

That single structural fact generates most of §20's failure scenarios, the 03:10
page in §1, and about 80% of all PostgreSQL bloat incidents ever filed.

> **🔮 Predict #3.** A developer opens `psql`, runs `BEGIN;` and a single
> `SELECT count(*) FROM users;`, gets the answer, and then goes to lunch without
> typing `COMMIT`. The session is now `idle in transaction`, holding an open
> read-only transaction. It holds **no locks on any table** — it is read-only and
> it finished its query.
>
> Meanwhile the application updates the `sessions` table 10 million times over
> the next three hours, and autovacuum runs against it dozens of times,
> successfully, with no errors in the log.
>
> **How many of those 10 million dead tuples can vacuum remove?**

<details><summary><b>Answer</b></summary>

**Zero.**

Every one of those 10 million dead tuples has an xmax at or above the horizon,
because the horizon is pinned at the developer's snapshot from three hours ago.
Vacuum runs, walks all 11 million tuples, evaluates every one, removes **none**,
reports success, and goes back to sleep. Then it does it again. And again.

Three hours later the table is 2.5 GB of which 230 MB is real, the developer
comes back from lunch, types `COMMIT` (or their client times out), the horizon
jumps forward by three hours' worth of XIDs, the next vacuum removes all 10
million dead tuples in one pass — and **the table is still 2.5 GB on disk**
(§14 explains that last part).

This is worth internalising as a shape, because it recurs in every MVCC system
ever built:

> **One idle session, holding nothing, blocking nobody, caused unbounded space
> growth across the entire database.**

There are no locks involved. There is no error. `pg_locks` is clean. The only
symptom is disk. This is why every experienced PostgreSQL operator sets
`idle_in_transaction_session_timeout`, and why "who is holding the oldest xmin"
is the first query you run on a bloat page — not "is autovacuum running", which
was the on-call engineer's wrong first move in §1.

If you predicted "a few", the mistake was almost certainly assuming the horizon
is per-table or that a read-only transaction is harmless. Neither is true. The
horizon is global, and *read-only is exactly the dangerous kind* — a writing
transaction that long would at least be visibly holding locks.
</details>

## 13. WHAT VACUUM ACTUALLY DOES — five jobs, in order

"VACUUM removes dead tuples" is about a fifth of the truth. Here is the whole
job, because four of the five jobs cause a distinct production symptom you will
otherwise misdiagnose.

A vacuum of one table proceeds roughly like this:

**Phase 1 — scan the heap and build a list of dead tuples.**
Read the table's pages (skipping ones the visibility map marks all-visible — §17).
On each page, apply the horizon rule to every tuple. For each removable one, do
an in-page *prune*: move its data out, and set its line pointer to `DEAD`.
Collect its `ctid` into an in-memory list.

Why only `DEAD` and not `UNUSED`? Because indexes still point at that line
pointer. Freeing the slot now would let a future tuple occupy it, and an old
index entry would then point at an unrelated row. So the line pointer must be
kept as a tombstone until every index has been cleaned.

**Phase 2 — vacuum the indexes.**
For **each index on the table**, scan it and delete every entry whose ctid is in
that list. This is usually the *dominant cost of the whole operation*: a full
index scan per index. A table with 8 indexes pays 8 full index scans.

**Phase 3 — reclaim the line pointers.**
Go back over the heap pages and set those `DEAD` line pointers to `UNUSED`. Now
the slots and the space are genuinely reusable.

**Phase 4 — update the free space map and the visibility map.**
Record how much free space each page now has (that is how future inserts find a
home), and set the all-visible bit on pages where every tuple is now visible to
everyone.

**Phase 5 — freeze old tuples**, and possibly truncate empty pages off the end
of the file. §19 and §14 respectively.

### Four consequences you can now predict instead of discover

**(a) Vacuum is mostly an index operation.** If a vacuum is taking hours, the
first question is not "how big is the table" but "how many indexes does it have,
and how big are they". Dropping an unused index makes every future vacuum
faster, permanently.

**(b) The dead-tuple list has a memory budget, and running out used to be
brutal.** The list lives in `maintenance_work_mem`. If it fills up, vacuum must
stop scanning, do the *entire* index phase, come back, and resume — so a big
table could pay for five or ten complete passes over every index. On PostgreSQL
17 and later this changed significantly: the dead-TID list was rewritten to a
compressed radix-tree structure (`TidStore`) that removed the old 1 GB cap and
made multiple index passes far rarer. *[Version-specific: check your major
version. The old advice "raise maintenance_work_mem before a big vacuum" is
strong pre-17 advice and much less critical after.]*

**(c) Vacuum is deliberately, configurably slow.** It runs a **cost-based delay**
loop: it accumulates notional cost points for pages hit in cache
(`vacuum_cost_page_hit`), read from disk (`..._miss`), and dirtied
(`..._page_dirty`), and when it exceeds `vacuum_cost_limit` it *sleeps* for
`vacuum_cost_delay`. This is throttling by design — vacuum yields to your
workload. It is also the single most common reason autovacuum "cannot keep up":
it is not stuck, it is **obeying a speed limit set for a machine much smaller
than yours**. Defaults have historically been extremely conservative;
`autovacuum_vacuum_cost_delay` dropped from 20 ms to **2 ms** in PostgreSQL 12,
and on modern SSD hardware raising `vacuum_cost_limit` well above the default
200 is routine.

**(d) A plain `SELECT` can clean up.** Phase 1's in-page pruning also happens
*opportunistically* whenever a page is read and looks like it would benefit
(HOT pruning, §16). So a read query can dirty pages and generate WAL. If you have
ever wondered why a read-only workload produces write I/O, this is one of two
reasons — the other being **hint bits**: the first reader to touch a tuple after
its creating transaction committed writes a flag into the tuple header caching
"xmin committed", so the next reader need not consult the commit log. That flag
write dirties the page. Both are the system doing your future self a favour, and
both make "read-only query, why is it writing?" a non-mystery.

## 14. WHY THE DISK SPACE DOES NOT COME BACK

This is surprise #3 and #4 from the 03:10 incident, and it is the single most
common misconception about vacuum.

> **🔮 Predict #4.** The developer from §12 finally committed. The horizon has
> jumped forward. Autovacuum now runs on `sessions` and successfully removes all
> **10 million** dead tuples — the table has 1 million live rows, about 230 MB of
> real data, and the file on disk is 2.5 GB.
>
> The vacuum reports success. **What does `du` say the file size is now?**

<details><summary><b>Answer</b></summary>

**2.5 GB.** Unchanged. Not one byte returned to the operating system.

If you predicted 230 MB, you have just made the same mistake the on-call
engineer made at 03:10, and the next paragraph is the most useful one in the
lab.
</details>


> **Ordinary VACUUM does not return space to the operating system. It makes
> space reusable *inside* the existing file.**

Freeing tuple slots inside a page leaves the page where it is. The file has the
same number of pages. `du` reports the same size. What has changed is that the
free space map now says "page 4,201 has 6 KB free", so the *next* insert or
update will land there instead of extending the file.

So the effect of vacuum is **not shrinkage — it is that growth stops.** The table
reaches a steady-state size somewhere above its live size, and stays there.
That steady state is the honest, healthy outcome, and it is why a
permanently-somewhat-larger-than-its-data table is not a bug.

There is exactly one case where plain vacuum *does* shorten the file:
**truncation**. If the pages at the very **end** of the file are entirely empty,
vacuum can lop them off and hand the space back. Note the two conditions — the
empty pages must be **contiguous** and at the **end**. Delete 3 million rows
scattered through a 41 GB table and you free space in the middle of thousands of
pages and truncate approximately nothing. (Truncation also needs a brief
`ACCESS EXCLUSIVE` lock, which vacuum will give up on rather than block your
workload, and can be disabled per-table with `vacuum_truncate = off`.)

Which finally explains the incident in §1 exactly:

- Disk did not fall after the `DELETE` — because `DELETE` writes xmax stamps and
  frees nothing. In fact it grew, because 3 million xmax stamps had to be
  written to the WAL and dirtied every page they touched.
- Disk did not fall after the manual `VACUUM` — because (i) the horizon was
  pinned by something (find it!), so almost nothing was removable, and (ii) even
  had it removed everything, the file would not shrink.

### The four ways to actually get the 38 GB back

| method | how it works | lock | cost |
|---|---|---|---|
| **Wait and reuse** | let vacuum keep the space free-listed; future rows fill it | none | free, but the file stays big forever |
| **`VACUUM FULL`** | rewrites the entire table into a brand-new compact file, then swaps and drops the old one | **`ACCESS EXCLUSIVE` for the whole operation** — all reads and writes on that table block | needs up to **2× the table size** in free disk while it runs. On a 41 GB table with 4 GB free, this fails. |
| **`pg_repack` / `pg_squeeze`** (extensions) | build the compact copy concurrently, capture changes with a trigger, swap under a brief lock at the end | brief exclusive lock at the swap only | also needs ~2× space; needs installing an extension; the standard production answer |
| **partitioning** | make the old data a separate partition and `DROP` it | brief | the *correct* answer for time-series retention. `DROP TABLE` on a partition returns the file to the OS instantly, with no vacuum, no rewrite, and no 2× space requirement. |

The last row deserves emphasis, because it is the design-level lesson hiding in a
tuning table: **the winning move on the 03:10 incident is not to run a better
vacuum — it is to have partitioned `payment_attempts` by month so that deleting
90-day-old data is a `DROP`, not a `DELETE`.** Mass `DELETE` on a large table is
the operation MVCC handles worst; it is the one to design away rather than
optimise.

## 15. FIRST-COMMITTER-WINS, in one paragraph, because you already know it

You learned in [isolation-levels](../isolation-levels/) that snapshot isolation
resolves concurrent writers by **first-committer-wins**. Here is the physical
implementation, which is a two-line addition to what you already have.

When Asha (701) tries to update tuple A, she checks A's xmax. If it is set to a
still-running transaction, she cannot proceed — someone else got there first —
so she **waits on that transaction's lock**. When it finishes: if it aborted, she
proceeds; if it committed, then at `READ COMMITTED` she re-reads the *newest*
version and re-evaluates her `WHERE` clause against it (this is the special
"EvalPlanQual" re-check, and it is precisely why `READ COMMITTED` permits lost
update while still not permitting dirty write), and at `REPEATABLE READ` or
`SERIALIZABLE` she gets `ERROR: could not serialize access due to concurrent
update` — SQLSTATE **40001**, the retry-loop error you already know.

So the entire write-conflict rule is: *the xmax field is the conflict detector.*
No separate lock table entry is needed for row-level write conflicts, because the
row version itself carries the claim.

## 16. INDEXES, AND THE OPTIMISATION THAT SAVES THEM — HOT

Return to a cost mentioned in passing. Every new tuple needs an entry in
**every** index on the table, because the new version is at a **new ctid** — a
new physical address — and every index maps key → ctid.

So a table with 6 indexes, updated once: **1 new heap tuple + 6 new index
entries**, even if you only changed one non-indexed column. And later, cleaning
that tuple up costs 6 index scans. Index bloat is frequently worse than heap
bloat, and unlike the heap it directly slows down the lookups you actually care
about.

That is so expensive that PostgreSQL has a special case for it, and the special
case is worth understanding because you can *cause* it or *prevent* it with
schema decisions.

### HOT — Heap-Only Tuple

If **both** of these hold:

1. the `UPDATE` changes **no indexed column**, and
2. the new tuple **fits on the same page** as the old one,

then PostgreSQL performs a **HOT update**:

```
BEFORE                          AFTER a HOT update
page 42                         page 42
 lp[1] NORMAL ──▶ tuple A        lp[1] REDIRECT ──▶ lp[3]
                                 lp[3] NORMAL ────▶ tuple C   (new version)
index entry: 'Meera' → (42,1)   index entry: 'Meera' → (42,1)   ← unchanged!
```

The index entry still points at line pointer 1. Line pointer 1 becomes a
`REDIRECT` that forwards to the new tuple within the same page. **No index was
touched.** The chain of versions lives entirely inside one page — hence
"heap-only tuple", a tuple no index points to.

The payoff compounds: HOT chains can also be pruned *without* an index scan,
opportunistically, by any query that reads the page. So a HOT-heavy workload
largely cleans up after itself, and autovacuum has far less to do.

**The two ways to lose HOT, and both are choices you make:**

- **Indexing a column you update frequently.** Adding an index on
  `sessions.last_seen_at` — which sounds harmless and even helpful — converts
  every single session update from a HOT update into a full index-entry-per-index
  write. This is one of the highest-leverage and least-known facts in PostgreSQL
  schema design: *an index on a hot-updated column costs far more than its own
  maintenance; it destroys HOT for every update of that table.*
- **Filling pages to 100%.** Condition 2 needs free space on the page. A
  freshly-loaded table is packed full, so the first update of every row must go
  to a different page and cannot be HOT. The lever is **`fillfactor`**: `ALTER
  TABLE sessions SET (fillfactor = 85)` tells PostgreSQL to leave 15% of each
  page empty at insert time, reserved for future in-page versions. You are
  spending 15% more disk to buy HOT updates. On an update-heavy table this trade
  is usually strongly positive; on an append-only table it is pure waste, which
  is why the default is 100.

*(PostgreSQL 16 added a related but distinct idea for indexes themselves,
"bottom-up index deletion" — introduced in PG 14 — where a B-tree page about to
split first tries to delete entries it can prove are dead, which specifically
targets the index bloat caused by non-HOT updates of unchanged index columns.
Worth knowing the name; not worth tuning, as it has no knobs.)*

## 17. THE VISIBILITY MAP — and why your "index-only scan" reads the heap

Vacuum keeps a tiny side file per table called the **visibility map** — **two
bits per page**:

- **all-visible**: every tuple on this page is visible to every possible
  transaction (no dead tuples, nothing recent).
- **all-frozen**: stronger; §19.

It exists to let vacuum skip pages: if a page has not changed since it was marked
all-visible, vacuum need not look at it at all. That alone turns vacuuming a
1 TB mostly-static table from a 1 TB read into a small one.

But it acquired a **second job**, and this is where it reaches into your query
plans.

Consider `SELECT count(*) FROM orders WHERE customer_id = 88` with an index on
`customer_id`. In principle the index alone answers this — it contains
`customer_id` values, so why touch the table? Because of everything in §9: **the
index entry does not carry visibility information.** An index entry might point
at a tuple that was deleted, or created by a transaction you cannot see. Only the
tuple header knows.

So the escape hatch is: for each index entry, check the visibility map. If the
target *page* is marked all-visible, then every tuple on it is visible to
everyone, so this one certainly is, and **the heap fetch can be skipped**. If it
is not marked, the heap must be visited to check.

Now the operational consequence, which surprises people badly:

> **An `Index Only Scan` in `EXPLAIN` is not a promise. It is a hope.** Run
> `EXPLAIN (ANALYZE, BUFFERS)` and read the **`Heap Fetches:`** line. On a
> freshly-vacuumed table it says 0 and the query is fast. On a
> recently-written table it can equal the row count, and the "index-only" scan is
> doing exactly as much work as a plain index scan plus overhead.

Which yields a sentence that connects two things you would never have connected:

> **Whether vacuum ran recently is an input to your query performance, not just
> to your disk usage.** A reporting query can be 20× slower on Monday than on
> Sunday because a bulk load on Sunday night cleared the all-visible bits on
> every page it touched and autovacuum has not caught up. Nothing in the plan
> changed. Nothing in the statistics changed. This is also why manually running
> `VACUUM` after a large bulk load — even though there are no dead tuples to
> remove — measurably speeds up subsequent reads: you are setting visibility-map
> bits, not collecting garbage.

---

> ### ✅ CHECKPOINT — end of the INTERMEDIATE MODEL
>
> **You can now:** explain what a dead tuple costs (three distinct costs);
> state the horizon rule and use it to explain why a vacuum that runs
> successfully can remove nothing; list vacuum's five phases and name the
> symptom each one causes; explain why `VACUUM` does not shrink files and name
> the four ways to actually reclaim space; and diagnose a slow "index-only" scan
> from `Heap Fetches`.
>
> **What the next layer adds:** the counter runs out. Everything so far assumed
> XIDs are just increasing numbers. They are 32 bits, they wrap, and the fix for
> that is a second, entirely separate reason vacuum must visit every page of
> every table — including tables that are never written to at all.

---

## 18. WHO DECIDES WHEN TO VACUUM — the autovacuum daemon

Nobody runs `VACUUM` by hand in production. A background daemon does it. Knowing
its decision rule is what lets you predict *why it did not run on the table you
cared about*.

An **autovacuum launcher** wakes every `autovacuum_naptime` (default **1
minute**), picks databases, and starts up to `autovacuum_max_workers` (default
**3**) worker processes. A worker examines each table's statistics and vacuums it
if a threshold is crossed.

**The vacuum trigger:**

```
dead_tuples  >  autovacuum_vacuum_threshold  +  autovacuum_vacuum_scale_factor × reltuples
                          (default 50)                    (default 0.2)
```

Read that as: *"vacuum when 20% of the table is dead."* Which is fine on a
100,000-row table (20,000 dead tuples of slack) and catastrophic on a
100,000,000-row table, where it means **20 million dead tuples must accumulate
before autovacuum will even start** — by which time the table is 20% bloated,
the vacuum is enormous, and it takes hours during which another few million
tuples die.

> **The single most common autovacuum tuning change, and the one to reach for
> first:** lower the scale factor on your big, hot tables, per table.
>
> ```sql
> ALTER TABLE payment_attempts SET (
>   autovacuum_vacuum_scale_factor = 0.01,   -- 1%, not 20%
>   autovacuum_vacuum_threshold    = 1000
> );
> ```
>
> Small, frequent vacuums instead of rare, huge ones. The total work is similar;
> the *peak* is a fraction, and the bloat ceiling drops from 20% to 1%. Some
> shops go further and set `scale_factor = 0` with a fixed threshold, making the
> trigger purely absolute.

**There is a second trigger, for inserts only** (PostgreSQL 13 and later):
`autovacuum_vacuum_insert_threshold` (default **1000**) and
`autovacuum_vacuum_insert_scale_factor` (default **0.2**). It exists because an
insert-only table generates *no dead tuples at all*, so it never crossed the
first trigger, and therefore never got its visibility map set or its tuples
frozen — meaning append-only tables historically sat untouched until they hit an
anti-wraparound emergency, and every index-only scan on them fetched the heap.
If you run an older major version, this is a real gap you must cover with a
scheduled manual `VACUUM`.

**Autovacuum is a polite guest, with one exception that will bite you.** A
normal autovacuum worker holds only a `SHARE UPDATE EXCLUSIVE` lock, which does
not block reads or writes, and it **cancels itself** the moment somebody requests
a conflicting lock (an `ALTER TABLE`, an index build). That is why routine
autovacuum is invisible.

The exception: an **anti-wraparound** autovacuum (§19) does **not** yield.
It cannot — the alternative is the database shutting down. So the shape of a
classic outage is: a migration takes `ACCESS EXCLUSIVE`, queues behind a
six-hour anti-wraparound vacuum, and every query on that table queues behind the
migration. The table is up, the vacuum is working correctly, and the application
is completely down. **If a `DDL` statement is hanging, check
`pg_stat_activity` for an autovacuum worker on that table and look at whether its
query text says `(to prevent wraparound)`.** Killing it is usually the wrong
move; it will simply restart, and the wraparound clock is still ticking.

## 19. FREEZING AND TRANSACTION ID WRAPAROUND — the counter runs out

Everything so far treated XIDs as plain increasing integers. They are **32-bit**,
which gives about **4.29 billion** values, and a busy system can burn through
that in weeks. So they wrap around to the beginning.

Now look at the visibility rule again and notice that it is built entirely on
comparisons like *"is this XID older than that one?"*. With a wrapping counter,
"older" cannot mean "numerically smaller". PostgreSQL therefore compares XIDs
**modulo 2³²**: from where you stand, the **2 billion XIDs behind you are the
past**, and the roughly 2 billion ahead of you are the future.

That is a circle, not a line. And it has a lethal property:

> If a tuple's `xmin` is allowed to fall more than **2 billion** transactions
> behind the current XID, it silently crosses over and starts appearing to be
> **in the future**.
>
> A tuple in the future is invisible to everyone.
>
> **The row disappears. No error. No log entry. It is simply not in your table
> any more, and it is not in your backups either if they are logical dumps.**

This is the worst failure mode in PostgreSQL, and the whole freezing machinery
exists to make it impossible.

### The fix: freezing

**Freezing** marks a tuple as *"created so long ago that it is visible to
everybody, unconditionally, forever"*. Once frozen, its xmin is never compared
again, so it can never cross the horizon of the circle.

*(Mechanically: PostgreSQL 9.4 and later set a flag bit in the tuple's infomask
rather than overwriting xmin with a magic value, so the original XID stays
readable for forensics. Older versions really did overwrite it.)*

Freezing is a **second, independent reason vacuum must visit pages** — and it is
the reason vacuum must eventually visit **every page of every table, including
tables that have never been updated and contain no dead tuples at all.** A
read-only lookup table loaded once in 2019 still needs to be frozen.

### The four ages that govern it

| setting | default | what happens |
|---|---|---|
| `vacuum_freeze_min_age` | 50 million | any vacuum freezes tuples older than this while it is there anyway |
| `vacuum_freeze_table_age` | 150 million | the next vacuum escalates to an **aggressive** scan: it may no longer skip all-visible pages (only all-*frozen* ones), so it reads much more of the table |
| `autovacuum_freeze_max_age` | 200 million | an **anti-wraparound autovacuum is forced**, even on tables with `autovacuum = off`, even in a database nobody is connected to. This is the one that does not yield to locks. |
| `vacuum_failsafe_age` | 1.6 billion (PG 14+) | **emergency**: vacuum abandons the cost-delay throttle and skips index cleanup entirely, doing the bare minimum to freeze tuples fast |

And the endgame, if all four are outrun:

- At roughly **40 million** transactions of margin remaining, every commit starts
  emitting `WARNING: database "x" must be vacuumed within 39,999,999
  transactions`. This is the alarm. It is in your logs, and it is almost always
  ignored for weeks.
- At roughly **a million** remaining, the server **refuses to assign new
  transaction IDs**: `ERROR: database is not accepting commands to avoid
  wraparound data loss`. Your application is down. Reads may still work; anything
  that consumes an XID does not. You must let vacuum finish. (On older major
  versions this meant stopping the server and running in single-user mode —
  a genuinely frightening operation on a production system at 3 a.m.)

**Watch it with one query**, and put it on a dashboard before you need it:

```sql
-- illustrative: how much of the 2-billion budget each table has consumed
SELECT c.relname,
       age(c.relfrozenxid) AS xid_age
FROM   pg_class c
WHERE  c.relkind IN ('r','m')
ORDER  BY xid_age DESC
LIMIT  10;
```

If the top value is climbing steadily past 200 million and never falling back,
autovacuum is losing, and you need to find out why *now* rather than at 1.9
billion.

### A second counter you did not know existed

There is a **multixact ID**, also 32-bit, also wrapping, with its own
`autovacuum_multixact_freeze_max_age` (default **400 million**). A multixact is
allocated when **more than one transaction holds a lock on the same row at once**
— which happens routinely from `SELECT ... FOR SHARE` and, much more often than
people realise, from **foreign-key checks**, which lock the parent row. A schema
with a heavily-referenced parent table (a `tenants` or `accounts` row that every
child row points at) can burn multixacts far faster than XIDs and hit a
wraparound emergency on a counter nobody was monitoring. If you have ever seen
`pg_multixact` grow to tens of gigabytes, this is why.

### An honest note on version drift

Everything above describes 32-bit XIDs. Work on **64-bit transaction IDs** —
which would delete this entire class of failure — has been proposed and reworked
in the PostgreSQL community for many years and, as far as this lab can state, has
**not** landed in a released major version as of PostgreSQL 18. *Treat that as a
claim to verify against your version's release notes rather than a fact to rely
on*; it is exactly the sort of thing that changes. The freezing machinery, the
ages, and the monitoring query above are stable across every version you are
likely to run.

## 20. FAILURE SCENARIOS — ten shapes, and how to tell them apart

Every one of these is the same underlying event — **the horizon stopped moving**,
or **cleanup could not keep up** — wearing a different costume. The diagnostic
skill is telling the costumes apart quickly.

The universal first move, before any of them:

```sql
-- illustrative: who is holding the horizon back, ranked
SELECT pid, state, backend_xmin, age(backend_xmin) AS xmin_age,
       now() - xact_start AS txn_duration, query
FROM   pg_stat_activity
WHERE  backend_xmin IS NOT NULL
ORDER  BY age(backend_xmin) DESC;
```

**1 — `idle in transaction`.** A session `BEGIN`s, does something small, and
stops. *Watch:* `state = 'idle in transaction'` with a large `xmin_age`.
*Guarantee kept:* every reader still sees a correct snapshot — correctness is
never at risk, only space. *Fix:* `idle_in_transaction_session_timeout` (set it;
5–15 minutes is common) and application-side connection hygiene. In an ORM this
is usually a transaction opened around an HTTP request that then makes an
external API call.

**2 — a genuinely long query.** A four-hour analytics job, or `pg_dump`, which
holds one snapshot for its whole run by design. *Watch:* `state = 'active'` with
a long `xact_start`. *Distinguishing it from #1 matters* because the fix is
opposite: you cannot just kill it, the work is real. *Fix:* run it on a replica;
or accept a defined bloat budget for its duration; or make it incremental.

**3 — a long query on a physical replica, with `hot_standby_feedback = on`.**
The nastiest of the family, because the cause is **on a different machine**. The
replica tells the primary "do not remove anything my queries still need", so the
primary's horizon is pinned by a report running somewhere the on-call engineer is
not looking. *Watch:* `pg_stat_replication.backend_xmin` on the primary, and
`pg_replication_slots.xmin`. *The tradeoff is explicit and you must choose a
side:* with feedback **on**, the primary bloats; with it **off**, the replica's
long queries get cancelled with `ERROR: canceling statement due to conflict with
recovery`. There is no setting where both are happy — this is a genuine PACELC-
shaped "somebody pays" choice, not a misconfiguration.

**4 — an abandoned replication slot.** A logical replication or CDC consumer
(Debezium, a read-replica that was decommissioned, a failed upgrade) has a slot
that nobody reads. The slot pins both the WAL **and**, for logical slots, the
horizon. *Watch:* `pg_replication_slots WHERE active = false`, and its
`xmin`/`catalog_xmin`. *This is the one that also fills your disk with WAL,* so
it often presents as two alarms at once. *Fix:* drop the slot — and note the
sharp edge from [change-data-capture](../change-data-capture/): dropping a slot
that a real consumer still needs loses its position permanently.

**5 — an orphaned prepared transaction.** Somebody ran `PREPARE TRANSACTION`
(two-phase commit — see [two-phase-commit](../two-phase-commit/)) and the
coordinator died before resolving it. It survives restarts, holds locks and the
horizon **forever**, and appears in no ordinary session view. *Watch:*
`SELECT * FROM pg_prepared_xacts;` — a query almost nobody runs, which is why
this can go undiagnosed for days. *Fix:* `COMMIT PREPARED` / `ROLLBACK PREPARED`
once you determine the right outcome. If `max_prepared_transactions = 0` (the
default) you can rule this out instantly.

**6 — autovacuum falling behind on a hot table.** No horizon problem at all;
cleanup is simply slower than damage. *Watch:* `n_dead_tup` climbing steadily in
`pg_stat_user_tables` while `last_autovacuum` keeps updating; `n_dead_tup` **not
dropping to near zero after each run** is the signature. *Fix, in order:* lower
that table's `autovacuum_vacuum_scale_factor`; raise `vacuum_cost_limit`; raise
`autovacuum_max_workers` (last — workers share the cost budget, so more workers
each go slower unless you raise the limit too); drop unused indexes; consider
`fillfactor` + removing an index on a hot-updated column to restore HOT.

**7 — autovacuum starved by locks.** On a table receiving frequent DDL or
`LOCK TABLE`, autovacuum politely cancels itself every time and never completes a
pass. *Watch:* `last_autovacuum` far in the past despite high `n_dead_tup`; log
lines about autovacuum being cancelled. *Fix:* find the DDL. This one silently
escalates into #8.

**8 — the anti-wraparound vacuum that will not yield, blocking a migration.**
The inverse of #7 and described in §18. *Watch:* a hanging `ALTER TABLE`; check
for an autovacuum worker whose query text ends `(to prevent wraparound)`. *Do
not kill it* — the wraparound clock does not stop, and it will restart.

**9 — mass `DELETE` with no space returned.** §14. *Watch:* table size flat or
growing after a large delete. *Correct response:* accept the reuse (do nothing),
or `pg_repack`, or — properly — partition so the operation becomes `DROP`.

**10 — wraparound emergency.** §19. *Watch:* `must be vacuumed within N
transactions` in the log, and `age(relfrozenxid)` past 1 billion. *Response:*
find and clear whatever is pinning the horizon (#1–#5 are the usual causes), let
the failsafe vacuum run, and do **not** raise `autovacuum_freeze_max_age` to make
the warning go away — that is turning off the smoke alarm.

**A cross-cutting one, #11, that never looks like a vacuum problem at all:**
someone adds an index on a frequently-updated column in a routine migration.
Every update on that table stops being HOT. Write throughput drops, index bloat
starts, and vacuum's per-run cost roughly doubles — with no error, no alert, and
a migration diff that looks completely innocent. If a table's vacuum cost jumps
after a deploy, look at what got indexed.

## 21. CORRECTNESS AND GUARANTEES — what you actually bought

**Guaranteed by MVCC as described here:**

- **Snapshot stability.** Within one snapshot, a given row's visible version
  never changes, however long you take. Repeated reads return the same answer.
- **Readers never block writers; writers never block readers.** Structurally, not
  by tuning — visibility is a local function of data plus snapshot, so there is
  no shared resource to contend on for *reading*.
- **Dirty reads are impossible.** Not "forbidden at this level" — *impossible*,
  because the visibility rule consults the commit log. This is why PostgreSQL's
  `READ UNCOMMITTED` silently behaves as `READ COMMITTED`, a fact you already
  recorded in [isolation-levels](../isolation-levels/) and can now explain
  mechanically.
- **Rollback is O(1)** and leaves no correctness debt.
- **Cleanup is never visible.** Vacuum only ever removes tuples that no snapshot
  could reach, so its actions are logically invisible — exactly the invariant you
  met as *compaction-is-logically-invisible* in
  [btree-and-lsm](../btree-and-lsm/). Same invariant, different engine.

**Explicitly not guaranteed:**

- **Not serializability.** Snapshot isolation permits **write skew**, because two
  transactions writing *different* rows never touch each other's xmax. You proved
  this in [isolation-levels](../isolation-levels/); PostgreSQL's
  `SERIALIZABLE` adds SSI *on top of* everything in this lab — it keeps the same
  version machinery and adds read-tracking and pivot detection. **MVCC gives you
  snapshot isolation; SSI is a separate layer.**
- **Not bounded space.** There is no upper bound on table size as a function of
  live data. The bound is a function of *the oldest snapshot*, which is
  controlled by client behaviour you may not own.
- **Not bounded cleanup latency.** Vacuum is best-effort and throttled.
- **Not freshness.** A long reader sees old data, by design. That is the feature.

**The assumptions the guarantees rest on:**

1. Every snapshot is eventually released. Violated by #1–#5 above.
2. XIDs are frozen before they age 2 billion. Violated by #10.
3. Cleanup throughput exceeds garbage production, on average. Violated by #6.

Notice the shape: **all three assumptions are about liveness, none about safety.**
MVCC's correctness is never at risk from any failure in this lab. What is at risk
is space, speed, and availability. That is the trade you made back in §3 when you
chose not to make Asha wait — and it is why an MVCC database fails by getting
slower and fatter rather than by getting wrong.

---

> ### ✅ CHECKPOINT — end of the ENGINEERING MODEL
>
> **You can now:** explain freezing and wraparound and why a read-only table
> still needs vacuuming; predict which autovacuum trigger fires and when; work
> the ten failure shapes back to a cause from a symptom; and state precisely what
> MVCC guarantees and what it does not.
>
> **What the last layer adds:** PostgreSQL is one of at least four different
> answers to the same problem. InnoDB, Oracle and SQL Server all implement MVCC
> and **none of them has a VACUUM**. Seeing where they put the old versions
> instead is what turns this from PostgreSQL trivia into a transferable model —
> and it reveals that all four have the same long-running-transaction failure,
> under four different names.

---

## 22. PRODUCTION — the same problem, four different places to put the garbage

Every engine on this page implements MVCC. Every one of them lets readers see old
versions without blocking writers. They differ on **one decision**: *where does
the old version physically live?* Everything else — including whether they need a
VACUUM at all — falls out of that one choice.

### PostgreSQL — old versions live in the table

What you have just learned. New versions are appended to the heap alongside the
current ones; the table *is* the version store.

- Rollback: **free**.
- Reading an old version: **free** (it is right there, in the same page, often
  the same cache line's neighbourhood).
- Reading the *current* version: pays for every dead tuple in the way.
- Cleanup: a separate, throttled, failure-prone background process — **VACUUM**.
- The bill: **space in your tables and indexes**.

### MySQL / InnoDB — old versions live in the undo log

InnoDB updates the row **in place** in the clustered index and writes the
*before-image* into an **undo log record** in an undo tablespace. Each clustered
index record carries `DB_TRX_ID` (which transaction last modified it) and
`DB_ROLL_PTR` (a pointer to the undo record holding the previous version). Old
versions are a linked list running *backwards out of* the table into the undo
log.

A reader with an old **read view** that finds a too-new `DB_TRX_ID` follows
`DB_ROLL_PTR` and **reconstructs** the older version by applying undo records,
one hop at a time.

- Rollback: **expensive** — real undo work, exactly the ARIES shape from
  [acid](../acid/).
- Reading the current version: **fast** — the table contains only current rows.
- Reading an *old* version: **slow, and slower the older it is** — every hop is
  a random read plus a reconstruction. A long transaction gets progressively more
  expensive as the chain it must walk grows.
- Cleanup: **purge threads** (`innodb_purge_threads`, default 4) discard undo
  records no read view needs and physically remove delete-marked index entries.
- The bill: **undo tablespace growth and purge lag**, measured by **`History
  list length`** in `SHOW ENGINE INNODB STATUS` — a noun you already recorded in
  the [acid](../acid/) lab, and which you can now read as *"the number of undo
  records the oldest open read view is preventing purge from discarding."*
  Watching that number climb is watching a PostgreSQL bloat incident in another
  language.

Two structural notes worth carrying: InnoDB's secondary indexes have **no
per-row transaction ID** — instead each index page carries a `PAGE_MAX_TRX_ID`,
and if that is old enough relative to the read view, the index can answer without
touching the clustered index. *That is InnoDB's visibility map, at page
granularity, doing the same job as §17.* And InnoDB transaction IDs are **48-bit
counters**, so there is no wraparound, no freezing, and no anti-wraparound
vacuum — that entire family of PostgreSQL failure modes simply does not exist
there. (MySQL 8.0 also finally made undo tablespaces truncatable, which earlier
versions could not do — the direct analogue of "VACUUM does not shrink the
file".)

### Oracle — undo segments, and a fundamentally different answer to slow readers

Structurally close to InnoDB: in-place update, before-images in **undo
tablespaces**, consistent reads reconstructed by applying undo to a block as of
your SCN.

The interesting difference is what happens when a reader is *too slow*. Oracle
keeps undo for `UNDO_RETENTION` and then **overwrites it**. A long-running query
that later needs a before-image which has been recycled does not get a wrong
answer, and does not get a slow answer. It gets:

> `ORA-01555: snapshot too old: rollback segment too small`

**Read that as a design statement, not an error message.** Oracle has chosen to
**bound the space and sacrifice the reader**. PostgreSQL has chosen to
**guarantee the reader and let the space grow without bound**. Those are the only
two options, and there is no third — this is the central tradeoff of the entire
lab, stated as cleanly as it ever gets.

PostgreSQL once offered an opt-in switch to Oracle's side of that trade,
`old_snapshot_threshold`, which raised a `snapshot too old` error rather than
retaining versions forever. It was **removed in PostgreSQL 17** after long-standing
correctness problems. *[Version-specific; verify against your release notes.]*
Which means: on modern PostgreSQL, **you cannot opt out of unbounded retention.**
Your only lever is client discipline.

### SQL Server — versions in a side store, in a different database

Snapshot behaviour is **off by default**; you enable it per database with
`READ_COMMITTED_SNAPSHOT ON` (a switch you already flagged in
[isolation-levels](../isolation-levels/) as one that changes application
behaviour with no code change) or per transaction with `SET TRANSACTION ISOLATION
LEVEL SNAPSHOT`. Row versions get a 14-byte pointer appended and the old versions
are copied into the **version store in `tempdb`**.

The failure mode is therefore *"`tempdb` filled up"*, and its cause is — again —
one long-running transaction preventing version-store cleanup. Same disease,
third pathology report. (SQL Server 2019's Accelerated Database Recovery moved
this to a Persistent Version Store inside the user database, partly to fix
exactly this.)

### CockroachDB / Pebble — versions in the key encoding

You have this one already from [cockroachdb](../cockroachdb/): every key is
stored as `key/timestamp → value`, so versions are literally adjacent entries in
the sorted keyspace, and old versions are removed by MVCC garbage collection once
they are older than **`gc.ttlseconds` (default 14400 — 4 hours)**.

Which puts CockroachDB firmly in **Oracle's camp**: retention is *bounded by
time*, and a read older than the GC threshold **fails** rather than being served.
And the thing that stops GC for a backup or a changefeed is called a **protected
timestamp** — a noun you already have, which you can now recognise as *an
explicit, named, revocable handle on the horizon*, which is arguably what
PostgreSQL is missing.

### The whole comparison in one table

| | **PostgreSQL** | **InnoDB / Oracle** | **SQL Server (RCSI)** | **CockroachDB** |
|---|---|---|---|---|
| old versions live | in the table | in undo logs | in `tempdb` | in the keyspace |
| current row read | pays for dead tuples in the way | fast — table is clean | fast | pays for versions in the way |
| old version read | free | costly, grows with age | pointer chase | free |
| rollback | free, O(1) | real undo work | real undo work | free |
| cleanup process | **VACUUM** / autovacuum | **purge threads** | version-store cleanup | **MVCC GC** |
| retention bound | **none** — the oldest snapshot | time / space based | until cleanup | **time** (`gc.ttlseconds`) |
| slow reader gets | a correct answer, always | `ORA-01555` (Oracle) | error 3958 | `batch timestamp … must be after GC threshold` |
| the bill lands as | table + index bloat | undo growth, purge lag, history list length | `tempdb` growth | range size / GC queue |
| counter wraparound | **yes** — 32-bit XIDs, freezing | no (48-bit / SCN) | no | no |

## 23. THE ONE TRUTH UNDERNEATH ALL FOUR

Strip away the storage layouts and every row of that table says the same thing:

> **In any MVCC system, the amount of history that must be retained is determined
> by the oldest snapshot still alive — and that snapshot is held by a client the
> database does not control.**

Which yields a rule you can apply to any versioned system you meet for the rest
of your career, including ones that do not exist yet:

> **Find the horizon. Find who holds it. That is your bloat, your undo growth,
> your `tempdb`, your GC queue, your S3 lifecycle backlog, your Kafka log
> retention, your git pack size.** The mechanism differs; the shape never does.

And the corollary, which is the one to say out loud in a design review:

> **A long-running transaction is not a slow query. It is a resource leak with a
> global scope, and it is the only kind of leak that a completely idle,
> read-only, unprivileged client can cause.**

## 24. THE OPERATOR'S TOOLKIT — what to look at, in what order

**The four queries.** These are the whole runbook; everything else is
interpretation.

```sql
-- 1. Is anything holding the horizon?  (run this FIRST, always)
SELECT pid, state, age(backend_xmin) AS xmin_age,
       now() - xact_start AS duration, left(query, 60)
FROM   pg_stat_activity WHERE backend_xmin IS NOT NULL
ORDER  BY age(backend_xmin) DESC LIMIT 5;

-- 2. The three non-session horizon holders that query misses
SELECT slot_name, active, xmin, catalog_xmin FROM pg_replication_slots;
SELECT * FROM pg_prepared_xacts;
SELECT application_name, backend_xmin FROM pg_stat_replication;

-- 3. Which tables are actually bloated, and is vacuum reaching them?
SELECT relname, n_live_tup, n_dead_tup,
       round(100.0 * n_dead_tup / NULLIF(n_live_tup + n_dead_tup, 0), 1) AS pct_dead,
       last_autovacuum, autovacuum_count
FROM   pg_stat_user_tables ORDER BY n_dead_tup DESC LIMIT 10;

-- 4. How close is anything to wraparound?
SELECT relname, age(relfrozenxid) FROM pg_class
WHERE  relkind IN ('r','m') ORDER BY 2 DESC LIMIT 10;
```

**The one live view worth knowing:** `pg_stat_progress_vacuum` shows a running
vacuum's phase and how far through the heap it is — the difference between "it is
stuck" and "it is on pass 3 of 7 through the indexes".

**The extensions worth installing before you need them:** `pgstattuple` (measures
real bloat precisely, at the cost of reading the table), `pg_visibility`
(inspects the visibility map — the tool for "why is my index-only scan fetching
heap"), `pageinspect` (`heap_page_items()` shows you raw xmin/xmax/line-pointer
states, which makes everything in §10 directly observable).

**The four metrics on the dashboard:** `n_dead_tup` per big table · `max(age(relfrozenxid))`
across the cluster · oldest `backend_xmin` age · count of inactive replication
slots. Those four catch every failure in §20.

**The settings, ranked by how often they are the right change:**

1. `idle_in_transaction_session_timeout` — set it. Catches the most common cause.
2. Per-table `autovacuum_vacuum_scale_factor` on your large hot tables — 0.01–0.05.
3. `vacuum_cost_limit` — the default 200 assumes hardware from another era.
4. `fillfactor` (70–90) on update-heavy tables, plus deleting the index on the
   hot-updated column — both to restore HOT.
5. `autovacuum_max_workers` — raise *with* the cost limit, not instead of it.
6. `log_autovacuum_min_duration` — set to something like `1s` so vacuum activity
   is in your logs *before* the incident, not after.

**Never do these:** turn autovacuum off ("it is causing load") — the load does
not go away, it accumulates and arrives later as an anti-wraparound vacuum that
does not yield; raise `autovacuum_freeze_max_age` to silence the wraparound
warning; run `VACUUM FULL` on a large table during business hours; or reach for
`VACUUM` at all before you have run query #1.

## 25. COMMON WRONG MODELS

**"`DELETE` frees space."** It writes an xmax and returns. It *consumes* space —
WAL records and dirtied pages — and creates work for later. *Counterexample:* §1's
3-million-row delete, after which disk usage went **up**. Correct model: `DELETE`
marks; `VACUUM` reclaims *within the file*; only `TRUNCATE`, `DROP`, `VACUUM
FULL` or `pg_repack` return bytes to the OS.

**"`VACUUM` shrinks tables."** It makes space reusable inside the existing file.
Its normal, healthy outcome is that *growth stops*, not that size falls.
*Counterexample:* the manual vacuum in §1 that completed successfully and freed
zero bytes.

**"Bloat means autovacuum isn't running."** Usually autovacuum is running
perfectly and is *forbidden* from removing anything, because the horizon is
pinned. *Counterexample:* §12's prediction — dozens of successful vacuums, zero
tuples removed, no errors. The first query is `pg_stat_activity`, not
`last_autovacuum`.

**"A read-only transaction is harmless."** A read-only transaction is precisely
the dangerous kind, because it holds a snapshot while holding no locks, so every
lock-based diagnostic shows a clean system. *Counterexample:* the developer at
lunch in §12.

**"MVCC means transactions never block each other."** Readers never block
writers and writers never block readers. **Writers absolutely still block
writers** — two transactions updating the same row serialise on that row's xmax
(§15). MVCC removes one of the three contention pairs, not all three.

**"MVCC gives me serializability."** It gives snapshot isolation. Write skew
survives it; you measured that in [isolation-levels](../isolation-levels/).
PostgreSQL's `SERIALIZABLE` is SSI layered *on top of* the machinery in this lab.
Oracle's `SERIALIZABLE` is snapshot isolation and permits write skew.

**"An `Index Only Scan` doesn't touch the table."** It touches the table for
every row whose page is not marked all-visible. Read `Heap Fetches` in `EXPLAIN
(ANALYZE)`. Its performance is a function of *how recently vacuum ran* (§17).

**"Wraparound is a legacy problem that modern versions fixed."** The failsafe
(PG 14) and better freezing make it much harder to reach, but XIDs are still
32-bit and the shutdown behaviour is still there. It is *rarer*, not *gone*.

**"An extra index just costs a bit of write amplification."** On a
frequently-updated table, an index on the updated column also destroys HOT for
every update on that table, multiplying heap bloat and vacuum cost (§16, failure
#11). Its cost is not local to itself.

**"Adding a column with a default rewrites the table, so it is like an
`UPDATE`."** Since PostgreSQL 11 a non-volatile default is stored in the catalog
and **no rows are rewritten** — this is one of the few schema operations that
does *not* generate a table's worth of dead tuples. Worth knowing precisely,
because the older folk wisdom is now wrong.

## 26. KNOWLEDGE MAP AND NEXT STEPS

**This lab sits directly downstream of:**

- [isolation-levels](../isolation-levels/) — you learned *which anomalies each
  level forbids*. This lab is the machinery underneath the snapshot-based half of
  that table, and it explains mechanically why PostgreSQL's `READ UNCOMMITTED`
  cannot produce a dirty read.
- [acid](../acid/) — the WAL and ARIES undo. This lab is the striking contrast:
  a no-overwrite heap needs no undo pass, and pays for that in a background
  collector instead. Its note that InnoDB's undo tablespace "also serves MVCC
  reads" is now fully cashed in (§22).
- [btree-and-lsm](../btree-and-lsm/) — pages, line pointers, write/read/space
  amplification, and *compaction-is-logically-invisible*, which is the same
  invariant vacuum obeys.

**Commonly confused with:**

- **MVCC vs optimistic locking** ([optimistic-vs-pessimistic-locking](../optimistic-vs-pessimistic-locking/)):
  MVCC is how the engine stores and shows versions; optimistic locking is an
  application-level conflict *policy*. You can run either policy on an MVCC
  engine.
- **VACUUM vs LSM compaction** ([btree-and-lsm](../btree-and-lsm/)): both
  discard shadowed data in the background, but compaction *rewrites and merges
  files* (and reclaims to the filesystem), while vacuum *frees slots in place*
  (and does not).
- **VACUUM vs garbage collection** ([jvm-garbage-collectors](../jvm-garbage-collectors/)):
  strikingly close, and the difference is the interesting part. A GC decides
  death by **reachability from roots** — it never finds the dead, it defines them
  as everything it did not reach. Vacuum decides death by **visibility relative
  to the oldest snapshot** — a horizon, not a graph walk. Both suffer the same
  pathology from the same cause: *a long-lived reference (a GC root / an old
  snapshot) keeps garbage alive*, and in both systems the cost is paid in a
  background process competing with your application. If it helps: **the oldest
  snapshot is vacuum's GC root.**
- **Snapshot (MVCC) vs snapshot (backup)**: unrelated meanings of the word;
  a filesystem/volume snapshot is a point-in-time copy of bytes.

**Suggested next lessons, ready to type:**

- `/learn database-indexing` — the one layer this lab kept treating as a black
  box, and the place where "which index should exist" meets "what an index costs
  every update".
- `/learn wal-replication-and-pitr` — the other half of the WAL story that
  [acid](../acid/) left open; replication slots appeared here as failure #4 and
  deserve their own treatment.
- `/learn postgres-query-planning` — why `Heap Fetches`, `n_distinct` and stale
  statistics change plans, which is the natural sequel to §17.
- `/learn toast-and-large-values` — how PostgreSQL stores values too big for a
  page, and why an `UPDATE` that does not touch a large column is cheaper than
  you would guess from §11's arithmetic.
- `/learn zheap-and-undo-in-postgres` — the abandoned/incomplete attempt to give
  PostgreSQL an InnoDB-style undo heap, which is the best possible way to
  understand what the current design actually buys.

## 27. GLOSSARY

In order of appearance.

- **Tuple** — one physical version of one row (PostgreSQL's usage). One row may
  be many tuples.
- **Version chain** — all tuples that are copies of the same logical row.
- **xmin (tuple)** — the XID of the transaction that created this tuple.
- **xmax (tuple)** — the XID of the transaction that deleted or superseded it; 0
  if none. May instead record a row *lock*, distinguished by flag bits.
- **XID / transaction ID** — a 32-bit, wrapping counter identifying a transaction.
- **Commit log (`pg_xact`, formerly `clog`)** — two bits per XID recording
  in-progress / committed / aborted.
- **Snapshot** — `(xmin, xmax, xip[])`: the set of transactions a reader is
  allowed to believe. Not a timestamp.
- **Snapshot xmin / xmax** — the snapshot's bounds; distinct from tuple stamps of
  the same name.
- **xip[]** — the list of XIDs in progress when the snapshot was taken.
- **Visibility rule** — created-by-a-committed-transaction-before-my-snapshot and
  not-deleted-by-one.
- **cmin / cmax** — command numbers within a transaction, so a statement does not
  see its own output.
- **infomask** — the tuple header's flag bits (xmin committed, xmax is a lock,
  frozen, HOT-updated, …).
- **Heap** — the table's main data file: a sequence of 8 KB pages.
- **Line pointer (ItemId)** — a 4-byte slot at the top of a page holding a
  tuple's offset and one of four states: `NORMAL`, `DEAD`, `UNUSED`, `REDIRECT`.
- **ctid** — a tuple's physical address, `(page, line pointer)`. What every index
  entry points at. Changes on every non-HOT update.
- **Dead tuple** — a tuple invisible to every possible snapshot; garbage.
- **Bloat** — the gap between a table's live data size and its file size.
- **Horizon (OldestXmin, `backend_xmin`)** — the minimum snapshot xmin across the
  whole system. Nothing at or above it may be removed.
- **VACUUM** — the process that removes dead tuples, cleans indexes, updates the
  free space and visibility maps, and freezes old tuples.
- **Pruning** — in-page removal of dead tuples, done by vacuum and
  opportunistically by ordinary reads.
- **Free space map (FSM)** — a per-table fork recording free space per page, so
  inserts can find reusable room.
- **Visibility map (VM)** — a per-table fork with two bits per page: all-visible
  and all-frozen. Lets vacuum skip pages and lets index-only scans skip heap
  fetches.
- **Hint bits** — cached commit status written into a tuple header by the first
  reader after commit; why read queries can dirty pages.
- **Cost-based delay** — vacuum's deliberate self-throttling
  (`vacuum_cost_limit`, `vacuum_cost_delay`).
- **maintenance_work_mem** — the memory budget for vacuum's dead-tuple list;
  overflowing it caused repeated index passes (much improved in PG 17).
- **`VACUUM FULL`** — a full table rewrite into a new compact file. Takes
  `ACCESS EXCLUSIVE`; needs ~2× the space.
- **pg_repack / pg_squeeze** — extensions that achieve the same compaction with
  only a brief lock.
- **Truncation** — vacuum returning *trailing* empty pages to the OS; the only
  case where ordinary vacuum shrinks a file.
- **HOT (Heap-Only Tuple)** — an update that touches no indexed column and fits
  on the same page, so no index entry is created; the old line pointer becomes a
  `REDIRECT`.
- **fillfactor** — the percentage of a page filled at insert time; free space left
  behind enables HOT.
- **Bottom-up index deletion** — a B-tree's attempt (PG 14+) to delete provably
  dead entries instead of splitting a page.
- **Index-only scan** — an index scan that skips the heap fetch when the target
  page is marked all-visible. `Heap Fetches` in `EXPLAIN` tells you how often it
  failed to.
- **Autovacuum** — the daemon: a launcher plus up to `autovacuum_max_workers`
  workers, triggered by `threshold + scale_factor × reltuples`.
- **Anti-wraparound vacuum** — a forced vacuum at `autovacuum_freeze_max_age`
  that does **not** yield to conflicting lock requests.
- **Freezing** — marking a tuple as unconditionally visible to everyone, so its
  XID is never compared again.
- **Transaction ID wraparound** — the failure where an unfrozen tuple's xmin ages
  past 2 billion and appears to be in the future, making the row vanish.
- **relfrozenxid / datfrozenxid** — the oldest unfrozen XID in a table /
  database; `age()` of it is your wraparound budget consumed.
- **Failsafe (`vacuum_failsafe_age`)** — PG 14+ emergency mode: skip index
  cleanup and throttling to freeze fast.
- **Multixact** — a second wrapping 32-bit counter, allocated when multiple
  transactions lock the same row (notably via foreign-key checks).
- **`idle in transaction`** — a session inside an open transaction running
  nothing; the most common horizon holder.
- **Replication slot** — a server-side position marker for a consumer; an
  inactive one pins both WAL and (for logical slots) the horizon.
- **Prepared transaction** — a `PREPARE TRANSACTION` awaiting resolution; survives
  restart and pins the horizon indefinitely. Visible only in
  `pg_prepared_xacts`.
- **hot_standby_feedback** — a replica telling the primary not to remove versions
  its queries need; trades primary bloat against replica query cancellations.
- **Undo log / rollback segment** — where InnoDB and Oracle put old versions
  instead of the table.
- **DB_TRX_ID / DB_ROLL_PTR** — InnoDB's per-row transaction stamp and pointer
  into the undo chain.
- **Read view** — InnoDB's name for a snapshot.
- **Purge thread** — InnoDB's vacuum.
- **History list length** — InnoDB's bloat metric: undo records not yet purged.
- **ORA-01555 "snapshot too old"** — Oracle's choice to fail the slow reader
  rather than retain versions forever.
- **`old_snapshot_threshold`** — PostgreSQL's removed (PG 17) opt-in to that same
  behaviour.
- **Version store** — SQL Server's `tempdb` area holding row versions under
  RCSI/snapshot isolation.
- **Protected timestamp** — CockroachDB's explicit, named handle preventing MVCC
  GC below a point, for backups and changefeeds.
- **`gc.ttlseconds`** — CockroachDB's time-bounded retention (default 4 hours).

---

*Lab built 2026-08-30. Everything marked `[Version-specific]` should be checked
against your own major version's release notes; PostgreSQL's vacuum internals
change meaningfully between releases and this lab describes roughly the 14–18
era, with older behaviour noted where it is still commonly encountered.*
