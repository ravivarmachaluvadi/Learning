# Solutions — eventual consistency

Rungs 7–8 are worked design discussions, not single correct answers.

---

## Rung 1

**1.1** "If everyone stops writing and you wait long enough, every replica ends up
serving the same value; until then, no promises." Clause 1 — **delivery** (every
update keeps being propagated until it is everywhere) is the *time* half: it makes
"eventually" arrive. Clause 2 — **decision** (replicas holding the same set of
updates must serve the same thing) is the *agreement* half: it makes the arrival
mean one answer.

**1.2** The week proved: under last week's load and last week's network, the
window was small enough that nobody looked through it. It cannot prove the
guarantee, because the promise is about an unbounded future ("eventually") and the
dangerous behaviors live in conditions QA didn't create: the [linearizability] lab
measured a design with *no* guarantee showing **0 violations in 600 calm runs**
and 109/600 under network jitter. Green under calm conditions is a statement about
the conditions, not the store. What to tell the PM: "we measured the window, and
we know which flows are safe *because of the measurement*, not because of the
week of green."

**1.3** The Monotonic-reads judge can point at a *finished thing that already went
wrong*: read A saw a notice, read B (later, same person) hit a board that has
never heard of it — no future event can un-happen that pair. The EC judge's
promise is about what will *eventually* be true; at any moment you accuse it, the
future in which it comes true is still allowed, so the accusation can never be
proven from what has happened so far. The first kind of promise is called
**safety** (finite witness), the second **liveness** (no finite witness).

## Rung 2

