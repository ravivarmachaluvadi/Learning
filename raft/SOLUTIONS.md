# Raft — Solutions

Every measured number below was produced by running the lab. Where a claim was
checked by running a modified copy, the command is shown.

---

## Rung 1 — EXPLAIN

**1.1** Several machines must hold the same list of changes in the same order, so
that replaying the list anywhere gives the same answer. "Everyone writes down what
they hear" fails because two machines can be told different things at the same
moment and both write them into the same slot — and with no shared clock there is
no fact of the matter about which came first. Raft manufactures the missing
ordering by electing one machine to be the sole writer for a numbered stretch of
time.

**1.2** The correct version: **at most one leader per *term*, and only the leader
of the newest term can commit anything.** Two servers genuinely believe they are
leader at the same moment all the time — the visualization's partition scenario
shows it in three clicks. What that buys you is that Raft never has to *prevent*
split-brain; it only has to make the losing half **harmless**, which is far
cheaper. The old leader cannot reach a majority, so it can never commit, so
nothing it does can be observed.

**1.3** Because wall clocks disagree, drift, and jump — a machine with a clock 60
seconds fast would win every comparison and could overwrite genuinely newer
decisions. (The [`quorums-and-replication`](../quorums-and-replication/) lab
measured exactly that: under last-write-wins, one node 60 s fast silently
discarded **1000 of 1000** newer writes.) A term is a *logical* clock: it only
ever increases, it increases only when someone attempts leadership, and every
server that sees a higher one adopts it immediately. It orders *attempts*, which
is all Raft needs.

**1.4** Waiting for all five means one machine in a bad mood stops the shop — and
it will happen, because at any moment something is always restarting. Waiting for
one means two different machines can each be the "one" for two different writes,
and then nobody can say which happened. Three of five is the smallest number with
the property that **any two such groups must share a member**, which is what makes
"was this already decided?" answerable by asking any three machines. It is also a
tail-latency device: you wait for the third-fastest, never the slowest.

---

## Rung 2 — IDENTIFY

**2.1** Two independent signals: the role badge reads `leader` and the row's dot
is teal; and in the message band, the arcs *originating* from that row are solid
(outbound `AppendEntries`) while every other row only sends dashed replies. A
third: the verdict banner at the top names it.

**2.2** A dashed, outlined cell is written down but not committed; a solid filled
cell is committed. It becomes solid when the leader has learned that a **majority**
of servers hold it — and, if it was created in an earlier term, only once the
leader commits an entry of its *own* term above it.

**2.3** *Entries written down* ≥ *entries it knows are safe*, always. The follower
learns about the commit point on the **next** message after the one that carried
the entry, so it is always one round behind. It can never be the other way round
because `commitIndex = min(leaderCommit, lastLogIndex)` — a server refuses to
claim as safe anything it does not actually hold.

**2.4** (a) The old leader, still badged `leader` on the small side, and the new
leader on the big side. (b) The **term** number in each row's metadata. The higher
term is the one allowed to commit; the lower one will step down the instant the
two sides can talk again.

**2.5** The two pairs are `(candidate's last index, candidate's last term)` and
`(my last index, my last term)`. The **term** comparison decides it first; the
index is only consulted when the terms are equal. The event line reads e.g.
`S1 refuses: cand (idx 0, term 0) < mine (idx 1, term 1)`.

---

## Rung 3 — PREDICT

**3.1** A's `RequestVote` carries term 6 (it incremented 5 → 6). B and C are
already on term 6 and have very likely already voted in it — if so they refuse,
A's election fails, and A tries again at term 7, where it will succeed *if* its
log is up to date. The cluster ends on term 7 (or higher). The important part:
**A's stale term did not make it lose; a stale log would have.** This is the
"disruptive server" problem, and it is exactly what pre-vote (exercise 7.4)
prevents.

**3.2** **Not committed, and yes it can still be deleted.** This is Figure 8. Four
of five is a majority, but the entry is from term 5 while the leader is on term 7,
so the own-term rule forbids committing it. If the fifth server holds a *different*
entry at index 4 created in term 6, that server's log is more up to date, so it
can still win an election and overwrite the index on all four others. Measured with
the rule disabled (`java src/Experiments.java figure8`): 12 distinct safety
violations, including `index 1 was committed as OLD@t1 but S1 has committed
NEW@t2 there`.

