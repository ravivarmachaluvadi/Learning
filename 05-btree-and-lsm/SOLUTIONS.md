# Solutions

Attempt first — rungs 3–7 are checkable by running something. Every number
below was measured on this lab's machine (Apple Silicon, JDK 25); yours
will differ in magnitude, not in shape.

---

## Rung 1

**1.1** One big sorted file makes *finding* wonderful (binary search, and
ranges are just "read forward") but *inserting* ruinous: a new key belongs in
the middle, so everything after it must shift — thousands of pages moved to
file one invoice. Writes arrive in random order; reads want sorted order;
keeping data sorted at all times means paying a re-sort on every write. And
disks move fixed-size chunks, fastest when they're sequential, so the fix has
to respect chunks too.

**1.2** Both answer "how do we stay sorted enough without re-sorting the
world on every write?" The B-tree repairs sortedness **at write time**,
locally: data lives in small pages with slack, so one insert rearranges one
page, and a full page splits in two. The LSM refuses to repair anything at
write time — it buffers writes in memory, dumps them as immutable sorted
files, and merges those files **later, in bulk**, in the background.

**1.3** The correction: neither is faster; they *move the same sorting work
to different moments*, and each pays in a different currency. The B-tree pays
**write amplification** (a whole page rewritten per row — 119.2× in exp1) to
buy flat, predictable reads (exactly `height` page reads, always). The LSM
pays **read amplification** and perpetual background **compaction I/O** to
buy cheap sequential writes. "Modern databases use LSM" is also just false:
Postgres, MySQL, SQLite, Oracle and nearly every secondary index on earth are
B-trees.

**1.4** Once a file has been written out, it is never changed again — so
there is physically nothing to erase; the only move available is to *write
something newer* that hides the old value, and reads honor "newest first."
That newer record is a **tombstone**: a marker meaning "this key is deleted."
The old value dies later, when a merge rewrites both records into one file.

## Rung 2

**2.1** The single top box is the root; middle rows are signpost pages;
the bottom row holds the data. Numbers inside a signpost are **directions,
not data** — separator keys saying "keys below this value go left." The same
value may appear both as a separator above and as real data in a leaf.

**2.2** Identical cost: a miss walks the same root→leaf path and reads the
same number of pages as a hit. The tree can only conclude "absent" by
reaching the one leaf where the key *would* live. (Contrast the LSM, where
absence is the *worst* case — nothing can stop early, which is why bloom
filters exist.)

**2.3** The memtable is empty and the WAL counter is back to 0: the flush
made that data durable in an immutable SSTable, so the log entries protecting
it are no longer needed and were truncated. The WAL only ever holds writes
that exist *nowhere but RAM*.

**2.4** Both copies are visible on the shelf — the newer one in the file
nearer the top. The read wins for the topmost, because the shelf is ordered
newest-first and the read stops at the **first** hit; the "last read checked"
counter confirms how few places it needed.

## Rung 3

**3.1** Measured: height 2 first at **insert 5** (the root leaf overflows and
splits), height 3 at **insert 13**, height 4 at **insert 37**. Note the
growth: each level takes ~3× more keys than the last — that's fanout, and
it's why real trees with fanout ~200 are 3–4 levels for billions of rows.

**3.2** Measured order (smallest → largest): LSM read amp **2.0**, B-tree
read amp **6.0**, LSM write amp **2.5**, B-tree write amp **119.2**. The
headline: the two engines' *write* costs differ by ~48×, their *read* costs
by ~3× — and the LSM's read number is only that good because bloom filters
turn most "places considered" into zero-I/O skips (0.9 real probes/get).

**3.3** Measured: **8 cold pages vs 914** — roughly 100×. Sequential appends
re-touch one hot right edge; scattered inserts drag a different cold page
into cache almost every write. The LSM columns are ~equal (22,000 vs 24,000
bytes): key order is a B-tree parameter and *no parameter at all* for an LSM.

