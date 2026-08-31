# Recorded results — 2026-08-28, Apple Silicon Mac, JDK 25

Ratios matter, not absolute values. Toy scale (4-key pages, 6-key memtables)
— real engines change the constants, never the shape.

## Exp1Amplification.java — 3,000 puts (1,000 distinct keys), 500 gets

| Scorecard | B-tree | LSM |
|---|---|---|
| WRITE amp (disk bytes / user bytes) | **119.2** (a whole 8 KB page per ~100 B row) | **2.5** (WAL + flush + compaction) |
| READ amp (places checked per get) | 6.0 (= height, flat) | 2.0 considered, **0.9 real probes** (blooms skip the rest) |
| SPACE amp | 1.40 (page slack; fill 71%) | 1.39 before compaction → collapses after |

LSM: 13 flushes, 3 compactions, 3 tables → 1 after final compact; read amp
after compaction 0.9 probes/get. **Neither engine wins both columns.**

## Exp2KeyOrder.java — 5,000-key base, then 1,000 measured inserts

| Over the 1,000 inserts | sequential (auto-inc) | scattered (UUID-style) |
|---|---|---|
| cold pages dragged into cache | **8** | **914** |
| distinct pages written | 759 | 915 |
| splits | 751 (rightmost churn — see note in program) | 1 (absorbed by slack) |
| final fill factor | 50% | 60% |

LSM same writes: 22,000 vs 24,000 bytes — order-blind.
Bloat bonus: deleted 2,500 of 6,000 keys → pages 3,746 → 3,746, zero
reclaimed (what VACUUM exists for).

## Exp3Bloom.java — 8 SSTables, 2,000 gets for absent keys

| | disk probes | per get |
|---|---|---|
| blooms OFF | 16,000 | 8.0 (every table, every time) |
| blooms ON | 270 | 0.14 |

False-positive rate 1.69% (10 bits/key ≈ ~1–2% by design);
**98.3% of disk I/O eliminated** by a few KB of RAM per table.

## ExpDebugBroken.java — the planted bug (exercises rung 6)

| Engine | answers before compaction | after |
|---|---|---|
| `Lsm` (correct) | `k01=v31`, `k00=null` (deleted) | identical — invariant 4 holds |
| `BrokenLsm` | `k01=v31`, `k00=null` | `k01=v1`, `k00=v0` — **10 answers changed** |

One changed expression makes compaction pick the *oldest* version: deleted
keys resurrect, recent updates silently revert. No crash, no error, no log
line — which is why the invariant is written down and tested. Pointing
`LsmTest`'s `fresh()` at `BrokenLsm` gives **5 passed, 1 failed**: only
`compaction_preserves_every_answer_and_reclaims_space` notices.

## Variants measured for EXERCISES.md rung 4

| Change | Effect |
|---|---|
| B-tree pages 4 → 32 keys | height 6→3, pages 461→46, splits 455→43, read amp 6.0→3.0, write amp 119.2→85.4 |
| LSM memtable 200 → 20 | flushes 13→148, compactions 2→29, write amp 2.5→**8.9** |
| LSM never compacts | files→13, places considered/get 2.0→3.2, write amp 2.5→1.9 |
| …and blooms off | real disk probes 0.9→**3.2 per get** |
| Bloom bits/key 10 → 2 | false positives 1.69%→**47.1%**, probes/get 0.14→3.76 |
