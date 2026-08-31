# Anti-Entropy — Exercises

Eight rungs, easiest first. Everything here is pencil-and-paper or the simulator
(`visualization.html`); nothing asks you to write code. Answers in
[`SOLUTIONS.md`](SOLUTIONS.md) — attempt first, especially rungs 3 and 6, where being
wrong is the point.

Shorthand used throughout: **A**, **B**, **C** are the three shops (replicas); **W**
is how many must confirm a write; **R** is how many are asked on a read.

---

## Rung 1 — Explain

**1.1** In your own words, in four sentences, with no jargon: why does a database that
keeps three copies of everything need a background job at all? Someone who has never
heard of replication should follow it.

**1.2** A colleague says: *"We use quorum reads with R+W>N, so our replicas stay in
sync."* One sentence: what is wrong with that sentence, and what would you say
instead?

**1.3** Why is the mechanism called *anti-entropy* rather than *repair* or *sync*?
What does the name insist on that the other two words let you forget?

**1.4** Explain a Merkle tree to someone who has never seen one, using the stocktake
picture from the lab, in three sentences, without the word "hash".

---

## Rung 2 — Identify

Open the simulator. Press **Close C**, then **Set this price** (croissant, 4.00), then
**Reopen C**.

**2.1** Look at the verdict box at the top. What number does it show, and what exactly
is that number counting? Is it counting the same thing as the "rows the shops hold
different records for" figure? When are the two different?

**2.2** In shop C's list, which item is highlighted, and what colour? What does
clicking it tell you that the shop card does not?

**2.3** Now press **Compare and repair** with A ↔ C selected and watch the descent.
In the comparison panel: how many boxes are green, how many are red, and how many are
faded out? What does a faded box mean about work the two shops did *not* do?

**2.4** Look at the five cost tiles. One of them does *not* get smaller when there is
less to repair. Which one, and why does that matter for anyone running this system?

---

## Rung 3 — Predict

Answer before you touch the simulator, then check.

**3.1** Settings: hints ON, window 180 minutes. You close C, change three prices,
press **+1 hour**, then reopen C. How many rows are divergent? Now the same thing but
with **+4 hours**. Why does the second number differ?

**3.2** Settings: hints ON. Close C. Change three prices. Now **close A and reopen
A** — a ten-minute restart, well inside the hint window. Then reopen C. Predict the
divergence, and say which mechanism failed and why.

**3.3** One item differs between A and C. Set the checksum setting to *2 ranges — 8
items each* and repair. Predict the "rows sent" tile before pressing. Then set it to
*16 ranges — 1 item each* and repeat. Predict both numbers, and the number of hashes
in each case.

**3.4** Set R to 1 and leave read repair ON. Close C, change the price of a croissant,
reopen C, then press **A customer asks the price** repeatedly, twenty times. Predict
what happens to the divergence figure. Explain your answer in terms of what a read
repair needs in order to do anything at all.

**3.5** Two shops are in perfect agreement and no writes are happening. Predict the
five cost tiles for a repair between them. One of them will not be zero — which, and
what is the real-world name for that cost?

---

## Rung 4 — Modify

Each of these changes one setting. For each: what breaks, why, and — name it
explicitly — is the property lost a *safety* property (something bad happens) or a
*liveness* property (something good stops happening)?

**4.1** Set the hint window to 0 minutes.

**4.2** Turn headstones OFF. Delete an item while C is closed, reopen C, repair.

**4.3** Set the merge rule to *whoever received it wins*. Create a divergence and
repair the same three shops in two different orders (use **Step back** to return to
the same starting point). What differs? Note carefully whether the system converges.

**4.4** Set shop C's clock 120 minutes fast. Have C set a price, then immediately have
head office set a different price. What is on the shelves, and what is unusual about
*when* the wrong outcome was decided?

**4.5** Set W to 1 and R to 1. Which of the three repair layers still does anything
useful, and which have been switched off in all but name?

---

## Rung 5 — Trace

Do these on paper, then check against the simulator.

**5.1** Starting state, all timestamps in minutes since opening:

```
A:  tart = 8.50 @ ts 40      B:  tart = 8.50 @ ts 40      C:  tart = 4.40 @ ts 0
```

Merge rule is newest-wins; the tree has 16 ranges, one item each. Trace a repair
between A and C: write down the root comparison, every descent, every pruned branch,
the leaf that mismatches, the rows transferred, and the final state of all three
shops. Then state the divergence figure *before* and *after*, and say why it is not
zero after a single pairwise repair in every possible starting state.