**3.4** Measured: blooms off **8.0 probes/get** (every table, every time);
blooms on **0.14** — 98.3% of the disk I/O eliminated by a few KB of RAM per
table, at a 1.69% false-positive rate.

**3.5** (a) disk bytes **drop**, (b) files collapse to **1**, (c) "Read k07"
still answers **not found** — identically to before. That last one is the
whole point: compaction is *logically invisible*, changing only the physical
representation. Both the old value and its tombstone died in the merge.

## Rung 4

**4.1** Measured, 4 → 32 keys per page: height **6 → 3**, pages **461 → 46**,
splits **455 → 43**, read amp **6.0 → 3.0**, write amp **119.2 → 85.4**, fill
~unchanged (71% → 69%). You turned the **page size** knob. Note the real
tradeoff the toy under-shows: bigger pages mean fewer, shallower reads but
*more bytes rewritten per row change* — real engines settle at 8–16 KB
because that's where the device's chunk size, cache efficiency, and rewrite
cost balance.

**4.2** Measured, memtable 200 → 20: flushes **13 → 148**, compactions
**2 → 29**, write amp **2.5 → 8.9**, places considered per get **2.0 → 2.8**.
You simulated **an undersized memtable**: more small files, far more merging,
write amp up 3.6× for the same user data. This is the mechanism behind
"compaction can't keep up" (**F-L1**) — and why real engines use ~64 MB
memtables, not 64 KB.

**4.3** Never compacting: files pile to **13**, places considered per get
rises (2.0 → 3.2) while write amp *falls* (2.5 → **1.9**) — you stopped
paying the rewrite cost and started paying it in reads and space instead;
shadowed values and tombstones now live forever. Adding `useBloom = false`:
real disk probes jump **0.9 → 3.2 per get** — every file probed on every
miss. You have deliberately built **F-L1 plus F-L4**: the read-amplification
disaster, which in production ends with the engine stalling writes to protect
itself.

**4.4** Measured, 10 → 2 bits per key: false-positive rate **1.69% → 47.1%**,
probes per get **0.14 → 3.76**. You bought ~80% less filter RAM and paid with
~27× more disk reads — a bloom filter that says "maybe" half the time is
barely a filter. This is exactly why ~10 bits/key is the near-universal
default.

## Rung 5

**5.1**
```java
public static int predictedHeight(int nKeys, int maxKeys) {
    int fanout = maxKeys + 1;       // a page holding maxKeys keys has maxKeys+1 children
    int height = 1;                 // height 1 = a single leaf page
    long capacity = maxKeys;
    while (capacity < nKeys) {
        height++;
        capacity *= fanout;         // each new level multiplies the leaf count
    }
    return height;
}
```
The insight is the loop's `*=`: capacity grows **multiplicatively** per
level, so height grows logarithmically — `predictedHeight(1_000_000, 100)`
is 3. That is the entire reason a billion-row index is 4 page reads deep.

**5.2**
```java
public static List<Entry> newestWinsMerge(List<Table> tables) {
    // TreeMap keeps the output sorted by key for free
    TreeMap<String, Integer> winningSeq = new TreeMap<>();
    TreeMap<String, String> winningValue = new TreeMap<>();
    for (Table table : tables) {
        for (Entry e : table.entries()) {
            // the SEQ decides which table is newer — never the list position
            Integer best = winningSeq.get(e.key());
            if (best == null || table.seq() > best) {
                winningSeq.put(e.key(), table.seq());
                winningValue.put(e.key(), e.value());
            }
        }
    }
    List<Entry> out = new ArrayList<>();
    for (var e : winningValue.entrySet()) {
        if (!Lsm.TOMBSTONE.equals(e.getValue())) out.add(new Entry(e.getKey(), e.getValue()));
    }
    return out;
}
```
The trap the tests set: an implementation that just iterates the list and
lets later entries overwrite earlier ones passes four tests and fails
`newest_table_wins_regardless_of_list_position`. (That trap is not
hypothetical — it caught the first draft of this very solution.)

