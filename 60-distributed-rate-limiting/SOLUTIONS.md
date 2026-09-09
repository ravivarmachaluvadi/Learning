# Solutions

Rungs 1–4 have right answers. Rungs 5–8 are design work — what follows is a
worked position you should be able to argue with, not a marking scheme.

---

## Rung 1 — Explain

**1.1** Because the limiter's memory lives inside one process. Each server
counts only the requests it personally handled, and none of them can see the
others, so twenty servers enforcing "100 per minute" each let 100 through and
the customer gets 2,000. Nobody wrote a bug — the rule simply stopped being
enforceable the moment there was more than one enforcer.

**1.2** *Over-admission* — more gets through than you promised. Victim: you
(capacity, cost, the thing the limit was protecting). Shows up as a graph.
*Under-admission* — you reject requests while allowance was actually
available. Victim: your customer. Shows up as a **support ticket**, because
from their side you are simply lying about the limit you sold them, and every
dashboard you own says the limiter is working perfectly.

**1.3** "…how out-of-date each enforcement point's view of the shared truth is
allowed to be." (Anything equivalent to *the staleness dial* is right.)

**1.4** Sharing the *data* is not the same as sharing the *decision*. If each
node reads the counter, decides locally, and writes it back, then every node
that reads during the gap between another node's read and write sees the same
stale value and spends the same tokens. Correctness requires the decision to
happen in one indivisible step at the store — not merely for the number to be
stored there.

---

## Rung 2 — Identify

**2.1** Data plane = the solid grey lines (clients → router → gateways →
backend). Coordination plane = the dashed violet lines from each gateway down
to the counter store. The **control plane** (rules: who gets what limit) is not
drawn, because the simulator holds the limit fixed at 60/s — there is nothing
to distribute. In production it is the third set of arrows and it fails
independently of the other two (README F8).

**2.2** v1: each gateway stores a complete token bucket for the full limit —
its bar is its own private allowance, and there are N of them. v7: each gateway
stores *what it believes the whole world has spent this second* — the count it
last learned, plus its own spending since. It resets every second because v7 is
counting inside a fixed one-second window; at the window boundary everyone's
count goes back to zero.

**2.3** One bar is one sub-key: an independent bucket carrying a fraction of
the promise. With 4 sub-keys and a 60/s promise, each carries **15/s**. A
request picks one at random, so a request can be rejected by an exhausted
sub-key while another sub-key still has room.

**2.4** v6 shows roughly a third of v4's store operations (measured: ~62/s
against ~180/s at 180 rps). The requests producing no store operation are being
decided **entirely locally**, out of the block of tokens the gateway already
leased. A gateway only talks to the store when its lease runs dry — plus a short
"this key was empty a moment ago" pause so a saturated key does not cost a
round trip per rejection.

---

## Rung 3 — Predict

**3.1** Admitted goes to roughly **8 × 60 = 480/s**, capped by whatever traffic
you are actually offering (at 180 rps you will simply see all 180 admitted —
the limit has effectively stopped existing). Store operations stay at **zero**,
which is the point: this design is free precisely because it is wrong.

**3.2** From 60/s to about **15/s**. Sticky sends every request for this key to
one gateway, and that gateway is only permitted limit ÷ 4. The other three hold
45/s of allowance that no request will ever reach. Note the asymmetry with 3.1:
the same routing change that *fixes* v1 completely *destroys* v2.

**3.3** (a) Admitted drops sharply toward the promise — from around 175/s at
1000 ms to roughly 70/s at 100 ms. (b) Store ops rise by the same factor,
roughly 4/s to 40/s. Ten times the coordination buys you about ten times less
error. That is the entire trade, and it is linear.

**3.4** Admitted climbs steeply. The mechanism: the write lands `2 × hop`
milliseconds after the read, and every request arriving inside that window
reads the same stale counter. The number of requests in the window is
`rps × 2 × hop`, so at 180 rps and a 1 ms hop about 0.4 requests overlap
(almost no leak), while at 12 ms about 4.3 overlap and each of them spends
tokens the others already spent. **The bug's severity is the product of your
latency and your traffic** — which is exactly why it is invisible in staging.

**3.5** *Degrade*: about **60/s** under round robin, because limit ÷ N happens
to add up when traffic is even — switch the router to skewed and it collapses.
*Fail open*: **all 180/s**; the limiter has stopped existing. *Fail closed*:
**0/s**; a perfectly-enforced total outage.

---

## Rung 4 — Modify

