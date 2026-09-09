# Microservices — Solutions

Worked answers. Rungs 7 and 8 are design discussions, not single correct
outputs — what matters is naming the right mechanism, not matching my wording.

---

## Rung 1 — Explain

**1.1** *(A model answer, 92 words.)*

> Right now the whole product is one thing, so everyone who wants to change any
> part of it has to take a turn, and everyone's mistakes land on everyone else.
> A one-line fix waits behind someone else's unfinished work, and if their work
> is wrong, ours gets cancelled too. Splitting the product into separate pieces
> gives each team its own turn. The cost is that the pieces now talk to each
> other over a network, which is slow and sometimes fails, so the product will
> break in new ways and we will need new tools to see why.

**1.2** Ask: **"When did you last release one of them on its own, without
coordinating with another team?"** — or equivalently, look at the last twenty
production releases and count how many were co-deployments. That question and
not another because twelve repositories is a fact about the source control
layout, while independent deployability is the only benefit of the style;
everything else (scaling, tech choice, isolation) is either obtainable without
splitting or must be bought separately. If they always release together, they
have one system in twelve repositories, and they are paying the distribution
bill without collecting anything.

**1.3** A dead dependency refuses the connection in about a millisecond, so the
caller's thread is released immediately and only the feature that needed it
fails. A slow one holds the thread for the whole call: at 100 requests per
second and a 5-second call, `100 × 5 = 500` threads are needed and only 200
exist, so the pool is empty two seconds in and *every* endpoint on the service
stops working. And it is invisible — the slow dependency is returning `200 OK`,
so its error rate is zero and every dashboard is green.

---

## Rung 2 — Identify

**2.1** Healthy steady state: about **2 of 200** threads in use (Little's Law:
`100/s × 0.02 s = 2`), essentially all of them inside `payments` calls, with a
**100%** checkout success rate. The point of reading this before touching
anything is that the healthy state is *boring* — the service is 1% utilised,
which is why nobody thinks the thread pool is a risk.

**2.2** `POST /checkout` fails first, which is expected and contained.
`GET /orders/{id}` fails a moment later, and that is the surprise. The page says
why in a full sentence: it is not failing because it needs `payments` — it never
calls `payments` — it is failing because there is no free thread to run it on.
That distinction is the definition of a cascading failure and it is the whole
reason the tab exists.

**2.3** Five services at 99.9% → **43.7 hours/year**. To get under 20 hours you
need **two** services (17.5 h); three is already 26.3 h. Which is the blunt
statement of 10g: the only large lever is *removing services from the required
set*, not improving them.

**2.4** In "cut by layer", the worst is a tie at **5** services — "Show a
delivery estimate" and "Add gift receipts", each of which needs a change in the
UI, the API layer, the business-logic layer, the data-access layer *and* the
batch jobs. Only the "cut by capability"
arrangement brings the **median** feature to exactly one service — and note that
even there, some features legitimately touch two. The test is the median, not
the maximum; a good cut still has cross-cutting features.

---

## Rung 3 — Predict

**3.1** **It survives, with no margin.** `100/s × 0.8 s = 80` threads of 200,
40% utilisation. But note two things. First, from the
[work-pressure-and-deadlines](../61-work-pressure-and-deadlines/) lab's
`ρ/(1−ρ)`: at 40% utilisation queueing already adds ~0.67× the service time, so
customer-visible latency is worse than 800 ms. Second, this configuration dies
if traffic rises 2.5× or if `payments` degrades a little further — and both of
those happen on the same afternoon, because a slow checkout makes customers
reload the page. **Surviving is not the same as being safe**, and the number
that tells you which one you are is saturation, not error rate.

**3.2** The pool does not exhaust (`100 × 5 = 500 < 2000`) — and "more threads"
is still not a fix, for four reasons:

1. **The bottleneck moves, it does not vanish.** Your database connection pool
   is probably 20–50. Your outbound HTTP connection pool is probably 100. One of
   those exhausts instead, and the failure is identical with a different graph.
2. **Memory.** Platform threads cost roughly 1 MB of stack each; 2,000 of them
   is real memory and real scheduler pressure.
3. **It does not help the customer.** Every checkout still takes 5 seconds.
4. **It is not a bound.** If `payments` degrades to 30 s you need 3,000. A
   number you have to keep raising is not a limit; a **timeout** is a limit,
   because it caps duration rather than counting occupants.

Worth connecting to your [java-virtual-threads](../18-java-virtual-threads/) lab:
virtual threads make the thread count nearly free, which removes reason 1's
thread-stack limit — and makes **bulkheads more important, not less**. With
cheap threads, nothing stops you from having 50,000 concurrent calls in flight
to a dying dependency; the constraint simply relocates to the downstream service
and to your connection pools, where it is less visible. Cheap concurrency
removes the accidental limit and leaves you needing a deliberate one.

**3.3** `100/s × 1 s = 100` threads of 200. `orders` survives comfortably.
**Checkout success rate: 0%** — every call times out, because `payments`
genuinely takes 5 s and the timeout is 1 s. **`GET /orders/{id}`: 100% success,
normal latency**, because 100 threads are free.

That is exactly the right outcome, and it is worth saying out loud why: the
timeout did not fix checkout and was never going to — `payments` is broken.
What it did was **convert a total service outage into a single broken feature**.
That is what all of section 10f buys: not success, but containment.

**3.4** Fan-out 6, p = 0.01: `1 − 0.99^6 = 1 − 0.9415 = 5.85%`. Fan-out 40:
`1 − 0.99^40 = 1 − 0.6690 = 33.1%`. So a dependency's p99 becomes roughly the
caller's p94 at width 6, and its p67 at width 40. This is Dean and Barroso's
point in *The Tail at Scale*: at fan-out 100, `1 − 0.99^100 = 63%` — the
majority of requests hit the rare case. **A p99 is a property of a call, not of
a system, and it does not survive being fanned out.**

---

## Rung 4 — Modify

**4.1**

- **Bulkhead on, breaker off** — `GET /orders/{id}` stays alive. The bulkhead
  caps `payments` at (say) 40 threads, so 160 remain for everything else.
  Checkout still fails *slowly*: each attempt burns its full timeout.
  Property protected: **isolation** — one dependency cannot consume the whole
  pool.
- **Breaker on, bulkhead off** — checkout fails *quickly*, in microseconds
  rather than a second, because calls are not made at all. Property protected:
  **efficiency of failure** — you stop spending capacity on calls known to fail,
  which also lets `payments` recover by removing its load.
- Neither replaces the other. Before the breaker trips (the first 20 failures,
  or the first window) you are unprotected, and that is exactly when the
  bulkhead is holding the line. After it trips, the breaker is doing the work and
  the bulkhead is idle. **You need both because they cover different intervals
  of the same incident.**

**4.2** Thread occupancy goes from `100 × 1 s = 100` to `100 × 3 s = 300`,
which exceeds 200, so the pool exhausts again — you have undone your own fix.
Simultaneously the rate arriving at `payments` goes from 100/s to **300/s**,
tripling load on a service that is failing.

**Liveness is lost, safety is not.** Nothing incorrect is written; the system
simply stops making progress, and — the nastier part — *stays* stopped, because
every time `payments` recovers the retry backlog knocks it down again. This is
the distinction from [flp-impossibility](../33-flp-impossibility/) and
[eventual-consistency](../28-eventual-consistency/) applied to an operational
incident: the failure has no wrong state to point at, which is precisely why it
is hard to diagnose.

*(Caveat: safety is only preserved here because we assumed the retry is safe.
If `POST /payments` has no idempotency key, the retry is a safety violation too
— see 6.3.)*