**5.2** Same starting state, but the tree has only 2 ranges of 8 items. Redo the
trace. How many hashes, how many rows, and which rows were transferred despite being
identical on both sides?

**5.3** Trace this one carefully; it is the zombie:

```
t=0    A, B, C all hold  eclair = 4.60 @ ts 0
t=10   C closes
t=11   eclair is discontinued. Headstones ON, grace = 360 minutes.
t=500  headstones are cleared on A and B
t=501  C reopens
t=502  repair A ↔ C
```

Write out the record held by each shop at t=11, t=500, t=501 and t=502. At which
single step did the outcome become inevitable, and what would have had to happen
before that step to prevent it?

**5.4** A cluster is configured with `gc_grace_seconds` = 10 days and a repair cycle
that takes 6 days when everything is healthy. Repair fails silently on one keyspace on
day 1 and is noticed on day 12. Trace what may now be wrong with the data, and say
what you would check first.

---

## Rung 6 — Spot the flaw

Each of these is a described design, not code. Find the planted mistake, construct a
concrete run where it produces a wrong answer, and name the guarantee that breaks.

**6.1** *"To save memory we compute one checksum per replica over the whole key range.
If the two checksums differ we stream the entire range. It is simpler than a tree and
uses a constant amount of memory."* — What is the actual behaviour of this design on a
cluster taking continuous writes?

**6.2** *"Our repair is one-directional to keep it simple: the replica with the higher
node ID sends its rows to the lower one, which merges them in. Running it between
every pair covers everything."* — Construct a three-replica run where a value is lost.

**6.3** *"We do not need tombstones. Our repair marks a row as deleted if it is missing
on a majority of replicas."* — Give a concrete sequence where this deletes live data,
and a second where it resurrects deleted data.

**6.4** *"We skip the hint window entirely and keep hints forever, so no write is ever
lost."* — Give two distinct reasons this does not achieve what it claims, one about
storage and one about correctness.

**6.5** *"Our repair job builds the tree on replica X, then walks to replica Y and
builds it there, then compares. If they match, we log 'clean'. Our dashboard shows
clean runs 4% of the time, which we track as a data-quality metric."* — What is that
4% actually measuring?

---

## Rung 7 — Handle failure

Design work on paper. There is no single right answer; there is a right set of
concerns.

**7.1** A replica has been down for eleven days. `gc_grace_seconds` is 10 days. It is
now healthy and about to rejoin the cluster. Write the runbook: what do you do, in
what order, and what specifically must you *not* do? Justify each step by naming the
failure it prevents.

**7.2** Over-streaming is costing you: a nightly repair moves 400 GB to fix an
estimated few thousand rows. You cannot change the database. List every lever you
have, and for each, what it costs you elsewhere.

**7.3** Your product needs deletes to be permanent within 24 hours for a compliance
requirement, so somebody proposes setting `gc_grace_seconds` to 24 hours. Full repair
currently takes 30 hours. Explain the collision to a product manager in plain
language, then give two designs that satisfy the requirement, with their trade-offs.

**7.4** Design a monitoring scheme for anti-entropy on a cluster you operate. What
would you alert on, at what threshold, and what would the page say? Include at least
one alert for a failure that produces *no* symptoms until it is too late.

**7.5** Your merge rule is last-write-wins on client-supplied timestamps. One
application server's clock drifts 4 seconds ahead for a week before anyone notices.
Describe what is now wrong with your data, whether repair helps or hurts, and how you
would find the damage.

---

## Rung 8 — Design

**8.1** Design the storage layer for a product catalogue: 40 million items, three
datacentres 80 ms apart, five replicas, heavy reads and modest writes, with prices
that must never be silently wrong. Which parts of this lab's design survive as-is,
which must change, and what exactly do you monitor? Be specific about the merge rule
and justify it.

**8.2** You are designing a system where deletes are frequent — a message store where
users delete 30% of what they send. Walk through how tombstones, `gc_grace_seconds`,
repair cadence, and read latency interact here, and design a scheme that keeps all
four acceptable. Say what you would give up.

**8.3** Design an anti-entropy mechanism for a system where each replica holds 2
billion rows and a full disk scan takes 9 hours. You may not scan on every repair.
What would you build, what state would you maintain, and how would that state itself
be kept honest? (Riak's answer is one option; there are others.)

**8.4** Argue the other side: describe a real system where you would deliberately run
*no* anti-entropy at all, and say precisely what property of that system makes it
unnecessary — and what would have to change for the answer to flip.
