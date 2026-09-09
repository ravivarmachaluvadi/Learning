# Learning Path

The order to read the labs in, numbered 1 to N. This file is the **single source of the order**: every lab folder is named `<number>-<slug>` to match its line here, and `tools/renumber.py` keeps the two in step. Companion to `KNOWLEDGE-MAP.md` (which records *what* was learned) and `STYLE.md` (how a lab is written).

| Key | Value |
|---|---|
| Labs | 71, in eight stages |
| `Builds on` links in `KNOWLEDGE-MAP.md` | 478 |
| Numbering | one sequence, 1 to N; insert with decimals (3.5), see below |
| Folder name | `NN-slug` — `01-networking-for-developers`, `39-paxos`, `03.5-multipaxos` |
| Legend | `↳N` = N other labs name this one as a prerequisite |
| Regenerated | 2026-09-08, counts recomputed from `KNOWLEDGE-MAP.md` |

## How the numbering works

- Every lab has one number. The number is its position in the reading order, not the date it was learned.
- **To insert a lab between 3 and 4, give it 3.5.** Between 3 and 3.5, give it 3.25; between 3.5 and 4, give it 3.75. Nothing else moves. The folder is `03.5-<slug>`.
- The `/learn` skill does this for you: it reads this file, picks the position, creates the numbered folder, and adds the line.
- `python tools/renumber.py --check` confirms every folder matches its line and every relative link resolves. `--apply` renames folders to match this file and rewrites links. `--renumber` collapses decimals back into a clean 1..N sequence — run it only when you want that; every folder name and link changes.
- Stages are headings, not numbering resets. The sequence runs straight through them.

## The order

### Stage 1 — The Ground Floor

Before anything is distributed, one request travels a wire and lands on one disk. Every later topic is an argument about these two facts.

1. [`networking-for-developers`](01-networking-for-developers/README.md) ↳6 — One line of code hides six systems, and each fails as the same generic exception.
2. [`communication-protocols`](02-communication-protocols/README.md) ↳11 — Every named protocol is one answer to one conversation shape, choosable on five axes.
3. [`io-multiplexing`](03-io-multiplexing/README.md) ↳7 — A server's real job is waiting; the only question is what waiting costs.
4. [`database-landscape`](04-database-landscape/README.md) ↳8 — Every database is a bet on one access pattern. Seven physical tricks, seven regrets.
5. [`btree-and-lsm`](05-btree-and-lsm/README.md) ↳15 — Writes arrive unsorted, reads want sorted. The engine only chooses when to pay.
6. [`software-architecture-patterns`](06-software-architecture-patterns/README.md) ↳6 — Patterns are named ways of drawing boundaries, and every boundary sends a bill.

### Stage 2 — One Machine, Done Right

Almost every distributed bug is a single-machine bug that got a network wrapped around it. Learn correctness and speed where you can still see everything.

7. [`acid`](07-acid/README.md) ↳5 — A contract, not a mechanism — and its four letters come from three different places.
8. [`isolation-levels`](08-isolation-levels/README.md) ↳14 — BEGIN…COMMIT buys atomicity and durability, not isolation. The default permits silent data loss.
9. [`mvcc-and-vacuum`](09-mvcc-and-vacuum/README.md) ↳2 — Nothing is overwritten and nothing is erased, so the bill arrives later: someone must collect what no live reader could still want.
10. [`optimistic-vs-pessimistic-locking`](10-optimistic-vs-pessimistic-locking/README.md) ↳10 — The conflicts that matter span conversations, not transactions — and no isolation level sees those.
11. [`caching`](11-caching/README.md) ↳19 — A bounded copy of answers whose truth lives elsewhere. Delete it and lose only speed.
12. [`http-caching`](12-http-caching/README.md) ↳2 — Every response carries its own reuse contract. Caching is correctness before it is speed.
13. [`redis`](13-redis/README.md) ↳11 — One thread, one command at a time — its best guarantee and its worst failure, from one sentence.
14. [`tls`](14-tls/README.md) ↳3 — Privacy, integrity, identity — and only identity is hard. Strangers can agree a secret; they cannot agree who.
15. [`ssh`](15-ssh/README.md) ↳0 — Same cryptography as TLS, opposite answer on identity: your own memory of last time.

