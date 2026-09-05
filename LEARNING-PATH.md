# Learning Path

The order to re-read the 65 `/learn` labs in this folder. Companion to
`KNOWLEDGE-MAP.md` (which records *what* was learned) and to
`LEARNING-PATH.html` (the visual version — open it in a browser; the lab
links are clickable there).

Seven sequential stages plus one parallel track. Stage order is a curriculum
judgement laid over the 387 `Builds on` links in KNOWLEDGE-MAP.md — those links
only ever point backwards in time, so sorting by them alone just replays the
order things were learned in.

Legend: `↳N` = N other labs name this one as a prerequisite.


## Stage 1 — The Ground Floor

Before anything is distributed, one request travels a wire and lands on one disk. Every later topic is an argument about these two facts.

1. [`networking-for-developers`](networking-for-developers/README.md) ↳5 — One line of code hides six systems, and each fails as the same generic exception.
2. [`communication-protocols`](communication-protocols/README.md) ↳10 — Every named protocol is one answer to one conversation shape, choosable on five axes.
3. [`io-multiplexing`](io-multiplexing/README.md) ↳7 — A server's real job is waiting; the only question is what waiting costs.
4. [`database-landscape`](database-landscape/README.md) ↳6 — Every database is a bet on one access pattern. Seven physical tricks, seven regrets.
5. [`btree-and-lsm`](btree-and-lsm/README.md) ↳12 — Writes arrive unsorted, reads want sorted. The engine only chooses when to pay.
6. [`software-architecture-patterns`](software-architecture-patterns/README.md) ↳6 — Patterns are named ways of drawing boundaries, and every boundary sends a bill.

## Stage 2 — One Machine, Done Right

Almost every distributed bug is a single-machine bug that got a network wrapped around it. Learn correctness and speed where you can still see everything.

1. [`acid`](acid/README.md) ↳1 — A contract, not a mechanism — and its four letters come from three different places. **← re-read: met before its foundations**
2. [`isolation-levels`](isolation-levels/README.md) ↳10 — BEGIN…COMMIT buys atomicity and durability, not isolation. The default permits silent data loss. **← re-read: met before its foundations**
3. [`mvcc-and-vacuum`](mvcc-and-vacuum/README.md) ↳0 — Nothing is overwritten and nothing is erased, so the bill arrives later: someone must collect what no live reader could still want.
4. [`optimistic-vs-pessimistic-locking`](optimistic-vs-pessimistic-locking/README.md) ↳7 — The conflicts that matter span conversations, not transactions — and no isolation level sees those. **← re-read: met before its foundations**
5. [`caching`](caching/README.md) ↳17 — A bounded copy of answers whose truth lives elsewhere. Delete it and lose only speed.
6. [`http-caching`](http-caching/README.md) ↳2 — Every response carries its own reuse contract. Caching is correctness before it is speed.
7. [`redis`](redis/README.md) ↳10 — One thread, one command at a time — its best guarantee and its worst failure, from one sentence.
8. [`tls`](tls/README.md) ↳3 — Privacy, integrity, identity — and only identity is hard. Strangers can agree a secret; they cannot agree who.
9. [`ssh`](ssh/README.md) ↳0 — Same cryptography as TLS, opposite answer on identity: your own memory of last time.

## Stage 3 — Why Distribution Is Hard

The vocabulary stage. Six different things are all called "consistent", and two impossibility results say which combinations you may never have.

1. [`cap-theorem`](cap-theorem/README.md) ↳28 — Partitioned, you choose between refusing to answer and answering possibly-stale. The root of the whole field.
2. [`pacelc`](pacelc/README.md) ↳5 — CAP governs the rare broken moment. PACELC names the tradeoff that governs the other 99.99%. **← re-read: met before its foundations**
3. [`linearizability`](linearizability/README.md) ↳10 — The precise meaning of "consistent": indistinguishable from one copy handling one request at a time.
4. [`linearizability-vs-serializability`](linearizability-vs-serializability/README.md) ↳2 — "Is it consistent?" hides two independent questions — scope, and whether the order must respect the clock. **← re-read: met before its foundations**
5. [`eventual-consistency`](eventual-consistency/README.md) ↳7 — Not a weak consistency — a different kind of claim. A liveness property no finite test can refute. **← re-read: met before its foundations**
6. [`causal-consistency`](causal-consistency/README.md) ↳4 — One rule: never show a write without everything that happened before it.
7. [`clock-synchronization`](clock-synchronization/README.md) ↳0 — You cannot measure another machine's clock, only bound it. Where ε comes from, and why it is never zero.
8. [`hybrid-logical-clocks`](hybrid-logical-clocks/README.md) ↳4 — A timestamp has two jobs ordinary clocks split — ordering cause before effect, and meaning "as of 5 p.m." **← re-read: met before its foundations**
9. [`crdts`](crdts/README.md) ↳9 — If both sides must accept writes while unable to talk, fix the data type, not the resolver.
10. [`flp-impossibility`](flp-impossibility/README.md) ↳0 — One crash, no clocks, and no deterministic protocol can guarantee agreement, validity and termination together. **← re-read: met before its foundations**

