# Solutions — CRDTs

Every number below came from running the lab. Where an answer is a design
discussion rather than an output, it is marked as such.

---

## Rung 1 — Explain

**1.1** Your phone doesn't send "I added milk"; it sends the whole list, and the
list it sends happens not to contain the item your colleague added while you
were both offline. The server cannot tell "she deleted eggs" from "she never
heard of eggs", so keeping the newest list quietly destroys the other person's
work. Nothing errors, because from the server's point of view nothing went
wrong.

**1.2** Store the evidence rather than the conclusion, and make the rule for
combining evidence one that doesn't care about order, grouping or repetition.
Then two machines that have seen the same events are automatically holding the
same thing, without anyone having to decide who was right.

**1.3** Wrong verb: CRDTs don't *resolve* conflicts, they make the state
*unable to conflict*. Two concurrent updates are both representable in the
merged state by construction, so there is no resolution step at runtime — the
work was done at design time, in the shape of the data.

**1.4** Idempotence (`a⊔a = a`) is what makes a re-delivered message a no-op, and
at-least-once transports re-deliver by definition. Commutativity kills
out-of-order arrival; associativity kills different batching/relaying paths.
Measured: sum-merge (idempotence broken) reads **11** where the truth is **2**,
after ten deliveries of one message — `java src/Experiments.java 2`.

---

## Rung 2 — Identify

**2.1** Amira holds `milk(amira#1)`; Ben holds `eggs(ben#1)`. Whoever received
the one delivered message holds both. The receipts are the point: they are two
independent facts, not two versions of one fact.

**2.2** Two (or three) distinct versions, and it is not a failure because
strong eventual consistency only promises sameness *once the same set of updates
has been received*. Three messages are still on the wire; the promise has not
come due yet.

**2.3** The clock reading decides. What is absent is any record of *which items
were decisions*: the list `[bread, eggs]` cannot distinguish "milk was deleted"
from "milk was never seen", which is exactly the information the receipts carry.

**2.4** `OrSet.remove`:

```java
if (observed != null) nextKilled.addAll(observed);   // INVARIANT: observed-remove
```

If it tombstoned every dot for the value, past and future, it could not — dots
that other replicas have not minted yet do not exist to be named. If you
approximate it by blacklisting the *value*, you have rebuilt the 2P-Set and
`orset_readd_after_remove_works` fails. (See 4.1 and 6.4.)

---

## Rung 3 — Predict

**3.1** It reads **5** again, and nothing is lost. Ben's row comes back as
`max(1, 2) = 2`. This is the point of experiment 10 in a smaller form: a
state-based replica is repaired by an ordinary merge, because a healthy peer's
snapshot dominates the stale one. It works *because* the restore moved Ben's
state down and the merge moved it straight back up — which is also why the
opposite (restoring a replica whose peers have all been wiped) is not
recoverable at all.

**3.2** Milk is **gone**. Ben's remove tore up both `amira#1` and `ben#1`,
because both were dots Ben had observed. Observed-remove is about *what you
have seen*, not *who added it*. Contrast with the concurrent case, where
Amira's new dot is unobserved and therefore survives.

**3.3** A min-merge is still commutative, associative and idempotent — it is a
perfectly good join on the *reversed* order. So yes, it converges. But the local
update (increment) now moves the state *down* that order, which breaks
**monotonicity**: every increment a replica makes is subsequently undone by the
next merge, and the counter converges toward the minimum. The lesson: the three
laws guarantee *convergence*, and convergence alone tells you nothing about
whether the thing converges to something useful. You need the laws **and** local
updates that climb the same order.

**3.4** It holds **`["Oak Rd, Flat 3"]`**, alone. Cara's write joined both
version vectors and bumped her own entry, so its clock strictly dominates both
siblings; re-delivering Amira's old copy adds a candidate that is immediately
dropped as an ancestor. Verified by
`mvregister_a_write_that_saw_both_collapses_them`.

**3.5**
(a) **Still passes** — 500/500 converge with 90% of messages dropped. Losing
messages costs latency, never data, because every sync carries the whole state
and a later message subsumes every lost earlier one.
(b) **481 of 500 "diverge"** — with only two lossy rounds and no healthy ones,
most sessions simply have not finished exchanging yet.

They are not in conflict, and the wrong word is **"diverge"**. (a) measures
*cannot ever converge*; (b) measures *has not converged yet*. Confusing those
two is the single most common misreading of an eventually-consistent system, and
the same mistake in monitoring produces alerts that fire on healthy clusters.

---

## Rung 4 — Modify

**4.1** Measured, with the naive patch in place (drop the whole value if any of
its dots is tombstoned):

