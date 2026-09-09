# Learning

Self-study labs on distributed systems, databases, the JVM, and networking.
71 topics, each a numbered folder with a README you read like a story, a
browser visualization you drive yourself, and exercises with solutions.

## Start here

| File | What it is |
|---|---|
| [`LEARNING-PATH.md`](LEARNING-PATH.md) | **The order.** Labs numbered 1 to 71 in eight stages, from one request on one wire up to whole systems. The single source of the numbering. |
| [`LEARNING-PATH.html`](LEARNING-PATH.html) | The same path as a visual page. Open it in a browser; the lab links are clickable. |
| [`KNOWLEDGE-MAP.md`](KNOWLEDGE-MAP.md) | What each lab covers and how they depend on each other (478 `Builds on` links). Newest first. |
| [`STYLE.md`](STYLE.md) | How a lab is written: the Feynman / Socratic voice contract every README follows. |

## Which labs matter most

Every lab in `LEARNING-PATH.md` carries two ratings: `I★★★` for how likely you
are to be asked about it in an interview, and `L★★★` for how much understanding
it changes the way you think. They are a judgement call, recorded with reasons
in [`tools/importance.tsv`](tools/importance.tsv).

They are deliberately **not** the `↳N` count sitting next to them. That number
says how many labs cite this one — a fact about how these notes are wired
together, not a claim about whether the topic earns your time. The two disagree
in both directions, and the *Which of these actually matter?* section of
`LEARNING-PATH.md` lists exactly where: `flp-impossibility` is cited twice and
is one of the most clarifying labs here, while `redis` is cited eleven times and
is mostly vocabulary once you know caching.

## How the folders are numbered

Every lab folder is `<number>-<slug>`: `01-networking-for-developers`,
`24-cap-theorem`, `39-paxos`. The number is the lab's position in
`LEARNING-PATH.md`. Sort the folder by name and you are looking at the
curriculum.

**Inserting a lab later:** a topic that belongs between 3 and 4 gets the number
3.5 and the folder `03.5-<slug>`. Between 3 and 3.5 it gets 3.25. Nothing
else moves. The `/learn` skill does this automatically when it creates a lab;
`python tools/renumber.py --check` confirms folders and links agree, and
`--renumber` collapses the decimals back to a clean 1..N whenever you want
that.

## Shape of a lab

Each folder holds a `README.md` (the lesson), a `visualization.html` (open it
in a browser), and `EXERCISES.md` with `SOLUTIONS.md`. Some older labs also
carry runnable Java, kept for reference; the lesson never depends on running
it. Every one of the 71 labs now has all four files.

## How a lab reads

A README opens with the story, not with a table of contents. Every section
asks its question before answering it, says the answer in one breath a
twelve-year-old could follow, then gives the engineer's version with the
numbers in tables. Symbols arrive after the words for them. The story's
characters stay on stage all the way into the failure analysis and the
production section. And nothing is ever cut to make a lab shorter: a lab that
reads badly gets more structure, not less content. The full contract is
[`STYLE.md`](STYLE.md).

`39-paxos` and `01-networking-for-developers` were rewritten first, to set the
standard. The rest are being re-delivered in batches. The backlog at the
bottom of this file says which labs are done, which are queued, and what the
audit found wrong with each queued one.

## Tools

| Command | What it does |
|---|---|
| `python tools/renumber.py --check` | Every folder matches its `LEARNING-PATH.md` line; every relative link resolves |
| `python tools/renumber.py --apply` | Rename folders to match the path file; rewrite links |
| `python tools/renumber.py --renumber` | Collapse decimal numbers into 1..N, then apply |
| `python tools/audit.py .` | Delivery signals for every lab (front-matter, templates, notation, story continuity) |
| `python tools/preserve-check.py old.md new.md` | Prove a rewrite lost no number, link, table, diagram or glossary term |
| `python tools/xref-check.py <lab>` | Confirm `EXERCISES.md` and `SOLUTIONS.md` still cite sections that exist |
| `python tools/mark-rewritten.py` | Stamp the knowledge map and rebuild the backlog below, from `tools/rewritten.txt` |
| `python tools/mark-importance.py` | Write the interview and learning ratings into `LEARNING-PATH.md`, from `tools/importance.tsv` |
| `python tools/gen-atlas.py` | Rebuild `LEARNING-PATH.html` from the path file and the knowledge map |

## Running the Java labs (historical)

Most are single-file and need nothing on the classpath:

```bash
java Foo.java
```