## Stage 4 — Copies and Agreement

Now put the same data on many machines. Everything here answers one question: who decides, and what happens when the deciders cannot talk.

1. [`quorums-and-replication`](quorums-and-replication/README.md) ↳14 — Tell W, ask R, and if R+W>N the two sets cannot avoid each other.
2. [`consistent-hashing`](consistent-hashing/README.md) ↳10 — hash(key) % n makes every key's home depend on a global property. One substitution fixes it.
3. [`partitioning-and-rebalancing`](partitioning-and-rebalancing/README.md) ↳5 — Splitting data asks three questions replication never did: where, how found, and what on resize.
4. [`gossip-protocols`](gossip-protocols/README.md) ↳9 — Once a second, tell two random peers everything you know. The informed set multiplies.
5. [`anti-entropy`](anti-entropy/README.md) ↳1 — W<N creates divergence as a normal by-product of working correctly. Something must go back and repair. **← re-read: met before its foundations**
6. [`paxos`](paxos/README.md) ↳13 — How machines agree on exactly one value while crashing. Ask a majority first; adopt what you find.
7. [`raft`](raft/README.md) ↳10 — One leader per numbered term, one ordered log, committed at a majority. Paxos you can implement.
8. [`zab`](zab/README.md) ↳0 — The engine inside ZooKeeper — and not a replicated state machine at all. It is primary-backup.
9. [`split-brain`](split-brain/README.md) ↳4 — Two nodes both believing they are in charge. "At most one believes" is impossible; fencing is the answer.
10. [`distributed-locking`](distributed-locking/README.md) ↳8 — A lock made of messages, held by a process that cannot observe its own delays.
11. [`jepsen`](jepsen/README.md) ↳0 — The instrument that decides whether any of the last four stages is actually true of a running system. Records a history; searches for an explanation; convicts when none exists.

## Stage 5 — Work That Spans Services

A business action now touches several systems that can each fail alone. These are the patterns that keep it whole without a distributed lock.

1. [`two-phase-commit`](two-phase-commit/README.md) ↳18 — Commit or abort as a unit, via a coordinator. Its defining weakness is blocking.
2. [`distributed-transactions`](distributed-transactions/README.md) ↳7 — 2PC alone is not one. Commit across shards is a window, not an instant.
3. [`saga`](saga/README.md) ↳12 — A business transaction as local steps with compensations, run backwards on failure.
4. [`transactional-outbox`](transactional-outbox/README.md) ↳22 — Saving data and announcing it are two physical acts, and the world can crash between them.
5. [`idempotency`](idempotency/README.md) ↳13 — Silence carries no information, so retries are mandatory, so duplicates are certain. Only a key distinguishes them.
6. [`event-sourcing`](event-sourcing/README.md) ↳16 — Store the passbook, compute the balance. State is a fold over an immutable stream.
7. [`cqrs`](cqrs/README.md) ↳12 — Split the model along the read/write line. The read model is allowed to be stale only because it makes no decisions — and the window it costs you has a measurable width, a formula, and a cliff.
8. [`change-data-capture`](change-data-capture/README.md) ↳0 — A query captures data; CDC captures change. A poller over updated_at fails five ways, four silently.
9. [`kafka`](kafka/README.md) ↳0 — Stop deleting the messages, and every other property follows: replay, many audiences, and order only per key.
10. [`idempotent-consumer`](idempotent-consumer/README.md) ↳0 — The receiving end of every lab above. "Make it idempotent" is three obligations, and the one that fails in production is memory. **← read after `kafka`; it assumes offsets, groups and rebalances.**
11. [`kafka-exactly-once`](kafka-exactly-once/README.md) ↳0 — The one hop the lab above declines. Kafka has no rollback: records are written before any decision exists, and the abort is a marker appended after them. **← read last in this stage; it assumes `kafka`, `two-phase-commit`, `idempotency` and `idempotent-consumer`.**

