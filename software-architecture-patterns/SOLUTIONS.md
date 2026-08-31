# Solutions

Worked answers. For rungs 7–8 these are *discussions* — architecture has
no single correct output; judge your attempt by whether it named the same
tradeoffs, not whether it drew the same boxes.

---

## Rung 1

**1.1** As code grows, hidden connections between parts grow roughly with
the square of the code, while a person's carefulness stays constant — so
eventually a "safe" change breaks something far away that nobody
remembered was connected. Architecture fixes this structurally rather
than behaviorally: divide the system into parts with explicit boundaries,
control which part may know about which, and make the connections few and
visible — so the effect of a change becomes predictable and local, and
"careful" becomes achievable again because there's a bounded amount to be
careful about.

**1.2** Microservices buy independent deployment, per-team ownership, and
per-component scaling and fault isolation — benefits that only pay off
when you have multiple autonomous teams and genuinely different scaling
or isolation needs. They cost network failure modes on every call, loss
of cross-module transactions (hello sagas and eventual consistency), and
a large permanent operations bill. A well-modularized monolith is the
better engineering choice for most teams most of the time — Shopify and
Stack Overflow run at world scale on exactly that — so the monolith/
microservices question is about your *organization*, not modernity.

**1.3** A *principle* is a force or rule of thumb that makes designs good
or bad (low coupling). A *pattern* is a named, reusable solution-shape
with known tradeoffs for a recurring problem (circuit breaker). A *style*
is a pattern big enough to shape the entire system (event-driven
architecture).

## Rung 2

**2.1** The resilience family. The prerequisite is the **timeout**:
every other pattern in the family reacts to failure, and without
timeouts "slow" never becomes "failure" — retries never trigger,
breakers never see errors to count, bulkhead pools just fill up more
slowly. Unbounded waiting is the enabling condition of every cascade.

**2.2** Layered architecture. Honored: separation of concerns (each
folder is one technical kind of work). Sacrificed: cohesion per feature
— "the tip feature" has no single home, so every feature change fans
across all layers.

**2.3** Domain: the two interfaces (`OrderRepository`,
`PaymentGateway`) and `PlaceOrderService`. Adapters:
`PostgresOrderRepository`, `StripeGateway`, `OrderController`, and the
test fakes. All compile-time arrows point *inward*: adapters import the
domain; the domain imports nothing technological. `PlaceOrderService`
has no Stripe import because dependency inversion puts the interface on
the policy side — the domain defines *what it needs* (`charge(...)`) in
its own vocabulary, and Stripe is a plugin conforming to it, not a
foundation the domain stands on.

**2.4** The Enterprise Service Bus (SOA-era). Failure mode: business
logic sinks into the shared middleware until the "hub" is a new
monolith that every team queues behind, owned by an integration team,
undeployable independently — the exact disease "smart endpoints, dumb
pipes" was coined against.

## Rung 3

**3.1** Chain: 0.999⁵ ≈ **99.50%** (~43.5 h/year of failure — vs ~8.7 h
for a single 99.9% system). With hops 3–5 behind a durable queue, the
*user-facing* operation only needs hops 1–2 up: 0.999² ≈ **99.80%**
(~17.5 h/yr). The queued work completes later regardless — availability
was bought by *removing simultaneity*, not by improving any service.
(Caveat worth naming: the broker's own availability now enters the
math, but brokers are engineered and operated for exactly that role.)

**3.2** Multiplication: 3 × 3 × 3 = **27 attempts per user request**,
arriving with no spacing, exactly while the database is sickest. After
recovery, expect the *metastable* signature: a mountain of queued and
synchronized retries slams the recovering database, which slows again,
which triggers more retries — load stays pinned above capacity even
though the root cause is fixed, until traffic is shed or retries drain.
The graph looks like: outage → recovery attempt → self-inflicted second
outage → sawtooth.

**3.3** The page likely shows the **old address**. Two distinct
mechanisms: (1) TTL staleness — the other instance's cache still holds
the old value for up to 10 minutes because nothing invalidated it;
(2) even with delete-on-write invalidation, the read-repopulate race —
a concurrent read that fetched the old row just before the write can
fill the cache with stale data *after* the invalidation ran. (If a read
replica feeds the cache fill, replica lag is a third contributor.)
Bounded fix: event-driven invalidation + TTL as backstop + read-your-
own-writes routing for the updating user.

