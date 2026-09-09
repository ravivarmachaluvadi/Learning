# Apache Kafka

*Stop deleting the messages. Everything else follows from that one decision.*

> **Lab format** (no-code): this document + [`visualization.html`](visualization.html)
> (four interactive simulators) + [`EXERCISES.md`](EXERCISES.md) /
> [`SOLUTIONS.md`](SOLUTIONS.md). Every behaviour here is demonstrated by a
> hand-worked trace with real numbers, by the simulator, by a result measured in
> an earlier lab, or by a citation to Apache Kafka's own documentation — never by
> code you have to run. Config defaults quoted below were checked against the
> **Kafka 4.3** documentation (May 2026); where a default changed recently, the
> version is named.

> **Where you are:** twenty-one earlier labs stopped at Kafka's edge and named it
> as the next thing to learn — more than any other topic in the map. You already
> own almost every part of it:
>
> - **the log itself** — `btree-and-lsm` taught you that sequential writes beat
>   random ones by orders of magnitude, and that an append-only file plus
>   background merging is a legitimate storage engine, not a compromise;
> - **partitions** — `partitioning-and-rebalancing` taught you the partition as
>   the unit of ownership and movement, hot keys, and why `hash % n` is usually a
>   trap (Kafka walks into it deliberately; §10.3 says why);
> - **replication** — `quorums-and-replication` gave you leader/follower, stale
>   replicas, and the arithmetic of "how many copies must answer";
> - **the epoch** — `split-brain` unified term = epoch = generation = fencing
>   token into one object with seven names. Kafka calls it the **leader epoch**,
>   and §10.5 shows the exact data-loss bug that forced Kafka to add it in 2017;
> - **the dual-write problem** — `transactional-outbox` is the reason this lab
>   exists at all, and Kafka is the destination that lab kept writing to;
> - **at-least-once and dedup** — `idempotency` is the whole answer to Kafka's
>   delivery semantics, and §10.7 is that lab's idempotency key, implemented in a
>   network protocol;
> - **why the last 10% of capacity costs so much** — `queueing-theory`, which is
>   why a Kafka cluster at 90% disk-write utilisation behaves nothing like one at
>   60%.
>
> This lab is the assembly. Kafka is not a new idea; it is six ideas you already
> hold, bolted together in an order that makes each one necessary.

---

## Layer guide

| Layer | Stages | What you can do afterwards |
|---|---|---|
| **BEGINNER MODEL** | 1–9 | Explain what a log-based broker is, why it isn't a queue, and what an offset is |
| **INTERMEDIATE MODEL** | 10.1–10.6 | Trace a record from producer to consumer; choose a partition key; explain acks, ISR, and the high watermark; predict what a config change does |
| **ENGINEERING MODEL** | 10.7–12 | Reason about duplicates, ordering, rebalances, transactions, and the nine ways this breaks — including the three that present as silence |
| **PRODUCTION MODEL** | 13–14 | Size a cluster, name the four metrics that matter, know when Kafka is the wrong tool, and refuse the six wrong models |

Read §1–§9 in one sitting. §10 is the machine, built in eight refinements, each
forced by a failure in the one before. §11–§14 is what you need before touching
a production cluster.

---

## 1. WHY — the same fact, wanted by people you haven't met yet

Here is the situation that produced Kafka, stripped of all technology.

Something happens in your business. A parcel is scanned. A payment settles. A
user clicks. That event is *one fact*, and it is interesting to **more than one
audience** — and crucially, to audiences that do not exist yet at the moment the
fact occurs.

Every pre-Kafka answer to "how do I tell the other systems" assumed the opposite:
that the sender knows the receivers. HTTP calls know their callee. A queue knows
its consumer. A database write knows its table. All of them make the producer of
the fact responsible for the list of people who care about it.

That assumption fails in a specific, expensive way: **the list changes far more
often than the fact does**, and every change to the list is a change to the code
that emits the fact. Six months in, the function that records "parcel scanned" is
400 lines long, calls nine services, and is the most dangerous file in the
company — not because scanning parcels got harder, but because *being interesting
got harder*.

Kafka's answer is one sentence, and everything else in this lab is a consequence
of it:

> **Write the fact down once, in order, keep it, and let each audience read at
> its own speed and its own position.**

That sounds trivially obvious. It is not — it costs you the thing every previous
messaging system considered its core service (the broker knowing who has handled
what), and it buys you things those systems structurally cannot offer (replay, a
new consumer reading history, a hundred consumers at zero extra write cost).

---

## 2. THE PROBLEM — Deodar Dispatch has four teams and one scanner

In the town of Deodar — whose library you partitioned two labs ago and whose
noticeboards taught you eventual consistency — **Arjun** runs Deodar Dispatch, a
parcel courier. A parcel is scanned at each step of its journey:

```
parcel P-1001   09:02  PICKED_UP        (courier Nila, Cedar Street)
parcel P-1001   09:41  AT_HUB           (hub 3)
parcel P-1001   11:15  OUT_FOR_DELIVERY (courier Kabir)
parcel P-1001   12:30  DELIVERED        (signed: R. Sharma)
```

Roughly 4,000 scans per second at peak. Four teams want them:

| Team | Wants | Timeliness | Reaction to a duplicate | Reaction to a gap |
|---|---|---|---|---|
| **Billing** (Priya) | `DELIVERED` scans, to invoice | within minutes | double-charges a customer — serious | never invoices — very serious |
| **Notifications** (Sameer) | every scan, to text the customer | within seconds | annoying second text | customer thinks parcel is lost |
| **Analytics** (Devi) | every scan, forever, for hub-throughput models | hours is fine | slightly wrong average | model silently wrong |
| **Fraud** (Kabir) | every scan, to detect impossible routes | within a minute | harmless | missed fraud |

Read the last two columns carefully — they are the whole design brief. The four
teams disagree about **latency** (seconds vs hours), about **history** (Devi
wants a year; Sameer wants the last five minutes), and about **what a mistake
costs**. No single delivery policy is right for all four.

And there is a fifth team that does not exist yet. In eight months, someone will
want to build a delivery-time prediction model, and their first question will be:
*"can I have last year's scans?"*

---

## 3. THE OBVIOUS SOLUTION #1 — the dispatch office phones everybody

The first design any competent engineer writes: when a scan arrives, the scan
service calls each team's HTTP endpoint.

```
scan arrives → POST /billing/scan
             → POST /notifications/scan
             → POST /analytics/scan
             → POST /fraud/scan
             → return 200 to the scanner
```

This is genuinely reasonable. It is simple, it is synchronous (you *know* it
worked), and it needs no new infrastructure.

It dies of three well-understood diseases, all of which you have already met in
the `microservices` lab, so we will move fast:

1. **Availability multiplies downwards.** The scan endpoint is now up only when
   all four teams are up. Four services at 99.9% give the scanner 99.6% — the
   scanner became the *least* reliable thing in the building by depending on
   everyone.
2. **Latency adds.** The courier's phone waits for the slowest of four calls. And
   from `queueing-theory`: whichever of the four is busiest contributes not its
   average but its tail, and the tails are what the courier feels.
3. **Every new audience edits the producer.** Team five means a code change,
   review, and deploy of the most critical service in the company.

There is a subtler fourth. Suppose billing succeeds and notifications times out.
What now? Retry only notifications (you must now track per-callee state)? Fail
the whole scan (the parcel *was* scanned — the fact is true whether or not you
managed to tell anyone)? Return 200 and drop it (silent data loss)? There is no
good answer, because **you are trying to make a fact about the physical world
transactional with four network calls.** That is the dual-write problem from
`transactional-outbox`, wearing a different hat.

---

## 4. THE OBVIOUS SOLUTION #2 — a proper message broker

So Arjun does the correct next thing: he puts a **message broker** in the middle
— the classic kind, the kind that had existed for twenty years before Kafka. Call
it the **pigeonhole board**.

The model is the one almost everyone means by "queue":

- the producer drops a message into the board and is done (decoupled — fixes §3);
- the broker holds it and hands it to a consumer;
- the consumer **acknowledges**; the broker **deletes** it;
- if the consumer dies before acking, the broker hands it to someone else.

This is a good design. It is what RabbitMQ, ActiveMQ, SQS and JMS do, and for
"here is a job, someone please do it" it beats Kafka to this day (§13.7).

The load-bearing sentence is the third one: **acknowledged means deleted**. The
broker's job is to track, per message, whether it has been handled. That single
commitment produces four failures, and every one of them is a design constraint
of Kafka.

---

## 5. FAILURE — four ways the pigeonhole board breaks Deodar Dispatch

### 5.1 Two audiences, one message

Sameer's notification service and Kabir's fraud service both subscribe to the
scan board. Scan `P-1001/PICKED_UP` arrives once. Sameer's consumer picks it up
and acks. It is deleted. **Kabir never sees it.**

The classic fix is *fan-out at the broker*: declare one queue per team and have
the broker copy each message into all four (this is exactly what an AMQP exchange
does). Now:

- the broker stores 4 copies of every scan and does 4 acknowledgement bookkeeping
  operations per scan — 16,000 tracked deliveries per second at Deodar's peak,
  each a small random-access state mutation;
- team five means an *operations* change (declare a queue, bind it) rather than a
  code change — better than §3, but still a change that must happen **before** the
  first message team five cares about arrives.

### 5.2 The fifth team wants last year

Eight months in, the prediction-model team arrives. Their queue is created today.
It receives messages from today. The 300 million scans from before are **gone** —
not archived, not slow to fetch, *deleted*, because deletion was the design.

The workaround everyone reaches for is: also write every scan to a database or a
data lake, and let latecomers read that. Now the scan service writes to two
places (dual write again), the two disagree during incidents, and "the stream"
and "the history" are two different systems with two different schemas and two
different bugs.

### 5.3 Redelivery scrambles a parcel's story

This is the failure that most surprises people, so trace it with real values.

Kabir's fraud consumer reads the board for parcel P-1001 (its four scans were
enqueued in order). It takes `PICKED_UP` (09:02), starts work, and its JVM hits a
long GC pause — you measured those in `jvm-garbage-collectors`. Meanwhile it
takes `AT_HUB` (09:41) and `OUT_FOR_DELIVERY` (11:15) on other threads and
finishes both. The `PICKED_UP` message times out unacked, and the broker — doing
exactly its job — **redelivers it**.

Kabir's service now sees, in this order:

```
AT_HUB (09:41) → OUT_FOR_DELIVERY (11:15) → PICKED_UP (09:02)
```

A parcel that was picked up *after* it went out for delivery. His
impossible-route detector fires on a parcel that is perfectly fine.

The cause is structural, not a bug: **a broker that redelivers on failure cannot
also preserve order**, because redelivery *is* reordering. You can have one or the
other. Classic brokers, being task-oriented, chose redelivery, and the usual
mitigation ("one consumer, prefetch 1, no concurrency") throws away all your
throughput to buy back order.

