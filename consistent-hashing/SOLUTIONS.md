# Consistent hashing — solutions

Try the exercises first. Rungs 7–8 are worked discussions, not single answers.

---

## Rung 1 — Explain

**1.1** *"It makes every item's location depend on the total number of machines, so
changing that total changes almost every location at once."*

The trap in the question is real: modulo is not slow (0.051 µs, the fastest thing in the
lab) and not unbalanced (1.07×, as good as anything else). It wins four of the five
columns in §12's table. It loses one, and that one is the only one that matters.

**1.2** *"Adding a machine will never disturb an item that is not moving to the new
machine."* Or, operationally: *"a machine joining cannot make any other machine's job
change."*

The reason to be able to say this without the algorithm is that **monotonicity is what
you promise, and the ring is merely how you keep the promise**. Rendezvous hashing keeps
the same promise with no ring at all.

**1.3** Virtual nodes solve:

1. **Steady-state balance** — few random points cut a circle into wildly unequal gaps
   (4.74× at one position each, 1.06× at a hundred).
2. **Failure blast radius** — with one position each, a dead machine's entire share lands
   on one arbitrarily-chosen neighbour (+483% on the machine that happened to be next,
   which in the measured run was the *quietest* in the cluster).

**Lead with the second.** The first is a capacity-planning inconvenience; the second is
the mechanism of a cascading failure, and it is the one a reviewer will not have thought
of. It also answers "why not just use 10 positions?" — 10 fixes most of the balance and
very little of the blast radius.

**1.4** Because a lookup would put a service on the path of **every single data access in
your entire system**. That service is now a hard dependency with your full request rate
pointed at it, it must be replicated, and you have to decide what happens when it is
slow — at which point you have recreated a harder version of the problem you started
with.

If you did allow one lookup, you would build the **fixed-slot design** in §15: a table
mapping slot → machine, published by a coordinator. That is a completely legitimate
answer, and it is what Redis Cluster and Kafka chose. The point is that it is a different
design with different costs, not a cheaper version of this one.

**1.5** `CRC16(key) % 16384` is modulo hashing — it has §4's disease in full. It escapes
the consequences because **16,384 never changes**. The count in the formula is a
constant, not a property of the cluster, so no cluster event can alter any key's slot.
What varies instead is a separate table saying which machine owns each slot, and moving a
slot is an explicit operation.

The cost is a **coordinator**: something must own that table, agree on it, and publish
it. Consistent hashing's entire selling point is not needing one. So the two designs are
a trade of *coordination* for *control* — with a coordinator you get exact placement,
hand-tunable rebalancing and no disagreement window (§11 F4); without one you get
statistical balance and a window bounded by 1/n.

---

## Rung 2 — Identify

**2.1** Whatever the panel says — the point is that it says all four without you doing
any arithmetic. If you had to guess at "which machine would own it under modulo", the
page failed.

**2.2** `Ring.successorIndex`, the line `return lo == positions.length ? 0 : lo;`.

Without it you get **something worse than a crash**: `BrokenRing` bug 3 clamps to the
last index instead, so every key past the top of the ring is silently handed to whichever
machine happens to sit at the end of the sorted array. No exception, no error, every
client agreeing on the same wrong answer. And its severity depends on ring density —
79.6% of keys misplaced on a 2-machine cluster, 0.0% at 8×200 — so it hides in
production shape and appears in test shape.

**2.3** `Placements.Modulo.owner`:

```java
int i = (int) Math.floorMod(Hashing.hash64(key), nodes.size());
```

`nodes.size()`. That is the whole thing.

**2.4** You can see which machine won, and **no, you could not have predicted it, and you
should not be able to.** The winner is decided by where `hash("n3#0")` happens to fall
relative to `hash("n5#0")` — an arbitrary consequence of the hash function with no
meaning at all.