**4.1** **v1 (no coordination) + sticky routing.** Every request for the key
reaches the same gateway, so that gateway's local state is complete and exact.
This is key-owner routing. Its three costs: (i) you must own the router and can
only hash on **one** dimension, so it cannot serve three simultaneous limits;
(ii) the owning gateway becomes a hot spot for your biggest customer; (iii)
scaling or losing a node reshuffles ownership, and during the reshuffle two
nodes briefly both believe they own the key — an over-admission window on
exactly the event (a scale-up under load) when you least want one. Node death
also loses that key's counter outright.

**4.2** You will find something in the 150–300 ms region holds within ~15%.
Then doubling the traffic pushes it back out of tolerance, because the error is
`N × per-node rate × T` — it is **proportional to traffic**, and your load test
fixed the traffic. The lesson: a sync interval tuned at one traffic level is
not a constant, it is a point measurement. Size it from your *peak* rate for
that key, or make it adaptive.

**4.3** You trace a curve of diminishing returns. Store ops fall roughly as
`1/batch` — big gains from 2 to 10, small ones from 20 to 30 — while
under-admission grows roughly linearly with `N × batch` as stranded allowance.
It stops helping once the batch is large enough that a gateway routinely fails
to spend it before the lease expires; past that you are paying accuracy for
savings you no longer receive. Rule of thumb from the README: batch must stay
small relative to the limit, which is why leases are for large limits only.

**4.4** Because sub-key splitting costs accuracy through **variance**, and
variance only shows when the sub-keys are near their capacity rather than far
past it. At 80 rps against a 60/s promise split 8 ways, each sub-key gets
about 10 requests/s against a 7.5/s allowance — close enough that random
arrival lumpiness leaves one sub-key rejecting while another has unspent
tokens, and its buffer (burst ÷ 8) is too small to smooth it out. At very high
traffic every sub-key is permanently saturated, so each simply admits its full
7.5/s and the total is exact.

The implication is uncomfortable and worth stating in a design review:
**splitting a key degrades accuracy precisely in the regime where the customer
is near their limit** — which is the only regime they notice. It is safe when
the key is genuinely, continuously saturated, and that is exactly what "hot
key" means. Split hot keys; do not split ordinary ones.

---

## Rung 5 — Specify

### 5.1 The atomic store operation

```
CHECK_AND_CONSUME(keys[], costs[], now, limits[], bursts[]) -> Decision

reads   : for each key, {tokens, lastRefillAt}
writes  : for each key, {tokens, lastRefillAt}   — only if ALL keys pass
returns : allowed        : bool
          blockedBy      : key name or null
          retryAfterMicros : int
          remaining[]    : per key

GUARANTEE: the whole read → refill → decide → write sequence is indivisible.
No other caller may observe or modify any of these keys in between.
```

Three points that carry the design:

- **All keys in one call, consume only if all pass.** Checking the three
  dimensions in three calls triples latency and store load, and leaves you
  consuming quota from limits 1 and 2 for a request that limit 3 rejected.
- **`now` is supplied by the store, not the caller.** That is what makes the
  design immune to clock skew (F5) — one clock is consulted, so all gateways
  implicitly agree.
- **`retryAfterMicros` and `remaining` are the two outputs that exist purely
  for the HTTP response**, and the gateway cannot compute them itself: both
  depend on the post-decision state of a counter that the gateway does not hold
  and that other gateways are concurrently changing. Computing them
  gateway-side is how you get a dishonest `Retry-After` and the retry storm in
  F7.

### 5.2 Rule schema

```yaml
rule:
  id: rl.merchant.payments.standard
  version: 47                      # snapshots are versioned and applied atomically
  match:
    plan: standard
    endpointGroup: payments-write
  key: [merchant_id, endpoint_group]   # the identity this allowance belongs to
  limit: 100
  window: 1m
  burst: 20                        # tuned separately from rate, and documented to customers
  costs:                           # a request is not one unit of work
    default: 1
    POST /payments/bulk: 20
    GET  /payments/{id}: 1
  onStoreUnavailable: degrade_local   # degrade_local | fail_open | fail_closed
  mode: enforce                    # enforce | shadow
  response:
    exposeLimitName: true          # false for security limits (see 7.2)
```

`onStoreUnavailable` sits **inside the rule** on purpose. F1 has no globally
correct answer: a limit guarding a fragile downstream should fail closed, one
that exists for billing fairness should fail open. Making it a per-rule field
turns it into a reviewed product decision instead of a global default nobody
remembers choosing.

