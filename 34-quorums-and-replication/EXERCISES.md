# Exercises — quorums and replication

Eight rungs, easiest first. Attempt each before opening
[SOLUTIONS.md](SOLUTIONS.md). Rungs 7 and 8 are worked design discussions with
no single right answer.

```bash
java src/Tests.java                  # 42 scenario checks
java src/Experiments.java            # ten measured experiments
open visualization.html
```

---

## Rung 1 — Explain

**1.1** Why does keeping three copies of your data create a problem that
keeping one copy does not? Answer without using the word "consistency".

**1.2** Derive `R + W > N` from scratch, out loud, for `N = 5`. Why is `R = 3,
W = 3` enough and `R = 2, W = 3` not?

**1.3** In the [paxos](../39-paxos/) lab you met a quorum that was a majority. In
this lab a quorum is any R or W with `R + W > N`. Give one sentence that
separates what each guarantees.

**1.4** Your colleague says "we set `acks=all`, so our writes are durable."
What is the follow-up question, and why does the answer matter?

**1.5** Explain to a non-engineer why "the write failed" does not mean "the
write did not happen".

---

## Rung 2 — Identify

Open the [visualization](visualization.html).

**2.1** With N=3, W=2, R=2, do one write and one read. Which replica is coloured
violet, and what exactly does that colour claim?

**2.2** During a write, at which step is the client told "success"? What happens
to the remaining replicas after that moment, and why does it matter?

**2.3** Turn on **Sloppy quorum**, cut the network, and write. Look at the
banner at the top: the arithmetic and the verdict now disagree. Which one is
lying, and what is the precise reason?

**2.4** In [`src/Cluster.java`](src/Cluster.java), find the two loops in `put`.
What is true of a replica that appears in the first loop but not the second, and
which failure scenario in the README is that?

**2.5** In [`src/Node.java`](src/Node.java), find the three-way switch in
`write`. What happens on `CONCURRENT`, and what would change if that line said
`dominated = true` instead?

**2.6** In [`src/Cluster.java`](src/Cluster.java)'s `get`, find the line that
stops the read early. Which of the three numbers does it enforce, and what would
break if you removed it?

---

## Rung 3 — Predict

Commit to an answer, then run the command.

**3.1** `N=3`. At which of the nine (R,W) combinations is the stale-read count
exactly zero? Now predict the *write success rate* at `W=3` with each replica
failing 20% of the time. `java src/Experiments.java overlap`

**3.2** A write requires W=3, one replica is slow, the client gets an error.
Out of 500 such writes, how many values are readable afterwards?
`java src/Experiments.java partial`

**3.3** 1,000 pairs of concurrent writes. How many updates does last-write-wins
lose? How many does the version-vector mode lose?
`java src/Experiments.java concurrent`

**3.4** `R+W>N` holds throughout, sloppy quorums are on, and a partition cuts
the coordinator off from two of three owners. What fraction of writes will a
strict read afterwards be able to see? `java src/Experiments.java sloppy`

**3.5** In fully-synchronous mode with both followers unreachable, how many of
100 writes are refused? `java src/Experiments.java leader`

**3.6** Each replica stalls on 10% of requests. Waiting for 3 of 3 rather than
2 of 3 — what fraction of requests becomes slow?
`java src/Experiments.java latency`

---

## Rung 4 — Modify

For each: predict, change it, run it, and name whether what you lost was
**safety** (something wrong happened) or **liveness** (nothing wrong happened,
but progress stopped).

**4.1** In [`src/Experiments.java`](src/Experiments.java)'s `staleRun`, remove
`ctx = wr.vv()` so every write is made with an empty version context. Re-run
`overlap`. What happens to the stale-read counts, and *why*? (This is the bug
the README describes in the box in stage 10.2 — reproduce it deliberately.)

**4.2** Set `c.readRepair = true` in `staleRun` and re-run `overlap`. Do the
stale counts for `R+W ≤ N` fall to zero? Should they? What has read repair
actually changed, and what has it not?

**4.3** In [`src/Cluster.java`](src/Cluster.java), change `get` so that it
gathers from *all* reachable replicas but still requires only R to have
answered. Which experiment's numbers change, and does the guarantee get
stronger, or only the luck?

**4.4** In [`src/LeaderCluster.java`](src/LeaderCluster.java), set
`minInSync = 3` and re-run `leader`. Which column changes, and what have you
just made impossible?

