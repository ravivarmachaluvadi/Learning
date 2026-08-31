# Solutions

Attempt each exercise first — especially rungs 3–5, where the machine itself
is the answer key. Rungs 7–8 are worked design discussions, not single correct
outputs; treat them as one strong answer to argue with.

---

## Rung 1

**1.1** (One good version.) Different questions want data physically arranged
in different ways: finding one order wants related things stored together;
adding up two years of revenue wants just the numbers stored together; search
wants a word-to-items index; recommendations want connections stored as
connections. One arrangement can't be all of these at once, so each heavy
question-shape eventually gets its own store, all copied from one authoritative
source. The skill isn't knowing fifty products — it's recognizing the shape of
the question.

**1.2** The source of truth is the one store whose value *defines* correct —
if it burns down, the data is simply gone, so it gets the durability, backups,
and transactions. Every other store (cache, search index, warehouse) is a
copy arranged for speed: allowed to lag, expected to be deletable and
rebuildable from truth. The discipline matters because two stores *will*
disagree — the truth/derived contract is what decides who wins, and every §11
disaster (F1, F2) is that contract being crossed silently.

**1.3** "Faster" at *what*? Meera's subject-index cards are enormously faster
than her ledger at "which books mention trains" — and useless for "how much
did we earn in March". Every store is faster than every other store at the
question it's arranged for and slower at the rest. The true sentence: *each
database is faster for the access pattern it bet on*. exp3 shows one engine
"winning" by 566× and *losing* by 57.8× on the same data, depending only on
the query.

## Rung 2

**2.1** Only the **price strip's** cells light up (the query names one
column); the other seven strips never get touched. Pale green = cells already
read — the running total of I/O spent so far.

**2.2** **5 places**: the memtable plus (after compaction) 1 on-disk run —
the number is shown as "places a read must check", and its parts are the
dashed memtable box + each run box. (Mid-run, before compaction, it can be as
high as 1 + 3.)

**2.3** (a) append-only log (trick 7); (b) inverted index *plus analyzer
machinery* (trick 5); (c) columnar layout + compression (trick 4);
(d) hash table with atomic increment (trick 1); (e) index-free adjacency
(trick 6); (f) LSM-tree + partitioning (trick 3).

## Rung 3

**3.1** Measured here: **354×** for the lookup (anything from hundreds to
thousands is a right prediction — the point is orders of magnitude, from full
scan to ~3 B-tree page reads); `SUM(price)` was **1.25×**, and wanders between
0.82× and 1.25× from run to run — i.e. unchanged, since it must visit every
row regardless of any index.

**3.2** Column store wins the aggregate (566× here); row store wins the
single-row fetch (57.8× here). Right order of magnitude ≈ right answer.

**3.3** They **collapse to 8** — exactly one seek per page, because ordered
keys fill a page completely before moving on. That is why auto-increment (or
time-ordered UUIDv7) primary keys are standard advice: they turn every insert
into the B-tree's cheap case, while random UUIDv4 keys turn every insert into
a random page touch.

**3.4** A **time-window point-ish query at high frequency on fresh data** —
that's an OLTP-side read (Postgres with an index on `created_at`, or Redis if
it must be microseconds), *not* the warehouse: F6 applies (latency in seconds,
scan pricing, staleness of minutes-to-hours — "last 15 minutes" would be
missing its most important rows).

## Rung 4

**4.1** The speedup shrinks — the columnar advantage is proportional to
(columns in table) / (columns touched), so 4-of-8 roughly halves the gap
vs 2-of-8, and `SELECT *` erases it entirely (plus compression benefits).
Warehouse lesson: **narrow queries are the whole point**; `SELECT *` on a
scan-priced engine is paying for the row-store experience at columnar prices.

**4.2** Lookup: marginally better at best (it was already a B-tree walk; now
the price rides along, saving the table visit). `SUM(price)` over *all* rows:
in H2 it may now use the smaller index structure to scan, which helps
some — but it remains a visit-everything O(n) scan, not a jump. Both
observations are trick 2: B-trees change *where* you read, never *how much*
a full-scan must read.

**4.3** (a) More, smaller sequential blocks and far more compaction passes —
total write work (write amplification) rises; (b) oscillates but averages
lower per read. This is real Cassandra/RocksDB **compaction tuning**: the
knobs trade write amplification against read amplification against space —
the on-call symptom being "compaction backlog grows and p99 reads spike."

## Rung 5

**5.1** Core of a working answer (replacing the plain intersection):

```java
static List<Map.Entry<Integer, Integer>> rankedQuery(
        Map<String, Set<Integer>> inverted, int k, String... terms) {
    Map<Integer, Integer> scores = new HashMap<>();
    for (String t : terms)
        for (int docId : inverted.getOrDefault(t, Set.of()))  // each matched term = 1 point
            scores.merge(docId, 1, Integer::sum);
    return scores.entrySet().stream()                         // 2-term hits outrank 1-term hits
            .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
            .limit(k).toList();
}
```
Verify: `rankedQuery(inverted, 5, "blue", "locomotive").get(0)` is doc 123456 with score 2,
ahead of every score-1 title. (Real BM25 also weights rare terms higher and
normalizes by document length — same skeleton, better arithmetic.)

**5.2** Sketch: build a `Map<String, Set<String>> grams` mapping each 3-gram to
the terms containing it (from `sortedTerms`); on a miss, split the query term
into 3-grams, count overlaps per candidate term, take the best-overlap term(s)
and re-run the normal term query. "mistery" and "mystery" share `ste/ter/ery`
(3 of 5 grams) — comfortably the top candidate. That overlap-counting is the
essence of Elasticsearch's ngram/fuzzy machinery.

