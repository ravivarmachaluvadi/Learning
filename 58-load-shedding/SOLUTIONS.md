# Load Shedding — Solutions

Rungs 1–5 have checkable answers. Rungs 6–8 are design work; what follows is a
worked line of reasoning, not the only acceptable one.

Every number quoted here comes from the lab's simulator and can be reproduced in
`visualization.html`.

---

## Rung 1 — Explain

**1.1** Because the resource being fought over is *time*, and time spent on a
request that nobody is waiting for any more is gone. When everything is let in,
every request gets a share of a queue too long for any of them to finish inside
their deadline — so the work is done and thrown away. Turning most of them away
concentrates the same amount of work on a small enough group that the group
actually finishes in time. Measured: 0.1% served without it, 33.3% with it,
identical hardware and identical offered load.

**1.2** *"A rate limiter enforces a promise about who you are; a shedder
enforces a fact about what I can currently do."* The rate limiter's answer does
not change when the service is on fire, and the shedder's answer does not change
when the caller is important.

A rate limiter running correctly and not helping: ten thousand mobile clients,
each with a limit of 60 requests/minute, each sending 4. Every client is well
inside its limit; the aggregate is 40,000/minute and it buries you. Overload is
usually an aggregate phenomenon and rate limits are per-identity.

**1.3** Throughput counts requests the server *finished*. Under overload the
server finishes them at full speed — it just finishes them long after the client
hung up, so they count as work done and nothing delivered. The number to watch is
**goodput**: completions that arrived before their client gave up. Equivalently,
successes divided by requests *offered*, which requires counting rejections and
timeouts, not just responses.

**1.4** Because it assumes the loss is caused by shedding. At 150/s against a
50/s capacity, 100 requests/second are lost no matter what anybody does — the
only question is whether they are lost *cheaply and immediately* or *after
consuming a worker, a connection and a second of the user's life*. Shedding does
not add loss; it directs it.

---

## Rung 2 — Identify

**2.1**

| | lever | protects |
|---|---|---|
| a. `queue.size() >= 20` | admission control | memory, and the client's time |
| b. newest-first | queue order | goodput and latency |
| c. discard the dead request at the head | drop rule | wasted work |
| d. 10 connections, no acquire timeout | **admission control by accident** — and a broken one | nothing; it is an unbounded queue with a bound on the wrong thing |
| e. `Retry-After: 2` | none of the three | it is *advice to the client*, and the only lever that acts outside your process |

(d) is the point of the question: every connection pool, thread pool and
semaphore in your stack is already an admission controller. The ones you did not
configure are the dangerous ones.

**2.2**

| | signal | set point | actuator |
|---|---|---|---|
| queue cap 20 | number of requests waiting | 20 | admit / 503 |
| CoDel target 5 ms | measured time each request waited | 5 ms of standing queue delay | drop at the head |
| AIMD limit | completions, classified fast or congested | implicit — whatever concurrency keeps completions fast | admit / 503 |

**2.3 Impossible to say, and the missing number is goodput** (or success rate
over offered). Every figure given is consistent with both a healthy service
shedding its overflow correctly *and* a service serving almost nobody. 100%
utilisation and a 67% rejection rate are what a working shedder looks like.
Median latency of 210 ms is measured over survivors and cannot help.

**2.4**

- `Connection refused` — **retry.** Definitely not received, and cheap for the
  server to have refused.
- `503 from a shedder` — **do not retry**, or retry once after a substantial
  honest delay. It is the one error guaranteeing both that the work did not
  happen and that the service is already at its limit.
- `504 gateway timeout` — the *unknown* case. Retry only if the operation is
  idempotent or you carry an idempotency key.
- `429` — do not retry before `Retry-After`. It is a statement about your quota,
  and retrying sooner cannot succeed.
- `500` — also unknown; the request may have been half-applied. Same rule as 504.

---

## Rung 3 — Predict

**3.1 About 0.7%.** Not 83%. The queue grows by 10 requests/second forever; within
about a minute everyone joining is already certain to time out, and the server
keeps doing their work anyway. Wasted work reaches 99%.