### Stage 3 — The JVM Underneath: One Machine, Many Threads

Still one machine, but now several things happen at once inside it. This is where "can both be in here at once?" and "is either guaranteed to see the other?" are first asked — the same two questions the distributed stages ask across machines. Nothing here depends on the stages after it, and nothing after it depends on this block until production, where tuning and queueing meet.

16. [`java-multithreading`](16-java-multithreading/README.md) ↳2 — Two questions only: can both be in here at once, and is either guaranteed to see the other.
17. [`java-memory-model`](17-java-memory-model/visualization.html) ↳8 — **no README yet; the link opens the visualization.** Which reorderings are legal between threads. happens-before is the contract; races are undefined-ish.
18. [`java-virtual-threads`](18-java-virtual-threads/README.md) ↳8 — A cheap heap-backed thread. Blocking parks the stack and frees the carrier, so waiting is nearly free.
19. [`structured-concurrency`](19-structured-concurrency/README.md) ↳3 — Confine every subtask's lifetime to a block. Errors flow up, cancellation flows down.
20. [`scoped-values`](20-scoped-values/README.md) ↳2 — An invisible final parameter for everything below a block — and the cure for ThreadLocal's diseases.
21. [`java-version-features`](21-java-version-features/README.md) ↳0 — Java nearly froze on 8 because releases were feature-driven. The six-month train is the fix.
22. [`jvm-garbage-collectors`](22-jvm-garbage-collectors/README.md) ↳3 — A collector never finds the dead. It marks the reachable and declares the rest dead by definition.
23. [`jvm-performance-tuning`](23-jvm-performance-tuning/README.md) ↳0 — Not flag-picking — bottleneck-finding. Elapsed time splits into seven buckets and exactly one dominates.

### Stage 4 — Why Distribution Is Hard

The vocabulary stage. Six different things are all called "consistent", and two impossibility results say which combinations you may never have.

24. [`cap-theorem`](24-cap-theorem/README.md) ↳32 — Partitioned, you choose between refusing to answer and answering possibly-stale. The root of the whole field.
25. [`pacelc`](25-pacelc/README.md) ↳7 — CAP governs the rare broken moment. PACELC names the tradeoff that governs the other 99.99%.
26. [`linearizability`](26-linearizability/README.md) ↳12 — The precise meaning of "consistent": indistinguishable from one copy handling one request at a time.
27. [`linearizability-vs-serializability`](27-linearizability-vs-serializability/README.md) ↳3 — "Is it consistent?" hides two independent questions — scope, and whether the order must respect the clock.
28. [`eventual-consistency`](28-eventual-consistency/README.md) ↳7 — Not a weak consistency — a different kind of claim. A liveness property no finite test can refute.
29. [`causal-consistency`](29-causal-consistency/README.md) ↳5 — One rule: never show a write without everything that happened before it.
30. [`clock-synchronization`](30-clock-synchronization/README.md) ↳1 — You cannot measure another machine's clock, only bound it. Where ε comes from, and why it is never zero.
31. [`hybrid-logical-clocks`](31-hybrid-logical-clocks/README.md) ↳5 — A timestamp has two jobs ordinary clocks split — ordering cause before effect, and meaning "as of 5 p.m."
32. [`crdts`](32-crdts/README.md) ↳9 — If both sides must accept writes while unable to talk, fix the data type, not the resolver.
33. [`flp-impossibility`](33-flp-impossibility/README.md) ↳2 — One crash, no clocks, and no deterministic protocol can guarantee agreement, validity and termination together.

### Stage 5 — Copies and Agreement

Now put the same data on many machines. Everything here answers one question: who decides, and what happens when the deciders cannot talk.

