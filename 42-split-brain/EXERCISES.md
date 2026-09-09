# Split Brain — Exercises

Eight rungs, from saying it back in your own words to designing a real system. None of
them ask you to write code. Rungs 2 to 6 are all checkable against
[`visualization.html`](visualization.html) — open it in a browser and keep it open.

Worked answers are in [SOLUTIONS.md](SOLUTIONS.md). Attempt each rung before opening
them; for rungs 7 and 8 the "answer" is a discussion rather than a single correct
result, so compare reasoning rather than checking a box.

**Simulator reminders.** The four defences are independent switches. "Damaged" means
a write the database accepted from an authority older than one it had already served.
The default silence limit is 40 ticks, the default freeze is 70 ticks, and the
"cluster" selector controls how many servers sit in each city.

---

## Rung 1 — Explain

No notation, no simulator. Write your answers out; the point is to catch the places
where you can only gesture.

**1.1** Explain to a colleague who has never heard the term: what split brain is, and
why you cannot fix it by making the failure detector more careful.

**1.2** "At most one server believes it is in charge" is the wrong goal. Say why in
one sentence, then state the goal that replaces it.

**1.3** Define each of these in one sentence, in a way that makes clear it is *not*
the others: **partition**, **split brain**, **divergence**, **data loss**.

**1.4** The lab claims fencing "does not prevent split brain, it makes it harmless".
Explain what that buys you and why it is a better target than prevention.

---

## Rung 2 — Identify

Open the simulator. These are all read-off-the-screen questions.

**2.1** Run **scenario 1** (cable cut, heartbeat only). When it finishes: which
servers believe they are in charge, what epoch is each one in, and what does the
database report as the oldest epoch it will accept? Why is that last number what it
is?

**2.2** Run **scenario 9** (freeze, quorum only). Scroll the event log and find (a)
the exact tick at which two servers first both believed they were in charge, and (b)
the exact tick at which that stopped being true. What ended it — a timer expiring, or
a message arriving?

**2.3** Run **scenario 10** (freeze, fencing on). Hover over the squares in the
ledger and find a hollow one. Which server sent that write, what epoch did it carry,
and what epoch had the database already served? Write the comparison out.

**2.4** Still in scenario 10: click on `S1` and read its inspector panel *after* the
run. What does `S1` believe about itself, and what does that tell you about the
difference between the two counters at the top right?

**2.5** Run **scenario 6** (non-transitive partition). Click through several servers
and note their epoch numbers. Compare with a healthy cluster (press Reset, set the
defences to quorum and lease, and run for a while). What is the difference, and what
is it a symptom of?

---

## Rung 3 — Predict

Write your prediction down *before* running each one. Being wrong here is the point.

**3.1** Five servers, 3 London / 2 Manchester. Quorum and lease both on. The leader
is in London. You cut the site link. What does the **Manchester application** see —
success, an error, or a stale success? Why?

**3.2** Same setup, but tick "cut only one direction" so that Manchester's messages
reach London but London's do not reach Manchester. Predict what Manchester's servers
do, and whether London ever notices.

**3.3** Four servers, 2 London / 2 Manchester, plus a witness — but imagine the
witness were placed **in London** rather than Bristol. Cut the site link. Who wins?
Is that cluster a tiebreaker arrangement or something else? What have you actually
built?

**3.4** Healthy network, quorum only, no fencing. You freeze the leader for **30**
ticks with the silence limit at 40. Predict the damage. Then predict it for a **70**
tick freeze. What is the rule that separates the two cases, and how much would you
trust it in production?

**3.5** Quorum, lease and fencing all on, cable cut, incumbent stranded in the
minority. Predict both counters: how many servers will believe they are in charge,
and how many writes will be damaged.

---

## Rung 4 — Modify

Change one thing, predict, run, and explain which property moved.

**4.1** Set the lease length **longer** than the freeze length, then run the freeze
scenario with quorum and lease on and fencing off. Does the damage disappear? What
did you pay for that, and why is this not a real fix?

**4.2** Push the silence limit to its maximum (90 ticks) and run a leader crash. What
gets better, what gets worse, and which of safety or liveness did you trade?

**4.3** Point **both** applications at the same server, then at servers in different
cities, and re-run the cut with heartbeat-only defences. Does the routing choice
change whether damage occurs, or only how much? Which property does client routing
actually affect?

**4.4** Turn **quorum off** and **fencing on** — an unusual combination. Cut the
cable. Predict the two counters, then run it. What does the result tell you about
which defence is doing the safety work and which is doing the availability work?

**4.5** With everything on, set the lease to its minimum (10 ticks) and run a
partition. What happens to the number of writes served, and why?

