# CQRS — Solutions

Work the exercise first. For the design rungs there is no single right answer,
so what follows is a worked reasoning process and the traps to avoid, not a
key to copy.

Figures cited are from `README.md`, which is reproducible in
`visualization.html`.

---

## Rung 1 — Explain

**1.1** A model answer:

> Recording a sale and reporting on sales want opposite things. Recording wants
> careful rules and small, exact updates. Reporting wants totals that are
> expensive to work out from scratch. If both go through one set of tables,
> the reports slow the sales down — they hold the database's workers for a long
> time while a sale needs one for a moment — and the reports stay slow, because
> the tables are laid out for recording, not for answering. So: keep the strict
> tables for recording, and keep separate tables that already hold the answers,
> updated whenever something changes. The answer tables are a little behind.
> For reporting that is fine.

The first half of that covers the **contention**; the last three sentences
cover the **arithmetic**. If your version only said "reports are slow", add the
contention: at 6 reports/second, checkout's p99 went from 23 ms to 1,590 ms
without checkout changing at all.

**1.2** *Permission is granted by the fact that nothing depends on the read
model being right at any particular instant — it makes no decisions.*

The smallest change that revokes it: have a command read from a read model.
One `SELECT` in one command handler. That is exercise 6.2, and it turns a
latency property into an oversell of 12 units per unit of stock.

**1.3** They have **CQS** — Command–Query Separation, Meyer 1988 — which is a
rule about methods, not models. It is genuinely useful: it makes handlers
easier to reason about and test. It buys **nothing** from §1's table, because
both classes still hit the same schema on the same connection pool, so the
reports still block checkout and the joins still run per query.

**1.4** "Read model" suggests a *view of* the write model — something derived
live, like a SQL view or a projection in the mathematical sense. It is not: it
is a **separately stored answer**, maintained by a process, that can be wrong
if that process is broken. "Answer table", "materialised answer" or the lab's
"chalkboard" all carry that better. The misunderstanding it prevents: that a
read model is automatically consistent with the write model, and therefore
safe to validate against.

---

## Rung 2 — Identify

**2.1**

| Thing | Side | Why |
|---|---|---|
| uniqueness constraint on order id | **write** | It is a rule. Rules are enforced where the truth is |
| order must have ≥ 1 item | **write** | Same |
| stock level used to *reject* an order | **write** | It is an input to a decision, so it must be the truth. This is the §11.2 trap |
| stock level *shown* on the product page | **read** | Showing is not deciding. Being 300 ms stale here is fine |
| lifetime order count *shown* in admin | **read** | Display |
| lifetime order count used to *grant a discount* | **write** | Deciding. Same trap wearing different clothes — and harder to spot, because "count of orders" feels like a reporting number |

The last two rows are the point of the exercise: **the same number is a read
model in one sentence and a write-model concern in the next.** What decides is
the verb, not the data.

**2.2** The **checkpoint**. Kafka calls it a *consumer offset*; Debezium (and
Postgres logical replication) call it an *LSN*, and the slot's
`confirmed_flush_lsn` is the durable form of it.

**2.3** μ = 1000 × 50 / (50 × 4 + 5) = 50,000 / 205 = **243.9 events/s**.
ρ = 0.8 → λ = 0.8 × 243.9 = **195.1 events/s**.

**2.4** Several qualify; any two of:

- **§11.6 reordering.** Three orders were cancelled and still show as active.
  Lag is zero, errors are zero, because nothing failed — events were applied in
  the wrong order and the result converged to the wrong value.
- **§11.7 non-idempotent accumulation.** Revenue is 5.39% too high and
  climbing. Nothing errors; a number is simply wrong.
- **§11.3 the cliff.** ρ could be 0.98, meaning a 2% traffic rise makes the
  window unbounded — and a 340 ms lag reading cannot distinguish ρ = 0.1 from
  ρ = 0.98 (307 ms vs 509 ms).
- **§11.5 cross-model skew.** Every page load is showing a combination of two
  read models that never existed.

---

## Rung 3 — Predict

**3.1** Formula: mean ≈ (poll/2)(1 + ρ) + apply + ckpt.
= 400 × 1.1 + 7 = **447 ms**. Anything in 400–460 is a good prediction. The
thing to get right is that it is dominated by poll/2 and barely touched by
anything else at low ρ.