That is worth noticing as a property rather than a curiosity: consistent hashing's
balance is **statistical**, not designed. Nobody chose which machine gets the big slice,
nobody can predict it, and it changes completely if you rename a machine. If you need
placement you can reason about or control by hand, you want the fixed-slot design.

**2.5** The `lookup` column. Rendezvous scores every machine on every lookup, so it is
O(machines) where the ring is O(log positions). Measured across cluster sizes:

| machines | ring | rendezvous |
|---|---|---|
| 10 | 0.254 µs | 0.384 µs |
| 100 | 0.104 µs | 3.462 µs |
| 1,000 | 0.176 µs | 34.179 µs |

At ten machines rendezvous costs 1.5× — irrelevant, and it is the better engineering
choice for its simplicity. At a thousand it costs **194×** and is disqualifying. The ring
won the mindshare because it is the one that keeps working as the cluster grows.

---

## Rung 3 — Predict

**3.1** Modulo **99.0%**, ring **0.9%**, and the floor is **1.0%** — one hundredth of the
keys must move for 101 machines to share them evenly.

The ring is *at* the floor. Modulo does a hundred times the work to achieve the same
result, and the ratio gets worse as you grow: the waste is proportional to the cluster
size.

**3.2** **One machine inherits all of them**, chosen by an accident of the circle rather
than by who has capacity. Measured: n3's 7,996 keys all went to n7, taking it from 1,657
keys to 9,653 — a +483% step change on the quietest machine in the cluster.

With 200 positions each, all seven survivors share it and each takes a 10–19% bump.

**3.3** **The ring: never. Modulo: constantly.**

The reason, which is better than the number: a key's owner is the first machine
clockwise. Inserting a new position can only place a new candidate *in front of* some
keys — it cannot reorder machines that were already on the ring. So the only change
possible is "onto the newcomer".

Measured: of the 181,668 keys modulo moved, **163,679 went sideways** between machines
that had not changed at all.

**3.4** **One machine gets everything and the other seven get zero.**

```
64-bit key hash: {n0=2636, n1=2385, n2=2539, n3=2378, n4=2546, n5=2509, n6=2349, n7=2658}
32-bit key hash: {n0=0, n1=0, n2=0, n3=0, n4=0, n5=20000, n6=0, n7=0}
```

Nothing collided. A 32-bit value spans ~4×10⁹ and the ring spans ~1.8×10¹⁹, so every key
lands in about one hundred-millionth of the circle and one machine's slice contains all
of them. If you predicted "somewhat lumpy", the thing to update is that this is a **range
mismatch**, not a quality problem — a perfect hash of the wrong width fails identically.

**3.5** r=3 returns 3 machines. r=4 returns **the same 3** — not an error.

```
r=3 -> [c, b, a]   r=4 -> [c, b, a]
```

Silently returning fewer is defensible for a placement function, whose job is "where does
this belong" rather than "is your cluster big enough". But it is exactly the kind of
quiet degradation that hides a misconfiguration: a cluster that shrank below your
replication factor keeps working and keeps reporting success, while your actual
durability has quietly dropped. Log it at minimum. See 5.2.

**3.6** Modulo **90.8%**, ring **9.5%**. Same propagation window, same everything else —
the only difference is the blast radius, and it is exactly the remapping fraction from
3.1.

---

## Rung 4 — Modify

**4.1** Measured, 8 machines, 200,000 keys:

| positions | imbalance | ring entries | rebuild |
|---|---|---|---|
| 200 | 1.070× | 1,600 | 4.8 ms |
| 500 | 1.024× | 4,000 | 4.8 ms |
| 2,000 | **1.035×** | 16,000 | 21.1 ms |
| 5,000 | 1.009× | 40,000 | 17.9 ms |

**Look at the 2,000 row: it is worse than the 500 row.** That is not a measurement error
and it is the most useful thing in this table. The guarantee is *statistical*, so the
curve has noise in it, and past a few hundred positions you are deep in diminishing
returns where the noise is larger than the trend. Chasing 1.02× to 1.01× is chasing
something you cannot reliably observe.