**4.3** Three things that became product decisions, and who decides:

1. **What the customer is told at checkout.** "Order placed" is now a lie until
   payment settles; it must say something like "Order received — confirming
   payment". *Decided by:* product and legal (in some jurisdictions what you may
   claim about an order is regulated).
2. **What happens when payment fails after the customer left.** An email? A
   retry with a saved card? A grace period? *Decided by:* product, with finance,
   because it changes the conversion and refund numbers.
3. **What happens to the stock in the meantime.** If you reserved it, someone
   else cannot buy it while a probably-failing payment settles; if you did not,
   you may oversell. *Decided by:* product and operations, because "we oversold,
   here's an apology and a discount" is a business policy, not a bug.

The general lesson: **moving work off the critical path converts an engineering
problem into a product problem.** That is a real conversion, not an evasion —
and shipping it without having that conversation is how teams end up sending
"your order is confirmed" emails for orders that will be cancelled.

**4.4** The rename is identical; the consequence differs in **who can see the
dependency**.

- **Shared store:** the readers are other services querying the table directly.
  There is no artefact anywhere — no import, no call, no config — that records
  they are readers. The rename compiles, deploys, passes every test, and breaks
  a batch job at 3am in a service you have never opened.
- **Private store:** the only readers are callers of your contract. They are
  enumerable — from your access logs, your consumer groups, your service
  catalogue — so the rename is a *known* breaking change and goes through
  expand/contract.

Nothing in either codebase differs. The difference is entirely in whether the
dependency is **discoverable**, and that is why database grants, not code
review, are the enforcement mechanism.

---

## Rung 5 — Trace

Throughout: `threads = arrival rate × duration`, cap 200. The 20 req/s of
`GET /orders/{id}` take ~5 ms each and occupy ~0.1 threads, so they are
negligible until there are none left.

**5.1 — `t = 1.0 s`.** ~**2 threads** in use. Nothing has changed yet; the
requests in flight are the healthy 20 ms ones. Customers: fine.

**5.2 — `t = 2.0 s`.** **100 threads** in use. In the last second, 100 checkouts
arrived and *none has completed* — the first sick one will not finish until
`t = 6.0 s`. Checkout is neither succeeding nor failing; it is **waiting**, which
is why no error-rate alarm has fired. Customers: staring at a spinner.

**5.3 — `t = 3.0 s`.** **200 threads — the pool is full.** A
`GET /orders/{id}` arriving now finds no free thread. It does not fail
immediately; it queues, and nothing will free a thread until `t = 6.0 s`. So it
will sit for ~3 seconds and then almost certainly hit the client's own timeout.

**5.4 — `t = 6.0 s`.** The first sick checkouts complete, freeing threads at
100/s. But arrivals are 120/s. **The queue grows by 20 requests per second,
forever.** Latency therefore rises without bound. The load balancer's health
check is itself an HTTP request needing a thread, so it times out; the LB marks
`orders` unhealthy and removes it. Its share of traffic is redistributed to the
sibling `orders` replicas, **which now receive more than 100/s each and
saturate faster than this one did.** The whole `orders` fleet is gone within
seconds of the first instance going.

**5.5 — First `GET /orders/{id}` failure: just after `t = 3.0 s`** (it queues
from 3.0 and dies at its client timeout). That time is the important number
because it is **the moment the incident changes category**. Before it, the story
is "payments is broken, so customers cannot check out" — contained, expected,
and correctly attributable. After it, the story is "orders is down", and the
page goes to the wrong team about the wrong service. Every mitigation in 10f
exists to push that moment to *never*.

**5.6 — With a 1-second timeout.** Steady state is reached at `t = 2.0 s` (one
timeout period after degradation) and holds:

- **100 threads** in use (`100/s × 1 s`), 100 free.
- Checkout: 0% success, each attempt failing after 1 s.
- `GET /orders/{id}`: 100% success at normal latency, forever.
- The load balancer's health check succeeds, so the fleet stays up.

