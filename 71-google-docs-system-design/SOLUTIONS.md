# Solutions

Try first. Rungs 7 and 8 are worked discussions, not single right answers.

---

## Rung 1 — Explain

**1.1** Something like: *"When we email the file, each person edits their own
copy. When two copies come back, somebody has to pick one — and whatever was
only in the other one disappears. Nobody gets an error; the change is just
gone. Google Docs avoids that by never having two copies: everyone types into
the same document, and each keystroke is sent as its own small change, so two
people's changes can both be kept instead of one replacing the other."*

**1.2** Because an edit is written against a *particular* state of the document,
and by the time it is applied somewhere else that state has changed. "Insert S
at position 3" was correct when Bob typed it into `HAT` and wrong when Alice ran
it against `CHAT`. Order alone does not fix it: applying edits in a consistent
order still applies *the wrong edits*. What is needed is to rewrite each edit for
the state it is actually landing on.

**1.3** Because a network round trip is 50–300 ms and you would feel it on every
single letter — the product would be unusable. Applying locally first means each
person's screen temporarily contains edits nobody else has seen, which is
precisely why a client must transform incoming edits against its own
un-confirmed ones (§10, Rule 2). The whole difficulty of the design is the price
of that one product decision.

**1.4** *Convergence*: when everything has been delivered, every copy shows the
same text. *Intention preservation*: each edit still does what its author meant.
A system with the first and not the second: "when two edits conflict, blank the
document." Perfectly convergent, useless. A subtler one: last-writer-wins on the
whole document — every copy agrees, and one person's work is gone.

---

## Rung 2 — Identify

**2.1** The **server panel** holds the official order — the numbered log.
"Waiting for confirmation: 1" means that client has sent an edit the server has
not acknowledged, so its own screen contains something the rest of the world has
not seen yet. `Bob: insert 'S' at 4` proves the rewriting happened: Bob's finger
pressed S at position 3, and the log records the position his edit *had to
become* once Alice's C was in front of it.

**2.2** Tie-break: `return a.site.compareTo(b.site) < 0 ? a : a.withPos(a.pos + 1);`
in `Ot.transform`. One-in-flight: `if (outstanding == null) { ... } buffer.addLast(op);`
in `Collab.Client.edit`. Retry safety: `if (dedup && seq <= lastSeq.getOrDefault(from, 0))`
in `Collab.Server.receive`.

**2.3** Any feed line reading *"(their own editor rewrote it to …, against their
un-confirmed edit)"*. It needed rewriting twice because two different machines
knew two different things: the server knew the operations already in the log, and
the receiving client knew its own edits that the server had not yet seen. Neither
could do the other's half.

**2.4** "Queued behind it" rises; "revision" does not move at all, because
revision only advances when the server tells you it has. What is sitting in his
browser is one edit that has been sent (or is stuck), plus a queue of edits that
exist nowhere else in the world. Close that tab and they are gone.

---

## Rung 3 — Predict

**3.1** `!AT` on every screen. Alice's delete removes the H; Bob's `!` was meant
to go after the H, and with the H gone it lands at the front. Both delivery
orders give the same answer — verified: whichever edit reaches the server first,
`server="!AT" Alice="!AT" Bob="!AT"`.

Note what happened to intention: Bob meant "after the H". There is no "after the
H" any more. The system picks the nearest surviving meaning rather than asking
anybody, which is the whole product philosophy in one character.

**3.2** Eight. Each pending operation costs two transforms — one to rewrite the
incoming edit past it, one to rewrite it past the incoming edit — and she has
four pending (1 outstanding + 3 buffered). General formula: `2 × (pending
count)` per incoming edit, and on the server side, one transform per log entry
the sender had not seen. That second one is what makes F4 expensive.

**3.3** It converges, and nobody sees a conflict prompt — ever. That is the
guarantee. What can happen to the *meaning* is F9: both sets of edits land
correctly and the resulting sentence is nonsense that neither person wrote. No
algorithm fixes that; visible cursors and presence are the product's answer.

**3.4** 500 of 500. This is the most useful number in the lab. The naive design
is not "risky under load" — it is broken on essentially every run with two
active writers. Anything that appears to work in testing is a scheduling
accident.

---

## Rung 4 — Modify

**4.1** **Safety.** Progress never stops — every edit is accepted, every message
is delivered, the system stays responsive. What breaks is the guarantee that
nothing wrong appears: the copies permanently disagree (`HAT!S` vs `HATS!`) and
no amount of waiting heals it. A liveness bug makes the system stop; this one
makes it lie.

**4.2** Verified: the test run dies with
`IllegalStateException: insert at 5 but doc is only 4 long: "HATS"` inside
`ot_typing_a_burst_faster_than_the_round_trip`. The message points at the
symptom — a position beyond the end of the document — and says nothing about the
cause, which is that two edits both claimed to be written against revision 0
while the second was typed on a screen that already contained the first. That
gap between symptom and cause is exactly why the rule is worth stating
explicitly rather than discovering.

