# ZAB — Solutions

Worked answers. For rungs 7 and 8 the "answer" is a design discussion, not a single output — yours may
differ and still be right, but it should engage with the same trade-offs.

---

## Rung 1 — Explain

**1.1** A group of machines has to keep one identical list of changes, where each change was worked out
by one machine on top of all the ones before it. The machine doing the working-out will eventually die
halfway through. Without a protocol like ZAB, whoever takes over may be missing the last few changes, or
may hold changes nobody else has, and either way it starts computing new answers from a wrong picture —
so two clients end up being given the same answer, or an answer somebody was already promised quietly
disappears.

**1.2** *Raft replicates the command and every server computes the answer itself; ZAB replicates the
answer that one server already computed.* Because ZAB's changes are results computed on top of earlier
results, it is not enough for the messages to be ordered — the **primaries themselves** must be ordered,
so a new primary must finish delivering everything it inherited before it computes anything of its own.
Raft has no such requirement, because any server can recompute the state from the log at any time.

**1.3**
*Correctness:* a result is **idempotent**. Applying `ticket = 44` twice, or replaying it over a
half-written snapshot, or applying it after a crash-restart, all land in exactly the same state.
Applying `increment` twice does not. This is what lets ZooKeeper take **fuzzy snapshots** — snapshots
taken while the tree is changing — and simply replay the transaction log over them.
*Recovery:* the result is fixed at the moment the leader creates it, so a follower never has to reproduce
the leader's computation. Generated values — sequential node names, `czxid`s, session owners, timestamps
— are identical on every server without any server needing a matching random seed, clock, or code
version. A follower running a slightly different ZooKeeper build still ends up byte-identical.

---

## Rung 2 — Identify

**2.1** Whichever server the pill reads **PRIMARY**. Its epoch is shown on its row (`epoch 1` on a fresh
run). `acceptedEpoch` and `currentEpoch` are **equal** once the primary has finished recovery — they
differ only in the window between agreeing to a new epoch and a quorum confirming the synchronisation.
Catch them differing by clicking a follower during the recovery phases in scenario 2.

**2.2** It has the change **on disk** and has told the primary so, but it has not been told it counts.
It must not show it to a client, and it must be prepared to delete it.

**2.3** Three of five. **quorum = ⌊N/2⌋ + 1**. Notice it is three, not five — waiting for everybody would
mean one slow machine stops the cluster, which is the tail-latency argument from your
`quorums-and-replication` lab.

**2.4** The hatched change is `1.2` on the server that was partitioned with the dying primary. The
justifying sentence: **fewer than a quorum ever stored it, so no client was ever told it happened, so
deleting it breaks no promise — and keeping it would leave one server's list permanently different from
everyone else's.**

**2.5** The **dead** server — the old primary. It still holds `1.2` un-delivered because it crashed
before hearing anything back. Its disk state is intact and untouched; when it restarts it will report
`1.2`, discover the new primary does not have it, and be told to TRUNC. This is worth noticing: the
protocol never reaches into a dead machine. It repairs it on the way back in.

---

## Rung 3 — Predict

**3.1** `2.5` reached S1, S2 and S5 (its author) — that is 3 of 5, a quorum, so **S5 had already committed
it and answered the client.** Any electing majority must overlap {S1, S2, S5}, and S5 is dead, so the
electing group contains S1 or S2. The winner is the **maximum** of the electing group, and both S1 and S2
are at `2.5` while S3 and S4 are at `2.4` — so **the winner is S1 or S2**, and it holds `2.5`. Its
NEWLEADER round makes `2.5` final again. **The write survives, and it is arithmetic rather than luck.**

**3.2** Now only S1 stored it, and S1 is gone too. S2, S3, S4 are all at `2.4`; the highest server id
wins the tie, so **S4** leads. `newEpoch = 3`, and its first transaction is **`3.1`** — new chapter,
counter restarts. When S1 returns it reports `lastZxid = 2.5`; the leader does not have `2.5`, so it
sends **TRUNC to `2.4`** followed by a DIFF of everything from `3.1` onward. `2.5` is deleted. No
promise is broken, because only one server ever held it, so it can never have been committed.

