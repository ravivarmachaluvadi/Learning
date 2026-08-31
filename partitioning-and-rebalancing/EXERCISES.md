# Exercises — Partitioning & Rebalancing

Pencil-and-paper and visualization work only. Attempt each rung before opening
[`SOLUTIONS.md`](SOLUTIONS.md). The visualization is
[`visualization.html`](visualization.html) ("The Moving Shelves").

---

## Rung 1 — Explain

1.1 In your own words, no jargon: why must data be split across machines, and
why doesn't replication solve the same problem?

1.2 The lab's key idea is "cut the data into many more pieces than you have
machines, and make the piece the unit of movement." Explain to a colleague
*why* the piece-count being larger than the machine-count is the load-bearing
part — what goes wrong at pieces == machines?

1.3 Explain the difference between data skew and load skew, with one concrete
example of each, and say which of the two can be fixed by moving partition
boundaries.

1.4 A colleague says "we can't add partitions, the partition count is frozen
forever". In your own words — no arithmetic — explain in what sense they are
right, in what sense they are wrong, and what question you would ask about
their system before agreeing with either half.

1.5 Explain to a non-engineer why "we keep three copies of your data" can be
technically true and practically false, and what a rack has to do with it.

## Rung 2 — Identify (in the visualization)

2.1 Tab 1, scheme "fixed shelves", press "+60 books" twice. Which building
holds the most books right now? Which *shelf* is the fattest, and what is its
book count? What is the current data-balance number, and what would 1.00×
mean?

2.2 Press "40 borrows of the Sharma novel". Which shelf is glowing, in which
building? Read the two balance numbers — which one moved, and why only that
one?

2.3 Tab 2, before starting a move: who owns p11 according to the catalog, at
which epoch? What version does every record hold?

2.4 Tab 3, mode "hash mod P". Press "Double the shelf count" once. Read both
big numbers: what does each one count, and why is one of them zero? Then find
shelf p3 and shelf p19 — which building is each in, and why is that not a
coincidence?

2.5 Tab 4, tick "each copy of a shelf in a different rack". Read the shelf
table: how many copies did p0 actually get, what reason is printed next to it,
and what is the total number of copies missing across the whole cluster? Now
say which single number on this screen an on-call engineer should be paged
about.

2.6 Tab 2, untick "old owner seals the shelf at cutover", run a move to
completion, press "A reader updates an address" once, then "Stale client READS
from the old owner". Which version did the client receive, which version is
current, and how many writes were lost?

## Rung 3 — Predict (then check in the viz)

3.1 Tab 1, scheme "fixed shelves", P=12, 3 buildings with ~120 books. You
open a 4th building. Predict: (a) how many shelves move, (b) what percentage
of *books* move, (c) how many books move *between the three old buildings*.
Run Scenario 1 (or press "Open a new building") and check all three.

3.2 Tab 1, scheme "by key order + auto-split". Predict what the shelf layout
looks like after "+40 newspapers (date keys)" pressed twice: how many shelves
hold newspapers, and which shelf will take *tomorrow's* newspaper? Check with
Scenario 2.

3.3 Tab 2, protocol v2 (catch-up). You press "write" twice during the
snapshot copy and once during catch-up. Predict the verdict at the end, and
predict what the delta log's maximum length was along the way.

3.4 Tab 3, mode "hash mod P", P=16. Predict, before pressing anything: after
"Double the shelf count", (a) roughly what percentage of keys land on a
different partition, (b) what percentage must be copied over the network, and
(c) what P becomes. Then predict the same three numbers for "Set P to 24".
Run scenarios 8 and 9 and check all six.

3.5 Tab 4, with three buildings in three different racks, rack rule ON and
three copies required. Predict what happens when you close one building —
how many shelves become unplaceable, and how many copies each of them keeps.
Check it, then predict what happens if you drop the requirement to 2 copies.

3.6 Tab 3, mode "hash RANGE". Predict what the two big numbers show after
"Split the fattest shelf in half", and predict what P becomes. Now predict
whether pressing it ten more times ever forces a key to cross the network.
Check with scenario 10 and then by pressing it repeatedly.

## Rung 4 — Modify

4.1 In Tab 1, set P=8 with 3 buildings. What is the *best possible*
data-balance number the fixed-shelf scheme can achieve (assume all shelves
equal size)? Now answer for P=24. What did raising P buy, and what does it
cost (name two per-partition costs from the doc)?