**3.2 0.1% — exactly the same as no cap at all.** A queue of 100 at 80 ms across
4 workers is 2,000 ms of waiting, twice the client deadline. Bounding a queue at a
number that merely *sounds* small does nothing. Measured: caps of 400, 200 and 100
all return 0.1%; the cap has to reach about 30 before anything changes.

**3.3 (a) Goodput does not move — 50/s in every case. (b) Nor do the admission
decisions: 394/s at 200 ms, 393/s at 1 s, 395/s at 1 s with jitter.**

This is the one most people get wrong. Backoff and jitter fix *synchronisation* —
clients retrying in lockstep at the same instant. Under sustained overload there
is no herd to spread out: every client will exhaust its attempts whenever you
schedule them. Jitter is a fix for correlation, not for volume. The thing that
worked was not retrying the rejection: back to 150/s.

**3.4 Enormously, and no.** Twenty runs of the identical configuration produced
success rates from **1.1% to 70.6%**. And running longer makes it *worse*, not
more reliable: mean 28.6% over ten minutes, 11.7% over an hour. At exactly ρ = 1
the queue is a random walk with nothing pulling it back down, so there is no
steady state to converge on. "We run at 100% utilisation" is not an operating
point.

**3.5** Success rate 0.1% → **33.4%, the theoretical ceiling**. Median latency
532 ms → **66 ms**. Wasted work 100% → **0%**. And **queue depth does not improve
at all** — still 60,047 and still growing. LIFO fixes who you serve and does
nothing about memory, which is exactly why the production design pairs it with a
drop rule.

---

## Rung 4 — Modify

**4.1** Capacity `4 workers ÷ 0.4 s` = **10/s**, down from 50/s — a 5× fall with
no traffic change. The correct cap scales with the same factor: about **4**, from
`c·(D/S − 1) = 4 × (1000/400 − 1) = 6`, then divided down for the same reason the
original 46 became 20. **Your configured cap of 20 is roughly 5× too large** — and
it therefore delivers 0.1% against a 16.7% ceiling, the same as having no shedder.

**4.2** Capacity doubles to 100/s.

- **(a) queue cap 20 and (b) in-flight limit 12** — both still protect goodput
  perfectly (measured 33.2% and 33.4% against a 33.3% ceiling at 3× the new
  capacity), but they now correspond to *half* the queueing delay they used to,
  so if you sized them for a latency target you should double them. They are
  counts, and counts are per-worker quantities in disguise.
- **(c) CoDel target 50 ms — unchanged.** It is denominated in time, and the time
  you are willing to wait did not change because you bought more machines. This
  is the whole argument for time-based signals in one line.
- **(d) the client's 1-second timeout — unchanged.** It is a property of the
  caller's patience, not of your capacity.

**4.3** The ceiling is `workers ÷ cost per rejection`. At 2 ms: `4 ÷ 0.002` =
**2,000 rejections/s**. At 0.4 ms: `4 ÷ 0.0004` = **10,000/s** — a **5×**
improvement. Measured: goodput survives to 5,000/s offered (25.0/s) and reaches
zero between 9,000 and 10,000/s, exactly as predicted.

**4.4** `allow = 0.25` of a **fixed** limit of 12 is a hard limit of **3
concurrent recommendation requests, enforced at every moment**, including when
the workers are 69% busy and there is capacity for everything. The fraction
inherited the fixed limit's flaw: it encodes "a quarter of capacity" as a
constant, and a constant cannot know that the service is currently fine.

- *Changing a number:* raise the fractions until they only bind under real
  overload. This works and is fragile — the right value depends on capacity,
  which changes.
- *Changing the design:* apply the fractions to an **adaptive** limit, so when
  the service is fast the limit is large and a quarter of it is plenty. Measured:
  79.0% → **95.3%** at healthy load, with identical protection under overload
  (checkout 96% vs 97% at 3×). This is the right answer.

---

## Rung 5 — Trace

