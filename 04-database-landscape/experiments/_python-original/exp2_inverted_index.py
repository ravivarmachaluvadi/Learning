"""Experiment 2 — why search engines exist.

Claims under test (README §10 trick 5, incident 2):
  1. LIKE '%word%' must scan every row, every query — cost grows with the table.
  2. An inverted index (word -> set of doc ids) answers the same question in
     microseconds, because it intersects two small sets instead of scanning.
  3. Exact-term lookup is NOT search: the typo/prefix 'locomotiv' needs analyzer
     machinery (here: a prefix range over sorted terms) — showing where a real
     engine's relevance pipeline attaches.

Pure stdlib. Run:
  .venv/bin/python experiments/exp2_inverted_index.py
"""

import bisect
import random
import sqlite3
import time
from collections import defaultdict

N_DOCS = 200_000
# NOTE: 'locomotive' is deliberately NOT in the pool — only the planted doc
# below contains it, so both query strategies should find exactly one book.
WORDS = (
    "the blue red green train station mystery murder garden midnight express "
    "shadow river mountain letter secret doctor winter summer island bridge "
    "clock library orient detective harbor lantern whisper"
).split()
random.seed(42)

print(f"Building {N_DOCS:,} book titles...")
docs = [" ".join(random.sample(WORDS, 6)) for _ in range(N_DOCS)]
docs[123_456] = "the blue locomotive"  # the book the user is looking for

db = sqlite3.connect(":memory:")
db.execute("CREATE TABLE books (id INTEGER PRIMARY KEY, title TEXT)")
db.executemany("INSERT INTO books VALUES (?, ?)", enumerate(docs))
db.commit()

# --- The database way: LIKE substring scan --------------------------------
def like_query(pattern):
    return [r[0] for r in db.execute("SELECT id FROM books WHERE title LIKE ?", (pattern,))]

t0 = time.perf_counter()
like_hits = like_query("%blue%locomotive%")
t_like = time.perf_counter() - t0

# --- The search-engine way: an inverted index -----------------------------
# Build once at write time (this is the cost search engines pay on indexing):
t0 = time.perf_counter()
inverted = defaultdict(set)
for doc_id, title in enumerate(docs):
    for term in title.split():  # 'tokenize' — the simplest possible analyzer
        inverted[term].add(doc_id)
sorted_terms = sorted(inverted)  # enables prefix matching below
t_build = time.perf_counter() - t0

# Query = intersect the posting sets of each term. Cost ~ size of the smallest
# set, INDEPENDENT of total corpus size — this is the inverted index's bet.
def term_query(*terms):
    sets = [inverted.get(t, set()) for t in terms]
    return set.intersection(*sets) if sets else set()

t0 = time.perf_counter()
for _ in range(1000):
    inv_hits = term_query("blue", "locomotive")
t_inv = (time.perf_counter() - t0) / 1000

print(f"\n  LIKE '%blue%locomotive%' (scans all {N_DOCS:,} rows)  {t_like*1000:9.3f} ms")
print(f"  inverted-index intersection of 2 posting sets       {t_inv*1000:9.3f} ms")
print(f"  (index build cost, paid once at write time:         {t_build*1000:9.1f} ms)")
print(f"  both found exactly the planted book: {set(like_hits) == inv_hits == {123_456}}")
print(f"  speedup per query: {t_like / t_inv:,.0f}x")

# --- Where 'search' becomes more than lookup ------------------------------
print("\nUser now types the prefix/typo 'locomotiv':")
print(f"  LIKE '%locomotiv%' finds it (substring luck): {len(like_query('%locomotiv%')) > 0}")
print(f"  exact inverted lookup finds it:               {len(term_query('blue', 'locomotiv')) > 0}")

# Fix: expand 'locomotiv' to every indexed term starting with it (prefix range
# over the sorted term list) — a toy version of what analyzers/fuzzy matching do.
def prefix_expand(prefix):
    i = bisect.bisect_left(sorted_terms, prefix)
    out = set()
    while i < len(sorted_terms) and sorted_terms[i].startswith(prefix):
        out |= inverted[sorted_terms[i]]
        i += 1
    return out

hits = term_query("blue") & prefix_expand("locomotiv")
print(f"  prefix expansion over sorted terms finds it:  {hits == {123_456}} (doc id {sorted(hits)})")

print(
    "\nLesson: the speed comes from the inverted index; the QUALITY (prefixes,\n"
    "typos, stemming, ranking) comes from machinery bolted onto it. A real engine\n"
    "(Lucene/Elasticsearch) is this dict — persistent, compressed, scored, sharded."
)