4.2 Suppose the migration protocol's catch-up drain runs at 100 writes/second
(throttled) and the partition receives 130 writes/second. Describe the
delta log's length over time, and what the operator must change for the
migration to ever finish. Which dashboard metric from stage 13 catches this?

4.3 The auto-split threshold in the viz is 30 books. What would change —
good and bad — if it were 5? If it were 10,000? Connect each answer to a
line in the trade-off table of stage 12.

4.4 You inherit a store with P = 1,024 fixed partitions holding 30 GB of data
on 4 machines, and it will never grow past 100 GB. Name three concrete costs
this is paying, name the operation that fixes it, state the only target
partition counts that operation can accept, and say what the operation costs
in availability.

4.5 Take the migration protocol v3 and remove exactly one thing: the epoch
check on **reads** (keep it on writes). Which invariant of stage 12 breaks?
Construct the shortest sequence of events that produces a wrong answer, and
say how a monitoring system could ever detect that it happened. Then answer
the harder half: with the read check present, what does the client see
instead, and what has that cost you?

4.6 The placer in Tab 4 treats every rule as a hard constraint. Suppose
"keep Tokyo users' partitions on Tokyo machines" were worth 100 ms of latency
but nothing else. Should it be a hard constraint or a soft preference, and
what specifically goes wrong if you get that choice backwards in each
direction?

## Rung 5 — Trace (by hand, then verify)

5.1 Fixed partitions, P=6, nodes {A, B}, assignment: A={p0,p2,p4},
B={p1,p3,p5}. Node C joins. Using the "steal whole partitions from the
most-loaded node until the newcomer reaches fair share" rule (ties: steal the
highest-numbered partition), write the final assignment, list every partition
that moved, and compute the moved fraction of keys (assume equal keys per
partition). Then say which of the five invariants of stage 12 your trace
preserved and how you'd check each.

5.2 Trace migration protocol v2 by hand. Partition holds keys
{asha:v1, ben:v1, chirag:v1}; copy order is alphabetical, one key per step.
Events, in order: copy asha → **write asha** → copy ben → copy chirag →
**write ben** → (catch-up begins) → drain → drain → cutover.
Write down, after every event: the copy cursor, B's versions, D's versions,
and the delta log contents. What does D hold at cutover, and is the
verdict "nothing lost"? Then answer: which of the two writes would have
been lost under protocol v1, and why exactly those? (The viz's write button
cycles asha, ben, chirag, … in order, so you can reproduce the same shape
in Tab 2 — it just has six records instead of three.)

5.3 By hand, no simulator. Eight keys have hashes **3, 11, 20, 28, 37, 45,
54, 62**. P = 8, the rule is `partition = hash mod P`, and partitions are
dealt to nodes {A, B, C} by `partition mod 3`. Write the partition and node
for all eight keys.

Now double P to 16 and write them again. Report three numbers: (a) how many
keys changed partition; (b) how many changed node **if each new partition
p + 8 is created on the machine that already held partition p**; (c) how many
changed node **if instead the placer re-deals all 16 partitions by
`partition mod 3`**. Compare (b) and (c), then say in one sentence what the
"doubling is free" claim of stage 10.8 actually depends on — because it is not
only the arithmetic.

Finally redo it for P = 12 and report (a) and (c) again. Why can no
"stays-home" rule rescue this case?

5.4 Trace the resurrected delete (stage 10.6, hole 2) on three keys.
Partition holds {asha:v1, ben:v1, chirag:v1}; copy order alphabetical, one key
per step. Events: copy asha → **delete ben** → copy ben → **delete asha** →
copy chirag → catch-up → cutover. Track after every event: B's contents, D's
contents, and the delta log. Do it twice — once with the delta log recording
*keys that changed*, once recording *operations*. State exactly which records
exist on D at cutover under each, and which of the two deletions each version
loses. Which one is worse, and why?

## Rung 6 — Spot the flaw

Each design below is described, not coded. Find the planted mistake,
construct a concrete run where it gives a wrong outcome, and name the
violated invariant or guarantee.

6.1 *"Our migration is safe: the destination copies a snapshot, then replays
the delta log, and we never pause writes — when the delta log is empty we
just flip the map."* (Hint: the log was empty at the moment we *checked*.)