### 5.3 Hot/cold promotion policy

- **Signal:** requests per second for a given key, measured **per gateway** and
  summed at the store during the periodic sync (you already have that channel).
- **Grain:** the full rate-limit key, not the merchant — `merchant_42` may be
  cold while `endpoint:POST /payments` is scorching.
- **Window:** a 10-second sliding rate, so a single burst does not promote.
- **Thresholds:** promote above 100 rps; demote below 25 rps. The gap is
  deliberate.
- **At the moment of promotion:** the key's authoritative counter must be
  *drained into* the new scheme, not duplicated. Concretely: the store marks
  the key `hot` with an effective-from timestamp; gateways that see the flag
  stop per-request calls and begin syncing; the store's counter continues to be
  the aggregation point, so nothing is double-counted. The dangerous window is
  gateways learning the flag at different times — during it, some are per-request
  and some are syncing, which is *safe* (both are conservative) but briefly
  under-admits.
- **How a naive version oscillates:** with a single threshold, promoting a key
  removes per-request store traffic, which is the very signal you were
  measuring — so the key looks cold again, gets demoted, becomes hot, and
  flaps. The gap between promote and demote thresholds (hysteresis) plus
  measuring rate at the *gateway* rather than at the store are what prevent it.

---

## Rung 6 — Debug

**6.1 — "exact in staging, 3× in production."** There are exactly two
candidates, and they are distinguishable.

1. *Node count* — staging runs one instance, production runs several, and the
   design has no coordination (README v1). The over-admission factor would equal
   the instance count and would be **flat with traffic**.
2. *The read-modify-write race* (v3). Over-admission is
   `rps × 2 × store-hop` shaped, so it would **grow with traffic** and vanish
   overnight.

Tell them apart in one query: plot the over-admission factor against request
rate. Flat → it is your topology; rising → it is the race. Also check store
operations per request: two ops per request means a read and a separate write,
which is the race by construction. The fix for (1) is to coordinate at all; for
(2) it is to move the decision into the store as one atomic script — not a
bigger Redis.

**6.2 — Tuesday 14:00, latency 90 ms → 2.5 s, no Redis errors.** This is F2:
the store is not down, it is *slow*, and slow is more dangerous than down. A
weekly scheduled job (an analytics scan, a `KEYS` sweep, a background save,
bulk key expiry) is blocking Redis's single thread. Every gateway request now
blocks on the limiter, request threads pile up, and requests that would have
been *allowed* start failing.

The fix is four things, not one: an aggressive timeout on the limiter call
(5–10 ms, treated exactly like an outage); a circuit breaker so you stop adding
load to a struggling store; a bulkhead so the limiter's connection pool cannot
starve everything else; and alerting on **limiter latency and fallback rate**
rather than on store uptime — uptime was green the whole time.

**6.3 — 400,000 password attempts, never rejected.** The key is wrong, not the
counting. Limiting login by IP alone means an attacker with a botnet has as
many allowances as they have addresses; 400,000 attempts spread over 100,000
hosts is 4 each, comfortably under 5/minute. This is README 10.5, rule 2: login
needs **two keys at once** — per source IP *and* per target username. The IP key
stops one host trying many accounts; the username key stops many hosts trying
one account. Either alone is trivially bypassed, and this is the single most
common rate-limiting design bug in production.

**6.4 — total volume went UP 40% after shipping the limiter.** F7. Two
mechanisms, probably both: a fixed 30-second `Retry-After` given to every
rejected client turns scattered load into a synchronised spike every 30 seconds
(your own header is the synchroniser); and if the retry time is even slightly
early, obedient clients retry, get rejected again, and poll you as fast as you
permit. Check also that you are returning `429` and not `503` — `503` tells
well-behaved clients and load balancers that *you* are broken, which triggers
aggressive retries and health-check failures. Fix: compute the retry time from
real limiter state, round up, add server-side jitter, and document exponential
backoff.

**6.5 — half the nodes on the new rule for ninety minutes.** F8, control plane.
The rule set is being distributed without versioning or atomic application, so
nodes converge at their own pace and there is no way to see which node is on
which version. The customer's experience depends on which gateway they land
on — which is why it looked random. Fix: versioned snapshots applied
atomically, a per-node "current rule version" metric so divergence is visible,
gradual rollout watched against rejection rate, single-operation rollback, and
shadow mode so a new rule's effect is measured before it is enforced.

---

## Rung 7 — Handle failure