34. [`quorums-and-replication`](34-quorums-and-replication/README.md) ↳17 — Tell W, ask R, and if R+W>N the two sets cannot avoid each other.
35. [`consistent-hashing`](35-consistent-hashing/README.md) ↳14 — hash(key) % n makes every key's home depend on a global property. One substitution fixes it.
36. [`partitioning-and-rebalancing`](36-partitioning-and-rebalancing/README.md) ↳7 — Splitting data asks three questions replication never did: where, how found, and what on resize.
37. [`gossip-protocols`](37-gossip-protocols/README.md) ↳9 — Once a second, tell two random peers everything you know. The informed set multiplies.
38. [`anti-entropy`](38-anti-entropy/README.md) ↳1 — W<N creates divergence as a normal by-product of working correctly. Something must go back and repair.
39. [`paxos`](39-paxos/README.md) ↳13 — How machines agree on exactly one value while crashing. Ask a majority first; adopt what you find.
40. [`raft`](40-raft/README.md) ↳13 — One leader per numbered term, one ordered log, committed at a majority. Paxos you can implement.
41. [`zab`](41-zab/README.md) ↳0 — The engine inside ZooKeeper — and not a replicated state machine at all. It is primary-backup.
42. [`split-brain`](42-split-brain/README.md) ↳8 — Two nodes both believing they are in charge. "At most one believes" is impossible; fencing is the answer.
43. [`distributed-locking`](43-distributed-locking/README.md) ↳11 — A lock made of messages, held by a process that cannot observe its own delays.
44. [`jepsen`](44-jepsen/README.md) ↳1 — The instrument that decides whether any of the last four stages is actually true of a running system. Records a history; searches for an explanation; convicts when none exists.

### Stage 6 — Work That Spans Services

A business action now touches several systems that can each fail alone. These are the patterns that keep it whole without a distributed lock.

45. [`two-phase-commit`](45-two-phase-commit/README.md) ↳20 — Commit or abort as a unit, via a coordinator. Its defining weakness is blocking.
46. [`distributed-transactions`](46-distributed-transactions/README.md) ↳7 — 2PC alone is not one. Commit across shards is a window, not an instant.
47. [`saga`](47-saga/README.md) ↳13 — A business transaction as local steps with compensations, run backwards on failure.
48. [`transactional-outbox`](48-transactional-outbox/README.md) ↳25 — Saving data and announcing it are two physical acts, and the world can crash between them.
49. [`idempotency`](49-idempotency/README.md) ↳20 — Silence carries no information, so retries are mandatory, so duplicates are certain. Only a key distinguishes them.
50. [`event-sourcing`](50-event-sourcing/README.md) ↳16 — Store the passbook, compute the balance. State is a fold over an immutable stream.
51. [`cqrs`](51-cqrs/README.md) ↳12 — Split the model along the read/write line. The read model is allowed to be stale only because it makes no decisions — and the window it costs you has a measurable width, a formula, and a cliff.
52. [`change-data-capture`](52-change-data-capture/README.md) ↳1 — A query captures data; CDC captures change. A poller over updated_at fails five ways, four silently.
53. [`kafka`](53-kafka/README.md) ↳2 — Stop deleting the messages, and every other property follows: replay, many audiences, and order only per key.
54. [`idempotent-consumer`](54-idempotent-consumer/README.md) ↳1 — The receiving end of every lab above. "Make it idempotent" is three obligations, and the one that fails in production is memory. **← read after `kafka`; it assumes offsets, groups and rebalances.**
55. [`kafka-exactly-once`](55-kafka-exactly-once/README.md) ↳0 — The one hop the lab above declines. Kafka has no rollback: records are written before any decision exists, and the abort is a marker appended after them. **← read last in this stage; it assumes `kafka`, `two-phase-commit`, `idempotency` and `idempotent-consumer`.**

### Stage 7 — Running It In Production

Correct is not enough once there is load, money, and a deadline. Queues, limits, cloud, and the human side of shipping.