- The scenario test `concurrent add vs remove: the add wins` fails — which is
  not a bug, it is the behaviour you asked for.
- The *fixed-sample* law check (the five hand-written states) passes it
  completely.
- The randomised law check breaks: **associativity fails on 1,696 of 50,000
  random triples.** Commutativity and idempotence survive.

Two lessons, and the second is the more valuable one:

1. It really is broken — just not in the sample states a hand-written law check
   happens to use. A fixed handful of samples is not a proof. (`Tests.java`
   runs a 50,000-triple randomised law check for exactly this reason, and that
   check also found a genuine idempotence gap in the lab's own `OrSet.add` —
   see the INVARIANT comment there.)
2. The real answer: **it cannot be fixed inside `merge`, because the information
isn't there.** Add-wins falls out of *"a remove may only destroy adds it has
observed"*. Remove-wins requires the opposite — a remove must be able to kill
adds it has **never seen**, including ones minted after it. To express that, the
state must record the remove as a *causal claim* ("everything for this value up
to version V is dead"), not as a list of specific dead dots — which means a
version vector per removed element rather than a tombstone per dot. Riak's
ORSWOT uses exactly this machinery, which is also how it avoids storing one
tombstone per delete.

The README sentence that told you: *"An add is not a value. It is a unique event
— and a remove destroys events, never values."* Remove-wins needs a remove that
destroys something other than events.

**4.2** Measured: **5 failures** out of 45.

```
FAIL  G-Counter: idempotent
FAIL  PN-Counter: idempotent
FAIL  the same message five times still says 2
FAIL  +5 then -2 across two replicas = 3
FAIL  stock goes NEGATIVE (-2) — no global invariant
```

Note what still passes: **commutative and associative**. Sum is a perfectly good
commutative, associative operation — it is *only* idempotence that breaks, which
is why this bug is invisible until a message is delivered twice.

And that is the answer to the second half: on a healthy network with no
duplicates, the broken counter passes. **A CRDT test suite that does not
duplicate messages is not testing the property that matters.** The law checks
catch it without any network at all, which is why `Tests.java` runs them first.

**4.3** **Commutativity** breaks. Smallest scenario: two writes with the *same*
timestamp and different values — `x = "blue"@100`, `y = "green"@100`. Then
`x.merge(y)` = blue and `y.merge(x)` = green, forever. `java src/Tests.java
broken` prints exactly that. The reason it matters: "same millisecond" is not
rare on a busy service, and the divergence is permanent and silent.

**4.4** The **clock reading** in Amira's dashed box (`clock=…`). With the skew
on, her stamp is 60,000 higher than real time, and merge compares nothing else
first. That single number is the entire conflict-resolution algorithm — which is
why "we'll just use last-write-wins" is a decision to let NTP decide your data
model.

---

## Rung 5 — Implement

**5.1** The blank starts at **2 / 5** (associativity and idempotence pass
trivially for `return this`). The reference implementation:

```java
@Override public YourMerge merge(YourMerge other) {
    TreeSet<Dot> nextTorn = new TreeSet<>(torn);
    nextTorn.addAll(other.torn);                       // 1. learn ALL tombstones first

    TreeMap<String, SortedSet<Dot>> nextLive = copy(live);
    other.live.forEach((item, dots) ->                 // 2. union the live receipts
        nextLive.computeIfAbsent(item, k -> new TreeSet<>()).addAll(dots));

    nextLive.values().forEach(d -> d.removeAll(nextTorn));   // 3. THEN subtract
    nextLive.values().removeIf(Set::isEmpty);
    return new YourMerge(nextLive, nextTorn);
}
```

**5 / 5.** The ordering of steps 1 and 3 is the whole exercise: subtract before
you have learned the other side's tombstones and you get `Broken.LeakyOrSet`,
whose result depends on merge direction.

**5.2** Reproduces experiment 7: **10 visible items, 500 dots stored (250 of
them tombstones), 50.0 records per item.** The number to internalise is that it
scales with *operations ever performed*, not with data size — a long-lived,
frequently-edited cart is unbounded.

**5.3** A grow-only unique-visitor set is a `GSet` of visitor ids (or, for real
scale, a HyperLogLog — which is itself a CRDT: merge is per-register max, and it
obeys all three laws). You cannot build a shrinking one the same way because
removal moves the state *down* the order, and merge would restore it. Removal
requires either the two-pile trick (and its permanent-death problem) or
per-event identities (and their metadata cost). There is no third option.

---

## Rung 6 — Debug

**6.1 `SummingCounter`** breaks **idempotence**. Shortest wrong answer: Amira
+1, Ben +1, sync once = 2 (correct), deliver the same message once more = **3**.
After ten deliveries: **11**.

**6.2 `LeakyOrSet`** breaks **commutativity**. Two replicas: `a` holds
`milk(amira#1)`; `b` is `a` after `remove("milk")`. Then
`b.merge(a)` = `[]` and `a.merge(b)` = `[milk]`. The correct answer is **`[]`**
— the remove observed `amira#1`, so it must stick. The bug is subtracting
tombstones you knew about before unioning in the ones you just learned.

**6.3 `TielessLww`** breaks **commutativity**, but only on exact timestamp
ties. A test suite with hand-written distinct timestamps never triggers it; a
production service doing thousands of writes per second per millisecond triggers
it constantly. This is the general shape of CRDT bugs: they are invisible on the
happy path and permanent when they fire, which is why the *law* checks matter
more than the scenario tests. All **3 of 3** planted bugs are caught by the laws
alone, with no network simulation.

**6.4** Not implementable: the dots another replica *will* mint do not exist
yet, and a replica cannot name them without coordinating — which is the one
thing this whole family gives up. To do something like it you would need a
causal claim rather than a list of dots ("everything for `milk` up to my version
7 is dead"), which is precisely 4.1's answer and precisely what ORSWOT stores.

---

## Rung 7 — Worked design discussions

### 7.1 Tombstone garbage collection

**The fact that must be true:** every replica that will ever merge this state
has already seen the tombstone. That is **causal stability**. If it holds, the
tombstone can never again be needed — there is no surviving copy of the add for
it to cancel.

**The mechanism:** each replica publishes a version vector of what it has seen;
a replica takes the *pointwise minimum* across all members ("the stable
frontier") and may free any tombstone whose dot is below it. Membership and
liveness come from gossip ([`gossip-protocols`](../gossip-protocols/)); the
frontier is a lattice too, so it converges by the same rules.

**Dropping one too early:** the add it was cancelling gets re-delivered by a
replica that never heard about the delete, and the deleted item **comes back,
permanently**. This is the single worst CRDT failure in production because it
looks like data corruption and cannot be undone by retrying.

**A permanently unreachable replica:** the minimum stops advancing and GC stalls
forever. Every real system therefore has an eviction policy: after a timeout, a
silent replica is removed from the membership and must re-bootstrap from scratch
if it returns. That eviction is a *coordination* decision — note that even a
coordination-free data type needs a coordinated membership service around it.

**The check:**

```java
boolean safeToDrop(Dot d, List<VersionVector> whatEveryoneHasSeen) {
    return whatEveryoneHasSeen.stream()
        .allMatch(vv -> vv.entries().getOrDefault(d.replica(), 0L) >= d.counter());
}
```

The subtlety worth naming: this is safe only if the list really is *everyone*.
An incomplete membership list turns a correct-looking check into the
too-early-drop failure above.

### 7.2 Restore from an old backup

A *wiped* replica is safe (experiment 10: recovers to 60 in one sync) because
empty is the bottom of the lattice — merging can only climb out of it.

An *older non-empty* state is only safe for a type whose merge cannot be
confused by it. `GCounter` survives: `max(old, new) = new`. `OrSet` survives:
unioning old receipts back in is harmless, because any that were tombstoned are
subtracted again by tombstones the peers still hold.

Where it breaks is any type whose state is not purely accumulative — including,
critically, a **garbage-collected** OR-Set. Restore a replica from before a GC
run, and it re-introduces adds whose tombstones have since been freed. The item
returns from the dead, everywhere, permanently. Same mechanism as 7.1's
too-early drop, arriving by a different door.

**The operational rule:** never restore a CRDT replica from a backup into a live
cluster. Bootstrap it **empty** and let it re-merge. A backup is for
total-cluster-loss recovery only.

### 7.3 Sibling explosion

The number of siblings grows with concurrent writes that did not read each
other. The client-side fix is **read-before-write**: every write carries the
causal context it read, which lets it dominate and collapse those candidates.
That is not an optimisation, it is how a write earns its place in the order —
the [`quorums-and-replication`](../quorums-and-replication/) lab hit the exact
opposite bug, where writing with an *empty* context made every write's vector
identical and the store ignored them all.

The read-side behaviour that makes it worse: a client that reads, sees siblings,
picks one to display, and then writes back **without** merging the others. Each
such write looks concurrent to something, so the sibling set never shrinks. Cap
the sibling count, alert on the 99th percentile, and treat "value with 500
siblings" as a code bug in the client, not a storage problem.

---

## Rung 8 — Worked design discussions

### 8.1 Offline-first cart for a payments product

**CRDTs:**
- **The cart** — an OR-Set of line items, keyed by product id, **add-wins**.
  Justification: an item that reappears is a support email; an item that cannot
  be deleted is a chargeback.
- **Quantity per line** — a **PN-Counter** per item, not an LWW integer, so
  "+1 on the phone" and "+2 on the tablet" both survive.
- **Saved-for-later, wish list, recently viewed** — OR-Sets. Nothing here has a
  cross-replica rule.
- **UI preferences, address, delivery note** — LWW-Registers. Losing a
  concurrent edit to a delivery note is acceptable; say so out loud in the
  design doc rather than letting it be an accident.

**Not CRDTs, and what they use instead:**
- **Stock / inventory** — a single-writer or consensus-backed store. Experiment
  in `Sim.java` scene 5: two partitioned warehouses each sell 6 of 10 bags and
  the merged stock is **−2**. A PN-Counter cannot enforce `stock ≥ 0`, ever.
- **Payment capture** — one authoritative ledger, idempotency keys, exactly-once
  semantics. An append-only ledger has no conflicts to resolve, which is why
  [`event-sourcing`](../event-sourcing/)'s expected-version check is the right
  tool: it *refuses* the conflicting write rather than preserving it.
- **Price** — server-authoritative, read-only on the client. A merged price is
  a fraud vector.
- **Coupon "one use per customer"** — a uniqueness invariant. Consensus, or a
  conditional write on a single-partition store.

**The boundary:** the cart is a *proposal*; checkout is a *decision*. Everything
before the tap of "Pay" is a CRDT and syncs freely; the tap crosses into the
coordinated world, where the server re-prices, re-checks stock, and can say no.
What crosses the boundary is a cart snapshot plus an idempotency key.

**What the user sees on disagreement:** items merge silently (both survive, and
that is the desirable outcome). Quantities merge silently. If checkout fails
because stock ran out while they were offline, they see an explicit
"1 of these is no longer available" screen at checkout — never a silently
altered cart, because a cart that changes itself destroys trust in the number
next to the payment button.

**Three launch metrics:** (1) cart metadata size, p99 per user — the tombstone
tax, which is what will page you first; (2) sync lag between regions, p99 — the
disagreement window, and the number that explains every "my phone and my laptop
disagree" ticket; (3) checkout stock-rejection rate — the rate at which the
offline promise is meeting the coordinated world, and the leading indicator that
your CRDT/consensus boundary is drawn in the wrong place.

**What would get you fired:** making stock, price, or payment a CRDT. Those need
the ability to say *no*, and a CRDT structurally cannot.

### 8.2 CRDT or OT for a document editor

Offline for a week decides it. OT requires each edit to be transformed against
the operations it was written without knowing about, and the cost is roughly
(edits missed × edits made) — the
[`google-docs-system-design`](../google-docs-system-design/) lab measured
**8,200** transforms for a client that missed 200 edits while making 20. A week
offline makes that unbounded, which is why real OT products tell the client
"your revision is too old" and force a reload — unacceptable when the whole
promise was working on a plane. OT also needs one server per document deciding
the official order, so the laptop cannot merge with a colleague's laptop
directly. A CRDT needs neither, at the cost of metadata: this lab measured
**2.0 cells per visible character** after light editing, and the google-docs lab
measured **1,000 cells for 100 visible characters** under heavy churn. Accept
the memory and the compaction work; take Automerge or Yjs rather than writing
your own, because the parts you would get wrong (causal delivery, GC,
encoding) are the parts they have already got right.

### 8.3 The CRDT you should not build

**Why it cannot work.** A seat reservation is a uniqueness invariant: seat 14C
may be held by at most one person. Enforcing that requires being able to *refuse*
a booking, and refusal requires knowing what everyone else has done — which is
exactly the coordination an offline-capable data type gives up. Any CRDT you
build will faithfully record that two people booked 14C and converge on a state
where both did, the same way `Sim.java` converges on −2 bags of flour. The
merge is not wrong; there was never a rule for it to check.

**What to build instead.** Keep the offline experience for everything that is
not the invariant: browsing, seat maps, party size, preferences, and a *held
intent* ("I want 14C") that syncs as an OR-Set entry and is explicitly labelled
as not yet confirmed. Confirmation is a single coordinated write against the
authoritative seat map — a conditional write or a short lock — with an
idempotency key so a retry over a bad connection cannot double-book. The user
sees "requested" and then "confirmed", which is honest, and on the rare loss
they see "14C was taken; here are two seats next to each other". The general
rule: **let the CRDT carry the intent, and let consensus carry the commitment.**
