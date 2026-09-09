# Solutions — Partitioning & Rebalancing

Attempt the exercises first. For rungs 7–8 these are worked design
discussions, not single correct answers.

---

## Rung 1 — Explain

**1.1** One machine eventually can't hold all the data (disk) or serve all the
traffic (CPU/network) — usually traffic breaks first. Replication makes *more
copies of everything*: every replica still stores the full dataset and still
receives every write, so it multiplies read capacity and survivability but
does nothing for total size or write throughput. Splitting gives each machine
a *different piece*, so both storage and write load divide across the fleet.
They're orthogonal and composed: real systems replicate each piece.

**1.2** At pieces == machines, "rebalance" and "re-cut the data" are the same
operation — to change the machine count you must redraw boundaries through the
records themselves, which is `mod n`'s disease (the consistent-hashing lab
measured 99% of keys moving). With many pieces per machine, the piece-to-key
mapping is frozen; only the small piece-to-machine table changes, so growth
means relocating whole pieces — a table edit plus bulk copies — and the moved
fraction drops to the theoretical minimum of ~1/n. The surplus of pieces is
also the balance granularity: you can't spread 3 pieces evenly over 4 machines.

**1.3** Data skew: uneven *bytes* — the S-surname shelf holds 3× the books of
the X shelf. Load skew: uneven *requests* — one bestseller gets more borrows
than the rest of the library combined. Boundary moves (split the S shelf, merge
quiet neighbours) fix data skew and the *multi-key* kind of load skew; they
cannot fix a single hot key, because every scheme gives one key one home.
That needs key-level surgery (salting) or a cache in front.

**1.4** Both halves are true of different systems, and the question tells you
which. They are right that under `hash mod P` the *formula* freezes P: every
client computes `hash(key) mod P` offline, so changing P re-addresses data
everywhere at once. They are wrong that this makes P unchangeable — doubling
(and halving) is cheap, because `h mod 2P` is either `h mod P` or `h mod P + P`,
so each partition cuts locally in two with nothing crossing the network. And
they are wrong in a second, bigger way if their system partitions by hash
*range* rather than by remainder: then P is not in the rule at all and any
partition can be split at any time. **The question to ask: "is our rule a
remainder or a range?"** Everything else follows from the answer.

**1.5** Three copies protect you against *independent* accidents — one disk
dies, one machine reboots, one process crashes. Put all three copies in the
same rack and they stop being independent: one power supply, one network
switch, one clumsy afternoon of maintenance takes all three at once. So you
have three copies of the data and one copy of the *risk*. A rack is the unit
of "things that fail together", which is why serious systems refuse to place
copies without knowing which rack each machine is in. (In cloud terms the same
argument climbs a level: availability zone, then region.)

## Rung 2 — Identify

**2.1** (Deterministic, so your numbers should match.) With P=12 over 3
buildings the shelves sit 4/4/4; the fattest shelf is whichever collected the
most hash collisions of the 120 keys — read it off the widest count. Balance
1.00× would mean the busiest building holds exactly total/3; the displayed
number is busiest ÷ that fair share.

**2.2** The shelf owning `sharma-the-cedar-gate` glows (its heat bar fills);
only the **load** balance number jumps, because 40 borrows added almost no
*data* (one key) but lots of *traffic*. That split — data balance calm, heat
balance spiking — is exactly how a hot partition looks on a real dashboard.

**2.3** Catalog: p11 → **B** at **epoch 5**; every record at **v1**; D's table
is empty (nothing copied yet).

**2.4** The first number counts keys whose *address* changed (48.8% — a
bookkeeping fact); the second counts keys whose *bytes* had to be carried to
another machine (0%). The second is zero because partition p19 is created out
of p3 by splitting the data already sitting on p3's machine — there is nothing
to send. p3 and p19 are in the same building, and that is the whole mechanism:
every key of p19 was a key of p3 a moment ago, since `h mod 32 = 19` implies
`h mod 16 = 3`.