**3.3** It agreed to join epoch 7, but the epoch-7 leader **died before bringing a quorum into step** —
otherwise `currentEpoch` would have been updated too. During the next election it reports
`currentEpoch = 5` and its `lastZxid`, i.e. it honestly says "my data is from chapter 5". It will
**refuse any message stamped epoch 7 or lower**, including from a leader that thinks it is still running
epoch 7. That combination — refuse aggressively, report honestly — is exactly why the two numbers cannot
be merged into one.

**3.4** Any value the primary has committed **up to and including `3.9`** — so the value at `3.9`, or the
value at `3.8`, or older. All of those are legitimate: a ZooKeeper read is served from the local
server's memory with no coordination. What would indicate a bug: a value from a change the primary
**never committed**; a value that goes **backwards** for that same client (having once seen `3.9`, a
client must never be served something older — the session tracks the last zxid it saw); or a value that
never appeared in any transaction at all.

**3.5** The 3-server side elects a new primary at a higher epoch after its followers time out (roughly
`syncLimit × tickTime`) and carries on serving reads and writes. The 2-server side: the old primary can
append to its own history but never collects a quorum of acknowledgements, so it commits nothing and
answers nobody; after it has been unable to reach a majority for long enough it **resigns**, and clients
there get timeouts and then session expiry. On healing, the old primary's uncommitted tail is TRUNC'd
and it joins as a follower.

---

## Rung 4 — Modify

**4.1** *Breaks P2 (once told, it happened) — a safety failure.* The new primary cannot pull because
**ZAB synchronisation is one-directional by design**: the leader pushes, the followers accept. That is
what makes recovery one message per follower instead of a negotiation, and it is only safe because the
election guarantees the winner is already the maximum. Remove that guarantee and the same
one-directional push becomes a one-directional **delete**: the behind-the-times primary pushes its
shorter history onto everybody, and committed changes are hatched out of the whole cluster.