**4.3** Nothing changes because no test in the suite ever retries. Duplicate
suppression only does anything when a duplicate arrives, and the suite never
produces one — the retry paths live in `Experiments.java` instead. The lesson
generalises well beyond this lab: a green suite proves things about the paths it
exercises and nothing whatever about the paths it does not, and idempotency is
almost always in the second category.

**4.4** `ot_two_people_delete_the_same_character` catches it, and the document
becomes `CHA` instead of `CHAS`: the second delete, having no idea its target is
already gone, removes whatever slid into that position — the innocent S.

---

## Rung 5 — Implement

The four cases:

```java
// 1. insert vs insert
if (b.pos < a.pos) return a.withPos(a.pos + 1);
if (b.pos > a.pos) return a;
return a.site.compareTo(b.site) < 0 ? a : a.withPos(a.pos + 1);

// 2. insert vs delete
return b.pos < a.pos ? a.withPos(a.pos - 1) : a;

// 3. delete vs insert
return b.pos <= a.pos ? a.withPos(a.pos + 1) : a;

// 4. delete vs delete
if (b.pos < a.pos) return a.withPos(a.pos - 1);
if (b.pos > a.pos) return a;
return Ot.Op.noop(a.site);
```

**5.2** The two most commonly wrong are the boundary in case 3 (`<=`, not `<` —
an insert *at* the position of the character you are deleting still pushes it
right) and the no-op in case 4. Both are invisible in a two-person demo and
appear immediately under random schedules, which is why the harness runs 1,000 of
them rather than four examples.

---

## Rung 6 — Debug

**6.1** `ot_same_position_inserts_land_in_the_same_order_everywhere` catches it
directly, and `ot_converges_under_1000_random_schedules` catches it in bulk. It
violates **convergence**. It does *not* violate local responsiveness, and
arguably not intention preservation — each person's letter did land where they
meant it; the two copies simply disagree about which came first. That is what
makes it such a good bug: every individual operation looks correct.

**6.2** A crash is a bug that announces itself, in one place, with a stack trace.
This one produces two documents that both look like something a person might
have typed, on two screens that will never be compared, in a system with no
mechanism that ever notices. The users find it days later as a sentence nobody
remembers writing — and there is nothing to debug from, because by then the
divergence has been built on by hundreds of later edits.

