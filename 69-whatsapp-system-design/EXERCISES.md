# Exercises — WhatsApp high-level design

Eight rungs, easiest first. Attempt each before opening
[SOLUTIONS.md](SOLUTIONS.md). Rungs 7 and 8 are design discussions — there is
no single correct output, and the solution file argues a position rather than
giving an answer.

Everything runs with no build tool:

```bash
java src/Tests.java                 # 31 scenario checks
java src/Experiments.java <name>    # trace offline crash partition dup group ordering storm capacity
open visualization.html
```

---

## Rung 1 — Explain

No notation, no diagrams. Say these out loud or write them in a few sentences.

**1.1** Why can a chat app for two billion people not be built the way you would
build a normal web API? Answer in terms of what the *recipient's* phone is doing.

**1.2** Explain the difference between one grey tick and two grey ticks to
someone non-technical, in a way that is actually true. Then say what neither of
them tells you.

**1.3** In one sentence each: what is a mailbox, and why is there one per
*device* rather than one per person?

**1.4** The system deletes your messages from its servers as soon as they are
delivered. Name one thing that gets much easier because of this, and one thing
that becomes impossible.

---

## Rung 2 — Identify

Open the [visualization](visualization.html), press **Send a message**, and
step through it.

**2.1** At the moment Asha's screen shows one grey tick, how many copies of the
message exist, and where exactly is each one? (Click the *Mailboxes* box.)

**2.2** Click *cs2*. What durable state does a connection server hold? What does
`pushed, awaiting device receipt` mean, and when does an entry leave that list?

**2.3** In the routing table, what does the ⚠ **lie** marker mean? Crash a
server to produce one, and say precisely which fact has become untrue.

**2.4** In [`src/Server.java`](src/Server.java), find the two lines that must
never be swapped. Why does their order decide whether a tick can lie?

**2.5** In [`src/Device.java`](src/Device.java), find where a message id is
created. Why does it matter that this line is in `Device` and not in `Server`?

---

## Rung 3 — Predict

Commit to an answer, then run the command.

**3.1** Bilal is offline. Asha sends five messages. What does her screen show,
how many mailbox rows exist, and what has Bilal's phone been told?
`java src/Experiments.java offline`

**3.2** Every packet is delivered exactly twice, and Asha sends ten messages.
How many copies reach Bilal's device, and how many lines appear on his screen?
`java src/Experiments.java dup`

**3.3** The connection server holding Bilal's socket crashes *before* Asha
sends. Is the message lost? What does Asha's screen show, and for how long?
`java src/Experiments.java crash`

**3.4** One message to a 256-person group. How many mailbox writes? Now: how
many *encryptions* would the sender do, naively — and how does the real design
avoid it? `java src/Experiments.java group`

**3.5** Twelve messages are delivered in one burst after a reconnect. Nothing in
the design sorts anything. Will they be in order? Under what change would they
stop being? `java src/Experiments.java ordering`

**3.6** 50,000 phones reconnect in the same second, having sent no messages at
all. Which subsystem does the most work? `java src/Experiments.java storm`

---

## Rung 4 — Modify

Change the code or the switches, predict what breaks, then confirm it — and for
each, name whether what you lost was **safety** (something wrong happened) or
**liveness** (nothing wrong happened, but progress stopped).

**4.1** In [`src/Tests.java`](src/Tests.java), remove the
`c.startSweepers(...)` line from `lossyNetwork_30pctDrop_...` and re-run. How
many of the 20 messages arrive? Explain why a *perfect* network afterwards does
not rescue them.

**4.2** In [`src/Device.java`](src/Device.java), change `scheduleRetry` so the
delay is a constant 800 ms instead of doubling. Run
`java src/Experiments.java storm`. What kind of incident does this make worse,
and why does the constant delay make it *self-amplifying*?

**4.3** In the visualization, switch **One ordered connection** off and send a
burst. Which guarantee disappears? Was that guarantee ever implemented anywhere
in the code?

**4.4** In [`src/Server.java`](src/Server.java), change the sweeper period from
1200 ms to 60,000 ms in the test that uses it. What gets worse, what gets
better, and what would you actually monitor to choose this number in production?