**5.1**
  a. 46 ahead ÷ 4 workers × 80 ms = **920 ms**.
  b. 920 + 80 = **1,000 ms**.
  c. **Exactly at the deadline** — so roughly half of them miss it, since real
     service times vary around the mean.
  d. **The formula sizes the queue so that the *average* admitted request just
     barely makes it, which means about half do not.** Measured: a cap of 46
     returns 18.7% against a 33.3% ceiling — a bit over half of optimal. Any
     deadline-derived cap is a ceiling to divide by two or three, not a setting.

**5.2**
  a. 150 × 60 = **9,000 arrive**; 50 × 60 = **3,000 can be finished**.
  b. 9,000 − 3,000 = **6,000 waiting**.
  c. 6,000 ÷ 4 × 80 ms = **120 seconds** — two minutes, against a one-second
     deadline.
  d. The queue reaches 1-second-deep (50 requests) after about half a second of
     overload. **From roughly the first second onward, every newly-queued request
     is already certain to fail**, so essentially 100% of worker time after that
     is spent on work nobody will receive. The measured figure over ten minutes is
     100% wasted, 0.1% served.

**5.3**
  a. 4 workers × 1,000 ms = **4,000 worker-ms per second**.
  b. About 50/s admitted, so ~950/s rejected × 2 ms = **1,900 worker-ms/s**, just
     under half the machine. (Measured 1,083 rejections/s and 49% of CPU — a
     little higher, because the rejection work displaces service, which lets more
     arrivals be rejected.)
  c. `4,000 ÷ 2` = **2,000 rejections/s**, so roughly **2,050/s offered**.
     Measured: goodput 0.1/s at 1,800/s offered and 0.0 at 2,400/s, with
     rejections plateauing right at 2,000/s.
  d. Serving 40/s costs 40 × 80 = **3,200 worker-ms/s**, leaving 800 for
     rejections = **400 rejections/s**, so **≈ 440/s offered**. Measured at 440/s
     offered: goodput **40.2/s**. The arithmetic is exact.

**5.4**
  a. 50 admitted, **100 rejected**.
  b. All 100 retry: **100 arrive** as attempt 2.
  c. Naively: 150 + 100 + 67 ≈ **317/s**.
  d. **The first pass leaves out the feedback.** It computes the rejection
     probability from the *original* 150/s, but the retries raise the arrival
     rate, which lowers the admission probability, which rejects more, which
     produces more retries. Solving for the fixed point — `A = 150(1 + q + q²)`
     with `q = 1 − 50/A` — gives **396/s**, against a simulated **394/s**.

     This is the whole danger of retries in one calculation: **the amplification
     factor is not a constant you can multiply by, it is a loop that finds its own
     level, and the level rises faster than the load does.**

---

## Rung 6 — Spot the flaw

**6.1 CPU cannot see the most common overload.** Smallest failing scenario: your
database slows from 80 ms to 400 ms. All four workers are occupied waiting on
network I/O, the queue grows without bound, goodput goes to zero — and CPU is
near idle the whole time, because waiting is not computing. The shedder never
fires. Broken guarantee: the signal must respond to *inability to finish work*,
and CPU measures something else. (Note the honest exception: Google's SRE book
*does* use CPU-against-reservation as its primary signal, for CPU-bound RPC
servers with controlled thread pools. Copying the signal without that context is
the error.)

**6.2 A shedder that is off by default has never been tested.** Smallest failing
scenario: the first time it is switched on, in an incident, the threshold is in
the wrong units and the value chosen under pressure is 100 — which the lab
measured as worth 0.1%, identical to nothing. The deeper flaw is that everything
around it is untested too: the 503 path allocates, the request gets logged at
INFO with its body, the client retries it immediately, and `/healthz` is shed.
None of that is visible in code review. Correct model: shedding runs **always**,
waving everyone through on a normal day, so that its calibration is a
steady-state property rather than a decision made at 3 a.m.