## Stage 6 — Running It In Production

Correct is not enough once there is load, money, and a deadline. Queues, limits, cloud, and the human side of shipping.

1. [`microservices`](microservices/README.md) ↳1 — They buy exactly one thing — independent deployability — and only a data boundary delivers it.
2. [`queueing-theory`](queueing-theory/README.md) ↳1 — Waiting is not proportional to load. Time inside grows as 1/(1−ρ), and the last few percent cost the most.
3. [`load-shedding`](load-shedding/README.md) ↳1 — Past capacity, requests are lost either way. The only choice is whether you pick which ones, or the queue picks for you.
4. [`load-balancing`](load-balancing/README.md) ↳0 — It performs no work and adds no capacity. It only decides which queue you wait in — and better information about load can make that decision worse.
5. [`distributed-rate-limiting`](distributed-rate-limiting/README.md) ↳10 — A limiter correct on one machine stops being correct the instant there are two.
6. [`work-pressure-and-deadlines`](work-pressure-and-deadlines/README.md) ↳3 — Advice loses to advice. A date is a bet, not a fact — so make the bet quantitative.
7. [`amazon-web-services`](amazon-web-services/README.md) ↳1 — A shop where you rent other people's distributed systems. Three inventions, stacked.
8. [`terraform`](terraform/README.md) ↳0 — Not a tool for creating infrastructure — a tool for maintaining a mapping nothing in the cloud knows about.
9. [`compound-engineering-plugin`](compound-engineering-plugin/README.md) ↳0 — Finishing saves the code and loses the reasoning, so the same discovery gets paid for twice.

## Stage 7 — Whole Systems

Capstones. Each one is every earlier stage at once, with the tradeoffs already made by someone who had to ship it.

1. [`google-spanner`](google-spanner/README.md) ↳2 — Did not build a more accurate clock — built one that admits how wrong it might be.
2. [`cockroachdb`](cockroachdb/README.md) ↳0 — Spanner made writers pay for clock error by waiting. CockroachDB declares a bound and pays elsewhere.
3. [`mongodb`](mongodb/README.md) ↳0 — The document is the unit of everything; the majority commit point is the line between an opinion and a fact. **← read after `raft`, `split-brain`, `causal-consistency`, `isolation-levels` and `partitioning-and-rebalancing`; it assembles all of them and re-teaches none.**
4. [`whatsapp-system-design`](whatsapp-system-design/README.md) ↳2 — Hard for one invisible reason: the person you are messaging is usually not there.
5. [`uber-system-design`](uber-system-design/README.md) ↳2 — Moving supply, moving demand, milliseconds — and never the same car promised twice.
6. [`google-docs-system-design`](google-docs-system-design/README.md) ↳2 — One product decision — your keystroke appears before any server agrees — and everything else is the bill.

## Parallel track — The JVM Underneath

A parallel track, not a stage. None of it depends on the distributed line, and the distributed line does not depend on it — until production, where tuning and queueing meet.

1. [`java-memory-model`](java-memory-model/README.md) ↳8 — Which reorderings are legal between threads. happens-before is the contract; races are undefined-ish.
2. [`java-multithreading`](java-multithreading/README.md) ↳2 — Two questions only: can both be in here at once, and is either guaranteed to see the other.
3. [`java-virtual-threads`](java-virtual-threads/README.md) ↳8 — A cheap heap-backed thread. Blocking parks the stack and frees the carrier, so waiting is nearly free.
4. [`structured-concurrency`](structured-concurrency/README.md) ↳3 — Confine every subtask's lifetime to a block. Errors flow up, cancellation flows down.
5. [`scoped-values`](scoped-values/README.md) ↳2 — An invisible final parameter for everything below a block — and the cure for ThreadLocal's diseases.
6. [`java-version-features`](java-version-features/README.md) ↳0 — Java nearly froze on 8 because releases were feature-driven. The six-month train is the fix.
7. [`jvm-garbage-collectors`](jvm-garbage-collectors/README.md) ↳1 — A collector never finds the dead. It marks the reachable and declares the rest dead by definition.
8. [`jvm-performance-tuning`](jvm-performance-tuning/README.md) ↳0 — Not flag-picking — bottleneck-finding. Elapsed time splits into seven buckets and exactly one dominates.

## Load-bearing twelve

The labs the rest lean on, measured from the graph. If time is short, re-read only these.

