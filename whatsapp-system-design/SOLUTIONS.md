# Solutions — WhatsApp high-level design

Attempt [EXERCISES.md](EXERCISES.md) first. Rungs 7–8 are worked discussions,
not answers.

---

## Rung 1 — Explain

**1.1** Because in a normal web API, information only moves when the *client*
asks. The recipient's phone is asleep, on a bad network, or with no signal at
all for most of the day — so if the only way it learns about a message is by
asking, it must ask constantly. Two billion phones asking every two seconds is
a billion requests per second, virtually all of which answer "nothing", and the
asking itself drains the battery. The fix is to invert the direction: the phone
opens one connection and the *server* speaks first.

**1.2** One grey tick: *"the service has your message and will keep trying."*
Two grey ticks: *"their phone actually has it."* Neither tells you the person
has looked at it — that is the blue ticks — and neither tells you the person is
holding the phone. Also worth saying: one grey tick says nothing at all about
the recipient, not even whether their account is reachable. It is a statement
about your message reaching the post office.

**1.3** A mailbox is a durable queue holding messages that have been accepted
from a sender but not yet confirmed by the recipient. It is per device because
delivery state is per device: Bilal's phone and laptop go offline at different
times, acknowledge separately, and must each end up with a copy. One queue per
person could not express "the laptop has it, the phone does not".

**1.4** Easier: storage is tiny (a few hundred GB of pending messages for the
whole world, rather than exabytes of history), and the company can truthfully
say it does not hold your conversations. Impossible: server-side search, and
getting your history onto a new phone without your own backup — which is why
that restore flow exists and why it is the user's problem.

---

## Rung 2 — Identify

**2.1** Three copies. Asha's own phone (her local chat), the mailbox row for
`bilal:phone`, and the mailbox row for `bilal:laptop`. Zero copies exist on any
connection server — they hold sockets, not messages.

**2.2** None. That is the entire point: a connection server can be killed at any
moment because losing it loses only sockets, which clients re-establish.
`pushed, awaiting device receipt` is the in-memory list of pushes that have not
yet been acknowledged; an entry leaves it when the device acknowledges, or when
the device disconnects (at which point the push is dead and the mailbox row is
what saves the message).

**2.3** It means the routing table says a device's connection lives on a server
that no longer holds it. The untrue fact is specifically *"that socket is on
that machine"* — the device row itself is not wrong about who the user is, and
the message is not lost. Only the delivery *route* is wrong.

**2.4** In `Server.onSend`: the loop that appends to each mailbox, and the
`net.send(... onServerAck ...)` line. If you acknowledge first and the server
dies before writing, Asha holds a tick for a message that exists nowhere, and
she will never retry because she believes it was accepted. Store first and the
worst case is a duplicate, which the phone already knows how to discard.

**2.5** `Device.send` builds `String id = deviceId + "-" + convId + "-" + seq`.
It has to be on the device because retries originate there. If a server minted
the id, every retry would arrive looking like a brand-new message, the
recipient's deduplication set would never match, and every lost acknowledgement
would become a duplicate on screen.

---

## Rung 3 — Predict

**3.1** One grey tick on all five; five mailbox rows for `bilal:phone`; Bilal's
phone has been told nothing at all — it is not connected, so there is nothing to
tell. On reconnect all five drain in order and the ticks become two.

**3.2** 80 copies reached the device, 10 lines appeared. (More than 20 because
duplicated acknowledgements cause further redeliveries, which are duplicated in
turn — the duplication compounds along the path.) The screen is unaffected.

**3.3** Not lost. Asha reaches one grey tick and stays there. The message sits
in the mailbox; the push is routed at the dead server and evaporates. It stays
that way until the stale session expires and Bilal's phone reconnects somewhere
else — seconds to tens of seconds in production, however long you like in the
simulation.

**3.4** 255 mailbox writes. Naively 255 encryptions *per message*, because with
end-to-end encryption each recipient device needs its own ciphertext. The real
design uses a **sender key**: one symmetric group key, distributed once (255
pairwise encryptions, once), after which each message is encrypted a single time
and the server fans out 255 copies of identical ciphertext.

