# The Pattern Catalog — exhaustive reference

Companion to [`README.md`](README.md). The README tells the story and earns
the vocabulary; this file is the reference you return to when choosing.
Every entry answers the same six questions:

> **Problem** it solves · **How** it works · **Use when** · **Real systems**
> using it · **Do NOT use when** (and why) · **The bill** (what you pay).

Patterns you learned in a dedicated lab get a short entry plus a link —
the lab is the depth.

**Families:**
[1. Structuring one codebase](#family-1--structuring-one-codebase) ·
[2. Distribution styles](#family-2--distribution-styles) ·
[3. Communication & integration](#family-3--communication--integration) ·
[4. Data](#family-4--data-patterns) ·
[5. Resilience](#family-5--resilience-patterns) ·
[6. Deployment & operations](#family-6--deployment--operational-patterns) ·
[7. Anti-patterns](#family-7--the-anti-pattern-gallery) ·
[8. Principles quick reference](#family-8--principles-quick-reference) ·
[9. Selection cheat sheet](#9--selection-cheat-sheet)

---

## Family 1 — Structuring one codebase

### Layered architecture (n-tier)

- **Problem:** unstructured code mixes HTTP parsing, business rules and SQL
  in one function; nothing can be changed or tested in isolation.
- **How:** horizontal layers by technical kind — presentation → business →
  persistence → database — each calling only downward. A stricter variant
  ("closed layers") forbids skipping a layer.
- **Use when:** CRUD-dominant apps, internal tools, small teams, early
  products; when you need structure at near-zero learning cost. It is the
  correct *default starting point* for most applications.
- **Real systems:** the majority of business applications ever written;
  classic Spring MVC apps (`@Controller` → `@Service` → `@Repository`).
- **Do NOT use when:** the domain is deep and long-lived (rules leak out of
  the middle layer and the database's table shapes start driving the
  design — the doc's stage-10.2 erosion), or when features need team-level
  ownership (a feature spans all layers, so no team owns anything
  end-to-end).
- **Bill:** low cohesion per feature (one feature = edits in every layer);
  gravitational pull toward database-centric design; "architecture sinkhole"
  requests that pass through layers adding nothing.
- **Related:** hexagonal (fixes the database-at-the-bottom problem),
  vertical slices (fixes the feature-smearing problem).

### Hexagonal architecture (ports & adapters)

- **Problem:** business rules entangled with frameworks and vendors — you
  cannot test the domain without a database, or swap a vendor without
  touching the rules. Layered architecture *reduces* this but leaves the
  domain depending on persistence.
- **How:** the domain sits at the center and owns **ports** — interfaces in
  domain vocabulary. All technology (HTTP, DB, brokers, vendors, tests) is
  an **adapter** implementing or calling a port from outside. Every arrow
  points inward. Distinguish *driving* adapters (things that call the
  domain: REST controller, scheduler, test) from *driven* adapters (things
  the domain calls through a port: Postgres repository, Stripe client).
- **The shape in code** (Java, minimal — the load-bearing lines are the
  two interfaces and who owns them):

  ```java
  // ── domain (owns its ports; imports NOTHING technological) ──
  public interface OrderRepository {            // driven port
      Order load(OrderId id);
      void save(Order order);
  }
  public interface PaymentGateway {             // driven port
      PaymentResult charge(Money amount, PaymentToken token);
  }
  public class PlaceOrderService {              // the policy
      private final OrderRepository orders;     // depends on interfaces only
      private final PaymentGateway payments;
      public OrderId placeOrder(Cart cart, PaymentToken token) {
          var order = Order.from(cart);                 // pure domain logic
          var result = payments.charge(order.total(), token);
          if (result.declined()) throw new PaymentDeclined(result.reason());
          orders.save(order.confirmed());
          return order.id();
      }
  }
  // ── adapters (outside; know the technology AND the domain) ──
  class PostgresOrderRepository implements OrderRepository { /* SQL here */ }
  class StripeGateway implements PaymentGateway { /* Stripe SDK here */ }
  class OrderController { /* HTTP here; calls PlaceOrderService */ }
  // tests plug InMemoryOrderRepository + FakeGateway into the same ports
  ```

  What to notice: `PlaceOrderService` compiles with zero framework imports;
  Stripe→Adyen is a new class, not an edit; the whole checkout rule set is
  testable in microseconds with fakes.
- **Use when:** the domain logic is the valuable part and will outlive any
  framework/vendor; integrations are numerous or likely to change; you
  want fast domain tests.
- **Real systems:** standard in DDD-practicing backend teams; the shape
  behind most "clean architecture" service templates; Netflix and many
  banks structure core services this way internally.
- **Do NOT use when:** the app is a thin CRUD veneer over a database —
  the ports/adapters/mapping tax buys nothing because there is no domain
  to protect. (A 10-endpoint admin tool with hexagonal ceremony is a
  classic over-engineering smell.)
- **Bill:** more types, more mapping between domain objects and
  DTOs/rows; discipline needed to stop framework types leaking inward.
- **Related:** clean/onion (same family), DIP (its principle), modular
  monolith (hexagonal works *inside* each module).

### Clean architecture / Onion architecture

- **One idea, three brandings:** domain at the center, dependencies point
  inward, technology at the rim. Clean (Robert Martin) prescribes rings —
  entities → use cases → interface adapters → frameworks — and the
  **dependency rule** (source dependencies only inward). Onion (Palermo)
  is the same with different ring names.
- **Use / don't / bill:** as hexagonal. Choose whichever vocabulary your
  team already speaks; do not debate the three names — it is one pattern.
- **Trap:** treating the ring diagram as mandatory folders. Rings are a
  dependency rule, not a directory layout; four layers of pass-through
  interfaces for a simple feature is the pattern misread as ceremony.

### Vertical slice architecture

- **Problem:** layer-first organization gives one feature no home — every
  change fans across controller/service/repository folders, and teams
  can't own features.
- **How:** organize by feature. Each slice (`place-order/`, `refund/`)
  contains its request handler, its logic, its data access. Slices don't
  call each other's internals; genuinely shared domain concepts get an
  explicit shared kernel (kept deliberately small).
- **Use when:** request/response-shaped apps with many largely-independent
  features; teams that want per-feature ownership and per-slice
  simplicity ("this slice is simple CRUD, that slice is rich domain" —
  each slice picks its own internal style).
- **Real systems:** popularized in the .NET world (Jimmy Bogard); common
  in modern Node/Go service templates.
- **Do NOT use when:** the domain is one deep, heavily-shared rule set
  (pricing engines, risk models) — slices would each need the whole
  domain, and the shared kernel becomes the app; hexagonal fits better.
- **Bill:** duplication pressure across slices; shared-rule placement
  needs judgment; cross-slice refactors (renaming a core concept) touch
  many folders.

### Modular monolith

- **Problem:** one team-sized codebase → many teams stepping on each
  other; but microservices' bill (network, sagas, ops) isn't justified.
- **How:** one deployable; inside, modules cut by **business capability**
  (orders, payments, kitchen), each with a public API (facade/interface),
  build-enforced private internals (ArchUnit / Java modules / Gradle
  module boundaries / import-linting), and privately-owned tables.
  Modules call each other's public APIs in-process, or publish in-process
  events. Each module is a rehearsal of a future service — extraction
  becomes "move code + swap in-process call for network call."
- **Use when:** team is growing past ~8–10; you want ownership and local
  reasoning without distribution; you suspect you'll need microservices
  *later* but boundaries aren't proven yet. This is the modern default
  recommendation for serious systems.
- **Real systems:** Shopify (flagship example — world-scale commerce, one
  majestic modular monolith); GitHub's Rails app; many banks' core
  ledgers.
- **Do NOT use when:** you genuinely need *independent deployment* or
  *differential scaling today* (regulatory isolation, one component
  needing 50× the fleet) — the monolith deploys and scales as one lump;
  or the org is already dozens of autonomous teams (Conway pressure will
  shred the shared release train).
- **Bill:** discipline/tooling to keep seams honest (unenforced modules
  erode into a big ball of mud); one runtime = shared fate for crashes
  and resource exhaustion; one tech stack.
- **Related:** microservices (its extraction target), strangler fig (the
  extraction method), fitness functions (the seam enforcement).

### Microkernel / plugin architecture

- **Problem:** a product whose value is *extensibility* — many optional,
  per-customer, or third-party behaviors around a small stable core.
- **How:** minimal core system (lifecycle, plugin registry, extension-point
  contracts) + plugins implementing extension points. Core knows the
  contract, never the plugins. Open/Closed as a whole architecture.
- **Use when:** IDEs, browsers, CI systems, payment/tax engines with
  per-country rules, anything with a marketplace.
- **Real systems:** Eclipse, VS Code, Chrome extensions, Jenkins, Kafka
  Connect, Stripe's per-method payment integrations.
- **Do NOT use when:** the variation points aren't real ("we might want
  plugins someday" is YAGNI — extension-point contracts are among the
  hardest interfaces to get right, and wrong ones fossilize).
- **Bill:** contract design and versioning (plugins built against v1 must
  survive core v2); plugin isolation (a bad plugin crashing the host);
  registry/discovery machinery.

### Pipes and filters

- **Problem:** a transformation-shaped job — data flows through successive
  independent processing steps.
- **How:** each **filter** does one transformation, knows nothing of its
  neighbors; **pipes** carry data between them; the topology is
  composition. Filters are independently testable, reorderable, reusable,
  and parallelizable per-stage.
- **Use when:** compilers, ETL/data pipelines, media processing, log
  enrichment, stream processing.
- **Real systems:** Unix shell pipelines; Kafka Streams topologies; Apache
  Beam/Flink jobs; scikit-learn pipelines.
- **Do NOT use when:** the logic is interactive/stateful rather than
  flow-shaped, or steps need rich shared context (you end up threading a
  god-context object through every filter — the pattern fighting you).
- **Bill:** end-to-end error handling and per-stage backpressure need
  design; debugging means inspecting intermediate flows.

### MVC / MVP / MVVM (noted for completeness)

UI-tier patterns separating screen rendering from screen logic from data.
They live one altitude below this lab (inside the presentation part) —
recognize the names; the architectural content of this catalog doesn't
change with the one you pick.

---

## Family 2 — Distribution styles

### The monolith (as a deliberate choice)

- Not an anti-pattern. One deployable = one transaction scope (real ACID
  across the whole domain — no sagas, no outbox, no consistency windows),
  one thing to debug with a stack trace, one thing to deploy, in-process
  call latency (~ns, not ms), and the cheapest ops bill in this catalog.
- **The failure mode is the *unstructured* monolith** (big ball of mud),
  not the monolith itself. A modular monolith at scale is a proud,
  competitive architecture (Shopify, Stack Overflow).
- Choose distribution only when a named pressure (team count, differential
  scale, fault isolation, independent deploy cadence, regulatory
  isolation) outgrows it.

### Microservices

- **Problem:** many teams sharing one deployable and one runtime — release
  trains, lockstep rollbacks, whole-process blast radius, one-size-fits-all
  scaling.
- **How:** services split by business capability, each: independently
  deployable, owned end-to-end by one team ("you build it, you run it"),
  **owning its data privately**, communicating via APIs and events. Size
  guidance that survives contact with reality: "owned by one team, fits in
  one team's head" — not "small."
- **Use when:** multiple genuinely autonomous teams; components with
  wildly different scaling or availability needs; parts needing different
  stacks or compliance isolation. The trigger is *organizational* before
  it is technical (Conway).
- **Real systems:** Amazon (the 2002 mandate), Netflix (~1000 services),
  Uber, most large SaaS.
- **Do NOT use when:** the team is small (< ~3 teams — the fixed bill
  exceeds the benefit; F2 in the README); the domain is not yet understood
  (boundaries drawn early are drawn wrong → distributed monolith, F1);
  you cannot yet operate them (no CI/CD, no observability stack, no
  on-call — microservices without the platform is outage roulette).
- **Bill:** the full distributed tax: network failure modes on every call,
  eventual consistency ([saga](../47-saga/), [outbox](../48-transactional-outbox/)),
  resilience patterns as mandatory equipment (Family 5), an observability
  stack as mandatory equipment, service scaffolding/platform investment,
  and much harder cross-service refactoring (boundaries fossilize).
- **Related:** modular monolith (the rehearsal), strangler fig (the
  migration), everything in Families 3–5 (the equipment).

### SOA (service-oriented architecture) with ESB

- **Historical.** Services (often large, entity-ish) integrated through a
  smart **Enterprise Service Bus** owning routing, transformation and
  orchestration. The bus accreted business logic until it was the new
  monolith, owned by an integration team every other team queued behind.
- **Why it's still worth knowing:** (1) you will meet it in enterprises;
  (2) its failure is the origin of microservices' "smart endpoints, dumb
  pipes"; (3) its ghost recurs whenever logic sinks into an API gateway
  or a config-heavy iPaaS. Recognize the smell: *the pipe knows the
  business.*

### Event-driven architecture (EDA)

- **Problem:** synchronous service chains multiply unavailability
  (0.999⁵ ≈ 99.5% — the README's stage-10.3 calculation) and couple every
  producer to every consumer's existence, health and speed.
- **How:** producers publish **events** (facts, past tense: `OrderPlaced`)
  to a durable **broker**; consumers subscribe and react at their own
  pace. Producers don't know consumers. New consumers attach without
  touching producers.
- **The three event flavors** (Fowler) — choose per event stream:
  - **Event notification:** the event is a pointer ("order 1042 placed");
    consumers call back for details. Smallest coupling to schema, but
    reintroduces a sync dependency (the callback) and its load.
  - **Event-carried state transfer:** the event carries the data;
    consumers keep local copies. Full consumer autonomy (producer can be
    down; consumers keep serving), at the price of copies that go stale
    and fat events that fossilize schemas.
  - **[Event sourcing](../50-event-sourcing/):** events are a service's own
    source of truth (a storage decision inside one service — orthogonal
    to using EDA between services).
- **Use when:** naturally reactive domains (orders trigger kitchen,
  riders, notifications, fraud, analytics…); fan-out to many consumers;
  load leveling (queue absorbs bursts); audit/integration streams.
- **Real systems:** LinkedIn (Kafka's birthplace), Uber, most modern
  retail/fintech backbones; virtually every large company's integration
  layer now has an event backbone.
- **Do NOT use when:** the caller needs the answer *now* (a query is not
  an event); workflows demand visible, strongly-ordered, transactional
  steps that would fight eventual consistency; team lacks the muscle for
  idempotency/duplicates/replay (the [outbox](../48-transactional-outbox/)
  lab's disciplines are prerequisites, not extras).
- **Bill:** consistency windows on every consumer ([cqrs](../51-cqrs/) lag
  lessons apply); at-least-once ⇒ idempotent consumers everywhere;
  workflow invisibility (F4 event spaghetti — budget for tracing and a
  schema registry from day one); the broker is now tier-0 infrastructure.
- **Related:** saga choreography, outbox (how events leave a service
  without dual-writes), CDC, schema registry.

### Serverless / FaaS

- **Problem:** paying for and operating always-on servers for spiky,
  glue-shaped, or low-volume workloads.
- **How:** deploy functions; the platform (AWS Lambda, Cloud Functions)
  runs them per-event, scales to zero and to thousands, bills
  per-invocation. Pairs with managed queues/streams/API gateways —
  "architecture as a wiring diagram of managed services."
- **Use when:** event handlers, scheduled jobs, image/file processing,
  webhooks, unpredictable burst traffic, small teams that want zero
  server ops.
- **Real systems:** vast AWS-ecosystem usage; many startups' entire glue
  layer; parts of Netflix's encoding pipeline.
- **Do NOT use when:** latency-sensitive paths where cold starts bite;
  long-running or stateful computation (execution limits); sustained high
  volume (per-invocation pricing crosses over above always-on cost); or
  when vendor lock-in is a real strategic constraint.
- **Bill:** cold starts; local testing/debugging friction; distributed
  tracing across a confetti of functions; per-vendor programming model.

### Space-based architecture

- **Problem:** extreme concurrent write bursts (ticket on-sales, flash
  sales, trading opens) that would melt any central database.
- **How:** the hot path runs entirely against replicated **in-memory data
  grids** in each processing unit; the database is asynchronously updated
  behind the grid, out of the hot path. Processing units scale
  elastically; a data-replication layer keeps grids in sync.
- **Real systems:** ticketing platforms, trading systems, gaming
  leaderboards; grids like Hazelcast, Apache Ignite, GigaSpaces (where
  the pattern was named).
- **Do NOT use when:** data doesn't fit in memory economically; strong
  consistency is required (grid replication is eventual — CAP applies);
  or the burst problem could be solved more cheaply with a queue (load
  leveling) in front of a normal database.
- **Bill:** high complexity and cost; eventual consistency between grid
  and database; operational rarity (few engineers have run one).

### Client-server, N-tier, peer-to-peer, cell-based (recognition entries)

- **Client-server / N-tier deployment:** the deployment-topology ancestor
  of everything above — UI tier, app tier, data tier on separate machines.
  Still the physical shape of most systems; mentioned because "3-tier" in
  conversation means this, not the layered *code* style.
- **Peer-to-peer:** no server; peers coordinate directly (BitTorrent,
  blockchain, Discord's early voice). Specialist.
- **Cell-based:** partition the *whole stack* into independent cells, each
  serving a slice of customers; a cell failure strands only its slice
  (blast-radius control at company scale — AWS internal, Slack, DoorDash).
  Advanced follow-on to microservices.

---

## Family 3 — Communication & integration

### Synchronous vs asynchronous (the fundamental dial)

- **Sync (REST/gRPC request-response):** simple mental model, immediate
  answers, natural for queries — and temporally couples caller to
  callee's health and latency; chains multiply unavailability; load
  propagates downstream unbuffered.
- **Async (queues/events):** decouples time — callee may be down, caller
  unaffected; queues absorb bursts (load leveling); enables fan-out — and
  costs duplicates, ordering questions, consistency windows, and workflow
  invisibility.
- **Best practice:** sync for user-facing queries and genuine
  request/reply; async for side effects, fan-out, and anything that can
  be "soon" instead of "now." Never build a synchronous chain deeper than
  ~2–3 hops on a user-facing path without a stated reason.

### API gateway

- **Problem:** N clients × M services each reimplementing auth, TLS, rate
  limiting, routing; clients coupled to internal service topology.
- **How:** one edge component all external traffic enters: routing,
  authn/z, rate limiting, TLS termination, caching, request logging.
  Internal topology becomes invisible to clients.
- **Real systems:** Kong, AWS API Gateway, Apigee, Envoy edge, Zuul.
- **Do NOT use when:** a single small service (a gateway fronting one
  monolith adds a hop and an ops item for little gain), and never as a
  place for business logic — a gateway that transforms payloads per
  business rules is the ESB reborn.
- **Bill:** an extra hop; a tier-0 single point of failure (run it
  redundantly); config sprawl.

### Backend-for-frontend (BFF)

- **Problem:** one general-purpose API serves mobile, web, TV and
  partners badly — each needs different shapes, granularity, and
  versioning cadence; the shared gateway team becomes a bottleneck.
- **How:** one thin backend per client *type*, owned by that client's
  team, aggregating and shaping downstream calls for its screens.
- **Real systems:** Netflix (where it was articulated), SoundCloud (who
  named it), Spotify.
- **Do NOT use when:** one client type, or clients whose needs barely
  differ — BFFs then just multiply deployables and duplicate logic.
- **Bill:** more services to run; shared logic across BFFs needs a home.

### Service mesh (sidecar proxies)

- **Problem:** every service reimplementing retries, timeouts, mTLS,
  and traffic metrics — inconsistently, in N languages.
- **How:** a **sidecar** proxy (Envoy) deploys beside every service
  instance; all traffic passes through it; a control plane (Istio,
  Linkerd) configures them fleet-wide. Networking policy becomes
  platform, not application code.
- **Do NOT use when:** under ~10–15 services — the mesh's own
  operational complexity (upgrades, debugging through proxies, resource
  overhead) exceeds what it saves; a shared client library does the job.
- **Bill:** significant ops complexity; per-hop latency; another tier-0
  system to understand during incidents.

### Anti-corruption layer (ACL)

- **Problem:** integrating with a legacy or third-party system whose
  model is messy or foreign — and watching its concepts (`CUST_MST_TBL`,
  40-field SOAP orders) leak into and corrupt your domain model.
- **How:** a translation layer at the boundary owning both vocabularies:
  outward it speaks their protocol and model; inward it exposes only
  your domain's clean concepts. All their weirdness is quarantined in
  one place.
- **Use when:** any integration where the external model must not shape
  yours: legacy migration (paired with strangler fig), ERP/mainframe
  integration, acquisitions.
- **Do NOT use when:** the external model is fine and stable and yours
  adds nothing — a pass-through ACL is pure indirection tax.
- **Bill:** translation code and its upkeep; possible impedance
  mismatches to resolve (their "customer" ≠ your "customer").

### Strangler fig

- **Problem:** replacing a live legacy system. Big-bang rewrites take
  years, deliver no value until complete, freeze features, and have a
  dismal industry track record (the old system keeps changing while you
  rewrite it).
- **How:** put a routing **facade** in front of the legacy system; build
  one capability at a time in the new world; flip the facade's route for
  that capability; repeat until the legacy system serves nothing and is
  turned off. Named for the fig vine that grows around a tree until the
  tree inside dies. Every step is small, shippable and reversible; the
  facade gives you per-capability rollback.
- **Real systems:** the standard monolith→services migration everywhere;
  documented cases at Shopify (extractions), many banks' core-system
  replacements.
- **Do NOT use when:** the system is small enough to replace in one
  honest step, or you cannot intercept its entry points (no facade
  point = no strangler).
- **Bill:** a long period of running both worlds (double ops, data
  synchronization between old and new — often via CDC); the facade
  itself to build and eventually delete; discipline to finish (half-
  strangled systems are a common resting state).

### Messaging micro-patterns (recognition list)

- **Point-to-point queue vs publish-subscribe:** one consumer takes each
  message (work distribution) vs every subscriber gets each event
  (fan-out). Kafka consumer groups give both from one log.
- **Competing consumers:** N workers on one queue for throughput; forces
  the idempotency + ordering questions your
  [outbox](../48-transactional-outbox/) lab covered.
- **Request-reply over messaging:** correlation IDs + reply queues when
  you need an answer but must go async; adds latency and machinery —
  question whether you actually just need a sync call.
- **Dead letter queue (DLQ):** where messages go after N failed
  processing attempts, so one poison message doesn't block the queue;
  needs monitoring and a replay story or it's a data grave.
- **Claim check:** store a large payload in blob storage, send a
  reference through the broker (brokers hate 50MB messages).
- **Schema registry + versioning:** central, versioned event schemas
  with compatibility rules (backward/forward) enforced at publish time;
  the vaccine for F4 event spaghetti.

---

## Family 4 — Data patterns

### Database-per-service · Shared database

Covered as the rule and its violation: each service's data private
(access via API/events only) — because a shared schema is a hidden,
maximal, interface-less contract (README F3). Shared database is
acceptable *knowingly* in exactly one situation: as a temporary bridge
mid-migration, with an owner and an end date.

### Saga · Transactional outbox · CQRS · Event sourcing

Your dedicated labs are the depth: [saga](../47-saga/) (multi-service
transactions via compensations; orchestration vs choreography),
[transactional-outbox](../48-transactional-outbox/) (atomically save +
announce; kills the dual write), [cqrs](../51-cqrs/) (split write model from
query-shaped read models; consistency window as the price),
[event-sourcing](../50-event-sourcing/) (events as the source of truth;
fold to get state). One selection note connecting them: **outbox is
near-universal equipment** once you have events at all; saga only where a
genuine multi-service business transaction exists; CQRS only where read
and write shapes genuinely diverge; event sourcing only where history is
a first-class requirement (ledgers, audit) — it is the most expensive of
the four to live with.

### CDC (change data capture)

- **Problem:** getting reliable events out of a database whose
  application you can't (or won't) change — or feeding read models,
  caches, and search indexes without dual writes.
- **How:** tail the database's own transaction log (the WAL/binlog) and
  publish each committed change as an event (Debezium → Kafka is the
  canonical stack). The log is the truth, so nothing is missed and
  nothing is invented — it's the outbox guarantee, extracted from the
  outside.
- **Use when:** integrating legacy systems into an event backbone;
  keeping Elasticsearch/caches/warehouses in sync; strangler-fig data
  bridging.
- **Do NOT use when:** you control the application and the event should
  carry *intent* — the log says "row updated," not "customer moved
  house"; an application-level outbox event is semantically richer.
  CDC events also couple consumers to table shapes (mitigate with a
  transformation layer).
- **Bill:** operating the connector fleet; schema-change handling;
  consumers coupled to physical schema unless you translate.

### Caching patterns

- **Problem:** a hot read path hammering a database (or a slow/expensive
  computation) with the same questions.
- **The three write disciplines:**
  - **Cache-aside** (most common): app reads cache → on miss reads DB
    and fills cache; writes go to DB and *invalidate* the key. Simple,
    resilient to cache loss — and the invalidation is the famous hard
    part (races can repopulate stale data; missed deletes linger —
    README F6).
  - **Write-through:** every write goes to cache *and* store
    synchronously; reads always warm; writes pay double latency.
  - **Write-behind:** write to cache now, flush to store async; fastest
    writes, absorbs bursts — and now the cache holds the only copy for
    a window: a crash loses acknowledged writes. Use only where that
    loss is acceptable or the cache is durable/replicated.
- **The two diseases:** **staleness** (a cache is a replica; CAP-style
  reasoning applies at key granularity — bound it with TTLs and
  event-driven invalidation) and **stampede** (a hot key expires; 5,000
  requests rebuild it simultaneously and flatten the DB — fixes:
  per-key rebuild locks, probabilistic early refresh, serve-stale-
  while-revalidating).
- **Real systems:** Redis/Memcached everywhere; CDN edge caching is
  this family at HTTP scale.
- **Do NOT cache when:** reads aren't repeated (hit rate too low to pay
  for the staleness risk), correctness demands read-your-write freshness
  you can't engineer, or the data is cheap to fetch anyway. "We'll add
  caching" as the reflex answer to slowness — before profiling — is a
  classic anti-pattern; the index you didn't add is often the real fix.

### Replication & read replicas

- **How:** one writer (primary), N read-serving copies (replicas) fed
  by the primary's log, async by default.
- **Use when:** read-heavy workloads; read scaling without sharding's
  surgery; geographic read locality; failover standby.
- **The catch you already know:** async replicas lag ⇒ read-your-write
  anomalies ([cqrs](../51-cqrs/)'s consistency window, at the storage
  layer; [cap-theorem](../24-cap-theorem/) when partitions hit). Route
  freshness-critical reads to the primary; monitor lag as an SLO.
- **Do NOT use for:** write scaling — every write still lands on one
  primary. That's sharding's job.

### Sharding (data partitioning)

- **Problem:** one primary can no longer absorb the write volume or hold
  the data.
- **How:** split rows across independent nodes by a **shard key**
  (customer ID, tenant, geography); each shard is a full
  primary+replicas unit. Route by hashing or range on the key.
- **The costs, in order of pain:** cross-shard queries and joins
  (scatter-gather, slow), cross-shard transactions (essentially gone —
  hello [saga](../47-saga/)/[2pc](../45-two-phase-commit/) tradeoffs at the
  storage layer), **hot shards** (a celebrity customer melts their
  shard — key choice is destiny), and **resharding** (moving data while
  live; consistent hashing exists to soften exactly this).
- **Real systems:** every planet-scale store — Instagram (sharded
  Postgres), Slack (sharded MySQL → Vitess), YouTube (Vitess's
  birthplace), DynamoDB/Cassandra (sharding as a managed service).
- **Do NOT use when:** a bigger box, better indexes, caching, or read
  replicas still work — sharding is the *last* resort on the scaling
  ladder because its complexity is permanent and its key choice nearly
  irreversible.

### Materialized views

- Precomputed, stored query results, refreshed on schedule or via
  events/CDC — the database-native cousin of a [cqrs](../51-cqrs/) read
  model: same idea (query-shaped copies, staleness as the price),
  smaller machinery. Reach for it before reaching for full CQRS.

---

## Family 5 — Resilience patterns

*(The cascading-failure story motivating this whole family, with the
thread-pool walkthrough, is README stage 10.6 — read it first; these are
the reference entries.)*

### Timeout

- Every remote call gets a deadline, sized from the caller's latency
  budget (what can the end user tolerate, minus the other hops), not
  from the callee's average. No timeout = an unbounded resource hold =
  the enabling condition for every cascade. **The zeroth resilience
  pattern: nothing else works without it.**
- Do NOT set timeouts longer than the caller's own callers' timeouts
  (an inversion where your work is discarded while you still compute
  it), and don't retry on timeout without asking whether the operation
  is idempotent — the call may have *succeeded* slowly.

### Retry (exponential backoff + jitter)

- Re-attempt **transient** failures (network blips, 503s) — never
  permanent ones (400s) — on **idempotent** operations only (or with
  idempotency keys), spacing attempts exponentially (1s, 2s, 4s) with
  randomness (jitter) so a thousand failing callers don't return as
  synchronized waves.
- **Retry budgets** cap total extra load (e.g. retries ≤ 10% of
  requests); pick ONE layer of the stack to own retries — layered
  retries multiply (3×3×3 = 27 — README F5).
- Do NOT retry when: non-idempotent without keys (double-charge), the
  failure is deterministic, or the SLA is already blown (return the
  error; let the caller decide).

### Circuit breaker

- The state machine (CLOSED → OPEN on failure-rate threshold →
  HALF_OPEN probes after cool-off → CLOSED or back to OPEN) that stops
  calling a drowning dependency: callers fail in microseconds instead
  of holding threads for timeout-lengths, and the victim gets zero load
  to recover under. The shape in code:

  ```java
  class CircuitBreaker {
      enum State { CLOSED, OPEN, HALF_OPEN }
      private State state = State.CLOSED;
      private final RollingWindow window = new RollingWindow(20);
      private Instant openedAt;

      <T> T call(Supplier<T> remote, Supplier<T> fallback) {
          if (state == State.OPEN) {
              if (Instant.now().isAfter(openedAt.plusSeconds(30)))
                  state = State.HALF_OPEN;      // cool-off over: allow probes
              else
                  return fallback.get();        // fail fast: no thread held,
          }                                     // no load on the victim
          try {
              T result = remote.get();          // (this call still has a timeout)
              window.recordSuccess();
              if (state == State.HALF_OPEN) state = State.CLOSED; // recovered
              return result;
          } catch (TransientException e) {
              window.recordFailure();
              if (state == State.HALF_OPEN || window.failureRate() > 0.5) {
                  state = State.OPEN;           // trip: sacrifice the circuit
                  openedAt = Instant.now();     // to save the house
              }
              return fallback.get();
          }
      }
  }
  ```

  Load-bearing lines: the OPEN branch *returns without calling* — that
  is the entire pattern; and HALF_OPEN exists because you need evidence
  of recovery, not a timer's optimism.
- Real systems: Netflix Hystrix (retired) → resilience4j, Polly (.NET),
  Envoy outlier detection (breakers as mesh policy).
- Do NOT use for: in-process calls, or as a substitute for fixing a
  chronically failing dependency; and an unmonitored breaker is a
  silent feature-killer — every OPEN transition must alert.

### Bulkhead

- Partition resources per dependency (separate bounded thread/connection
  pools, or separate instances per traffic class) so one drowning
  dependency exhausts only its own compartment. Sizing is the design
  work: each pool ≈ expected concurrency (Little's law: rate × latency)
  + headroom.
- Do NOT bother when there's a single dependency, and beware
  over-partitioning: twenty 10-thread pools waste capacity that one
  well-shed 200-thread pool would use.

### Rate limiting & load shedding

- **Rate limiting** (protect from *others*): cap requests per
  client/key (token bucket, sliding window) at the edge; return 429
  with Retry-After. Standard equipment on any public API.
- **Load shedding** (protect from *everyone*): when saturated, reject
  excess *early and cheaply* (at admission, before expensive work),
  preferably by priority (shed analytics before checkout). Partial
  service for most beats degraded service for all — the counterintuitive
  discipline is that dropping work on purpose is how you stay up.

### Backpressure

- Bounded queues + consumers pulling at their own pace, so overload
  propagates *backwards* as slowdown to the source instead of
  accumulating in unbounded buffers until something falls over.
  Reactive Streams, Kafka's pull model, TCP flow control are all this
  pattern. The anti-pattern it kills: the unbounded in-memory queue
  ("it'll catch up") that converts overload into an OOM crash with
  data loss.

### Fallback / graceful degradation

- Pre-decide the reduced experience per dependency: recommendations
  down → bestsellers; ETA service down → "being assigned"; payment
  provider down → queue the order and charge on retry (only if the
  business accepts that!). Fallbacks are *product decisions* wearing
  engineering clothes — decide them with product owners, in daylight,
  not in an incident channel at 2 a.m.

### Health checks, hedging, chaos (recognition entries)

- **Health checks:** liveness ("restart me") vs readiness ("don't
  route to me yet") — conflating them causes restart storms during
  dependency outages.
- **Hedged requests:** for tail latency, send a duplicate request to a
  second replica after the p95 mark, take the first answer; costs extra
  load for smoother tails (Google's tail-at-scale trick; needs
  idempotent reads).
- **Chaos engineering:** inject real failures (kill instances, add
  latency, partition networks) in production, on purpose, continuously
  — resilience patterns you haven't tested against real failure are
  hypotheses, not equipment (Netflix Chaos Monkey → Gremlin, AWS FIS).

---

## Family 6 — Deployment & operational patterns

### Blue-green deployment

- Two identical environments; deploy to idle, smoke-test, flip the
  router, keep the old one warm for instant rollback. Bill: 2×
  infrastructure during the window; and the *database* doesn't have a
  color — both environments share it during the flip, so every schema
  change must be backward-compatible: **expand/contract** (release 1:
  add new column, write both; release 2: migrate readers; release 3:
  drop old) — this discipline, not the router flip, is the hard part.

### Canary release

- Route 1% → 5% → 25% → 100% of real traffic to the new version,
  gating each step on error/latency metrics; roll back on regression.
  Blast-radius control for deploys; automated in Argo Rollouts,
  Flagger, Spinnaker. Needs: metrics good enough to judge a canary,
  and both versions interoperating (same expand/contract discipline).
  Sticky-session and cache effects can hide canary damage — route by
  user cohort, not per-request, when state matters.

### Feature flags

- Runtime switches decoupling *deploy* from *release*: ship dark, turn
  on per user/cohort/percentage, kill instantly by flipping off. Also
  the mechanism for A/B tests and trunk-based development. The bill is
  **flag debt** — dead flags multiplying into untestable `if` mazes
  (2^N combinations); the discipline: every flag gets an owner and an
  expiry, and removing a finished flag is part of the feature's
  definition of done.

### Rolling deployment · stateless services · autoscaling (recognition)

- **Rolling:** replace instances gradually (the Kubernetes default);
  N and N+1 always coexist briefly ⇒ interoperability is mandatory.
- **Stateless services:** keep no per-user state in the process
  (sessions → Redis/tokens); the property that makes horizontal
  scaling, rolling deploys and instance death boring. The core of the
  **12-factor app** checklist (config in env, logs as streams,
  disposable processes) — the operational best-practice baseline for
  cloud services.
- **Autoscaling:** add/remove instances on load signals; only as good
  as the metric chosen (CPU is a poor proxy for queue-depth-shaped
  load) and useless for the stateful parts (the database doesn't
  autoscale — that's Family 4's problem).

---

## Family 7 — The anti-pattern gallery

Six from README stage 11 (distributed monolith, premature microservices,
shared database, event spaghetti, retry storm, cache inconsistency) plus
the rest of the rogues:

- **Big ball of mud** — the null architecture: no boundaries, everything
  touches everything. Not chosen; *accreted* — the default outcome of
  never deciding. Every pattern in this catalog is, at root, a defense
  against mud.
- **God service / god module** — one service that half the system's
  logic migrated into ("core", "common", "platform" are its usual
  names); a monolith hiding inside a microservices diagram. Cause:
  boundaries by convenience; anything unclear got tossed in.
- **Nanoservices** — services so small (one function each, but not
  serverless-managed) that interaction cost dwarfs their content;
  hundreds of deployables for a domain that had a dozen capabilities.
  Overcorrection from monolith fear; "one team per service" flips into
  "one service per file."
- **Golden hammer / resume-driven design** — pattern choice by
  familiarity or fashion instead of fit ("we use Kafka for everything";
  "microservices because Netflix"). The tell: the tool is named before
  the -ilities are.
- **Dual write** — writing two systems (DB + broker, DB + cache)
  without a transaction spanning them; the crash between them invents
  or loses facts. Your [outbox](../48-transactional-outbox/) lab exists
  because of this one.
- **Anemic domain model** — entities as bare data bags, all rules in
  procedural service classes; the structural erosion that layered
  architectures drift toward (rules end up wherever was convenient).
  Matters at architecture altitude because it makes *boundary-finding
  impossible* — behavior's true home is exactly what you need to know
  to draw service lines.
- **Premature optimization / speculative generality** — complexity
  bought for imagined futures (the YAGNI violations): the abstraction
  layer over the database "in case we switch," the plugin system with
  one plugin, the multi-region design for a product with no users.
  Each carries real, daily carrying cost against a payoff that usually
  never arrives — and when it does arrive, it's shaped differently
  than the speculation guessed.
- **Lava flow / half-strangled system** — a migration abandoned
  mid-flight: two worlds, both load-bearing, forever. The strangler
  fig's failure mode; the vaccine is finishing (or explicitly deciding
  not to start).

---

## Family 8 — Principles quick reference

| Principle | One-line rule | Cardinal violation |
|---|---|---|
| Low coupling, high cohesion | Few connections between parts; strong belonging within | Everything touches everything |
| Information hiding (Parnas) | Hide each likely-to-change decision inside one module | Vendor/schema details in every caller |
| Single responsibility | One reason to change per module | The order-saver that also sends SMS |
| Open/closed | Extend by adding, not editing | Every new case edits the same switch |
| Liskov substitution | Implementations honor the interface's full contract | The "fast" adapter that skips validation |
| Interface segregation | Client-shaped small interfaces | The 40-method service interface |
| Dependency inversion | Policy owns interfaces; details plug in | Domain importing the Stripe SDK |
| DRY (properly read) | Never duplicate *knowledge*; duplicating code is sometimes fine | Two copies of the tax rule — or one forced abstraction over coincidence |
| KISS | Simplest design meeting *current* -ilities | Hexagonal ceremony on a 10-endpoint CRUD app |
| YAGNI | No complexity without a named current requirement | 12 services for a 6-person team |
| Separation of concerns | One part per kind of work | SQL in the HTTP handler |
| Conway's law | Architecture copies the org chart — so design the org | 4 teams, 40 services |
| First Law (Richards/Ford) | Everything is a tradeoff | "This pattern has no downside" |
| Second Law | The *why* outlives the *how* | An architecture nobody can explain |

---

## 9 — Selection cheat sheet

Situation → candidates (always the simplest that fits; every row assumes
"and you verified the situation is real, not imagined"):

| Your actual situation | Reach for | Resist |
|---|---|---|
| New product, small team, unclear domain | Modular-ish monolith, layered or slices inside, one boring DB | Microservices, event backbone, CQRS |
| Domain logic is the crown jewels | Hexagonal/clean core, domain tests on fakes | Letting the ORM shape the domain |
| Many independent request/response features | Vertical slices | One-size-forced layering |
| Team passed ~2–3 autonomous teams, monolith release train hurts | Modular monolith with enforced seams → extract the 1–2 hottest modules | Big-bang decomposition |
| Genuinely independent deploy/scale/fault domains, platform exists | Microservices by business capability | Entity-named services |
| One action fans out to many reactions | Event-driven (broker), outbox on producers, idempotent consumers | Synchronous call chains |
| Multi-service business transaction | Saga (orchestrated for core flows) | 2PC across services; giant sync chains |
| Read shape ≠ write shape; read scale ≫ write scale | Materialized views → CQRS read models | Event sourcing "while we're at it" |
| History/audit is a first-class requirement | Event sourcing (that aggregate only) | System-wide event sourcing |
| Legacy replacement | Strangler fig + ACL + CDC bridge | Big-bang rewrite |
| Extreme read load, repeated queries | Cache-aside + TTL + stampede protection | Caching before profiling |
| Write volume beyond one primary | Sharding (choose the key like it's permanent) | Sharding before replicas/caching/indexes are exhausted |
| Any remote call, anywhere | Timeout + budgeted jittered retry + breaker + fallback | Naked calls; layered retries |
| Spiky glue workloads, tiny ops budget | Serverless + managed queues | Serverless for latency-critical sustained cores |
| Deploys are scary | Canary + feature flags + expand/contract schema discipline | Friday big-bang deploys |
| "What happens when X occurs?" takes archaeology | Orchestrators for core flows, schema registry, tracing | More choreography |