- `cap-theorem` — 28 labs build on it
- `transactional-outbox` — 22 labs build on it
- `two-phase-commit` — 18 labs build on it
- `caching` — 17 labs build on it
- `event-sourcing` — 16 labs build on it
- `quorums-and-replication` — 14 labs build on it
- `idempotency` — 13 labs build on it
- `paxos` — 13 labs build on it
- `btree-and-lsm` — 12 labs build on it
- `cqrs` — 12 labs build on it
- `saga` — 12 labs build on it
- `communication-protocols` — 10 labs build on it

## Gaps — suggested by your labs, never opened

Topics named as `Next` across the labs and never taken up. **load-balancing** —
asked for by nine labs, more than any other — was at the top of this list and was
taken up on 2026-08-31, following **mvcc-and-vacuum**, **clock-synchronization**
and **load-shedding** the day before. Counts below are occurrences of `[topic?]`
in `KNOWLEDGE-MAP.md`, recounted 2026-08-31.

- **database-indexing** — asked for by 5 labs. B-tree and LSM gave you the structures. This is choosing them: selectivity, covering indexes, and why the planner ignored yours.
- **idempotency-keys** — asked for by 5 labs. Narrower than the `idempotency` lab you have: the key itself — where it comes from, how long it lives, and what it costs at the storage layer.
- **observability / observability-slos** — asked for by 6 labs across two spellings, and now seven: `load-balancing` found that every failure it teaches is invisible on an ordinary dashboard. Metrics vs logs vs traces, cardinality, and SLOs as a budget.
- **tail-latency-and-hedging** — asked for by 4 labs, and named by `load-balancing` as its direct sequel. The p99 is a different system from the mean. Hedged requests, and the cost of asking twice.
- **backpressure** — asked for by 4 labs. Both `load-shedding` and `load-balancing` deferred to it. Reactive streams, TCP windows and Kafka consumer lag as one idea in three places.

---

Generated 2026-08-30 from KNOWLEDGE-MAP.md. `kafka`, `jepsen`,
`mvcc-and-vacuum`, `clock-synchronization`, `load-shedding`, `load-balancing`,
`idempotent-consumer`, `kafka-exactly-once` and `mongodb` were added by hand
afterwards; their `↳N` prerequisite counts and the stage ordering have **not**
been recomputed, and `LEARNING-PATH.html` is now **nine labs behind**. Regenerate
both from KNOWLEDGE-MAP.md's `Builds on` links after adding labs.

`mongodb` is placed at Stage 7 item 3 on a curriculum judgement that its `Builds
on` links support: it names sixteen earlier labs and adds no new mechanism of its
own — it is `raft` (pull-based), `split-brain` (the stale-primary window as three
read concerns), `causal-consistency` + `hybrid-logical-clocks` (cluster time and
sessions), `isolation-levels` (snapshot-isolated transactions) and
`partitioning-and-rebalancing` (shard keys, ranges, the balancer) assembled into
one product, so it sits with the other capstones, directly after `cockroachdb`,
which shows the same "proceed and detect" storage posture at the other end of the
consistency dial.

`load-shedding` and `load-balancing` are placed at Stage 6 items 3 and 4 on their
`Builds on` links, which are unambiguous: both build on `queueing-theory`
directly above them, and `load-balancing` builds on `load-shedding` (it reuses
that lab's service — four workers, 80 ms, 50 requests a second — as *one of its
eight backends*). Take them in that order: the cliff, then what to do when you
are past it, then how to avoid reaching it on one machine while seven others sit
idle.

`kafka-exactly-once` is placed at Stage 5 item 11 on its `Builds on` links,
which are unambiguous: every one of them — `kafka`, `two-phase-commit`,
`idempotency`, `idempotent-consumer`, `transactional-outbox`, `saga` — is already
in Stage 5 above it, and it is the only lab in the map that consumes all six.
Read it after `idempotent-consumer` rather than instead of it: that lab covers
every redelivery source except the one a Kafka transaction removes, and this one
covers the single hop it declines.

`clock-synchronization` is placed at Stage 3 item 7 on a curriculum judgement,
not on its `Builds on` links: it is the physical-time half of the pair whose
logical half is `causal-consistency`, and `hybrid-logical-clocks` is the fusion
of the two, so it belongs between them. Six labs that were learned earlier —
`hybrid-logical-clocks`, `google-spanner`, `distributed-transactions`,
`distributed-locking`, `cockroachdb`, `split-brain` — consume ε and now have
their foundation behind them rather than ahead.
