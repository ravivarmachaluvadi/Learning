# ACID — exercises

Eight rungs, easiest first. Everything here is pencil-and-paper or done in
`visualization.html`. **No code.** Answers in `SOLUTIONS.md` — attempt first;
rungs 3, 5 and 6 are built so that being wrong teaches you something specific.

Reference state used throughout, same as the doc and the page:

```
page 5 = Ravi ₹700      page 9 = Meera ₹300      total must be ₹1000
T1 = move ₹500 Ravi → Meera        T2 = move ₹100 Meera → Ravi
```

---

## Rung 1 — Explain

**1.1** In your own words, in under five sentences and without using the words
*log*, *WAL*, *redo* or *undo*: what problem does a database have that an
ordinary program writing to a file does not, and what is the one idea that
solves it?

**1.2** A colleague says: *"We do both updates inside one method and it's fast,
so a crash in between is basically impossible."* Give the one-sentence reply
that shows why "fast" is not an answer.

**1.3** Explain to a non-engineer why a transaction can be *committed* even
though not one byte of the changed data has been written to the data file yet.

---

## Rung 2 — Identify

Open `visualization.html`, press **Run the whole story**, and pause partway.

**2.1** At any moment, name which of the four boxes on screen would still exist
after (a) `kill -9` on the database process, (b) a power cut.

**2.2** Click page 9 in the bound ledger. What is its *stamp*, what is that
stamp called in a real database, and what single decision does recovery use it
for?

**2.3** Click a day-book line of type `update`. Which field would recovery use
to **finish** the job, and which to **take it back**?

**2.4** After the whole story finishes, count the lines in the sealed day-book.
Which of them were written by *recovery itself* rather than by Asha, and why do
they need to be in the log at all?

---

## Rung 3 — Predict

Write your answer down before touching the page.

**3.1** T1 has committed and both its pages have already been written to the
safe. The power is cut. How many of recovery's three passes actually change
anything?

**3.2** The log has `#101 T1 BEGIN`, `#102 T1 p5 ₹700→₹200`, and nothing else.
Page 5 on disk reads ₹200 with stamp `#102`. Is that state legal? What will
recovery do, and what will the total be afterwards?

**3.3** Two transactions, T1 and T2, are both running. T1 commits. The power is
cut. During the **redo** pass, does recovery re-apply T2's changes? Give the
reason, not just the answer.

**3.4** You turn *off* "seal the log before saying done" and *on* "the safe lies
about sealing", then commit T1 and kill the **process**. Predict the verdict.
Now predict it for a power cut.

---

## Rung 4 — Modify

Each of these changes exactly one switch on the page. For each: say what breaks,
name **which of A / C / I / D** is lost, and say whether the loss is *loud* (an
error appears) or *silent*.

**4.1** Turn the day-book off entirely.

**4.2** Turn "allow stealing pages" off. Nothing breaks in the simulation — so
what does it cost instead, and why does no production engine choose it?

**4.3** Turn "force pages at commit" on. What does this buy, what does it cost,
and which of the two recovery passes could you now delete?

**4.4** Turn "keep a full page copy in the log" off, then crash during a page
write. Why can redo not repair the result, when redo can repair a page that is
simply out of date?

---

## Rung 5 — Trace

Do this on paper. Then check it against §10.5 of the README and against the
page.

Starting state: page 5 = ₹700 stamp `#100`, page 9 = ₹300 stamp `#100`.
The sealed day-book after the crash reads:

```
#100  CHECKPOINT
#101  T1 BEGIN
#102  T1  p5  before ₹700  after ₹200   prevLSN #101
#103  T1  p9  before ₹300  after ₹800   prevLSN #102
#104  T1 COMMIT
#105  T2 BEGIN
#106  T2  p5  before ₹200  after ₹300   prevLSN #105
#107  T2  p9  before ₹800  after ₹700   prevLSN #106
```

The data file at crash time: **page 5 = ₹300 stamp `#106`**,
**page 9 = ₹300 stamp `#100`**.