## Rung 6

**6.1** The expression is the loop that keeps the entry with the **largest
`negPrio()`**:

```java
for (int x = i + 1; x < j; x++) {
    if (merged.get(x).negPrio() > winner.negPrio()) winner = merged.get(x);
}
```

Entries are tagged `Tagged(key, -seq, value)`, so `negPrio()` is the *negated*
sequence number: taking the **largest** `-seq` selects the **smallest** seq —
the **oldest** table. The correct code just takes the first element of the
group (`merged.get(i)`), because the k-way merge already ordered same-key
entries newest-first.

**6.2** `compaction_preserves_every_answer_and_reclaims_space` in
`tests/LsmTest.java` — the test that asserts every `get()` answers identically
before and after compaction. Point `fresh()` at `BrokenLsm` and the suite
reports **5 passed, 1 failed**, that one. Nothing else notices: writes, reads, flushes, file counts, and
disk shrinkage all look perfectly healthy.

**6.3** LSM invariant 4, **compaction is logically invisible** (§12) — and,
through it, invariant 1 (newest wins), since the merge output no longer
reflects the newest write.

**6.4** Measured output of `ExpDebugBroken`: every deleted key comes
back (`k00: null -> v0`) and every overwritten key reverts to its *original*
value (`k01: v31 -> v1`) — all 10 answers change. The user sees **deleted data resurrect and
recent updates silently roll back** — overnight, with no error anywhere. It's
among the worst possible bugs because (a) it's silent — no crash, no log
line, no failing health check; (b) it destroys data that was correctly
written and acknowledged; (c) it's *retroactive*, so backups taken after the
compaction contain the corruption; and (d) for a deletion-for-compliance
workflow, resurrecting the record is a legal problem, not just a technical
one.

## Rung 7

**7.1** **Neither loss nor corruption.** Verified by experiment: after
flushing 4 keys and then replaying the stale WAL, all four keys read back
correctly and the fifth (post-flush) write survives too. The replayed
operations simply re-insert values that already exist in the SSTable; because
the memtable is newer, its identical value wins, and the next flush writes a
duplicate that compaction later collapses. The property that makes this safe
is **idempotence**: `put(k, v)` applied twice equals `put(k, v)` applied
once — the same property that makes at-least-once delivery survivable in the
`transactional-outbox` lab. This is why WAL recovery is allowed to be sloppy
about replaying too much, but never too little. (Deletes are idempotent for
the same reason: a tombstone written twice is one tombstone.)

**7.2** Merge two sibling leaves when their combined key count fits in one
page (real engines use a threshold like "below half full") — but *only*
siblings sharing a parent, since merging non-siblings would break the
separator structure. The parent must then drop the separator that used to
route between them, which can leave the parent underfull, cascading upward;
if the root ends up with one child, the root is removed and the tree
*shrinks* a level (the exact mirror of the root split). The new failure mode:
splitting only ever *adds* structure, so a reader walking down always finds a
valid page; merging **deletes** pages while readers may still be descending
toward them, so merge requires coordination (latching, deferred freeing, or
epoch-based reclamation) that splitting doesn't. That difficulty is precisely
why real engines defer or skip merging and let VACUUM-style processes clean
up instead — the bloat in `Exp2KeyOrder` is a *deliberate* tradeoff, not an
oversight.

**7.3**
- *Throttle ingest* — fixes the root cause immediately (compaction wins the
  race), costs producer latency or backpressure upstream, and could break
  tonight by pushing the queue backup into an upstream system that has no
  backpressure handling (a Kafka consumer lag alert, a full outbox table).
- *Raise compaction concurrency* — fixes the backlog fastest, costs disk
  bandwidth and CPU shared with foreground reads, and could break tonight by
  making p99 reads *worse* before better, or by exhausting the disk headroom
  you're already short of (71% full, and STCS merges need room for input +
  output — **F-L3**).
