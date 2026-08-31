# Exercises — the eight-rung ladder

Attempt each rung before opening [`SOLUTIONS.md`](SOLUTIONS.md). This lab
has no code project, so rungs 5–6 are paper-engineering exercises (write
the ADR, find the planted flaw) — for architecture, paper *is* the medium:
the real-world artifact of this skill is a decision, written down. Rungs
7–8 are the ones that build the actual muscle; do them longhand.

---

## Rung 1 — Explain (no notation, no pattern names if you can help it)

**1.1** Explain to a smart non-engineer why "being careful" stops working
as a codebase grows, and what architecture does about it. Two paragraphs
max.

**1.2** Your teammate says: "Microservices are the modern way; monoliths
are legacy." Correct this in three sentences, naming what microservices
actually buy and what they cost.

**1.3** What is the difference between a *principle*, a *pattern*, and a
*style*? One sentence each, with one example each.

## Rung 2 — Identify

**2.1** Open the Pattern Atlas (`visualization.html`). Filter by "my
system falls over when one dependency slows down." Which family lights
up? Within it, which pattern is the *prerequisite* for all the others,
and why?

**2.2** A codebase has folders `controllers/`, `services/`,
`repositories/`. A single feature change ("add a tip to an order")
touches all three. Which style is this, which principle is being honored,
and which is being sacrificed?

**2.3** In the hexagonal code sketch in CATALOG Family 1: which lines are
domain, which are adapter, and in which direction do the compile-time
dependencies point? Why does `PlaceOrderService` have no Stripe import?

**2.4** Your company's diagram shows 30 services and one box labeled
"Integration Hub" that transforms messages and applies routing rules
based on order type. Which historical pattern is being reinvented, and
what is its known failure mode?

## Rung 3 — Predict

**3.1** Five services in a synchronous chain, each 99.9% available.
Predict end-to-end availability of the chain (compute it). Then predict
what it becomes if hops 3–5 are moved behind a durable queue.

**3.2** A team adds retries (3 attempts, no backoff) at the client, the
gateway, and the service layer. The database gets slow for 60 seconds.
Predict the request multiplication factor and the shape of the load graph
after the database recovers.

**3.3** Cache-aside with 10-minute TTLs, no event invalidation. A user
updates their delivery address, then immediately reloads the page, which
is served by a different app instance. Predict what they see and for how
long, and name the two distinct mechanisms that can make it stale.

**3.4** TiffinBox splits into services named `user-service`,
`database-service` (wraps the shared DB), and `logic-service`. Predict,
concretely, what a typical feature PR looks like six months later.

## Rung 4 — Modify

**4.1** Take the layered TiffinBox and make exactly one structural change
that lets the domain be tested without a database. What did you invert,
and who owns the new interface?

**4.2** The circuit breaker in CATALOG trips at 50% failure over 20
calls, cools off 30s. Modify it for a dependency that is a *payment
provider* (calls are money-moving, low volume, high value). Which
parameters change, which direction, and what non-parameter change does
"money-moving" force?

**4.3** An event-driven checkout uses pure choreography across 9
services. Modify the design to make the *order flow* debuggable without
losing fan-out benefits for analytics/notifications. What did you
centralize, what stayed decoupled, and what new component do you now own?

## Rung 5 — Implement (on paper: the ADR)

**5.1** Write a one-page ADR for TiffinBox at 12 engineers choosing
**modular monolith over microservices**. Include: context, at least two
options with honest pros/cons, the decision, and 3+ consequences —
at least one of which must be a *downside of the chosen option*.

## Rung 6 — Debug (find the planted flaws)

**6.1** An architect proposes this design for a flash-sale feature
(200k requests in the first minute):

> "The mobile app calls the API gateway, which synchronously calls
> `sale-service`. `sale-service` checks remaining stock by querying
> `inventory-service` (sync REST), reserves a unit by writing to the
> shared `inventory` table that `inventory-service` also writes, then
> synchronously calls `payment-service` (timeout 60s, 5 immediate
> retries on failure to make sure the charge goes through), then
> publishes `SalePlaced` to Kafka and commits its own DB row. Caching
> is added on stock counts with a 30s TTL so the stock check is fast."

There are at least **five** distinct architectural flaws, each violating
a named pattern or principle from this lab. Find them and name the
violation.

## Rung 7 — Handle failure

**7.1** TiffinBox's recommendation service ("customers also ordered...")
has become the #2 cause of checkout-page incidents, despite being the
least important feature on the page. Redesign the checkout page's
relationship to it so recommendation failures *cannot* affect checkout,
using at least three named patterns. Specify: the failure modes covered,
what the user sees in each, and what alerts fire.

**7.2** During a monolith → services migration (strangler fig), orders
exist in *both* the legacy DB and the new orders service for the 6-month
transition. The support team needs one consistent view, and the finance
batch job still reads the legacy schema. Design the transition-period
data architecture: who owns writes, how the two stores stay in sync, in
which direction, and what happens when they disagree.

## Rung 8 — Design (the real exam)

**8.1** Design **TiffinBox 2.0** end to end: 3M orders/day across 40
cities, 60 engineers in 8 teams (orders, payments, kitchen ops, riders,
pricing/promotions, customer app, restaurant app, data/ML), lunchtime
traffic is 20× baseline, riders' GPS pings are 2,000 writes/sec,
payments must never double-charge, and the CFO wants the infra bill
justified. Produce: (a) the service/module decomposition with the
*reasoning* per boundary, (b) sync vs async per interaction on the
place-order path, (c) the data architecture (per-store choices,
consistency stance per flow), (d) the resilience stack on the critical
path, (e) the deployment strategy, and (f) the three biggest risks of
your own design — argued as seriously as its strengths.

**8.2** (Reverse design) You inherit: 85 microservices, 9 engineers,
release train every 2 weeks because "everything depends on everything,"
p50 checkout latency 2.8s across 14 sync hops, AWS bill growing 3× YoY
with flat traffic. Management wants "microservices done right."
Argue for what you'd actually do instead, with a 12-month migration
plan, the metrics that gate each phase, and the political case for why
merging services is not an admission of failure.