**3.2** (a) The mean window does **not change** — 8.12 ms at batch 10, 100 and
2000. (b) Capacity rises: 400.0 → 487.8 → 499.4/s.

If you predicted a wider window you made the same mistake I did when designing
this lab. The batch is *opportunistic* — it takes whatever has arrived, which
at ρ = 0.08 is about one event, so a bigger limit changes nothing. Batching
costs latency only when you **wait** to fill the batch, and that waiting has a
name: it is a linger timer, and a poll interval is a linger timer. That is the
entire 297 ms vs 8.1 ms difference between v2 and v3.

**3.3** 390 → 478/s (ρ 0.80 → 0.98): mean window 445 → **509 ms**. A 23%
traffic rise bought a 14% window increase — nothing.

478 → 498/s (ρ 0.98 → 1.02): mean window 509 → **8,661 ms**. A 2% traffic rise
multiplied it by 17×, and the number is no longer meaningful anyway, because
past ρ = 1 the window grows with how long you have been over capacity
(14.3 s → 29.0 s → 57.9 s → 118.2 s as the run doubled).

The lesson is not "watch out near ρ = 1". It is that **lag gives you no warning
at all** — it is flat across the entire safe range, so you must monitor
projector utilisation as a separate signal.

**3.4** 2-second poll: **46.66 on average**, p99 97, worst seen 117.
Streaming: **0.33 on average**, worst 5.

Both numbers are λ × window: 40 × 1.161 = 46.4, and 40 × 0.0081 = 0.32.

The prediction that matters is the second one. Most people predict zero for
streaming. It is not zero, and it cannot be, because there is always some
window.

**3.5** **100%.** Identical configuration, identical load, identical code —
they disagree on every single sampled page load, with a mean gap of 10.1
events. Independent projectors have independent checkpoints and there is no
instant at which both are in the same place. No amount of tuning fixes this;
only putting the two answers in one read model behind one projector does.

**3.6** Positive, and about **+5%** (measured: +5.39%).

Positive because the failure mode is re-applying, and re-applying an
accumulator adds again. The Kafka detail in the question is the important part:
the offset lives in Kafka, so there is no transaction spanning the read store
and the offset store, so you are structurally on the non-atomic row of §10.5's
matrix and idempotency is your only protection — which `+=` does not provide.

---

## Rung 4 — Modify

**4.1** Switch the projector from polling to **streaming**: mean window
297.2 ms → 8.1 ms, a **37×** reduction.

- What it cost: a notification path (LISTEN/NOTIFY, a broker, a CDC stream)
  and a long-lived connection to maintain, plus a process that is now
  event-driven rather than a cron-like loop.
- What it did **not** change: capacity, which is 487.8/s either way. Streaming
  fixes the floor of the window. The ceiling is untouched.

Other single changes that also qualify — cutting the poll to 10 ms — are worse
answers: they get you to a 65 ms window at poll 100 ms while multiplying the
projector's wake-ups and its load on the feed, and they still leave a timer.

**4.2**

| Quantity | 2 ms | 10 ms | Moves? |
|---|---|---|---|
| μ | 487.8/s | 99.5/s | **4.9× worse** |
| ρ | 0.082 | 0.402 | **4.9× worse** |
| mean window | 298 ms | 465 ms | 1.6× worse |
| p99 window | 543 ms | 750 ms | 1.4× worse |
| drain 10,000 events | 0.4 min | 2.8 min | **7× worse** |

**Which pages you: almost certainly none of them.** A lag alert set anywhere
sensible (say 5 s) is nowhere near 465 ms. The quantity that moved most — ρ —
is the one nobody graphs. You have gone from 8% to 40% utilised, and you will
find out at 100%.

**4.3** *Catches that the old one missed:* the §11.4 slowdown. When applies go
from 2 ms to 24 ms, 10,000 events stop being 0.4 minutes of work and become
105.5 minutes — the same count, a 264× different meaning. An age alarm notices
because age is measured in the unit your users and your SLA are written in.

*Catches later:* a sudden burst on a healthy projector. 10,000 events can pile
up in a couple of seconds during a spike and be drained in 24 seconds, and the
count alarm fires immediately while the age alarm never reaches 60 s. That is
usually the *correct* behaviour — you did not want to be paged — but it is a
genuine loss of sensitivity, so if you care about bursts, alarm on both, with
the count alarm as a ticket rather than a page.

