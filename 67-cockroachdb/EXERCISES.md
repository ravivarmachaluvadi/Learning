# CockroachDB — Exercises

Twenty-four exercises on the eight-rung ladder. Everything here is pencil-and-paper or
done in the simulator — there is nothing to install and no code to write.

Work in order: each rung uses the previous rung's answer. Answers are in
[SOLUTIONS.md](SOLUTIONS.md); attempt first, because several of the exercises are designed
so that the *wrong* first answer is the instructive one.

Throughout, "the simulator" means
**[The Reader Pays](https://claude.ai/code/artifact/2119fede-4cbd-44ca-9e82-b4583323640b)**
(or `visualization.html` in this directory), and the **defaults** are: Mumbai +180 ms,
Frankfurt 0, Ohio −120 ms, `max_offset` 500 ms, cross-region round trip 60 ms, and all
four switches on.

---

## Rung 1 — Explain

No notation, no jargon you have not defined in the same breath. Imagine you are explaining
to a competent backend engineer who has never heard of any of this.

**E1.1** In four or five sentences, explain why a database spread over three cities has a
problem that the same database on one machine does not — *without* mentioning networks,
message loss, or partitions. Then say what CockroachDB's one big idea is for solving it.

**E1.2** Spanner and CockroachDB both give you serializable transactions across machines.
Explain the difference between them to someone choosing between the two, in a way that
makes clear *who pays* and *when*. Then say one workload where each is clearly the better
choice, and why.

**E1.3** A colleague says: "We're getting SQLSTATE 40001 errors in production. Something is
broken — let's file a bug with Cockroach Labs." Explain, in plain language, why they are
mistaken about the diagnosis, what those errors actually mean, and what you would look at
to decide whether anything is in fact wrong.

---

## Rung 2 — Identify

Point at the pieces. Open the simulator for these.

**E2.1** Run **scenario 1** to the end at the defaults. From the screen alone, name:
(a) which node holds the lease for `/accounts/n–z`; (b) how many rounds of consensus the
transfer needed; (c) the exact timestamp both new versions carry; (d) at which step the
transaction became committed, and at which *later* step anything first said COMMITTED.

**E2.2** Run **scenario 3** to the end at the defaults. On the timeline, identify: (a) the
dashed line with the steepest right-hand lean, and what its number means; (b) the amber
band's left edge before and after the restart, and its right edge before and after;
(c) the colour of the `SETTLED` dot at the final step, and what that colour means.

**E2.3** In **scenario 2**, pause at the step titled *"Frankfurt dies"*. Using the
transaction inspector, write down T1's status, its in-flight write list, and its commit
timestamp. Then answer: at that instant, is the money moved? What single fact on the screen
determines the answer?

---

## Rung 3 — Predict

Commit to an answer in writing before you run anything.

**E3.1** A transaction begins on Frankfurt at read timestamp 1000 with `max_offset` 500. It
reads three keys and finds committed versions stamped **860**, **1340** and **1780**. For
each: visible, ambiguous, or invisible? For any ambiguous one, give the transaction's read
timestamp and uncertainty ceiling immediately after the restart.

**E3.2** Same transaction as E3.1, but before reading the key stamped 1340 it had already
contacted that key's leaseholder once, at a moment when that node's clock read **1500**.
Does the classification of 1340 change? What if the node's clock had read **1200** at that
first contact? Explain both answers in one sentence each.

**E3.3** In the simulator, set Mumbai's clock to **+900 ms**, leave Ohio at −120 ms, leave
`max_offset` at 500 ms, and switch **off** *machines shut themselves down*. Before running
**scenario 3**: does Ben see SETTLED or PENDING, and how many uncertainty restarts occur?
Now compute, using the condition in stage 11.3, the smallest whole-millisecond value of
Mumbai's offset at which the answer changes. Check both against the simulator.

---

## Rung 4 — Modify

Change one thing and say precisely what breaks. For each, name whether the property lost
is **safety** (something wrong becomes observable) or **liveness** (progress stops or
slows).

**E4.1** Turn off *re-check reads when pushed* and run **scenario 5**. What is the final
state of the two doctors' rows? Name the anomaly, name the isolation level CockroachDB has
been reduced to, and say which of safety or liveness was lost. Then answer a harder
question: the two transactions wrote *different* rows and neither overwrote anything the
other wrote — so what exactly is the conflict that read refreshing detects?

**E4.2** Turn off *remember each machine's clock* and run **scenario 4**. Report the restart
count with the switch on and off, and the value the report reads for `/payments/p2` in each
case. Both answers are correct. Explain how two different answers can both be serializable,
and say what this tells you about what an optimisation is allowed to change.

**E4.3** Set `max_offset` to its maximum of 900 ms and run **scenario 8**. Does the causal
reverse still occur? Now set it to 0 and run again. Explain what `max_offset` is actually
doing to the anomaly, and why raising it is not a general fix for the gap between
serializable and strictly serializable.

---

## Rung 5 — Trace

Execute the rules by hand, one line at a time, then check against the simulator or the
document.

**E5.1** Two ranges, leaseholders 60 ms apart, parallel commits **on**. A transaction on
the near node reads one local key (1 ms) and one remote key (60 ms), then writes both.
Write the trace line by line with true times, starting at t = 0, and give the total time
the client waits. Then do it again with parallel commits **off**. Finally, generalise:
for a transaction touching *n* ranges spread across regions, how does the client's wait
scale in each case, and what does *n* actually change?

**E5.2** Trace the write-skew scenario by hand with the clocks *swapped*: Frankfurt at
−120 ms and Ohio at +180 ms, everything else at the defaults. Give each transaction's read
timestamp, which one is pushed, what the push target is, which read refresh runs, and which
transaction receives 40001. Then state the general rule your trace demonstrates about
*which* of two conflicting transactions gets refused.

**E5.3** A transaction reads keys on all three nodes while all three are being written to
continuously. Bound the number of uncertainty restarts it can suffer, and prove the bound
from the two brakes in stage 10.4. Then construct the worst case: what pattern of writes
and reads actually achieves the bound?

---

## Rung 6 — Spot the flaw

Each of these describes a plausible variant of the real design with one rule removed or
changed. For each: construct a concrete run with real timestamps where it gives a wrong
answer, and name the guarantee violated.

**E6.1** *"The uncertainty ceiling should move up with the read timestamp. If we restart at
1180, our new window should be (1180, 1680] — after all, we now believe it is later than we
did, so we should be sceptical about a later range too."* Show why this is worse than the
real rule, with a concrete run.

**E6.2** *"Recovery of a `STAGING` transaction is simple: check the listed keys, and if any
intent is missing, mark the record ABORTED and remove the rest."* One step of the real
procedure has been dropped. Name it, then construct a run — two readers and one slow
network — in which this version produces a torn transaction.

**E6.3** *"The timestamp cache is only a performance structure — it holds no data and is
never replicated — so when a leaseholder dies, the new one can simply start with an empty
cache and carry on."* Construct a run in which this loses serializability, and name the
error message the real design produces instead.

---

## Rung 7 — Handle failure

Extend the design on paper so it survives something it currently does not.

**E7.1** A client sends a transfer, the coordinator commits it, and the client's connection
dies before the acknowledgement arrives. The client retries. Design the smallest change to
the *application* that makes this safe, state exactly what the database must provide for
your design to work, and say why a retry loop written for 40001 errors is *almost* — but
not quite — enough on its own.

**E7.2** Your payments table has `id BIGSERIAL PRIMARY KEY` and inserts are pinned to one
node at 95% CPU while five other nodes idle. Give three different fixes, say what each one
costs you in query capability, and pick one for a table that is (a) only ever looked up by
id, and (b) frequently scanned by `created_at` ranges.

**E7.3** Your cluster runs across three regions. The clock-offset gauge on one node has
been climbing for an hour and now sits at 380 ms against a 500 ms budget. Nothing is
erroring. Write the runbook: what you check, in what order, what you do first, and what you
must *not* do. Then explain what happens at 400 ms, and why the number is 400 and not 500.

---

## Rung 8 — Design

Production-scale prompts with real constraints. There is no single correct answer; the
answer is a defended set of choices. The solutions give a worked discussion.

**E8.1** **A three-region payments ledger.** Mumbai, Frankfurt and Ohio, round-trip times
of roughly 120 ms between any pair. Requirements: an account's balance must never go
negative; a transfer between two accounts is atomic; regulators require that a completed
payment is visible to a subsequent query from any region; 90% of traffic touches accounts
in one region only; a nightly reconciliation reads everything. Design the schema's
localities, say which tables are `REGIONAL BY ROW` and which (if any) are `GLOBAL`, choose
a survival goal, choose `--max-offset`, choose primary keys, and say what you monitor and
what you alert on. Then state which parts of your design would have to change if the
regulator upgraded "visible to a subsequent query" to "the order of all payments must match
real time."

**E8.2** **Migrating from a sharded PostgreSQL setup.** Sixteen PostgreSQL shards, sharded
by `customer_id`, with an application-level saga for the cross-shard cases and a nightly
reconciliation job that fixes what the saga missed. Plan the migration to CockroachDB.
Which parts of the application become simpler, which become *harder*, and what is the one
new failure mode your on-call rotation must learn? Be specific about what happens to the
saga and to the reconciliation job.

**E8.3** **Choosing between four systems.** You are given a workload — a global product
catalogue, read from every region on every page load, written by a merchandising team
roughly a hundred times a day — and asked to choose between: a default CockroachDB table,
a CockroachDB `GLOBAL` table, Spanner, and a cache in front of a single-region PostgreSQL.
Make the choice, justify it with the *who-pays* framing from stage 6, and say what would
change your answer.

---

## After the exercises

If you want to go further, the highest-value next labs are listed in stage 15 of the
[write-up](README.md#15-where-does-this-sit-and-what-comes-next):
`/learn calvin-deterministic-databases`, then `/learn jepsen`.