**3.3** (a) yes — measured 30/30 at 50% loss and 26/30 at 70%; (b) mostly no —
only **7 of 30** runs committed their writes at 50%, and 0/30 at 70%; (c) **no
safety property breaks at any loss rate, including 90%.** Loss cannot corrupt
anything; it can only prevent heartbeats landing inside an election timeout, which
turns the cluster into a machine for holding elections.

**3.4** It depends entirely on **when the current leader was elected**, because
`nextIndex` is set optimistically to the leader's own log end *at that moment* and
only ever walks backwards. Measured (`java src/Experiments.java log-repair`):

- follower missed 6 entries, leader elected holding all 6 → `nextIndex = 7` → **6
  rejections**, 47 messages.
- follower holds 5 wrong entries, leader elected when its own log was short →
  `nextIndex = 2` → **0 rejections**, and all five wrong entries deleted by a
  single message.

The counter-intuitive half: being *behind* is expensive, being *wrong* can be free.

**3.5** **Either outcome is legal and neither is a bug.** The entry was never
committed, so Raft promised nothing about it. If the next leader happens to be a
server that stored it, it survives (and gets committed later); if not, it is
deleted. What *would* be a bug is the client treating its timeout as "the write did
not happen" — which is why every client of a Raft system must retry idempotently.
Same conclusion as [`transactional-outbox`](../transactional-outbox/): **a failed
write means "not confirmed", never "did not happen".**

---

## Rung 4 — MODIFY

**4.1** Every election test fails; safety tests all still pass. **Liveness** was
lost, safety untouched. Measured over 6000 ticks: term 40 reached, no leader ever
elected, 0 violations. This is the cleanest demonstration in the lab that the two
properties are independent.

**4.2** With `HEARTBEAT = 200` and `ELECTION_MIN = 150`, a follower's timer expires
before the next heartbeat arrives, so it starts an election against a perfectly
healthy leader — measured **48 terms burned in 10,000 ticks** versus 0 at
`HEARTBEAT = 100`. Rule of thumb from the paper:
`broadcastTime ≪ electionTimeout ≪ MTBF`; etcd ships a 10× margin (100 ms
heartbeat, 1000 ms election timeout).

**4.3** Nothing breaks — `duplicates_never_broke_a_safety_property` passes, and
`only-once` is applied exactly once on all five servers. Two mechanisms: **(1)
term filtering** — every handler drops a message whose term does not equal
`currentTerm`, so replies from old attempts are ignored; and **(2)
position-addressed writes** — `AppendEntries` says *"put these entries at these
indices"*, not *"append these"*, so re-delivering it writes the same content to
the same slots. Raft is idempotent by shape, not by a dedup table.

**4.4** Measured (`java Tests.java` with `majority() = peers.length / 2`): 8+
failures, first `the_winner_needed_a_majority_of_votes`, and the checker reports

```
LOG MATCHING broken: S0 and S2 both have term 1 at index 1, but differ at index 1
STATE MACHINE SAFETY broken: index 1 was committed as committed-write@t1 but S2 has committed overwrite@t1
```

The overlap argument dies immediately: with N=5, two groups of 2 can be
**disjoint** ({S0,S1} and {S2,S3}). Two candidates can each collect 2 votes in the
same term, so two leaders exist in one term; and a "committed" entry can sit on 2
servers that a future leader never has to talk to. Half **plus one** is not a
convention — it is the smallest number that forces intersection.

**4.5** **It breaks, and it is a liveness bug.** The own-term rule makes the set
of committable indices **non-contiguous**: an old-term entry at index 1 may have a
majority and still be forbidden, while the leader's own entry at index 2 is
allowed. An upward scan that stops at the first index failing the test stops at
index 1 forever and never reaches index 2. Reproduced directly:

```
upward scan   : leader term=2 log=[old@t1 new@t2] commitIndex=0   STALLED
downward scan : leader term=2 log=[old@t1 new@t2] commitIndex=2   committed
```

The downward scan is not a stylistic choice — it is what lets a new leader commit
its own entry and drag its predecessors' entries into the committed region
underneath it.

---

## Rung 5 — IMPLEMENT

