# Solutions — gossip protocols

Try [`EXERCISES.md`](EXERCISES.md) first. Rungs 7–8 are worked discussions, not
single right answers. Every number below was measured with the code in this lab.

---

## Rung 1 — Explain

**1.1** Because that machine becomes the thing everything else depends on. Every
one of the 500 has to call it to report in and to find out about the others, so
it carries load that grows with the cluster. If it stops, nobody learns anything
new. And worst of all: if the network splits, the machines on the far side cannot
reach it, so they are left with no idea who is alive — at exactly the moment when
knowing that matters most. To make it reliable you end up running several copies
that must agree with each other, which is a harder problem than the one you
started with.

**1.2** Because every machine that hears the news starts telling it too, so the
number who know is multiplied each round rather than increased by one: 1, 3, 9,
27. Reaching 500 by multiplication takes about five steps; reaching it by
addition takes 500.

**1.3** Gossip can spread the *news* of who the leader is, but it cannot *pick*
one. It has no notion of agreement at an instant — two halves of a partition will
each happily gossip a different leader, and both are internally consistent.
Electing requires a mechanism that can refuse to proceed without a majority
([`paxos`](../39-paxos/), Raft). The usual architecture is both: consensus decides,
gossip distributes.

---

## Rung 2 — Identify

**2.1** From a fresh page with fanout 2: 1 → 3 → 7 → 15-ish, then all 20. The
ratio is roughly 3 (each knower tells 2 more and keeps knowing), until it runs
out of uninformed nodes to reach.

**2.2** The inspector shows whether it is running, whether it has the news, its
heartbeat and incarnation, and how many peers it believes alive / suspect / dead.
While a machine is selected the ring shows **its** opinion — the page says so
under the table, and it is the fastest way to see that different machines hold
different views at the same moment.

**2.3** A mixed column means the nodes disagree about that machine — some say
alive, some suspect, some dead. The **nodes disagreeing** stat box puts a
percentage on it, and it is the same quantity the `consistent-hashing` lab called
the membership propagation window.

**2.4** The merge:

```java
int cmp = Long.compare(in.incarnation, this.incarnation);
if (cmp == 0) cmp = Integer.compare(in.status.ordinal(), this.status.ordinal());
if (cmp == 0) cmp = Long.compare(in.heartbeat, this.heartbeat);
if (cmp <= 0) return false;
```

The refutation, in `Node.merge`: `incarnation = in.incarnation + 1;` inside the
branch that handles somebody gossiping about *me*.

---

## Rung 3 — Predict

**3.1** **6 rounds.** 10 nodes take 2. The rule is `log(n)/log(fanout+1)` =
log₄(5000) ≈ 6.1. Five hundred times the cluster, three times the rounds.

**3.2** **Nine of 1,000 never learn it, and they never will.** A stopped rumour
cannot restart: no node knows anybody is missing it. That is exactly why an
anti-entropy layer runs underneath the rumour layer. The stop rule is still worth
having — it used 4,696 messages against 29,933.

**3.3** **Push is slower at the tail**, 16.5 rounds against pull's 12.0 for 500
nodes at fanout 1. Push reaches 90% in 11.9 rounds and then spends another 4.6
finding the last 10%, because a push only helps if it lands on one of the few
remaining uninformed nodes. Pull has the opposite shape, and push-pull (8.3) beats
both.

**3.4** **100% of beliefs say DEAD**, with all 100 processes running, and only 10
of 100 nodes ever got the news. The failure detector, not the network, produced
the outage.

**3.5** **Zero.** Heartbeat gossip carries n5's rising heartbeat to n1 through the
other 18 nodes, so n1 never suspects it — and cheerfully keeps sending work down a
link that does not work. Under the strict rule it is 9.7 of 20, which is the
opposite error. Indirect probes give 0 for the right reason.

---

## Rung 4 — Modify

**4.1** Measured, push-pull:

```
     n=10     fanout 1: 3.5    fanout 3: 2.0
     n=100    fanout 1: 6.4    fanout 3: 3.9
     n=1000   fanout 1: 9.0    fanout 3: 5.1
     n=5000   fanout 1: 11.0   fanout 3: 6.0
```

One sentence: fanout 1 tracks log₂(n) and fanout 3 tracks log₄(n), because the
informed population multiplies by (fanout + 1) each round — so fanout changes the
*base* of the logarithm, which is why going 1→3 helps a lot and 6→10 barely
registers. Nothing unsafe happens; this is pure liveness/latency.

