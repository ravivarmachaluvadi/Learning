# Kafka Exactly-Once — Exercises

Eight rungs, easiest first. Nothing here asks you to write code. Everything is
reasoning, tracing, prediction or design, and most rungs can be checked against
`visualization.html`.

**The standing setup**, unless an exercise says otherwise:

> **Rupaya's settlement service.** Consumer group `settlement`, reading
> `captures-3`, positioned at offset 5000. It reads three captures, writes three
> ledger lines to `ledger-entries-1` (starting at offset 810), two payout rows to
> `merchant-payouts-0` (starting at offset 44 — TeaHouse ₹1,960 and BookBarn
> ₹2,450), and commits offset 5003 to `__consumer_offsets-12`.
> `transactional.id = settlement-3`, producer id 4711, epoch 0.
> Downstream, a **bank transfer job** reads `merchant-payouts-0` and pays out
> whatever it is handed.

Work them before opening `SOLUTIONS.md`. Rungs 6, 7 and 8 have no single right
answer; the solutions give a worked line of reasoning instead.

---

## Rung 1 — Explain

**1.1** In your own words, without using the word "atomic": what does a Kafka
transaction actually make true, and about *what set of things*? Your answer must
be precise enough that someone could use it to decide whether EOS helps their
pipeline.

**1.2** A colleague says: *"We turned on exactly-once, so our processing function
now runs once per record."* Give the sharpest one-sentence correction you can,
then say what they **did** buy.

**1.3** Explain to someone who has only ever used a SQL database why an
`abortTransaction()` in Kafka is not a rollback. Use the words *append* and
*filter*, and say who does the filtering.

**1.4** §4 argues that "just make the consumer idempotent" is usually the right
answer, and then gives three situations where it stops working. Reproduce the
three, and say which of them is a *hard* impossibility rather than an expense.

**1.5** Why does `isolation.level` default to `read_uncommitted` when that
default silently disables the feature for anyone who forgets it? Give the reason
Kafka would give, not the reason you wish it gave.

**1.6** In one sentence each, distinguish: the **idempotent producer**, a
**transaction**, and an **idempotency key**. Say which hop each one protects.

---

## Rung 2 — Identify

**2.1** At which exact step of the nine-step protocol does the transaction become
irreversible? Name the record that makes it so, and where that record lives.

**2.2** Two brokers are involved in `sendOffsetsToTransaction`. Name both roles,
say what each one is sent, and say why the work is split between them.

**2.3** Which component decides that a `read_committed` consumer must stop
reading — the consumer, the broker, or the coordinator? What is it consulting?

**2.4** A partition contains, physically:

```
808 plain   809 plain   810 txn(4711,0)   811 txn(4711,0)   812 txn(4711,0)
813 ABORT(4711)   814 txn(4711,1)   815 COMMIT(4711)   816 txn(5002,0)
```

For each of the two isolation levels, list exactly which offsets the
**application** receives, and give the LSO.

**2.5** Two different things in this lab are called an "epoch". Name both, say
what each one fences, and name the lab where you first met the general object.

**2.6** Where does the transaction timeout clock start? Name the exact API call
or protocol message, and name the one that people wrongly assume starts it.

---

## Rung 3 — Predict

Check each against the simulator.

**3.1** The producer completes `EndTxn(commit)`, `PrepareCommit` is durable, and
then the producer process is killed and never restarted. What happens to the five
records? What does the *application* observe on restart?

**3.2** Same as 3.1 but the producer is killed one step earlier — after
`sendOffsetsToTransaction` and before `EndTxn`. Give the state of all three
partitions at t+30s and at t+120s.

**3.3** A hung transaction pins `merchant-payouts-0`'s LSO at 44. A completely
unrelated service then appends a normal, non-transactional record at offset 46.
When does a `read_committed` consumer see it? When does a `read_uncommitted` one?

**3.4** The settlement service is upgraded so that each instance uses
`transactional.id = "settlement-" + UUID.randomUUID()`. Instance A freezes
mid-transaction, B takes over and commits, A wakes and commits. Give the final
payout to TeaHouse, and say which protocol mechanism failed.

**3.5** A team enables `processing.guarantee=exactly_once_v2` on a Kafka Streams
app that was previously `at_least_once`, changes nothing else, and deploys.
Name two things that change immediately, one of which they did not configure.

**3.6** A `read_committed` consumer's application logs show it processed offset
812 and then offset 814. A monitoring rule fires: "gap detected, possible data
loss." Is there data loss? What is at 813?

---

## Rung 4 — Modify

**4.1** Raise `transaction.timeout.ms` from 60 s to 600 s. Name one failure it
makes less likely and one it makes worse, and say who experiences the second one.

**4.2** Set `transaction.state.log.min.isr` to 1 on a 3-broker cluster. Construct
the smallest sequence of events in which a committed transaction's outcome is
lost. What guarantee from §12.1 breaks?

**4.3** A Streams application raises `commit.interval.ms` from 100 to 5000 under
EOS. What improves, what degrades, and what is the *unit* of the thing that
degrades?

**4.4** Move the settlement service from one transaction per batch of 3 captures
to one transaction per batch of 3,000. List the four things this changes, and
name the config that now becomes the binding constraint.

