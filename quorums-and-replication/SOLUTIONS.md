# Solutions — quorums and replication

Attempt [EXERCISES.md](EXERCISES.md) first. Rungs 7–8 are worked discussions.

---

## Rung 1 — Explain

**1.1** With one copy, whatever it says is by definition the truth — there is
nothing to disagree with. With three copies, a change may reach one and not the
others, and then three machines hold three different answers, all of them
delivered confidently and quickly. You have traded "the data can be destroyed"
for "the data can be wrong in a way nothing detects".

**1.2** For `N = 5`: the read set has R members, the write set has W members,
both drawn from the same 5. They can be completely disjoint only if `R + W ≤ 5`,
because that is exactly when R + W distinct machines fit. So `R = 3, W = 3` gives
6 > 5 and they must share one. `R = 2, W = 3` gives 5, and 5 distinct machines
fit inside 5 exactly — the reader can ask precisely the two the writer did not
reach.

**1.3** A consensus quorum makes the nodes **agree on one value and one order**,
so every later reader sees the same history. A read/write quorum only makes the
read set and the write set **intersect**, so a reader sees the last successful
write's value — with no ordering between concurrent writes and no agreement
between two readers about what happened.

**1.4** *"What is `min.insync.replicas` set to?"* Because `acks=all` means "all
replicas currently in sync", and when failures shrink that set to just the
leader, `acks=all` becomes `acks=1` with no error and no change in behaviour.
The measured evidence is in `java src/Experiments.java leader`: fully
synchronous mode with both followers down refuses **zero** writes.

**1.5** The write was sent to three machines. Two of them replied "got it" and
one did not reply in time, so we needed three and only counted two — but the
third one may well have stored it anyway, and there is no way to reach back and
undo that. So an error means "we could not confirm enough copies", not "nothing
happened". That is why re-sending has to be harmless.

---

## Rung 2 — Identify

**2.1** The violet replica is in both the write set and the read set. It claims
exactly one thing: *this replica acknowledged the last successful write and also
answered this read, so the value the read returned is at least as new as that
write.* It claims nothing about ordering between concurrent writes.

**2.2** At the step where the acknowledgement count reaches W. The remaining
replicas keep being written to afterwards, in the background. It matters because
it means the count W is about *confirmation*, not about *how many copies exist* —
which is the same gap that makes a failed write still land (failure F3).

**2.3** The verdict is right and the arithmetic is irrelevant. `R + W > N` is a
statement about two sets being forced to intersect, and it only means anything
while both sets are drawn from the same N replicas. A sloppy quorum takes its
acknowledgements from outside that set, so the read set has nothing to intersect
with. Measured: 300/300 writes accepted, 200/300 visible afterwards.

**2.4** A replica in the first loop but not the second **received and stored the
write, but did not reply in time to be counted**. That is failure F3, and it is
why "write failed" never means "write did not happen".

**2.5** On `CONCURRENT` the incoming value is added *alongside* the existing one,
so both survive as siblings. If that line set `dominated = true` instead, the
incoming value would be dropped whenever it could not be proven newer — which
means every concurrent write would be silently discarded in favour of whichever
arrived first. You would have built last-write-wins with an even worse rule
(first-write-wins, per replica, so different replicas could keep different
values).

**2.6** `if (responders.size() >= R) break;` enforces **R**. Remove it and every
read waits for every replica, which makes reads strictly fresher and
dramatically slower and less available — you would have turned every read into
`R = N`. Experiment 10 measures the cost: 27% of requests slow instead of 2.85%.

---

## Rung 3 — Predict

**3.1** Zero stale reads at exactly the six combinations where `R + W > N`:
(1,3), (2,3), (3,3), (2,2), (3,2), (3,1). Write success at `W=3` with 20%
independent failure is `0.8³ ≈ 51.2%` — and the measured figure is **51.2%**.

**3.2** 500 of 500 — 100%.

**3.3** Last-write-wins loses **1000**; version vectors lose **0** and surface
1000 conflicts instead.

**3.4** 200 of 300 with hinted handoff off; 300 of 300 once handoff has run.

**3.5** **Zero.** That is the trap: "wait for all live followers" is vacuous when
none are live.