**3.4** The distributed monolith signature: a typical feature PR spans
three repos (`logic-service` for the rule, `database-service` for the
new access method, `user-service` for the field), the three changes
must deploy in a coordinated order, and a version mismatch between them
is a production incident. Services named after *technical roles*
(database, logic) rather than business capabilities guarantee this —
every business change cuts across all of them by construction.

## Rung 4

**4.1** Invert the persistence dependency: define an `OrderRepository`
interface **owned by the domain**, move all SQL into an adapter
implementing it, and hand the domain its repository from outside
(constructor injection). The domain now depends on an interface it
owns; Postgres depends on the domain's contract. Tests plug in an
in-memory implementation. (This is the layered → hexagonal move in
one step; nothing else needs to change yet.)

**4.2** Direction of changes: **more sensitive and more patient.** Trip
faster: low volume means a 20-call window reacts too slowly — shrink
the window (e.g. 10) and/or trip on consecutive failures (e.g. 5) since
each failure is high-value; lengthen the cool-off (payment providers'
incidents last minutes, not seconds) and let HALF_OPEN admit exactly
one probe. The non-parameter change "money-moving" forces: the fallback
cannot be "fail silently" and retries/probes MUST carry **idempotency
keys** — a timeout is not evidence the charge didn't happen, so the
breaker must treat timeouts as "unknown outcome," never re-charge
without a key, and the fallback becomes an explicit product decision
(queue order + charge later, or tell the user payment is unavailable).

**4.3** Introduce an **orchestrator** (a saga orchestrator / workflow
engine) that owns the order flow's core steps — reserve stock, charge,
confirm kitchen — as explicit, inspectable state; it still communicates
with participants via commands/events over the broker. Keep pure
pub-sub **choreography for the passive fan-out** — analytics,
notifications, fraud scoring, loyalty — which subscribe to the events
the flow emits and can multiply freely without touching it. You
centralized *the workflow's decision logic and state*; you kept
*reaction fan-out* decoupled. You now own a new stateful component:
the orchestrator needs HA, monitoring, and a UI/console where a human
can see and resume stuck flows — that operational surface is the price
of debuggability, and it's a good trade for the *core* flow only.

## Rung 5

**5.1** A good ADR hits these beats (yours can differ in content):

> **ADR-007: Modular monolith for TiffinBox core (not microservices)**
> **Context.** 12 engineers in 3 loose teams; Rails-era monolith, no
> enforced internal boundaries; release conflicts rising; domain
> boundaries (orders vs kitchen vs riders) still shifting monthly as the
> business changes; no platform team; on-call is 2 people.
> **Option A — microservices now.** Pros: independent deploys,
> per-team ownership, differential scaling later. Cons: boundaries
> would be drawn before the domain is stable (high distributed-monolith
> risk); we'd inherit sagas/outbox/observability as day-one mandatory
> equipment with no platform team to own them; ops load exceeds 2-person
> on-call.
> **Option B — modular monolith with enforced seams.** Pros: team
> ownership via modules; boundaries refactorable in-process while the
> domain settles; one transaction scope keeps checkout simple; ops
> unchanged. Cons: shared release cadence remains; one runtime = shared
> blast radius; requires new build tooling (ArchUnit rules) and
> discipline to stop seam erosion.
> **Decision.** Option B. Modules: orders, payments, kitchen, riders,
> catalog — by business capability; each with a public facade, private
> tables, in-process events between them.
> **Consequences.** (1) We accept the shared release train for now;
> revisit when team count passes ~5 (trigger written here on purpose).
> (2) Boundary fitness functions run in CI from week one — an
> unenforced module boundary is a drawing, not a boundary. (3) Payments
> module gets an outbox even in-process, so a future extraction doesn't
> change its contract. (4) *Accepted downside:* a memory leak anywhere
> still restarts everything; we mitigate with per-module resource
> monitoring, not isolation we haven't paid for.

**5.2 (grading your own)** Must-haves: a real second option argued
honestly; a written *trigger for revisiting*; at least one consequence
that is a cost of the chosen option. An ADR with no downsides recorded
is advocacy, not a decision record (Second Law: the why must survive).

## Rung 6

**6.1** The five-plus flaws:

1. **Shared database** — `sale-service` writes the `inventory` table
   that `inventory-service` also writes. Violates database-per-service;
   creates a hidden interface-less contract; also a check-then-write
   race on stock under 200k concurrent requests.