**4.4** You move to the **non-atomic checkpoint** column — §10.5's second and
fourth rows. You lose the atomic checkpoint, so **idempotency becomes your only
protection**.

What to audit: every projection statement for accumulation. Anything of the
form `SET x = x + :v` is now unsafe. The fixes are an upsert keyed by the
aggregate's identity, or a sequence-guarded update
(`SET total = total + :v, last_seq = :seq WHERE ... AND last_seq < :seq`).

The alternative worth knowing: store the offset in the read store yourself, in
the same transaction as the applies, and `seek()` to it on partition
assignment. That buys the atomicity back and is the standard exactly-once sink
pattern.

**4.5** At ρ = 0.92 with μ = 487.8/s, a 50 M-event rebuild takes 367 hours —
15 days.

1. **Blue/green with a parallel replay.** Build v2 alongside v1 with many more
   workers than the live projector uses; the ordering constraint is only per
   key, so replay parallelises. 8× workers turns 15 days into under 2 days.
   *Drawback:* the extra workers load the same read store, so real speed-up is
   less than linear, and you need double the storage during the cutover.
2. **Reduce H.** Snapshot the read model as of some sequence and replay only
   from there, or shorten retention. *Drawback:* you give up the ability to
   rebuild from zero, which was one of the pattern's main benefits — now the
   snapshot is load-bearing state you must also protect.
3. **Rebuild against a replica or a restored copy of the feed**, off the
   production read store entirely, then swap. *Drawback:* cutover complexity,
   and the delta accumulated during the rebuild still has to be applied.

The answer that is *not* available: reducing λ. That is why §13.4 says compute
this number quarterly.

---

## Rung 5 — Trace

**5.1**

| seq | commit | visible | window |
|---|---|---|---|
| 1 | 120 | 511 | **391** |
| 2 | 300 | 511 | **211** |
| 3 | 460 | 511 | **51** |
| 4 | 520 | 1009 | **489** |
| 5 | 700 | 1009 | **309** |
| 6 | 1400 | 1507 | **107** |

Mean window = 1558 / 6 = **259.67 ms**.

The reasoning, step by step:

- First tick at t = 500. Events committed at or before 500: seq 1, 2, 3 — that
  is exactly `batch`, so the batch is full. Cost = 3 × 2 + 5 = 11 ms, so it
  commits at **511**, and all three become visible together.
- The batch was full, so the projector drains rather than sleeping. At t = 511
  nothing new is available (seq 4 committed at 520), so it sleeps to the next
  tick.
- Tick at t = 1000. Available: seq 4, 5. Cost = 2 × 2 + 5 = 9, commits at
  **1009**. Not a full batch, so it sleeps.
- Tick at 1500. Available: seq 6. Cost = 7, commits at **1507**.

**5.2**

*Three events, one visibility time, three windows:* because the applies and the
checkpoint share one transaction, **visibility is quantised by the batch** —
every event in a batch becomes visible at the same instant regardless of when
it committed. The window is measured from each event's own commit, so it
differs even though visibility does not. This is why the window's distribution
is a sawtooth rather than a bell.

*60 ms later, 438 ms longer:* seq 3 commits at 460 and just catches the 500 ms
tick (window 51). Seq 4 commits at 520 and misses it by 20 ms, so it waits for
the next one (window 489). **The poll interval is a cliff, not a gradient** —
missing a tick by a millisecond costs a whole interval. That is the mechanism
behind the whole `poll/2` term.

**5.3** Streaming, same costs:

| seq | commit | visible | window |
|---|---|---|---|
| 1 | 120 | 127 | 7 |
| 2 | 300 | 307 | 7 |
| 3 | 460 | 467 | 7 |
| 4 | 520 | 527 | 7 |
| 5 | 700 | 707 | 7 |
| 6 | 1400 | 1407 | 7 |

Mean = **7 ms**, versus 259.67 ms. Every event costs exactly one apply plus one
checkpoint commit and nothing else.

> The poll interval was buying **nothing except a smaller number of wake-ups.**
> It was not buying throughput (capacity is identical), it was not buying
> batching (the batches here are size 1 either way at this rate), and it was
> not buying safety. It cost 37× the window.

**5.4** Before the crash: events 1–3 applied, revenue = 300, checkpoint = 3.
The projector applies event 4 (revenue → 400) and dies before committing.
Truth after recovery should be **500**.