**6.3 You have built a synchroniser.** Smallest failing scenario: an overload
begins; in one 50 ms window you reject 4,000 clients; all 4,000 are told exactly
1 second; all 4,000 return inside the same 50 ms window one second later. You
have converted smooth overload into a periodic thundering herd of your own
manufacture, timed by your own header. Fixes: jitter the value **server-side**,
and make it honest — if you say 1 second and are still overloaded in 1 second,
the next wave is worse. This is your `distributed-rate-limiting` lab's warning
about `Retry-After` arriving in a new place.

**6.4 Uniform edge shedding degrades the healthy and does not save the sick.**
Smallest failing scenario: six services behind the edge, one of them overloaded.
Shedding 30% of everything cuts the five healthy services' traffic for no reason,
while the sick one still receives 70% of a load it cannot take — so it is still
down, and now five other things are worse. The edge's signal is aggregate; the
overload is local. Broken guarantee: the decision must be made where the accurate
signal is.

**6.5 The Little's Law number is a ceiling, not a setting** — the same error as
5.1(d), in the other units. 50 in flight is the concurrency at which the *average*
request finishes exactly at the deadline, so half do not. Measured: an in-flight
limit of 50 returns **18.7%** against a 33.3% ceiling, while 12 returns the full
33.3%.

**6.6 You have made the mitigation into the outage.** Smallest failing scenario:
one instance becomes overloaded and sheds; its `/healthz` is shed too; the load
balancer marks it dead and moves its traffic to the remaining instances; they
become more overloaded and shed harder; their health checks are shed; the whole
pool is removed. Health checks and control-plane endpoints must be exempt — on a
separate port, pool, or criticality class — and a health check should report *"can
I serve?"* rather than *"am I alive?"*.

**6.7 True about goodput, false about memory, and false about the client.** Two
smallest failing scenarios:

- **Memory:** at 3× overload, LIFO reached 33.4% *with a queue of 60,047 and
  growing*. The service dies of `OutOfMemoryError`, restarts cold, and meets the
  same traffic with an empty cache.
- **The client:** LIFO and a queue cap deliver the *same* 33% goodput, but they
  fail the other 67% completely differently — under LIFO they wait a full second
  and get nothing; under a cap they are told "no" in about a millisecond and can
  fall back, degrade, or try another region. Failing fast is a feature you ship
  to your callers.

Also worth naming: LIFO **violates liveness**. Under permanent overload the
request at the bottom of the stack is never served at all. It is an overload
*mode*, not a default.

---

## Rung 7 — Handle failure

**7.1** You have hit the **rejection ceiling** (F1): offered load has risen to the
point where the cost of saying "no" consumes the machine. `workers ÷ cost per
rejection` is your ceiling — 4 ÷ 2 ms = 2,000/s in the lab's service.

*Confirming it from metrics:* CPU or worker occupancy at 100% while goodput falls
toward zero and the **rejection rate has plateaued** rather than continuing to
track offered load. That plateau is the signature — you are refusing as fast as
you physically can. Compare rejection rate against `workers ÷ measured 503 cost`.

*Options in five minutes,* in order of leverage:

1. **Move the rejection upstream** — shed at the load balancer or CDN, where a
   refusal is far cheaper, or drop before TLS termination.
2. **Make the rejection cheaper** — turn off access logging for shed requests,
   drop the response body, stop emitting a per-request metric with a high
   cardinality label. Halving the cost doubles the ceiling.