## Rung 6

**6.1** **F2 — dual-write.** Retries don't help the case where the process
dies *between* the two writes (or the second keeps failing past retry
budget): the writes aren't atomic and can't be made so from the handler. Fix:
write Postgres only; Elasticsearch is fed from the change stream
(outbox/CDC → Kafka → indexer), plus periodic drift reconciliation.

**6.2** **F1 — a cache quietly promoted to truth.** TTL is irrelevant; the
failure is eviction under memory pressure, restart, or failover — carts
vanish mid-purchase, revenue-impacting. Fix: carts are truth → durable store
(Postgres/DynamoDB); Redis may *cache* them.

**6.3** **F6 — warehouse on the request path.** 40/min is enough: seconds of
latency per page load, and on per-scan pricing each dashboard view costs real
money. Fix: scheduled/streamed aggregation into a serving table (Postgres or
ClickHouse); the page reads the precomputed answer.

**6.4** **F5 — unbounded document.** Frequent riders make the biggest, slowest
documents; every append rewrites the array; the 16 MB cap eventually rejects
writes for the *best customers first*. Fix: trips as their own collection (or
a relational table) keyed by rider + time.

**6.5** **F4 — hot/unbounded partition.** Nine partition-key values means at
most nine partitions doing all the work — 2M devices funneled through 9 keys,
each partition unbounded and enormous. Fix: partition by `device_id` (2M
well-spread keys), bucketed by time if per-device history is long.

## Rung 7

**7.1** Working answer in three parts. *Rebuildability:* truth lives in
Postgres; the indexer is a Kafka consumer of the CDC stream, so a full rebuild
is "create new index, replay the product topic from offset 0, flip the alias"
— aliases make the flip atomic, and the rebuild is a **drill you've run**, not
a hope. *During:* degrade explicitly — serve category browse + Postgres
full-text (or a "search is limited" banner) rather than 500s; feature-flag
the search box's fancy features. *Earlier detection:* drift job comparing
truth counts vs index counts per category; indexing-lag alert; slow rise in
mapping errors. The deep answer: **because the index was never truth, this is
an inconvenience, not a disaster** — the design decision was made months
before the outage.

**7.2** *In the cache:* stagger TTLs with jitter (no synchronized expiry);
consider soft-TTL/serve-stale-while-revalidate. *In the app:* request
coalescing / singleflight — one loader per missing key per instance, others
wait; circuit-break to a degraded page rather than hammering. *In the
database:* connection-pool caps and statement timeouts so 100% cache-miss
traffic queues and sheds instead of toppling the primary; read replicas absorb
what they can. Result: p95 degrades for minutes; nothing falls over. (This trio
is asked, in some form, in most senior system-design interviews.)

## Rung 8

**8.1** One defensible architecture (yours may differ — grade on *reasons*):

| Workload | Store | Why / fed by | Window | Veto in review |
|---|---|---|---|---|
| Orders, payments, restaurants, couriers | **PostgreSQL** (truth) | multi-row ACID; evolving queries; one region per city is fine | 0 | any team caching order state in Redis and reading it back as truth |
| Courier GPS 30k writes/s | **Cassandra/Scylla** (or DynamoDB) — partition `courier_id` + day-bucket, cluster by ts | LSM firehose, one known query ("courier X recent track") | seconds | ad-hoc queries on it; use the stream/warehouse instead |
| Live courier positions ("nearest now") | **Redis** geo sets, TTL ~30 s | ephemeral by nature — perfect cache-class data | seconds; loss harmless | anyone persisting it — it's derived from pings |
| Restaurant search | **Elasticsearch** (geo + text + "open now" filter) | fed by CDC from Postgres | ~2 s | writing to ES from request handlers (F2) |
| Ops dashboard (orders/min/city, last hour) | **ClickHouse** (or Timescale) | user-facing sub-second aggregates over fresh events, fed from Kafka | ~seconds | pointing it at the warehouse (F6-in-reverse: too stale) |
| Finance monthly | **BigQuery/Snowflake** | big scans, hourly staleness fine; ELT from Kafka/lake | hours | request-path reads |
| "You might like" | **pgvector** in the truth Postgres | 12 cities ≠ 100M vectors; joins with "open now" | re-embed cadence | a dedicated vector cluster on day one |
| The connective tissue | **Kafka** + outbox/CDC | one change stream feeds all derived stores; future consumers replay | — | request/response calls disguised as events |

**8.2** Counter-proposal shape: the clickstream — append-only, loss-tolerant,
query-known — is the *only* workload outgrowing Postgres, and it isn't even a
database workload: it's a **stream**. Move *it*: events → Kafka (buffering,
fan-out), landing in a lakehouse (cheap history) and ClickHouse (product
analytics). Orders/users/payments — transactional, modest volume — *stay* in
Postgres, which instantly loses 90%+ of its write load. "Migrate everything to
Cassandra" fails the requirement audit twice: checkout loses its transactions,
analytics loses ad-hoc queries — Cassandra fits neither retained workload.
Sequence: (1) double-write clickstream to Kafka behind a flag *(acceptable
here only because click events are loss-tolerant; for money you'd outbox)*;
(2) backfill history into the lake; (3) cut ingestion over; (4) delete the
clickstream tables and watch Postgres p99 fall. Numbers that justify each
step: writes/s split by table (proves what's actually hot), p99 latency and
replication lag before/after, cost per month of the new pipeline vs the next
Postgres instance size, consumer lag once live. Success criterion stated up
front: checkout p99 back under X ms with clickstream growth decoupled from the
OLTP box.