One number changed and the blast radius went from "the order service and every
replica of it" to "the checkout feature".

---

## Rung 6 — Spot the flaw

**6.1 — The shared library.** The flaw is that `riverbend-common` contains
**domain types and entity mappings**, so it is the shared database schema
wearing a Maven coordinate.

*Concrete run:* the platform team releases `riverbend-common` 2.0, which adds a
required `fulfilmentState` field to `Order` and changes an enum. `orders`
upgrades and starts emitting the new shape. `shipping` is still on 1.9 and fails
to deserialize. To fix it, all seven services must upgrade — in an order, in a
window, with a meeting.

*Violated:* **independent deployability**. Also rule 2 if the entity mappings
imply shared tables.

*The distinction that makes this usable:* shared libraries are fine for
**technical** concerns with no domain knowledge — logging, metrics, tracing
propagation, HTTP client defaults — and poisonous for **domain** types. Across a
service boundary, **duplication is correct**: `shipping` should have its own
small `Order` class containing only the four fields it needs, and if `orders`
adds a field, `shipping` ignores it (tolerant reading). Two teams maintaining
two slightly different `Order` classes is not technical debt; it is the whole
point of a bounded context.

**6.2 — The one breaker.** The flaw is that breaker state is **global to the
service rather than per-dependency**.

*Concrete run:* the card network degrades, `payments` fails 20 times, the
breaker opens. Now the same open breaker rejects calls to `catalog` and
`inventory`, which are perfectly healthy — so the product page and the order
lookup fail too. **The mitigation manufactured the cascade it was bought to
prevent.**

*Violated:* fault isolation, and specifically the containment property in 10f.

*Second, subtler flaw:* "20 **consecutive** failures" is the wrong trigger under
mixed traffic. If a healthy `catalog` call succeeds between every two failing
`payments` calls, the consecutive counter resets forever and the breaker never
opens. The correct trigger is a **failure rate over a rolling window** (e.g.
>50% of at least 20 calls in the last 10 seconds), evaluated **per (dependency,
endpoint)** — because `POST /payments` failing tells you nothing about
`GET /payments/{id}`.

**6.3 — The helpful retry.** Every part of the retry *policy* is right — the
timeout, the backoff, the jitter, the budget. What is missing is an
**idempotency key**.

*Concrete run:* `orders` sends `POST /payments`. `payments` receives it, charges
the card, and begins writing its response — which is lost, or arrives at
2.1 seconds. `orders` timed out at 2.0 s and retries. `payments` sees a request
it cannot distinguish from a new one and charges the card again.

*Violated:* **safety** — the at-most-once effect. And note the shape from your
[idempotency](../49-idempotency/) lab: a timeout is the **unknown** outcome, and
retrying an unknown without a key is the definition of the double-charge bug.

*Fix, and all three parts are needed:* the caller mints the key **before the
first attempt** and reuses it verbatim on every retry (a fresh key per retry
defeats everything and the server cannot tell); `payments` **claims the key
atomically with the effect** — an `INSERT` under a `UNIQUE` constraint in the
same transaction as the charge record, not a check-then-act; and it **stores and
replays the response**, because answering "duplicate!" leaves the caller as
ignorant as before.

**6.4 — The reporting shortcut.** "Read-only" addresses the wrong risk. The
coupling created by a read is on the **shape** of the data, and shape is exactly
what a schema change breaks.

*Concrete run (schema):* `orders` renames `status` to `state`. The nightly
analytics job fails at 3am, in a service the `orders` team does not know exists
and cannot see in any dependency graph. This is F4 verbatim.

*Concrete run (operational):* an analyst runs an unindexed aggregate over
`orders` at 14:00. It holds a long transaction, consumes IO, inflates
replication lag and pushes checkout p99 past its SLO. A read-only credential
grants no writes and unlimited ability to hurt.