**2.5** p0 gets **one** copy, with the reason "every remaining building is in
a rack this shelf already uses"; all six shelves are short two copies, so 12
copies are missing cluster-wide. The number to page on is the count of
under-replicated/unplaceable shelves — **not** rebalancing activity, which is
zero and looks perfectly healthy (F7).

**2.6** The client receives v1; the current value at D is v2; **zero** writes
were lost. That combination — nothing lost, and a wrong answer served — is the
whole point of F8: the write fence and the read fence protect different things,
and having one does not give you the other.

## Rung 3 — Predict

**3.1** (a) 3 shelves move (12/4 = 3 is the newcomer's fair share); (b) ~25%
of books (3 of 12 shelves, subject to shelf-size noise); (c) **zero** books
move between old buildings — monotonicity. Scenario 1 shows ~75% for `mod N`
on the same change, with most moves sideways.

**3.2** All newspapers occupy a run of shelves at the *end* of key order (the
`zz-day-…` keys sort after every surname); after enough filings the tail shelf
splits, but tomorrow's paper always lands on whichever shelf currently ends at
+∞ — the newest last shelf. The frontier is a property of the *keys*, not of
any particular shelf.

**3.3** Verdict: nothing lost — every write went into the delta log (during
copy) or the delta log (during catch-up), and the drain empties it before the
flip. Max delta length: 2 (the two snapshot-phase writes queue; the catch-up
write arrives while draining, so the log briefly holds it too — depending on
interleaving you'll see 2 or 3; the point is it *shrinks to zero* before
cutover).

**3.4** Doubling: (a) ~49% change partition, (b) **0%** cross the network,
(c) P = 32. P → 24: (a) ~69%, (b) ~69%, (c) P = 24. The instructive part is
the second column: the same "change P" operation costs nothing or costs a full
redistribution depending only on whether the target is a multiple.

**3.5** Closing one of three racks makes all six shelves unplaceable at the
third copy — each keeps 2 of 3. Dropping the requirement to 2 copies makes all
six placeable immediately, which is worth pausing on: *lowering your durability
target is always available as a way to make the red go away*, and it is a real
decision (fewer copies, legally placed) rather than a fix. Choosing it
deliberately is fine; drifting into it because the dashboard turned green is
how clusters end up under-replicated for months.

**3.6** ~6% change partition, 0% cross the network, P = 17. Pressing it ten
more times never forces a key across the network: a split always creates the
new partition where the data already is. Movement between machines is the
*separate* decision of rebalancing, and keeping the two apart is exactly why
hash-range partitioning can resize forever.

## Rung 4 — Modify

**4.1** P=8 over 3 nodes: best split is 3/3/2, so best balance = (8/3)/(8/3)…
in shelf counts the busiest holds 3 shelves against a fair share of 8/3 ≈ 2.67
→ **1.125×**. P=24 over 3: 8/8/8 → **1.00×**, and over 4 nodes 6/6/6/6 →
still 1.00×. Raising P buys balance granularity and smaller (cheaper, faster)
moves; it costs per-partition overhead ×P (files, metadata, recovery
bookkeeping) and wider scatter fan-out (a local-index query now asks 24
partitions, not 8 — and the tail-amplification math worsens with fan-out).

**4.2** The delta log grows at 30 writes/second forever — the drain never
catches up, so the migration literally cannot reach cutover; it is a queue
with arrival rate > service rate (the replication-lag lesson from the quorums
lab). The operator must raise the migration throttle (or move the partition
during a quieter window, or split the partition first so each half's write
rate fits under the throttle). Dashboard: **rebalance in flight — bytes
queued/ETA and throttle utilization**; the tell is an ETA that grows instead
of shrinking.

**4.3** Threshold 5: partitions stay tiny — near-perfect balance and cheap
moves, but per-partition overhead explodes and every query fans wider; the
split machinery runs constantly (and each split churns the map, so stale-map
redirects rise). Threshold 10,000: splits almost never happen — minimal
overhead, but partitions become jumbo (slow, risky moves; coarse balance;
the unit of movement stops being "manageable"). Rows implicated: "more
partitions" (both directions) and "dynamic split/merge" in stage 12's table.

**4.4** Costs: (1) per-partition overhead ×1,024 — files, memtables, open
handles, recovery bookkeeping, heartbeat and metadata rows, for partitions
averaging 30 MB; (2) every scatter query fans out 1,024 ways, so tail-latency
amplification is at its worst (the 0.99^n curve of stage 10.7 is essentially
zero at n = 1,024); (3) the map is 1,024 rows to store, agree on, propagate and
keep fresh in every client cache, for a cluster of four machines. The fix is
**halving** — Elasticsearch's `_shrink`, or its equivalent — and it can only
target a **factor** of the current count: 512, 256, 128, … 1. Cost in
availability: shrink-style operations typically require the source shards to be
collected onto one node first and writes blocked for the duration, so it is a
planned-maintenance operation, not a background one.

**4.5** The broken invariant is **routing safety** ("a stale map costs extra
hops, never wrong answers"). Shortest run: migrate p11 from B to D; cut over;
one write lands at D (the true value advances); a client whose cached map still
says B issues a read; B answers from the copy still on its disk. Three events
and a wrong answer, with no node failure, no pause and no partition. Detection
is the nasty part: nothing errors, so there is no rate to alarm on — the only
signals are indirect (clients reporting impossible sequences, a read-repair or
checksum job noticing divergence, or the age of the deposed owner's copy). With
the read check present the client gets a `MOVED` redirect instead: it pays one
extra round trip and repairs its map. That is the cost — an extra hop on some
reads, plus a check on the hottest path — and it buys the difference between
"slow sometimes" and "wrong invisibly".

**4.6** Soft preference. Hard is wrong here because a rule worth 100 ms should
never be able to stop the placer entirely: lose the Tokyo machines to
maintenance and a hard rule leaves those partitions unplaced and *unavailable*
— you traded 100 ms for an outage. Backwards in the other direction (soft when
it should be hard) is worse in a quieter way: a residency rule expressed as a
preference will be silently violated the first time the EU machines are full,
and nothing will report it, because a satisfied-preference system considers
itself healthy. Rule of thumb: constraints that protect **correctness or
legality** are hard; constraints that protect **performance** are soft.

## Rung 5 — Trace

**5.1** Fair share = ⌊6/3⌋ = 2. Steal 1: A and B tie at 3; node-name order
picks A; A's highest is p4 → C. (A:{p0,p2}, B:{p1,p3,p5}, C:{p4}.) Steal 2:
B is most-loaded at 3; B's highest is p5 → C. Final: **A={p0,p2},
B={p1,p3}, C={p4,p5}**. Moved: p4, p5 → **2/6 = 33.3% of keys**, which is
exactly the newcomer's fair share — minimal, zero sideways. Invariant checks:
*coverage* — p0…p5 each appear exactly once in the final assignment (count
them); *single owner per epoch* — each stolen partition's map entry flips
once, epoch bumped; *handover completeness* — each of the two moves runs the
v2/v3 protocol; *routing safety* — old owners redirect until caches heal.

**5.2** Trace (cursor = next key to copy; log = delta log):

| # | Event | Cursor | B (src) | D (dst) | Delta log |
|---|---|---|---|---|---|
| 0 | start | asha | a:1 b:1 c:1 | — | ∅ |
| 1 | copy asha | ben | a:1 b:1 c:1 | a:1 | ∅ |
| 2 | **write asha** → v2 | ben | **a:2** b:1 c:1 | a:1 | [a→2] |
| 3 | copy ben | chirag | a:2 b:1 c:1 | a:1 b:1 | [a→2] |
| 4 | copy chirag | done | a:2 b:1 c:1 | a:1 b:1 c:1 | [a→2] |
| 5 | **write ben** → v2 | — | a:2 **b:2** c:1 | a:1 b:1 c:1 | [a→2, b→2] |
| 6 | drain | — | a:2 b:2 c:1 | **a:2** b:1 c:1 | [b→2] |
| 7 | drain | — | a:2 b:2 c:1 | a:2 **b:2** c:1 | ∅ |
| 8 | cutover (pause, drain ∅, flip) | — | (former) | a:2 b:2 c:1 | ∅ |

D holds a:2, b:2, c:1 — every acknowledged version. Verdict: nothing lost.
Under v1, **both** writes die: the asha write landed behind the cursor at
step 2 (snapshot already took a:1), and the ben write landed after the whole
snapshot (step 5) — v1 flips straight from step 4's copy, so D would serve
a:1 and b:1 forever. A write is lost under v1 exactly when it lands at the
old owner in a region the snapshot has already passed — and by the end of the
copy, that's *every* region.

**5.3** P = 8, `partition = h mod 8`, node = `partition mod 3`:

| h | 3 | 11 | 20 | 28 | 37 | 45 | 54 | 62 |
|---|---|---|---|---|---|---|---|---|
| P=8 partition | 3 | 3 | 4 | 4 | 5 | 5 | 6 | 6 |
| node | A | A | B | B | C | C | A | A |
| P=16 partition | 3 | **11** | 4 | **12** | 5 | **13** | 6 | **14** |

(a) **4 of 8** keys changed partition — exactly the half whose hash had the
16-bit set, which is the arithmetic of stage 10.8 in miniature.
(b) With children created where their parents live (p11 with p3, p12 with p4,
p13 with p5, p14 with p6), the node column is unchanged for all eight keys:
**0 crossings**.
(c) Re-dealt by `partition mod 3`, the new nodes are A, C, B, A, C, B, A, C —
**4 crossings**, i.e. every key that changed partition also changed machine.

So "doubling is free" depends on **two** things, and only one of them is
arithmetic. The arithmetic guarantees the split is *local*; the placer has to
then choose to keep the child where the parent was. A placer that re-derives
placement from a formula on the partition id throws the gift away and turns a
free split into a full redistribution. (This is why real split APIs are
explicitly "split in place, rebalance afterwards if you want to" — two
operations, deliberately separate, exactly the two hops of stage 6.)

For P = 12 the partitions become 3, 11, 8, 4, 1, 9, 6, 2: **5 of 8** changed,
and all 5 cross. No stays-home rule can help, because there is no
parent-child relationship to exploit — a key from old partition 5 can land in
new partition 1 or 9, and old partition 5's other keys land elsewhere again.
Nothing was split; everything was re-shuffled.

**5.4** Delta log as *keys that changed* — the resurrection version:

| after | B holds | D holds | delta log |
|---|---|---|---|
| copy asha | asha,ben,chirag | asha | — |
| **delete ben** | asha,chirag | asha | {ben} |
| copy ben | asha,chirag | asha | {ben} *(nothing to copy; D's ben stays absent)* |
| **delete asha** | chirag | asha | {ben, asha} |
| copy chirag | chirag | asha,chirag | {ben, asha} |
| catch-up | chirag | asha,chirag | drains: "what is ben now?" → nothing → no change; "what is asha now?" → nothing → no change |
| cutover | — | **asha, chirag** | — |

D serves **asha**, which was deleted. The ben deletion survived only by luck —
the copy cursor reached ben *after* the delete, so it copied nothing. That luck
is the tell: correctness that depends on the race between the cursor and the
workload is not correctness.

Delta log as *operations*: entries are `DELETE ben @t2`, `DELETE asha @t4`;
catch-up applies both; D holds **chirag** only, which is right. Both deletions
survive regardless of cursor position.

Which is worse? The asha case, and not because one row differs. A lost *write*
means a user's change is undone and they will usually notice and redo it. A
lost *delete* means data the user asked you to destroy is quietly alive again
on a new machine — that is a compliance incident, it is invisible to the user
who requested it, and it is discovered by an auditor rather than by a customer.

## Rung 6 — Spot the flaw

**6.1** "Empty when we checked" is a check-then-act gap (third lab running):
between D observing the empty log and the map flipping, B accepts write W and
appends it — W is on B, invisible to D, and B's copy is deleted. Concrete
run: log empties at t=1; W arrives at t=2; flip at t=3; W gone. Violated:
handover completeness. The brief write-pause exists precisely to make
"empty" still true at the instant of the flip — drain-then-flip must be
atomic with respect to new writes.

**6.2** An in-memory flag read by the node *about itself* is exactly the
stale holder's self-belief. Run: rebalancer calls B to set `still_owner=false`
→ the call races B's GC pause (or B is partitioned from the rebalancer, or B
restarts and re-initializes the flag to true) → B wakes/reconnects, flag
still true, accepts writes → two owners. The flag is a *push* of a fact to
the least reliable witness; the epoch is a *check* against an authoritative
fact carried by every request. Violated: single owner per epoch. (Also note
the restart case: the distributed-locking lab's token-counter-in-memory bug,
same shape.)

**6.3** Salting must be **write-random, read-ALL**. Writes scattering to
`key#0..19` while reads sample one random salt means each read sees 1/20th of
the writes — reads silently miss ~95% of the data (e.g. a counter reads ~5%
of its true value). Correct: reads fan to all 20 sub-keys and merge (that's
the tax salting pays), or maintain a merged rollup. The flaw isn't load
balance — load *is* even — it's correctness: the salt made one logical key
into 20 physical keys and the read pretended otherwise.

**6.4** Three seconds of silence triggers terabytes of movement: this is F5
armed. Run: normal GC pause of 5 s on node C → C declared dead → C's
partitions stream to survivors → streaming saturates B and D's network →
their heartbeats miss → more drains → the movement manufactures the next
verdict (gossip lab: silence is the only symptom, and load supplies it).
"We tested by killing nodes" tested only the true-positive path — the
detector's *false positives* under load were never exercised (the falsifying
test: pause, don't kill). Fixes: sustained-evidence thresholds (minutes),
concurrent-move and bandwidth caps, route-around-first (replicas serve while
the node is merely suspected), human gate for bulk movement.

**6.5** Two planted mistakes, one arithmetic and one categorical. The
arithmetic: 100 is not a multiple of 64, so there is no local split — `h mod
64` tells you nothing about `h mod 100`, and essentially the whole dataset is
re-addressed and must be shipped. (64 → 128 would have been nearly free.) The
categorical error is the last sentence: this is *not* the same operation as
adding a node, only bigger. Adding a node changes hop 2 — a table of
partition→node rows — and moves ~1/n of the data with the key→partition
mapping untouched, so clients' cached arithmetic stays correct throughout.
Changing P changes hop 1, the mapping every client computes for itself, which
means there is a window where clients disagree about *which partition a key
belongs to* — not merely about where a partition lives. Redirects cannot repair
that: a `MOVED` tells you where a partition is, and here the partition itself is
the wrong one. Violated: nothing in the invariant list — which is the point.
This design breaks an assumption underneath the list, that key→partition is
stable. "Overnight" is also wrong on its own terms: at 4 TB with a throttled
rebalancer this is days, and during them the cluster is neither in the old
scheme nor the new one.

**6.6** The mistake is in the evidence, not the design: *"the dashboards stayed
green all week"*. Consolidating three racks into two makes every three-copy
partition unplaceable under the rack rule (F7) — and a constrained placer's
response to "no legal placement" is to stop moving, which registers on a
dashboard as zero rebalancing activity, zero errors and normal latency.
Concrete run: rack C is decommissioned on Monday; each partition's third copy
becomes homeless; the cluster serves reads and writes perfectly all week from
two copies; on Friday a rack loses power and partitions that were supposed to
survive it lose their only remaining copy. The violated guarantee is the
*durability margin* the replication factor was purchased for, and it was lost
on Monday, silently. Secondary flaw worth naming: "moves partitions
continuously toward the emptiest machine" is a rebalancer with no hysteresis and
no throttle, which is F5's other half — it will chase noise. The monitoring
lesson is the one from F7: alarm on **under-replicated partitions**, never on
rebalancing activity, because the dangerous state is the one with no activity.

## Rung 7 — Handle failure (worked designs)

**7.1 D crashes mid-catch-up.** State at crash: B has all data + the delta
log + is still map owner (epoch unchanged — the flip never happened); D has
a partial snapshot + partially applied deltas, all of it *non-authoritative*.
Who notices: the migration coordinator (whoever drives the state machine —
the rebalancer) via D's death or lease expiry; B also notices the drain
stopped. Safety consequence: **none** — clients were routed to B throughout,
so no acknowledged write ever depended on D. The migration simply aborts:
B discards the delta log, D's partial copy is garbage-collected on restart.
Resume vs restart: restart-from-scratch is always *correct*; resume is an
optimization needing (a) the snapshot copy to be durably checkpointed on D
with its copy-cursor position, and (b) B to retain the delta log from the
snapshot's start point (or a WAL position — which is why real systems anchor
migrations to a log offset: "resume" = re-ship deltas since offset X).
The invariant that must hold: **D never serves until it holds
snapshot + complete delta**; the cheapest durable bookkeeping is a single
migration record in the authoritative map store — `p11: migrating B→D,
started@offset X, state=COPYING` — because whoever recovers (a new
coordinator, a restarted D) can read it and know the flip never happened, so
B is still the owner. The one thing that must NOT exist: any path where D
serves based on local state alone.

**7.2 Routing loop for a dead old owner.** (1) Send to the cached owner with
a short timeout. (2) On MOVED: update cache, retry at the named node — done.
(3) On timeout/connection-refused: don't retry the same node; fetch the map
entry from the authority (or ask any other cluster node, who forwards or
answers from *its* cache — a second opinion is cheaper than the authority).
(4) Cap redirect-chains (2–3 hops) then fall back to the authority. (5) Herd
control, from the caching + distributed-locking labs: **single-flight** the
map fetch per process (one in-flight refresh, everyone else waits on it) and
**exponential backoff with jitter** on retries — otherwise the owner's death
converts every client into a synchronized stampede on the map service (which
is exactly when the map service can least afford it). (6) Negative-cache the
death briefly so new requests don't re-probe the corpse.

**7.3** The shape of the answer matters more than the specific minutes.

*Minute 1:* do nothing about data. Serve from the two surviving copies, mark
the zone-C replicas unavailable, and route around them. Data movement is the
most expensive action available and the evidence so far ("we cannot reach zone
C") is the cheapest kind.

*Minute 30:* still no re-replication, but the state should now be *visible* —
every partition is under-replicated by one copy, and that is the number on the
dashboard. This is the window where an automatic rebalancer would already have
started copying a third of the cluster, which is precisely F5: the movement
would load the surviving two zones, whose heartbeats would then lag.

*Hour 4:* a judgement call, and the honest answer is that it should be a human's
unless the outage is well past any plausible network event. Re-replicating into
two zones means either violating the one-copy-per-zone rule (creating two copies
in one zone — durability you do not actually have) or leaving the partitions
under-replicated legally. Both are defensible; picking silently is not.

*If zone C is gone permanently:* re-replicate, deliberately, throttled, with the
placement rule either relaxed on purpose or a third zone provisioned first.

*The decision that separates the two cases when nobody knows which it is:* **is
the loss of a machine treated as a change in membership, or as a temporary
absence?** Cassandra makes this the difference between a node being "down"
(route around; free) and `removenode` (stream data; operator-invoked);
Elasticsearch makes it `index.unassigned.node_left.delayed_timeout`. The rule
is the same everywhere: *routing around is automatic and instant; moving data is
delayed and gated*, because the first is cheap and reversible and the second is
neither.

Page on: **under-replicated partition count** (F7 — it is the actual loss of
durability margin). Deliberately do *not* page on: rebalancing activity, either
its presence or its absence — high activity is normal during a planned move and
zero activity is normal during a healthy day, so both alarm shapes generate
noise while missing the real failures (F5 is caught by movement *and* detector
verdicts climbing together, not by movement alone).

## Rung 8 — Design: the payments ledger (worked discussion)

**Partition key & scheme.** Key everything by `account_id`, hash-partitioned;
within a partition, entries clustered by `(account_id, entry_time)` (compound
key: hash spreads accounts; the clustering component keeps one account's
entries contiguous and time-ordered). Query (a) — post entry + update
balance — is single-partition (balance row and entry stream share the
account's partition), so it can be a local transaction: no 2PC on the hot
path, which is the whole game (distributed-transactions lab's decision
ladder: "co-locate keys on one shard" beats everything above it). Query (b)
statement = one-partition range scan over the clustering key. Query (c) —
all entries for a calendar day — is the sacrifice: it becomes scatter/gather
across all partitions. Defend the sacrifice: it runs once daily, offline,
latency-tolerant; better still, don't scan the ledger at all — stream entries
via CDC/outbox into a date-partitioned analytical copy (database-landscape
lab: derived, rebuildable, its own partitioning matched to *its* query
shape). Never range-partition by time here: F2 would put every posting on
one partition.

**Fixed vs dynamic.** Fixed P with a generous pre-split — e.g. P = 4,096 —
because the fleet doubles yearly and fixed-P rebalancing is table-edit +
whole-partition streams with zero boundary churn; 4,096 partitions over even
a few hundred nodes keeps ~10+ partitions/node for balance granularity.
(Dynamic splitting is defensible too — CockroachDB would be a fine
substrate — but with a hash scheme and a known key population, fixed-P is
simpler and P can be sized once, honestly: 500M accounts × ~100 KB/account
lifetime ≈ 50 TB → ~12 GB/partition at P=4,096, moveable in minutes at a
tame throttle.)

**Whales (0.1% at 100×).** 100× the *median write rate* on one account is a
load-skew problem inside one partition. Data answer: the entry *stream* for a
whale can be bucketed (`account_id + month`-style clustering, or salt the
stream sub-key while keeping the balance row single) — but the **balance row
itself must not be salted**: it's the thing query (a) atomically updates, and
splitting it recreates the distributed-rate-limiting lab's invariant problem.
So: keep the balance row single (it's one row — 100× a median row's write
rate is hot but bounded), give whale partitions dedicated placement (a
partition holding a whale gets a node with headroom — placement constraints,
stage 13's omission list), and alarm on per-partition heat so new whales are
noticed, not discovered.

**Migration & epochs.** Protocol v3 verbatim: snapshot at a WAL offset,
delta = WAL tail, brief write-pause cutover, epoch bump in the map; the
store checks the epoch on **every write** (reads too, if you serve reads
from partition owners — the distributed-locking lab's read fence). Because
it's money, add the idempotency lab's layer: entry posts carry idempotency
keys, so a client retry across a MOVED/epoch-refused boundary can never
double-post — the migration machinery *will* cause retries, and retries must
be harmless before you rebalance anything.

**The map** lives in a Raft-backed store (etcd or equivalent): P=4,096 rows
plus epochs is tiny, changes rarely, must never fork. Clients are
partition-aware with the rung-7.2 routing loop.

**Automatic vs manual.** Automatic: leadership transfers (no data motion),
single-partition migrations under caps (≤2 concurrent, bandwidth-throttled,
sustained-evidence health checks), split of a partition that trips size/heat
alarms — with hysteresis. Manual (human approves): anything moving >1% of
partitions at once (fleet doubling is a planned, staged campaign), any
response to a *suspected* node (route around automatically; never bulk-move
on suspicion — F5), and the annual P-adequacy review.

**Five dashboards first:** (1) per-partition heat histogram + busiest:median
ratio (whale detector); (2) per-partition size histogram (jumbo detector);
(3) migrations in flight: bytes queued, ETA trend, throttle utilization;
(4) epoch-rejection + MOVED rates (should be ~0 / low-and-spiky-only-during-
moves); (5) movement-per-hour vs detector-verdicts-per-hour on one graph
(the F5 tripwire). Plus the idempotency lab's oldest-RUNNING age on the
posting path, because ledger.

**The honest tension:** (a) "post atomically" wants everything about an
account on one partition forever; (d) "never lose an acknowledged entry
while doubling the fleet yearly" wants aggressive movement. The compromise:
movement happens at partition granularity with v3 + idempotent posts, and
the posting path is designed to *tolerate* migration (retries harmless,
epoch checks loud) rather than migrations being scheduled around the posting
path. The second tension — query (c) vs the hash scheme — is resolved by
refusing to serve it from the ledger at all: a derived, date-partitioned
copy answers it, one day behind, and the regulator's SLA (daily) makes that
honest.


---

## 8.2 — The resharding you inherited (worked discussion)

**Target partition count.** Work from the unit of movement, not the machine
count. Eleven hours per 62 GB partition is the symptom; pick a target
partition size that makes a move routine — say 4–8 GB, i.e. under an hour
throttled. 4 TB / 6 GB ≈ 680, so **1,024** is the number to reach for: it is
a power of two, so it is reachable by doubling from 64 (64 → 128 → 256 → 512 →
1024, four doublings) and it can be halved later if you overshoot.

**Which route.** Three exist and only one survives contact with this system.
*Full redistribution to an arbitrary P* is rejected: 4 TB re-shuffled, days of
it, with every client's `mod 64` arithmetic wrong the whole time — and, worse,
wrong in a way redirects cannot repair (exercise 6.5). *Switching to hash-range
partitions* is the right destination and the wrong migration: it changes hop 1
for every one of nine client teams simultaneously, so it belongs in the next
system, not this quarter. *Repeated doubling* is the answer: each step splits
every partition locally into two, moves nothing across the network, and can be
done one partition at a time.

**Does the client library change?** Yes, and this is the real project. Clients
hardcode 64 in `md5(user_id) mod 64`, so the number must become
data — fetched from ZooKeeper, versioned, and cached with the partition map
rather than compiled in. That change must ship to all nine teams and be fully
adopted *before* the first split, because a client computing `mod 64` while the
cluster is at 128 addresses a partition that no longer exists. Sequence:
(1) ship a library that reads P from the map and understands redirects;
(2) verify adoption — no client on an old version, measured, not assumed;
(3) only then split. If any team cannot upgrade, a routing tier in front is the
fallback: it costs a hop and buys independence from the slowest team.

**Losing nothing in the process.** Each split is: freeze writes for that one
partition (milliseconds), record the split point and the new ownership epoch in
ZooKeeper atomically, open the two children, unfreeze. Because no data moves,
there is no snapshot/delta problem for the split itself — the delta machinery
matters for the *rebalancing* afterwards, when children are spread across
machines, and there it is the full v3 protocol including **operations, not
dirty keys**, so deletes cannot resurrect (10.6 hole 2). Deletes deserve
explicit attention here because this is user-document storage: a resurrected
document is a deletion request quietly reversed.

**Reads during the change.** Two hazards, both from 10.6. During a child's
rebalancing move, reads follow ownership, not copy progress — the old owner
serves until the map flips. After the flip, the old owner must **seal** the
partition and answer `MOVED`, or nine teams' cached maps will silently harvest
stale documents. Since this store did not previously have per-partition epochs
at all, adding them is part of the client-library work, not an afterthought.

**Duration and write-blocking.** The splits themselves are fast — the blocked
window is per-partition and measured in milliseconds, so four doublings across
64 → 1,024 partitions is thousands of tiny freezes, not one long outage. The
slow part is optional and afterwards: rebalancing 1,024 small partitions across
24 machines to even things out, throttled, over days, with no write blocking at
all. That split (cheap-and-atomic vs slow-and-background) is the payoff of
keeping the two hops separate.

**Rollback at 40%.** Splits are individually committed, so "rollback" means
*stop*, not *undo* — a cluster with 600 of its partitions split and 424 not is
a perfectly valid cluster, because the map is a table and nothing requires the
partitions to be uniform. That is the property to verify before starting: the
map must express per-partition boundaries rather than a single global P. If it
cannot, fix that first, because a design where the intermediate state is
invalid has no rollback at any percentage.

**The one property that made this expensive:** hop 1 was a formula compiled
into nine independent clients, so changing the shape of the data required a
synchronized change to code owned by other people. **The sentence for the next
design doc:** *the number of partitions is data, not a constant — it lives in
the map, clients read it, and no client may assume it.*