Why production sits at 100–200 anyway: **rebuild cost**. The ring is `machines ×
positions` entries, sorted on every membership change, in every client. At 1,000 machines
× 200 that is 200,000 entries; at 5,000 positions it is 5,000,000. The rebuild time above
already quadruples between 500 and 2,000. This is exactly why Cassandra moved its default
from 256 to 16 and added a deliberate allocation algorithm — placing few positions *well*
beats placing many at random.

Correctness never moved. This dial is pure efficiency.

**4.2** Measured, from 10 machines:

```
+1 at once  ->  9.4% moved   (floor  9.1%)
+3 at once  -> 24.0% moved   (floor 23.1%)

one at a time:  step 1 -> 9.4%,  step 2 -> 8.9%,  step 3 -> 7.8%
                26.1% of keys moved in TOTAL, for a net result of 24.0%
```

**Adding three at once moves less total data** — 24.0% against 26.1% — because doing it
incrementally makes some keys move twice, landing on machine A in step 1 and machine C in
step 3. That is 2.1 percentage points of pure waste.

But three separate steps produce **three smaller storms** (9.4%, 8.9%, 7.8%) instead of
one 24% one, and you can pause between them. So the real answer is a genuine trade rather
than a winner: **batch if total transfer volume is your constraint (F8), stagger if the
miss storm is (F3)** — and for a cache it is almost always the storm, so stagger.

