# Raft — Exercises

Eight rungs, easiest first. Do them in order; each assumes the one below it.
Answers in [`SOLUTIONS.md`](SOLUTIONS.md) — try first, being wrong is the
mechanism.

Everything runs with no build tool:

```bash
java src/Tests.java
java src/Experiments.java <name>
java src/ImplExerciseTest.java
java src/DebugMeTest.java
open visualization.html
```

---

## Rung 1 — EXPLAIN (no notation, no jargon)

**1.1** In three sentences, to someone who has never heard of consensus: what
problem does Raft solve, and why can't you solve it by having every machine write
down whatever it hears?

**1.2** A colleague says: *"Raft guarantees there is only ever one leader."*
That sentence is wrong in an important way. Correct it in one sentence, and say
what the correct version buys you.

**1.3** Why is the *term* number the only clock Raft has, and what would go wrong
if servers compared wall-clock timestamps instead?

**1.4** Explain, without using the words *commit* or *quorum*, why waiting for
**three of five** servers is better than waiting for **all five**, and better than
waiting for **one**.

---

## Rung 2 — IDENTIFY (open the visualization)

Open [`visualization.html`](visualization.html) and press **Play** until a leader
exists, then **Send a write to the leader** twice.

**2.1** Which row is the leader, and what exactly on the screen tells you? Name
two independent signals.

**2.2** Point at a cell that is *written down but not yet safe*. How is it drawn
differently from a safe one, and what would have to happen for it to become safe?

**2.3** Click a follower row. Its inspector shows *entries written down* and
*entries it knows are safe*. Why are those two numbers usually different, and
which one is bigger? Why can it never be the other way round?

**2.4** Tear the network between **S1 and S2**, wait for both sides to settle,
then find on screen: (a) two servers that both believe they are leader, (b) the
number that proves which of them is allowed to commit.

**2.5** In the *What just happened* log, find a line where a server **refused** a
vote. Read the technical line under it. Which two pairs of numbers were compared,
and which comparison decided it?

---

## Rung 3 — PREDICT (commit to an answer, then run the command)

**3.1** Server A has term 5. Servers B and C have term 6. A's election timer fires
first and it asks B and C for votes. What happens, and what term is the cluster on
afterwards?
*Check:* reason it out from `RaftNode.onRequestVote`, then watch a real one in
`java src/Experiments.java election`.

**3.2** A five-server cluster has an entry at index 4 that four of the five
servers hold. The leader is on term 7; the entry was created in term 5. Is the
entry committed? Can it still be deleted?
*Check:* `java src/Experiments.java figure8`

**3.3** You set the message drop rate to 60%. Predict, before running: (a) will a
leader be elected? (b) will writes commit? (c) will any safety property break?
*Check:* `java src/Experiments.java loss`

**3.4** A follower has been offline for 200 entries. It comes back. How many
round trips does the leader need before the follower is correct — and does the
answer depend on *when* the current leader was elected?
*Check:* `java src/Experiments.java log-repair`

**3.5** The leader crashes one microsecond **after** a follower stored an entry
but **before** the leader marked it committed. The client got no answer. Was the
write kept or lost — and is either answer a bug?

---

## Rung 4 — MODIFY (change one thing, name what broke)

**4.1** In `src/RaftNode.java`, set `ELECTION_MIN = ELECTION_MAX = 150`, so every
server uses an identical timeout. Run `java src/Tests.java`. Which tests fail?
Was **safety** or **liveness** lost? Restore it afterwards.

**4.2** Set `HEARTBEAT = 200` (above `ELECTION_MIN`). Run
`java src/Experiments.java heartbeat`. Explain the churn in one sentence, and
give the rule of thumb that prevents it.

**4.3** In `Cluster`, set `net.duplicateRate = 1.0` inside a test so **every**
message is delivered twice. Run `java src/Tests.java`. Nothing should break.
Name the two mechanisms that make Raft naturally idempotent.

**4.4** Change `RaftNode.majority()` to return `peers.length / 2` (half, not half
plus one). Run `java src/Tests.java`. Which safety property fails first, and why
does the *overlap* argument stop working?

**4.5** In `advanceCommit`, change the loop to scan **upwards** from
`commitIndex + 1` and stop at the first index without a majority, instead of
downwards from the end. Does anything break? Is this a bug, an optimisation, or
neither?

---

## Rung 5 — IMPLEMENT

Open [`src/ImplExercise.java`](src/ImplExercise.java). Three methods, three of
Raft's rules, all stubbed out.

```bash
java src/ImplExerciseTest.java     # 8 of 16 fail at the start
```

**5.1** `candidateLogIsUpToDate` — compare last (term, index). Which is compared
first, and why does that order matter?

