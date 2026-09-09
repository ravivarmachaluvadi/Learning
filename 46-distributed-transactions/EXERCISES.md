# Exercises — distributed transactions

Climb in order; each rung uses the one below it. Answers in
[`SOLUTIONS.md`](SOLUTIONS.md) — attempt first, in writing, before opening it.

## Rung 1 — Explain

1.1 In your own words, no jargon: why is "we use two-phase commit" not the
end of the conversation about moving money between two shards? What second
promise is missing, and who notices when it breaks?

1.2 The lab's key idea has two halves — one about *time*, one about *place*.
State both in plain sentences, and say which failure each half kills.

## Rung 2 — Identify

Run `java src/Sim.java 2` (the cell-by-cell percolator commit).

2.1 Which key is the primary, and what decided that?

2.2 After step "PREWRITE bob", read the printed shard state: what three
pieces of information does bob's shard hold about the in-flight transaction,
and which one would a reader follow to find the verdict?

2.3 Point to the exact printed line where the transaction becomes committed.
What is on shard S1 at that moment?

## Rung 3 — Predict

Write your answer down BEFORE running the command.

3.1 An auditor's snapshot is ts=10. It meets a lock with startTs=12 on a
key. May it simply read the newest version ≤10 and move on — even though it
has no idea whether that transaction will commit? Why? Check with scenario 1
in `java src/YourResolver.java` (read the case list) or the `l.startTs >
snapTs` branch in `Engines.resolveAndRead`.

3.2 In experiment 3, coordinators die mid-commit in both TPC_2PL and PERCO.
Predict the two "readers who gave up" numbers before running
`java src/Experiments.java 3`. What single design difference produces the gap?

3.3 Clock engine, no commit wait, skew=2 ticks: predict the stale-snapshot
count in experiment 6's first row block (`java src/Experiments.java 6`).
Then say why that number is the most dangerous one in the whole table.

## Rung 4 — Modify

4.1 In `Engines.java`, set `LOCK_TTL = 2`. Run `java src/Tests.java`. Which
tests fail, and what real-world incident does this misconfiguration
correspond to? Which side of the safety/liveness line is hurt? (Put it back.)

4.2 In `Engines.resolveAndRead`, delete the `l.startTs > snapTs` early
return (make every lock trigger resolution). What behavior degrades, and
does anything become *incorrect*?

4.3 Make the percolator prewrite skip the `latestCommitTs(key) > startTs`
check. Which isolation property dies, which test notices, and what is the
single-machine name for the anomaly you just legalized?

## Rung 5 — Implement

5.1 `java src/YourResolver.java` — implement the read rule until 6/6. Do not
look at `Engines.resolveAndRead` first; the six cases plus the hints are
sufficient.

## Rung 6 — Debug

`java src/Tests.java broken` runs four planted bugs. For each one, before
reading the printed explanation: which of the four core guarantees
(durability-atomicity, visibility, isolation strictness, liveness) does it
break, and what is the smallest scenario that exposes it? Which of the four
is the most dangerous in production, and why does "most dangerous" not mean
"most visible"?

## Rung 7 — Handle failure

7.1 A percolator client calls commit and the process hosting it dies before
any response arrives. The user hits "retry". Today's lab client would run a
brand-new transaction — possibly transferring twice. Using only mechanisms
that already exist in this lab (start-ts, primary, commit records), design a
safe retry: what must the client persist before prewriting, and what does
the retry check? Which earlier lab's law did you just re-derive?

7.2 The oracle process dies. Nothing in the lab models it — describe what
actually stops, in order, and sketch the two production escapes (one from
TiDB's playbook, one from Spanner's).

## Rung 8 — Design

A payments company runs a ledger sharded across 3 regions (EU, US, APAC),
~50ms apart. Requirements: an account's balance must never go negative;
transfers within a region are 95% of traffic; cross-region transfers must
never be observed half-done by the fraud pipeline (which reads at high
volume); regulators demand that a confirmed transfer is visible to any
subsequent read anywhere.

Design the transaction layer: which of this lab's designs (or production
descendants) per traffic class, where the timestamps come from, what the
fraud pipeline reads, what you monitor, and — explicitly — which parts of
the problem you refuse to solve with distributed transactions at all
(decision ladder, stage 15). State what each choice costs.