- *Switch STCS → leveled* — fixes read amp and space amp durably, costs a
  large one-time rewrite of the entire dataset (the most dangerous option
  tonight: it multiplies write amp exactly when the system is already behind)
  and roughly 10× ongoing write amp.
The order a careful on-call engineer picks: **throttle first** (stop digging),
**then concurrency** (dig out), **schedule the policy change** for a calm
window with disk headroom secured. Never the policy switch first.

## Rung 8

**8.1** One strong answer (grade on reasoning, not agreement):
- **Engine: LSM.** 500k writes/sec of never-updated data is the write-firehose
  workload; a B-tree's per-write page rewrite is exactly wrong here.
- **Key design:** partition/primary key `(channel_id, timestamp)` — channel
  as the prefix so one channel's messages are contiguous, timestamp
  descending as the clustering order so "last 50 in channel X" is a single
  short sequential read from the start of the range, and the time range query
  is one contiguous scan. Time-bucket the key (`channel_id, day`) if channels
  can grow unbounded, or a hot channel becomes an unbounded partition.
- **Compaction: time-windowed (TWCS).** Messages are immutable and time-keyed,
  so files from different windows never shadow each other — merging them
  would be pure wasted I/O. TWCS also makes retention nearly free: an expired
  window is a whole file to drop, not millions of tombstones.
- **Bloom filters:** ~10 bits/key, but note they matter *less* here than
  usual — queries are ranges within a known channel, not point lookups of
  possibly-absent keys. Don't over-provision RAM for them.
- **Retention/deletes:** compliance deletes are rare and individual — those
  get tombstones. Bulk expiry must NOT be done with deletes (that's the
  tombstone storm, F-L2); use TTL/window dropping.
- **Deliberately accepted bad number:** space amplification (immutable files
  keep superseded data until a merge) and read amp for *old* messages, which
  may sit in several time windows. Both are cheap in exchange for the ingest
  rate, and neither hurts the dominant query, which reads the newest window.

**8.2** **Mechanism:** UUIDv4 primary keys are uniformly random, so every
insert targets a random leaf. In InnoDB the primary key is a **clustered
index** — the row itself lives inside that B-tree — so each insert dirties a
random 16 KB page rather than appending to a hot right edge. The working set
of recently-written pages explodes (exp2: 8 cold pages vs 914), so the buffer
pool thrashes, page writes scatter, and splits leave pages ~half full,
inflating the tree and worsening cache hit ratio further — a feedback loop
that matches all three symptoms (write latency, cache hit ratio, and the fact
that *tripling* volume quadrupled latency: superlinear).
**Evidence to gather:** buffer pool hit ratio over time; pages dirtied per
insert; index size vs data size (bloat); average leaf fill factor; whether
p99 correlates with checkpoint flushes; and the decisive one — compare insert
latency for a table with sequential keys under the same load.
**Three fixes:** (1) *UUIDv7/ULID* (time-ordered) primary keys — restores the
hot right edge, cheap for new tables, but requires a migration for the
existing 4 TB and any external references to old ids; (2) *auto-increment PK
with the UUID as a secondary unique index* — best write locality, but the
secondary index still takes random inserts (smaller, so cheaper) and
application lookups change; (3) *buy your way out* — more RAM so the working
set fits — real, immediate, and merely postpones the problem as data grows.
**Proof the fix worked:** p99 insert latency and pages-dirtied-per-insert at
matched write volume, plus buffer pool hit ratio recovering.
**Would MyRocks (LSM) fix it?** Yes for *this* symptom — an LSM is indifferent
to key order (exp2's LSM columns), so random UUIDs stop hurting writes. But
it would break other things: range scans and point reads get more expensive
(read amp), you inherit compaction as a permanent operational burden (F-L1,
F-L3) including the disk-headroom rule, secondary-index and transaction
behavior differ, and you'd be migrating 4 TB onto a less familiar engine to
solve a problem that a key-format change also solves. Correct answer: fix the
keys; consider the engine only if the workload is *genuinely* write-dominated
beyond what a B-tree can serve.