56. [`microservices`](56-microservices/README.md) ↳3 — They buy exactly one thing — independent deployability — and only a data boundary delivers it.
57. [`queueing-theory`](57-queueing-theory/README.md) ↳6 — Waiting is not proportional to load. Time inside grows as 1/(1−ρ), and the last few percent cost the most.
58. [`load-shedding`](58-load-shedding/README.md) ↳1 — Past capacity, requests are lost either way. The only choice is whether you pick which ones, or the queue picks for you.
59. [`load-balancing`](59-load-balancing/README.md) ↳0 — It performs no work and adds no capacity. It only decides which queue you wait in — and better information about load can make that decision worse.
60. [`distributed-rate-limiting`](60-distributed-rate-limiting/README.md) ↳13 — A limiter correct on one machine stops being correct the instant there are two.
61. [`work-pressure-and-deadlines`](61-work-pressure-and-deadlines/README.md) ↳4 — Advice loses to advice. A date is a bet, not a fact — so make the bet quantitative.
62. [`amazon-web-services`](62-amazon-web-services/README.md) ↳1 — A shop where you rent other people's distributed systems. Three inventions, stacked.
63. [`terraform`](63-terraform/README.md) ↳0 — Not a tool for creating infrastructure — a tool for maintaining a mapping nothing in the cloud knows about.
64. [`compound-engineering-plugin`](64-compound-engineering-plugin/README.md) ↳0 — Finishing saves the code and loses the reasoning, so the same discovery gets paid for twice.

### Stage 8 — Whole Systems

Capstones. Each one is every earlier stage at once, with the tradeoffs already made by someone who had to ship it. The URL shortener comes first because it is the smallest system that still needs everything above.

65. [`url-shortener-system-design`](65-url-shortener-system-design/README.md) ↳0 — The storage half is trivial (key → value). The whole design is minting globally unique short codes under many concurrent writers without a coordination bottleneck.
66. [`google-spanner`](66-google-spanner/README.md) ↳2 — Did not build a more accurate clock — built one that admits how wrong it might be.
67. [`cockroachdb`](67-cockroachdb/README.md) ↳1 — Spanner made writers pay for clock error by waiting. CockroachDB declares a bound and pays elsewhere.
68. [`mongodb`](68-mongodb/README.md) ↳0 — The document is the unit of everything; the majority commit point is the line between an opinion and a fact. **← read after `raft`, `split-brain`, `causal-consistency`, `isolation-levels` and `partitioning-and-rebalancing`; it assembles all of them and re-teaches none.**
69. [`whatsapp-system-design`](69-whatsapp-system-design/README.md) ↳2 — Hard for one invisible reason: the person you are messaging is usually not there.
70. [`uber-system-design`](70-uber-system-design/README.md) ↳2 — Moving supply, moving demand, milliseconds — and never the same car promised twice.
71. [`google-docs-system-design`](71-google-docs-system-design/README.md) ↳2 — One product decision — your keystroke appears before any server agrees — and everything else is the bill.

## Load-bearing twelve

The labs the rest lean on, measured from the `Builds on` links. If time is short, re-read only these.

| Lab | Labs that build on it |
|---|---|
| `cap-theorem` | 32 |
| `transactional-outbox` | 25 |
| `idempotency` | 20 |
| `two-phase-commit` | 20 |
| `caching` | 19 |
| `quorums-and-replication` | 17 |
| `event-sourcing` | 16 |
| `btree-and-lsm` | 15 |
| `consistent-hashing` | 14 |
| `isolation-levels` | 14 |
| `distributed-rate-limiting` | 13 |
| `raft` | 13 |

## Placement notes

Most positions follow the `Builds on` links. These are the judgement calls, and why.