**4.2** With `suspectAfter = 1`, scene 4's paused node is suspected almost
immediately and by nearly everyone (experiment 7: 98% of the cluster). A cluster
whose nodes pause for 200 ms every few seconds would flap constantly: nodes
endlessly suspected, endlessly refuting, membership changing many times a minute.
Every consumer of that list — a hash ring, a router — then reshuffles on each
change, which is far more damaging than the pauses were. Liveness lost, and
avoidably.

**4.3** Measured, 100 nodes, one crash, rounds until every node agrees again:

```
   maxEntriesPerMessage = 0 (unlimited) : 10.0 rounds
   maxEntriesPerMessage = 3             : did not reconverge within 200 rounds
```

A 3-row cap in a 100-node cluster starves the news: each message carries almost
nothing, so a change cannot outrun the noise. The real constraint that makes a cap
non-negotiable is packet size — a UDP datagram above the path MTU is fragmented or
silently dropped (see [`networking-for-developers`](../01-networking-for-developers/)),
so real systems cap the payload and compensate by sending **digests** and letting
peers ask for the rows they lack, which is exercise 5.1.

**4.4** Removing SUSPECT (straight ALIVE → DEAD) fails
`a_node_that_was_only_slow_refutes_its_own_death_and_stays_alive` — measured, 11
passed, 1 failed. What the cluster lost is the *appeal window*: there is no longer
a public statement of doubt that the accused can answer before the verdict is
final.

Whether the node ever recovers now depends on something that ought to be
unrelated — the exchange mode. Under push-pull it still recovers, because it keeps
initiating exchanges and the replies carry the verdict back to it, which triggers
refutation. Under push-only it is buried permanently: nobody gossips to a dead
node, so it never hears the verdict and never refutes (verified in 7.2 below). A
protocol whose recovery depends on that accident is a protocol with a missing
state.

---

## Rung 5 — Implement

**5.1 — digest exchange.** The shape: send `{id: (incarnation, heartbeat)}`, have
the receiver diff it against its own table and reply with (a) the ids where it is
*behind*, requesting full rows, and (b) full rows where it is *ahead*. Two
observations worth having before you start:

- The digest is `O(n)` too — just with a much smaller constant (two numbers per
  node instead of a full row). To get a genuinely bounded message you must also
  cap it and rotate which ids you include, which is what real implementations do.
- Convergence should cost roughly one extra round-trip per exchange, not more
  rounds of spread: the same information moves, in two messages instead of one.

**5.2 — adaptive timeout.** Track a per-peer mean gap and suspect at ~4× it. You
should find rows that look better in *both* columns of experiment 7, because a
node with a fast reliable link gets a tight threshold while a jittery one gets a
loose one — that is precisely the argument for phi-accrual.

The new failure you have introduced: a peer with **no history** (just booted, or
just became reachable) has no mean, so whatever default you pick is applied to
exactly the nodes you know least about. Real detectors handle this with a
conservative default and a warm-up period, and it is worth noticing that the
warm-up window is itself an attack surface for flapping.

---

## Rung 6 — Debug

**6.1** Three tests fail:

```
FAIL  merging_in_either_order_gives_the_same_answer   2 distinct results from 40 orderings
FAIL  bad_news_beats_good_news_at_the_same_incarnation
FAIL  a_refuted_node_is_not_re_suspected_by_stale_gossip
```

The single wrong idea: **"whoever has the newer heartbeat has the newer
information"** — deciding on the heartbeat *first* instead of on the incarnation
and status.

**6.2** Said out loud it sounds obviously right: a bigger heartbeat is more
recent, so prefer it. It is wrong because heartbeat is only meaningful *within*
one incarnation and one status. A stale message can carry a large heartbeat with
an old status, and under this rule that stale status wins — so a node that
refuted its death gets re-buried by an old rumour still circulating behind it.
And because the ordering of the three fields is now inconsistent, the merge stops
being commutative: 40 orderings of the same five facts produce two different final
states, so two nodes given the same news in different orders never agree again.

