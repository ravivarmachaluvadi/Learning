# Exercises — the eight-rung ladder

Work top to bottom; each rung uses the one below it. Attempt before opening
[`SOLUTIONS.md`](SOLUTIONS.md). Rungs 3–5 are runnable — check yourself against
the machine, not the answer key.

---

## Rung 1 — Explain (no notation allowed)

**1.1** Explain to a new teammate, in under five sentences and zero jargon, why
a company ends up running six kinds of database when "one really good one"
sounds so much simpler.

**1.2** Explain the difference between a *source of truth* and a *derived
store*, and why the distinction — not any product choice — is what keeps a
polyglot system sane.

**1.3** A friend says "NoSQL databases are faster than SQL databases." Using
Meera's ledger (or any story of your own), explain what's wrong with the
sentence and what the true statement underneath it is.

## Rung 2 — Identify

**2.1** In the visualization's *Under the hood* tab, Machine A, run the
`SUM(price)` query. Which cells light up on the column-store side, and what do
the pale-green cells represent at any moment?

**2.2** In Machine B with random keys, after 32 writes: how many *places* must
an LSM read check, and which on-page elements show you that number's parts?

**2.3** For each, name the §10 physical trick powering it: (a) Kafka retaining
a week of order events; (b) Elasticsearch surviving the typo "mistery";
(c) BigQuery summing 2 years of revenue in 3 s; (d) Redis `INCR` at 500/s;
(e) Neo4j's 5-hop fraud query; (f) Cassandra absorbing 100k writes/s.

## Rung 3 — Predict (commit before running)

**3.1** Before running `exp1`: the table has 1M rows. Predict the *ratio*
(not ms) between the un-indexed and indexed user lookup, and whether the
`SUM(price)` time will change after the index exists. Then:
`java --enable-native-access=ALL-UNNAMED -cp ../lib/duckdb_jdbc.jar:../lib/h2.jar experiments/Exp1IndexVsScan.java`

**3.2** Before running `exp3`: predict which query the *row* store wins, which
the *column* store wins, and by roughly what order of magnitude. Then run it.
Compare with `experiments/RESULTS.md`.

**3.3** In Machine B, switch keys to **Sequential** and predict: do the
B-tree's seeks stay ~28, halve, or collapse? Why would an auto-increment
primary key change a database's write behavior? Run it.

**3.4** Chaptr adds "orders in the last 15 minutes, newest first" served on
every admin page load, 50×/s. Predict which category serves it — and why it is
*not* the warehouse, even though it's an aggregate-flavored question.

## Rung 4 — Modify

**4.1** Edit `exp3` so the analytic query is `SELECT SUM(price), SUM(qty),
AVG(ts), COUNT(note) ... ` — touching 4 of 8 columns instead of 2. Predict how
the columnar speedup changes, then measure. What does this tell you about wide
`SELECT *` queries on a warehouse?

**4.2** Edit `exp1` to create the index as `(user_id, price)` (a *covering*
index for the lookup query). Re-run. Does the lookup improve further? Does
`SUM(price)` improve now? Explain both observations with trick 2.

**4.3** In Machine B's model (`computeWriteState`), the memtable flushes at 8
keys and compaction fires at 4 runs. What would flushing at 2 / compacting at
2 do to (a) sequential blocks written and (b) places a read must check? Which
real Cassandra pain does this knob-fiddling reproduce?

## Rung 5 — Implement

**5.1** Extend `exp2`: add *ranking*. Score each hit by how many of the query
terms it contains (2 beats 1), and return the top 5 in order. Verify: a title
containing both "blue" and "locomotive" must outrank titles with only one.

**5.2** Extend `exp2` further: implement a 3-gram fuzzy fallback — when a term
matches nothing, split it into 3-letter chunks ("mistery" → mis/ist/ste/ter/
ery) and rank indexed terms by shared chunks. Verify it finds "mystery" from
"mistery" — you've just rebuilt the idea behind Elasticsearch fuzziness.

## Rung 6 — Debug (spot the architecture bug)

Each mini-design below ships and later pages someone at 3 a.m. Name the
failure (README §11's F1–F7 where it applies), the mechanism, and the fix.

**6.1** "We write every new product to Postgres and then, in the same request
handler, to Elasticsearch. Both calls have retries, so they can't diverge."

**6.2** "Cart contents live in Redis with a 30-day TTL — it's blazing fast and
carts are temporary anyway."

**6.3** "Our seller dashboard queries Snowflake directly on page load; it's
only ~40 queries a minute."

**6.4** "We store each rider's full trip history as an array inside their
MongoDB user document — one read gets everything."

**6.5** "Partition key for our IoT table is `device_type`. We have 9 device
types and 2 million devices."

## Rung 7 — Handle failure

**7.1** Chaptr's Elasticsearch cluster loses an index beyond repair at peak
hour. Design the recovery path that should already exist: what makes the index
rebuildable, what serves search during the rebuild, and what monitoring would
have caught the slow corruption earlier?

**7.2** The Redis cache in front of the product catalog dies. The database
now takes 100% of read traffic and browns out — the *cache stampede / thundering
herd* problem. Design the three layers of defense (in the cache, in the app,
in the database) that let the system degrade instead of collapse.

## Rung 8 — Design

**8.1** Design the data layer for a food-delivery platform in 12 cities:
orders (must be correct), courier GPS pings every 3 s (~30k/s), restaurant
search ("thai near me, open now"), a live ops dashboard ("orders/min per city,
last hour"), monthly finance reports, and "restaurants you might like".
For every store: category, why, what feeds it, its consistency window, and the
one misuse of it you'd veto in review.

**8.2** You inherit a startup whose single Postgres now takes 40k writes/s of
clickstream events (growing 10%/month), and the CTO says "migrate everything
to Cassandra." Write the counter-proposal: what actually moves, what stays,
what the migration sequence is, and which numbers you'd measure to justify
each step.