**4.5** A team decides that since aborts are rare, they will run their
`read_committed` consumers as `read_uncommitted` "for latency, and dedupe
downstream." Under what precise condition is this actually sound? Under what
condition is it strictly worse than never having used EOS?

---

## Rung 5 — Trace

Write the state after each step. Use real offsets.

**5.1** Trace the full nine-step commit from the standing setup. For each step
give: the message name, what is appended and where, the coordinator's state, and
the LSO of all three partitions.

**5.2** Trace an abort of the same run. Then answer: how many bytes of the
original payload were reclaimed, how many offsets were freed, and how many
records now exist in `merchant-payouts-0`?

**5.3** Trace the KIP-890 leak (protocol version 1). Producer 4711 epoch 0 writes
TeaHouse ₹1,960 to `merchant-payouts-0` at 44; the request for BookBarn ₹2,450
stalls; T1 aborts; T2 writes Deepa Tailors ₹500; the stalled request lands; T2
commits. Give every offset, every status, and the total the bank job pays. Then
redo it with the epoch bumped on `EndTxn` and give the total.

**5.4** Trace a coordinator failure between the first and second `WriteTxnMarkers`.
Give the partition contents at the moment of failure, what the new coordinator
reads, what it does, and the final state. Then say which step made the outcome
un-changeable.

**5.5** Trace the zombie in §10.6 with a **stable** `transactional.id`. Give the
epoch at each step, the offsets of A's and B's records, and the exact error A
receives when it wakes.

---

## Rung 6 — Spot the flaw

Each design below is wrong. Construct the **smallest concrete scenario** where it
fails, and name the violated guarantee.

**6.1** *"We keep a Redis set of processed capture ids inside the transaction
loop, so even if a transaction aborts we skip records we have already seen."*

**6.2** *"Our consumer uses `read_committed` and `enable.auto.commit=true`. Auto
commit is fine — it only commits offsets we have actually polled."*

**6.3** *"We run three replicas of the settlement pod for availability, all with
`transactional.id=settlement`, all subscribed to the same group."*

**6.4** *"To avoid the LSO stall we set `transaction.timeout.ms=2000`. Our batches
take about 800 ms, so there is plenty of headroom."*

**6.5** *"We wrap the JPA save and the Kafka send in one `@Transactional` method
with a chained transaction manager, so the database and Kafka commit atomically."*

**6.6** *"Aborted records are filtered by the broker, so a high abort rate costs
us nothing downstream."*

---

## Rung 7 — Handle failure

Design answers, not single outputs.

**7.1** A partition's LSO has not moved in six hours. `read_uncommitted` consumers
are healthy; `read_committed` lag is climbing linearly; the brokers holding that
partition are growing past their retention budget. Write the diagnosis, the exact
commands, the fix, and what you would change so it cannot recur.

**7.2** The settlement service must call an external bank API and then write a
payout row to Kafka. Design the pipeline so that a crash at any point cannot
double-pay a merchant. Say explicitly which part EOS covers, which part it cannot,
and what carries the guarantee across the boundary.

**7.3** A `read_committed` consumer must not stall for more than 5 seconds, but
the upstream transactional producer legitimately needs up to 3 minutes per batch.
Both requirements are real. Give at least three ways out and pick one, saying what
you gave up.

**7.4** Design the alert set for an EOS pipeline. For each alert give the signal,
the threshold shape, the failure it catches, and — importantly — the failure it
would **not** catch. At least one alert must catch a failure that produces no
errors anywhere.

**7.5** You inherit a pipeline that "has exactly-once". Write the audit: the
five things you check, in order, and what a wrong answer to each one means. The
first check should be the one that most often ends the investigation.

---

## Rung 8 — Design

**8.1** Rupaya wants to add a stage computing each merchant's rolling hourly
payout total, and it must be exactly right. Say why the `idempotency` lab's
answer does not apply to this stage, what does, and what new operational
properties you have just taken on.

**8.2** A topic is shared by six teams. One team wants to enable transactions on
their producer. Write the case *against* letting them, in terms the other five
teams would care about, then write the conditions under which you would say yes.

**8.3** Kafka 4.1 added `transaction.two.phase.commit.enable`, letting an external
coordinator decide a Kafka transaction's outcome. A colleague proposes using it to
get atomic database-plus-Kafka commits in a Spring Boot service. Evaluate the
proposal. Name the specific property it switches off and what that costs, and give
your recommendation with a reason a non-specialist would accept.

**8.4** Rupaya must delete a merchant's data on request (a GDPR erasure). The
merchant's records are spread across transactional topics with 7-day retention,
some in aborted transactions. Explain what "delete" can and cannot mean here, and
design something that satisfies the requirement.

**8.5** **Counterfactual.** Suppose Kafka had no last stable offset — consumers
were simply served everything and told, out of band, which transactions had
aborted. Describe the smallest concrete situation that breaks, what each consumer
would now have to implement, and what it would cost. Then say what the LSO is
really buying.

**8.6** **Counterfactual.** Suppose the producer epoch were bumped only on
`initTransactions()` and never on commit or abort — the pre-4.0 behaviour. Give
the smallest concrete sequence that produces a wrong result, then explain why the
fix could not simply have been "reject requests older than N seconds."