**2.1** Delivery clause: the **"Copies on the road"** strip plus the sync-round
lines in the event log — that's the machinery making "eventually" arrive.
Decision clause: each replica card's **Holds vs Displays** pair — the set, and the
resolver's function of the set. The measured-but-never-promised number: the
**staleness histogram** (and each board's staleness bar) — the consistency window
as data.

**2.2** Its "had seen" list contains only the old Saturday notice — **not** Ben's
"market cancelled". So Asha, posting "Sunday", never observed "cancelled": the two
writes are concurrent, and LWW's timestamp order between them is manufactured, not
causal. A write is only *knowingly* replaced when some later write's "had seen"
list includes it; "Sunday" (the write that dies in scenario 3) appears in no
later write's context — which is exactly the Lost-updates judge's rule for
calling it lost rather than superseded.

**2.3** The decision clause: *same set ⇒ same display*. Identical **Holds** rows
with different **Displays** rows are the finite witness that the display is a
function of arrival history rather than of the set — the one provable breach of
eventual consistency.

## Rung 3

**3.1** **No judge objects — that is the answer.** Ben reads the same board twice
(sticky): a board's own set only grows, so monotonic reads holds; he wrote
nothing, so read-your-writes holds; EC is open; lost-updates pending. He is
arbitrarily stale the entire time and every session judge is green. Lesson:
session guarantees are about *self-coherence*, not freshness — a consistently
stale reader violates nothing. (Freshness lives in the staleness meter, i.e. in
measurement.)

**3.2** Worst: first read hits a fresh board (say R1), later reads hit boards the
write hasn't reached — the first miss is a **read-your-writes** violation and,
because she'd already *seen* her write, also a **monotonic-reads** violation; with
three random reads she can collect several of each. Best: all reads land on R1
(or after the 2 s forward window) — zero violations. The single change: sticky
routing (in real systems: session affinity or a session token, the
[causal-consistency] lab's 3759→0 result). Storage untouched in both worlds.

**3.3** With forwarding healthy the gap closes in **2 s** (travel time). The two
racing mechanisms: v1 forwarding (fast, gives up after one try) and the cadenced
sync round (slow, re-offers everything). The number that matters operationally is
the *fallback*: if the forward dies, staleness runs until the next round — so the
cadence you configure is your staleness ceiling **under failure**, which is how
you should choose it.

## Rung 4

**4.1** The delivery clause is now false — "eventually" will never arrive. No
judge notices because every judge rules on *events* (reads, settlements), and a
liveness breach produces no event: reads still answer, writes still ack, nothing
diverges *visibly* unless someone happens to read a behind replica and even that
is legal. The only signal that can notice: monitoring the **machinery itself** —
time since last successful sync round, replication backlog *age*, an injected
write-then-read-everywhere probe. Alarm on heartbeats, because the contract has
no pulse.

**4.2** With every clock +60, Ben's write stamps ts = 60 and Asha's later write
stamps ts = 75 — **the genuinely-newer write now wins**. LWW ordering depends
only on *relative* skew; a uniform absolute error is harmless to who-beats-whom.
(It is not harmless generally: anything comparing stamps to real time — TTLs,
"as of 5 p.m." reads, cross-system merges — inherits the +60 lie; the
[hybrid-logical-clocks] lab's drift-bound story is this distinction at full
depth.) The scenario-3 disaster needs clocks that disagree *with each other*.

**4.3** Writes accepted at R2 or R3 can never reach R1 (once their one forward is
lost, nothing re-offers them to R1) — the delivery clause is broken for exactly
the writes that don't originate at the hub. Real-world costume: one-way
hub-and-spoke repair ("we only repair from the primary DC"), or a firewall/ACL
that lets sync traffic flow one direction. With forwarding on it is *masked* —
everything looks fine until the first lost forward, which is the worst kind of
misconfiguration: one that only acts under failure.

## Rung 5

Stamps: "Sunday" at R1, t=2, skew 0 → **ts 2**. "cancelled" at R2, t=4, skew −30
→ **ts −26**.

- **t=6, Ben reads R2.** R2 holds {Saturday ts0, cancelled ts−26}. LWW takes the
  max: **"Saturday" wins — ts 0 beats ts −26.** Ben's own board refuses to display
  the write it just accepted: the slow clock stamped his notice *into the past*,
  where it loses even to the ancient default. (Read-your-writes is *technically*
  satisfied — his write is in the set — the resolver just out-voted it.)
- **t=10 sync round.** All pairs exchange; every board now holds
  {Saturday ts0, Sunday ts2, cancelled ts−26} and displays **"Sunday"** (ts 2).
- **t=12, Ben reads R3:** "Sunday". No monotonic violation — R3's set contains
  everything Ben had seen.
- **t=20 sync (nothing to copy), settle:** converged on **"Sunday"** —
  EC judge: KEPT. **Lost-updates judge: "cancelled" died** — it is displayed
  nowhere and appears in no later write's context (nobody who wrote afterwards
  had seen it), so it was dropped unknowingly by the resolver.

The wrinkle worth keeping: a *slow* clock is as dangerous as a fast one — it
doesn't delay your writes, it **back-dates** them into defeat.

## Rung 6

The run: all three replicas hold `k = v, ts 5`. User deletes `k` on R1 → R1
removes the row. Next anti-entropy: R2 offers `(k, v, ts 5)`; R1 has *no row* for
`k`, so there is nothing to compare against — "missing key" looks identical to
"key I never heard of", and R1 adopts `v` again. One round later every replica
has `k = v` back: **the delete is undone everywhere, permanently.**

Missing mechanism: a delete must be **data, not absence** — a **tombstone**
(`k = ⟨deleted⟩, ts 9`) that replicates and competes in LWW like any write, plus
a GC grace window and the repair-deadline rule (rung 7.2). Prior-lab terms:
tombstone and `gc_grace_seconds` from [quorums-and-replication]/[btree-and-lsm],
and the resurrection story is README F6.

Is it still eventually consistent? **Yes** — and that is the instructive part.
The state is a deterministic function of the update set, replicas converge, every
clause holds. The delete was never an *update* in the system's model, so the
contract cannot see it. "Eventually consistent" certifies convergence over the
updates the design chose to represent; it says nothing about whether your intent
was represented at all.

## Rung 7 (worked discussion)

**7.1** A monitoring page that beats the customer to each failure:

| Failure | Signal | Why "compare replicas, alert on divergence" misses it |
|---|---|---|
| F1 window too wide | **Injected t-visibility probes**: canary write, read back from every replica, plot the delay distribution; run some probes with injected latency/faults | A compare check samples instants and exercised keys; the probe measures the window itself, tail included |
| F2 non-monotonic sessions | Enforce affinity/session tokens and alert on token-validation failures; optionally a roaming canary client | Divergence between replicas is *normal* here; the failure is in routing, which replica comparison never sees |
| F3 LWW clock loss | **Clock-skew gauge per node** (NTP offset) with alert + eviction | The store converges *promptly and wrong* — there is no divergence to find; only the clock reveals the danger |
| F4 non-deterministic resolver | Periodic assertion: same content digest ⇒ same served value, across replicas | Plain divergence checks that only compare *stored* data pass; you must compare digest AND what is served |
| F5 dead machinery | **Age** alarms: time since last successful repair round per range; backlog age (not size); oldest-undelivered-update age | Divergence accumulates in keys nobody reads and the comparer may sample none of them; a dead daemon with a quiet workload compares clean |
| F6 resurrection risk | Node-down duration vs GC grace; repair-completion-within-window compliance; block re-admission past the deadline | Nothing diverges until *after* GC has destroyed the evidence — by then the check confirms the resurrected value as "consistent" |

The falsifying query for "our store is healthy": the **t-visibility probe under
injected network stress**. Every other green is compatible with a calm network
doing you a favor — the lesson measured four labs running ([linearizability]'s
0/600-when-calm), now applied to operations.

**7.2** A delete becomes a tombstone write with its own timestamp, replicated and
resolved like any update. Its lifetime must exceed *(longest guaranteed interval
between completed full sync rounds) + (longest replica outage you will tolerate
re-admitting)*. The rule that ties them: **every board must complete a full sync
round within the tombstone lifetime — a board cut off longer forfeits its set**:
it re-enters empty and re-syncs from the others (the bootstrap rule, same as
[crdts]' never-restore-from-backup). Cassandra ships exactly this contract as
`gc_grace_seconds` + "repair every node within gc_grace".

## Rung 8 (worked discussion)

- **(a) Profile-photo display:** EC + **session token** (read-your-writes for the
  author — the one person who notices staleness), LWW register resolver (conflict
  = same user racing themselves; losing one is acceptable). SLO: sign something
  like "99.9% of reads fresh within 1 s cross-region", and measure it with canary
  probes *including fault-injected runs*, not with production complaint counts.
- **(b) Shopping cart:** **SEC — an OR-Set CRDT**, add-wins. The [crdts] lab
  measured why: 2P-set re-add fails 0/100, OR-set 100/100, and merge survives any
  delivery disorder. Cart taps must work during partitions and merge across
  devices; refusing a cart add to get serializability is the wrong trade — this
  is the flow where **strong-everywhere is the design error** (80 ms × quorum on
  every tap, and unavailability during partitions, protecting data whose
  conflicts merge trivially).
- **(c) Password change + "log out everywhere":** the credential record needs a
  **strong (linearizable) write and read path** — single ordering authority
  (leader/consensus-backed, or a single-region primary). "Log out everywhere" is
  a cross-replica invariant, and the [crdts] warehouse (−2 flour) is the proof
  that no coordination-free design can enforce one. Practical shape: strong
  store for credentials/sessions + revocation events pushed to validators;
  validators may cache with a *bounded* staleness only if the product accepts
  "logged out within T" — and that T goes in the contract, not in a wish.
- **(d) Balance next to "withdraw":** split the flow. The *display* may be EC or
  bounded-staleness **with honesty** ("as of 12:01"). The *withdraw decision*
  must never be computed from an EC read (README F7): the write path enforces the
  invariant — a conditional/serializable debit at the strong ledger
  ([optimistic-vs-pessimistic-locking]'s conditional write, or a real
  transaction). An EC balance read + unconditional debit is the textbook silent
  money-loser.

Grading yourself: full marks is not the strongest contract everywhere — it is a
*different* contract per flow, each justified by what the data tolerates, plus
the two honesty artifacts: a measured staleness SLO, and an explicit list of
flows forbidden from deciding on EC reads.