### 5.4 The bookkeeping is the bottleneck

Every message is a small mutable record with a state machine
(`ready → delivered → acked → deleted`), living per consumer. That is
random-access, read-modify-write work — the access pattern `btree-and-lsm` taught
you is the expensive one. It is why classic brokers are typically comfortable in
the thousands-to-tens-of-thousands of messages/sec range per broker and start
hurting when the queue gets *deep* (millions of pending messages means millions
of live records to index, page in, and eventually rewrite).

**🔮 Predict:** *Which of the four failures above would be fixed by simply
buying a bigger broker machine?*

<details><summary>Reveal</summary>

Only 5.4, and only for a while. 5.1, 5.2 and 5.3 are consequences of the
**semantics** — "acknowledged means deleted", "the broker owns delivery state" —
and no hardware changes semantics. This is the moment to stop optimising and
start questioning the model, and it is exactly the moment LinkedIn was at in
2010 when they wrote Kafka.

</details>

---

## 6. THE REAL REQUIREMENT

Strip the four failures to what they demand. Arjun needs a thing where:

1. **The fact is written once** and one write serves every audience, present and
   future. (kills 5.1's write amplification and §3's fan-out)
2. **Reading does not consume.** Two hundred readers can read the same fact and
   none of them removes it for the others. (kills 5.1)
3. **Facts are kept for a declared time, not until handled.** History is the
   default, not a separate system. (kills 5.2)
4. **Order is preserved for facts about the same thing**, even under failure and
   retry. (kills 5.3)
5. **Each reader's position is its own** — Sameer can be 2 seconds behind while
   Devi is 3 hours behind, and neither affects the other or the producer.
6. **The broker's per-reader bookkeeping must be tiny**, so that adding readers
   is nearly free. (kills 5.4)

Look at requirements 2, 5 and 6 together and the design is already forced. If
reading does not consume, the broker cannot track per-message state. If each
reader has its own position and the bookkeeping must be tiny, then **a reader's
entire state must be a position — a single number.**

That is Kafka. The rest is engineering.

---

## 7. THE KEY IDEA — the ledger and the bookmark

> Replace the pigeonhole board (a pile of tasks the broker hands out and destroys)
> with a **ledger**: an append-only book where every fact is written on the next
> line, and each reader keeps a **bookmark** — the line number they have reached.

Two consequences, both worth saying out loud because they are the whole shift:

**The broker gets simpler, not smarter.** It appends bytes to the end of a file
and serves byte ranges out of the middle. It does not know whether anyone
processed anything. When Sameer's service crashes, the broker does not notice,
does not care, and has nothing to redeliver — because it never *delivered*
anything in the old sense; it answered a read.

**The consumer gets the responsibility.** "Where am I?" is now the consumer's
problem, and — as §10.8 shows — *when* it saves that bookmark decides whether the
system loses records or duplicates them. Kafka does not remove that choice; it
hands it to you, explicitly, in one config.

The name for this is a **log** (in the write-ahead-log sense from `acid` and
`change-data-capture`, not the printf sense). You have now met the same structure
four times: the WAL that makes a database durable, the replication log that makes
a follower a copy, the event store in `event-sourcing`, and the LSM's append-only
segments. Kafka's insight was to notice that the thing databases hide *inside*
themselves is the most useful thing to expose *between* systems.

---

## 8. TERMINOLOGY — earned, in the order you needed it

Only the load-bearing terms here; the rest arrive when the mechanism needs them,
and all of them are in the glossary (§16).

**Record** *(often "message", "event")* — one immutable entry in the log:
`(key, value, timestamp, headers, offset)`. Kafka does not care what the value
is; it is bytes. The **key** is not an ID — its one job is choosing the partition
(§10.3), and therefore the ordering unit. `P-1001` is a good key; a UUID unique
per scan is usually a bad one.

**Topic** — a named ledger: `parcel-scans`. Purely a name; the storage is the
partitions below it. Not a queue, despite the shape of most tutorials.

**Partition** — the actual append-only log. A topic is a *set* of them. Ordering
exists **inside** a partition and nowhere else. This is the single most important
sentence in the lab, and §14.2 is the misconception it exists to kill.

**Offset** — the position of a record in its partition: an integer that starts at
0 and increases by one per record, forever. `(topic, partition, offset)` names a
record uniquely and permanently. Offsets are *not* comparable across partitions —
`partition-0/offset-500` and `partition-1/offset-500` have no relationship
whatsoever.

**Producer** — a client library that appends. **Consumer** — a client library
that reads from a position. **Broker** — a server holding partitions. **Cluster**
— brokers together. Note that "producer" and "consumer" are *libraries in your
application*, not servers: a surprising amount of Kafka's cleverness lives on the
client side, which is why Kafka client versions matter as much as broker versions.

**Retention** — how long a record stays before deletion, declared per topic
(default `retention.ms` = 7 days). The broker deletes by **age or size**, never
by "everyone read it". A consumer that falls behind by more than the retention
period does not slow anything down — it *loses data*, silently, and §11.6 is that
failure in detail.

> **Checkpoint.** You can now explain what a log-based broker is and why it is not
> a queue. You cannot yet explain how it goes faster than one machine, survives a
> crash, or lets ten consumers share work. Those are §10's eight refinements, and
> each one is forced by a failure in the one before.

---

## 9. TWO MENTAL MODELS, AND WHERE THE FIRST ONE LIES

### The simple model — the newspaper archive

A topic is a **newspaper**, and each partition is one **edition** printed
continuously. The broker is the archive: it files every issue in order and keeps
them for seven days. Subscribers do not receive papers; they come to the archive
and read from where they left off, noting the page number as they go. A hundred
subscribers cost the archive nothing extra — they are reading the same shelf.
Nobody's reading removes an issue. After seven days the archive skips the old
ones for pulping, whether or not anybody read them.

### The engineering model

```
topic "parcel-scans", 3 partitions, replication factor 3

  partition 0  [ 0 ][ 1 ][ 2 ][ 3 ][ 4 ][ 5 ]         ← leader on broker 1
  partition 1  [ 0 ][ 1 ][ 2 ]                        ← leader on broker 2
  partition 2  [ 0 ][ 1 ][ 2 ][ 3 ][ 4 ]              ← leader on broker 3
                 ↑              ↑
        group "analytics"    group "notifications"
        committed offset 1   committed offset 4
```

- a **partition** is a directory of segment files on one broker's disk, plus two
  copies elsewhere;
- writes go **only to the leader replica** of a partition; followers pull from it;
- a record is bytes at a byte-offset in a segment file, found through a sparse
  index;
- a consumer's state is a small number per partition — and even that lives in a
  Kafka topic (§10.8), so the broker still stores nothing bespoke.

### Mapping the models

| Newspaper archive | Kafka |
|---|---|
| Newspaper title | topic |
| One continuously printed edition | partition |
| Issue number | offset |
| Reader's noted page number | committed offset |
| Reading room (any number of readers) | consumers, all reading the same files |
| Seven-day shelf, then pulped | `retention.ms` |
| Three archives in three towns, one authoritative | replication factor 3, one leader |

### Where the analogy lies — read this before it hardens

1. **There is no single chronological "newspaper".** Three partitions are three
   independent editions. Record *X* in partition 0 and record *Y* in partition 1
   have **no defined order**, ever, even if X was produced an hour earlier. The
   archive analogy quietly suggests a global timeline; there isn't one.