*Violated:* independent deployability (rule 2), and operational isolation.

*The fix ladder, weakest to strongest:* (1) point them at a **read replica** —
removes the IO coupling, keeps the schema coupling entirely; (2) **CDC into an
analytics store** — removes the operational coupling, and still couples to your
table shape unless the producer owns the transformation; (3) **publish events
designed as a contract**, versioned, with the same expand/contract discipline as
any other interface. Only (3) actually makes analytics a consumer you can see
and a shape you can change.

---

## Rung 7 — Handle failure

*(Design discussions. What matters is naming the mechanism.)*

**7.1 — The late decline.**

- **Order state machine, explicitly:**
  `PENDING → PAID → CONFIRMED` on the happy path; `PENDING → PAYMENT_FAILED →
  CANCELLED` on this one. The states are customer-visible, so they get
  customer-visible names.
- **What the customer sees.** They were told "Order received — confirming
  payment", never "confirmed". On decline: an email within a minute saying
  payment could not be taken, with a link to retry using another card, and the
  order held in `PAYMENT_FAILED` for a grace window (say 24 h) before
  cancelling. If a confirmation email was already sent, you now owe a correction
  email — which is why **the confirmation must be sent after the pivot step, not
  before**. That naming rule is the actual fix; everything else is cleanup.
- **The reservation.** Two independent mechanisms, because either can fail.
  (a) The saga's compensation explicitly releases it, idempotently. (b) The
  reservation carries a **TTL** — 30 minutes — after which `inventory` releases
  it unilaterally. (b) exists because (a) can be lost forever, and a reservation
  that leaks is stock you can never sell again.
- **The pivot step.** In [saga](../47-saga/) terms, the charge is the pivot: before
  it, compensations are cheap (release a reservation); after it, they cost real
  money (a refund with fees). Design so that everything reversible happens
  before the pivot and everything irreversible after it.
- **What support needs.** The saga log must be **durable and queryable**, not a
  transient queue: the agent needs to see every step attempted, its outcome, and
  its timestamp; to retry the charge against a new card; to force-cancel and
  release; and to see which compensations failed. A saga you cannot query is a
  saga you cannot support.
- **Idempotency everywhere.** The decline event will be delivered more than
  once. So will the compensation. Both must be idempotent, or the customer gets
  two cancellation emails and, worse, two refunds.

**7.2 — Surviving a `catalog` outage.**

- **Order-history page: stop calling `catalog` at all.** Store the product name
  and the price **on the order line, at the time of the order**. This is not a
  cache and it does not go stale — an order is a historical record of what was
  bought at the price paid, and showing today's name and price on last year's
  order is not a fresher answer, it is a **wrong** one. Recognising that a
  cross-service read is really a missing snapshot is the highest-value move in
  this exercise, and it removes the dependency permanently rather than
  softening it.
- **Product-detail page: cache with serve-stale-on-error.** A 60-second TTL for
  freshness, plus the ability to keep serving the last known value when
  `catalog` is unreachable — the `stale-if-error` behaviour from your
  [http-caching](../12-http-caching/) lab, and the same control-plane rule you met
  in [distributed-rate-limiting](../60-distributed-rate-limiting/): a cached copy
  that keeps serving when its source is gone. Cap it: beyond a chosen max-stale
  (say 24 h) show an error rather than a year-old page.
- **Never from the stale copy: the price used to charge.** F6's rule — display,
  never decide. Either read the price from `catalog` synchronously on the
  checkout path, or (better) snapshot the price into the order at the moment the
  customer added the item and charge from the snapshot, so there is no
  cross-service read on the critical path at all.
- **`catalog` itself**, as the highest-risk shared node (F8): the highest
  availability target you own, more replicas than its callers, HTTP caching at
  the gateway, and **load shedding** so it degrades rather than dies.