**4.2** *Breaks P3 (a new primary finishes its predecessor's business first) — a safety failure.* The
first epoch is immune because there is **nothing inherited**. The primary's history is empty, so
"deliver your inherited history before writing" and "do nothing" are the same instruction. The rule only
has teeth at a **handover**, which is precisely why it is easy to omit and get away with it in testing:
a system that is never failed over will never show this bug.

**4.3** *Breaks P1 (one list everywhere) — a safety failure.* The divergent server holds an extra change
that nobody else has, but as long as nothing new is written, every server's *delivered* prefix still
agrees — the extra change is just sitting there. The moment the primary writes one more change, that
change lands at **position 3** on the divergent server and **position 2** on everyone else, and now the
same query to two servers returns two different answers.

That delay is what makes this class of bug dangerous: **the damage is done at time T and only becomes
observable at time T + something**, by which point the event that caused it has scrolled out of your
logs. It is the same shape as a missed `fsync` — invisible until the power fails.

**4.4** *Breaks P1 and P2 simultaneously.* When the stale change is inserted underneath an already-final
change, (a) a change that had been **delivered becomes un-delivered**, which violates the promise that
delivery is permanent, and (b) two servers now hold different things at the same position. Note that the
stale transaction's *contents* are perfectly valid — nothing about the data says it is stale. The epoch
in the zxid is the only marker, and one integer comparison is the entire defence.

**4.5** The invariant panel reports **two servers claiming the same epoch**, and then divergence once
both sides commit. The counting argument: any two subsets of size `⌊N/2⌋+1` of a set of size `N` must
**intersect**, because `2 × (⌊N/2⌋+1) > N`. For `N=5` that is `2 × 3 = 6 > 5` — true. For a threshold of
2, `2 × 2 = 4 ≤ 5` — false, so `{S1,S2}` and `{S3,S4}` are disjoint and can each "decide" without ever
learning of the other. Half-plus-one is the **smallest** number that forces intersection, which is why
it is not a tunable.

**4.6** **No invariant chip ever turns red.** What degrades is *progress*: elections churn, followers
detect gaps and re-synchronise, writes take longer or fail to commit at all. The principle, which you
measured in the `raft` lab: **safety never degrades with message loss; liveness degrades smoothly and
then collapses.** Election churn is always a *timing* problem, never a correctness one — which is why
"leader elections per minute" is the alarm worth having and "did we lose data" is not a question loss
rate can answer.

---

## Rung 5 — Trace

### 5.1

**Election.** Votes are `(currentEpoch, lastZxid, serverId)`, largest wins, compared in that order.
All are at `currentEpoch = 1`, so the `lastZxid`s decide: S1 `1.3`, S2 `1.2`, S3 `1.2`, S4 **`1.4`**,
S5 `1.1`. **S4 wins** — it holds the most.

**Phase 1 — Discovery.** Everyone reports `acceptedEpoch = 1`; S4 sets **`newEpoch = 2`** and announces
it. Each follower writes `acceptedEpoch = 2` to disk and replies with its `currentEpoch` and `lastZxid`.

**Phase 2 — Synchronisation.** S4's history is `1.1, 1.2, 1.3, 1.4`:

| Follower | reports | receives |
|---|---|---|
| S1 | `1.3` | **DIFF** containing `1.4` |
| S2 | `1.2` | **DIFF** containing `1.3, 1.4` |
| S3 | `1.2` | **DIFF** containing `1.3, 1.4` |
| S5 | `1.1` | **DIFF** containing `1.2, 1.3, 1.4` |

No TRUNC anywhere: every follower's history is a prefix of S4's. Then `NEWLEADER(epoch 2)`; each
follower writes `currentEpoch = 2` and acknowledges.

**End of the NEWLEADER round.** With a quorum of acknowledgements, S4 **delivers its entire history —
`1.1` through `1.4`** — and sends UPTODATE. Everyone delivers up to `1.4`.

**First client write: `2.1`.** New chapter, counter restarts at 1.

**The point of this exercise:** `1.3` and `1.4` were **never committed by the old primary** — they sat
un-delivered on one and two servers respectively. They become final anyway, on the *new* primary's
authority, because a quorum has now confirmed they hold exactly this history. ZAB never asks "was this
promised?" — it only asks "does the new primary have it?", and the election rule makes those two
questions have the same answer whenever it matters.

### 5.2

S4 is down, so the electing group is {S1, S2, S3, S5} with `lastZxid`s `1.3, 1.2, 1.2, 1.1`.
**S1 wins** (`1.3` is the maximum present). `newEpoch = 2`.

Sync: S2 gets DIFF `1.3`; S3 gets DIFF `1.3`; S5 gets DIFF `1.2, 1.3`. NEWLEADER, quorum, deliver
`1.1`–`1.3`, UPTODATE. First client write is **`2.1`**.

**`1.4` is not in the new history — and that is not a violation.** Only S4 ever held it. One server is
below the quorum of three, so `1.4` can never have been committed, so no client can have been told it
happened. Deleting it breaks no promise. **P2 is about promises, not about bytes.**

### 5.3

S4 returns while the leader S1 has `1.1, 1.2, 1.3, 2.1, 2.2`.

S4 reports `currentEpoch = 1, lastZxid = 1.4`. The leader looks for `1.4` in its own history and does
not find it. So: **TRUNC to `1.3`** (the newest zxid they share), then **DIFF containing `2.1, 2.2`**.

S4's history afterwards: `1.1, 1.2, 1.3, 2.1, 2.2`, with `1.4` deleted and recorded as gone. It writes
`acceptedEpoch = currentEpoch = 2`, acknowledges NEWLEADER, receives UPTODATE, and starts serving again.

Worth noticing: S4 was the **most up-to-date server in the cluster** at the start of 5.1 and is the one
that loses data here. Being ahead is not a claim on anything. Only having reached a quorum is.

---

## Rung 6 — Spot the flaw

**6.1 — no NEWLEADER acknowledgement round.**
*Violates Agreement (property 3) and P2.*
Run: S3 wins the election holding `1.1, 1.2`, where `1.2` was stored **only** on S3 before the old
primary died. S3 delivers its whole history immediately, so clients reading from S3 now see `1.2`, and
S3 computes `2.1` on top of it. S3 then dies before any follower has stored `1.2`. S1 and S2 (both at
`1.1`) elect a new primary; `1.2` and everything derived from it never existed for them. Clients were
shown a change that the cluster has now unanimously forgotten.
The acknowledgement round is what converts *"I have this history"* into *"a majority has this history"*,
and only the second one makes delivery safe.

**6.2 — `acceptedEpoch` rebuilt from the log.**
*Violates the at-most-one-primary-per-epoch invariant, and through it Total order.*
The whole point of `acceptedEpoch` is that it can be **higher than any epoch present in the log** —
that is exactly the state of a server that agreed to join a chapter whose leader died before writing
anything. Run: cluster at epoch 3. S4 proposes epoch 4; S1, S2, S3 accept, writing `acceptedEpoch = 4`;
S4 dies before any epoch-4 transaction exists. S1 restarts and rebuilds `acceptedEpoch = 3` from its
log. Now S5 proposes epoch 4 as well, and S1 accepts — so two different servers have been granted epoch
4 by overlapping groups, and two primaries can stamp transactions with the same epoch. Every downstream
comparison that assumes "same epoch means same primary" is now wrong.
The extra fsync is not overhead. It is the mechanism.

**6.3 — ask the leader to resend the missing zxid.**
*Violates Total order (property 2).*
The design assumes the leader's history is append-only across the gap. **TRUNC makes that false.** Run:
S2 is following a leader at epoch 2 and has `2.4`. Its connection drops; it misses `2.5`. Meanwhile the
epoch-2 leader dies, a new leader at epoch 3 truncates `2.5` away and writes `3.1, 3.2`. S2 reconnects,
sees the next thing offered is `3.1`, notices a gap and asks for `2.5`. There is no correct answer:
`2.5` no longer exists, and S2 has no way to discover whether its own `2.4` is still a valid prefix.
The only procedure that answers "is my prefix still valid, and if not where does it diverge" is the
discovery-and-synchronisation handshake — which is precisely why a gap must send the follower back
through it rather than trigger a repair.

**6.4 — acknowledge from page cache, fsync later.**
*Violates P2 and Agreement.*
Run: the leader proposes `2.5`; S1, S2, S3 acknowledge it out of page cache; the leader commits and tells
the client "done". A rack loses power, taking S1, S2 and S3 with it. They restart without `2.5`. S4 and
S5 never had it. The election picks one of them and `2.5` is gone, though a client was told it succeeded.
The argument "a majority are unlikely to lose power at once" is precisely backwards: the failures that
matter in practice are **correlated** — one rack, one PDU, one hypervisor, one availability zone, one bad
deploy. An acknowledgement is a durability claim, and a quorum of claims that are not durable is not a
quorum of anything.

**6.5 — the leader adopts a follower's extra transaction.**
*Violates Primary integrity (property 6) and Total order (property 2).*
Run: the leader is at epoch 3 with history `1.1, 1.2, 3.1, 3.2` — the epoch-2 tail was truncated during
its own recovery. A follower reports `2.7` and the leader adopts it. `2.7` now sorts between `1.2` and
`3.1`, so on any server that had already delivered `3.1`, a change has been **inserted underneath**
something already final, and `3.1` was computed on a state that did not include `2.7`.
There is also a structural objection: adopting from a follower destroys the invariant that the leader is
the maximum, which is the single assumption that makes one-directional synchronisation correct. Once the
leader can be behind, every argument in section 12 of the README stops working.

---

## Rung 7 — Handle failure

### 7.1 The retry that issues two tickets

**The bad run.** A client asks for a ticket. The primary computes `1.2 = "ticket 2"`, gets a quorum of
acknowledgements, delivers it, and **dies before its response reaches the client**. The client sees a
timeout, reconnects to the new primary, and retries. The new primary has delivered its inherited history
(so it knows about ticket 2) and computes **ticket 3** for the retry. The client walks away with ticket
3; ticket 2 was issued to nobody, or — in the `create sequential` version of this — two nodes now exist
where the client wanted one.

**What the server side needs:** the ability to recognise a retry as *the same request*, not a new one.
That means a client-supplied identifier carried on the request and remembered by the server, which is the
standard idempotency-key shape from your **[idempotency](../idempotency/)** lab.

**What ZooKeeper actually gives you**, and it is most of the way there:

- **Conditional operations.** `create` of a *specific* path fails with `NodeExists` on a retry;
  `setData` with an expected version fails with `BadVersion`. If your operation is conditional, the
  retry is naturally safe — this is the `event-sourcing` expected-version idea again.
- **The gap is `sequential` creates**, because the path is generated server-side and a retry cannot name
  it. Apache Curator's answer is **protection mode** (`create().withProtection()`): it embeds a GUID in
  the node name, so after a connection loss the client can look for a node bearing its own GUID and
  adopt it instead of creating a second one. That is a client-side idempotency key implemented with the
  only tool available — the node name.

**The transferable rule:** do not try to discover what happened to a request with no answer. Make
answering it twice harmless.

### 7.2 Three servers in DC-A, two in DC-B

**Why the sentence is false.** Quorum is 3. Lose DC-B and you have 3 — fine. Lose **DC-A** and you have
2, which is **below quorum**: no leader can be elected, no writes are served, sessions expire, and every
system depending on ZooKeeper stops. So the deployment survives losing DC-B and does *not* survive losing
DC-A. It is not a symmetric design and describing it as one is how people get surprised at 3am.

Worse, a **partition** between the datacentres — with both alive — produces the same outcome: DC-A keeps
running, DC-B is dead, and DC-B's local applications cannot coordinate even though every machine in DC-B
is healthy.

**Better option 1 — three datacentres, 2 / 2 / 1.** Quorum is 3, and any single datacentre's loss leaves
at least 3. *Cost:* every write now needs acknowledgements from two datacentres, so write latency is
bounded below by the cross-DC round trip. You need a genuine third site — a small one is enough, since
it holds one voting member.

**Better option 2 — three datacentres, 1 / 1 / 1.** Cheapest way to get real symmetry. *Cost:* only
tolerates one failure, and every write crosses a DC boundary.

**Better option 3 — 3 voters in DC-A, observers in DC-B.** Writes stay fast (all voters are local) and
DC-B gets local reads and a local endpoint. *Cost:* this does not tolerate losing DC-A at all — it is
honest about being asymmetric rather than pretending otherwise, which is already a large improvement.

**The rule underneath:** count how many voting members are in your largest failure domain. If that number
is greater than or equal to the quorum, losing that domain stops everything.

### 7.3 The lock, the GC pause, and the database

**What each party believes.** The client creates `/locks/recon-0000000031`, sees it is the lowest, and
begins writing. It then freezes for 60 s. ZooKeeper stops receiving heartbeats, the session times out,
and — correctly, and with no error anywhere — **deletes the ephemeral node**. Client B sees it now holds
the lowest node, acquires the lock, and starts writing. Client A wakes up still believing it holds the
lock and continues writing. The database sees **two writers**, both legitimate as far as it can tell.

**ZooKeeper did nothing wrong.** It cannot distinguish a frozen client from a dead one, and the freeze
was longer than the timeout the client itself negotiated.

**The fix: fence at the resource.** The lock holder passes a **monotonically increasing token** with
every write, and the database rejects any write whose token is lower than the highest it has already
accepted. The token comes free: use the **`czxid`** of the ephemeral node — ZooKeeper mints it, it never
repeats, and it increases with every acquisition.

**What the fix requires of the database:** it must be able to compare-and-reject on the token
atomically — a conditional update, a version column checked in the same statement, or a stored token
with a `WHERE token > current` guard. If the resource cannot do that (a plain filesystem, a third-party
API with no conditional write), the lock **cannot be made safe**, and the honest engineering answer is
to make the operation idempotent instead so that two writers are harmless. That is the same conclusion
your **[distributed-locking](../distributed-locking/)** lab reached: *the lock service is not the
problem; the resource's inability to check a token is.*

### 7.4 Losing the leader every ninety seconds

**The inequality being tested:**

```
GC pause + fsync latency + network round trip   <   syncLimit × tickTime
```

**Diagnostic order, and why:**

1. **JVM GC pause distribution on all five servers.** First because it is the most common cause, the
   easiest to measure, and it produces exactly this symptom: a healthy server stops answering for longer
   than `syncLimit × tickTime`, is declared dead, and an election starts. Look at max pause, not mean.
2. **fsync latency and disk queue depth on `dataLogDir`.** Second because it is on the critical path of
   every single write, and because it is the failure that also explains the *latency spikes* you
   described — a shared or contended disk gives you both symptoms at once. Confirm `dataLogDir` is on its
   own physical device.
3. **Network round-trip time and loss between ensemble members.** Third, not first, because it is the
   thing everybody suspects and rarely the cause. Rule it in or out with actual measurements between
   the members' addresses, not from your laptop.

Only after those three do you touch configuration. Raising `syncLimit` and `tickTime` widens your
patience, which stops the churn — but it also lengthens **detection time for a genuinely dead leader**,
so you are trading write availability during real failures for stability during false ones. Do it
knowingly, and only once you know which side of the inequality you actually moved.

---

## Rung 8 — Design

### 8.1 Coordination across three regions, 80 ms apart

**Membership.** Five voting members: **2 / 2 / 1** across the three regions. Quorum is 3, so no single
region holds a quorum by itself, and losing any one region leaves at least 3 voters alive. A 3 / 1 / 1
layout would be cheaper but reintroduces exercise 7.2's asymmetry — losing the region with three stops
everything.

**Observers.** Add one observer per region that holds application read traffic, so local reads never
cross a region boundary. Observers never widen the write path.

**Write latency — the number you must say out loud.** A commit needs acknowledgements from 3 of 5
voters, which necessarily includes at least one remote region. So **every write costs at least one
80 ms round trip**, plus an fsync, and in the worst case the leader's own region is the slow one. Budget
**100–200 ms per write** and design so that nothing on a user-facing path does one.

**Which operations pay it.** Only writes. Reads are local and free — and stale, which is fine for a
feature flag and *not* fine for lock ownership. So:

| Data | Where it lives | Why |
|---|---|---|
| Merchant → settlement region ownership | ZooKeeper, written rarely | Small, changes at failover, everyone must agree |
| Feature flags | ZooKeeper, read locally with watches | Staleness of a few hundred ms is harmless |
| Reconciliation lock | ZooKeeper ephemeral + **fencing token** | Correctness depends on the token, not the lock |
| Anything per-transaction | **Not ZooKeeper** | One leader, one write path, 80 ms — this is the mistake that kills coordination services |

**Region failure vs region partition.** A *failure* of the 1-voter region: quorum survives, nothing
notices. A *failure* of a 2-voter region: 3 voters remain, quorum survives, writes continue at higher
latency. A *partition* isolating one region: that region becomes read-only and stale, its clients'
sessions expire, and — crucially — **its ephemeral nodes disappear**, so any lock held there is released
and may be granted elsewhere. Design for that: locks must be fenced, and long-running jobs must tolerate
losing their lock mid-run.

**The reconciliation lock, avoiding 7.3.** Acquire an ephemeral sequential node; take its `czxid` as the
fencing token; pass the token into every settlement write; the settlement store rejects writes carrying a
token lower than the highest it has seen. The job must be built to be **stopped and resumed**, because
an 80 ms-apart three-region deployment will lose sessions occasionally, and that is not an incident.

**What to monitor:** leader elections per hour (the leading indicator); `zk_synced_followers` on the
leader (if it sits at exactly 3, you are one failure from a stop); cross-region RTT p99 against
`syncLimit × tickTime`; fsync latency; and, at the application layer, **fencing-token rejections** — a
non-zero rate there is your evidence that the lock alone would not have been enough.

**What survives from this lab's model at 80 ms and what does not.** The protocol survives entirely —
ZAB does not care about latency, only about timeouts being generous enough. What does **not** survive is
the *usage pattern*: at 2 ms you can afford a ZooKeeper write on a request path, and at 80 ms you cannot.
`tickTime`, `initLimit` and `syncLimit` must all be raised, and every one of those makes real failure
detection slower. That is the actual trade, and it is not the protocol's fault.

### 8.2 "Just use PostgreSQL"

**What it genuinely gives you.** A great deal, and it deserves a fair hearing. One less system to run,
patch, monitor and staff. Real transactions across multiple keys, which ZooKeeper does not offer beyond
`multi`. SQL for queries, existing backups, existing access control, a data model with no 1 MB limit.
For **leader election and simple locks**, `SELECT ... FOR UPDATE` or an advisory lock genuinely works,
and a table with a version column gives you fencing tokens directly. Many teams should do this.

**What it does not give you.**

- **Failure semantics.** A single Postgres instance is a single point of failure — exactly the thing the
  coordination layer exists to route around. With streaming replication and a failover controller, you
  now need something to decide *when to fail over*, which is the original problem again. (This is worth
  saying plainly: consensus does not disappear, it moves.)
- **Session-tied liveness.** ZooKeeper's ephemeral nodes tie lock ownership to a **live connection**, so
  a dead holder's lock is released automatically. A Postgres row does not know its owner died. Advisory
  locks tied to a connection get you closer, but connection pools break the connection–client identity
  that makes it work.
- **Watches.** Push notification on change. Replacing it with polling changes your latency and your load
  profile, and polling at ZooKeeper-like latencies against a database is expensive.
- **Read scale-out with local reads.** Every ZooKeeper server answers reads from memory; Postgres
  replicas can approximate this with their own staleness caveats.

**When it is the right call:** you already run Postgres with a mature HA story; your coordination needs
are locks and a small amount of config; your tolerance for a coordination-layer outage is the same as
your tolerance for a database outage (often true — if the database is down, the work stops anyway); and
you would rather have one well-understood system than two. That last point is a real engineering
argument and not a lazy one.

**When it is not:** you need dead-client detection without polling; you need many services watching for
changes; your coordination layer must survive failures your database does not; or — the decisive one —
**your database's own failover is itself decided by the thing you are proposing to replace.**

### 8.3 50 GB of configuration in ZooKeeper

**Why it fails.** The entire data tree is held **in memory on every server**, and every write goes
through a single leader. 50 GB means 50 GB of heap on all five machines, plus room for snapshots and
garbage collection.

**What breaks first, in order:**

1. **GC pauses.** Long before you run out of memory, a heap that size produces pauses longer than
   `syncLimit × tickTime` — so the servers start declaring each other dead. The cluster churns through
   elections and becomes unavailable. *This is the real answer to "what breaks first", and it is not
   memory exhaustion.*
2. **Snapshot time and size.** Every `snapCount` transactions ZooKeeper serialises the tree to disk. At
   50 GB that is minutes of IO, repeatedly.
3. **Server startup and rejoin.** A restarted server must load the snapshot and replay the log before it
   can serve. With `initLimit` at any sane value it will never finish in time, so it can never rejoin —
   the loop described in README section 11.5.
4. **The per-node limit.** `jute.maxbuffer` defaults to about 1 MB, so you cannot store large values in
   individual nodes anyway, and working around it with many small nodes multiplies the node count and
   the watch load.

**What should hold it instead.** An object store or a proper database — S3/GCS with versioned objects, or
a relational store — with a CDN or local cache in front of it for read volume. Configuration is large,
read-mostly, and does not need consensus.

**What should still live in ZooKeeper, and why:** the **pointer and the version**. A single small znode
holding *"the current configuration is version 4711, at this location, with this checksum"*. That is a
few hundred bytes, everyone must agree on it, it changes rarely, and clients can `watch` it to be told
the instant it changes. The heavy bytes live where bytes belong; the **one fact everyone must agree on**
lives where agreement lives.

This is the general pattern and it is worth naming: **coordination services store the pointer, not the
payload.**

### 8.4 Adding linearizable reads

**Mechanism 1 — route reads through the leader.** Every read becomes a request to the leader, which
answers from its own delivered state. *Cost:* destroys the read scalability that is ZooKeeper's whole
performance story, and makes the leader the bottleneck for reads as well as writes. *Not enough on its
own either:* a leader that has been partitioned away but has not yet noticed will happily serve a stale
read, so this needs a quorum check or a lease as well.

**Mechanism 2 — a quorum round per read (Raft's ReadIndex).** The server asks the leader for its current
commit position, the leader confirms with a quorum that it is still the leader, and the reader waits until
it has delivered up to that position. *Cost:* one round trip per read, but it can still be served locally
afterwards, and correctness does not depend on any clock.

**Mechanism 3 — a leader lease.** The leader holds a time-bounded lease and serves reads locally without
coordinating. *Cost:* correctness now depends on **bounded clock drift** between machines. Your `raft`
lab already recorded this trade — *a lease is a bet on bounded clock drift* — and `google-spanner` is the
lab where somebody bought special hardware to make that bet safe.

**What ZooKeeper actually offers: mechanism 2, as `sync()`.** A `sync()` is queued to the leader and
returns down the same ordered connection, so once it returns the follower has everything the leader had
committed when the call was issued. `sync()` followed by `getData()` is a linearizable read.

**Why it is opt-in rather than default.** Because most ZooKeeper reads do not need it, and making
everyone pay a round trip for the few that do would remove the reason to choose ZooKeeper at all. The
design decision is deliberate: **the fast, weak thing is the default, and the strong thing has a name you
have to type.** Whether that is the right default is a genuine question — etcd made the opposite choice —
but it is a choice, not an oversight.
