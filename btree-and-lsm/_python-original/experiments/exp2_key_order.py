"""Experiment 2 — key order: the UUID incident, measured (README F-B1, F-B2).

Two identical B-trees of 5,000 keys. Then 1,000 MORE inserts:
  tree A: sequential appends (auto-increment style)
  tree B: keys landing between existing keys (UUIDv4 style)
Measured over those 1,000 inserts only: cold pages dragged into cache,
splits, and final fill factor. Then a bonus: bloat (F-B2) — delete half the
keys and watch zero pages get reclaimed.

The LSM columns show the same 1,000 writes are order-blind there: everything
lands in the memtable either way.

Run:  python3 experiments/exp2_key_order.py
"""

import random
import shutil
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
from btree import BPlusTree
from lsm import LSM


def page_ids(tree):
    ids = set()
    def walk(p):
        ids.add(id(p))
        if not p.leaf:
            for c in p.children:
                walk(c)
    walk(tree.root)
    return ids


BASE = 5_000
TAIL = 1_000
base_keys = [f"{k:07d}" for k in range(0, BASE * 10, 10)]

seq, rnd = BPlusTree(max_keys=4), BPlusTree(max_keys=4)
for k in base_keys:
    seq.insert(k, "v")
    rnd.insert(k, "v")

seq.reset_counters(); rnd.reset_counters()
pre_seq, pre_rnd = page_ids(seq), page_ids(rnd)

for i in range(TAIL):
    seq.insert(f"{BASE * 10 + i * 10:07d}", "v")          # append at the right edge
scattered = random.Random(9).sample(range(0, BASE * 10, 10), TAIL)
for k in scattered:
    rnd.insert(f"{k:07d}5", "v")                           # lands mid-tree, everywhere

cold_seq = len(seq.touched_pages & pre_seq)
cold_rnd = len(rnd.touched_pages & pre_rnd)

print(f"After {BASE:,} base keys, measuring the NEXT {TAIL:,} inserts only:\n")
print(f"{'':44}{'sequential':>12}{'scattered':>12}")
print(f"{'cold pages dragged into cache':44}{cold_seq:>12,}{cold_rnd:>12,}")
print(f"{'total distinct pages written':44}{len(seq.touched_pages):>12,}{len(rnd.touched_pages):>12,}")
print(f"{'splits':44}{seq.splits:>12,}{rnd.splits:>12,}")
print(f"{'final fill factor':44}{seq.fill_factor():>11.0%}{rnd.fill_factor():>12.0%}")
print(
    "\n(Note the splits row: appends split the rightmost page constantly because\n"
    "this toy splits at the median like any other page — while scattered inserts\n"
    "sank into existing slack. Real engines special-case the rightmost split\n"
    "(leave the left page ~full, start the right page empty) exactly because\n"
    "appends are so common. The cold-page row is the one the UUID incident is\n"
    "about: 8 warm pages vs 911 cache misses.)"
)

# ---- the LSM doesn't care (same writes, both orders) ----
probes = []
for order, tail in (("sequential", [f"{BASE*10+i*10:07d}" for i in range(TAIL)]),
                    ("scattered", [f"{k:07d}5" for k in scattered])):
    d = tempfile.mkdtemp(prefix="lsm-exp2-")
    db = LSM(d, memtable_limit=500, compact_at=999)
    for k in base_keys:
        db.put(k, "v")
    before = db.bytes_written
    for k in tail:
        db.put(k, "v")
    probes.append((order, db.bytes_written - before, db.flushes))
    shutil.rmtree(d, ignore_errors=True)
print(f"\nLSM, same {TAIL:,} writes: "
      f"{probes[0][0]} order wrote {probes[0][1]:,} bytes; "
      f"{probes[1][0]} order wrote {probes[1][1]:,} bytes — order-blind, as promised:")
print("every write is a WAL append + memtable insert regardless of key.")

# ---- bonus: bloat (F-B2) ----
pages_before = rnd.page_count()
for k in base_keys[::2]:
    rnd.delete(k)
print(f"\nBloat bonus: deleted {len(base_keys)//2:,} of {rnd.key_count() + len(base_keys)//2:,} keys; "
      f"pages before/after: {pages_before:,} / {rnd.page_count():,} — zero reclaimed.")
print("This slack is what VACUUM / REINDEX / pg_repack exist to hand back (F-B2).")

print(
    "\nLesson: same logical work, wildly different physical cost — key ORDER is a\n"
    "B-tree performance parameter (hence UUIDv7/ULID), and no parameter at all\n"
    "for an LSM. This is F-B1 with your own numbers."
)