```java
static boolean candidateLogIsUpToDate(int candLastIndex, int candLastTerm,
                                      int myLastIndex, int myLastTerm) {
    if (candLastTerm != myLastTerm) return candLastTerm > myLastTerm;
    return candLastIndex >= myLastIndex;
}

static boolean shouldGrantVote(int myTerm, int myVotedFor,
                               int candTerm, int candId, boolean candLogUpToDate) {
    if (candTerm < myTerm) return false;
    if (myVotedFor != -1 && myVotedFor != candId) return false;
    return candLogUpToDate;
}

static int highestCommittableIndex(int[] matchIndex, int[] logTerms,
                                   int currentTerm, int commitIndex) {
    int best = commitIndex;
    for (int n = logTerms.length - 1; n > commitIndex; n--) {
        if (logTerms[n] != currentTerm) continue;          // only your OWN term counts
        int count = 0;
        for (int m : matchIndex) if (m >= n) count++;
        if (count >= matchIndex.length / 2 + 1) { best = n; break; }
    }
    return best;
}
```

**5.1** Term is compared **first**. A short log ending in term 5 is more up to date
than a long log ending in term 4, because everything after term 4 superseded it —
the long log's extra entries were uncommitted work from a leader that has since
been replaced. Comparing length first would let stale-but-verbose logs win, and
would delete committed entries.

**5.2** Because a `RequestVote` can be **duplicated or retried**. If S1 voted for
S2 and the reply was lost, S2 retries; refusing the retry would waste an election
for no reason. Granting it is safe because it is the same vote, not a second one —
the rule is *one candidate per term*, not *one message per term*.

**5.3** It protects **State Machine Safety** via Figure 8: an entry from an older
term can reach a majority and *still* be overwritten later by a server holding a
higher-term entry at that index. Counting replicas is a valid test only for entries
the current leader created itself.

---

## Rung 6 — DEBUG

Exactly three tests fail: `equal_logs_are_up_to_date`,
`grants_again_to_the_SAME_candidate`, `does_not_commit_without_a_majority`.

| # | The wrong token | Cost | Property broken |
|---|---|---|---|
| 1 | `candLastIndex > myLastIndex` — should be `>=` | **liveness** | none, but no election can ever be won when logs are equal (i.e. always, on a healthy cluster) |
| 2 | `if (myVotedFor != -1) return false` — missing `&& myVotedFor != candId` | **liveness** | none; it refuses legitimate retries and wastes elections |
| 3 | `count >= matchIndex.length / 2` — should be `/ 2 + 1` | **safety** | Election Safety and State Machine Safety: two disjoint "majorities" of 2 in a 5-server cluster |

**6.4** **Bug 3.** Bugs 1 and 2 announce themselves the moment you start the
cluster — elections stop working. Bug 3 is invisible while every server is healthy
and caught up, because when all five hold index N, `count = 5` satisfies both
`>= 2` and `>= 3`. It only does damage when servers are behind or partitioned —
i.e. during the exact incident when you most need the guarantee. **A safety bug
that is quiet on the happy path is the worst kind:** it ships, passes every test,
and destroys data during your first real failure.

**6.5** Cluster-scale results, one flag at a time (`java src/Tests.java`):

| Flag | First failing test / signal | Guarantee lost |
|---|---|---|
| `fixedTimeout` | no leader in 6000 ticks; term 40 | liveness only — 0 violations |
| `voteTwice` (with `fixedTimeout`) | `ELECTION SAFETY broken: term 1 has 5 leaders at once` | Election Safety |
| `noUpToDateCheck` | `STATE MACHINE SAFETY broken: index 1 was committed as committed-write@t1 but S2 has committed overwrite@t2` | Leader Completeness → State Machine Safety |
| `commitAnyTerm` | 12 violations in the Figure 8 history | State Machine Safety |
| `noLogMatchCheck` | `S2 log=[d@t2]` where the leader has 6 entries | Log Matching |

---

## Rung 7 — HANDLE FAILURE (worked discussion, not one right answer)

**7.1 — Safe retries.** Add `(clientId, seqNo)` to `Entry`. The state machine keeps
a small map `clientId -> (lastSeqNo, lastResult)`. On applying an entry, if
`seqNo <= lastSeqNo` for that client, **do not re-apply** — return the stored
result. The crucial dependency: this works only because **every replica applies
the same log in the same order**, so every replica's dedup table is identical, so
a retry that lands on a different leader after a failover gets the same answer. In
other words State Machine Safety is what makes exactly-once *possible*; the
session table is what makes it *happen*. Note the dedup table has to be part of the
replicated state machine (and therefore part of snapshots), not a cache beside it.