**3.5** Yes, in order — and nothing sorts them. The order comes from the mailbox
being FIFO and everything being delivered over one ordered connection. Turn
`orderedLinks` off (or, in production, load-balance a device's packets across
several connections) and the order is gone:
`[12, 2, 5, 1, 4, 7, 8, 6, 10, 11, 3, 9]`.

**3.6** The routing table. 50,000 session writes and 50,000 mailbox scans, with
zero messages sent. Connection churn, not message volume, is what sizes this
system.

---

## Rung 4 — Modify

**4.1** 16 of 20; 4 strand permanently. The perfect network does not help
because the retry timer that would have re-pushed them was never created: it is
created by the server that *receives* the hand-off, and the hand-off packet is
the one that died. **Safety is intact** (nothing wrong was shown to anyone);
**liveness is lost**, permanently, for those four messages — which is the worst
kind of liveness failure, because it never recovers on its own.

**4.2** It makes a reconnect storm worse and turns it into an outage. With a
constant delay, every client that failed at the same moment retries at the same
moment, forever — the load arrives as a synchronised spike rather than
spreading out. Overload causes timeouts, timeouts cause retries, and the spike
repeats on a fixed period. Doubling the delay (and, in production, adding
random jitter) breaks the synchronisation. Note the failure is not in the
messaging logic at all; it is in the *timing* of a recovery path.

**4.3** Per-conversation ordering. And no — it was never implemented anywhere.
It was inherited from the transport. That is the general lesson: an inherited
guarantee has no code and no test protecting it, so it disappears silently
during a refactor that looks like a pure optimisation.

**4.4** Longer period: less background work, but the recovery time for a lost
push goes from ~1 second to ~1 minute, which shows up as messages that arrive
suspiciously late for online users. Shorter: faster recovery, more constant
scanning of mailboxes that are almost always empty. What to monitor to choose
it: **the age of the oldest undelivered message for devices that are currently
online**, and **how many deliveries the sweeper is responsible for**. If the
sweeper is delivering a meaningful share of traffic, pushes are failing and the
period is masking a different problem.

---

## Rung 5 — Implement

**5.1** Keep `Map<String senderDevice, Set<String> recentIds>` on the server,
and in `onSend`, if the id is already present, send the server ack and return
without appending or pushing. All 31 checks still pass because the phone's
deduplication was never the thing being tested — this only removes wasted work.

Bound it by **time**, not by count: a sliding window comfortably longer than
the client's maximum retry horizon (the lab retries up to 8 times with backoff
capped at 5 s, so tens of seconds). Bounding by count is dangerous because a
burst from one sender can evict ids that are still live. If the bound is too
small, a late retry gets treated as a new message: a second mailbox write and a
second push — not a correctness failure, because the phone still deduplicates,
but exactly the work you added this for.

The important framing: this is an **optimisation**, and it must never become
the thing correctness depends on. Correctness lives on the receiving device.