**4.3** See the table in 2.5. Only **rendezvous** moves in the ranking, and the `lookup`
column moves it. It is competitive at 10 machines (0.384 µs vs the ring's 0.254 µs) and
hopeless at 1,000 (34 µs).

The crossover is somewhere around **30–50 machines**, but pick it by budget rather than
by the graph: at 3 µs a lookup, a service doing 10 placement decisions per request has
spent 30 µs on arithmetic, which is fine; at 34 µs it has spent a third of a millisecond,
which is not.

**4.4** Measured, with weights 1, 1 and 20:

```
200 positions: small 0.90x  mid 0.99x  big 1.01x    (1.00 = exactly its weight)
  5 positions: small 1.01x  mid 1.68x  big 0.97x
```

At 200 positions the weights hold well. At 5, "mid" gets **68% more than its weight
entitles it to** — the ratios have stopped meaning anything.

The reason: a machine's share is the sum of its gap widths, and a *ratio* between shares
is only as precise as the number of samples underlying it. With 5 positions the small
machines have 5 gaps each; their totals are dominated by luck, not by weight. Weighting
does not need "some" positions, it needs enough for the law of large numbers to
apply — which is the §10 warning, quantified.

**4.5** Every test passes because the ring is internally consistent either way. The
positions are different, but they are *consistently* different — one client using `-`
produces a perfectly valid ring.

It is an incident because a client's ring is determined by three things — the hash
function, the virtual-position naming format, and the position count — and **two clients
that differ on any of the three compute different owners for the same key** (§11 F7).
Ship this change to half your fleet and the two halves silently disagree about every key,
permanently. Symptom: a hit ratio that halves with no other explanation, because each
half keeps populating keys the other half cannot find.

This is why memcached's ketama is a *compatibility contract* rather than an
implementation detail, and why a change to any of the three is a fleet-wide coordinated
deploy, not a refactor.

---

## Rung 5 — Implement

**5.1** `Ring.arcShare()` already does this; the exercise is to check it against reality.
It should track the measured key counts closely, because the arc share *is* the
probability a uniformly-hashed key lands on that machine.

Why it is worth having: it measures balance **without traffic**. You can evaluate a
proposed membership change — adding five nodes, removing a rack — before you make it,
and you can detect a bad position count on an idle cluster. A metric that needs
production traffic to reveal a problem is a metric that reveals it too late. It is also
the honest way to separate the two causes of imbalance: if arc share is even but measured
load is not, you do not have a ring problem, you have a hot key (F5).

**5.2** The walk becomes: collect nodes clockwise, skipping any whose *rack* is already
represented.

The hard part is the fallback, and there are three options:

- **Return fewer than r** — silent, and it means your durability quietly dropped below
  what you configured, with everything still reporting success. This is 3.5's problem
  made worse.
- **Fail the write** — loud and safe, and it turns a partial rack outage into a total
  write outage. Rarely what you want.
- **Degrade explicitly** — place the remaining copies on distinct *nodes* even though the
  racks repeat, and **emit a metric saying you did**.

The third is what production systems do (Cassandra's `NetworkTopologyStrategy` behaves
this way), and the metric is not an afterthought — it is the whole point. The failure you
are guarding against is not "we cannot place the data", it is "we placed the data less
safely than you believe and nobody told you". Whichever you choose, the requirement is
that it is **visible**.

**5.3** The ranges that changed owner are exactly the arcs between the new machine's
positions and the position that preceded each of them. For each such arc, the data must
stream from the machine that *used* to own it (the old successor) to the new one.

While it is in flight, a read has three options and they are the design decision:

- **Read from the old owner until the transfer completes** — correct, and it means the
  new machine's arrival changes nothing until it is ready. This is the right default.
- **Read from the new owner** — fast to implement and returns "not found" for data that
  exists, which is data loss from the caller's point of view.
- **Read from both and merge** — correct and doubles the read cost for the duration.

The subtlety worth stating: **the transfer is not finished when the last byte arrives**,
because writes kept happening during it. You need either a hand-off point where writes go
to both owners, or a final catch-up pass over what changed while you were copying. This
is why joining a Cassandra node is a multi-phase operation with an explicit state machine
rather than a copy.

---

## Rung 6 — Debug

| bug | symptom | caught by | guarantee broken (§12) |
|---|---|---|---|
| 1 — positions from the list index | removing one machine moves **72.0%** of keys; two clients that added machines in different orders disagree about **100%** of keys | `ring_removal_moves_only_the_dead_machines_keys`, `two_clients_building_the_ring_separately_agree_on_every_key`, `with_many_vnodes_a_dead_machines_keys_spread_across_everyone` | #1 monotonicity, #3 no coordination, #4 determinism |
| 2 — replicas not distinct | 45.0% of keys have two "copies" on one machine; 3.7% have all three | `replication_returns_r_distinct_machines`, `replication_degrades_gracefully_when_r_exceeds_the_cluster` | the r-distinct-nodes property |
| 3 — no wrap | keys past the last position silently handed to the wrong machine; 79.6% at 2×1 positions, 0.0% at 8×200 | `ring_wraps_around_past_the_top_of_the_number_space` | #4 determinism, and balance |

**Why each is hard to find in production:**

- **Bug 1** looks perfect in the operation you actually test. See 6.4.
- **Bug 2** produces correct data. Reads work, writes work, nothing errors. You discover
  it the day a machine dies and takes the only copy of 3.7% of your keys. And a small
  test cluster **cannot** reveal it: with one position per machine the walk always lands
  on distinct machines, so the configuration you develop against is immune.
- **Bug 3** never crashes and every client agrees on the same wrong answer, so no
  consistency check fires. Its severity is inversely proportional to ring density, which
  means it is loudest in your test environment and quietest in production — the exact
  opposite of the pattern people's instincts are tuned for.

**6.4 — why the monotonicity test passes.** Because the bug only bites when an index
**shifts**, and appending a machine to the end of the list does not renumber anybody.
`n10` becomes index 10; n0 through n9 keep indices 0 through 9; every existing machine's
positions are unchanged; monotonicity holds perfectly.

The bug appears on **removal** (every index after the gap shifts down) and on **different
insertion orders across clients** (100% disagreement).

What that tells you generally: **a test named after a property does not test the
property — it tests the property under one operation.** The suite had a test called
`ring_never_moves_a_key_between_two_existing_machines` and a bug that destroys exactly
that guarantee, and they coexisted happily, because the test only ever added.

The transferable habit: for every invariant, ask *which operations could violate this?*
and cover each of them — not just the one that is easiest to write. Here that means add,
remove, reorder, and rebuild-from-scratch, and the bug dies on three of the four.

**6.5** Test the property, not a symptom. The wrap is what makes the space circular, so
assert circularity directly rather than hoping a key lands past the last position:

```java
static void the_space_is_a_circle_at_every_density(MiniRing unused) {
    for (int[] cfg : new int[][]{{2,1},{4,1},{8,10},{8,200}}) {
        Ring r = new Ring(cfg[1]);
        for (int i = 0; i < cfg[0]; i++) r.addNode("n" + i);
        List<Object[]> layout = r.layout();
        long lastPos  = (Long) layout.get(layout.size() - 1)[0];
        String first  = (String) layout.get(0)[1];
        // Probe positions we KNOW are past the end, instead of hoping a key lands there.
        for (long probe : new long[]{lastPos + 1, Long.MAX_VALUE, lastPos + 1000000L})
            eq("a position past the last point wraps to the first machine, at "
               + cfg[0] + "x" + cfg[1], first, r.ownerOfPosition(probe));
    }
}
```

Two changes make it density-independent: it **constructs** the positions it wants to test
instead of sampling keys and hoping, and it runs across densities so it cannot pass by
being lucky about ring shape. (It needs a small `ownerOfPosition(long)` accessor, which
is a one-line addition worth making — a test that has to route through the key hash is
testing two things at once.)

**6.6 — a fourth bug the suite would miss.** The hint is the real answer: hunt for a
property nothing asserts, not for a cleverer bug.

**Worth reporting an attempt that failed**, because it teaches more than the success. My
first candidate was: sort the positions but build the parallel `ownerOf` array from the
*unsorted* list, so every key gets a valid machine name that is simply the wrong one.
The reasoning was that the suite only checks aggregates — counts, ratios, distributions —
and a permutation preserves all of them.

It does not survive contact:

```
   FAIL  ring_moves_only_about_one_nth_of_the_keys_when_a_machine_joins
   FAIL  ring_never_moves_a_key_between_two_existing_machines
   FAIL  ring_removal_moves_only_the_dead_machines_keys
   FAIL  two_clients_building_the_ring_separately_agree_on_every_key
   FAIL  with_many_vnodes_a_dead_machines_keys_spread_across_everyone
   17 passed, 5 failed
```

Five tests, because the permutation is not stable: inserting a position shifts the
mis-mapping for every entry after it, so the *membership-change* tests catch what the
*steady-state* tests cannot see. That is a genuinely useful thing to learn about this
suite — it is stronger than it looks, precisely because so many of its tests assert
behaviour *across a change* rather than at rest.

**A bug that does survive — and it is in `Ring.java` right now.** `Ring` has two ways to
add a machine: `addNode(name)` and `addNode(name, weight)`. The first does
`weights.put(node, 1)`. So when a machine that was registered with a weight re-registers
through the plain path — a heartbeat, a reconnect, a config reload, a node rejoining
after a blip — **its weight is silently reset to 1**:

```
after setup            : small=41,841  big=158,159  (ratio 3.78x)
after big re-registers : small=101,424  big=98,576  (ratio 0.97x)
```

Your 4× machine just became a 1× machine. Nothing throws, nothing logs, the ring stays
perfectly valid and every client agrees on it. What you observe in production is that a
large expensive box is mysteriously half-idle while the small ones run hot — with no
event to correlate it against, because the re-registration was routine.

All 22 tests pass, because **not one of them adds the same machine twice.** That is the
uncovered property: the suite tests add, remove and rebuild, but never *re-add*.

The test:

```java
static void re_registering_a_machine_keeps_its_weight() {
    Ring r = new Ring(200);
    r.addNode("small", 1);
    r.addNode("big", 4);
    Map<String, Integer> before = counts(r, KEYS);
    r.addNode("big");                       // a heartbeat re-registers it
    Map<String, Integer> after = counts(r, KEYS);
    double ratioBefore = before.get("big") / (double) before.get("small");
    double ratioAfter  = after.get("big")  / (double) after.get("small");
    ok("a machine that re-registers keeps the weight it had (" + ratioBefore
       + " -> " + ratioAfter + ")", Math.abs(ratioAfter - ratioBefore) < 0.5);
}
```

And the fix is a design decision, not a one-liner: either `addNode(name)` preserves an
existing weight (`weights.putIfAbsent`), or the single-argument overload is deleted so a
caller must always state the weight. **The second is better** — the bug exists because two
methods quietly disagree about what "add" means, and removing the ambiguity removes the
whole class of mistake rather than this one instance of it.

**The general lesson, which is the point of the rung:** the bugs that survive a good
suite are not the clever ones. They live in the *sequences of operations nobody wrote a
test for*. This suite covers add, remove and rebuild thoroughly, and re-add not at all —
so that is exactly where something was waiting.

## Rung 7 — Handle failure

### 7.1 — Surviving the miss storm anyway

The ring cuts the storm from ~99% to ~1/n. On a large fleet 1/n is still enormous, so:

| technique | what it buys | what it costs | what it cannot do |
|---|---|---|---|
| **Add nodes one at a time, with a pause** | turns one 24% storm into three ~9% ones (measured in 4.2) | slower expansion; ~2 points more total data movement | help if even 1/n exceeds your database's headroom |
| **Pre-warm the new node** before it joins the ring | near-zero storm | you must know which keys it will own — computable from the ring in advance — and pay to fetch them | protect against an *unplanned* join, i.e. a node returning after a failure |
| **Serve from the previous owner during a grace period** | zero misses; the new node fills in behind | clients need two rings (old and new) and a rule for reading both | help if the previous owner is the one that died |
| **Rate-limit misses to the origin** (single-flight, a queue, a circuit breaker) | hard ceiling on database load | some requests get slow or shed | keep your hit ratio up — it protects the database by sacrificing the request |

**What to build first:** the last one, because it is the only one that bounds the damage
from failures you did not plan, and because it protects you from every *other* cause of a
miss storm too. Note that single-flight is already familiar from
[caching](../caching/) — one expired hot key turning 200 concurrent readers into 200
database calls is the same shape at a smaller scale.

The honest summary: consistent hashing turns "your database is about to fall over" into
"your database will notice". That is a large improvement and it is not a guarantee.

### 7.2 — Being correct with a stale ring

Reads and writes need different answers, which is the main insight.

**Reads** can be made correct cheaply: on a miss, **also ask the machine the other
plausible ring says**. During a membership change a key has at most two candidate owners
(the old successor and the new one), so a miss can fall back to the other before going to
the origin. Cheap, bounded, and it converts the whole failure into a slightly slower read.

**Writes** cannot be patched this way — a write to the wrong owner is not detectably
wrong, and it stays invisible until a reader who needed it comes looking. The options:

- **Write to both candidates** during a membership change. Correct, doubles write cost,
  needs everyone to agree a change is in progress.
- **Version the ring** and have the server reject writes carrying a stale version, so the
  client refreshes and retries. This is the fencing-token idea from
  [redis](../redis/)'s F7, applied to membership: the *receiver* checks, because only the
  receiver knows the truth.
- **Accept it**, if the data is a cache and a lost write is a future miss.

**What to build first on a fixed budget:** ring versioning on writes. It is the only one
that turns a silent wrong answer into a loud retry, and silence is the actual problem —
extra latency you will find in a dashboard; a write that went to the wrong machine you
will find in a customer complaint six weeks later.

### 7.3 — Moving the data

A safe join, in order:

1. **Announce the joiner as "joining", not "a member".** It gets no reads and appears in
   no client's ring yet.
2. **Compute what it will own** — from the ring, in advance, which is exercise 5.3.
3. **Stream those ranges** from the current owners, throttled so it does not compete with
   live traffic.
4. **Double-write** during the transfer: writes to those ranges go to both the current
   owner and the joiner. Without this, everything you copied is stale by the time you
   finish.
5. **Catch up** on anything that changed before double-writing began.
6. **Flip membership**, so clients begin routing to the joiner.
7. **Only now** let the old owners drop the transferred ranges — and not immediately; keep
   them for a grace period so a client with a stale ring still gets an answer (7.2).

**If the joiner dies halfway:** nothing has been lost, because the old owners still hold
everything and are still authoritative. This is why step 7 is last and why "delete after
transfer" is a separate, delayed decision. The cost of getting the order wrong is
permanent data loss; the cost of being conservative is disk.

**If a write arrives mid-transfer:** step 4 is the answer, and the ordering matters —
start double-writing *before* you start copying, not after, or you have a window whose
writes land only on a machine that is about to stop being the owner.

---

## Rung 8 — Design

### 8.1 — The placement layer

**200 nodes, 3 DCs, 4 PB, r=3, 400 KB objects.** That is ~10 billion objects and ~13 TB
of primary data per node before replication.

- **Strategy: the ring.** At 200 nodes rendezvous costs 3.5 µs per lookup (measured) and
  an object store makes placement decisions constantly. Fixed slots would also work well
  here and are worth considering if a coordinator already exists — see 8.5.
- **Positions: ~100–200 per node.** 200 × 200 = 40,000 entries, which is nothing to hold
  and fast to rebuild. Do not go to 1,000: 4.1 shows the balance gain is inside the noise
  and the rebuild cost is not.
- **Replicas: one per datacentre**, using the rack-aware walk from 5.2. With r=3 and 3
  DCs this is the natural mapping and it survives losing an entire DC. Do *not* just take
  the next three distinct nodes — they can easily be three nodes in one DC.
- **Membership: gossip**, with an explicit version number on the list, because 7.2's
  write-side fix depends on being able to name a ring version.
- **Alerts:** (1) per-node capacity as a ratio to the mean, above 1.3×; (2) any node
  receiving zero traffic — the loudest and only symptom of F6 and F7; (3) streaming
  backlog during membership changes; (4) how many distinct ring versions are live at
  once, which makes F4's window visible rather than inferred.

**At 20 nodes instead of 200:** switch to **rendezvous**. At that size its lookup cost is
irrelevant (0.384 µs) and you delete the position count, the sorted array, the rebuild
path and an entire class of tuning bug. Smaller clusters should use the simpler
algorithm, and the reason they usually do not is that everyone reaches for the famous one.

### 8.2 — Adding 50 nodes to a 200-node cluster in a week

**What actually happens if you do it at once.** 50/250 = **20% of the entire dataset**
must move — 800 TB at 4 PB. That streaming competes with live traffic for disk and
network on both ends (F8), so latency rises for as long as it runs, which at any sane
throttle is days. Meanwhile every cache in front of it takes a 20% cold hit at the moment
membership flips (F3), and the membership change itself propagates over a longer window
than usual because the list changed by a quarter (F4).

**What to do instead:** add them **in batches of five, with the cluster fully settled
between batches**. Each batch moves ~2% instead of 20%.

The quantified argument, from 4.2: incremental costs ~2 percentage points more total data
movement, because some objects move twice. In exchange you get ten small, individually
recoverable events instead of one large irreversible one — and critically, **you can stop
after batch three** if latency degrades, which you cannot do halfway through a single
20% rebalance.

The sentence to say out loud to whoever asked for a week: the constraint is not the ring
and not the machines, it is disk and network bandwidth against live traffic, and that
does not care how many people want it done by Friday.

### 8.3 — The reply about the hot key

> I don't think consistent hashing will help here, and I want to be clear about why
> before we spend a sprint on it. Every scheme we might switch to — the ring, rendezvous,
> jump hash — agrees that one key has exactly one owner. That agreement is the feature.
> So a single product ID that is 30% of traffic will still be 30% of traffic on one
> machine afterwards, and adding machines still will not help, because sharding
> distributes *keys* and we have a problem with *one* key.
>
> What will actually help, cheapest first. **Put a small in-process cache in front of it**
> with a one-second TTL — for a key this hot, one second of staleness collapses ~30% of
> our cache traffic to roughly one fetch per app server per second, and it is a few lines.
> If we need more, **split the key** into `product:X:0` through `product:X:9`, write to
> all ten and read from a random one; that spreads it across ten machines at the cost of
> ten times the write amplification for that key, which for a read-heavy product page is
> a good trade.
>
> Consistent hashing is still worth doing, for the reason we found last month — the
> miss storm when we scale the fleet. It is just a fix for a different problem, and I'd
> rather we adopted it for the one it actually solves. Shall I do the local cache this
> week and we look at the rest after?

### 8.4 — Migrating from modulo without a storm

The trap: switching 20 servers from `% 20` to a ring in one deploy remaps ~95% of keys
(measured: 20→ring is essentially a total reshuffle) — the exact storm you are adopting
consistent hashing to prevent, suffered once on the way in.

**The approach: read from both, write to the new, for one TTL.**

1. Ship a client that computes **both** placements. Writes go to the ring's owner only.
   Reads try the ring's owner, and on a miss fall back to the modulo owner, and on a hit
   there, populate the ring's owner.
2. Deploy that to the whole fleet. The dual-read makes the hit ratio hold flat — every
   key is found either where it is going or where it was.
3. **Wait one full cache TTL** (plus margin). By then every live key has been read at
   least once and re-populated at its new home, or expired.
4. Ship a second client that drops the fallback.

Cost: reads are slightly more expensive during the window, and memory usage roughly
doubles for one TTL because keys exist in both places. Both are temporary and cheap.

**Do not** try to migrate the data directly. These are caches; the source of truth can
regenerate them, and a read-through migration is self-healing where a copy is a
distributed transaction you do not want. And pin the ring's three parameters (hash,
position naming, position count) in the *first* deploy — if the two client versions
disagree on any of them, you have shipped F7 into the middle of your migration.