**7.2 — No-op on election.** In `checkWon`, append `new Entry(currentTerm,
"<no-op>")` before the first heartbeat. Now the leader has an entry of its own
term immediately, so as soon as a majority stores it, the own-term rule fires and
**everything below it commits at once** — including all inherited entries. Without
it, a new leader that receives no client traffic can sit indefinitely with
uncommitted inherited entries, and `Tests.noOpLessonTest` shows the follower
divergence that persists in the meantime. It cannot simply mark them committed
directly: that *is* the Figure 8 bug.

**7.3 — Faster repair.** Extend `AppendReply` with `conflictTerm` and
`conflictIndex`. On rejection the follower reports the term of the entry it has at
`prevLogIndex` and the first index it holds for that term. The leader then either
jumps to its own last index for `conflictTerm`, or straight to `conflictIndex`.
Case A's six rejections become one or two. This is section 5.3 of the paper, where
Ongaro notes the optimisation is probably unnecessary in practice because failures
are rare — it matters most for a follower rejoining after a long absence, before
snapshots kick in.

**7.4 — Pre-vote.** Reproduce it by partitioning one server off, letting it burn
terms, then healing: its inflated term deposes a healthy leader, costing an
election for no reason. Pre-vote adds a round asking *"would you vote for me if I
started an election?"* **without** incrementing the term. Voters answer no if they
have heard from a leader recently. Only a majority of yes answers triggers a real
election. etcd, TiKV and CockroachDB all ship this on by default.

**7.5 — Correct reads.** First show the bug: put the leader alone in a minority
partition and read from it — it still believes it is leader and happily returns
data that the majority has already moved past. Two fixes:

- **ReadIndex**: record `commitIndex`, send one round of heartbeats, wait for a
  majority to confirm you are still leader, wait until `lastApplied >= that index`,
  then read. Costs one round trip; assumes nothing about clocks.
- **Lease read**: after a successful heartbeat round, assume leadership for
  `electionTimeout - clockDrift`. Costs nothing; assumes **bounded clock drift**,
  which is a real assumption that has caused real incidents.

The choice is the whole trade: a round trip, or a belief about clocks.

---

## Rung 8 — DESIGN (discussion, no single right answer)

**8.1 — Three datacentres, 50 ms apart.**

- **Five servers, 2 + 2 + 1** across London / Frankfurt / Dublin. Not 3-in-one-DC:
  that survives no datacentre loss at all. Not 1+1+1: it survives one DC, but every
  single write must cross the ocean, and any one DC loss leaves you one failure
  from a dead cluster. 2+2+1 tolerates a whole DC *and* a machine.
- **Write latency ≈ 50 ms**, and the reason matters: a commit needs 3 of 5, and the
  leader plus its local partner are only 2 — so **every write waits for one
  cross-DC round trip**, plus the leader's `fsync`. You cannot engineer this away;
  it is the speed of light.
- **Dublin cut off:** nothing happens. Dublin's single server is a minority; the
  other four carry on. Dublin's clients must be routed elsewhere.
- **London cut off (holding the leader):** Frankfurt + Dublin have 3 of 5 and
  elect a new leader after one election timeout. London's two servers keep
  believing, commit nothing, and step down on reconnect. **Set the election timeout
  well above 50 ms** — a 150 ms timeout across a 50 ms link will re-elect on every
  hiccup.
- **Survives unchanged:** every safety rule, the message shapes, the failure
  behaviour. **Must change:** persistence with real `fsync`, snapshots, membership
  changes, batching, correct reads, timeouts tuned to WAN latency, pre-vote, and
  mutual TLS between DCs.
- **Dashboard:** leader elections per minute (page on sustained > 0), fsync p99,
  commit-index lag per follower, proposal latency p99, quota usage. **Page someone
  at 3am for:** sustained elections, or quorum lost.

**8.2 — What belongs in Raft.**