**6.3** It violates **I1** (order independence) and **I4** (only the subject may
overturn a suspicion). The user-visible symptom: nodes that pause — a long garbage
collection, a slow disk — get marked dead, refute, and get marked dead again by
the rumour that is still going round. The membership list flaps for those nodes
indefinitely, and everything downstream (a hash ring, a router, a shard map)
reshuffles on every flip.

**6.4** Two merges and one assertion:

```java
PeerState p = new PeerState(2, 11, Status.ALIVE, 0);        // a fresh refutation
p.mergeFrom(new PeerState(1, 99, Status.SUSPECT, 0), 1);    // an old rumour, big heartbeat
check("a stale rumour cannot bury a refuted node", p.status == Status.ALIVE, p.toString());
```

Note the shape: it asserts that something did **not** change. A test that only
checks the happy merge direction passes against the bug.

---

## Rung 7 — Handle a failure

**7.1 — the detector meltdown.** Three mitigations, and the one thing that
separates them:

- **A cap on simultaneous suspicion** ("never suspect more than X% of the cluster
  at once"). Purely local, trivial to implement, and blunt: it delays the
  meltdown rather than preventing it, and picks an arbitrary victim set.
- **A quorum before a verdict binds.** This is Redis Cluster's PFAIL → FAIL: one
  node's private suspicion is only promoted to a cluster-wide verdict when a
  majority of primaries agree. Strong, and it costs you a majority — during the
  very partition where you cannot get one, no node can be marked dead at all.
  That is a deliberate CP-flavoured choice inside an AP protocol.
- **Self-doubt (Lifeguard).** A node that is failing to hear from *many* peers
  concludes that the problem is probably itself, and lengthens its own timeouts
  and lowers its confidence. This is the one that directly addresses F5, because
  in a meltdown every node is in exactly that situation.

The distinguishing question the exercise asks: **which can a node decide with
purely local information?** The cap and self-doubt can — a node knows how many of
its own peers have gone quiet. The quorum cannot: it needs to talk to a majority,
which is precisely what a bad network denies. That is why the local, humble
mechanism is the one that saves you in the worst case.

**7.2 — a rebuilt node with a reset incarnation.** Measured on a 20-node cluster
where n7 was buried by all 19 peers, then rebuilt with incarnation 1:

```
  push-pull : every peer says ALIVE again; n7's incarnation is 2
  push-only : 0 peers say ALIVE; n7 stays buried, incarnation still 1
```

The step-by-step: nobody gossips *to* a dead node, so n7 hears nothing. Under
push-pull, n7 still initiates exchanges; the peer ignores its stale `ALIVE(inc 1)`
(the peer holds `DEAD(inc 9)`, which is larger) but its **reply** carries that
`DEAD(inc 9)` back to n7, whose refutation branch fires and jumps to incarnation
10. Under push-only there is no reply, so the verdict never reaches n7 and its
protests never win. A rebuilt node is invisible for ever.

The fix is to make the incarnation **strictly increasing across restarts**, which
means it cannot live only in memory. Options and their real costs: a boot
timestamp (Cassandra's *generation* — free, but it trusts the clock, and a machine
whose clock jumps backwards after a rebuild is silently unrecoverable); a counter
persisted to disk before joining (correct, but now joining requires a durable
write and a rebuilt-from-blank machine has lost it); or a fresh unique id per
process (correct and free, but the node is now a *different member*, so anything
keyed by node identity — a hash ring position, stored shards — must be rebuilt,
which is the very churn you were avoiding).

**7.3 — a malicious or buggy node.** One node gossiping `everyone: DEAD,
incarnation 999` is catastrophic in this design: the merge rule is built to prefer
higher incarnations, so the lie beats every truthful record, and gossip's own
efficiency spreads it to the whole cluster in log(n) rounds. Every real node
would refute *itself* on hearing its own death — but only for its own row, and
only if it hears; meanwhile every node's belief about every *other* node is now
DEAD, and dead nodes are not gossiped to.

What limits it: the self-refutation path, and (in Redis Cluster's design) a
quorum requirement before a verdict binds. What amplifies it: the merge rule's
preference for higher incarnations, the fanout, and the fact that no node
questions the provenance of what it is told.

This is a **Byzantine** fault — a node that lies rather than simply stops. Nothing
in this lab, or in Cassandra or SWIM, defends against it; they assume crash-stop
failures. Surviving it requires signed statements about a node's own state
(so nobody can forge a claim about you) plus a Byzantine-fault-tolerant protocol
for anything that must be agreed. In practice, production systems buy this far
more cheaply with authentication at the edge: you make sure only your own nodes
can join the gossip at all.

---

## Rung 8 — Design

**8.1 — three datacentres.** Keep the protocol, change the topology.

- **Within a DC:** normal gossip, fanout ~3, sub-second interval, tight-ish
  timeouts. Local links are fast and reliable, so detection can be quick.
- **Across DCs:** far fewer messages. Elect (by a deterministic rule, e.g. lowest
  id per DC per round) a small number of nodes that gossip cross-DC, so the WAN
  carries `O(DCs)` streams rather than `O(nodes)`. Cassandra does exactly this.
- **Timeouts must be per-link-class, not global.** An 80 ms RTT with jitter needs
  a much looser threshold than a local link; a single global `suspectAfter` will
  either flap the remote DCs or fail to detect local crashes. This is the same
  reasoning as phi-accrual, applied by topology.
- **A DC cut off entirely** must NOT result in the other two marking 100 nodes
  dead and reshuffling everything. Guard it with the "never suspect more than X%
  at once" rule from 7.1, and make cross-DC failover an operator decision rather
  than an automatic one. The important sentence: from inside, a partition and a
  dead datacentre look identical, and the cost of being wrong is very different in
  the two cases.
- **Survives from this lab:** the merge rule, incarnations, suspicion,
  anti-entropy. **Must change:** flat uniform peer selection, one global timeout,
  and the assumption that all links are equal.

**8.2 — 10,000 nodes.** In order of what breaks first:

1. **Message size (F8).** 10,000 rows in every message, several times a second.
   Symptom: gossip traffic dominates the network, then packets exceed the MTU and
   are fragmented or dropped. Fix: digests plus a capped, rotating set of
   piggybacked updates.
2. **Membership churn.** With 10,000 nodes, *something* is always joining, dying
   or pausing, so the list never stops changing. Symptom: the disagreement window
   never closes. Fix: make consumers of the list tolerant of churn (a hash ring
   already is) and damp the flapping with adaptive timeouts.
3. **Convergence time.** log₄(10,000) ≈ 6.6 rounds — the *good* news, and the
   reason gossip is used at this scale at all.
4. **The detector.** More nodes means more simultaneous pauses, so the false
   positive rate scales with the cluster while your tolerance for it does not.
   Fix: phi-accrual, suspicion, and a cap on simultaneous suspicion.

**8.3 — the alert.** Page on **the rate of membership state changes across the
cluster** — say, more than N transitions per minute sustained for five minutes —
not on any node's state.

- Not "node X is dead": that is routine and self-healing, and it will page you
  every night.
- Not "cluster size below N": it fires only after the damage, and it cannot tell
  a rolling deploy from an outage.
- Not convergence time alone: it is the best *health* metric but it stays fine
  right up until the meltdown, then goes to infinity with nothing in between.

What the rate metric misses: a **quiet** wrong state — a cluster that has settled
on an incorrect but stable view, such as everyone agreeing a healthy node is dead.
Nothing is changing, so nothing alerts. Cover that with a separate, cheap check
from outside the gossip system: can a prober reach the nodes the cluster says are
dead?

**8.4 — 2,000 proxies: gossip or a coordinator?**

*For a coordinator* (the real service meshes' choice): routing config is
authored centrally and must be exact, versioned and auditable; operators need to
say "roll out config v37" and know what is live. Gossip cannot give you "this
version is now live everywhere", which is a requirement here rather than a nicety.

*For gossip:* 2,000 proxies phoning home is real load and a real dependency; a
control-plane outage must not stop the data plane from knowing which peers exist.

**Commit:** a coordinator for **configuration** (it is authored, versioned and
must be exact), and gossip for **liveness** (it is observed, local, and needed
exactly when the control plane is unreachable). The two questions have opposite
requirements, and the standard mistake is answering both with one mechanism.

**The failure that would make me regret it:** the split brain between the two
layers — gossip says a proxy is alive while the coordinator has not sent it the
current config, so traffic is routed to a reachable proxy running stale rules.
The mitigation is to make config version part of the gossiped state, so peers can
see who is behind, and to have a proxy refuse traffic for rules it knows are
outdated — which is the [`redis`](../13-redis/) lab's `min-replicas-to-write` idea
in a different costume.