6.2 *"We don't need ownership epochs: before serving any write, the old owner
checks a `still_owner` boolean it keeps in memory, which the rebalancer sets
to false at cutover."* (Hint: the distributed-locking lab's Act 3.)

6.3 *"To fix our hot timestamp key, we salted it: writes go to
`key#rand(0..19)`. Reads pick a random salt too, so load spreads evenly."*

6.4 *"Our auto-rebalancer keeps the cluster healthy: any node that misses
3 heartbeats (3 seconds) is drained — its partitions immediately stream to
the survivors. We tested it by killing nodes; it works great."*

6.5 *"Resharding is easy: we run `hash mod P` with P = 64, and when we need
more granularity we just bump P to 100 and let the rebalancer sort it out
overnight. It's the same operation as adding a node, only bigger."*

6.6 *"Our placer is rack-aware: it never puts two copies of a partition in
the same rack. To keep the cluster balanced it also moves partitions
continuously toward the emptiest machine. Last week we consolidated from
three racks to two for cost reasons — the dashboards stayed green all week,
so the change was safe."*

## Rung 7 — Handle failure

7.1 Extend migration protocol v3, on paper, to survive this: **the
destination node D crashes halfway through the catch-up phase.** Specify:
what state exists where at the moment of the crash, who notices, what
happens to the partly-copied data, whether the migration restarts from
scratch or resumes, and what B must have kept for either answer. What is
the invariant your extension must never violate, and what is the cheapest
piece of durable bookkeeping that guarantees it?

7.2 A stale-map client's redirect (F3) requires the old owner to be alive.
Design the complete client-side routing loop that is correct even when the
old owner is dead: what does the client try, in what order, with what
fallback, and what stops a thundering herd of clients all hammering the map
authority at once? (Two prior labs supply the herd fix.)

7.3 Your cluster enforces "one copy of each partition per availability zone,
three zones". Zone C goes offline for four hours (network, not data loss).
Design the response on paper: what should the placer do at minute 1, minute
30, and hour 4; what should it do differently if zone C is gone permanently;
and what is the single decision that separates those two cases when nobody
knows yet which one it is. Name the metric you would page on and the metric
you would deliberately *not* page on, and connect both to F5 and F7.

## Rung 8 — Design (production scale)

Design the partitioning and rebalancing story for a **payments ledger** —
your system-design curriculum's home turf. Requirements:

- 500M accounts; each account has a balance row and an append-only entry
  stream (~2 KB/entry, ~50 entries/account/year, heavy-tailed: 0.1% of
  accounts produce 100× the median).
- Queries: (a) post an entry and update the balance atomically (99% of
  traffic), (b) statement for one account for a date range, (c) daily
  regulator scan of *all* entries for one calendar day.
- Growth: the fleet doubles roughly yearly; migrations must never lose an
  acknowledged entry (it's money — the idempotency lab applies).

Decide and defend: the partition key and scheme (and what happens to query
(c) under your choice); fixed vs dynamic partitions and why; how the 0.1%
whale accounts are handled; the migration protocol and where epochs are
checked; what the map lives in; which rebalancing operations are automatic
and which need a human; and the five dashboards you build first. Then list
the two requirements that are in genuine tension and state the compromise
you chose.


8.2 **The resharding you inherited.** A service you now own stores 4 TB of
user documents in a home-grown store: `partition = md5(user_id) mod 64`, 64
partitions over 24 machines, a partition→node table in ZooKeeper, and a
partition-aware client library used by nine teams. Each partition is now
~62 GB; a single migration takes eleven hours, so the cluster effectively
cannot be rebalanced during business hours, and last quarter a machine failure
took two days to fully re-replicate.

Produce the plan. Cover: the target partition count and how you justify the
number; which of the three P-change routes from stage 10.8 you take and why
the other two are rejected; whether the client library must change and what
happens to the nine teams if it does; how you sequence the change so that no
acknowledged write and no acknowledged delete is lost at any point; what you
do about reads during the change; how long the whole thing takes and what
fraction of it must happen with writes blocked; and the rollback plan for the
moment it goes wrong at 40% complete. Finally: name the one property of the
current design that made this expensive, and write the one sentence you would
put in the design doc of the *next* system to avoid inheriting this again.