- **Every caller** gets its own bulkhead and breaker on `catalog`, so its
  slowness cannot become their outage.

**7.3 — Renaming `status` to `state`.**

- **Find the consumers.** In rough order of reliability: the **schema registry /
  event catalogue** if you have one; **consumer groups** on the topic (for
  events) and **access logs** on the endpoint (for HTTP), which give you actual
  observed callers rather than declared ones; the **service catalogue's**
  declared dependencies; and finally announcement-and-wait, which is the weakest
  and the most common.
- **Say the honest part out loud:** for events, you often *cannot* enumerate
  consumers, because subscribing does not require your permission. That is
  precisely the gap a schema registry with a consumer registry exists to close,
  and if you do not have one, this exercise is the business case for it.
- **The sequence, three deploys:**
  1. **Expand.** Emit both `status` and `state`, with identical values. Deploy.
     Verify by consuming your own topic that both fields are present on every
     message. Nothing can break — this is an additive change and tolerant
     readers ignore the new field.
  2. **Migrate.** Notify every known consumer with a deadline. Each moves on its
     own schedule. Measure before proceeding: every known consumer has
     positively confirmed (not "we announced it and heard nothing"), consumer
     lag is normal, error rates unchanged. Wait weeks, not days.
  3. **Contract.** Stop emitting `status`. Deploy. This is the only step that
     can break anyone, which is why it is last and separate.
- **If one consumer never migrates:** you do not remove the field. You either
  keep dual-writing indefinitely — recording it as debt with a named owner and a
  date — or you escalate. And if the consumer turns out to have **no owner**,
  you have found F9, and the fix is organisational: assign it, or delete it.
  Do not let an unowned service hold your schema hostage forever by default.

**7.4 — Monitoring the outbox relay.**

**Why queue depth is the wrong instrument** — three reasons, and the third is
the one that matters:

1. **It is traffic-dependent.** A depth of 500 is a crisis at 3am and normal at
   noon, so any static threshold is either noisy or blind.
2. **It reads zero when nothing is being written.** "Depth is 0" cannot
   distinguish "healthy and idle" from "the relay is dead and so is the writer".
3. **A head-of-line block hides completely.** If the relay is running but one
   poison row fails repeatedly, the relay keeps draining nothing while depth
   stays low and flat — and the oldest row ages forever. Depth is at its most
   reassuring exactly when this is happening.

**The three alarms to set** — all of them phrased as **age**, per the liveness
rule from [eventual-consistency](../28-eventual-consistency/):

1. **Age of the oldest unpublished outbox row > 60 s → page.** This is the SLO
   restated as an alarm, it is traffic-independent, and it catches the poison
   row, the dead relay, and the slow broker with one signal.
2. **Time since the relay last published anything > 120 s, while writes are
   occurring → page.** A machinery heartbeat: it catches the case where the
   relay dies during a genuinely quiet period, when alarm 1 has nothing to
   measure.
3. **Publish failure rate > 0 sustained for 60 s, or any row's retry count
   above a threshold → page** (and route the row to a DLQ). This names the
   poison row explicitly so the responder is not hunting for it.

Plus a fourth that is worth more than all three: an **end-to-end synthetic
probe**. Write a canary order every 30 seconds and measure how long until its
event is observed by a downstream consumer. That is `t-visibility` from the
eventual-consistency lab, it measures the property the customer actually cares
about, and it is the only one of these that survives you being wrong about which
component broke.

---

## Rung 8 — Design

**8.1 — The decomposition brief.**

**(a) What I would actually do in the first quarter: not split.** The stated
pain is a 4-hour weekly release with a go/no-go meeting and cross-team breakage
— and *none* of that requires splitting to fix. In order:

1. **Measure the real seams.** Take the last 100 merged pull requests and build
   a co-change matrix: which modules changed together. That is empirical
   evidence of where the boundaries actually are, and it usually contradicts the
   architecture diagram. Splitting without this is guessing.
