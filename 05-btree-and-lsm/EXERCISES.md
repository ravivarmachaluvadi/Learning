# Exercises — the eight-rung ladder

Work top to bottom. Attempt before opening [`SOLUTIONS.md`](SOLUTIONS.md).
Rungs 3–7 are runnable: the machine, not the answer key, tells you if you're
right.

Run everything from the lab root (`~/Documents/Learning/05-btree-and-lsm`).
Compile once, then run each suite by class name:

```
javac -d out src/*.java tests/*.java experiments/*.java
java -cp out BTreeTest        # 6 passed, 0 failed
java -cp out LsmTest          # 6 passed, 0 failed
java -cp out ExercisesTest    # 0 passed, 8 failed
```

(12 engine tests pass today; 8 exercise tests are RED until rung 5.)

---

## Rung 1 — Explain (no notation, no diagrams)

**1.1** Explain to a teammate, in five sentences, why databases needed to
invent *anything* here — what exactly goes wrong if you just keep your data
in one big sorted file?

**1.2** Explain the B-tree and the LSM-tree as two answers to the *same*
question. Your answer must contain the phrase "at write time" and the phrase
"later, in bulk."

**1.3** A colleague says "LSM-trees are faster than B-trees, that's why all
the modern databases use them." What's the honest correction? (Name the
currency each engine pays in.)

**1.4** Why can't an LSM-tree delete a row? Explain without using the word
"tombstone", then say the word and define it.

## Rung 2 — Identify (in the visualization)

Open `visualization.html` (or the published artifact).

**2.1** Machine 1: insert 12 sequential keys. Which box is the root, which
are signposts, which hold the data? What do the numbers *inside* a signpost
page mean — are they data or directions?

**2.2** Machine 1: press **Find a key** for a key that isn't there. How many
pages does it read, and how does that compare to a key that *is* there? Why
are they the same?

**2.3** Machine 2: write until the first flush. What is now in the memtable,
what is in the WAL counter, and why did the WAL counter change the way it
did?

**2.4** Machine 2: after "Overwrite k07" and one more flush, find both copies
of `k07` on the shelf. Which one wins a read, and what tells you so from the
screen alone?

## Rung 3 — Predict (commit, then run)

**3.1** Machine 1, empty tree, 4-key pages. At which insert number does the
tree first reach height 2? Height 3? Predict both, then insert and count.

**3.2** Before running: `java -cp out Exp1Amplification` — order
these four numbers from smallest to largest without peeking: B-tree write
amp, B-tree read amp, LSM write amp, LSM read amp. Then run it.

**3.3** Before running `java -cp out Exp2KeyOrder`: two trees get
1,000 inserts each, one sequential and one scattered. Predict the ratio of
**cold pages** between them (2×? 10×? 100×?). Then run it.

**3.4** Before running `java -cp out Exp3Bloom`: 8 SSTables, 2,000
gets for keys that were never written. Predict disk probes per get with
blooms off and with blooms on. Then run it.

**3.5** Machine 2: press **Compact the shelf** immediately after a delete of
`k07` has flushed. Predict what happens to (a) the disk-bytes counter,
(b) the number of files, (c) the answer to "Read k07". Then do it.

## Rung 4 — Modify

**4.1** Change the B-tree's `maxKeys` from 4 to 32 in
`experiments/Exp1Amplification.java` — the argument of `new BTree(4)`.
Predict, then measure: what happens to height, page count, splits, and write
amp? Which single real-world knob did you just turn?

**4.2** In `experiments/Exp1Amplification.java`, change the LSM's memtable
limit from 200 to 20 — the first argument of `new Lsm(d, 200, 6)`. Predict,
then measure: flushes, file count, read amp, write amp. Which production
symptom (§11) did you just simulate?