**5.2** Add level 1.5 ("first device has it") alongside level 2 ("all devices
have it"). There is no right answer for what to show because the underlying
truth — *some subset of their devices has this* — does not fit a two-state UI.
Showing "first device" over-promises (it may be a laptop they never look at);
showing "all devices" under-promises and can *never* complete if they own a
device that is switched off in a drawer, which is a worse failure because it
looks like the message never arrived. Most products choose first-device, i.e.
they choose to over-promise, because a stuck tick generates far more support
complaints than an optimistic one.

**5.3** The test that matters is the negative one: after expiry, assert the
device receives nothing *and* the sender's tick is still 1. Note what you have
just encoded — the only place in this system where data is deliberately
destroyed. Also note that the sender is never told; there is no "expired" tick,
and adding one would leak whether the recipient's phone has been off, which is
a privacy decision disguised as a UI decision.

---

## Rung 6 — Debug

### `bug=delete-on-push` — 4 checks fail

Fails `durability: the copy is STILL in the mailbox after the push`,
`durability: nothing was deleted on send`, and both `two grey ticks` checks.
Violates **invariant 2**: nothing is deleted before the recipient device
confirms it.

**What a user sees:** messages that vanish. Specifically, a message that was
pushed while the recipient's phone was dying or losing signal is gone forever —
and the sender is stuck at one grey tick, so from their side it looks like the
recipient is merely offline. It is silent data loss dressed as an offline user.

**Caught by monitoring?** Barely. Delivery *rates* look normal. The signal
would be a rising gap between "pushes" and "device acks", which is exactly the
kind of derived metric nobody has until after the incident.

### `bug=no-dedup` — 6 checks fail

Violates **invariant 4**: a device accepts each id at most once. Notice the
duplicate count reaches 34 and 30 messages appear where 20 were sent.

**What a user sees:** the same message two, three, five times in a row — worse
on a bad network, which is exactly when users are least tolerant. This is the
one bug here that is loudly visible.

**Caught by monitoring?** No, but caught instantly by users, which is a
different and much more expensive detector.

### `bug=new-id-on-retry` — 2 checks fail, only under packet loss

Violates **invariant 3**: message identity belongs to the sender. Every retry
becomes a new message, so deduplication has nothing to match on.

**What a user sees:** occasional doubled messages, only on flaky networks.

**6.4 — why it is so hard to see.** On a healthy network there are no retries,
so the bug never executes. It has no effect at all until the network is bad, and
then its effect is probabilistic. Every test on a reliable network passes; every
manual QA pass on office wifi passes; it ships, and the bug reports come from
users on 2G in places the team never tested — described as "sometimes messages
double", which is unreproducible by definition.

The lesson: **for distributed systems, the network is an input, not an
environment.** If packet loss, duplication, delay and partition are not
parameters your tests set on purpose, then an entire class of bugs is invisible
to your whole test suite. That is why `Net` in this lab has `dropRate`,
`dupRate` and `partition` as first-class knobs and why every fault funnels
through one function.

---

## Rung 7 — Handle a new failure (discussion)

**7.1 · Sender's device dies mid-send.** The message must be persisted on the
device *before* it is put on the wire — an outbox on the phone, with the id
already minted. On restore, the client replays unacknowledged outbox entries
with their original ids, and the server-side or device-side deduplication makes
the replay harmless. Note this is the [transactional-outbox](../transactional-outbox/)
pattern with the phone as the database: the local write and the network send
must not be a dual write, so the local write wins and the send is a retry loop
over it. If the backup does not include the outbox, the message is genuinely
lost and no protocol change can help — which is a good illustration that
"exactly once" always bottoms out in somebody's durable write.

**7.2 · Alive but wedged.** The trap is that the sweeper runs on the suspect
server, so every self-report comes from the thing that is broken. You need a
signal from *outside* it. Two that work:

- **Mailbox age, computed by the store, not the server.** The mailbox layer
  knows the age of the oldest undelivered entry per device, and it knows which
  server holds each device's session. Old entries concentrated on one server,
  for devices whose sessions are being refreshed (so they are online), is the
  signature. "Quiet users" do not produce old entries, because quiet users have
  *empty* mailboxes — that is the distinction you are looking for.
- **Acknowledgement latency per server**, from the sender side. Deliveries
  routed through the wedged server stop producing receipts while others do not.

Response: stop routing new sessions to it, then force-disconnect its sockets so
clients reconnect elsewhere. **Risk of being wrong:** you have just caused a
reconnect storm for 1M users (failure F6) on a healthy server. So the action
should be rate-limited and staged — drain slowly, and never let automation drain
more than a small fraction of the fleet at once, because the failure mode of the
detector is correlated across servers.

**7.3 · Acknowledges but cannot store.** The acknowledgement must be sent
*after* the device's own durable write completes, not on receipt — which means
the client's storage failure has to be a real error path, not an exception
swallowed on a background thread. Then a full disk produces no acknowledgement,
the server keeps the mailbox copy, the sender stays at one grey tick, and the
user gets a "storage full" prompt. The general principle is the same one that
governs the server side: **acknowledge what you have made durable, never what
you have merely received.** The bug in the wild is almost always an
acknowledgement wired to the wrong event.

---

## Rung 8 — Design (discussion)

**8.1 · Three datacentres, 80 ms apart.** What survives unchanged: sender-minted
ids, per-device mailboxes, store-before-ack, delete-on-device-ack, device
deduplication, and the sweeper. None of them require any two machines to agree.

What must change: the routing table becomes global-ish. A device's session lives
in one region, and a sender in another region needs to find it. Two workable
designs: (a) **home region per user** — every user's mailbox and session live in
their home region, and cross-region sends are one extra hop to that region;
(b) **replicated session directory** — each region has a copy, accepting that it
is stale, and cross-region routing tolerates the stale answer by falling back to
the mailbox and the sweeper, which it already does.

(a) is simpler and is what the extra latency should buy: sends within a region
are unaffected, cross-region sends pay ~80 ms once. Put the mailbox in the
*recipient's* home region, not the sender's — the mailbox is read repeatedly by
the recipient's connection server and written once by the sender.

The traveller: their device reconnects in a new region, the session row now
points at a server far from their mailbox, and every delivery pays the crossing.
Fix by re-homing lazily after the move persists (do not re-home on a single
reconnect, or a flight becomes a storage migration). The group spanning three
regions: fan-out happens once, into 256 mailboxes spread across three regions;
the sending region does three batched cross-region writes rather than 256
individual ones — batching per region is the optimisation that matters.

**8.2 · Add server-side history.** Storage: at 100 B messages/day and ~200 bytes
each, ~20 TB/day, ~7 PB/year, before replication, indexes and media — three to
four orders of magnitude more than the ~200 GB of pending messages. That is not
an incremental change; it is a different company with a different cost
structure.

Encryption: end-to-end encryption and server-side history are not compatible in
any useful way. You can store ciphertext, but then search, moderation, and
multi-device restore need the keys — so in practice history means the server
holds plaintext, or holds keys, which is the same thing. Meta's own products
split exactly along this line, and that is not a coincidence.

What the user gains: real search, instant history on a new device, no backup
dance, web login that works. What the company can no longer say: that it cannot
read your messages, and that a subpoena would produce nothing.

The three-sentence argument (against, for this product): WhatsApp's entire
differentiation is the claim that it cannot read your messages, and that claim
is a *structural* one — it is true because the data is not there, not because of
a policy. Adding history converts a structural guarantee into a promise, and
promises can be changed by a future management team or compelled by a court.
The user-facing gain is real but is addressable at the edge with device-side
search and better backup, which is the strictly better trade.

**8.3 · One million subscribers.** Fan-out on write is wrong because it is a
million durable writes and a million delivery state machines for one post,
paid every time, and 99% of subscribers may never open it. The ratio is
inverted from chat: readers vastly outnumber writes.

The right shape is fan-out on **read**: store the post *once* in an append-only
per-channel log, and have subscribers pull from an offset. A reconnecting
subscriber sends its last-seen position and gets everything after it — no
per-subscriber queue exists at all. Delivery state moves from the server (a
million queues) to the client (one integer). Push becomes a lightweight
notification "channel X has new posts", not a copy of the content.

Read the shape carefully: this is the Kafka consumer-offset
model, and it is exactly what Discord does for large channels. Note the honest
cost: you lose per-recipient delivery receipts, because there is nothing
tracking each recipient any more. That is the trade, and for a broadcast channel
nobody wants per-subscriber read receipts anyway.

**8.4 · Message plus money.** "Deliver the message, then call the payment
service" is wrong because it is a dual write across two systems with no shared
transaction: the message can be delivered and the payment lost, or the payment
made and the message never delivered, and a crash between them leaves no record
of the intent.

Put an **outbox in the payment service's own database**, in the same local
transaction that records the payment intent. The relay then publishes an event
that (a) moves the money and (b) causes the chat message to be sent. The chat
message is the *last*, non-critical step; the money movement is the **pivot** —
the step after which the saga can no longer be cancelled, only compensated.

Ordering matters and is counter-intuitive: send the message *after* the money
moves, never before. A message saying "£50 sent" that is followed by a failed
payment is a customer-support incident; a payment that succeeds a second before
its notification is invisible. And because chat delivery is at-least-once, the
message must be idempotent at the receiver — which it already is, via the
sender-minted id. See [transactional-outbox](../transactional-outbox/) and
[saga](../saga/).