2. **Readers are not passive.** A Kafka consumer *pulls*, in batches, on its own
   schedule. Nothing is pushed. This is why a slow consumer cannot hurt the
   broker or the producers — it simply asks less often (compare the classic
   broker, where a slow consumer makes the broker's backlog grow).
3. **The archivist has no idea who read what.** Not "doesn't tell you" — *does
   not know*. Consumer lag is computed by subtracting a number the consumer wrote
   down from a number the broker knows; nobody tracked a delivery.
4. **Pulping is not "when everyone's done".** Retention is a clock, not a
   refcount. The archive throws away issues that nobody has read yet, on
   schedule, and this is a *feature* (bounded storage) that becomes §11.6's
   failure when a consumer is down for a long weekend.

---

## 9.5 OPEN THE SIMULATOR NOW — what to look at

Before the mechanism, get the picture moving. Open
[`visualization.html`](visualization.html) — **"The Deodar Ledger"** — in any
browser. It is four tabs, and each one exists to answer one question that prose
answers badly:

| Tab | The question it answers | Read it alongside |
|---|---|---|
| **1 · The Log** | Where does a keyed record land, why do two consumer groups not interfere, and what *exactly* breaks when you add a partition? | §10.1–10.3 |
| **2 · Replication** | What do LEO, HW and ISR do during a slow follower and a leader crash — and which `acks`/`min.insync.replicas` combinations lose an acknowledged record? | §10.4–10.5 |
| **3 · Consumer Group** | What does a rebalance actually reassign, how much does eager cost versus cooperative, and where do duplicates come from? | §10.8, §10.10 |
| **4 · Retention & Compaction** | What does a late consumer see, and what does the cleaner really remove? | §10.9 |

Each tab has a **verdict line** under the controls that says, in plain English,
what just changed and why. Tabs 2 and 3 include a **predict-then-run** step:
commit to an answer, then watch. That step is the point — a prediction you got
wrong is worth ten paragraphs you agreed with.

> **What the simulator is and is not.** It animates *this lab's model* of Kafka —
> the state machine of LEO/HW/ISR, assignment, and cleanup — at a scale you can
> read (single-digit records, three brokers). It is not a Kafka client and proves
> nothing about a real cluster; where a real broker's behaviour differs in a way
> that matters, this document says so in text.

---

## 10. THE ACTUAL MECHANISM — eight refinements, each forced by the last

We build Kafka the way it was built: the simplest thing that could work, then the
failure that breaks it, then the fix.

### 10.1 Version 1 — one log, one file, one machine

Arjun's first ledger: one file, `parcel-scans.log`, on one machine. Producers
append. Consumers read from a byte offset.

Requirements 1–3, 5 and 6 from §6 are already satisfied by this trivial design,
and requirement 4 (order) is satisfied *completely*: there is one file, so there
is one order, and it is the order of arrival.

**Why this is fast, which is not obvious.** People assume "writes to disk" means
"slow", so it is worth being precise, because Kafka's throughput is not magic:

- **Sequential writes.** `btree-and-lsm` established the gap between sequential
  and random I/O — orders of magnitude, on both spinning disks and SSDs. Appending
  to the end of a file is the best case there is, and it is *all* Kafka ever does
  on the write path.
- **The page cache does the caching.** Kafka does not maintain its own in-process
  record cache. It writes into the OS page cache and lets the kernel flush. A
  consumer reading recent records is served from RAM without Kafka doing anything
  clever — and, importantly, without those bytes living in the JVM heap, which is
  why Kafka brokers run comfortably with modest heaps and enormous machines
  (`jvm-garbage-collectors`: bytes you never put on the heap are bytes the
  collector never scans).
- **Zero copy.** Serving a consumer is "send bytes 4,096–69,632 of this file to
  this socket" — the kernel's `sendfile` path, no copy into user space, no
  deserialisation. Kafka never looks inside a record it is serving. *(Caveat
  worth knowing: TLS encryption forces the data back through user space, so
  `sendfile` no longer applies — encrypted clusters give up this optimisation.)*
- **Batching.** Producers group records into batches and compress the batch as a
  unit; the batch stays compressed on disk and is sent compressed to consumers.
  Compression ratios are far better on a batch of similar records than per
  record, and the CPU is spent once.

None of that is a trick. It is what you get by choosing an access pattern the
hardware likes and then refusing to add cleverness on top of it.

**🔮 Predict:** *One file on one machine. Deodar grows tenfold. Name the two
different ceilings this design hits — and note that they are not the same
ceiling.*

<details><summary>Reveal</summary>

**Ceiling 1 — the write side.** One partition lives on one machine: its disk
bandwidth, its NIC, its CPU. You can buy a bigger machine exactly once.

**Ceiling 2 — the read side, and this one no hardware fixes.** Records in a
single log must be processed *in order* by the audience that cares about order.
Kabir's fraud detector cannot run 20 threads over one log and keep parcel order.
Even with infinite disk bandwidth, **one log means one ordered reader**.

Ceiling 2 is the interesting one, because it means the fix cannot be "make the
log bigger" — it has to be "have more logs". Which immediately raises the
question that decides everything else: *which records go in which log?*

</details>

### 10.2 Version 2 — many logs (partitions), and the price of the split

A topic becomes **N independent logs**. Now N brokers write in parallel, and N
consumers read in parallel.

And instantly you have paid the price named in §9's "where the analogy lies":
**there is no longer a total order.** Kafka does not try to hide this or soften
it. The guarantee is stated exactly, and it is the one you will quote for the
rest of your career:

> **Records are ordered within a partition. Across partitions, there is no
> order.**

So the design question is not "how do I keep ordering" — it is **"what is the
smallest unit of my data that genuinely needs ordering?"** For Deodar Dispatch,
that unit is the parcel: P-1001's four scans must stay in sequence; P-1001 and
P-2002 have nothing to do with each other and may interleave freely.

That unit is called the **key**.

### 10.3 Keys, and the modulo you were warned about

The producer computes the partition. In the Java client's default partitioner:

```
partition = murmur2(serialized_key) % number_of_partitions       // key present
```

Same key → same partition → same log → ordered, forever. Different keys spread
across partitions.

Trace it, with `parcel-scans` at 3 partitions:

```
P-1001 PICKED_UP        murmur2("P-1001") % 3 = 2   → partition 2, offset 40
P-2002 PICKED_UP        murmur2("P-2002") % 3 = 0   → partition 0, offset 88
P-1001 AT_HUB           murmur2("P-1001") % 3 = 2   → partition 2, offset 41
P-1001 OUT_FOR_DELIVERY murmur2("P-1001") % 3 = 2   → partition 2, offset 42
P-1001 DELIVERED        murmur2("P-1001") % 3 = 2   → partition 2, offset 43
```

P-1001's story is offsets 40→43 of partition 2, in order, permanently. Kabir's
detector can run three consumers in parallel — one per partition — and *still*
never see a parcel out of order, because a parcel never spans partitions. This is
the trick that makes ordered parallel processing possible at all, and it is worth
sitting with: **Kafka did not solve "ordering vs parallelism". It made you
declare the granularity at which you need order, and then gave you parallelism
across that granularity.**

**When the key is null**, the producer has no ordering constraint to honour, so
it optimises for batching: the modern Java client uses **sticky partitioning** —
fill a batch for one partition, send it, then switch to another. (Older clients
round-robined per record, which made batches small and throughput worse. The
change was KIP-480.) Note the consequence: *null key means no ordering guarantee
for anything*, which is fine for independent events and catastrophic for a
parcel's scan sequence.

**Now the modulo.** In `consistent-hashing` you established at length that
`hash(key) % n` is the design that makes every key's home depend on a global
property, so that changing `n` remaps almost everything — and you learned the
ring as the fix. Kafka uses the modulo anyway. Why?

Because **the ring's fix doesn't apply here.** Consistent hashing moves *keys* to
new homes cheaply. But a Kafka partition is not a bucket you can rehash — it is an
ordered, immutable, append-only log with consumers holding positions inside it.
You cannot move record 41 of partition 2 into partition 3 without destroying the
offsets that every consumer, every committed bookmark, and every replica depends
on. So Kafka makes the opposite trade:

> **Partition count is (effectively) permanent. You may increase it, but existing
> records never move, and from that moment a key may live in two partitions.**

Concretely: increase `parcel-scans` from 3 to 4 partitions at 14:00. P-1001's
scans from before 14:00 are in partition 2 at offsets 40–43. `murmur2("P-1001") %
4` = 1, so scans from 14:01 onward go to partition 1. A consumer of partition 1
may process `DELIVERED` while `OUT_FOR_DELIVERY` is still unread in partition 2.
**Ordering for that key is broken across the boundary, permanently, with no
error, no warning, and no way to repair it.**

This is the single most common self-inflicted Kafka wound, and the operational
rules that follow from it are:

- **over-provision partitions at topic creation** (see §13.3 for how to pick);
- if you must add partitions to a keyed topic, either accept a bounded window of
  disorder (often fine — pause producers, drain consumers, then add), or create a
  new topic and migrate;
- **never** add partitions to a compacted topic used as a table (§10.9) without a
  full rebuild — the "latest value per key" invariant breaks the same way.

**🔮 Predict:** *Devi points out that 80% of Deodar's scans come from 5 large
retail customers, and suggests keying by `customer_id` instead of `parcel_id`,
"so each customer's data stays together." What happens?*

<details><summary>Reveal</summary>

A hot partition, of exactly the kind you measured in
`partitioning-and-rebalancing`. With 12 partitions and 5 heavy customers, at most
5 partitions carry ~80% of the traffic, and one of them probably carries 30% on
its own. That partition's leader broker saturates while others idle, and — the
part people miss — **the consumer assigned to that partition becomes the lag
bottleneck for the whole pipeline**, since a partition is consumed by exactly one
member of a group (§10.10). You cannot add consumers to fix it; there is nothing
to add them to.

The general rule, straight from the partitioning lab: **choose the key by the
ordering requirement, then check its cardinality and skew.** `parcel_id` has
millions of values and near-uniform traffic. `customer_id` has hundreds with a
power-law distribution. Same topic, same code, completely different operational
life.

</details>

> **Checkpoint.** You can now explain the log, offsets, partitions and keys — the
> beginner model, complete. Everything so far assumed the broker never crashes.
> It does, and the next three refinements are entirely about that.

### 10.4 Version 3 — replication, and why "written" has three meanings

One machine holds partition 2. Its disk dies. P-1001's story is gone, along with
Devi's year of history.

The fix is the one from `quorums-and-replication`, specialised: each partition
gets **R replicas** (typically 3) on different brokers. One is the **leader**;
all reads and writes for that partition go to the leader. The others are
**followers**, and — a detail worth noticing — followers replicate by *sending
the leader an ordinary fetch request*, the same protocol consumers use. A
follower is, mechanically, a consumer that writes what it reads to its own log.

Three numbers per replica govern everything that follows:

- **LEO** (log end offset) — the next offset this replica will write; i.e. how far
  it has got.
- **HW** (high watermark) — the highest offset that *every in-sync replica* has.
  **Consumers may only read below the high watermark.**
- **ISR** (in-sync replica set) — the replicas currently caught up "enough".
  Formally: a replica is in the ISR if it has fetched up to the leader's LEO
  within the last `replica.lag.time.max.ms` (default 30s). Note that this is a
  *time* lag, not a message-count lag — a replica 2 million records behind but
  fetching hard is still in-sync, because it is keeping up with the *rate*.

**Why consumers can't read above the HW.** Suppose the leader has written offset
105 but only it has that record. If a consumer could read 105 and the leader then
died, the new leader (which has up to 104) would assign offset 105 to a
*different, later* record. A consumer would have read a record that never
officially existed, and — worse — the offset would be reused for different
content. The high watermark is the line between "written on the leader" and
"impossible to lose without losing every copy". Kafka calls records below it
**committed**.

That is a mechanism you have seen before under another name: the HW is Kafka's
**commit point**, and the ISR is a *dynamic* quorum rather than the fixed
majority of `quorums-and-replication`. That difference matters — see §12.4.

**The producer's `acks` setting** chooses how much of this the producer waits for.
This is the config people get wrong most often after partition count:

| `acks` | Producer waits for | Survives | Costs |
|---|---|---|---|
| `0` | nothing (fire and forget) | nothing; you won't even learn about a failure | fastest; unbounded loss |
| `1` | leader wrote to its log | leader staying alive | one crash between write and replication loses acknowledged records |
| `all` (`-1`) | every replica **in the ISR** has it | any failure that leaves an ISR member alive | one extra round trip of latency |

**Since Kafka 3.0 the default is `acks=all`** (with idempotence on — §10.7), which
is the right default and reverses a decade of tutorials that say otherwise.

**And now the trap.** `acks=all` means *all in-sync replicas*, not *all
replicas*. Trace it:

```
partition 2, RF=3, acks=all, min.insync.replicas=1 (the default!)

10:00:00  ISR = {B1(leader), B2, B3}         healthy
10:00:12  B2 and B3 both fail
10:00:42  30s of replica.lag.time.max.ms elapse → leader shrinks ISR = {B1}
10:00:43  producer sends record R, acks=all
10:00:43  B1 writes R, and "all in-sync replicas have it" is TRUE — ISR is {B1}
10:00:43  producer receives SUCCESS
10:00:50  B1's disk fails
          → R is gone. The producer was told it was durable.
```

The ISR shrank to a single replica and `acks=all` degraded silently to `acks=1`.
The fix is the second half of the setting, and it is a *topic/broker* config, not
a producer one:

```
min.insync.replicas = 2      # with RF=3 → tolerate one failure, refuse at two
```

With `min.insync.replicas=2`, the write at 10:00:43 fails immediately with
`NOT_ENOUGH_REPLICAS` instead of succeeding falsely. **The durable configuration
is `acks=all` AND `min.insync.replicas=2` AND `replication.factor=3` — all three,
or none of them.** Note what you just did: you chose unavailability over silent
loss during a partition. That is `cap-theorem`'s choice, made in two config lines
per topic, and Kafka lets you make it differently per topic — a genuinely nice
piece of design.

**🔮 Predict:** *RF=3, `min.insync.replicas=3`, `acks=all`. How many broker
failures can this topic tolerate while still accepting writes?*

<details><summary>Reveal</summary>

**Zero.** Any single broker failure drops the ISR to 2, below `min.insync`, and
every produce request fails. RF=3 + min.insync=3 is the classic
"maximum-durability" misconfiguration: it maximises durability of what *is*
written by making writing impossible the first time anything sneezes.

The arithmetic to remember: **failures tolerated for writes = RF −
min.insync.replicas.** 3−2 = 1 is the standard production choice. If you want to
survive two failures while writing, you need RF=5, min.insync=3 — which is
`quorums-and-replication`'s majority, and precisely what Kafka's own internal
controller quorum uses.

</details>

### 10.5 Failover, truncation, and the bug that created the leader epoch

The leader of partition 2 dies. The controller (§13.1) picks a new leader from
the ISR, and every remaining replica must now agree on what the log contains.

Since followers can be at different LEOs, the new leader's log may be *shorter*
than a surviving follower's. Records above the new leader's LEO were never
committed (they were above the HW), so the rule is: **followers truncate to match
the new leader, then resume fetching.** Uncommitted records are discarded — which
is correct, because nobody was ever told they were durable.

Here is where Kafka had a real, shipped, data-losing bug until 2017, and the fix
is a concept you already own. Kafka's followers originally truncated to their
**high watermark** on becoming a follower. The HW propagates to followers one
fetch round *behind* — a follower learns the new HW in the response to its next
fetch. That one-round lag is enough:

```
Setup: partition 2, replicas A (leader) and B. Both have offsets 0..9. HW = 9.

t1  A appends record X at offset 10.        A: LEO=11 HW=9
t2  B fetches, receives X, writes it.       B: LEO=11 HW=9   (B has not yet
                                             learned the HW moved to 10)
t3  A advances HW to 10 (all replicas have X).
t4  B crashes before its next fetch — so B never learns HW=10.
t5  B restarts. Rule: truncate to my HW (9). B drops X.  B: LEO=10
t6  A crashes before B re-fetches.
t7  B becomes leader with LEO=10, appends a *different* record Y at offset 10.
t8  A restarts as follower, truncates to its HW... and the two logs disagree
    about what offset 10 contains.
```

Record X was committed — the producer was told `acks=all` succeeded — and it is
gone, replaced by a different record at the same offset. Two logs that permanently
disagree about the content of an offset, which is worse than loss: it is
**divergence**, and every consumer that read X saw something that no longer
exists.

The fix, KIP-101 (Kafka 0.11, 2017), is the object `split-brain` told you has
seven names: a **leader epoch**. Every leadership change increments a monotonic
integer; every record is stamped with the epoch of the leader that wrote it; each
broker keeps a small `leader-epoch-checkpoint` file mapping epoch → first offset.
On restart, a follower no longer guesses from its HW. It **asks**:

```
B → A:  OffsetsForLeaderEpoch(partition 2, my last epoch = 5)
A → B:  "epoch 5 ended at offset 10"
B:      truncate to exactly 10 — no more, no less
```

Truncation becomes a fact obtained from the authority rather than an inference
from a stale local number. Take the general lesson, because it recurs across a
dozen labs: **whenever a node must decide how much of its state is still valid
after a leadership change, the answer must come from the new authority and be
tagged with a generation number.** Raft's term, ZAB's epoch, CockroachDB's lease,
the ownership epoch of `partitioning-and-rebalancing`, the fencing token of
`distributed-locking` — same object, seventh name.

**Unclean leader election.** What if *every* ISR member is gone and only an
out-of-sync replica survives? Two choices:

- `unclean.leader.election.enable = false` (**the default**): the partition goes
  **offline**. No reads, no writes, until an ISR member returns. You chose
  consistency; you are unavailable.
- `= true`: promote the stale replica. The partition returns instantly — and every
  committed record it lacks is **silently gone**, with offsets reused for new
  records. You chose availability; you accepted invisible data loss.

That is `cap-theorem`, per partition, as a boolean. Leave it `false` unless you
are handling a stream where a gap is genuinely cheaper than downtime (some metrics
pipelines qualify; nothing that bills anyone does).

### 10.6 The failure the producer sees, and the duplicates it creates

Now zoom out to the client. Arjun's scan service sends a record, and the network
does what networks do:

```
producer → [record] → leader:  leader writes it at offset 41, sends ack
                               ← ack lost in the network
producer times out, retries    leader writes it AGAIN at offset 42
```

Partition 2 now contains P-1001's `AT_HUB` scan twice, at 41 and 42. Sameer texts
the customer twice. This is the `idempotency` lab's opening problem, verbatim:
**the sender cannot distinguish "my request was lost" from "the response was
lost", so it must retry, and retrying is how duplicates are born.**

Worse, retries can *reorder*. With multiple requests in flight, if batch 1 fails
and is retried while batch 2 succeeds, the log holds batch 2 before batch 1 — and
you have lost the ordering guarantee §10.3 sold you, without touching the keys.

### 10.7 Version 4 — the idempotent producer

The fix is the idempotency lab's, moved into the protocol: **give the request a
key that lets the server recognise a repeat.**

- On startup, the producer gets a **PID** (producer ID) and an epoch from a broker.
- Every batch carries `(PID, epoch, partition, sequence number)`, sequence
  increasing by one per batch per partition.
- The leader remembers the last 5 sequence numbers per (PID, partition). A batch
  whose sequence it has already seen is **acknowledged but not written**. A batch
  arriving with a sequence more than one ahead is rejected with
  `OUT_OF_ORDER_SEQUENCE_NUMBER`.

The result is two guarantees for the price of one:

1. **No duplicates from producer retries** — the retry is recognised and dropped.
2. **No reordering from retries** — the strict sequence check makes it impossible
   for batch *n+1* to land before batch *n*, so up to
   `max.in.flight.requests.per.connection = 5` batches can be in flight *and*
   ordering still holds. (Before idempotence, ordering under retries required
   `max.in.flight=1`, which cost you most of your throughput. This is why the
   idempotent producer is a throughput feature as much as a correctness one.)

**Since Kafka 3.0, `enable.idempotence=true` is the default**, which also forces
`acks=all` and `retries=Integer.MAX_VALUE`. Modern defaults are the safe ones;
most Kafka folklore predates that.

Be precise about the boundary, because §14.4 depends on it:

| Idempotent producer **does** stop | Idempotent producer **does not** stop |
|---|---|
| duplicates from a retry inside one producer session | duplicates from *your application* re-sending after its own restart |
| reordering from retries | duplicates when the producer restarts and gets a **new PID** |
| — | duplicates seen by the *consumer* after a rebalance (§10.8) |
| — | duplicated *side effects* (the email you already sent) |

It de-duplicates a network conversation, not your business process. The business
process still needs `idempotency`'s idempotency key at the point where the effect
happens.

> **Checkpoint.** Producer-to-broker is now complete: partitioned, keyed,
> replicated, durable by explicit config, exactly-once *onto the log* within a
> session. Nothing so far has said how a consumer remembers where it is, or how
> ten consumers split the work. That is the second half of Kafka, and it has more
> failure modes than the first.

### 10.8 Version 5 — where the bookmark lives, and the choice you cannot avoid

Sameer's notification consumer reads partition 2 from offset 40 to 43, texts four
customers, and crashes. It restarts. Where does it resume?

The bookmark must be **durable and outside the consumer process**, or every
restart replays from the beginning of retention. Kafka's answer is pleasingly
self-referential: the consumer **commits** its offset by producing a record to an
internal topic called `__consumer_offsets`, keyed by
`(group, topic, partition)`, holding the value "next offset to read". That topic
is compacted (§10.9), so it collapses to the latest bookmark per key and stays
small forever.

Note what did *not* happen: the broker did not record a delivery. It received an
ordinary write to an ordinary topic. Requirement 6 from §6 survives intact.

**Now the choice.** Commit *before* processing, or *after*?

```
COMMIT BEFORE PROCESSING ("at-most-once")
  poll offsets 40..43 → commit 44 → crash while texting offset 42
  restart at 44. Customers for 42 and 43 are never texted. RECORDS LOST.

COMMIT AFTER PROCESSING ("at-least-once")
  poll offsets 40..43 → text all four → crash before committing
  restart at 40. Customers for 40..43 are texted AGAIN. DUPLICATES.
```

There is no third option available to a consumer whose side effects are outside
Kafka. This is not a Kafka limitation — it is the two-generals shape of the whole
field, and you have met it as at-most-once vs at-least-once in `idempotency` and
`transactional-outbox`. **Kafka's default and the only sane choice for almost
everyone is at-least-once**, with duplicates neutralised at the point of effect
(dedup table, idempotency key, natural idempotence). Say the whole sentence
whenever someone asks about delivery semantics: *at-least-once delivery plus an
idempotent consumer equals effectively-once processing.*

**Auto-commit is a third, sneakier position.** `enable.auto.commit=true` (the
default!) commits the offsets returned by the *previous* poll every
`auto.commit.interval.ms` (5s), from inside `poll()`. So it commits records that
you may still be processing — a bounded at-most-once window — while *also*
allowing duplicates on a crash inside the 5s window. It is the convenient option
and the one to turn off in anything that matters.

**What if the committed offset is gone?** Devi's analytics consumer is off for a
long weekend; retention is 7 days but her lag was already 6 days. It restarts and
asks for offset 1,240,000, which has been deleted. The broker answers
`OFFSET_OUT_OF_RANGE`, and the consumer applies `auto.offset.reset`:

- `latest` (**the default**) — jump to the end. Everything between is **skipped
  silently**. Devi's model is trained on a hole she cannot see.
- `earliest` — jump to the oldest surviving record. Everything between is
  **reprocessed**, which for a non-idempotent consumer is a different disaster.
- `none` — throw an exception. Loud, and usually correct for anything that bills.

This config is also what decides what a *brand new* group does on first start, and
that is the reading of it people actually know. Both readings share one default,
which is why "we deployed a new consumer group and it silently ignored the last
seven days of data" is a rite of passage.

### 10.9 Version 6 — the log is not infinite: retention and compaction

Deodar writes 4,000 scans/sec × 400 bytes ≈ 1.6 MB/s ≈ 138 GB/day, ×3 for
replication ≈ 414 GB/day of disk. Something must delete.

**Physically**, a partition is a directory of **segment** files (default
`segment.bytes` = 1 GiB). Exactly one is the **active segment** being appended to;
the rest are closed and immutable. Deletion happens by removing whole closed
segments — never by editing a file, never by removing a record. This is why
"delete this one record" is not a Kafka operation, and why GDPR erasure on a
Kafka topic is a design problem, not an API call (`event-sourcing` covered the
crypto-shredding answer).

**Two cleanup policies**, and they answer different questions:

**`cleanup.policy=delete`** (the default) — the *stream* policy. Drop whole
segments once every record in them is older than `retention.ms` (default 7 days)
or the partition exceeds `retention.bytes`. Kafka is a buffer of recent history;
the size is bounded by a clock you choose.

**`cleanup.policy=compact`** — the *table* policy. Keep, for every key, **at least
the most recent record**, forever. Older values for the same key are eventually
removed by a background **log cleaner**.

```
before compaction (offsets are permanent, gaps are normal):
  0:(P-1001,PICKED_UP) 1:(P-2002,PICKED_UP) 2:(P-1001,AT_HUB)
  3:(P-1001,DELIVERED) 4:(P-2002,AT_HUB)
after compaction:
  1:(P-2002,PICKED_UP)?  3:(P-1001,DELIVERED)  4:(P-2002,AT_HUB)
        └─ removed once superseded; offsets 0 and 2 are simply absent
```

A compacted topic is a **table**: replay it from offset 0 and you have the current
value of every key that ever existed. That is the log–table duality you met in
`change-data-capture` and `cqrs`, and it is how `__consumer_offsets` stays small,
how Kafka Connect stores connector state, and how Kafka Streams backs its state
stores. **Deletion in a compacted topic is a `null` value — a tombstone**, kept
for `delete.retention.ms` (default 24h) so that a consumer reading through has a
chance to observe the deletion, then removed. *(Absence cannot be shipped: the
same result the partitioning lab reached — a gap is indistinguishable from
never-heard-of-it, so deletion must travel as a record.)*

Three things about compaction that surprise people, each a real incident:

1. **It is not deduplication and it is not prompt.** The cleaner only processes
   *closed* segments, only when the "dirty ratio" of a partition exceeds
   `min.cleanable.dirty.ratio` (default 0.5 — i.e. half the log is stale), and
   `min.compaction.lag.ms` can hold records back deliberately. A consumer reading
   a compacted topic **will** see multiple values for a key. Compaction bounds
   growth; it does not give you a unique-key view.
2. **It is per-partition, so it is per-key-only-if-the-key-picks-the-partition.**
   Adding partitions to a compacted topic scatters one key's history across two
   partitions and the "latest value per key" invariant dies.
3. **`delete` and `compact` can be combined** (`cleanup.policy=compact,delete`),
   which compacts *and* still enforces retention — used when you want a table that
   also forgets, e.g. Streams windowed state.

### 10.10 Version 7 — consumer groups, and the unit of assignment

Sameer's single consumer cannot keep up with 4,000 scans/sec. He wants ten
consumer processes sharing the work — and he wants them to not do the same work
twice.

A **consumer group** is a set of consumers sharing a `group.id`. The rule is one
sentence, and every consumer-side property follows from it:

> **Each partition of a subscribed topic is assigned to exactly one consumer in
> the group at a time.**

Not each *record* — each *partition*. That is why a slow record does not get
handed to somebody else (no redelivery, so §5.3's reordering cannot occur), why
ordering per key survives parallel consumption, and why the broker still tracks
nothing per record.

Consequences worth memorising, because interviews and incidents both live here:

- **Parallelism is capped by partition count.** 12 partitions, 20 consumers → 8
  consumers idle, holding no partitions, doing nothing. This is the second reason
  partition count is your most important sizing decision (§13.3).
- **Groups are independent.** Sameer's group at offset 400,000 and Devi's group at
  offset 12,000 read the same files with no interaction. Requirement 2 from §6,
  delivered. Adding Devi's group costs the cluster only more read bandwidth —
  usually from page cache, so nearly free.
- **A rebalance is not a data move.** Careful here, because the word means
  something very different in `partitioning-and-rebalancing`: **that** rebalance
  copies terabytes between nodes; **this** one reassigns *who reads what* and
  moves zero bytes. Same word, different universe. Confusing them is §14.7.

**How the group is coordinated.** One broker acts as the **group coordinator** for
a group (chosen by hashing `group.id` onto a `__consumer_offsets` partition).
Members send **heartbeats** to it. Two independent clocks decide liveness, and
mixing them up is the most common consumer misdiagnosis:

| Config | Default | Measures | Blown when |
|---|---|---|---|
| `session.timeout.ms` | 45 s | time since last **heartbeat** (sent by a background thread every `heartbeat.interval.ms`, 3 s) | the process died, is network-partitioned, or is in a long stop-the-world GC pause |
| `max.poll.interval.ms` | 5 min | time between **`poll()` calls** on your application thread | your processing of one batch is too slow — the process is alive and heartbeating, but stuck |

A consumer that hangs for 90 seconds *processing* stays in the group (heartbeats
continue) but gets kicked out at 5 minutes. A consumer whose process is frozen for
50 seconds is kicked out at 45. Different symptoms, different fixes: the first is
"reduce `max.poll.records` or speed up processing"; the second is "fix your GC or
your network".

**When membership changes, the group rebalances.** Three generations of protocol,
and knowing which you are running matters:

1. **Eager (the original).** *Everyone* revokes *all* partitions, then the leader
   computes a new assignment and everyone re-joins. A **stop-the-world pause for
   the entire group**, on every single membership change. Rolling-restart 10
   consumers and you trigger up to 20 full pauses.
2. **Cooperative incremental** (KIP-429, Kafka 2.4 — `CooperativeStickyAssignor`).
   Members keep partitions that are not moving; only the reassigned ones are
   revoked, in a second round. Consumers that aren't affected never stop.
3. **The new consumer group protocol** (KIP-848). The *broker* computes assignments
   and reconciles them incrementally with each member — no group-wide barrier and
   no client-side "group leader" at all. It is GA on the broker since Kafka 4.0,
   but as of **Kafka 4.3 the client default `group.protocol` is still `classic`**;
   you opt in with `group.protocol=consumer`, and 4.3 logs a warning telling you
   `classic` is on its way out.

**Static membership** (KIP-345, `group.instance.id`) is the other half of the
answer: a member with a stable instance ID that leaves and returns within
`session.timeout.ms` gets its **same partitions back with no rebalance at all**.
This is what makes rolling restarts of a large consumer fleet cheap.

**🔮 Predict:** *Devi's analytics group has 12 consumers on 12 partitions. One
consumer's host has a 40-second network blip, and the group uses the eager
protocol. How much processing does the group lose?*

<details><summary>Reveal</summary>

Far more than one consumer's share for 40 seconds. At 45s `session.timeout` the
blip is *just* survivable — but if it tips over, the coordinator declares the
member dead and triggers a rebalance in which **all 12 consumers revoke all 12
partitions** and stop. Nobody processes anything until the new assignment
completes — typically seconds, but tens of seconds with large groups, slow
`onPartitionsRevoked` handlers, or a group that must first commit offsets.

Then the blipped consumer comes back and **rejoins**, triggering a *second*
full-group rebalance. One flaky NIC on one host stopped a 12-machine pipeline
twice.

This is the entire motivation for cooperative rebalancing and static membership,
and it is why "why did our lag spike to 4 minutes when nothing was wrong?" is
usually answered by grepping consumer logs for `Revoking previously assigned
partitions`.

</details>

### 10.11 Version 8 — transactions, and what "exactly-once" actually covers

One case remains, and it is the one that made Kafka an application platform
rather than a pipe. Kabir's fraud service is a **consume-process-produce** loop:
read from `parcel-scans`, compute, write to `fraud-alerts`, commit the input
offset. Three actions, and a crash between any two of them is a bug:

```
crash after writing the alert, before committing the offset
  → restart reprocesses the scan → duplicate alert
crash after committing the offset, before writing the alert
  → the scan is never reprocessed → missing alert
```

The idempotent producer (§10.7) cannot help: these are two different resources.
What is needed is **atomicity across a set of partitions**, including the
`__consumer_offsets` partition holding the bookmark. Kafka's answer (0.11, 2017)
is transactions, and the machinery should feel familiar from `two-phase-commit`
and `saga`:

- The producer declares a stable `transactional.id`. On `initTransactions()` a
  **transaction coordinator** (a broker, selected by hashing the id onto the
  internal `__transaction_state` topic) assigns it a PID and **bumps its epoch** —
  which **fences** any older instance with the same id. A zombie that wakes from a
  GC pause and tries to write is rejected with `ProducerFenced`. That is the
  fencing token again, doing exactly what `distributed-locking` said it must.
- Records written during the transaction go into their real partitions
  immediately, marked as part of the transaction. The offset commit is sent to the
  *coordinator* (`sendOffsetsToTransaction`) and becomes part of the same
  transaction.
- `commitTransaction()` runs a two-phase commit: the coordinator writes the
  decision to `__transaction_state`, then writes a **control record** (a commit or
  abort marker) into every partition the transaction touched.
- Consumers with **`isolation.level=read_committed`** never return records from
  open or aborted transactions. They read only up to the **LSO** (last stable
  offset) — the offset of the earliest still-open transaction. Aborted records are
  physically present in the log and filtered out by the client.

Two costs that decide whether you should use it:

1. **Latency.** A `read_committed` consumer cannot advance past an open
   transaction. One hung transactional producer stalls its consumers until
   `transaction.timeout.ms` (default 60 s) aborts it. `read_uncommitted` — **still
   the client default in 4.3** — has no such wait, which means enabling
   transactions on the producer changes nothing for consumers until you *also*
   change the consumer.
2. **Scope.** This is the sentence to keep: **Kafka's exactly-once is
   exactly-once *within Kafka*.** Read-from-Kafka → write-to-Kafka →
   commit-offset is atomic. Read-from-Kafka → **charge a credit card** →
   commit-offset is not, cannot be, and no configuration will make it so. The
   moment an effect leaves Kafka, you are back to `idempotency`'s answer: an
   idempotency key at the sink, or a transactional outbox on the way out.

Kafka Streams sets all of this up for you with one setting
(`processing.guarantee=exactly_once_v2`), which is why most people who use EOS
successfully are using Streams rather than assembling it by hand.

> **Checkpoint.** The machine is complete: append-only logs, partitioned by key,
> replicated with an ISR and a high watermark, fenced by leader epochs, written
> idempotently, read by groups holding their own bookmarks, bounded by retention
> or collapsed by compaction, and optionally transactional inside the cluster.
> Everything from here is what it looks like when it goes wrong.

---

## 11. FAILURE SCENARIOS

Nine failures worth carrying into an on-call rotation. Three of them present as
**silence** — no error, no alert, correct-looking dashboards — and those are the
ones to read twice.

### 11.1 Leader broker dies (the ordinary case)

**What happens.** The controller notices the broker's session is gone, picks a new
leader from the ISR for each partition it led, and propagates new metadata.
Producers and consumers get `NOT_LEADER_OR_FOLLOWER`, refresh metadata, and retry
against the new leader.

**What you see.** A latency spike of roughly seconds on affected partitions;
`UnderReplicatedPartitions` > 0 until the dead broker returns and catches up;
possibly a burst of producer retries.

**What survives.** Everything below the old high watermark — i.e. everything
acknowledged under `acks=all` with `min.insync.replicas ≥ 2`. Records that were
only on the dead leader are discarded by truncation, and no producer was told
they were durable.

**The point:** with correct settings this is a *non-event*, handled by client
retries. Most "Kafka outages" are really this, plus a client configured with
`retries=0` or an unwisely short `delivery.timeout.ms`.

### 11.2 A GC pause ejects a healthy broker

**How.** A 20-second stop-the-world pause on a follower (the exact failure mode you
measured in `jvm-garbage-collectors`) stops its fetches. If the pause exceeds
`replica.lag.time.max.ms` (30 s), the leader drops it from the ISR. If it happens
to the *leader*, its controller session lapses and its partitions fail over — for
a machine that is perfectly healthy 20 seconds later.

**What you see.** `IsrShrinksPerSec` spiking then `IsrExpandsPerSec` recovering —
the flap. If `min.insync.replicas=2` and RF=3, one flap is survivable; two
overlapping flaps cause producers to fail with `NOT_ENOUGH_REPLICAS`.

**What it teaches.** *The failure detector cannot distinguish slow from dead* —
`split-brain`'s central point, arriving in Kafka as ISR flapping. This is why
Kafka brokers use modest heaps and lean on the page cache: fewer heap bytes, fewer
long pauses, fewer false ejections.

### 11.3 ISR shrinks to one and stays there — **silent**

**How.** Two of three replicas are slow (a disk degrading, a saturated NIC, a
noisy neighbour). The ISR quietly becomes `{leader}`. With the default
`min.insync.replicas=1`, producers keep getting successful `acks=all`
acknowledgements that mean nothing (the trace in §10.4).

**Why it is silent.** Throughput is *fine*. Latency *improves* — there is nobody
to wait for. Nothing errors. The only visible signal is
`UnderReplicatedPartitions` and `UnderMinIsrPartitionCount`, which is exactly why
those two are on every Kafka runbook's alert list.

**Guarantee status.** Ordering and offsets intact; **durability gone**. You are
one disk away from losing acknowledged data.

### 11.4 Unclean leader election — **silent, and destructive**

**How.** All ISR members for a partition are down; `unclean.leader.election.enable`
is `true`; a stale replica is promoted at LEO 8,400 while the true log reached
9,000.

**What happens.** 600 committed records vanish. New records reuse offsets
8,400–9,000 with different content. Consumers past 8,400 hold offsets that now
point at different records; consumers behind will read a log with a hole. In a
compacted topic, values silently revert to older ones.

**Detection.** Almost none from the outside. Downstream discovers it as
reconciliation mismatches days later.

### 11.5 Consumer lag grows — the diagnosis tree

**What it is.** `lag = log end offset − committed offset`, per partition. It is
the number Kafka operators watch above all others, because it is the only place
where "the business is behind" becomes visible.

**Read it per partition, never only in aggregate.** The shape tells you the cause:

- **All partitions lag equally** → the consumers are globally too slow. Add
  consumers (up to partition count), or make processing faster.
- **One partition lags and the rest are flat** → a hot key (§10.3's prediction) or
  a poison record wedging one consumer. Adding consumers changes nothing.
- **Lag sawtooths every few minutes** → rebalances. Grep for `Revoking previously
  assigned partitions`.
- **Lag flat and non-zero, throughput normal** → an idle consumer holding a
  partition it never processes, or a stuck offset commit.
- **Lag falls to zero suddenly and permanently** → **not good news.** Either the
  consumer skipped ahead via `auto.offset.reset=latest` (§11.6) or someone reset
  offsets manually. A sudden green is the most dangerous green there is.

**The queueing view.** From `queueing-theory`: consumers are servers with a service
rate; if arrival rate exceeds it, lag grows without bound and *no* amount of
patience fixes it. If arrival is 95% of capacity, lag is finite but the recovery
time from any blip is brutal (`1/(1−ρ)`). Size consumers for the peak plus the
catch-up rate you need, not for the average.

### 11.6 Retention outruns a consumer — **silent, and the data is gone**

**How.** Devi's consumer is down over a long weekend; `retention.ms` is 7 days and
her lag was already large. On restart her committed offset no longer exists;
`auto.offset.reset=latest` (the default) jumps her to the end.

**What you see.** Lag drops to zero. The consumer runs perfectly. Dashboards are
green. Three weeks of model training later, someone notices a hole.

**Prevention.** `auto.offset.reset=none` on anything that matters (fail loudly),
alert on *lag as a fraction of retention* rather than lag alone, and remember the
Kafka-specific version of the rule: **retention is your recovery-time budget.** If
you cannot repair a broken consumer inside `retention.ms`, retention is too short.

### 11.7 The poison record

**How.** One record cannot be deserialised or processed — a schema change, a
corrupt payload, a null someone swore was impossible. The consumer throws,
restarts, reads the same offset, throws again. Because there is no redelivery and
no dead-letter concept in the broker, **the partition stops advancing forever**
while its 11 siblings run fine.

**What you see.** Exactly the "one partition lags, the rest are flat" signature,
with a CrashLoopBackOff.

**The fix is architectural, and it is on you.** Kafka has no dead-letter queue.
You build one: catch, produce the offending record to `parcel-scans.DLQ` with its
error and original coordinates, commit the offset, move on. Kafka Connect and
Spring Kafka both ship this pattern; the broker does not, because a broker that
knew which records failed would be back to per-message state (§5.4).

### 11.8 Rebalance storm

**How.** Processing gets slower than `max.poll.interval.ms` (e.g. a downstream API
degrades). A consumer is evicted mid-batch; its partitions move to already-busy
peers; those get slower and are evicted in turn. With the eager protocol every
eviction stops the whole group, so throughput collapses to near zero while the
group spends its life rebalancing.

**What survives.** No data is lost (offsets were committed or not), but you will
see **duplicates** — every eviction reprocesses whatever was uncommitted.

**Fixes**, in order of effect: reduce `max.poll.records` so each batch is
completable; raise `max.poll.interval.ms` to a real bound; use
`CooperativeStickyAssignor` or `group.protocol=consumer`; use `group.instance.id`
for planned restarts; move slow I/O off the poll thread (with the pause/resume
API, not by heartbeating a lie).

### 11.9 Zombie producer after a pause

**How.** A transactional producer freezes (GC, container throttling). Its
orchestrator starts a replacement with the same `transactional.id`. The original
wakes and tries to write.

**What happens.** The coordinator bumped the producer epoch when the replacement
called `initTransactions()`. The zombie's writes are rejected with
`ProducerFenced`, and its in-flight transaction is aborted. It is the fencing
token from `distributed-locking`, working exactly as designed — and it is the one
protection that **requires** transactions: a plain idempotent producer that
restarts gets a *new* PID and is not fenced at all.

---

## 12. CORRECTNESS AND GUARANTEES

The precise version. Learn to state these as carefully as they are written,
because most Kafka arguments are two people using one word for different things.

### 12.1 What Kafka guarantees

1. **Per-partition order of writes.** Records appended to a partition are read by
   every consumer in the same order, with the same offsets, forever. *(Assumes no
   unclean leader election.)*
2. **Per-key order, given a stable partition count and a keyed producer.** All
   records with key `k` land in one partition, so they are ordered relative to
   each other.
3. **Per-producer order within a partition**, even under retries, when
   `enable.idempotence=true` (default since 3.0) with `max.in.flight ≤ 5`.
4. **Durability of committed records**: a record acknowledged under `acks=all`
   with `min.insync.replicas=m` survives up to `m−1` simultaneous broker failures,
   *provided* unclean leader election is off.
5. **At-least-once delivery to a consumer**, by default and in practice.
6. **Atomicity across partitions within one Kafka cluster** for transactional
   writes plus offset commits, observed by `read_committed` consumers.
7. **Immutability**: a record at `(topic, partition, offset)` never changes. It
   may be deleted wholesale by retention or compaction; it is never edited.

### 12.2 What Kafka does not guarantee

1. **Any order across partitions.** No global order, no cross-partition
   timestamps, ever.
2. **Order across a partition-count change** for a given key (§10.3).
3. **Exactly-once delivery.** Delivery is at-least-once; *processing* can be
   exactly-once, only inside Kafka, only with transactions.
4. **Deduplication of application-level retries.** Two `send()` calls with the same
   payload are two records; nothing looks at content.
5. **That an acknowledged record is on disk.** Kafka acknowledges when replicas
   have the record **in their page cache**, not after `fsync` —
   `flush.messages`/`flush.ms` default to effectively never. Durability comes from
   *replication*, not from syncing. A simultaneous power loss across all replicas
   can lose acknowledged records; a normal process crash cannot (the page cache
   survives it). Deliberate trade — and the thing to check before writing
   "durable" in a design doc. *(Contrast `acid`'s WAL, where durability meant
   exactly `fsync`.)*
6. **Delivery to a specific consumer.** There is no addressing. Consumers pull.
7. **Time ordering.** `timestamp` is producer-supplied by default
   (`message.timestamp.type=CreateTime`), so offsets and timestamps can disagree.
   Set `LogAppendTime` if you need broker-stamped times, at the cost of losing the
   original event time.

### 12.3 The assumptions underneath

- **Non-Byzantine.** Brokers do not lie. A corrupt disk that returns wrong bytes
  is outside the model (CRCs catch some of it, not all).
- **The failure detector is a timeout**, so slow is indistinguishable from dead
  (§11.2). `FLP` is the reason this cannot be fixed, only tuned.
- **Clocks matter only for retention and timestamps**, never for correctness of
  ordering — Kafka orders by append, not by time. This is why Kafka needs nothing
  like Spanner's TrueTime.
- **The controller quorum is a real consensus group.** In KRaft mode, cluster
  metadata is itself a replicated log kept by a Raft quorum of controllers — the
  `raft` lab, running inside Kafka.

### 12.4 The central tradeoff, stated once

Kafka's ISR is a **dynamic quorum**, unlike the fixed majority of
`quorums-and-replication`. With RF=3 and majority quorums you need 2 acks always;
with the ISR you need "all of whoever is currently keeping up", which is 3 acks
when healthy and 1 when degraded. The benefit is that a slow replica is *removed*
rather than tolerated, so latency tracks the healthy replicas instead of the
median one. The cost is that the size of your durability guarantee is a runtime
variable — which is precisely why `min.insync.replicas` exists: it is the floor
you place under a quorum that would otherwise shrink to nothing.

That one design decision explains most of Kafka's character: **it prefers to keep
running with fewer copies and make you declare the minimum**, rather than to stop
whenever the ideal is unavailable.

---

## 13. PRODUCTION — from this lab's model to a real cluster

### 13.1 What a cluster is made of

```
        ┌──────────────── KRaft controller quorum (3 or 5) ──────────────┐
        │  a Raft-replicated metadata log: topics, partitions, ISRs,     │
        │  leaders, configs, ACLs — the cluster's own source of truth    │
        └──────────────────────────┬─────────────────────────────────────┘
                                   │ metadata
     ┌───────────┐         ┌───────┴───┐         ┌───────────┐
     │ broker 1  │         │ broker 2  │         │ broker 3  │
     │ p0 leader │         │ p1 leader │         │ p2 leader │
     │ p1,p2 fol │         │ p0,p2 fol │         │ p0,p1 fol │
     └───────────┘         └───────────┘         └───────────┘
```

**ZooKeeper is gone.** For a decade Kafka kept cluster metadata in ZooKeeper
(whose protocol you learned in the `zab` lab). **KRaft** replaced it: the
controllers form a Raft quorum and metadata becomes just another Kafka log that
brokers *consume*. KRaft went production-ready in 3.3 and **ZooKeeper support was
removed entirely in Kafka 4.0** (March 2025). The wins are fewer moving parts,
much faster failover with large partition counts (brokers cache metadata locally
and apply deltas instead of re-reading ZooKeeper), and one security model. If you
read a pre-2024 Kafka guide, mentally delete every ZooKeeper paragraph.

### 13.2 What the lab left out

| Simplification here | Production reality |
|---|---|
| "the leader writes to its log" | records land in the page cache; `fsync` is rare and deliberate (§12.2.5) |
| "a consumer reads from the leader" | since KIP-392 a consumer may fetch from the **closest replica** (`client.rack`) to avoid cross-AZ transfer cost — a large real-money saving |
| "records are deleted after retention" | with **tiered storage** (KIP-405, shipped 3.6) old segments move to S3/GCS/Azure and stay readable; local disk holds hours, remote holds months |
| "a producer sends a record" | it batches by `linger.ms` (**default changed 0 → 5 ms in Kafka 4.0**) and `batch.size`, compresses, and pipelines up to 5 batches per connection |
| "a broker serves the request" | network threads + request handler threads + purgatory for delayed requests; `RequestHandlerAvgIdlePercent` is the saturation metric |
| "clients connect to the cluster" | `advertised.listeners`, multiple listeners per security protocol, and the #1 setup failure: clients bootstrap fine then fail because advertised hostnames are unreachable |
| no security | SASL/mTLS authentication, ACLs per topic/group, encryption in transit (which costs you `sendfile`) |
| no multi-tenancy | client/user **quotas** on produce/fetch/request rate — the load-shedding you would otherwise build by hand |
| one cluster | MirrorMaker 2 / Cluster Linking for geo-replication; **offsets are not identical across clusters**, which is why cross-cluster failover of consumers is genuinely hard |

### 13.3 Sizing: partitions, the one decision you cannot take back cheaply

Partition count sets your maximum consumer parallelism (§10.10) and is painful to
change (§10.3). The working method:

1. Measure or estimate **per-consumer throughput** `t` (records/s that one
   consumer instance can process, including its slowest downstream call).
2. Take **peak arrival rate** `λ`, add the catch-up headroom you want (2× is
   typical — recall `queueing-theory`: with no spare capacity, recovery time is
   unbounded).
3. `partitions ≥ 2λ / t`, then round up for future growth.
4. Sanity-check the other direction: partitions are not free. Each is files, memory
   and a replication stream on some broker; each adds controller metadata and
   failover work. Modern KRaft clusters handle hundreds of thousands
   cluster-wide, but "one partition per customer" designs still die of it.

Deodar: λ = 4,000/s peak, one consumer handles ~350/s → `2×4000/350 ≈ 23` → 24 or
32 partitions. Not 3, and not 1,000.

### 13.4 The metrics that matter

If you watch four things, watch these:

1. **`UnderMinIsrPartitionCount`** — partitions currently unable to satisfy
   `min.insync.replicas`; producers to them are failing or about to. Page on it.
2. **Consumer lag per group per partition** — the business-level "how far behind
   are we", and the only one a product owner can read. Alert on *trend* and on
   *lag ÷ retention*, not on an absolute number.
3. **`UnderReplicatedPartitions` + `IsrShrinksPerSec`** — durability erosion and
   flapping, the §11.3 silent failure made visible.
4. **`RequestHandlerAvgIdlePercent`** — broker saturation; below ~30% you are on
   `queueing-theory`'s cliff and every latency percentile is about to move
   together.

Then: `ActiveControllerCount` (must be exactly 1 cluster-wide), produce/fetch
latency percentiles, disk free vs retention, and `OfflinePartitionsCount` (should
be 0 — anything else means a partition has no leader).

### 13.5 Configs worth knowing by heart

| Config | Default (4.3) | Why you touch it |
|---|---|---|
| `acks` | `all` | durability; `all` needs `min.insync.replicas` to mean anything |
| `enable.idempotence` | `true` | dedup + ordering under retries; leave it on |
| `min.insync.replicas` | **`1`** | the one durability default that is wrong for production — set 2 with RF=3 |
| `unclean.leader.election.enable` | `false` | keep false unless a gap is cheaper than downtime |
| `retention.ms` | 7 days | your recovery-time budget, not just a disk knob |
| `linger.ms` | `5` (was 0 before 4.0) | throughput vs latency, per producer |
| `max.poll.records` | `500` | the main lever against `max.poll.interval` evictions |
| `max.poll.interval.ms` | 5 min | how long one batch may take before you are declared stuck |
| `session.timeout.ms` | 45 s | how long a *silent* consumer may be gone |
| `auto.offset.reset` | `latest` | silent skipping vs loud failure; `none` for critical consumers |
| `enable.auto.commit` | `true` | turn off wherever correctness matters |
| `isolation.level` | `read_uncommitted` | must be `read_committed` for EOS to mean anything on the read side |
| `group.protocol` | `classic` | `consumer` opts into KIP-848; classic is deprecated-in-waiting |
| `compression.type` | `none` | `lz4`/`zstd` typically 3–5× less network and disk for JSON |

### 13.6 The ecosystem, in one line each

- **Kafka Connect** — a cluster that runs source/sink connectors so you do not
  write another "poll DB, push to Kafka" service; where **Debezium**
  (`change-data-capture`) lives.
- **Kafka Streams** — a Java library (not a cluster) for stateful stream
  processing; its state stores are local (RocksDB) and backed by compacted Kafka
  topics, and it is the easiest correct way to get exactly-once.
- **Schema Registry** — versioned schemas plus compatibility rules; the answer to
  "what stops team five from breaking every consumer with a field rename". Not
  part of Apache Kafka.
- **Share groups (KIP-932)** — queue-style, per-record acknowledgement *inside*
  Kafka, so several consumers can share one partition and individual records can
  be re-delivered. Early access in 4.0, preview in 4.1, and reported
  production-ready in 4.2. It is Kafka growing a second, queue-shaped consumption
  model alongside the log-shaped one — worth watching, because it removes the
  main reason to run RabbitMQ alongside Kafka.

### 13.7 When Kafka is the wrong answer

Say this out loud in design reviews; it is the fastest way to be trusted with the
choice:

- **A task queue with per-message retry, priorities, delays or per-message
  acking** — that is RabbitMQ/SQS/Sidekiq shaped, and until share groups mature it
  is not Kafka shaped. Kafka's unit is the partition, not the message.
- **Request/response** — Kafka has no correlation, no addressing, no timeout
  semantics. Use RPC (`communication-protocols`).
- **Fewer than a handful of events per second, one producer, one consumer** —
  you are paying a cluster's operational cost for a database table and a poller.
- **Random access by key** — Kafka reads by offset or timestamp, never by key
  ("give me parcel P-1001's current state" is a *table* question; that is what a
  compacted topic materialised into a database, or Kafka Streams' state store, is
  for).
- **Very large payloads** — the practical message limit is ~1 MB
  (`message.max.bytes`); put the blob in S3 and the pointer in Kafka.
- **Strict global ordering across everything** — needs one partition, which caps
  you at one consumer and one machine. If you truly need it, you need a different
  design, not a bigger Kafka.

---

## 14. COMMON WRONG MODELS

### 14.1 "Kafka is a message queue"

**Why it feels right:** it sits between producers and consumers, and half the
documentation on the internet calls it one.

**Smallest counterexample:** two consumer *groups* on one topic. Both receive
every record. A queue that gives every message to every reader is not a queue.

**Correct model:** Kafka is a **replicated, partitioned, append-only log with
consumer-held positions**. Consumption is a *read*, not a *take*. Everything else
— replay, multiple audiences, no redelivery, no dead-letter queue, no priorities —
follows from that single difference.

### 14.2 "Kafka guarantees ordering"

**Why it feels right:** it *is* a log, and logs are ordered.

**Smallest counterexample:** topic with 2 partitions, no key. Produce A then B.
They land in different partitions; two consumers read them in either order.

**Correct model:** ordering is **per partition**, therefore **per key**, and only
while the partition count is unchanged. If someone says "we need ordering", the
only useful reply is *"ordering of what, relative to what?"*

### 14.3 "acks=all means all replicas have it"

**Smallest counterexample:** §10.4's trace — ISR shrunk to `{leader}`, `acks=all`
succeeds with one copy.

**Correct model:** `acks=all` means *all currently in-sync replicas*, a set that
shrinks under failure. `min.insync.replicas` is what puts a floor under it. Three
settings or none.

### 14.4 "Exactly-once means my side effects happen once"

**Why it feels right:** the feature is genuinely called exactly-once semantics.

**Smallest counterexample:** consume a scan, `POST /charge`, then crash before
committing the offset. On restart the scan is reprocessed and the card is charged
twice. No Kafka setting prevents this, because the charge is outside the
transaction.

**Correct model:** EOS is atomicity of *Kafka reads, Kafka writes and offset
commits* within one cluster. Every effect outside Kafka needs `idempotency`'s
answer: an idempotency key at the sink.

### 14.5 "More partitions means more throughput"

**Why it feels right:** partitions are the parallelism unit, so more must be
faster.

**Counterexample:** a topic with 5,000 partitions on a 3-broker cluster.
Producer batches fragment across many partitions (smaller batches, worse
compression, more requests), each partition costs files and memory on a broker,
end-to-end latency rises, and failover has vastly more work to do. Meanwhile
consumer throughput is unchanged if your consumers are the bottleneck.

**Correct model:** partitions set a *ceiling* on consumer parallelism. Throughput
is set by the actual bottleneck — usually consumer processing or broker disk.
Size with §13.3, then stop.

### 14.6 "Adding consumers to the group makes it faster"

**Counterexample:** 12 partitions, 12 consumers, one hot partition. Add 12 more
consumers: all 12 sit idle (no partitions to assign), and the hot partition is
exactly as slow as before.

**Correct model:** parallelism = min(partitions, consumers), and it is bounded per
partition by *one* consumer. Skew is a key-design problem, not a scaling problem.

### 14.7 "Rebalancing moves data"

**Why it feels right:** you learned rebalancing in
`partitioning-and-rebalancing`, where it moves terabytes.

**Correct model:** a Kafka **consumer group rebalance** reassigns *which consumer
reads which partition* — zero bytes move, and it takes milliseconds to seconds.
The operation that actually moves data is **partition reassignment** (moving a
replica between brokers, done with `kafka-reassign-partitions` or Cruise
Control), which is the partitioning lab's rebalance, throttled and slow. Two
different operations, and only one of them is what your consumer logs mean.

### 14.8 "Compaction removes duplicates"

**Counterexample:** a compacted topic where you write key `k` twice per second.
Read it back immediately: you see both values. The cleaner runs on closed segments
past a dirty-ratio threshold, and never on the active segment.

**Correct model:** compaction **eventually** keeps at least the latest value per
key. It is a storage-bounding policy, not a uniqueness constraint, and a consumer
must be idempotent regardless.

### 14.9 "Kafka is slow because it writes to disk" (or: "Kafka is fast because it's in memory")

Both wrong, symmetrically. Kafka writes every record to disk and is fast anyway,
because it only ever writes **sequentially**, serves reads from the **page cache**
via `sendfile`, and moves **batches** rather than records. Nothing is held in the
JVM heap for caching. The speed comes from the access pattern, not from skipping
the disk — which is precisely `btree-and-lsm`'s central result, applied to a
network service.

### 14.10 "If we lose data we can just increase the replication factor"

**Counterexample:** the loss came from `auto.offset.reset=latest` after retention
expiry (§11.6), or from a consumer bug, or from unclean leader election. RF=5
prevents none of them.

**Correct model:** replication protects against **broker/disk failure** only.
Retention protects against slow recovery. Idempotent consumers protect against
duplicates. Schema compatibility protects against poison records. They are four
different defences against four different failures, and people reach for the
first one because it is a single number in a config file.

---

## 15. KNOWLEDGE MAP AND NEXT STEPS

**Prerequisites you used:** `btree-and-lsm` (sequential I/O, segments, the log as
a storage engine) · `partitioning-and-rebalancing` (partition as unit, hot keys,
`hash % n`) · `quorums-and-replication` (leader/follower, stale replicas, quorum
arithmetic) · `split-brain` (epoch = fencing token; slow vs dead) ·
`idempotency` (at-least-once + dedup = effectively once) ·
`transactional-outbox` (the dual-write problem Kafka is usually the destination
of) · `queueing-theory` (why lag and utilisation behave as they do) ·
`jvm-garbage-collectors` (why brokers keep small heaps).

**Concepts this unlocks:** `event-sourcing` and `cqrs` gain their usual transport;
`change-data-capture` gains its usual sink (Debezium writes here); `saga`
choreography is normally Kafka topics; `microservices` event-driven integration is
this lab.

**Commonly confused with:**

- **RabbitMQ / SQS** — broker-tracked per-message state, redelivery, per-message
  ack, priorities, no replay. Kafka has none of those and offers replay, fan-out
  and per-partition order instead.
- **Pulsar** — same log-based model, but storage is separated from serving
  (BookKeeper), which makes scaling and rebalancing cheaper at the cost of another
  system to run.
- **Kinesis** — Kafka's model as a managed service: shards ≈ partitions, sequence
  numbers ≈ offsets, 24h–365d retention.
- **A database** — Kafka has no random access by key, no queries, no indexes
  beyond offset/timestamp. A compacted topic is *table-shaped*, not a table.
- **`partitioning-and-rebalancing`'s rebalance** — see §14.7.

**Ready to type:**

- **`/learn kafka-streams`** — the stateful half: KTable vs KStream, local state
  stores backed by compacted topics, windowing, and why exactly-once is easy there
  and hard by hand.
- **`/learn schema-evolution`** — Avro/Protobuf, compatibility modes, and the
  organisational problem Schema Registry solves; the missing defence in §14.10.
- **`/learn jepsen`** — asked for by nine labs and still unopened. You now hold a
  dozen consistency claims, including Kafka's; Jepsen is how such claims get
  falsified.
- **`/learn load-shedding`** — quotas, backpressure, and the cliff
  `queueing-theory` proved exists.
- **`/learn observability`** — four labs assume it; §13.4 is the Kafka-shaped
  slice of it.

---

## 16. GLOSSARY

In order of first appearance.

- **Log** — an append-only, ordered, immutable sequence of records. Kafka's only data structure.
- **Record** — `(key, value, timestamp, headers, offset)`; immutable once written.
- **Topic** — a named collection of partitions. A name, not a storage unit.
- **Partition** — one physical append-only log; the unit of ordering, parallelism, replication and assignment.
- **Offset** — a record's permanent integer position within its partition. Meaningless across partitions.
- **Key** — the record field that selects the partition (`murmur2(key) % partitions`), and therefore the unit of ordering. Not an identifier.
- **Sticky partitioning** — the null-key strategy of filling one partition's batch before switching, for better batching (KIP-480).
- **Producer / consumer** — client libraries in your application, not servers.
- **Broker** — a server holding partitions and serving fetch/produce requests.
- **Retention** — age- or size-based deletion of whole segments, independent of who has read them.
- **Segment** — a file holding a contiguous offset range; the unit of deletion. One per partition is "active" (being appended to) and never compacted.
- **Replica / leader / follower** — copies of a partition; all reads and writes go to the leader; followers replicate by fetching, exactly like consumers.
- **LEO (log end offset)** — the next offset a replica will write; how far it has got.
- **High watermark (HW)** — the highest offset held by *all* in-sync replicas; the boundary of what consumers may read. Kafka's commit point.
- **Committed (record)** — replicated to all in-sync replicas, i.e. below the HW. Distinct from a *committed offset* (a consumer's bookmark) — one word, two meanings, constantly confused.
- **ISR (in-sync replica set)** — replicas that have fetched within `replica.lag.time.max.ms` (30 s). A *dynamic* quorum: it shrinks under failure, which is why `min.insync.replicas` must floor it.
- **`acks`** — how much replication a producer waits for: `0` / `1` / `all` (all *in-sync*, not all).
- **`min.insync.replicas`** — the minimum ISR size for `acks=all` to succeed; below it, produce fails with `NOT_ENOUGH_REPLICAS`. Default `1`, which is wrong for production.
- **Truncation** — a replica discarding records above the new leader's log on failover; correct because those records were never committed.
- **Leader epoch** — a monotonic generation number per partition leadership; stamped on records, used via `OffsetsForLeaderEpoch` to truncate exactly. Kafka's name for the fencing token (KIP-101, fixing real divergence).
- **Log divergence** — two replicas permanently disagreeing about the content of one offset; the pre-KIP-101 bug, worse than loss.
- **Unclean leader election** — promoting an out-of-sync replica when the ISR is empty: availability bought with silent, unrecoverable data loss. Default off.
- **Idempotent producer** — `(PID, epoch, sequence)` per partition lets the broker drop retried batches and reject out-of-order ones. Default on since 3.0; per-session only.
- **PID (producer ID)** — broker-assigned id for a producer session; a new session gets a new PID, which is why idempotence does not survive a restart.
- **Committed offset** — the consumer's bookmark, stored as a record in the compacted `__consumer_offsets` topic.
- **At-most-once / at-least-once** — commit before processing (loses) vs after (duplicates). Kafka is at-least-once by default and by preference.
- **Effectively-once** — at-least-once delivery + an idempotent consumer. The honest name for what most systems achieve.
- **`auto.offset.reset`** — what to do with no valid committed offset: `latest` (skip, default), `earliest` (reprocess), `none` (fail loudly).
- **Consumer group** — consumers sharing a `group.id`; each partition is assigned to exactly one member. Groups are independent readers of the same log.
- **Group coordinator** — the broker managing one group's membership and offsets.
- **Rebalance (consumer group)** — reassigning partitions to members. Moves **no data**. Eager (stop-the-world) → cooperative incremental (KIP-429) → broker-driven (KIP-848).
- **Static membership** — `group.instance.id`, letting a member restart within `session.timeout.ms` without triggering any rebalance (KIP-345).
- **`session.timeout.ms` vs `max.poll.interval.ms`** — two independent liveness clocks: "are you alive?" (heartbeat thread) vs "are you still processing one batch?" (your thread).
- **Consumer lag** — `log end offset − committed offset`, per partition. Read per partition; the shape names the cause.
- **Log compaction** — retaining at least the latest value per key; makes a topic table-shaped. Eventual, segment-based, never touches the active segment.
- **Tombstone** — a record with a `null` value, meaning "this key is deleted"; retained for `delete.retention.ms` (24 h) so readers can observe the deletion.
- **Log–table duality** — a stream of keyed changes and a table of current values are the same information in two shapes; compaction is the conversion.
- **Transaction / transaction coordinator** — atomic multi-partition writes plus offset commits within one cluster, decided by a coordinator and marked with control records.
- **`transactional.id`** — a stable producer identity whose epoch bump fences zombies (`ProducerFenced`).
- **Control record** — an internal commit/abort marker written into each participating partition; filtered out by clients but consuming an offset (which is why offsets can appear to skip).
- **LSO (last stable offset)** — the read limit for `read_committed` consumers: the earliest still-open transaction. An open transaction stalls them.
- **`isolation.level`** — `read_uncommitted` (default) sees aborted/in-flight transactional records; `read_committed` does not.
- **Exactly-once semantics (EOS)** — atomic consume-process-produce **inside Kafka**. Never covers external side effects.
- **Poison record** — a record that always fails processing, wedging its partition forever; needs an application-level dead-letter topic, since Kafka has none.
- **Rebalance storm** — the slow-processing → eviction → slower-peers → eviction feedback loop.
- **KRaft** — Kafka's built-in Raft metadata quorum; replaced ZooKeeper (production-ready 3.3, ZooKeeper removed in 4.0).
- **Controller** — the elected quorum leader owning cluster metadata: leader election, ISR changes, topic creation. `ActiveControllerCount` must be exactly 1.
- **Tiered storage** — offloading closed segments to object storage while keeping them readable (KIP-405, shipped 3.6).
- **Follower fetching** — consumers reading from the nearest replica via `client.rack` to avoid cross-AZ cost (KIP-392).
- **Quota** — per-client/user produce, fetch and request-rate limits; Kafka's built-in load shedding.
- **Share group** — KIP-932's queue-style consumption with per-record acknowledgement, allowing multiple consumers per partition. Early access 4.0 → preview 4.1 → production-ready 4.2.
- **Purgatory** — the broker structure holding delayed requests (a fetch waiting for `fetch.min.bytes`, a produce waiting for ISR acks).
- **`UnderMinIsrPartitionCount` / `UnderReplicatedPartitions`** — the two metrics that make §11.3's silent durability loss visible.