A few need an embedded database. Those jars are not committed; see
[`lib/README.md`](lib/README.md) for the Maven Central download commands and
the pinned versions. Two labs generate throwaway keys and certificates on first
run (`ssh/ssh-real.sh`, and the `tls` lab's setup); those artifacts are not
committed either.

## Rewrite backlog

<!-- BACKLOG:START -->
47 rewritten, 1 written from scratch, 0 queued, 23 left as-is. A lab is left as-is when `tools/audit.py` finds none of the delivery problems: no wall of meta before the story, no repeated label templates, few measurement asides, symbols introduced after words.

Queued labs are re-delivered with `/learn rewrite <slug>`. A rewrite never removes content: `tools/preserve-check.py` compares old against new and fails if a number, link, diagram or glossary row went missing, and `tools/xref-check.py` confirms the exercises still point at the right sections.

| # | Lab | Status | Note |
|---|---|---|---|
| 1 | `networking-for-developers` | **rewritten** | re-delivered 2026-09-08, after the reader reported it hard to read |
| 2 | `communication-protocols` | **rewritten** | re-delivered 2026-09-08, after the reader reported it hard to read |
| 3 | `io-multiplexing` | **rewritten** | re-delivered 2026-09-09 |
| 4 | `database-landscape` | **rewritten** | re-delivered 2026-09-09 |
| 5 | `btree-and-lsm` | reads well, left as-is |  |
| 6 | `software-architecture-patterns` | reads well, left as-is |  |
| 7 | `acid` | **rewritten** | re-delivered 2026-09-09 |
| 8 | `isolation-levels` | **rewritten** | re-delivered 2026-09-08 |
| 9 | `mvcc-and-vacuum` | **rewritten** | re-delivered 2026-09-09 |
| 10 | `optimistic-vs-pessimistic-locking` | reads well, left as-is |  |
| 11 | `caching` | reads well, left as-is |  |
| 12 | `http-caching` | reads well, left as-is |  |
| 13 | `redis` | **rewritten** | re-delivered 2026-09-09 |
| 14 | `tls` | **rewritten** | re-delivered 2026-09-08 |
| 15 | `ssh` | **rewritten** | re-delivered 2026-09-09 |
| 16 | `java-multithreading` | **rewritten** | re-delivered 2026-09-09 |
| 17 | `java-memory-model` | **written** | new document, 2026-09-09 — this lab had none |
| 18 | `java-virtual-threads` | reads well, left as-is |  |
| 19 | `structured-concurrency` | reads well, left as-is |  |
| 20 | `scoped-values` | reads well, left as-is |  |
| 21 | `java-version-features` | **rewritten** | re-delivered 2026-09-09 |
| 22 | `jvm-garbage-collectors` | **rewritten** | re-delivered 2026-09-09 |
| 23 | `jvm-performance-tuning` | **rewritten** | re-delivered 2026-09-09 |
| 24 | `cap-theorem` | reads well, left as-is |  |
| 25 | `pacelc` | reads well, left as-is |  |
| 26 | `linearizability` | **rewritten** | re-delivered 2026-09-09 |
| 27 | `linearizability-vs-serializability` | reads well, left as-is |  |
| 28 | `eventual-consistency` | reads well, left as-is |  |
| 29 | `causal-consistency` | **rewritten** | re-delivered 2026-09-09 |
| 30 | `clock-synchronization` | **rewritten** | re-delivered 2026-09-09 |
| 31 | `hybrid-logical-clocks` | reads well, left as-is |  |
| 32 | `crdts` | **rewritten** | re-delivered 2026-09-09 |
| 33 | `flp-impossibility` | **rewritten** | re-delivered 2026-09-09 |
| 34 | `quorums-and-replication` | **rewritten** | re-delivered 2026-09-08 |
| 35 | `consistent-hashing` | **rewritten** | re-delivered 2026-09-09 |
| 36 | `partitioning-and-rebalancing` | reads well, left as-is |  |
| 37 | `gossip-protocols` | **rewritten** | re-delivered 2026-09-09 |
| 38 | `anti-entropy` | **rewritten** | re-delivered 2026-09-09 |
| 39 | `paxos` | **rewritten** | re-delivered 2026-09-08, after the reader reported it hard to read |
| 40 | `raft` | **rewritten** | re-delivered 2026-09-08 |
| 41 | `zab` | **rewritten** | re-delivered 2026-09-09 |
| 42 | `split-brain` | **rewritten** | re-delivered 2026-09-09 |
| 43 | `distributed-locking` | reads well, left as-is |  |
| 44 | `jepsen` | reads well, left as-is |  |
| 45 | `two-phase-commit` | reads well, left as-is |  |
| 46 | `distributed-transactions` | reads well, left as-is |  |
| 47 | `saga` | reads well, left as-is |  |
| 48 | `transactional-outbox` | reads well, left as-is |  |
| 49 | `idempotency` | reads well, left as-is |  |
| 50 | `event-sourcing` | reads well, left as-is |  |
| 51 | `cqrs` | **rewritten** | re-delivered 2026-09-09 |
| 52 | `change-data-capture` | **rewritten** | re-delivered 2026-09-09 |
| 53 | `kafka` | **rewritten** | re-delivered 2026-09-09 |
| 54 | `idempotent-consumer` | **rewritten** | re-delivered 2026-09-09 |
| 55 | `kafka-exactly-once` | **rewritten** | re-delivered 2026-09-09 |
| 56 | `microservices` | **rewritten** | re-delivered 2026-09-09 |
| 57 | `queueing-theory` | **rewritten** | re-delivered 2026-09-09 |
| 58 | `load-shedding` | **rewritten** | re-delivered 2026-09-09 |
| 59 | `load-balancing` | **rewritten** | re-delivered 2026-09-09 |
| 60 | `distributed-rate-limiting` | **rewritten** | re-delivered 2026-09-09 |
| 61 | `work-pressure-and-deadlines` | **rewritten** | re-delivered 2026-09-09 |
| 62 | `amazon-web-services` | **rewritten** | re-delivered 2026-09-09 |
| 63 | `terraform` | **rewritten** | re-delivered 2026-09-09 |
| 64 | `compound-engineering-plugin` | **rewritten** | re-delivered 2026-09-09 |
| 65 | `url-shortener-system-design` | reads well, left as-is |  |
| 66 | `google-spanner` | **rewritten** | re-delivered 2026-09-09 |
| 67 | `cockroachdb` | **rewritten** | re-delivered 2026-09-09 |
| 68 | `mongodb` | **rewritten** | re-delivered 2026-09-09 |
| 69 | `whatsapp-system-design` | **rewritten** | re-delivered 2026-09-09 |
| 70 | `uber-system-design` | **rewritten** | re-delivered 2026-09-09 |
| 71 | `google-docs-system-design` | **rewritten** | re-delivered 2026-09-09 |
<!-- BACKLOG:END -->