---

## Rung 5 — Implement

**5.1 · Server-side duplicate suppression.** Today, when Asha's client retries,
the server appends the same message to the mailbox again (it is deduplicated
later, on the phone). Add a small per-sender set of recently seen message ids to
`Server`, so a retry that arrives after the message is already stored is
acknowledged *without* a second mailbox write and without a second push.

Constraints: all 31 existing checks must still pass, and the set must be
bounded — write down what you would bound it by and what breaks if the bound is
too small.

**5.2 · A "sent to at least one device" receipt.** Bilal has a phone and a
laptop. Add a third tick level that fires when the *first* device acknowledges,
distinct from the level that fires when *all* of them have. Which one should the
UI show, and why is there no obviously right answer?

**5.3 · Mailbox expiry.** Implement the 30-day retention rule as a sweep that
drops entries older than N simulated milliseconds, and add a test proving that
an expired message is never delivered and that the sender is never upgraded past
one grey tick.

---

## Rung 6 — Debug

Three bugs are planted behind flags. Each one disables exactly one invariant
from stage 12 of the [README](README.md#12-what-is-promised-what-is-not-and-what-was-sold-to-get-it).

```bash
java src/Tests.java bug=delete-on-push
java src/Tests.java bug=no-dedup
java src/Tests.java bug=new-id-on-retry
```

For **each** bug, answer:

**a.** Which checks fail? **b.** Which invariant is violated? **c.** What would
a *user* see — describe the visible symptom, not the test output. **d.** Would
this bug be caught in production by monitoring, or would it be silent?

Then the harder question: **6.4** — `bug=new-id-on-retry` makes the client mint
a fresh id on every retry. It fails only two checks and only under packet loss.
Explain why a bug that destroys the deduplication guarantee is so hard to see,
and what that tells you about testing distributed systems on healthy networks.

---

## Rung 7 — Handle a failure the system does not currently survive

**7.1 · The sender's own device dies mid-send.** Asha types a message, her phone
sends it, and the phone dies before the server ack arrives. When she gets a new
phone and restores from a backup, what should happen to that message? What state
must be persisted on the device, and where, for the answer to be "it is sent
exactly once"?

**7.2 · A connection server that is alive but wedged.** It holds sockets, its
heartbeat still refreshes its routing rows, but it has stopped pushing. Nothing
in this lab detects that: the sweeper runs *on that server*. Design the
detection. What signal proves the difference between "these users are quiet" and
"this server is not delivering"? What do you do once you are sure — and what is
the risk of being wrong?

**7.3 · A device that acknowledges but cannot store.** The phone's storage is
full. It acknowledges, the mailbox copy is deleted, and the message is gone.
What must change in the protocol so the acknowledgement means what it claims?

---

## Rung 8 — Design

Full production prompts. Aim for a structure like stage 15.7: requirements,
capacity, high-level design, the one or two deep dives that matter, failure
modes, what you monitor.

**8.1 · Three datacentres, 80 ms apart.** Users are homed to their nearest
region. Design cross-region delivery. Which parts of this lab's design survive
unchanged, which must change, and where does the extra latency land? What
happens to a user who flies from one region to another, and to a group whose
members span all three?

**8.2 · Add server-side message history**, as Messenger has and WhatsApp does
not. Walk through what changes: storage volume at 100 billion messages/day,
the encryption consequences, what becomes possible for the user, and what the
company can no longer truthfully claim. Then argue, in three sentences, for or
against.

**8.3 · Broadcast channels: one sender, one million subscribers.** Explain
concretely why the fan-out-on-write design in this lab is the wrong answer, and
design the right one. Where does your design put the copy, and what does a
subscriber's reconnect look like?

**8.4 · A payments-style twist** — connecting to
[transactional-outbox](../48-transactional-outbox/) and [saga](../47-saga/): a message
must be delivered *and* trigger a money movement, and neither may happen without
the other. Where would you put the outbox? What is the pivot step? Why is
"deliver the message, then call the payment service" wrong?
