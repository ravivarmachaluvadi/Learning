# CQRS — Exercises

Eight rungs, easiest first. Every one is reasoning, tracing, or design — none
asks you to write code.

Work them before opening [`SOLUTIONS.md`](SOLUTIONS.md). Where an exercise says
*check it*, the check is a control in `visualization.html`; do the reasoning
first, then let the page score you.

Notation used throughout: **λ** = commits per second, **μ** = projector
capacity, **ρ** = λ/μ, and *the window* = the time between an event committing
on the write side and becoming visible in a read model.

---

## Rung 1 — Explain

**1.1** Explain to a colleague who has never heard of CQRS why it exists, in
under 120 words, without using the words *command*, *query*, *projection* or
*eventual*. Then say which of §1's two problems your explanation covered — the
contention or the arithmetic — and add a sentence for the other.

**1.2** The lab claims the read model is *allowed* to be stale. What grants
that permission? Answer in one sentence, then give the smallest change to a
system that would revoke it.

**1.3** A teammate says "we already do CQRS — our handlers are split into
`PlaceOrderCommand` and `GetOrdersQuery` classes." What do they actually have,
what is it called, and what does it buy them from §1's table?

**1.4** Why is "read model" a slightly misleading name? Propose a better one
and say what misunderstanding it prevents.

---

## Rung 2 — Identify

**2.1** For each of these, say whether it lives on the write side, the read
side, or neither, and why:

- a uniqueness constraint on order id
- a rule that an order must have at least one item
- the current stock level used to reject an order
- the stock level shown on the product page
- the customer's lifetime order count shown in the admin UI
- the customer's lifetime order count used to grant a loyalty discount at
  checkout

**2.2** In §8's engineering model, which single object makes a projector
restartable, makes duplicates detectable, and makes lag measurable? Name its
equivalent in Kafka and in Debezium.

**2.3** Given `applyMs = 4`, `ckptMs = 5`, `batch = 50`, compute μ. Then say
what λ would put the projector at ρ = 0.8.

**2.4** A dashboard shows: projector lag 340 ms, error rate 0, throughput
steady, CPU 30%. Everything is green. Name two distinct failures from §11
that are fully consistent with this dashboard.

---

## Rung 3 — Predict

Commit to an answer before checking.

**3.1** A projector polls every 800 ms at ρ ≈ 0.1. Predict the mean window to
within 100 ms without running anything. State the formula you used.

**3.2** The team doubles the projector's batch size from 100 to 200, under
streaming, at ρ = 0.08. Predict what happens to (a) the mean window, (b)
capacity. Then check on panel 1.

**3.3** λ rises from 390/s to 478/s — a 23% traffic increase — on a projector
with μ = 487.8/s. Predict the change in the mean window. Then predict the
change when λ rises just 2% further, to 498/s. Check on panel 1's cliff chart.

**3.4** One unit of stock remains. The command checks a read model fed by a
2-second poll, at 40 orders/second. Predict how many orders are accepted.
Then predict it for streaming. Check on panel 2.

**3.5** Two read models are both fed by projectors polling every 500 ms, with
identical code and identical load. Their timers happen to fire 250 ms apart.
Predict the percentage of page loads on which they disagree.

**3.6** A projector crashes once per 1,000 applies. Its projection does
`revenue += total` and it commits its Kafka offset after the applies succeed.
Predict the sign and rough size of the error in reported revenue after 200,000
events.

---

## Rung 4 — Modify

**4.1** Take the canonical setup (λ = 40/s, poll 500 ms, apply 2 ms, batch
100). Change **one** thing to cut the mean window by at least 30×. What did
you change, what did it cost, and what did it *not* change?

**4.2** The read store gets five times slower (apply 2 ms → 10 ms). Traffic is
unchanged. Which of these move, and by how much: μ, ρ, mean window, p99
window, time to drain a 10,000-event backlog? Which of them would page you
under a typical alert configuration?

**4.3** You switch the alert from "more than 10,000 events behind" to "oldest
unapplied event is more than 60 seconds old". Give one incident the new alert
catches that the old one missed, and one it catches *later* than the old one
would have.

**4.4** You move the checkpoint out of the read store and into Kafka. Which
row of §10.5's matrix do you now live on, which protection did you lose, and
what must you audit in every existing projection?

**4.5** Your projector runs at ρ = 0.92 and you need to rebuild a 50 M-event
read model. You cannot reduce λ. Give three ways to make the rebuild finish
this week, and the drawback of each.

---

## Rung 5 — Trace

**5.1** A projector polls every 500 ms, `applyMs = 2`, `ckptMs = 5`,
`batch = 3`. Applies and the checkpoint share one read-side transaction, so a
batch becomes visible at its commit. It drains immediately after a *full*
batch, otherwise it sleeps to the next tick.

Six events commit at t = **120, 300, 460, 520, 700, 1400 ms**.

Fill in the table. Give the visibility time and the window for each event, and
the mean window.