2. **Retry storm on a non-idempotent operation** — 5 *immediate*
   retries on payment "to make sure the charge goes through" is 5
   chances to double-charge (no idempotency key mentioned, no backoff,
   no jitter) aimed at a struggling provider. Violates
   retry-with-backoff discipline and idempotency prerequisites.
3. **60s timeout on the user path** — holds a request thread for up to
   a minute (× 5 retries!) during the biggest traffic burst of the
   year; thread-pool exhaustion → cascade. Timeout must be sized to
   the user's latency budget; and where are the circuit breaker and
   bulkhead on this critical path? Absent entirely.
4. **Dual write** — publish `SalePlaced` to Kafka, *then* commit the
   local row: a crash between them announces sales that don't exist
   (and reversed order silently drops events). This is the exact
   problem the transactional outbox exists for.
5. **Stale cache used for a correctness decision** — a 30s-TTL cached
   stock count during a flash sale means selling from a count up to
   30s old at 200k req/min: guaranteed oversell. Caching is for
   *performance* reads, never for the authoritative check in a
   reserve decision; the reserve must be an atomic conditional
   operation at the source of truth (or a pre-allocated token/queue
   scheme).
6. (Bonus) **Fully synchronous chain for a burst-shaped workload** —
   gateway → sale → inventory → payment in-line under 20× burst is the
   availability-multiplication trap; a flash sale is the canonical
   case for admission control + queue (load leveling), with async
   confirmation.

## Rung 7

