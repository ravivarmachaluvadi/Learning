"""Experiment 1 — the index's power and its boundary.

Claim under test (README §10 trick 2, incident 1):
  A B-tree index turns a SELECTIVE lookup from O(n) scan into O(log n) walk —
  but does nothing for an aggregate that must visit every row anyway.

Pure stdlib: SQLite in-memory. Run:
  .venv/bin/python experiments/exp1_index_vs_scan.py
"""

import sqlite3
import time

N = 1_000_000


def timed(label, fn, repeat=5):
    best = min(_time_once(fn) for _ in range(repeat))
    print(f"  {label:<58} {best * 1000:10.3f} ms")
    return best


def _time_once(fn):
    t0 = time.perf_counter()
    fn()
    return time.perf_counter() - t0


db = sqlite3.connect(":memory:")
db.executescript("PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;")
print(f"Building {N:,} orders (this takes a few seconds)...")
db.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INT, price REAL)")
db.execute(
    """
    WITH RECURSIVE cnt(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM cnt WHERE x < ?)
    INSERT INTO orders SELECT x, x % 100000, ((x * 2654435761) % 100000) / 100.0 FROM cnt
    """,
    (N,),
)
db.commit()

q_lookup = "SELECT COUNT(*), SUM(price) FROM orders WHERE user_id = 424242 % 100000"
q_aggregate = "SELECT SUM(price) FROM orders"

print("\n--- WITHOUT an index on user_id ---")
t_scan = timed("lookup one user's orders (full table scan)", lambda: db.execute(q_lookup).fetchone())
t_agg_before = timed("SUM(price) over all rows", lambda: db.execute(q_aggregate).fetchone())

print("\nCreating index on user_id...")
db.execute("CREATE INDEX idx_user ON orders(user_id)")

print("\n--- WITH the index ---")
# The selective query now walks ~3 B-tree levels to the matching rows instead of
# visiting all 1M rows — this is the entire value proposition of an index.
t_idx = timed("lookup one user's orders (B-tree walk)", lambda: db.execute(q_lookup).fetchone())
# The aggregate still has to visit every row's price; the index is irrelevant to it.
t_agg_after = timed("SUM(price) over all rows (index can't help)", lambda: db.execute(q_aggregate).fetchone())

print("\n=== VERDICT ===")
print(f"Selective lookup speedup from the index : {t_scan / t_idx:,.0f}x")
print(f"Aggregate 'speedup' from the same index : {t_agg_before / t_agg_after:.2f}x  (~1 = no help)")
print(
    "\nLesson: indexes accelerate SELECTIVITY, not VOLUME. A query that must touch\n"
    "every row (incident 1's dashboard) is not an indexing problem — it is a\n"
    "storage-layout problem, which is why columnar stores (exp3) exist."
)