**3.6** 27.31% (waiting for 3) against 2.85% (waiting for 2) — nearly a
tenfold increase in slow requests, from replicas that are individually healthy
90% of the time.

---

## Rung 4 — Modify

**4.1** Every configuration reports stale reads, including the ones where that
is impossible. The cause is not the quorum logic: with an empty context, every
write produces the identical version vector `{cli:1}`, so `compare` returns
`EQUAL`, and `Node.write` treats each new write as one it already has and
**ignores it**. The replicas keep the first value for ever.

The lesson is not about measurement. In a version-vector store, a client that
writes without carrying forward the version it read is not writing "the newest
value" — it is writing something the store cannot tell apart from a stale
retry. **Read-before-write is how a write acquires its place in the order.**
This is neither safety nor liveness in the usual sense: the system did exactly
what it was told, and what it was told was meaningless.

**4.2** No — and this is worth measuring rather than reasoning about. Running
`overlap` with read repair on and off:

```
readRepair=false   R1W1 stale=784   R2W1 stale=119
readRepair=true    R1W1 stale=784   R2W1 stale=118
```

Essentially unchanged. Read repair fixes the replica **after** the stale value
has already been returned to the client. It improves the *next* read of that key
by that path; it cannot un-answer this one. What it has changed is the
long-run convergence of the replicas; what it has not changed is the guarantee,
because there was never a guarantee to change. Anything that makes staleness
*less likely* without making it *impossible* is a probability, not a property.

**4.3** The stale counts for `R + W ≤ N` fall, sometimes to zero on a healthy
cluster, because gathering from more replicas makes it more likely you catch the
fresh one. **The guarantee does not get stronger — only the luck.** The read
still *returns* after R replies in the general case, and under partition or
failure the extra replicas are exactly the ones you cannot reach. This is the
most dangerous kind of change: it makes the bug disappear in testing and keeps
it in production.

**4.4** The `refused 2 down` column goes to 100 for every mode, including ASYNC.
You have made it impossible to write unless all three replicas are in sync — you
have chosen `W = N`, with the availability that implies (measured at 51.2% under
20% independent failure). Safety maximal, liveness minimal.

**4.5** With "rotate", the stale read appears only when the rotation happens to
point at a replica that missed the write, so re-running gives different results.
Pinned, it happens every single time.

That is exactly why staleness bugs are so hard to reproduce: in production the
client library spreads requests across replicas, so the same user action is
correct on most attempts and wrong on some. The bug report says "it sometimes
shows old data", it never reproduces on demand, and the natural response —
"cannot reproduce" — is correct and useless. The way to reproduce it is to
**pin the routing**, which is the debugging trick worth remembering.

---

## Rung 5 — Implement

**5.1 · `LOCAL_QUORUM`.** Label nodes with a datacentre, and count
acknowledgements only from nodes in the coordinator's datacentre against a
local W. The test that shows what you gave up is the important one: write with
`LOCAL_QUORUM` in dc1 while dc2 is unreachable, heal, then read with
`LOCAL_QUORUM` in dc2 — and observe a stale read. **You have not weakened the
guarantee, you have narrowed its scope**: it now holds within a datacentre and
not across them. That is usually the right trade, and it must be a stated one,
because a user who fails over between regions will notice.

**5.2 · Bounded staleness.** It buys a *time* (or version) bound where R buys a
*set* bound. R says "I asked enough replicas that one must be current"; bounded
staleness says "whatever I got is at most K behind". They are useful together:
R protects the guarantee, the bound protects you when the guarantee has been
suspended (sloppy quorum, reset replica). The new failure it introduces is
**availability**: a replica that is behind now refuses to answer, so during
recovery — precisely when replicas are behind — reads start failing. You have
converted a silent-wrongness failure into a loud-unavailability one, which is
usually right and is never free.

**5.3 · Compare-and-set: the instructive failure.**

Both succeed. Measured with the lab's own cluster:

```
CAS: both clients read the same version=true
     bothWritesReportedOk=true
     valuesNowStored=[A-wins, B-wins]
```

Ana and Ben both read version `{init:1}`, both find their expected version
matches on the replicas they reach, and both write. Each write is applied. The
result is two siblings — which is honest, and is *not* a compare-and-set: the
whole point of CAS is that exactly one attempt succeeds.