**7.1 — a hot-tier gateway dies holding 40 unpublished spends.** The other
gateways believe the global count for that key is 40 lower than it really is,
so they will admit roughly 40 extra requests before the window rolls. The error
is **over-admission**, and it is silent.

Two mechanisms that bound it:

- **Shorten the sync interval.** Damage is linear in `T`, so halving the
  interval halves the worst case. Cost: proportionally more store load, paid
  continuously to insure against a rare event.
- **Publish before spending** — reserve the allowance at the store, then spend
  it locally. A death now strands reserved-but-unspent quota instead of hiding
  spent-but-unpublished quota, converting over-admission into under-admission.
  Cost: this is v6, so you inherit v6's latency on the reserve path and its
  stranded-quota problem, and you have chosen which error you prefer rather
  than eliminating error.

A third, cheaper partial measure: cap what any one gateway may spend between
syncs at `limit ÷ N`, which bounds the worst case regardless of `T`. It
reintroduces v2's under-admission whenever traffic is uneven.

**7.2 — telling the customer which limit they hit.** The store operation must
return `blockedBy` (which key failed) plus that key's own reset time, since
only the store knows both. The gateway then applies a disclosure policy, which
comes from the rule's `exposeLimitName` field:

- **Limits the customer owns** (their merchant limit, their plan's endpoint
  limit): name it and return its `Retry-After`. This is actionable and reduces
  support load.
- **Security limits** (per-IP, per-username login limits, global endpoint
  caps): return a generic `429` with no limit name, no remaining count, and a
  jittered retry hint. Naming them lets an attacker calibrate — they learn the
  exact threshold, the exact window, and whether they have found the right
  dimension to spread across.
- **Never** return `RateLimit-Remaining` for a limit you would not name; the
  header leaks the same information more precisely.

**7.3 — unauthenticated endpoints.** You have no API key, so:

- **Key on the source IP** — stops one host enumerating many accounts. Useless
  against a botnet, and it punishes large NATs, where thousands of legitimate
  users share one address.
- **Key on the target identifier** (the username or email being logged into /
  reset) — stops many hosts attacking one account. Useless against *spraying*,
  where the attacker tries one common password against a million different
  accounts, touching each username once.
- **Therefore also key on the endpoint globally** — a cap on total failed
  logins per minute across the whole system. This is the only key that catches
  spraying, and it is a hot key by construction, so it needs sub-key splitting.

An attacker with 50,000 IP addresses defeats the first key completely and is
unaffected by the second unless they concentrate on one account. What actually
stops them is the third key plus escalation that is not rate limiting at all:
proof-of-work or CAPTCHA once the global failure rate is anomalous, account
lockout with out-of-band recovery, and credential-stuffing detection on
password reuse. **State the limit of the tool honestly**: rate limiting raises
the cost of abuse, it does not prevent a determined distributed attacker, and
a design review that claims otherwise is wrong.

---

## Rung 8 — Design

### 8.1 Three-region payments limiter

**Shape of the answer: three limit classes, three different coordination
designs. Anyone who proposes one mechanism for all three has not read the
requirements.**

**Capacity.** 80,000 rps global peak, split roughly US 45% / EU 35% / IN 20% →
36k / 28k / 16k rps. Three dimensions per request → ~108k / 84k / 48k counter
checks/s regionally. Three million merchants × 3 dimensions × ~120 B ≈ 1.1 GB
if fully resident — again, **memory is not the constraint**; the 108k checks/s
against a ~50k-per-shard ceiling is, so US needs ≥3 shards for throughput and
would run ~6.

**Class 1 — free tier, 100/min, 3M merchants.** Cold keys: a handful of
requests each. Per-request atomic at the regional store (v4/v5). It is exact,
and exactness is nearly free here because the traffic per key is tiny.
For the global-versus-regional problem, use **home-region pinning**: a merchant
is assigned a home region and their traffic is routed there, so their counter
is single-region and genuinely exact. Merchants who genuinely send from
everywhere are the exception and can be reconciled as Class 2.
Fallback: `fail_open`. Accuracy here matters far less than availability, and
these are the customers you least want to break.

**Class 2 — enterprise, contractual, globally accurate to 1%.** Do **not**
attempt to count globally in real time; cross-region round trips are 70–150 ms
and the latency budget is 5 ms. Instead **split the quota into regional shares
and reallocate the shares asynchronously**: a global controller collects each
region's consumption every 1–2 s and re-divides the limit in proportion to
observed demand, holding ~10% back as an unallocated reserve for sudden shifts.