- **The JVM block is Stage 3, between "one machine" and "why distribution is hard".** It used to be a parallel track. In one linear sequence it belongs where it is: still one machine, but with several threads — the first place the two questions of every later stage ("can both be in here at once?", "is either guaranteed to see the other?") are asked. `java-multithreading` goes before `java-memory-model` because the first asks the two questions and the second is the precise contract that answers them. `jvm-garbage-collectors` and `jvm-performance-tuning` stay in the block so it reads as one unit; production tuning re-uses them from Stage 7.
- **`java-memory-model` has no README** (it is a runnable lab plus `visualization.html`). It keeps its number so the sequence is honest; writing its document is the top item in the gaps list.
- **`clock-synchronization` sits between `causal-consistency` and `hybrid-logical-clocks`** on a curriculum judgement, not on its links: it is the physical-time half of the pair whose logical half is causal consistency, and HLC is the fusion of the two.
- **`load-shedding` then `load-balancing`, both after `queueing-theory`**: the cliff, then what to do when you are past it, then how to avoid reaching it on one machine while seven others sit idle. `load-balancing` reuses `load-shedding`'s service as one of its eight backends.
- **`idempotent-consumer` then `kafka-exactly-once` close Stage 6**: the first covers every redelivery source except the one a Kafka transaction removes; the second covers that single hop and assumes all of `kafka`, `two-phase-commit`, `idempotency` and `idempotent-consumer`.
- **`mongodb` sits with the capstones**, after `cockroachdb`: it names sixteen earlier labs and adds no new mechanism of its own — it is `raft`, `split-brain`, `causal-consistency`, `hybrid-logical-clocks`, `isolation-levels` and `partitioning-and-rebalancing` assembled into one product.
- **`url-shortener-system-design` opens Stage 8** because it is the smallest whole system that still needs caching, consistent hashing and the CP/AP split, and it is the classic first system-design question.

## Gaps — suggested by your labs, never opened

Topics named as `Next` across the labs and never taken up. Counts are occurrences of `[topic?]` in `KNOWLEDGE-MAP.md`.

| Topic | Asked for by | Note |
|---|---|---|
| **observability** | 12 labs | metrics vs logs vs traces, cardinality, SLOs as a budget; `load-balancing` found its failures invisible on an ordinary dashboard |
| **database-indexing** | 6 labs | B-tree and LSM gave you the structures; this is choosing them — selectivity, covering indexes, why the planner ignored yours |
| **tail-latency-and-hedging** | 6 labs | the p99 is a different system from the mean; hedged requests and the cost of asking twice |
| **backpressure** | 5 labs | both `load-shedding` and `load-balancing` deferred to it — reactive streams, TCP windows and consumer lag as one idea |
| **deterministic-databases** | 5 labs |  |
| **idempotency-keys** | 5 labs | narrower than `idempotency`: where the key comes from, how long it lives, what it costs to store |
| **kafka-streams** | 3 labs |  |
| **schema-evolution** | 3 labs |  |
| **zookeeper** | 3 labs |  |
| **failure-detectors** | 3 labs | the formal half of "silence is the only evidence" from `distributed-locking` and `split-brain` |
| **kubernetes** | 3 labs |  |
| **local-first-software** | 3 labs |  |
| **byzantine-fault-tolerance** | 2 labs |  |
| **ddd-aggregates** | 2 labs |  |
| **stream-processing-semantics** | 2 labs |  |
| **metastable-failures** | 2 labs |  |
| **wal-replication-and-pitr** | 2 labs |  |
| **jit-and-escape-analysis** | 2 labs |  |
| **off-heap-memory** | 2 labs |  |
| **service-mesh** | 2 labs |  |
| **api-versioning** | 2 labs |  |
| **vector-clocks** | 2 labs |  |
| **geospatial-indexing** | 2 labs |  |
| **lamport-clocks** | 2 labs |  |
| **public-key-cryptography** | 2 labs |  |
| **certificate-management-and-pki** | 2 labs |  |
| **oauth-and-jwt** | 2 labs |  |

And one gap inside the sequence: **a README for `java-memory-model`**, the only numbered lab without a document. The visualization ("Store Buffer Theatre") and the five experiments under `src/` are the source material.

## Rewrite status

Labs are being re-delivered in the voice described in `STYLE.md` (story first, ask before telling, words before symbols, never cut). `tools/audit.py .` prints the delivery signals for every lab; `tools/preserve-check.py` proves a rewrite lost nothing. See the *Rewrite backlog* table in `README.md` for which labs are done and which are queued.