| seq | commit | visible | window |
|---|---|---|---|
| 1 | 120 | | |
| 2 | 300 | | |
| 3 | 460 | | |
| 4 | 520 | | |
| 5 | 700 | | |
| 6 | 1400 | | |

**5.2** Three events in your table share a visibility time but have three
different windows; and one event committed just 60 ms after another yet waited
438 ms longer than it did. Explain both, each in terms of one property of the
design.

**5.3** Redo the trace for a streaming projector with the same costs. Then
state, in one sentence, what the poll interval was actually buying.

**5.4** Now trace a crash. The projector is applying the batch `{4, 5}` at
t = 1000. It applies event 4, then dies before the transaction commits. It
restarts at t = 3000. Give the final state of the read model and the
checkpoint under each of the four combinations of *(idempotent apply, atomic
checkpoint)*, assuming each event adds 100 to a revenue total that started at 0
after events 1–3 were applied.

---

## Rung 6 — Spot the flaw

Each of these is a plausible design someone has actually shipped. For each:
name the flaw, construct the **smallest concrete scenario** in which it
produces a wrong result, and name the guarantee it violates.

**6.1** "Our projections are idempotent, so we don't bother with ordering. We
run 8 projector threads pulling from a shared queue."

**6.2** "The command handler reads `product_stats.units_sold` to check stock.
It's fine — our projector lag is under 50 ms, we alert on it."

**6.3** "We write the order to Postgres, then publish `OrderPlaced` to Kafka.
Two steps, but we retry the publish until it succeeds."

**6.4** "To make the rebuild fast we set the batch size to 10,000. The
checkpoint is in the same transaction, so it's safe."

**6.5** "The order page shows the item list from the orders projection and the
total from the revenue projection. Both are eventually consistent, so the page
is eventually consistent."

**6.6** "We alert when the projector falls more than 5,000 events behind.
That's about 10 seconds of work, which is well inside our SLA."

**6.7** "Our projection does `UPDATE daily_revenue SET total = total + :amt
WHERE day = :day`. It's a single statement, so it's atomic, so it's safe to
retry."

**6.8** "We adopted CQRS across the whole service so every team works the same
way."

---

## Rung 7 — Handle failure

Design answers, not one-liners. Say what you would build and what it costs.

**7.1** A user places an order and is redirected to a page that queries the
read model 120 ms later. With a 500 ms poll, 89% of users see nothing. Give
four different fixes, ordered by cost, and say which screens each one suits.
For the one you would ship, describe what has to change in the API contract.

**7.2** Your projector has been down for four hours. It comes back. Describe
what happens over the next several minutes, what the lag graph looks like,
what the *user-visible* symptom is, and how you would decide whether to let it
catch up or to rebuild.

**7.3** You discover that a projection has been running with a non-idempotent
accumulator for eight months. Describe how you would (a) determine whether it
is actually wrong, (b) determine by how much, (c) fix it without downtime,
and (d) prevent the class of bug. Note which of these is impossible to do
from the read model alone, and why.

**7.4** Design the read-your-own-writes mechanism from §11.1 option 3
end-to-end: what the command returns, what the client stores, what the query
layer does, what happens when the projector is 30 seconds behind, and what
happens when the user opens the page on a second device.

**7.5** Your feed is Kafka with 12 partitions, keyed by order id. A consumer
group rebalance happens during a deploy. Explain precisely which duplicates
and which reorderings are possible, which are not, and what your projection
must do about each.

---

## Rung 8 — Design

**8.1** You own a service with 1,000,000 writes/day and 100,000 reads/day.
A colleague proposes CQRS to "get ready for scale". Using §4b's framework,
give your answer with the arithmetic, and say what you would propose instead.

**8.2** Design the projections for an order-management system that must serve:
a customer's order list, a per-day revenue report, full-text order search, and
a live "orders awaiting dispatch" queue for warehouse staff. For each: the
store you would choose, the projector's delivery mode, the acceptable window,
and whether it may be combined with another projection. Justify every
combination and every separation.

**8.3** Your company mandates that any read model must be rebuildable within
4 hours. What does that constraint imply about λ, μ, retention, partitioning
and deployment? Turn it into three specific numeric requirements you could put
in a design doc.

**8.4** **Counterfactual.** Suppose the rule "the read model makes no
decisions" did not exist — suppose read models were considered authoritative
enough to validate against. Describe the smallest realistic system in which
this produces a loss of money, then explain why the fix cannot be "reduce the
window". Finally: is there *any* class of decision that can safely be made
from a read model? Characterise it precisely.

**8.5** **Counterfactual.** Suppose projectors could commit atomically across
the write store, the read store and Kafka. Which sections of this lab would
become unnecessary, and which would survive unchanged? What would you have
paid for it?

**8.6** You are on call. Alert: "orders-dashboard projector age > 60s".
Write the runbook: the first four things you check, in order, and what each
one distinguishes. Include the check that tells you whether this is a
projector problem or a read-store problem, and the one that tells you whether
waiting will fix it.