**6.3** Verified: the test passes today (random 40-edit run, log of 40 entries,
replay equals the server's document). It is worth writing anyway, because it
asserts something none of the other ten do — that the server's *state* and the
server's *history* still agree. Every existing test compares copies to each
other, so a bug that corrupted the log and the document in the same way would
pass all ten. It is also the property that everything downstream depends on:
version history, "restore to yesterday", crash recovery from a snapshot, and the
whole of F5 assume the log is the truth.

---

## Rung 7 — Handle failure

### 7.1 Automatic retry

Give `Client` a counter of deliveries since it last sent, and re-send
`outstanding` — **with the same sequence number** — when it exceeds a threshold.
Two tests:

- *heals a dropped request:* drop the `ToServer` message, let the timeout fire,
  assert everything converges.
- *does not duplicate on a dropped confirmation:* deliver the edit, drop the
  `ToClient` confirmation, let the timeout fire, assert the document contains the
  character **once**.

The second test only passes if `server.dedup` is on. That is the real answer to
"what must be true of the server": it must be able to tell *this again* from
*another one*, which needs (a) a per-client sequence number that a retry does not
increment, (b) memory of the last number seen per client, and (c) memory of the
*confirmation it sent*, so the retry gets the same revision number back rather
than a new one. Without (c) the client's revision jumps to a value that does not
describe its document.

Timeouts should be jittered. A server hiccup that stalls 5,000 clients for two
seconds will otherwise produce 5,000 simultaneous retries the instant it clears.

### 7.2 "Your revision is too old"

Keep the last K log entries; on a request based on an older revision, reply with
`{snapshot text, revision}` instead of a replay. The client must throw away
**everything** local: its document, its buffer, and its outstanding edit — which
means *the user's un-sent typing is discarded*. That is a genuine data loss, so
in a real product the client would first try to send its queue while it is still
within the window, and only reload if that fails.

What the user loses: unsent edits, cursor position, and undo history. What the
system gains: an upper bound on the work of joining, which turns F4 from
unbounded to O(K). Pick K by the reconnect window you want to support — 30
seconds of typing, not a week's.

### 7.3 Backpressure

Cap the per-client outbound queue; on overflow, disconnect and require a fresh
open (which then hits 7.2's snapshot path).

This breaks promise 2 (convergence) **for that one client, temporarily** — they
are cut off and must reload — in order to protect promises 1 and 2 for everyone
else, and to protect the server's memory. It is the right trade because the
alternative is unbounded memory growth caused by the worst connection in the
room: one person's café Wi-Fi becomes everybody's outage. It is the same call as
evicting a slow consumer from a broker, or shedding load rather than queueing it
([distributed-rate-limiting](../60-distributed-rate-limiting/)).

---

## Rung 8 — Design

### 8.1 Three datacentres, 80 ms apart

**Survives unchanged:** the algorithm. Transformation does not care about
latency; it cares about ordering, and ordering is decided in one place.

**What must move:** the document's owning process lives in exactly one region —
the one where most of its editors are, decided at open time and re-evaluated
rarely. Everyone else's edits pay a cross-region round trip *for confirmation
only*. Their own keystrokes still appear instantly (promise 1 is local by
construction); what they feel is other people's letters arriving ~80 ms later,
which is barely perceptible in a document and would be fatal in a game.

**What must not be attempted:** one owner per region with replication between
them. That is F8 with a longer cable. Two logs means two meanings for "revision
4", and no amount of replication repairs it after the fact. If you genuinely
need multi-region write ownership, you have chosen the CRDT architecture and
should say so explicitly rather than arriving there by accident.

**Storage:** the log must be durable before a confirmation, so a cross-region
synchronous write would put 80 ms on every keystroke's confirmation. Realistic
answer: durable in-region with fast local replication, asynchronously replicated
across regions, and accept that a region loss can lose the last few operations
of documents it owned. State that trade out loud; it is the
[CAP](../24-cap-theorem/) choice, per document.

**Monitor:** confirmation latency split by *client region vs owner region* —
one number hides the whole problem. Also ownership migrations per minute, and
cross-region replication lag.

### 8.2 Offline-first on a phone

Choose a **CRDT**. A week of offline edits against a log that has moved a
million operations is exactly the case where OT's replay cost (F4) and the
"your revision is too old" fallback (7.2) both fail: you cannot ask the user to
discard a week of work, and you cannot cheaply transform against a million
operations.

**The strongest argument against:** memory and storage. Every character carries
an id and a parent, and every deletion leaves a tombstone
(`java src/Crdt.java`: 1,000 cells for 100 visible characters). On a phone,
for a document edited daily for years, that needs real garbage collection —
which is only safe once you know every replica has seen the deletion, which
means tracking every replica, which is its own distributed problem.

**Storage:** the local device holds the full CRDT state, not a text snapshot,
because operations must be applicable against it. Sync becomes "exchange the
operations you have that I don't" rather than "replay the log from revision N".

**Version history:** no longer free. There is no single ordered log to point at,
so "yesterday 4 pm" has to be materialised deliberately — periodic named
snapshots, or a separately maintained server-side ordering.

### 8.3 Two hundred editors in one document

**First thing to fall over:** fan-out, in the owning process. Every accepted edit
is broadcast to 199 others, so 200 people typing at 5 operations/second is
1,000 in and ~200,000 out per second, from one process, with per-client queues
(F6) for every one of them.

**Mitigations, in order:**
1. *Batch broadcasts* — send accumulated operations every 50–100 ms instead of
   one message per operation. Cuts message count by an order of magnitude and
   costs 50 ms of perceived latency, which nobody notices in a document.
2. *Split the roles* — most of 200 people are reading. Serve read-only viewers
   from a replicated stream that is allowed to lag; only actual editors hold a
   session on the owner.
3. *Fan-out tier* — the owning process broadcasts to a small number of relay
   processes, each of which serves a subset of clients. The sequencer stays
   single; only the shouting is distributed.
4. *A hard cap*, honestly communicated in the product.

**Also worth doing at twenty editors:** batching (1) and per-client queue caps —
they are pure wins. (2) and (3) are scale hacks that add moving parts and should
wait until measurements demand them.

### 8.4 A Figma-style canvas

**Rules that survive:** all four, in spirit. One owner per file, edits stamped
with a revision, transform on both ends, deterministic tie-break.

**What changes:** the operation set. Objects have identity — `object 17` — so
most edits are `set(object 17, x, 340)` rather than positional. That makes the
common conflicts *easier*: two people moving different shapes never interact at
all, and two people moving the same shape is a last-writer-wins on one field,
which is a legitimate answer for a position in a way it never is for text.

**What gets harder:** structural relationships. Z-order is an ordered sequence,
so it has the text problem again. Grouping, parenting and "move this shape into
that frame" can produce cycles or orphans when two people restructure at once,
and no positional rule saves you — you need explicit rules like "a delete of a
group wins over a move into it" or a CRDT tree with conflict rules.

**Does it change the OT/CRDT answer?** Yes. Per-object last-writer-wins
registers plus a sequence CRDT for z-order is a natural, small CRDT design with
no positional transformation at all — which is why Figma's multiplayer is built
that way rather than as text-style OT. The text case is the one where OT's
efficiency really pays.

### 8.5 The payments crossover

| in this lab | in a payments API |
|---|---|
| per-client edit sequence number that a retry does not increment | **idempotency key** on the request |
| server remembers the last sequence number per client | idempotency record, stored with the charge |
| server re-sends the *original* confirmation rather than processing again | returning the original response for a repeated key, including the original id |

The fourth mechanism is the one people forget in both worlds: the server must
write its record of "I have handled this" **in the same durable step** as the
effect itself. If the effect is committed and the record is not, the retry does
it twice; that is F5 and the
[transactional-outbox](../48-transactional-outbox/) lab's dual-write problem, in a
third costume.