### 8.5 — Consistent hashing vs fixed slots, with etcd already running

**For consistent hashing:** no new dependency on the data path. Placement is a pure
function, so a client with the machine list is never blocked, never waits, and etcd being
slow or unavailable cannot stop a single request. Adding a node is one list entry.

**For fixed slots + an assignment table:** you already run etcd, so the coordinator is not
a new cost — it is a thing you already operate, monitor and back up. In exchange you get
**exact** placement rather than statistical: you can move one slot off a struggling node
by hand, drain a node deliberately, place slots to satisfy a rule the hash cannot express
(no two replicas in one rack, this tenant on those nodes), and you get **no disagreement
window** because there is one authoritative table version (F4 disappears). Rebalancing
stops being an emergent property and becomes an operation with a plan and a progress bar.

**I would pick fixed slots**, for 40 nodes with etcd already in place. The decisive
argument is not performance — it is that during an incident you can *look at the table*
and see exactly which node owns what, and *change it*. Consistent hashing's answer to
"why is node 17 hot?" is "the hash function decided that", which is true and useless at
2 a.m.

**The single fact that would change my mind:** if the clients are not all ours — a
polyglot fleet, third-party consumers, or anything embedded that we cannot redeploy on
demand. Then the coordinator becomes a distribution problem across code we do not control,
and the pure function that every client can compute independently wins on the spot.