**7.1** Pattern stack (minimum viable set):
- **Timeout** 150ms on the recommendations call (checkout page budget,
  not recommendation service's comfort).
- **Circuit breaker** around it — on OPEN, checkout renders instantly
  with the fallback; alert fires on the transition (page the *recs*
  owner, not the checkout on-call).
- **Fallback / graceful degradation** — pre-decided with product: show
  editorially-curated "popular near you" (served from a static cache),
  or nothing — the section collapses; checkout functionality identical
  in all cases.
- **Bulkhead** — recommendations calls get their own small connection/
  thread pool (or move fully async: fetched after page render via a
  separate endpoint), so even breaker-missed slowness can't touch
  checkout's resources.
Failure modes covered: recs down (breaker OPEN → fallback, ~0 added
latency), recs slow (timeout → fallback at +150ms worst case; bulkhead
caps concurrent exposure), recs erroring (breaker counts, opens).
Alerts: breaker state transitions, fallback-serve rate > threshold,
recs pool saturation. The success metric: recommendations can be *hard
down for a day* and checkout conversion doesn't move.

**7.2** Transition data architecture (one defensible shape):
- **Single write owner per capability at any moment** — the strangler
  facade routes each order-related *write* to exactly one world;
  migrate write ownership capability-by-capability (e.g. new orders →
  new service; refunds still legacy) rather than splitting one
  capability's writes.
- **Sync direction follows ownership:** new-service writes flow to the
  legacy schema via **CDC/outbox → a legacy-updater adapter** (so the
  finance batch job keeps working untouched — an anti-corruption layer
  writing *into* the old shape); anything still legacy-written flows
  the other way via CDC into the new service's store. One direction
  per table set — bidirectional sync of the same rows is where these
  migrations go to die.
- **Support's one consistent view:** a read model (CQRS-style)
  projecting *both* streams into one queryable store, labeled with
  source and sync lag — honest eventual consistency rather than
  pretended uniformity.
- **On disagreement:** the write owner is truth by definition; a
  reconciliation job diffs the stores continuously, auto-repairs the
  follower, and *pages a human with the diff* when repair fails —
  during migrations, silent disagreement is the real enemy, so the
  metric "rows in disagreement, by age" is a first-class SLO on the
  migration dashboard.
- **End state discipline:** every capability flip has a date; finance's
  batch job gets a migration deadline to read the new API/warehouse —
  otherwise this becomes a lava flow (the half-strangled forever-state).

## Rung 8

**8.1** A strong answer's skeleton (compare beats, not boxes):
- **(a) Decomposition mirrors the 8 teams** (Conway, on purpose):
  orders, payments, kitchen, rider-logistics, pricing/promotions as
  services or hard modules; the two apps as clients behind **two BFFs**
  (customer, restaurant); data/ML consuming the event stream, never
  production DBs. Boundary reasoning must be capability + ownership +
  divergent -ilities: payments isolated for consistency/compliance;
  rider-GPS isolated for its write shape (2k writes/s telemetry —
  different store, different availability stance); pricing separate
  because it changes at marketing speed, not platform speed. A merged
  orders+kitchen is defensible at 8 teams; entity-services
  (user-service) or technical services (database-service) are the
  automatic-fail answers.
- **(b) Place-order path:** sync for the user-facing decision segment —
  app → BFF → orders → payments (charge with idempotency key) — then
  **async for all fan-out**: `OrderPlaced` via outbox → kitchen,
  riders, notifications, loyalty, analytics. Justification: the user
  must know "paid & confirmed" now (sync, short chain, ~2 hops); the
  kitchen finding out is "soon" (async). GPS pings: fire-and-forget
  into a stream (Kafka), never request/response.
- **(c) Data:** DB-per-service; orders/payments on relational stores
  (payments: serializable + idempotency-key table — "never
  double-charge" is an idempotency argument, not a 2PC argument);
  GPS into a time-series/wide-column store with TTL; promotions
  cached aggressively (staleness harmless); analytics via the event
  stream into a warehouse. Consistency stance stated per flow:
  checkout strongly consistent inside payments, eventually consistent
  everywhere downstream, with read-your-own-writes on the customer's
  own order status.
- **(d) Resilience on the critical path:** timeouts sized from a
  stated user budget (e.g. 2s end-to-end), breakers + bulkheads on
  the BFF→orders and orders→payments hops, retry budget with jitter
  and keys, fallbacks decided per widget (ETA degraded, checkout
  never), load shedding at the gateway by priority at lunch peak
  (20× burst: autoscale the stateless tier ahead of the lunch curve —
  it's predictable! — and shed analytics-grade traffic first).
- **(e) Deployment:** canary + flags on customer-visible services,
  expand/contract schema discipline everywhere, blue-green acceptable
  for low-state services; deploy independence is the whole point of
  the split, so no release trains.
- **(f) Self-critique (the differentiating section):** e.g. (1) the
  event backbone is now tier-0 — broker outage stops kitchen fan-out:
  what's the buffered/degraded mode? (2) 8-team boundaries will be
  wrong somewhere within a year — which seams are cheapest to move,
  and did we keep them cheap? (3) the CFO question: BFFs, a mesh, and
  per-service infra multiply baseline cost — the honest answer prices
  the platform team and defers the mesh until service count demands
  it. An answer with no self-critique hasn't internalized the First
  Law.

**8.2** The defensible position: **this is a distributed monolith at
1/10th the team size that could sustain it — consolidate.** ~85
services / 9 engineers ≈ 9+ services per engineer; the release train
proves deploy-independence (microservices' core product) was never
achieved, so you're paying the full bill for no benefit. Plan shape:
- **Phase 0 (month 1–2), measure:** dependency-map the 85 (tracing
  data), find the 14-hop checkout chain, classify services into
  natural business capabilities; baseline the gating metrics —
  deploy lead time, cross-repo PR rate, p50/p99 checkout, infra cost
  per order.
- **Phase 1 (2–6), consolidate the core:** merge each cluster of
  chatty, co-deployed services into one modular service per capability
  (checkout's 14 hops → likely 2–3), strangler-style: new consolidated
  service stands up, facade/gateway shifts routes, old services
  drain. In-process calls replace network hops → latency and cost
  fall mechanically.
- **Phase 2 (6–10):** collapse the long tail (nanoservices → modules),
  delete idle infrastructure, keep genuinely separate only what has a
  named reason (payments compliance, one high-scale hot path).
  Target: ~6–10 deployables for 9 engineers.
- **Phase 3 (10–12):** boundary fitness functions + ADRs so the
  consolidated modules stay honest and future re-extraction stays
  cheap.
- **Gates per phase:** checkout p50 (2.8s → <800ms is realistic when
  hops collapse), deploys/week per engineer, % PRs spanning >1 repo
  (→ near zero), infra cost per order (the 3× YoY bill is the CFO's
  proof).
- **The political case:** frame with industry precedent — Amazon Prime
  Video's own team merged a microservice pipeline back and cut 90% of
  its cost; Segment did the same and wrote it up. "Microservices done
  right" *means* matching service count to team count (Conway's law);
  merging is not retreating from modern architecture, it is what the
  people who invented modern architecture do when the math says so.
  The modules keep their seams, so the day the org has 30 teams, the
  extraction path is rehearsed and cheap — you are keeping the
  option, and stopping the payments on an option you don't hold.