| Idempotent apply | Atomic checkpoint | After restart | Correct? |
|---|---|---|---|
| yes | yes | Transaction rolled back → revenue 300, ckpt 3. Replays 4 and 5 → **500**, ckpt 5 | ✅ |
| yes | no | Event 4's apply survived → revenue 400, ckpt 3. Replays 4 (no-op) and 5 → **500**, ckpt 5 | ✅ |
| no | yes | Rolled back → revenue 300, ckpt 3. Replays both → **500**, ckpt 5 | ✅ |
| **no** | **no** | Event 4 survived → revenue 400, ckpt 3. Replays 4 (**+100 again**) and 5 → **600**, ckpt 5 | ❌ **+20%** |

The two protections work by different routes — one *undoes* the partial work,
the other *tolerates* it — which is why either alone suffices, and why it
matters which one you have when the checkpoint lives somewhere you cannot open
a transaction on.

---

## Rung 6 — Spot the flaw

**6.1 — Idempotency is not ordering.**
Smallest scenario: `OrderPlaced(ord-1)` and `OrderCancelled(ord-1)` 50 ms
apart, handed to different threads; one thread stalls (GC pause) and the cancel
is applied first, then the place overwrites it. Order ord-1 shows as active
forever. Measured: **3 orders in 20,000** with one 1-second stall.
*Violated:* convergence — the read model has converged to a state the write
model does not agree with. It is not stale; it is wrong, permanently.
*Fix:* partition by key, which converts the corruption into 990 ms of latency.

**6.2 — A command reading a read model.**
Smallest scenario: one unit of stock, two orders 10 ms apart. Both read
`units_sold` before the first order's event is visible, both are accepted.
At 40 orders/s with a 50 ms window you oversell about **2**; at the lab's
500 ms poll, **12 on average and 41 at worst**.
*Violated:* the invariant the write model exists to enforce.
*Fix:* a conditional write on the write model — `UPDATE stock SET units =
units - 1 WHERE sku = ? AND units >= 1`. Note the alert in the quote is
irrelevant: a 50 ms lag alert firing correctly still leaves you overselling.

**6.3 — The dual write.**
Smallest scenario: the Postgres commit succeeds, the process dies before the
Kafka publish. The order exists and no event describing it ever will, so every
read model is permanently missing it and a rebuild does not fix it — the feed
has no record.
*Violated:* invariant 1, atomicity at the source.
*Fix:* the transactional outbox — write the event to a table in the same
transaction and relay it separately. "We retry until it succeeds" does not
help, because the process that would retry is the one that died.

**6.4 — Correct, and still broken.**
The claim is true: with an atomic checkpoint, a batch of 10,000 is perfectly
correct at any crash rate. The flaw is **progress**, not correctness. Every
replayed event is itself exposed to the crash hazard, so the governing quantity
is B·h. At B·h = 5 the projector wastes **27× the useful work**; at B·h = 12 it
applied **1.5% of the feed** before hitting a work budget of 60× and would have
stayed there indefinitely.
*Violated:* liveness. The projector is not wrong, it is stuck.
*Fix:* size the batch so B·h ≪ 1 — and note the trap that h rises exactly when
things are already going badly.

**6.5 — Two eventually-consistent things are not one eventually-consistent
thing.**
Smallest scenario: the item list shows 5 orders while the total shows the sum
of 4, because the two projectors are at different checkpoints. Measured: two
projectors with *identical* 500 ms polls whose timers fire 250 ms apart
disagree on **100% of page loads**.
*Violated:* nothing formal — each model is individually fine. What is violated
is the user's reasonable assumption that the page shows one moment in time.
*Fix:* put anything that must be internally consistent in **one** read model
behind **one** projector, or timestamp the two facts separately and show them
as such.

**6.6 — A threshold in units of work.**
Smallest scenario: the read store slows from 2 ms to 24 ms per apply. 5,000
events was 10 seconds of drain; it is now over 50 minutes. The alarm's
threshold did not change and its *meaning* changed by 264×.
*Violated:* the alarm's relationship to the SLA it was written for.
*Fix:* alarm on the **age of the oldest unapplied event**, which is in the same
unit as the SLA.