**5.2** `shouldGrantVote` — the one-vote-per-term rule. Watch out for
`grants_again_to_the_SAME_candidate`: why must a repeat request from the *same*
candidate still be granted?

**5.3** `highestCommittableIndex` — a majority *and* the leader's own term. Make
`FIGURE_8_refuses_to_commit_an_OLD_term_entry_on_its_own` pass, then explain in
one sentence what it is protecting.

All 16 must pass.

---

## Rung 6 — DEBUG

[`src/DebugMe.java`](src/DebugMe.java) contains three subtly wrong rules — one per
method. Each compiles. Each looks reasonable.

```bash
java src/DebugMeTest.java          # exactly 3 of 16 fail
```

For each failure answer three questions **before** fixing it:

1. Which single character or token is wrong?
2. Does it cost **safety** (something wrong happens) or **liveness** (nothing
   happens)?
3. Which named Raft property does it break?

**6.4** Harder: one of the three bugs is completely **invisible** as long as no
server is ever slow, behind or unreachable — it passes every test you would write
on a healthy cluster, and only does damage during a failure. Which one, and why
does that make it the most dangerous of the three to ship?

**6.5** Now do it at cluster scale. In `src/Bugs.java`, turn on one flag at a
time, run `java src/Tests.java`, and for each flag write down: the first test that
fails, the violation message the checker prints, and the guarantee that was lost.

---

## Rung 7 — HANDLE FAILURE (extend the system)

**7.1 — Make client retries safe.** A client sends `transfer £500`, gets a
timeout, and retries. Today the lab would append it twice. Add a client session id
and a sequence number to `Entry`, and make the state machine remember the last
result per session so a retry is *answered*, not *re-applied*. Which Raft property
does this rely on to work at all?

**7.2 — Commit a no-op on election.** Make a new leader append an empty entry
immediately on winning (`checkWon`). Write a test showing that the previous
term's entries become committed *sooner* as a result, and explain why a leader
cannot simply mark them committed directly.

**7.3 — Faster log repair.** Today a rejection costs one index per round trip.
Add the conflicting **term** and the follower's first index for that term to
`Msg.AppendReply`, and use it in `onAppendReply` to skip a whole term per round
trip. Verify with `java src/Experiments.java log-repair` that Case A's *6
rejections* drops, and that `java src/Tests.java` still passes 56/56.

**7.4 — Pre-vote.** A server that was partitioned away returns with an inflated
term and deposes a perfectly healthy leader. Reproduce it: partition one server
off, let it burn 20 terms, then heal. Then implement pre-vote — a trial round that
does *not* increment the term — and show the healthy leader survives.

**7.5 — Make reads correct.** Add a `read()` that must never return stale data.
Show first that the naive version (read the leader's state machine) *can* be
stale: put the leader in a minority partition and read from it. Then fix it with
either ReadIndex or a lease, and say what each one assumes about clocks.

---

## Rung 8 — DESIGN (no single right answer)

**8.1 — Three datacentres, 50 ms apart.** Design the configuration store for a
payments platform running in London, Frankfurt and Dublin, 50 ms round trip
between them. Answer concretely:

- How many Raft servers, in which datacentres, and why not 3-in-one-DC?
- What is the write latency, and where exactly does it come from?
- What happens when Dublin is cut off? When London (holding the leader) is?
- Which of this lab's design elements survive unchanged, and which must change?
- What do you put on the dashboard, and what pages someone at 3am?

**8.2 — What belongs in Raft and what does not.** Your platform needs all of
these replicated. For each, say whether it belongs in a Raft group, and if not,
what it should use instead: (a) which service owns which shard; (b) the
transaction ledger, 40k writes/sec; (c) the distributed lock for end-of-day
settlement; (d) user session tokens; (e) the feature-flag config; (f) idempotency
keys for payment requests.

**8.3 — The 3am question.** Your etcd cluster is showing 40 leader elections per
minute. Writes are timing out. No server has crashed and the network team says the
network is fine. List, in order, what you check and why — and name the single most
likely cause that has nothing to do with the network.

**8.4 — Argue the other side.** Someone proposes replacing your Raft-backed lock
service with "a Postgres row and `SELECT FOR UPDATE`". Make the strongest case
*for* their proposal, then the strongest case against, then say what you would
actually do.

**8.5 — Payments specifics.** A payment must be recorded exactly once. Your
service writes to a Raft group and then calls an external card network.
Enumerate every point where a crash can leave the two out of step, and say which
of them Raft fixes for you and which it cannot. (The
[`transactional-outbox`](../48-transactional-outbox/) and [`saga`](../47-saga/) labs are
the other half of this answer.)
