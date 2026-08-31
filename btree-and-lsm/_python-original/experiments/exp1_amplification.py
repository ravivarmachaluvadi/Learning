"""Experiment 1 — the amplification scorecard (README §7, §12, §14).

3,000 random puts into both engines, then reads over the whole key space.
Prints write amp, read amp, and space amp for each — the §12 table, measured.

Run:  python3 experiments/exp1_amplification.py
"""

import random
import shutil
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
from btree import BPlusTree
from lsm import LSM

N = 3_000
PAGE_BYTES = 8192          # model: one B-tree page write = one 8 KB page
rng = random.Random(42)
# 1,000 distinct keys, 3,000 puts => every key overwritten ~3x on average,
# so the LSM accumulates shadowed versions for compaction to reclaim (the
# space-amp line below) while the B-tree just updates in place.
keyspace = [f"k{rng.randrange(10**9):09d}" for _ in range(1_000)]
keys = [rng.choice(keyspace) for _ in range(N)]
value = "x" * 90           # ~100-byte rows, like a modest table row

# ---------------- B-tree ----------------
bt = BPlusTree(max_keys=4)
for k in keys:
    bt.insert(k, value)
user_bytes = sum(len(k) + len(value) for k in keys)
bt_write_amp = (bt.page_writes * PAGE_BYTES) / user_bytes
bt.reset_counters()
sample = rng.sample(keys, 500)
for k in sample:
    bt.search(k)
bt_read_amp = bt.page_reads / len(sample)
bt_space_amp = 1 / bt.fill_factor()

# ---------------- LSM ----------------
d = tempfile.mkdtemp(prefix="lsm-exp1-")
db = LSM(d, memtable_limit=200, compact_at=6)
for k in keys:
    db.put(k, value)
lsm_write_amp = db.bytes_written / db.user_bytes
db.reset_counters()
for k in sample:
    db.get(k)
lsm_read_amp = (db.file_probes + db.bloom_skips) / len(sample)  # places considered
lsm_probes = db.file_probes / len(sample)                       # disk touches
tables_before = db.table_count()
space_before = db.disk_bytes()
db.compact()
space_after = db.disk_bytes()
db.reset_counters()
for k in sample:
    db.get(k)
lsm_read_amp_after = db.file_probes / len(sample)

print(f"{N:,} random ~100-byte puts, then 500 random gets\n")
print(f"{'':38}{'B-tree':>10}   {'LSM':>10}")
print(f"{'WRITE amp (disk bytes / user bytes)':38}{bt_write_amp:>10.1f}   {lsm_write_amp:>10.1f}")
print(f"{'READ amp (places checked per get)':38}{bt_read_amp:>10.1f}   {lsm_read_amp:>10.1f}")
print(f"{'  ...of which real disk probes':38}{bt_read_amp:>10.1f}   {lsm_probes:>10.1f}  (blooms skip the rest)")
print(f"{'SPACE amp (page slack / dead data)':38}{bt_space_amp:>10.2f}   {space_before / max(1, space_after):>10.2f}  (before compaction)")
print(f"\nLSM detail: {db.flushes} flushes, {db.compactions} compactions, "
      f"{tables_before} tables -> 1 after final compact")
print(f"LSM read amp after compaction: {lsm_read_amp_after:.1f} probes/get "
      f"(one merged table + memtable)")
print(f"B-tree detail: height {bt.height}, {bt.page_count()} pages, "
      f"fill factor {bt.fill_factor():.0%}")
print(
    "\nLesson (§12): the B-tree pays a whole page write per row (huge write amp,\n"
    "flat cheap reads); the LSM pays WAL+flush+compaction rewrites (modest write\n"
    "amp, reads that degrade with table count and recover after compaction).\n"
    "Neither wins both columns. They move the same sorting work to different moments."
)
shutil.rmtree(d, ignore_errors=True)