**6.7 — Statement atomicity is not idempotency.**
Smallest scenario: the statement commits, the projector dies before the offset
is committed, the statement runs again. Revenue is 100 too high, forever, and
nothing errors.
*Violated:* effectively-once application — invariant 2.
*Fix:* guard it — `SET total = total + :amt, last_seq = :seq WHERE day = :day
AND last_seq < :seq` — or restructure the projection so the row is an upsert of
a computed value rather than an accumulation. "Atomic" answers a different
question ("can it half-happen?") from "idempotent" ("what if it happens
twice?").

**6.8 — Uniformity as a value.**
Smallest scenario: a subsystem with R/N = 0.1 (§4b's last row) now pays
11.11 CPU-hours/day of projection work to save 1.11 of query work, *and* has
acquired a consistency window it gains nothing from — and an engineer working
there, with no reason to have internalised §6's rule, writes exercise 6.2's
inventory check.
*Violated:* nothing technical. This is a judgement failure that manufactures
opportunities for all the others.
*Fix:* per-subsystem adoption, driven by the R/N ratio and by whether staleness
is genuinely tolerable there.

---

## Rung 7 — Handle failure

**7.1** Ordered by cost:

1. **Render from the command result.** The confirmation page already knows the
   order succeeded; it does not need to query anything. Free, and it removes
   the problem entirely for the single most common screen. Suits: confirmation
   pages, "saved" toasts, anything showing back what the user just submitted.
2. **Stream instead of poll.** 89.4% → 0.0% at every think time, for a config
   and deployment change. Suits: everything, and it is the highest-leverage
   single change available. Caveat: at ρ = 0.92 streaming's own window is
   89.6 ms mean / 197 ms p99, so it is not a licence to stop thinking.
3. **Sequence-number wait (read-your-own-writes).** Precise, per-user, and it
   costs a little latency for exactly the user who needs it. Suits: lists and
   detail pages the user was just redirected to.
4. **Route that query to the write model.** Always correct. Suits: rare,
   high-stakes screens. If you need it for most screens, this subsystem did not
   want CQRS.

**API contract change for option 3:** the command response stops being an empty
`200 OK` and starts carrying a **consistency token** — the event's sequence
number, or an opaque encoding of it. The client returns it on subsequent reads
(header or cookie). The query layer either waits until the relevant projector's
checkpoint ≥ the token, or routes to the write model, and it must **cap the
wait** and degrade rather than hanging. This is the same mechanism as a session
token for bounded staleness in a replicated database.

**7.2** Over the next several minutes:

- The projector resumes from its checkpoint and drains at μ, while λ keeps
  arriving, so it closes the gap at **μ − λ**, not μ. Four hours at 40/s is
  576,000 events; at 487.8/s capacity that is 576,000 / 447.8 ≈ **21 minutes**.
- The lag graph shows a sawtooth-free straight line down — a *linear* descent
  at a constant slope. Age falls at (1 − λ/μ) seconds per second: here, 0.92
  seconds of age per second of wall clock.
- The user-visible symptom is that read models are hours stale and then
  progressively less so. Crucially, **writes were never affected** — that
  decoupling is the feature you paid the window for.

*Catch up or rebuild?* Let it catch up if (a) the feed still retains everything
back to the checkpoint, and (b) μ − λ gives an acceptable time. Rebuild if the
retention window was exceeded during the outage — because then the checkpoint
points at events that no longer exist, and catching up would silently skip
them. **That is the check to do first, and it is the one people forget:** an
expired offset is not a stalled projector, it is a permanently wrong read model.

**7.3**

- **(a) Is it wrong?** You cannot tell from the read model — a wrong number
  looks exactly like a right one. Rebuild the projection into a shadow table
  from sequence 0 and diff. This is the only reliable test, and it is why §13.4
  lists reconciliation.
- **(b) By how much?** The diff gives it exactly, per row. Expect the error to
  correlate with crash/restart/rebalance history rather than with traffic —
  which is itself confirmation of the mechanism.
- **(c) Fix without downtime:** blue/green. Build the corrected projection
  alongside, let it catch up, then flip reads. Do not try to "repair" the live
  table with a correction delta — you cannot compute one reliably, because you
  do not know how many times each event was applied.
- **(d) Prevent:** convert accumulations to upserts or sequence-guarded
  updates; add reconciliation to the schedule; and if you are on Kafka, treat
  idempotency as mandatory rather than as good practice, because the atomic
  checkpoint is not available to you.

**7.4** Sketch:

- The command handler commits, and returns the event's sequence number as a
  token: `{"status":"ok","consistency":"orders:184423"}`.
- The client stores it — cookie for browser flows, header for API clients —
  scoped to that user's session, and sends it with subsequent reads.
- The query layer compares the token to the relevant projector's checkpoint. If
  the checkpoint is behind, it either waits (with a short cap, e.g. 200 ms) or
  routes to the write model.
- **Projector 30 s behind:** waiting is not an option. The query layer must
  detect that the wait would exceed the cap and immediately fall back — to the
  write model if the query can be answered there, otherwise to serving stale
  data with a visible "updated a moment ago" marker. Silently hanging is the
  worst outcome and the most common implementation bug.
- **Second device:** the token lives in the first device's session, so the
  second device has no token and reads normally — it may not see the write.
  That is correct and expected: read-your-own-writes is a *session* guarantee,
  not a global one. If you need it across devices, the token must be stored
  per-user server-side, which turns a cheap mechanism into a stateful one.

**7.5** With 12 partitions keyed by order id:

- **Guaranteed:** events for a single order are in one partition, so their
  relative order is preserved end to end. Order-level reordering is
  **impossible**.
- **Possible: duplicates.** A rebalance revokes partitions from a consumer that
  had applied records but not yet committed its offset. The new owner resumes
  from the last committed offset and re-delivers them. This is unavoidable —
  the offset lives in Kafka and cannot join your read store's transaction.
- **Possible: reordering *across* orders.** Partition 3 may be seconds ahead of
  partition 7. Anything that assumes a global sequence across orders is broken.
- **What the projection must do:** be idempotent per event (upserts or
  sequence-guarded updates), never assume a global order, and never derive a
  cross-order aggregate that must be internally consistent at an instant. If
  you need an exact global total, it belongs in one partition or is computed
  from the write model.

---

## Rung 8 — Design

**8.1** The arithmetic: R/N = 100,000 / 1,000,000 = **0.1**. With J = 40 ms,
read-time cost is 1.11 CPU-hours/day and projection-time cost is **11.11
CPU-hours/day**. Projections would cost **10× more** than the thing they
replace, and add a consistency window, a projector to operate, and a rebuild
to plan.

CQRS is a bet on a ratio, and this system is on the wrong side of it. "Getting
ready for scale" has it backwards: growing this workload makes projections
*relatively worse*, because N grows with it.

What to propose instead: index the specific slow queries; cache if the same
answers repeat; and if reads are interfering with writes, a **read replica** —
which fixes contention without adding a projection. Revisit if R/N moves,
which it will if you add a dashboard that a million people look at.

**8.2** A defensible design:

| Read model | Store | Delivery | Window | Combine? |
|---|---|---|---|---|
| Customer's order list | Postgres table, one row per order | streaming | < 100 ms; the user was just redirected here | **Yes — combine with the order total.** Anything a single page shows together belongs to one projector (§11.5) |
| Per-day revenue report | Postgres or ClickHouse, aggregated by day | poll, minutes | minutes; nobody notices | Separate — different shape, different audience, no page shows it beside the order list |
| Full-text order search | Elasticsearch | streaming | seconds is fine | Separate — different store, and it is the reason v1 (synchronous projection) is not available |
| "Awaiting dispatch" queue | Postgres, indexed by status | streaming | **as small as possible** | Separate, and treat with care — see below |

The last row is the interesting one, and the trap. Warehouse staff *act* on
that queue, so the temptation is to treat it as authoritative. It is not: two
staff can pick the same order inside the window. The projection may **show** the
queue; claiming an order must be a command against the write model with a
conditional update (`WHERE status = 'AWAITING'`). This is §6's rule reappearing
somewhere it does not look like inventory.

**8.3** "Rebuildable in 4 hours" is a constraint on **spare** capacity, since
rebuild time = H/(μ − λ). Turning it into requirements:

1. **μ − λ ≥ H / 14,400 s.** With H = 50 M that is a required spare capacity of
   **3,472 events/s** — over 7× the lab's whole projector. So either μ must be
   much larger, or H much smaller, or the replay must be parallel.
2. **Replay parallelism ≥ P**, where P is chosen so P × (μ − λ) meets (1). This
   is only sound if the feed is partitioned by key, so the constraint
   propagates back into a *design* requirement on the feed, not just an
   operational one.
3. **Retention ≥ the rebuild window plus margin**, and **H bounded** by
   retention or snapshots — otherwise H grows monotonically and requirement (1)
   silently expires. Add an alert on projected rebuild time, not just on H.

The broader lesson: a rebuild SLO is not an operational nicety, it constrains
your partitioning key and your retention policy at design time.

**8.4** *Smallest realistic system where it loses money:* one product, one unit
of stock, a checkout that validates against a projection. At 40 orders/second
with an ordinary 500 ms poll you accept **12 orders on average, 41 at worst**,
for one unit. Every one beyond the first is a refund, an apology, and possibly
a regulatory problem if you charged for it.

*Why "reduce the window" cannot be the fix:* the window is never zero in any
asynchronous design. Going from a 2 s poll to streaming took the oversell from
46.66 to 0.33 — a 140× improvement that still is not zero, and the remaining
0.33 is not a tuning failure, it is the definition of the design. Reducing the
window changes the probability of a wrong decision, never its possibility. Only
moving the decision to where the truth is makes it zero.

*Which decisions can safely be made from a read model?* Precisely those whose
correctness does not depend on the read model being current — which is a real
and non-empty class:

- Decisions that are **advisory** and will be re-validated later ("show this
  banner", "suggest this product", "pre-fill this form").
- Decisions on **monotone** predicates that cannot be invalidated by events
  still in flight. If a fact can only ever become *more* true — an account was
  created, an order was placed at some point — then a stale "no" may be wrong
  but a stale "yes" cannot be. Note the asymmetry: you may act on the yes and
  must not act on the no.
- Decisions where the **cost of being wrong is bounded and acceptable**, and
  someone has actually written down what that bound is.

Everything else — anything conserving a scarce resource, anything enforcing a
limit, anything the business would call a rule — belongs on the write model.

**8.5** With a magic transaction spanning the write store, the read store and
Kafka, what disappears:

- §10.5 in its entirety: no crash matrix, no idempotency requirement, no atomic
  checkpoint distinction, no B·h.
- §11.7's silent drift, and §11.6's reordering (a global transaction implies a
  global order).
- The outbox pattern itself — §13.1's whole first bullet.

What **survives unchanged**:

- The consistency window, if the projection is still asynchronous — atomicity
  is not synchrony. If you make it synchronous to close the window, you are
  back at §10.1's v1 and you have re-acquired its latency and availability
  coupling.
- §11.2's oversell, if a command still reads a read model. Nothing about
  atomicity stops you from validating against a store that is behind.
- §11.5's cross-model skew, unless every read model is in the same transaction
  as every other — at which point you have one giant synchronous system.
- §11.8's rebuild arithmetic. H/(μ − λ) does not care about transactions.
- §4b's economics. The reason to do this at all is untouched.

*What you would have paid:* the availability and latency of a distributed
transaction — your write path now blocks on the slowest and least reliable
participant, which is exactly what §10.1's breaking points 2 and 3 were about,
and exactly what your two-phase-commit lab priced. **That is the honest summary
of the whole pattern: CQRS is what you build when you decide that price is too
high, and every remaining complication in this lab is change from that
decision.**

**8.6** Runbook for *"orders-dashboard projector age > 60 s"*:

1. **Is it moving?** Compare the checkpoint now with the checkpoint 30 seconds
   ago. This is the first check because it splits the entire problem space:
   a checkpoint that is advancing means *behind*; one that is not means *stuck
   or dead*, which is a completely different investigation (crash loop, poison
   batch, lost lease, expired offset).
2. **Is it behind because of input or because of output?** Compare λ now with
   λ an hour ago, and compare the projector's utilisation ρ now with before. λ
   up and ρ up means a traffic event. λ flat and ρ up means the **read store
   got slower** — this is §11.4, and it is the one that surprises people
   because nothing about your service changed.
3. **Will waiting fix it?** Compute μ − λ. If positive, age falls at
   (1 − λ/μ) seconds per second and you can state an ETA; if λ ≥ μ, waiting
   will *never* fix it and you must shed, scale the projector, or accept
   unbounded staleness. This is the check that turns the page into either "I
   have an ETA" or "I have an incident".
4. **Is the feed still retaining what the checkpoint points at?** If the offset
   has expired, catching up would silently skip events, and the correct action
   is a rebuild, not patience. Check this before anyone "restarts it to see if
   that helps".

Two things deliberately *not* in the top four: restarting the projector (it
destroys the evidence for check 1 and fixes only the case check 1 would have
identified) and looking at error rates (all four of this lab's silent failures
have an error rate of zero).
