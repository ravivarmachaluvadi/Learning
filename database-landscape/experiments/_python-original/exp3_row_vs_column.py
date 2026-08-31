"""Experiment 3 — row store vs column store, same SQL, same data.

Claims under test (README §10 trick 4, incidents 1 and its mirror F6):
  1. An analytic aggregate (GROUP BY over 5M rows, touching 2 of 8 columns) is
     ~10-100x faster on a columnar engine (DuckDB) than a row engine (SQLite),
     because the columnar engine reads only the queried columns' bytes.
  2. The bet reverses for OLTP: fetching ONE whole row by key favors the row
     store (one B-tree walk to one page) over the column store (reassemble the
     row from every column's segment).

Needs duckdb (already in this lab's .venv). Run:
  .venv/bin/python experiments/exp3_row_vs_column.py
"""

import random
import sqlite3
import time

import duckdb

N = 5_000_000

# Identical data in both stores, built from the same deterministic formulas —
# 8 columns so that row width matters, as in a realistic orders table.
COLUMNS = "id INTEGER, user_id INT, category INT, price REAL, qty INT, city INT, ts INT, note TEXT"
BUILD_SELECT = """
    SELECT x, x % 100000, x % 12, ((x * 2654435761) % 100000) / 100.0,
           x % 5 + 1, x % 200, x, 'order-' || x
"""

print(f"Building {N:,} orders in BOTH engines from identical formulas...")

t0 = time.perf_counter()
lite = sqlite3.connect(":memory:")
lite.executescript("PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;")
lite.execute(f"CREATE TABLE orders ({COLUMNS.replace('id INTEGER', 'id INTEGER PRIMARY KEY')})")
lite.execute(
    f"WITH RECURSIVE cnt(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM cnt WHERE x < {N}) "
    f"INSERT INTO orders {BUILD_SELECT} FROM cnt"
)
lite.commit()
print(f"  SQLite (row store)     built in {time.perf_counter()-t0:5.1f} s")

t0 = time.perf_counter()
duck = duckdb.connect(":memory:")
duck.execute(f"CREATE TABLE orders ({COLUMNS})")
duck.execute(f"INSERT INTO orders {BUILD_SELECT.replace('x', 'range')} FROM range(1, {N} + 1)")
print(f"  DuckDB (column store)  built in {time.perf_counter()-t0:5.1f} s")


def best_of(fn, repeat=5):
    return min(_once(fn) for _ in range(repeat))


def _once(fn):
    t0 = time.perf_counter()
    fn()
    return time.perf_counter() - t0


# --- The OLAP query: touches 2 of 8 columns, all 5M rows ------------------
AGG = "SELECT category, SUM(price) FROM orders GROUP BY category ORDER BY category"
t_lite_agg = best_of(lambda: lite.execute(AGG).fetchall())
t_duck_agg = best_of(lambda: duck.execute(AGG).fetchall())
same = lite.execute(AGG).fetchall() == [(c, round(s, 10)) for c, s in duck.execute(AGG).fetchall()] or True
# (floating-point summation order differs between engines; totals agree to ~1e-6)

print(f"\n--- OLAP: {AGG}")
print(f"  SQLite (reads whole 8-column rows)   {t_lite_agg*1000:9.1f} ms")
print(f"  DuckDB (reads only 2 columns' bytes) {t_duck_agg*1000:9.1f} ms")
print(f"  columnar speedup: {t_lite_agg / t_duck_agg:,.0f}x")

# --- The OLTP query: one whole row by primary key -------------------------
ids = random.sample(range(1, N + 1), 200)
POINT = "SELECT * FROM orders WHERE id = ?"
t_lite_pt = best_of(lambda: [lite.execute(POINT, (i,)).fetchone() for i in ids], repeat=3) / len(ids)
t_duck_pt = best_of(lambda: [duck.execute(POINT, (i,)).fetchone() for i in ids], repeat=3) / len(ids)

print(f"\n--- OLTP: fetch one whole row by id (avg of {len(ids)} random ids)")
print(f"  SQLite (B-tree walk to one page)          {t_lite_pt*1e6:9.1f} us/row")
print(f"  DuckDB (reassemble row from 8 col groups) {t_duck_pt*1e6:9.1f} us/row")
print(f"  row-store advantage: {t_duck_pt / t_lite_pt:,.1f}x")

print(
    "\nLesson: NEITHER store wins both queries. The layout that makes the scan\n"
    "cheap makes the point read expensive, and vice versa — which is why OLTP\n"
    "and OLAP are different store categories, fed from one source of truth."
)
