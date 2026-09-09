# ZAB — Exercises

Work through these with the **[Epoch Ledger simulator](https://claude.ai/code/artifact/06a418b4-e42b-4491-9fb6-ef72c3e0d4c3)**
open beside you (or `visualization.html` in this directory).

Answers and worked design discussions: **[SOLUTIONS.md](SOLUTIONS.md)**. Try each one before looking —
the rungs from 3 onwards are much less useful if you read the answer first.

Nothing here asks you to write code. Everything is pencil, paper, and the simulator.

---

## Rung 1 — Explain

**1.1** In your own words, in no more than four sentences and using no protocol vocabulary at all
(no *zxid*, no *epoch*, no *quorum*): what problem does ZAB solve, and what goes wrong without it?

**1.2** Someone says: *"ZAB is basically Raft with different message names."* Give the one sentence
that shows this is wrong, then a second sentence explaining why that difference forces ZAB to do
something Raft does not have to.

**1.3** Why does the ZooKeeper leader broadcast *"ticket = 44"* rather than *"increment the ticket
number"*? Give two distinct benefits, one about correctness and one about what it makes possible during
recovery.

---

## Rung 2 — Identify

Open the simulator and run **scenario 1 · One write, from start to finish**. Pause partway through.

**2.1** Which server is the primary? What is its epoch? Click it and read off its `acceptedEpoch` and
`currentEpoch` — are they the same, and why?

**2.2** Point at a cell with a dashed outline. What does that server know about that change, and what
is it *not* allowed to do with it?

**2.3** How many acknowledgements did the primary wait for before the customer was told? How many
servers are there? State the relationship as a formula.

**2.4** Run **scenario 3** to the end. One cell is hatched red. Which change is it, which server had
it, and what single sentence justifies deleting it?

**2.5** In scenario 3's final state, one server shows a change with a `?` in the inspector. Which
server, and why is that change still un-delivered there when everyone else has moved on?

---

## Rung 3 — Predict

Answer each in writing *before* running it.

**3.1** Five servers. S5 is primary at epoch 2, and all five have delivered up to `2.4`. S5 proposes
`2.5`; S1 and S2 store it; then S5 dies. Which server can win the next election, and what happens to
`2.5`? Give the reasoning, not just the outcome.

**3.2** Same setup, but only S1 stores `2.5` before S5 dies, and S1 also dies immediately afterwards.
S2, S3 and S4 elect a new primary. What is the zxid of the new primary's first transaction? Then S1
comes back — describe exactly what happens to its copy of `2.5`.

**3.3** A server has `acceptedEpoch = 7` and `currentEpoch = 5`. What does that tell you about what
happened to it recently? What will it report during the next election, and what will it refuse?

**3.4** The primary is at epoch 3 and has just committed `3.9`. A client connected to a follower calls
`getData()` at that instant. List every value the client might legitimately be returned, and say which
of them indicate a bug.

**3.5** A network tear puts 2 servers on one side and 3 on the other, with the primary among the 2.
Describe what each side does over the next thirty seconds. Check against **scenario 6**.

---

## Rung 4 — Modify

Each of these is a switch in the simulator's **Change the rules** panel. For each: predict what breaks,
run it, then name **which of the four promises** (P1–P4 from README section 5) is violated and whether
what you lost was *safety* or *liveness*.

**4.1** Turn on *"Elect the lowest-numbered server instead of the most up-to-date one"* and run
**scenario 5**. Why can the new primary not simply ask the others for what it is missing?

**4.2** Turn on *"Let a new primary start writing before republishing its predecessor's changes"* and
run **scenario 4**. The duplicate appears in the *second* epoch, never the first. Why is the first
epoch immune?

**4.3** Turn on *"Never delete a change"* and run **scenario 8**. In that run the divergence only
becomes visible after one more write happens. Explain why the damage is invisible until then, and why
that makes this class of bug so dangerous in production.

**4.4** Turn on *"Accept proposals from an old epoch"* and run **scenario 9**. The stale change is
inserted *underneath* a change that was already final. Name the two promises that break at that moment.

**4.5** Turn on *"Let 2 of 5 servers be enough"* while a primary is running, then tear the network 2|3
and write on both sides. What does the invariant panel report, and what is the counting argument that
`⌊N/2⌋+1` satisfies and `2` does not?

**4.6** Set message loss to 40% and let it run for a while. Does any invariant chip ever turn red?
What *does* degrade? State the general principle this illustrates in one sentence — you have measured
the same thing in a previous lab.

---

## Rung 5 — Trace

Do these on paper first, then check.

**5.1** Starting state — five servers, quorum 3:

| Server | history | delivered | acceptedEpoch | currentEpoch |
|---|---|---|---|---|
| S1 | `1.1, 1.2, 1.3` | `1.2` | 1 | 1 |
| S2 | `1.1, 1.2` | `1.2` | 1 | 1 |
| S3 | `1.1, 1.2` | `1.2` | 1 | 1 |
| S4 | `1.1, 1.2, 1.3, 1.4` | `1.2` | 1 | 1 |
| S5 | `1.1` | `1.1` | 1 | 1 |

The primary (now dead) was S2. Trace the recovery: who wins the election and why; what `newEpoch` is;
what sync message each of S1, S2, S3, S5 receives and with what contents; what is delivered at the end
of the NEWLEADER round; and the zxid of the new primary's first client write.

**5.2** Same starting state, but S4 is **down** and does not participate. Redo the trace. What happens
to `1.4` — and is that a violation? Justify your answer with the promise it does or does not break.

**5.3** Now S4 comes back after the epoch-2 primary has committed `2.1` and `2.2`. Write down exactly
what S4 reports, what the leader decides to send it, and what S4's history looks like afterwards.

---

## Rung 6 — Spot the flaw

Each of these is a described protocol variant with one deliberate change. For each: construct a
concrete run (real zxids, real server names) where it produces a wrong answer, and name the ZAB property
from README section 12 that it violates.

**6.1** *"To save a round trip, the new primary skips the NEWLEADER acknowledgement round. It sends its
history to every follower, does not wait for any reply, delivers its own history immediately, and starts
accepting writes."*

**6.2** *"`acceptedEpoch` is kept in memory only. It is rebuilt on restart from the highest epoch in the
server's transaction log, which is much cheaper than an extra fsync."*

**6.3** *"When a follower detects a gap in the proposals it is receiving, it asks the leader to resend
the missing zxid rather than dropping the connection and re-synchronising."*

**6.4** *"A follower acknowledges a proposal as soon as it is in the OS page cache, and fsyncs in the
background. Durability is preserved because a majority of servers are unlikely to lose power at once."*

**6.5** *"During synchronisation the leader compares its history to each follower's and, where the
follower has a transaction the leader lacks, the leader adopts it — after all, that data is real and
throwing it away is wasteful."*

---

## Rung 7 — Handle failure

Extend the design on paper.

**7.1** A client sends a write, gets no response because the primary died, and retries against the new
primary. Show a concrete run where the naive retry issues the ticket **twice**. Then say what the server
side must add so that the retry is harmless — and identify which existing ZooKeeper mechanism already
provides most of it.

**7.2** You are running ZooKeeper across two datacentres, three servers in DC-A and two in DC-B, because
"that way we survive losing either one". Show, concretely, why this does not do what the sentence claims.
Then give two different designs that do better, and state exactly what each one costs.

**7.3** A ZooKeeper-based lock: a client creates an ephemeral sequential node, sees it has the lowest
number, and starts writing to a shared database. The client then freezes for 60 seconds (a GC pause) —
longer than its session timeout. Walk through what ZooKeeper does, what the client believes, and what
the database sees. Then fix it, and state what the fix requires of the database.

**7.4** Your ensemble is losing its leader roughly every ninety seconds. Writes mostly succeed but
latency is spiky. Give the diagnostic order you would work in — what you look at first, second, third —
and the reasoning for that order. Name the inequality you are testing.

---

## Rung 8 — Design

Longer, open-ended. The point is the reasoning and the trade-offs, not a single right answer.

**8.1** Design the coordination layer for a payments platform running in **three regions**, 80 ms apart,
that must keep working when any one region is lost. It stores: which region owns each merchant's
settlement processing, feature flags, and a distributed lock for the nightly reconciliation job.

Address: how many voting members and where; where observers go if any; what the write latency will
actually be and which operations pay it; what happens during a region failure and during a region
*partition*; how the reconciliation lock avoids the 7.3 problem; and what you monitor.

State clearly which parts of this lab's design survive at 80 ms and which do not.

**8.2** Your team proposes replacing ZooKeeper with a small service backed by PostgreSQL — "a table with
a row per key, `SELECT ... FOR UPDATE` for locks, it's simpler and we already run Postgres". Write the
technical response: what this genuinely does give you, what it does not, and under exactly which
conditions it would be the right call. Be fair to it — there are real cases where it wins.

**8.3** ZooKeeper's data must fit in memory on every server and every write goes through one leader.
You have been asked to store 50 GB of application configuration in it. Explain why this fails, what
actually breaks first, and design what should hold that data instead — including what small piece
*should* still live in ZooKeeper and why.

**8.4** Read the ZAB paper's property list (README section 12) and ask: *what would it take to add
read-your-writes-across-clients — that is, linearizable reads — to this system?* Give at least two
mechanisms, with their costs, and say which one ZooKeeper actually offers and why it is opt-in rather
than default.
