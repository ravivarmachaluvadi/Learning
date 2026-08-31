# Recorded results — 2026-08-28, Intel Core i9 Mac, JDK 25, DuckDB JDBC 1.5.5.1, H2 2.4.240

Every claim in README §10 that these experiments test, with the numbers this
machine actually produced. Re-run yourself and compare; the ratios matter, not
the absolute times.

## Exp1IndexVsScan.java — 1M rows, H2 (in-memory row store)

| Query | no index | with index | verdict |
|---|---|---|---|
| one user's orders (selective) | 69.1 ms (full scan) | 0.195 ms (B-tree walk) | **354× faster** |
| SUM(price) all rows (volume) | 301.4 ms | 240.9 ms | **1.25× — no help** |

Indexes buy selectivity, not volume. Incident 1 was never an indexing problem.

Across eight runs the lookup speedup landed between 293× and 366×, and the
aggregate ratio between 0.82× and 1.25× — it wanders to *both* sides of 1,
which is exactly the point: the index is irrelevant to a query that must visit
every row. The indexed lookup's 0.195 ms is also a floor imposed by JDBC
round-trip overhead, not by the B-tree, so 354× understates the index's win.

## Exp2InvertedIndex.java — 200k titles, H2 LIKE vs a HashMap of posting sets

| Strategy | per query | finds "blue locomotive" | finds typo "locomotiv" |
|---|---|---|---|
| `LIKE '%blue%locomotive%'` | 42.0 ms (scans everything, every time) | yes | yes (substring luck) |
| inverted index, exact terms | 0.000146 ms | yes | **no** |
| + prefix expansion (toy analyzer) | ~µs | yes | yes |

**288,088× faster** per query; index build cost 331 ms paid once at write time.
Speed lives in the inverted index; search *quality* lives in the analyzer
machinery attached to it.

## Exp3RowVsColumn.java — 5M rows × 8 columns, H2 vs DuckDB, identical data & SQL

| Query | H2 (row) | DuckDB (column) | winner |
|---|---|---|---|
| GROUP BY aggregate (2 of 8 cols) | 1,787.0 ms | 3.2 ms | **columnar, 566×** |
| fetch one whole row by id | 12.9 µs | 745.7 µs | **row store, 57.8×** |

Neither layout wins both. This table is the whole document in four cells.

Across five runs the columnar speedup landed between 505× and 625×, and the
row-store advantage between 45.9× and 68.4×. Both engines hold all 5M rows in
RAM, so the only thing separating these two rows of the table is the *layout*.

> Note for anyone re-running: H2 has two result caches (`QUERY_CACHE_SIZE` and
> `OPTIMIZE_REUSE_RESULTS`). Both are switched off in the connection URLs. Leave
> either on and repeated runs of the aggregate return a memoised answer in
> ~0.1 ms — the row store appears to *win* the scan, and you have measured a
> cache rather than a storage layout.