---

## Rung 5 — Trace

Pencil and paper. Do not run the simulator until you have finished writing.

**5.1** Starting state: five servers `S1..S5`, three in London (`S1 S2 S3`), two in
Manchester (`S4 S5`). `S3` is the leader in **epoch 4**. Every server's silence limit
is 25 ticks. Defences: quorum on, lease **off**, fencing **off**. The London app
writes through `S3`; the Manchester app writes through `S5`.

At `t=10`, `S3` freezes for 60 ticks.

Write out the sequence of events with tick numbers, up to `t=90`. For each step give:
what happened, which server's state changed and how, and what the database's newest
served epoch is. Then state the total damage.

**5.2** Repeat 5.1 with **fencing on**. Write out the same trace. At the moment
`S3`'s first post-wake write reaches the database, write out the exact comparison the
database performs and its result.

**5.3** Starting state: four servers, 2|2, quorum and lease on, `S1` leading in epoch
7 with a lease valid to `t=150`. At `t=100` the site link is cut. Trace what each of
the four servers is doing at `t=110`, `t=155`, and `t=300`, and say what the service
is doing at each of those moments.

---

## Rung 6 — Spot the flaw

Each of these is a described design with a real defect. For each: construct a
**concrete run** with specific values that produces a wrong result, and name the
guarantee that is violated.

**6.1** *"We take a lock in Redis with `SET job:42 worker-a NX PX 30000`, and a
background thread renews it every 10 seconds. To be safe, the worker re-checks that it
still holds the lock immediately before it writes to the database."*

**6.2** *"Our leader-election service issues an epoch number from an in-memory counter,
incremented on each successful election. Every write to the store carries it and the
store enforces monotonicity."*

**6.3** *"Every write carries a fencing token and the database checks it. Reads are
plain reads — there is no point fencing a read, since a read cannot corrupt
anything."*

**6.4** *"We run six coordination servers, three in each of two data centres, because
six tolerates more failures than three."*

**6.5** *"Our two-node cluster uses STONITH: whichever node detects the other has
failed powers it off through its management controller, then takes over."*

---

## Rung 7 — Handle failure

Extend a design on paper so it survives something it currently does not. State your
assumptions explicitly, and state the residual risk — the part you have *not* fixed.

**7.1** Your shared resource is a third-party payment API. It has no conditional
update, no version field, no way to reject a stale caller, and its idempotency support
is a header you are not certain it honours in all cases. You have a five-node cluster
electing a leader correctly, with epochs and leases. Design the safest arrangement you
can, and state clearly what residual risk remains and how you would size it.

**7.2** A client sends a write; the leader accepts it, then dies before replying. The
client retries against the new leader. Make this harmless. What must the *server* add,
and what must the *client* add? Which of the two is load-bearing?

**7.3** Design the break-glass procedure for forcing quorum onto a surviving site
after a real data-centre loss. Write it as steps an on-call engineer would follow at
3 a.m. It must be usable under pressure and must not be usable by mistake.

**7.4** Your leader runs on a JVM with a p99.9 stop-the-world pause of 800 ms and an
observed worst case of 4 seconds. Your failure-detector limit is 5 seconds and your
lease is 10 seconds. State what is currently guaranteed, what is not, and what you
would change first.

---

## Rung 8 — Design

Open-ended. There is no single right answer; there are answers that survive
questioning and answers that do not.

**8.1** **The payments control plane.** Design the component that decides which
instance of a payment-batch processor is currently allowed to submit batches to a
banking partner. Three regions, roughly 50 ms apart, each with its own application
tier. A batch must never be submitted twice. The business will tolerate a submission
being late; it will not tolerate a double submission.

Cover: where the coordination state lives, how many voters and where, how a submitter
proves its authority, what the banking partner has to do (and what you do if it will
not), what happens when one region is isolated, what happens when the coordination
cluster itself is unavailable, what you monitor, and what you would put in the
runbook.

Which parts of this lab's design survive intact? Which must change?

**8.2** **The retail edge.** Two servers in a shop, taking card payments. The WAN link
to head office fails regularly — several times a week, for minutes at a time. The shop
must keep taking payments while it is offline. There is no third machine in the shop
and no budget for one.

Reconcile that with everything in this lab. What guarantee do you give up, what do you
keep, and what mechanism makes the reconnection safe? Explain why a majority-based
design is the wrong tool here and what the right frame is instead.

**8.3** **The review question.** You are reviewing a colleague's home-grown leader
election, written for a service that renames files on a shared network filesystem.
List, in order, the questions you would ask. For each, say what a bad answer sounds
like and what it implies about their data.
