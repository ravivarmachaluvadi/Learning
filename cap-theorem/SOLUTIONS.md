# CAP Theorem — Solutions

## Part 1 — Predict

**1.1** No — the read returns the new value. With a healthy network, W=1
still triggers *asynchronous* replication to the other replicas (in the
simulator it lands everywhere immediately; in real systems within
milliseconds). R+W ≤ N means staleness is **possible** (no overlap is
*guaranteed*), not that it's certain. This is why AP systems look perfectly
consistent in demos and dev environments: the tradeoff only becomes visible
under partition or replication lag. CAP constrains the *worst case*, not the
average day.

**1.2**
- **L write → REFUSED.** Island 1 is {A} alone: 1 reachable < W=2.
- **L read → REFUSED.** 1 reachable < R=2.
- **R write → OK.** Island 2 is {B,C}: 2 ≥ W=2. The write commits on the
  majority side.
- **R read → OK, and consistent.** B and C both have the latest write.

Island 1's customers experience the outage; island 2's customers never notice
anything. A CP partition outage is *partial* — it hits whoever is stuck with
the minority. This is why real CP systems put replicas in 3+ failure domains:
to make the majority side as reachable as possible.

**1.3** **Nobody can write** — W=3 needs all three nodes, and no island has
three. **Both sides can read** (R=1). No stale read is possible: reads may
return *old* data in wall-clock terms, but no *newer committed write* can
exist anywhere — writes are frozen — so every read returns the latest
committed value. R+W=4 > 3 still holds, and the guarantee holds with it; you
paid with total write unavailability. (This is roughly "read-only mode during
incidents," a real production pattern.)

**1.4** W=1, R=3 optimizes for **fast, always-available writes** — a write
needs only one node. Readers pay: every read must assemble all three
replicas, so during the partition **no one can read at all** (no island has
3 nodes), while writes keep landing on both sides — diverging. It's the
mirror image of 1.3. Write-heavy ingest systems (metrics, logs) pick shapes
like this; interactive systems rarely do, because reads are what users feel.

## Part 2 — Modify

**2.1** **W=1** (the only value ≤ the minority island's size of 1). Both
islands then accept writes independently — you've enabled **split-brain**.
The bill arrives at heal time: experiment 4's `CONFLICT` line, where
last-write-wins discards an acknowledged write. Allowing writes everywhere
during a partition and keeping every write are incompatible wishes; you get
to keep at most one.

**2.2** Nobody is refused. Island {A,B} has 2 nodes ≥ quorum, so L (on A)
works normally. R talks to C — alone on its island, so C *should* be refused
for both reads and writes (1 < 2)... and that's the answer: **R is refused,
L is fine** — the outage moved to whoever is attached to the minority. The
point of the exercise: which clients suffer depends entirely on *where the
network happens to tear*, not on anything about the clients. Route-around
logic (retry against the other island) is how real clients cope.

**2.2b** Because a client pinned to one node inherits that node's fate: a
single fixed endpoint turns "one replica is on the wrong side of the tear"
into "this customer is down," even when a perfectly healthy majority is
sitting one hop away. Giving the client a node list (or a smart driver, as
Cassandra and MongoDB do) lets it retry elsewhere and ride out the storm.
What it does *not* buy you in a CP system: if every node the client can
reach sits on the minority side, retrying finds no quorum anywhere and the
client is still down — routing can move you to the majority, but it cannot
manufacture one. That residual outage is exactly the A that CP gave up.

**2.3** With N=5, W=3: the majority side keeps writing as long as it holds
≥3 nodes, so up to **2 nodes** can be cut away. With N=4, W=3 under a 2-2
split, *neither* side has 3 nodes: the entire system stops accepting writes
even though all 4 machines are healthy. Even N buys you extra hardware and
zero extra fault tolerance (N=4 tolerates the same 1-node-loss as N=3, with
a new total-stall failure mode added). Hence 3, 5, 7 everywhere in ZooKeeper
/ etcd deployments.

## Part 3 — Debug

**3.1** Flaw 1 — **"a really good network" is not a property you can
guarantee.** Partitions come from switch firmware, misconfigured firewalls,
GC pauses that make a node *look* partitioned, overloaded NICs, and backhoes.
P is decided by physics and operations, not by the designer's optimism; a
distributed system that hasn't chosen its partition behavior has still chosen
— it just doesn't know which. Flaw 2 — **the design already contradicts
itself under its own failure**: "replicate synchronously" means a write must
reach both replicas, so the moment the network *does* hiccup, writes block —
that's giving up A (it's a CP design wearing a CA badge). If it instead kept
serving writes from both replicas during the hiccup, it would diverge —
giving up C. The claim never survives its first partition; the only honest
version of "CA" is a single node.

**3.2** Mechanism: the email write landed on replica set X; the
password-reset service read from a replica that hadn't received it — a
**stale read** in an AP store. The confirmation page read its own write
(likely served by the same node that took it), which *masked* the divergence
from the user. *No errors is the signature of choosing A*: an AP system's
failure mode is wrong answers delivered successfully, so nothing throws — the
system is working exactly as configured. (A CP system's signature is the
opposite: errors everywhere, data wrong nowhere.) The two knobs from this
lab: raise **R and W so that R+W > N** (quorum reads/writes for the email
field — cost: latency on every operation, unavailability of the minority
side during partitions), or serve security-critical reads from the
**leader/primary only** (cost: the leader becomes a bottleneck and its
island's failure takes reads down). Ten minutes is long for replication lag —
the report also hints anti-entropy wasn't running; read-repair only fixes a
value *after* something reads it with quorum.

**3.3** The sequence in 3.3 healed with only ONE island having written —
nothing conflicted, so nothing was lost, and the OK read proves nothing about
safety. The counterexample is experiment 4's sequence: AP → cut →
**L write (bal=150)** → **R write (bal=200)** → heal. Both writes were
acknowledged OK to their clients; the heal line reads
`CONFLICT … last-write-wins kept bal=200 … bal=150 DISCARDED`. That line is
the smoking gun: client L was told "OK" and its write no longer exists
anywhere. "Consistent after healing" (convergence) guarantees replicas
*agree*, not that they agree on a history that kept your write. Eventual
consistency promises *a* value — not *your* value.
