"""EXERCISES.md rung 6 — run the invariant tests against the planted-bug engine.

The bug is ONE character in src/broken_lsm.py's compact(). This script builds
the same workload the real test suite uses, compacts with the broken engine,
and reports which answers changed.

Run:  python3 experiments/exp_debug_broken.py
"""

import shutil
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
from broken_lsm import BrokenLSM
from lsm import LSM


def build(cls, d):
    db = cls(d, memtable_limit=4, compact_at=999)
    for i in range(40):
        db.put(f"k{i % 10:02d}", f"v{i}")          # every key overwritten ~4x
    for i in range(0, 10, 2):
        db.delete(f"k{i:02d}")                     # delete the even keys
    for pad in ("pad1", "pad2", "pad3"):
        db.put(pad, "x")                           # force the tombstones to flush
    return db


for cls in (LSM, BrokenLSM):
    d = tempfile.mkdtemp(prefix="lsm-debug-")
    db = build(cls, d)
    before = {f"k{i:02d}": db.get(f"k{i:02d}") for i in range(10)}
    db.compact()
    after = {f"k{i:02d}": db.get(f"k{i:02d}") for i in range(10)}
    changed = {k: (before[k], after[k]) for k in before if before[k] != after[k]}
    print(f"\n=== {cls.__name__} ===")
    print(f"  answers before compaction: {before}")
    print(f"  answers after  compaction: {after}")
    if changed:
        print(f"  *** {len(changed)} ANSWER(S) CHANGED: {changed}")
        print("  *** Compaction is supposed to be LOGICALLY INVISIBLE (README §12,")
        print("  *** LSM invariant 4). This engine violates it.")
    else:
        print("  every answer identical — invariant 4 holds.")
    db.close()
    shutil.rmtree(d, ignore_errors=True)

print(
    "\nQuestions (answers in SOLUTIONS.md):\n"
    "  1. Which single expression in broken_lsm.py's compact() is wrong?\n"
    "  2. Which test in tests/test_lsm.py catches it?\n"
    "  3. Which README invariant does it violate, and what would a USER of a\n"
    "     real database observe when this bug ships?"
)