2. **Fix the release process.** Trunk-based development, feature flags, an
   automated pipeline, canary deploys with automatic rollback. This takes weekly
   4-hour releases to daily low-risk ones and directly addresses "one team's
   change broke another team's feature" — faster, cheaper, and more reversibly
   than any extraction.
3. **Enforce module boundaries inside the monolith.** A modular monolith with
   build-tool-enforced dependency rules and one owning team per module. This is
   where you rehearse the boundaries for free: if a boundary is wrong, you find
   out in a refactor rather than in a migration.
4. **Build the prerequisites you will need anyway:** distributed tracing (yes,
   in the monolith — it works and you will need the instrumentation), a service
   catalogue with owners, and contract-testing capability.

**(b) First extraction: product-search.** Criteria, all four of which it meets:

- **A genuinely different resource profile** — CPU-bound, 70% of traffic, while
  everything else is comfortable. That makes independent scaling a *quantified*
  benefit rather than a slogan, and it is the one benefit a modular monolith
  cannot give you.
- **A read path.** No transactional invariant crosses the boundary, so rule 3 is
  satisfied and no saga is needed.
- **Its data can be a projection** — a search index fed by events, so there is
  no shared-table extraction to do.
- **One team can own it.**

`payments` is the plausible second: a different compliance boundary, a different
change cadence, and a clean invariant boundary. Extract one, live with it for a
quarter, and measure what it cost before extracting another.

**(c) The monthly metric: the median number of deployable units changed per
shipped feature** — which should stay at 1. Alongside it: deploy frequency per
team, and change failure rate. Those three say whether the benefit is arriving.
**A count of services says nothing at all**, which is why it is the number
everyone reports.

**(d) Three refusals, each in one sentence to leadership:**

- *"Microservices by Q3" as the goal* → "The date can't be the target because
  the thing we actually want is teams shipping without blocking each other — I'll
  report that number every month, and we'll add a service each time that number
  says a service is what's blocking it."
- *Splitting by technical layer* → "A separate validation or data-access service
  means every feature has to change every service, which is exactly the problem
  we have today with a network added to it."
- *Splitting all 340 tables in one migration* → "That's a two-quarter feature
  freeze, and the first thing we'd learn is that some of the boundaries were
  wrong; we can move one table at a time and keep shipping the whole way."

**8.2 — Availability budget.**

**(a)** Four equal services to reach 99.95%: each needs
`0.9995^(1/4) ≈ 0.999875`, i.e. **99.9875%**, about **1.1 hours of downtime per
year each** — including deploys, dependency failures and bad code. That is a
demanding target and it is worth stating in hours rather than nines, because
"99.99%" sounds achievable and "one hour per year, deploys included" does not.

**(b)** The card network publishes a **99.9%** SLA and is not under your
control. `payments` cannot be more available than its hard dependency, so
`payments ≤ 99.9%`, so a chain containing it synchronously is **at best 99.9%**
— already worse than the 99.95% you promised, before counting your own three
services. **The promise is unachievable as designed**, and no amount of work on
your own services changes that. An external dependency's SLA is a hard ceiling
on every path that requires it.

**(c) The only two structural options:**

1. **Take `payments` off the critical path.** Accept the order, charge
   asynchronously (10g). The synchronous chain becomes gateway + orders +
   inventory, needing `0.9995^(1/3) ≈ 99.983%` each (~1.5 h/year each) — hard,
   but possible, and entirely within your control.
2. **Make the dependency redundant.** Integrate a second card processor with
   automatic failover. If the two really are independent, the combined
   unavailability is the product and the ceiling disappears — but "independent"
   is an assumption that fails on the days that matter (a shared network path, a
   shared card scheme outage), and a failover path that is not continuously
   exercised does not work when you need it.