**4.3** Set the compaction threshold to 999 (never compact) in exp1 — the
second argument of `new Lsm(d, 200, 6)` — and re-run. Which amplification gets
worse, and which stays flat? Then switch the engine to the five-argument
constructor with `useBloom = false` as well. Which failure from §11 have you
now built on purpose?

**4.4** Give the *reading* engines in `Exp3Bloom.java` 2 bits per key instead
of 10: `new Lsm(d, useBloom)` → `new Lsm(d, 6, 4, useBloom, 2)`. (A bloom
filter is rebuilt from the file whenever a table is loaded, so it is the
reader's setting that decides — changing only the writer's measures nothing.)
Predict the false-positive rate, then measure. What did you buy, and what did
you pay?

## Rung 5 — Implement (red → green)

Two stubs in `src/Exercises.java` throw `UnsupportedOperationException`; the
8 tests in `tests/ExercisesTest.java` fail until you fill them in.

**5.1** `predictedHeight(nKeys, maxKeys)` — the smallest height a B+ tree
of `nKeys` keys could have if every page were perfectly full. This is the
floor on read cost; the test checks it against real trees.

**5.2** `newestWinsMerge(tables)` — the core of compaction: merge sorted
tables, newest value per key wins, tombstoned keys vanish. Watch the trap:
the tables arrive in arbitrary order, and `seq` (not list position) says
which is newer.

Green when `java -cp out ExercisesTest` reports 8 passed — 20 across all
three suites.

## Rung 6 — Debug (a planted bug)

`src/BrokenLsm.java` is the real engine with **one expression changed** in
`compact()`. Run:

```
java -cp out ExpDebugBroken
```

**6.1** Which expression is wrong, and what is it doing instead of what it
should?
**6.2** Which test in `tests/LsmTest.java` catches it? (Predict first, then
verify by pointing that test at `BrokenLsm` — one line in `fresh()`.)
**6.3** Which README invariant does it violate?
**6.4** A user of a real database running this code writes a row, deletes it,
and compaction runs overnight. What do they see the next morning — and why is
this class of bug considered among the worst a storage engine can have?

## Rung 7 — Handle failure

**7.1 (runnable)** Our LSM truncates the WAL at flush. Suppose flushing the
SSTable *succeeded* but the process died before the WAL was truncated. What
happens on restart — data loss, duplication, or neither? Prove your answer:
write a small Java program that builds an LSM, flushes, replays the old WAL
by hand, and checks the resulting reads. What property of the replayed
operations makes the outcome safe? (You have met this property in the
`transactional-outbox` lab.)

**7.2** Our B-tree never merges underfull pages, so `Exp2KeyOrder` shows
zero pages reclaimed after deleting half the keys. Design (in prose, or in
code) the smallest correct fix: when should two sibling leaves merge, what
must happen to the separator in the parent, and what new failure mode does
merging introduce that splitting never had?

**7.3** Compaction is falling behind on a production LSM: file count is
climbing, p99 reads are up 3×, disk is 71% full. You have three levers —
throttle ingest, raise compaction concurrency, switch STCS → leveled. For
each: what does it fix, what does it cost, and what could it break *tonight*?

## Rung 8 — Design

**8.1** Design the storage engine for a **message history** service: 500k
writes/sec of `(channel_id, timestamp) → message`, messages never updated,
occasionally deleted for compliance, queried as "last 50 messages in channel
X" and "messages in channel X between two timestamps". Choose the tree, the
key design, the compaction policy, the bloom configuration, and the retention
strategy — and justify each against this lab's scorecard. Where does your
design deliberately accept a bad number?

**8.2** You run a 4 TB PostgreSQL database whose primary keys are random
UUIDv4. Write volume has tripled; p99 insert latency has quadrupled; the
cache hit ratio has fallen. Write the incident analysis: the mechanism (in
B-tree terms), the evidence you'd gather to confirm it, three candidate
fixes with their migration costs, and what you'd measure to prove the fix
worked. Would switching to an LSM engine (MyRocks) fix it — and what would
that break?