**Why the quorum cannot fix this.** The overlap rule guarantees a reader sees a
writer. It does not create a moment at which the two clients' attempts are
ordered relative to each other, because no node ever decides anything and no
node has authority. Both clients observed a true fact ("the version is
`{init:1}`") and both acted on it, and the fact stopped being true between the
observation and the action. That gap cannot be closed by asking more replicas.

**What you would have to add:** an agreement round — a majority of replicas
agreeing on which attempt goes first, before either is applied. That is
consensus, and it is exactly the [paxos](../paxos/) machinery. Cassandra offers
it as *lightweight transactions* (`IF NOT EXISTS`, `IF version = ?`), which run
a Paxos round per operation and cost roughly an order of magnitude more than a
normal write. DynamoDB's conditional writes get it from a single-leader
partition instead.

The takeaway is the shape of the whole topic: **overlap is cheap and gives you
visibility; agreement is expensive and gives you exclusivity.** Pay for the
second only where you genuinely need "exactly one".

---

## Rung 6 — Debug

### `bug=no-overlap-check` — 4 checks fail

Reads answer from the first replica that replies, ignoring R. Violates
**invariant 2** (a read is answered only when R owning replicas have replied).

**Symptom for a user:** intermittently old data — worse right after a replica
has been down, better later, never reproducible. Also note it breaks two *read
repair* checks, because a read that touches one replica cannot repair the others:
disabling the read quorum silently disables the repair mechanism that was
compensating for it.

**Monitoring:** would not catch it. Latency improves, error rates improve, and
every read succeeds. This bug looks like a performance win on every dashboard
you have.

### `bug=lww-always` — 2 checks fail

Resolves by timestamp even when the version vectors say `CONCURRENT`. Violates
**invariants 3 and 4** (nothing discarded unless provably older; concurrent
values preserved).

**Symptom:** customers lose data occasionally and cannot explain it — an item
disappears from a cart, an edit reverts. Support cannot reproduce it because it
requires two writes in the same instant.

**Monitoring:** only if you were already tracking sibling counts, and the bug's
effect is that sibling counts go to zero — which reads as "no conflicts,
everything is fine". A metric that looks healthiest exactly when the bug is
worst.

### `bug=count-any-ack` — 2 checks fail

Counts acknowledgements from nodes that do not own the key and store nothing.
Violates **invariant 1** (W counts owning replicas only).

**Symptom:** writes that report success and then are not there. This is the
worst of the three for a user, because it violates the one thing they were told.

**Monitoring:** invisible. Write success rate goes *up*.

**6.4** The rest of the suite did not notice because almost every test runs on a
healthy cluster where all owning replicas are reachable, and the buggy branch
only fires when fewer than W owners can be reached. **A quorum system is correct
on a healthy network by accident** — every configuration, including the broken
ones, looks right when nothing is failing.

The consequence for testing: failures must be an *input*, not an environment.
Node death, partitions, slow replicas and clock skew have to be parameters your
tests set on purpose, which is why `Cluster` in this lab exposes `kill`,
`makeSlow`, `partition` and `skewClock` as first-class knobs and why the tests
that catch these bugs are the ones that use them.

**6.5** Hardest to find in production, in order:

1. **`count-any-ack`** — silent, and every metric moves in the *good* direction.
   You would find it only from a user reporting a lost write, and you would not
   believe them.
2. **`lww-always`** — silent and rare, and its natural metric (siblings) reads
   as healthy when it is at its worst.
3. **`no-overlap-check`** — also silent, but it produces stale reads at a high
   enough rate (784 in 4,000 at R=W=1) that users notice a pattern.

The ranking principle worth keeping: a bug is hard to find in proportion to how
much it *improves* your dashboards.

---

## Rung 7 — Handling new failures (discussion)

**7.1 · The acknowledgement that outran the disk.** All three replicas
acknowledged from memory; the rack lost power; the write is gone. The client was
told it succeeded, and W=2 was genuinely met — the acknowledgement simply meant
something weaker than the client assumed.

The protocol change is to define what an acknowledgement asserts, and there are
three defensible answers, each a real setting:
- *"I received it"* — Kafka `acks=1` / Postgres `synchronous_commit = remote_write`.
- *"I wrote it to my log and fsynced"* — MongoDB `j: true`, Postgres `on`.
- *"I applied it and it is visible"* — Postgres `remote_apply`.

The cost is latency, and it is not small: an fsync is milliseconds, and it is on
the critical path of every write. The systems' answer is to make it configurable
per write, which is the same per-request tuning idea as R and W. The deeper point
is that **W counts acknowledgements, and the durability of a write is W
multiplied by what one acknowledgement means.** Quoting W alone tells you
nothing.

Note also the correlation trap: three replicas in one rack fsyncing perfectly
still lose everything when the rack does. Replica placement across failure
domains is part of the durability answer, not separate from it.

**7.2 · The hint holder dies.** The client was told the write succeeded. The
owning replicas never had it. The hint holder is gone. **The write no longer
exists anywhere, and nothing will ever recover it** — anti-entropy compares
owning replicas against each other, and they agree, on the old value.

So: should a sloppy quorum satisfy W on its own? The honest answer is that it
should be a *separate, explicit* mode, not a silent fallback. Riak makes this
concrete by exposing `pw` — the number of acknowledgements that must come from
**primary** (owning) replicas — alongside `w`. Setting `pw ≥ 1` means at least
one real owner has the value, which turns this scenario from "gone" into
"recoverable by repair". The general rule: **let a sloppy quorum extend
availability, never let it be the entire basis of a successful write.**

**7.3 · The grey failure.** The distinguishing signal is *not* request volume or
latency — both look healthy. It is **version age relative to peers on the same
keys**. A replica with quiet keys and a replica that has stopped applying writes
look identical in isolation and completely different in comparison: for the same
key, healthy peers hold a newer version than the suspect does, consistently,
across many keys. Anti-entropy already computes exactly this comparison, so the
detector is a metric derived from a process you should be running anyway —
"fraction of compared keys where this replica was behind", per replica.

Two supporting signals: read repair *direction* (a replica that is always the
target and never the source is not keeping up) and the digest-mismatch rate a
coordinator sees per replica.

Once sure: take it out of the read path first (stop routing reads to it), which
is cheap and reversible, before doing anything destructive. **The risk of a false
positive is a capacity cliff** — removing a replica from a set of three means the
other two now carry the load and a further failure makes reads unavailable. So
the automation must be rate-limited and must refuse to eject more than one
replica per replica set, because the detector's own failure mode is correlated:
whatever fooled it about one node probably fools it about its neighbours.

---

## Rung 8 — Design (discussion)

**8.1 · Three datacentres, 80 ms apart.** Place the replicas one per
datacentre (`N=3`, one per DC) so no single DC failure loses more than one copy.

The naive choice, `R=W=2` across all three, is correct and slow: every write
waits for an acknowledgement from a second datacentre, so every write costs
≥80 ms, and every read likewise. For a user-profile store that is a bad trade.

The better shape is `N=6`, two replicas per datacentre, with `LOCAL_QUORUM`
(local W=2, local R=2 out of the 2… which does not overlap locally, so make it
3 replicas per DC, N=9, local R=W=2). Simpler and more common in practice:
**N=3 per datacentre** (9 replicas total, RF 3 in each of three DCs), reads and
writes at `LOCAL_QUORUM` = 2 of the local 3. Writes are local-latency, and
cross-DC replication happens asynchronously.

What you gave up, stated plainly: **the guarantee now holds within a datacentre,
not between them.** A user whose request lands in dc2 immediately after writing
in dc1 may see old data. When one DC is isolated: users inside it keep reading
and writing normally against a diverging copy; users elsewhere are unaffected
and do not see that DC's writes; on healing, conflicts are resolved by whatever
rule you chose (and if that rule is last-write-wins, the clock skew between
datacentres is now a data-loss mechanism — see F5).

To the product manager: *"Writes are fast everywhere because each region
confirms locally. The cost is that a change made in Europe can take a second or
two to be visible in the US, and if a user is bounced between regions in that
window they can briefly see the older value. We can make that impossible, and
every write would then take at least 160 milliseconds instead of 5."*

**8.2 · A payment ledger.**

- **(a) Appending an immutable ledger entry** — a quorum is fine, and this is the
  best case for one. The entry has a unique id, it is never updated, so
  concurrent writes cannot conflict: two appends are two different rows. Use
  `W = quorum` for durability. This is the same insight as
  [event-sourcing](../event-sourcing/): append-only data has no conflicts to
  resolve.
- **(b) Reading a balance for display** — a quorum read is fine, and `R = 1` is
  arguably fine too if the UI labels it as of a moment. Cheap, available.
- **(c) Reading a balance to authorise a payment** — a quorum read is **not
  enough**, and the reason is subtle: `R + W > N` tells you your read reflects
  the last successful write, but authorising is a read-modify-write, and between
  your read and your write another authorisation can complete. You would be
  building exercise 5.3 and getting its answer. You need either a conditional
  write on a version (single-partition CAS), or to model the balance as a fold
  over the append-only ledger with a uniqueness constraint on the deduction.
- **(d) "This idempotency key may be used exactly once"** — this needs
  **consensus**, unambiguously. It is the definition of exclusivity: exactly one
  of many concurrent attempts may win. A quorum gives you overlap, and overlap
  lets both attempts see the same "not used yet" and both proceed. Use a
  single-leader store with a unique constraint, or a compare-and-set backed by
  Paxos/Raft.
- **(e) The daily reconciliation job** — a quorum read is fine, but read at a
  consistent point and expect to find genuine disagreement; reconciliation is
  the process that *discovers* the consequences of every choice above.

**What goes wrong if you get (d) wrong:** a retried payment request is processed
twice. The customer is charged twice, the ledger has two entries, and — because
the duplicate arose from a *retry*, which is most likely during an incident —
it happens to many customers at once. It is also nearly invisible in testing,
because it needs two concurrent attempts with the same key, which is exactly the
case a healthy network never produces. This is why the idempotency key is the
one place in a payment system where paying for consensus is not negotiable.

**8.3 · Migrating off last-write-wins.** The migration is a
[cqrs](../cqrs/)-shaped dual-write with a verification period:

1. Start writing a **conflict-free representation alongside** the existing
   column — an append-only set of operations, or a CRDT-shaped structure the
   application merges on read. Do not switch reads yet.
2. Backfill history into the new representation. It will be incomplete by
   definition: **the data already lost is not recoverable**, because
   last-write-wins left no record of what it discarded. Say so explicitly rather
   than implying the migration is a repair.
3. Run a **shadow comparison**: read both, serve the old, log the differences.
   The difference rate is your evidence, and it also measures how bad the
   original problem was — probably the first real number anyone has had.
4. Cut reads over when the difference rate is explained rather than merely small.
5. Remove the old column only after a full `gc_grace_seconds` period, so no
   replica that was down can resurrect it.

For the already-corrupted data: identify it (rows whose write timestamps cluster
suspiciously, or where an audit trail exists elsewhere), and treat recovery as a
business process, not a migration step.

**8.4 · A read-heavy service.** 10:1 reads, p99 budget 50 ms, 5% stall rate.

Consistency first: any `R + W > N` works. Latency decides between them, and the
arithmetic is the binomial from experiment 10. With a 5% per-replica stall rate:

- `N=3, R=2, W=2`: a read is slow if ≥2 of 3 replicas stall = `3(0.05²)(0.95) +
  0.05³ ≈ 0.72%`. Comfortably inside a p99 budget.
- `N=3, R=3, W=1`: slow if any of 3 stalls = `1 − 0.95³ ≈ 14.3%`. Blows the p99
  budget outright.
- `N=3, R=1, W=3`: reads are fastest possible (`0.0125%` slow) but writes are
  slow *and* fragile — one dead replica stops writes entirely.

Given 10:1 reads, the instinct is to push R down and W up. Do not go past the
line: `R=1, W=3` satisfies the guarantee but makes writes unavailable whenever
any replica is down, which at any real cluster size is most of the time. The
answer is **`N=5, R=2, W=4`**: reads are slow only if ≥2 of 5 stall (≈2.3%),
writes tolerate one dead replica, and the guarantee holds (2+4 > 5). If write
availability matters more, `N=5, R=3, W=3` is the balanced point.

**Which constraint bound the answer:** latency, not consistency. Every candidate
satisfied `R + W > N` — the consistency requirement did not distinguish between
them at all. That is the usual case in practice, and it is why picking R and W
by reciting the inequality and stopping there is only half the job.