**Pick option 1 as primary.** It is under your control, it needs no vendor
negotiation, and — decisively — it protects you against the dependency being
*slow* as well as *down*, which option 2 does not. Add option 2 later if the
business genuinely requires synchronous authorisation before confirming an
order.

There is an honest third option nobody lists: **renegotiate the promise.**
99.95% may simply be the wrong number, and discovering that from the arithmetic
before you commit to it is worth more than any amount of engineering afterwards.

**(d) The three SLOs, and the one to page on:**

1. **Checkout success rate ≥ 99.95% over 28 days** — the customer-facing
   promise. This is the one you page on at 3am, and as a **burn-rate** alert
   (e.g. burning the 28-day budget at 14× over a 1-hour window), not a raw
   threshold — a threshold either fires on every blip or misses a slow bleed.
2. **Checkout latency p99 < 500 ms** — ticket-level unless burning fast. Note
   from 3.1 that latency degrades before availability does, so this is your
   leading indicator.
3. **Payment settlement freshness: 99.9% of accepted orders reach a terminal
   payment state within 5 minutes.** This is the SLO the asynchronous design
   *created*, it is the one that catches a dead outbox relay or a stalled saga,
   and it is the one teams forget to write — which is why 7.4's alarms exist.

**8.3 — Three regions, 150 ms apart.**

**Survives unchanged:** the service boundaries and contracts; the
outbox + saga shape; timeouts, bulkheads and breakers — which become *more*
important, because a 150 ms floor means a cross-region call's timeout budget is
mostly network and a retry is expensive.

**Must change — `orders` data placement.** Partition by region with
**home-region pinning**: a customer's orders live in their home region and are
written only there, with async replicas elsewhere for reads. The alternative —
multi-region writable replication — buys you write conflicts on a
transactionally meaningful entity, and the resolution ("last write wins" on an
order) is never acceptable. Cross-region reads are stale, and the customer's own
reads must be routed to their home region for read-your-writes
([causal-consistency](../29-causal-consistency/)).

**Must change — `inventory`, and this is the genuinely hard one.** Two
structures with a real trade:

- **One global stock pool.** Reservations must be linearizable and therefore
  cross-region: +150 ms on every checkout, and unavailable in the minority
  region during a partition. This is the **CP** choice, and the
  [cap-theorem](../24-cap-theorem/) lab decides it.
- **Stock partitioned per region** (e.g. 40/30/30 of the pool). Reservations
  become local, fast and partition-tolerant — the **AP** choice — at the cost of
  **stranded stock**: one region sells out while another has surplus. That is
  precisely the "stranded quota" and "regional share reallocation" shape you
  already met in [distributed-rate-limiting](../60-distributed-rate-limiting/), and
  the same fix applies: a slow background reallocation process, plus the ability
  to borrow across regions on a longer timescale than a request.

Note which lab decides which half. [cap-theorem](../24-cap-theorem/) decides the
partition case; **[pacelc](../25-pacelc/) decides the normal case, and the normal
case is what you live in every day** — 150 ms added to every checkout is a
conversion-rate problem that will hurt you far more often than a partition will.
Most businesses choose EL/PA here and accept stranded stock.

**Outbox and saga during a cross-region partition — the new failure mode.** The
outbox is local, so writes keep succeeding (good). The relay to a cross-region
broker stalls, so events queue and your **age** alarm fires (7.4, and it fires
correctly). The dangerous part is the saga: a saga whose next step lives in an
unreachable region **stalls mid-flight**, holding a semantic lock and an
inventory reservation. If your reservation TTL or saga timeout is shorter than
the partition, the system will **compensate orders that would have succeeded** —
cancelling paid orders because of a network event, then having to un-cancel them
when the partition heals. So: **reservation TTLs and saga step timeouts must
exceed your expected partition duration**, and a saga that times out during a
known partition should escalate to a human rather than compensate
automatically. False compensation is the new failure mode multi-region
introduces, and it is invisible until the first real partition.