| | Verdict |
|---|---|
| (a) shard ownership | **Yes.** Small, must be exactly one answer, read constantly. This is etcd's core use case. |
| (b) ledger at 40k writes/sec | **No.** One leader, one majority round trip per write — you would be throughput-bound and latency-bound. Use a partitioned log or database; if you need consensus, use **many** Raft groups (one per shard), not one. |
| (c) end-of-day settlement lock | **Yes**, with care — a lock needs a lease with a fencing token, because a leaseholder can be paused by GC and wake up believing it still holds the lock. Raft gives you the token; it does not stop the stale holder acting. |
| (d) session tokens | **No.** High volume, per-user, and staleness is harmless. Redis or a signed token. |
| (e) feature flags | **Yes** if flips must be instant and globally consistent; otherwise a config service with eventual propagation is cheaper — see [`gossip-protocols`](../gossip-protocols/). |
| (f) idempotency keys | **Depends on grain.** Inside one shard's state machine, they belong in that shard's Raft group. A global idempotency store at payment volume does not — put them in the same store as the payment, so the key and the effect commit together ([`transactional-outbox`](../transactional-outbox/)). |

**8.3 — 40 elections/minute, nothing crashed, network fine.**

1. **fsync / WAL write latency on the leader.** This is the most likely cause and
   it is not a network problem at all. Raft must durably store `currentTerm`,
   `votedFor` and new entries *before* replying; a slow or contended disk delays
   heartbeats exactly as if the network were slow. A noisy neighbour on the same
   volume is the classic cause.
2. **Timeout configuration** — heartbeat raised without raising the election
   timeout, or defaults tuned for a LAN running across a WAN.
3. **A disruptive rejoining server** with an inflated term, if pre-vote is off.
4. **Database size / quota** — a cluster at its backend quota elects fine and
   commits nothing.
5. **CPU starvation or GC pauses** on the leader — same symptom as slow disk.

The lesson from `java src/Experiments.java loss` is what tells you where *not* to
look: election churn is always a **timing** problem, never a correctness one. Your
data is fine. Something is late.

**8.4 — Raft lock service vs `SELECT FOR UPDATE`.**

*For:* it is one line of SQL against a database you already run, back up, monitor
and understand. It is genuinely correct for mutual exclusion while the connection
lives. Most teams do not need distributed locks; they need a mutex, and Postgres is
a fine mutex. You avoid operating a second stateful system.

*Against:* your Postgres is a single point of failure and its failover is *not*
consensus — a primary/replica promotion under a partition can produce two writable
primaries, and then two holders of your "exclusive" lock. Lock lifetime is tied to
a TCP connection, so a hung client holds it until a TCP timeout you do not control.
And there is no fencing token, so a paused holder can wake and act.

*What I would do:* if you already run etcd (and if you run Kubernetes, you do), use
it — you get leases and fencing tokens for free, with no new system. If you do not,
use Postgres advisory locks with an explicit expiry and a **fencing token** the
downstream resource checks, and write down that your lock's safety is bounded by
your Postgres failover story.

**8.5 — Payments: where Raft stops.**

Crash points between "record the payment" and "call the card network":

1. **After the client sends, before the leader appends** — nothing recorded, client
   times out. Retry must be idempotent.
2. **After the leader appends, before a majority stores it** — may or may not
   survive. *Raft does not tell you which.*
3. **After commit, before the client is told** — recorded, client saw a timeout.
   **The dangerous one:** the client will retry, and without an idempotency key it
   pays twice.
4. **After commit, before the external call** — recorded but not executed. Needs a
   durable outbox and a worker, not Raft.
5. **External call made, response lost** — the card network's problem *and* yours:
   you must reconcile, using their idempotency key.
6. **External call succeeded, before recording the result** — recorded as pending
   forever until reconciliation.

**Raft fixes exactly one thing: 2 and 3 stop being ambiguous *internally*** — once
committed, every replica agrees the payment exists, and a failover cannot lose it.
It fixes **nothing** about 1, 4, 5 or 6, because those cross a boundary Raft cannot
see. The two-part answer is: Raft for the internal ordering, a
[`transactional-outbox`](../transactional-outbox/) for the hand-off to the outside
world, an idempotency key end to end, and a [`saga`](../saga/)-style compensation
for what cannot be undone. **Consensus makes your replicas agree; it does not make
the world agree with you.**