**4.5** In the visualization, set **asks first** to a fixed replica rather than
"rotate". Run the `W=1, R=1` scenario several times. What changed about the
*reproducibility* of the bug, and what does that tell you about why staleness
bugs are so hard to reproduce in production?

---

## Rung 5 — Implement

**5.1 · `LOCAL_QUORUM`.** Give each node a datacentre label (`dc1`, `dc2`) and
add a consistency level that requires a quorum *within the coordinator's own
datacentre*. Write a test showing that a write succeeds while the entire remote
datacentre is unreachable — and a second test showing exactly which guarantee
you have given up by doing so.

**5.2 · Bounded staleness.** Add a read mode that refuses to answer from a
replica whose version is more than K writes behind the newest version seen in
that read. What does this buy that R alone does not? What new failure does it
introduce?

**5.3 · Compare-and-set (the instructive failure).** Implement
`putIfVersionMatches(key, expectedVV, value)`: the write applies only if the
replica's current version matches. Then write a test in which two clients
attempt a compare-and-set concurrently and prove that **both can succeed**.

Then answer the real question: what would you have to add so that only one can?
Why is that a fundamentally different (and more expensive) mechanism than
anything else in this lab?

---

## Rung 6 — Debug

Three bugs are planted behind flags. Each disables exactly one rule from the
CORRECTNESS stage of the [README](README.md).

```bash
java src/Tests.java bug=no-overlap-check
java src/Tests.java bug=lww-always
java src/Tests.java bug=count-any-ack
```

For **each**: (a) which checks fail? (b) which invariant is violated? (c) what
would a *user* see — the symptom, not the test output? (d) would monitoring
catch it, or is it silent?

**6.4** `bug=count-any-ack` fails only two checks, and both are in a test that
had to be written specially. Why did the rest of the suite not notice, and what
does that tell you about how to test a quorum system?

**6.5** Which of the three bugs would be hardest to find in production, and why?
Rank them and defend the order.

---

## Rung 7 — Handle a failure the lab does not survive

**7.1 · The acknowledgement that outran the disk.** A replica acknowledges a
write when it has it in memory, before it is on disk. Three replicas do this,
W=2 is met, and then a rack loses power. What has been lost, what did the client
believe, and what must change in the protocol for the acknowledgement to mean
what it claims? What does this cost, and which real settings correspond to each
choice?

**7.2 · The hint holder dies.** During a partition, a sloppy write is held as a
hint on a node that does not own the key. That node then dies permanently while
holding the only recent copy. Trace exactly what the client was told, what
exists afterwards, and what mechanism (if any) recovers it. Then decide: should
a sloppy quorum be allowed to satisfy W on its own?

**7.3 · The grey failure.** A replica is up, fast, and answering every request —
with data that stopped updating an hour ago (a stuck compaction, a full disk
that silently drops writes). Nothing times out. Design the detection. What
signal distinguishes "this replica has quiet keys" from "this replica has
stopped applying writes"? What do you do once you are sure, and what is the risk
of acting on a false positive?

---

## Rung 8 — Design

**8.1 · Three datacentres, 80 ms apart.** A user-profile store. Choose N, R, W
and the replica placement. Where does each write's latency go? What happens when
one datacentre is isolated — for users in it, and for users elsewhere? Which
guarantee do you deliberately give up, and how would you word that to a product
manager?

**8.2 · A payment ledger.** Connecting to [saga](../47-saga/) and
[transactional-outbox](../48-transactional-outbox/): you are storing account
balances and a transaction ledger. Decide, for each of these, whether a
Dynamo-style quorum is acceptable or whether you need consensus, and say why:
(a) appending an immutable ledger entry, (b) reading a balance for display,
(c) reading a balance to authorise a payment, (d) enforcing "this idempotency
key may be used exactly once", (e) the daily reconciliation job. What goes wrong
if you get (d) wrong?

**8.3 · Migrating off last-write-wins.** A live Cassandra table uses
last-write-wins and you have discovered concurrent writes are losing customer
data. You cannot take downtime. Design the migration to a conflict-free
representation. What runs in parallel, how do you verify it before cutting over,
and how do you handle the data already corrupted?

**8.4 · A read-heavy service.** 10 reads per write, a p99 latency budget of
50 ms, and replicas that stall on 5% of requests. Choose N, R, W. Show the
arithmetic for both the consistency guarantee and the expected tail latency, and
say which of the two constrained your answer.