3. **Client-side adaptive throttling** (Google's `max(0, (requests − K·accepts) /
   (requests + 1))`, `K = 2`) — the only option that removes the cost from your
   machine entirely, because a client that has stopped calling costs you nothing.
   Not deployable in five minutes, which is exactly why it should already be
   there.
4. If the traffic is abusive rather than organic, drop at a layer that answers
   with a TCP reset rather than an HTTP response.

**7.2 The mechanism.** The burst pushed the queue past the point where anything in
it can finish in time. Every request now times out; every timeout produces a
retry; each retry is a new arrival. **The retries alone are now enough to keep
offered load above capacity**, so the system sustains its own overload with no
external cause. This is a metastable failure — two stable states, and you are in
the bad one. Measured: an identical burst without retries was absorbed in about
four minutes; with retries the queue was at 52,206 and growing thirteen minutes
later.

**Waiting does not work** because the input keeping you down is generated by the
state you are in. There is no decay term.

**Minimum intervention:** break the loop by refusing work cheaply — turn on
admission control, or if you have none, drop traffic at the load balancer for
long enough for the queue to drain. That is precisely why "turn it off and on
again" works on this failure when nothing else does: it is the only tool most
teams have for emptying a queue. Measured: with a queue cap of 20 in place, the
same 3,000-request burst never takes the queue above 1.

**7.3** *What A and B see:* C returns fast 503s. B sees a sharp rise in errors and
**falling** latency (rejections are quick). A sees whatever B chooses to do — and
if B retries, A sees B get slower while C gets three times the load.

| mechanism | lives in | protects |
|---|---|---|
| timeout | A→B and B→C | the **caller** — bounds how long its worker is held |
| retry (budgeted, not on 503) | A and B | the **request**, when failures are independent |
| circuit breaker | B, keyed on (C, endpoint) | the **caller** B — stops holding workers on a dead dependency |
| bulkhead | B | B's **other endpoints** — caps how much of B's pool C can occupy |
| inbound shedder | **C** | **C** |
| outbound concurrency limit | B, per dependency | **C** |

**Only the last two protect C.** Everything else is B looking after itself. That
asymmetry is the point of the exercise: four of the five patterns most teams ship
protect the caller, and a service that is being crushed is not helped by any of
them.

**7.4** Three hypotheses, all consistent with what the lab measured while building
these controllers:

1. **An absolute latency threshold that is now always exceeded.** If the
   controller calls anything over 400 ms "congested" and production service time
   is 400 ms — because production calls a real database and staging did not — then
   every completion is a congestion signal and the limit grinds to the floor.
   *Distinguishing measurement:* compare the threshold against the p50 service
   time in production. If p50 is anywhere near it, this is your answer.
   *Measured analogue:* 10.1% against a 16.7% ceiling at **66% worker
   utilisation** — shedding work it had the capacity to serve.
2. **A bad latency baseline.** A controller comparing against a *minimum*
   observed RTT never observes an uncontended request under sustained load, so
   the ratio pins at its floor and the limit sticks. *Distinguishing
   measurement:* graph the baseline the controller is using; if it never moves,
   or sits implausibly low, it is not being re-measured. *Fix:* Envoy's approach
   — periodically pin concurrency to its minimum to force an honest measurement.
3. **A drop signal that never stops.** Under genuinely permanent overload every
   AIMD-shaped controller receives "congested" forever, and the multiplicative
   decrease beats the additive increase indefinitely. The controller is not
   broken; it cannot distinguish "let more in" from "still drowning".
   *Distinguishing measurement:* is offered load actually above capacity? If yes,
   the limit is correct and the problem is that you needed a *fixed floor* under
   it.

The third is the uncomfortable one, because the fix is to admit that an adaptive
controller needs a sanity floor — and a floor is a constant, which is what you
adopted the controller to avoid.

**7.5** *What is true:* nothing is broken, the shedder is working, and the
customer's burst genuinely exceeds what you can absorb.

*What you do:*

1. **Check which class was shed and whether the shedding was correct.** If their
   batch is `SHEDDABLE` and your checkout traffic survived, the system did its
   job and you should be able to show that.
2. **Give them a shape they can use.** A burst of 20,000 is a scheduling problem,
   not a capacity problem. Offer an async/batch endpoint, a documented
   requests-per-second ceiling with an honest `Retry-After`, or a queue they can
   submit to. The best outcome converts a burst into a stream.
3. **Add jittered scheduling advice** — if fifty customers all fire at 09:00:00,
   the aggregate is the problem, not any one of them.
4. **Price it.** If they need guaranteed burst capacity, that is a capacity
   commitment, which is a commercial conversation with a reserved-quota answer.

*The argument against just raising the limit:* the limit is not a preference, it
is a measurement of what you can finish. Raising it does not create capacity — it
converts fast, honest rejections into slow timeouts, and the measured cost of
that conversion is everyone else's traffic. You would take one customer's
complaint about being refused and turn it into every customer's outage. If they
genuinely need the capacity, buy the capacity; do not fake it by disabling the
thing that tells you the truth.

---

## Rung 8 — Design

**8.1 — A payments API**

30,000/min = 500/s, of which ~20/s are authorisations and ~480/s are polls.

*The central observation:* the two request types differ in service time by nearly
**two orders of magnitude** (1,200 ms vs 15 ms). A single limit counting requests
is therefore meaningless — 100 in-flight polls is 1.5 seconds of work, 100
in-flight authorisations is two minutes of it. **Separate pools, separate limits,
and no shared thread pool.** This is a bulkhead, and it is not optional.

*Where to shed:* at the edge for gross protection and cheap rejections; a
per-pool adaptive limit inside the service, which is where the accurate signal
lives; and — most importantly — an **outbound concurrency limit on the card
network**, since it is the real constrained resource and an inbound limit does
nothing for it.

*Signal:* queue delay per pool. For authorisations the *relative* signal matters
because the card network's latency legitimately varies; an absolute threshold
would shed correct traffic on a slow day.

*Priority:* authorisations `CRITICAL_PLUS` — they are the product and they carry
money. Polls `SHEDDABLE`. Note the asymmetry: polls are 96% of traffic and
approximately 0% of the value.

*What to tell the polls:* `503` (or `429`) with an honest jittered `Retry-After`,
plus a `Cache-Control` so the dashboard shows a slightly stale status rather than
an error. Better still, move them off polling entirely — webhooks or SSE remove
the load rather than shedding it, and 480/s of polling for a status that changes
once is the actual design flaw.

*Monitor:* authorisation goodput and shed rate (alert on any non-zero
`CRITICAL_PLUS` shedding), outbound in-flight against the card network, queue
delay per pool, and the card network's own latency as an input rather than a
symptom.

*If the card network's latency doubles* to 2.4 s: authorisation capacity halves.
The outbound limit must fall — an adaptive one does this by itself, a fixed one
does not. The 3-second SLA now has almost no headroom, so **deadline propagation
matters more than any limit**: a request with 600 ms of budget left should never
be started. And because polls are on a separate pool, they are unaffected — which
is the entire return on the bulkhead.

**8.2 — Choosing one**

The defensible answer is **(b) adaptive LIFO + CoDel**, and the case is:

- It self-tunes to service time, which is the failure that actually happens.
  Measured through a 5× dependency slowdown: static cap **0.1%**, hand-tuned
  static limit 11.2%, adaptive LIFO + CoDel **15.4%** against a 16.7% ceiling —
  better than the setting chosen by someone who *knew the answer in advance*.
- It is far simpler than an adaptive concurrency controller, and this lab
  measured three separate ways to get one of those subtly wrong, all of which
  look correct in code review.
- It has a published production pedigree (Facebook, Wangle, HHVM) and one
  parameter with a clear physical meaning.

*When it would be the wrong choice:* when your service time is genuinely stable
and your team has no appetite for a queue-order change — then (a) is 90% of the
benefit for a tenth of the thought, and shipping (a) this week beats shipping (b)
next quarter. Also when your work is CPU-bound and uniform, where a fixed limit
is both correct and provable. And note the measured cost of (b) that (a) does not
have: at healthy load CoDel with a badly-scaled target shed **10%** of traffic
that did not need shedding. It is not free, and (a) is.

**8.3 — The dashboard**

*Metrics (four):*

1. **Goodput / success rate over offered** — detects F8, the outage invisible to
   throughput. This is the one that would have caught it.
2. **Queue delay, p50 and p99** — detects the v1 failure (service time moving) and
   F4 (CPU not firing), and it responds in milliseconds.
3. **Shed rate split by criticality** — distinguishes "working as designed" from
   "out of promised capacity", which no aggregate can.
4. **Rejection rate against `workers ÷ 503 cost`** — detects F1, the rejection
   ceiling.

*Alerts (two):*

1. **Any sustained shedding of the top criticality class.** Shedding
   `SHEDDABLE` is a healthy system; shedding `CRITICAL` means you have run out of
   the capacity you promised.
2. **Queue delay p99 above the deadline budget for 60 seconds.** Fires before
   goodput collapses rather than after.

*Why a p99 latency alert would not have fired:* p99 latency is computed over
requests that **received a response**. During the outage the only requests
receiving responses in time were the lucky few that arrived when the queue was
briefly short — so the p99 of that population was *better* than normal. Once
shedding is added it improves further, because a 503 is fast. **Every
latency metric you own is conditioned on survival, and the thing you are trying
to detect is deaths.**

**8.4 — Perfect deadline propagation**

*Becomes unnecessary:*

- **Guessing the drop threshold.** The lab's measured awkwardness — that dropping
  at the deadline is worth 3.8% while dropping 250 ms *before* it is worth 32.1%
  — exists only because the server is estimating. With a real budget you compare
  `remaining < expected service time` and the estimate disappears.
- **Deriving a queue cap from an assumed deadline.** Every request carries its
  own.

*Becomes simpler:*

- **The signal.** "Would this request make its deadline?" is answerable per
  request rather than inferred from aggregate queue delay. Admission control
  becomes almost trivial: admit if `remaining > predicted wait + service time`.
- **The whole call tree.** Every hop can drop work nobody is waiting for, instead
  of only the entry point knowing.
- **Priority.** Deadline and criticality are different axes, but earliest-deadline
  ordering is a well-understood scheduling discipline and becomes available.

*Still required:*

- **Admission control — yes, still, and this is the crux of the question.** A
  deadline tells you whether *this* request can be served; it does not bound how
  many requests are inside your process. Ten thousand requests each with a
  generous 30-second budget are all individually servable and will still exhaust
  your memory. **Deadlines solve the wasted-work problem, not the resource-bound
  problem** — which is why the safety property in stage 12 is stated about work
  in progress and not about time.
- **Cheap rejections** (F1 is unaffected — you still have to say no, and saying
  no still costs).
- **Retry control** (F2 is unaffected; a deadline does not stop a client asking
  again).
- **Priority** — deadlines rank by urgency, and urgency is not value. A
  recommendation carousel with 50 ms left is more urgent and less important than
  a checkout with 2 seconds left.

**8.5 — Multi-tenant fairness**

*The mechanism to add:* make the shed decision a function of `(tenant, that
tenant's current share of admissions)` rather than of arrival order alone. Two
practical shapes:

1. **Weighted fair queueing** — a queue per tenant, served round-robin by weight.
   Strong isolation, and the cost is real: per-tenant queues fragment your
   capacity, and your `queueing-theory` lab measured what that costs — four
   separate lines at 80% busy wait **5.2× longer** than one shared line. You are
   buying fairness with latency.
2. **Shed the heaviest sender first** — when overloaded, reject in descending
   order of each tenant's share of recent admissions. Much cheaper, no
   fragmentation, and only approximately fair, which is usually enough.

Prefer (2) unless a contract requires (1).

*What it costs when nothing is wrong:* option 2 costs essentially nothing — it
only ranks when the shedder is already firing. Option 1 costs latency **all the
time** (the pooling result above), which is why "shard the queue per tenant for
isolation" is so often a latency regression dressed as a feature.

*The failure mode of the fix:* **tenant identity is an input you do not always
have or cannot trust.** Unauthenticated traffic, a shared gateway that collapses
many tenants into one identity, a single tenant behind many API keys, or an
attacker rotating credentials — all of these defeat share-based shedding, and it
degrades to the blind version exactly when you need it most. So it is a fairness
improvement layered *on top of* an identity-blind shedder, never a replacement
for one.

Also worth saying plainly: this exercise is where load shedding hands off to rate
limiting. A shedder made fair is an approximation of a quota system built under
duress. If the noisy-neighbour problem is chronic rather than occasional, the
real fix is per-tenant limits negotiated in advance, and the shedder goes back to
doing the thing only it can do — surviving.
