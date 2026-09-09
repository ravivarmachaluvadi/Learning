"""Experiment 3 — what bloom filters are worth (README §10.5, F-L4).

Build an LSM with 8 SSTables, then issue 2,000 gets for keys that were NEVER
written — the worst kind of read, because absence can't stop early. Compare
disk probes with blooms off vs on, and measure the false-positive rate.

Run:  python3 experiments/exp3_bloom.py
"""

import shutil
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
from lsm import LSM

MISSING = 2_000
d = tempfile.mkdtemp(prefix="lsm-exp3-")

db = LSM(d, memtable_limit=250, compact_at=999, use_bloom=True)
for i in range(2_000):
    db.put(f"present{i:05d}", "v")
tables = db.table_count()
db.close()

results = {}
for use_bloom in (False, True):
    eng = LSM(d, use_bloom=use_bloom)
    eng.reset_counters()
    for i in range(MISSING):
        eng.get(f"missing{i:06d}")
    results[use_bloom] = (eng.file_probes, eng.bloom_skips)
    eng.close()

probes_off, _ = results[False]
probes_on, skips_on = results[True]
fp_rate = probes_on / (probes_on + skips_on)

print(f"{tables} SSTables on disk; {MISSING:,} gets for keys that DON'T exist\n")
print(f"  blooms OFF: {probes_off:>7,} disk probes  ({probes_off / MISSING:.1f} per get — every table, every time)")
print(f"  blooms ON : {probes_on:>7,} disk probes  ({probes_on / MISSING:.2f} per get)  "
      f"+ {skips_on:,} free 'definitely not here' skips")
print(f"  false-positive rate: {fp_rate:.2%}  (probes that found nothing; ~1% is the 10-bits/key design point)")
print(f"  disk I/O eliminated: {(1 - probes_on / probes_off):.1%}")
print(
    "\nLesson: a few KB of RAM per table turns the worst read pattern (absent\n"
    "keys) from probe-every-file into nearly free. It can never wrongly skip —\n"
    "only occasionally probe in vain. The cheapest win in storage (F-L4)."
)
shutil.rmtree(d, ignore_errors=True)