**5.1** Run the analysis pass. Write down the losers and the LSN redo starts
from.

**5.2** Run the redo pass. For each record, write *apply* or *skip*, the reason,
and the resulting page value. State the total at the end of redo.

**5.3** Run the undo pass. Write every compensation record you produce,
including its `undoNext`, and the page value after each.

**5.4** Give the final balances and the total. Which transaction survived?

**5.5** Now suppose the machine dies again immediately after your **first**
compensation record was sealed. Redo the whole recovery from #100 and show that
you land in the same place.

---

## Rung 6 — Spot the flaw

Each variant below is described in words. For each: construct a **concrete run**
with real values where it produces a wrong answer, and name the guarantee that
run violates.

**6.1** *"Before-images are wasteful. We'll log only the new value, and to roll
back a transaction we'll just re-read the row from the data file and put that
value back."*

**6.2** *"The pageLSN check is redundant — we only redo transactions that
committed, and a committed transaction's changes are correct, so applying them
twice is harmless."*

**6.3** *"The undo pass should skip losers whose changes never reached the data
file. It's faster: check whether the page on disk has the change, and only undo
if it does."*

**6.4** *"Group commit is just batching. Let's ack each transaction as soon as
its commit record is in the log buffer, and fsync in the background every 10 ms
— clients get their answer sooner and the data reaches the disk almost
immediately anyway."*

**6.5** *"Our schema has no CHECK constraints because the service layer
validates everything before writing. It's the same guarantee, just enforced
earlier."*

---

## Rung 7 — Handle failure

Design work on paper. State your assumptions.

**7.1** F9 in the README: the client sends a transfer, the database commits it
durably, and the ack is lost. The client retries. Design the smallest change
that makes the retry harmless — say exactly what is stored, *where*, and *in
which transaction*, and explain why storing it anywhere else fails.

**7.2** A transaction updates one million rows and is aborted 90% of the way
through. The machine then crashes in a loop, coming up and dying again every
two minutes. Explain, step by step, why this terminates — and what would happen
in a design without compensation records.

**7.3** Your database's storage turns out to acknowledge `fsync` before the data
is stable (F6). You cannot change the hardware this quarter. Describe two
different mitigations that operate at *different rungs of the durability
ladder*, and say what each one does and does not cover.

**7.4** Extend the lab's design so that a crash **during the analysis pass** is
survivable, and explain why this one is easier than surviving a crash during
undo.

---

## Rung 8 — Design

Open-ended. There is no single right answer; there are answers that name the
right tradeoffs.

**8.1 — A payments ledger across three regions.**
You are designing the store of record for payments. Three regions, ~80 ms
apart. The business rule: *a payment acknowledged to a merchant must never be
lost, even if a region is destroyed.* Traffic: 3,000 writes/second, p99 latency
budget 250 ms end to end.

State: which rung of the durability ladder you commit to and why; what that
costs per write; what your commit path looks like; what you monitor; and what
happens to your latency budget during a failover. Then name the part of your
design that you would be least surprised to be woken up about.

**8.2 — The order that must also become a Kafka event.**
An order must be persisted *and* an `OrderPlaced` event must reach Kafka.
Walk the decision tree in README §13.6, choose a design, and defend it. Then
state precisely which ACID letters your chosen design still gives you and which
it gives up, and what the business consequence of each loss is.

**8.3 — Tuning under a real complaint.**
A team reports: after every crash their database takes 6 minutes to accept
connections, and separately their WAL volume fills up twice a month. They ask
you to "make checkpoints less frequent, since checkpoints cause the disk
writes". Say whether that helps or hurts each of the two problems, name the
actual cause of each, and give the change you would make first plus the metric
you would watch to confirm it.

**8.4 — The invariant that isn't a constraint.**
Your system must guarantee: *the sum of all account balances equals the sum of
all deposits minus all withdrawals.* You cannot express that as a `CHECK` on a
row. Given README §12.2, describe three different places this rule could be
enforced, and rank them by how many writers each one actually protects against.