The key property: **shares always sum to the limit, so the ceiling can never be
exceeded** — the error is one-sided (under-admission) and appears only when
demand shifts between regions faster than the controller reallocates. Within a
region, each share is enforced by atomic per-request checks, so it is exact.
Fallback: `degrade_local` to the last-known share, which stays safe because
shares sum correctly even when the controller is unreachable.

**Class 3 — the 25,000 rps global cap on `POST /payments`.** This is an
external contract with a penalty, so treat the ceiling as inviolable and buy
safety with under-admission. Fixed regional shares (say 11,250 / 8,750 / 5,000)
reallocated by the same controller as Class 2, but with a **safety margin** —
allocate 23,000 of the 25,000 and keep 2,000 permanently unissued. Inside each
region this is one hot key by construction, so split it into sub-keys per
stage 10.7 / F3.

One addition the other classes do not need: **shape rather than police the
outbound leg**. You cannot ask a card network to retry, and it will suspend you
for bursting, so the calls leaving your system toward it go through a
leaky-bucket queue that emits at a steady rate. Police the front door, shape
the back door.

**What you cannot fully meet, and what to negotiate.** The enterprise 1% global
accuracy commitment is not achievable as an instantaneous guarantee: a customer
that abruptly moves all traffic from EU to US will be under-admitted for the
duration of one reallocation cycle, and a controller partition freezes the
shares entirely. Negotiate the contract into a form you can actually honour:
define the limit as **measured over a rolling 60-second window** rather than
instantaneously, state the regional-share model in the contract, and commit to
never *exceeding* the limit (which you genuinely can) rather than always
reaching it.

**On-call dashboard, in the order you would read it during an incident:**
rejection rate split by limit class; limiter decision latency p99/p999;
fallback rate (fraction of decisions made without the store — should be zero);
and share-reallocation lag together with top-N keys by rate.

### 8.2 Moving to 50 cells

**Survives unchanged:** the algorithm choice (token bucket), key design and the
cost-weighting scheme, the response contract, the control plane and its shadow
mode, and the per-rule fallback policy. Inside a cell you still run several
gateway nodes against one store, so the whole v4/v5 discussion applies at cell
scale — with a much smaller N, which makes degrade-to-local far more accurate.

**Becomes unnecessary:** cross-region reconciliation for merchant-scoped limits.
If a merchant maps to exactly one cell, their counter is exact by construction
— this is key-owner routing promoted to an architectural principle, and it is
the single biggest benefit of the move.

**The new problem the cell router creates:** it is now a stateful, critical
component in front of everything, and merchant→cell assignment is authoritative
state that must be consistent across every router instance. Two consequences.
First, **rebalancing a merchant between cells** (for capacity, or when a cell is
drained) creates a window in which two cells hold counter state for the same
merchant and both enforce the full limit — a 2× over-admission window on
exactly the operation you perform under load. You need an explicit handover:
freeze in the old cell, drain, then activate in the new one, accepting brief
under-admission instead of over-admission. Second, **endpoint-global limits get
worse, not better**: the 25,000 rps cap must now be divided 50 ways instead of
3, so each cell holds 500 rps and the under-admission from static division
becomes severe. Those limits need their own aggregation path that does not
follow the cell boundary.

### 8.3 The argument for not building any of this

*Scale.* We serve 400 rps at peak across three application instances. Our
limits exist to stop a single misbehaving integration from saturating our
database — they are safety valves, not products. Nobody has bought a
contractual rate limit, and no number in our configuration was derived from
anything more rigorous than "this feels like more than any sane client would
send".

*The error is smaller than the uncertainty.* With three instances, an
uncoordinated limiter is wrong by 3×. But the limit itself is a guess with at
least that much slack in it, so a 3× error on a number we invented is not a
correctness problem — it is a rounding difference. Setting each instance's
limit to a third of the intended figure and accepting the routing skew costs us
nothing we can measure, and our load balancer's distribution is even enough that
the skew stays small at this instance count.

*What we would be buying.* A shared counter store is a new hot dependency in
the path of 100% of our traffic, with sub-millisecond latency requirements, its
own failure modes, its own on-call burden, and a well-documented tendency to
take down the service it was installed to protect when it gets slow rather than
down. We would be adding a component whose worst day is worse than the problem
it solves. Use the gateway's built-in per-node limiting, write the assumption
down in an ADR, and revisit it the day we sell a customer a number — because
that is the day the limit stops being a guess and starts being a promise.
