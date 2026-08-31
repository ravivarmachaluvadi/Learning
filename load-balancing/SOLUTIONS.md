# Load Balancing — Solutions

Rungs 1–5 have right answers. Rungs 6–8 have *reasoning*, and what follows is one
worked line through each rather than the only acceptable one.

Every number cited here is in `README.md` and reproducible in
`visualization.html`.

---

## Rung 1 — Explain

**1.1** A load balancer exists to stop a request waiting behind a long queue when
a short queue exists somewhere else. That is the whole job.

The objection is the point of the lab, not a problem for the answer. Equal
*request counts* are not equal *queues*, for two independent reasons:

- Requests are not the same size, so equal counts give unequal work.
- Arrivals clump, so even identical requests pile unevenly if placement is
  random.

Round robin fixes the second and does nothing about the first. Stage 1's fleet
had 40 requests a second to each of eight servers, identical CPU, zero errors,
and a p99 of 561 ms against an SLO of 500 — because *a customer does not
experience the average*.

**1.2** *"A load balancer performs no work and adds no capacity; it recovers
capacity you already own and were wasting on idle servers while other servers had
queues."*

What it bought: at ρ = 0.9 it is worth 2.6× on p99 (995 ms random vs 384 ms for a
shared queue). What it buys at ρ = 0.5: **nothing** — every rule in the lab is
within 5% of every other one there, and within 4% of the theoretical optimum.
And above ρ = 1 it buys nothing again,
because every queue is growing and there is no good one to pick.

**1.3** One big server has **one queue**. Whoever is free next serves whoever has
been waiting longest, so no capacity is ever idle while someone waits. Eight
small servers have **eight queues**, and you have to commit a request to one of
them before you know which will free up first. Measured, same hardware and same
traffic at ρ = 0.9: one queue gives 91.5 ms mean, eight separate queues with
random placement give 244 ms.

We build it the worse way because one big server cannot be made big enough, cannot
be patched without downtime, and dies all at once. **The eight-queue penalty is
the price of horizontal scaling**, and the load balancer is the attempt to claw
most of it back.

**1.4** Round robin bets that **all requests cost about the same**.

- Bet right (c²ₛ = 0): p99 of 99.7 ms — *exactly* the shared-queue ideal of
  99.7 ms. It does not approximate the optimum, it achieves it.
- Bet wrong (c²ₛ = 16): 3,863 ms, against random dispatch's 3,847 ms. Taking
  turns has become marginally worse than not thinking at all, and both are 2.6×
  the ideal of 1,460 ms.

**1.5** The mechanism is a **positive feedback loop**, and it has nothing to do
with how fast load "really" changes.

At the moment of the snapshot some backend is emptiest. `argmin` then sends it
*every single arrival* until the next refresh — so the dispatcher's own actions
are what make the information wrong, immediately, and in proportion to how much
traffic it is carrying. The load did not change on its own; the balancer changed
it. Then the snapshot updates, that backend is now the worst, and the entire
stream swings onto the next victim.

Measured at ρ = 0.9: a 250 ms-old view makes least-connections **2.9× worse than
random**. A 15-second view is far past that.

---

## Rung 2 — Identify

**2.1**

| | what it is | failure it addresses |
|---|---|---|
| a. `least_conn` | dispatch rule | uneven queues; unequal request sizes |
| b. `max_ejection_percent: 50` | candidate filter (a bound on one) | a health checker that ejects the whole fleet and turns a latency problem into a total outage (F7) |
| c. `slow_start: 30s` | candidate filter (a weight ramp) | a cold backend given a full share — the 76-second p99 on a routine restart (v2) |
| d. `preStop` sleep | neither — it is connection draining | in-flight requests killed at shutdown; 502s on every deploy |
| e. `random two least_conn` | dispatch rule (power of two) | the herd under a stale or per-dispatcher view (F3) |
| f. `ip_hash` | dispatch rule, but one that **removes the choice** | it addresses no balancing failure; it buys affinity and *creates* F5 |
| g. dependency-free `readinessProbe` | candidate filter that does not work | nothing — it is the mechanism by which F1 survives the thing meant to prevent it |

**2.2**

- **i. One HAProxy, `leastconn`.** View: exact (one dispatcher, live state).
  Rule: `argmin` over all 8. Backend set: 8. This is the only row where
  least-connections' bet actually holds.
- **ii. Twenty NGINX pods, `least_conn`.** View: **local** — each pod knows only
  its own outstanding requests, i.e. 1/20th of the traffic. Rule: `argmin` over
  its own counts. From stage 10, at 32 dispatchers this costs 3.1× against the
  single-dispatcher case and loses to power of two.
- **iii. gRPC `round_robin`.** View: none needed. Rule: round robin, per client.
  Backend set: 8 — the client holds a connection to *each*. Note this is
  client-side balancing, so the connection count is `clients × backends`.
- **iv. AWS NLB.** View: none. Rule: hash of the 5-tuple, evaluated **once per
  connection**. Backend set: 8. This is F6: it balances connections, not
  requests.

**2.3**

| signal | (i) 5× slow | (ii) fails in 1 ms | (iii) cold restart | (iv) full disk, `/health` clean |
|---|---|---|---|---|
| queue depth | **yes** — queue stays long | **no** — reads as emptiest | **yes** | no |
| response time | **yes** | **no** — reads as fastest | **yes** | no |
| `/health` probe | usually no (it still answers) | maybe, if the check exercises the failing path | no | **no** — this is the whole problem |
| per-backend error rate | no (it is slow, not wrong) | **yes** | no | **yes** |

The row that matters: **queue depth and response time are blind to exactly the
failure that error rate catches, and vice versa.** That is why dispatch and
health are two mechanisms.

**2.4** Three mechanisms for one backend at 42%:

1. **Connection placement (F6).** Few long-lived HTTP/2 connections hashed at
   L4 — eight connections over eight backends measured a 42.2% busiest share.
   *Tell-tale:* the share is stable for hours and only moves when something
   reconnects.
2. **Session affinity with skew (F5).** A hash of tenant or session where one
   tenant is large. *Tell-tale:* the hot backend changes identity when the
   backend count changes, and restarting moves the heat elsewhere.
3. **A herd (F3).** Stale-view least-connections, where each backend takes turns
   being the victim. *Tell-tale:* at 1-second resolution the share oscillates
   wildly; at 1-minute resolution all backends look equal.

To tell them apart, graph per-backend request rate at **one-second** resolution
and watch what happens when a client restarts.

---

## Rung 3 — Predict

| | answer | source |
|---|---|---|
| **3.1** ρ = 0.5 | **1.04×** — 383 ms → 367 ms. Essentially nothing. | load sweep |
| **3.2** ρ = 0.95 | **4.2×** — 1,834 ms → 435 ms. | load sweep |
| **3.3** c²ₛ = 0 | round robin **99.7**, least conn 114.4, po2 131.4, random 277.7. **Round robin equals the ideal exactly** — and beats both load-aware rules. | c²ₛ table |
| **3.4** τ = 15 s | Far worse than random. The trend is 100 ms → 331 ms, 1 s → 2,336 ms against random's 244 ms, and it keeps climbing; at 15 s the fleet is oscillating so hard that the rule has become an anti-optimiser. | τ table, extrapolated — stated as reasoning |
| **3.5** fails in 1 ms | rr **12.5%**, po2 **23.1%**, least conn **66.1%**, least-response-time **100.0%** | F1 |
| **3.6** 5× slower | rr **12.5%**, po2 **3.1%**, least conn **3.0%**, least-response-time **1.0%** | F2, panel 4 |
| **3.7** 16 connections | busiest gets **28.1%** on average and **43.7%** in the worst run — 140 req/s onto a 50 req/s machine. Its queue never stops growing. | F6 |
| **3.8** lose one at ρ = 0.875 | ρ becomes exactly **1.0**; p99 **2,082 ms** and still rising when the measurement stops. | F4 |

If you got 3.3 wrong, that is the most useful miss in the set: it is the one
place where the "smarter" rules lose, and it is because they are *reacting* to
noise that round robin simply does not generate.

---

## Rung 4 — Modify

**4.1** `d = 2 → 4`: mean 108.6 → 96.2 ms, p99 413.9 → 392.0. You gain **12 ms of
mean**, having doubled the state you must read per request. For comparison,
`d = 1 → 2` gained **135 ms**. You pay twice the lookups and, more importantly,
you move back toward the behaviour that herds — the wider you sample, the more
likely two dispatchers agree on a victim. **Do not ship it.** The first extra
choice is worth 90% of what perfect information is worth.

**4.2** 8 → 128 backends at ρ = 0.9:

- **random**: worst queue 58 → 86. It *grows*, because the worst of `n`
  independent draws grows like `log n / log log n`, and more backends means more
  chances for one to be unlucky.
- **power of two**: worst queue 11 → 6.8. It *shrinks*. You land on a long queue
  only if *both* samples were long, which squares a small probability; the worst
  queue is `log log n / log d`, effectively a constant.

By 128 backends random is 12.6× worse. This is the clearest evidence in the lab
that the technique is not a cost-saving approximation.

**4.3** Dividing by weight solves **heterogeneous capacity** — a machine with
twice the cores gets twice the traffic, so it stops being the fleet's bottleneck
or its idler.

The new failure: a weight is **static configuration**, and it describes the fleet
you had when you wrote it. Plain least-connections *measures*, so it discovers a
degraded machine automatically; weighted least-connections partly overrides that
measurement with an assumption. A weight of 2 on a machine that is currently sick
tells the balancer to send it twice as much, and it will keep doing so until
somebody edits a config. Prefer measurement over configuration wherever you can.

**4.4** Switch to **round robin**. At c²ₛ = 0 it ties the theoretical optimum
(99.7 ms) and beats least-connections (114.4) and power of two (131.4), while
needing no state, no view, and no coordination — so it also cannot herd and does
not degrade when they add a second load balancer.

What they give up: **all of the automatic protection**. Round robin will hand a
5×-slow backend a full share and produce an unbounded queue on a fleet at 80%,
and will hand a cold restart a full share and produce a 76-second p99. They are
now entirely dependent on health checks, outlier ejection and slow start, and
those must be right before the switch, not after.

**4.5** Two backend-side changes, each moving a lever in the model:

1. **Make the service faster** (cut mean service time S). This lowers ρ for a
   fixed arrival rate, and the load sweep shows the gap between rules collapses
   as ρ falls — at ρ = 0.5 nothing matters. It also lowers the p99 *floor*, which
   no dispatch rule can touch.
2. **Make request costs more uniform** (cut c²ₛ) — split a heavy endpoint onto
   its own pool, cap page sizes, move a slow path to async. From the c²ₛ table
   this is worth going from 3,847 ms to 278 ms under random dispatch. Variability
   is the lever the balancer is fighting, and removing it at the source is
   strictly better than compensating for it.

A third, if you count it: **add backends**. From F4's table, more backends at the
same ρ pool better — at n = 128 even least-connections' worst queue is 1.

---

## Rung 5 — Trace

**5.1** Four backends, one worker, 100 ms each. Start `A:0 B:2 C:1 D:0`.
Arrivals at 0, 10, 20, 30, 40.

*Round robin*, starting at A: → A, B, C, D, A.
Resulting depths after the fifth: `A:2 B:3 C:2 D:1`.
The fifth request is second in A's queue behind one request that started at
t = 0, so it waits until ~100 ms then runs to ~200 ms.

*Least connections* (live view), re-evaluated per arrival:

| t | depths seen | pick | depths after |
|---:|---|---|---|
| 0 | A0 B2 C1 D0 | **A** (tie with D, take A) | A1 B2 C1 D0 |
| 10 | A1 B2 C1 D0 | **D** | A1 B2 C1 D1 |
| 20 | A1 B2 C1 D1 | **A** (three-way tie at 1) | A2 B2 C1 D1 |
| 30 | A2 B2 C1 D1 | **C** | A2 B2 C2 D1 |
| 40 | A2 B2 C2 D1 | **D** | A2 B2 C2 D2 |

The fifth request goes to D, which had one request in service since t = 10, so it
starts at ~110 ms and finishes at ~210 ms — **wait 70 ms** from its arrival at 40.

Under round robin the fifth went to A behind a request that started at t = 0, so
it starts at ~100 and finishes at ~200 — **wait 60 ms**.

So on *this* particular trace they are within 10 ms, and that is worth noticing:
with equal request sizes and a nearly-balanced start, least-connections has almost
nothing to win. Extend the trace, or start from `A:0 B:8 C:0 D:0`, and the gap
opens immediately. **The advantage of a load-aware rule is a statement about the
tail, not about any single request.**

**5.2** View snapshotted at t = 0 (`A0 B2 C1 D0`) and refreshed every 50 ms — so
for all five arrivals the belief is unchanged.

`argmin` of the frozen view is **A** (tie with D at 0; A wins the tie
consistently). All five requests go to A — or, with random tie-breaking, all five
split between A and D and none go to B or C.

Depths at t = 45 ms with deterministic tie-breaking: `A:5 B:2 C:1 D:0`.

Compare with 5.1's `A:2 B:2 C:2 D:1`. **The stale dispatcher built a queue of 5 on
one backend while leaving another completely idle**, in 45 milliseconds, using the
rule that is optimal with a live view. That is the herd, in miniature.

**5.3** A fails in 1 ms; B–H take 80 ms; live view; arrivals every 3 ms from
empty.

t=0: all zero → A (or a tie-break) → A busy.
t=1: A finishes; A back to 0.
t=3: depths `A0` and the rest 0 → tie, A eligible again.
t=4: A finishes.
t=6, 9, 12, …: A is at 0 every single time an arrival appears, because it clears
in 1 ms and arrivals are 3 ms apart. Every other backend that has taken one
request still holds it for 80 ms.

So after the first pass A takes essentially **every** arrival — a share close to
**100%** over ten requests.

Why the ten-minute figure is only 66.1%: with random tie-breaking A does not win
every tie, and at steady state the seven healthy backends carry 4 workers × 80 ms
each, so they are frequently at low counts too and win some of the comparisons.
The very short trace exaggerates the effect because the healthy backends have not
yet started clearing anything. **The direction is what matters, and it is the
same: the broken backend attracts far more than its share.**

**5.4** Power of two, depths `A1 B7 C0 D3 E9 F2 G0 H4`.

| pair | depths | pick | depths after |
|---|---|---|---|
| (B,E) | 7 vs 9 | **B** | B → 8 |
| (A,C) | 1 vs 0 | **C** | C → 1 |
| (E,H) | 9 vs 4 | **H** | H → 5 |
| (G,B) | 0 vs 8 | **G** | G → 1 |
| (D,F) | 3 vs 2 | **F** | F → 3 |

Final: `A1 B8 C1 D3 E9 F3 G1 H5`.

E — the worst queue — was sampled twice and chosen **zero** times.

Probability a single request lands on E (the unique maximum) under power of two:
E must be drawn *and* win, and it only wins if the other draw is also E. With
sampling *with replacement*, P = 1/n² = 1/64 ≈ **1.6%**. Under random dispatch it
is 1/8 = **12.5%**. Eight times less likely, from one extra question.

---

## Rung 6 — Spot the flaw

**6.1 Lowest average response time.** This is the **failure magnet** in its purest
form, and it is the worst rule in the lab for it.

Smallest scenario: eight backends, one loses its database connection pool and
returns HTTP 500 in 1 ms. Its 10-second average response time is 1 ms; every
healthy backend's is 80 ms. It wins every comparison, forever.

Measured: **100.0% of fleet traffic to the broken backend.** Violated guarantee:
none, formally — the rule did exactly what it promised. The design error is that
"fastest" was assumed to mean "healthiest", and *failing is the fastest thing a
server can do*.

**6.2 Shared Redis counter.** Three separate flaws, in increasing severity:

1. **You have put a network round trip in the dispatch path** to save a few
   hundred milliseconds of tail latency, and you now pay it on *every* request.
   `distributed-rate-limiting` priced exactly this trade.
2. **You have created a single point of failure** for a component whose entire
   purpose is surviving the failure of components. When Redis is slow, every
   dispatch is slow — and a slow store is far more dangerous than a dead one,
   because it exhausts your request threads while every dashboard stays green.
3. **The view is still stale.** Between your `GET` and the dispatch, other pods
   have dispatched. You have bought a *smaller* τ at enormous cost, and τ = 0 is
   what you needed. With 30 pods you are also generating 30 × λ Redis operations
   per second.

Smallest failure: Redis takes 200 ms once. All 30 pods block, then all 30 read
the same now-stale counters and stampede. You have built a synchronised herd.

**6.3 Eject after one failed check.** Too strict, and it is F7's second mode.

Smallest scenario: the fleet is at ρ = 0.85, so the backends' p99 is naturally
around 430 ms. One health check hits a GC pause and takes longer than its timeout.
That backend is ejected. Its traffic moves to the survivors, pushing them from
0.85 to 0.97, whose p99 goes to seconds, so *their* checks start timing out too.
Within two check intervals you have ejected the fleet.

Violated: nothing about correctness — you have converted a latency problem into a
total outage. The fixes are asymmetric thresholds (slow to condemn, quick to
forgive) and `max_ejection_percent`.

**6.4 Consistent hashing on user ID, "50,000 users so it will be even".** The
count of keys is nearly irrelevant; the **skew** is everything.

Smallest scenario: user traffic follows the usual shape where the largest account
is many times the median. Measured with Zipf-distributed tenants over 8 backends
at ρ = 0.8: **4,096 tenants still put 20.5% of all traffic on one backend** — 65
requests a second onto a machine that can do 50 — while the same 4,096 tenants
made *equal in size* produce 12.6% and a perfectly healthy p99 of 554 ms.

Violated: the assumption that many keys implies even load. What they have built
is sharding, not balancing, and `consistent-hashing` already established that a
hot key is unfixable by any hashing scheme, because every scheme agrees one key
has one owner.

**6.5 Retry 3× on a different backend.** Fine for an isolated blip; a load
multiplier in exactly the situation you cannot afford one.

Smallest scenario: the fleet is at ρ = 0.8 and one dependency slows, so requests
start timing out. Each failure becomes 3 requests. Offered load goes from 320 to
960 against a capacity of 400. Now every backend is at ρ = 2.4, everything times
out, and every timeout produces 3 more.

Violated: the implicit assumption that retries are rare. **A per-request count
multiplies fleet load by 3 precisely when it must not.** The fix is a retry
*budget* — a cap on retries as a percentage of active requests (Envoy's default is
20%) — which cannot exceed that percentage no matter how many requests fail.

**6.6 Four pods, CPU target 75%.** The arithmetic is `(N−1)/N`.

With 4 pods, surviving the loss of one requires ρ ≤ **75%** — and that is the
*break-even* point, not a safe one. At exactly ρ = 0.75 with 4 pods, losing one
puts the survivors at ρ = 1.0. From F4's table, a fleet at ρ = 1.0 has a p99 of
2,082 ms and rising; it does not have a steady state at all.

And because they scale *on* 75%, they are at 75% by design most of the time. So
the smallest failing scenario is: one pod restarts. Which happens on every
deploy.

---

## Rung 7 — Handle failure

**7.1 The failure magnet, live.**

*First five minutes*, in order of value:

1. **Take the backend out of the pool.** Manually, by hand, right now — drain it
   in the balancer or fail its health check deliberately. This is the whole fix
   and it takes one command. Error rate goes from 66% to 0%, not to 12.5%,
   because the remaining seven are healthy.
2. If you cannot do that, **switch the dispatch rule to round robin.** Error rate
   goes from 66% to 12.5% — a 5× improvement from a config reload. Note this is
   the one and only scenario in the lab where round robin is the right answer,
   and it works because it optimises nothing.
3. Check the fleet has headroom for seven-of-eight at current load before doing
   either. If you were at ρ = 0.875 you are about to hit F4.

*Next thirty minutes:*

4. Fix the health check that let this happen — it passed on a backend failing
   every real request, which means it does not exercise the failing dependency.
5. Turn on **outlier ejection** on error rate, with `max_ejection_percent` set so
   the mechanism cannot remove the fleet.
6. Only then think about the code.

*Most likely to make things worse:* restarting the backend without draining it
(you lose in-flight requests and it rejoins at full weight — v2's cold-start
problem), and increasing timeouts (F1 is not a timeout problem; it is answering
too fast).

**7.2 Health check for a Postgres → transform → Kafka service.**

*What it does:* a handler returning a **cached** status, recomputed by a
background task every 5 seconds. The background task does a trivial real query
(`SELECT 1` on a pooled connection, with a 1-second timeout) and checks the Kafka
producer's metadata age. It returns 503 if either has failed for two consecutive
attempts.

*Why cached:* every balancer instance probes every backend; a check that hits
Postgres directly on every probe is `dispatchers × backends × frequency` queries
per second against the database — the check becomes its own load problem.

*Frequency and thresholds:* probe every 2 s; eject after **3** consecutive
failures (~6 s to detect); restore after **2** successes. Asymmetric on purpose:
slow to condemn, quick to forgive. Cap ejection at 50% of the pool.

*What it will not catch:* a backend that is **slow but working** — the gray
failure. `SELECT 1` returns fine while real queries take 5 seconds. It will also
miss a backend that is wrong rather than broken (returning bad data quickly).

*The second mechanism:* **outlier ejection on real traffic** — compare each
backend's observed p99 and error rate against its peers and eject the statistical
outlier. That tests the actual code path with actual data, which no synthetic
probe does. Plus load-aware dispatch, which routes around slowness automatically
without needing to classify it (F2).

**7.3 Removing a backend cleanly.**

1. **Mark it not-ready** (fail readiness, or set the balancer's drain flag).
   *Skip it, and* new requests keep arriving at a process about to exit.
2. **Wait for propagation.** Every dispatcher must observe the change; the wait
   must be at least one health-check interval plus one discovery-propagation
   delay. *Skip it, and* the pod stops accepting while some dispatchers still
   think it is live — a burst of connection refusals. This is the step almost
   everyone skips, and it is why `preStop: sleep 15` exists.
3. **Stop accepting new connections**, keep serving existing ones.
4. **Wait for in-flight requests to complete**, bounded by a drain timeout that is
   at least your p99. *Skip it, and* every in-flight request becomes a 502.
5. **Close idle keep-alive connections**, so clients reconnect elsewhere rather
   than reusing a socket to a dying process. *Skip it, and* HTTP/2 clients keep
   multiplexing onto it (F6 in reverse).
6. **Exit.**

And on the way back in: **slow start**, or you get v2's 76-second p99.

**7.4 Subsetting for 40 clients × 200 servers.**

*Subset size:* start from what balancing quality needs, not from the connection
budget. Power of two on a subset of 20 is close to power of two on 200 — the
`log log n` scaling flattens fast — while 20 × 40 = 800 connections instead of
8,000. Never go below ~5, or a single backend failure removes a large fraction of
a client's options.

*How chosen:* **deterministically**, not randomly — assign subsets so they tile
the backend set evenly. Google's SRE book calls this deterministic subsetting;
Finagle's *deterministic aperture* is the same idea. Random subsets give a
binomial spread in how many clients point at each backend, which reintroduces
exactly the imbalance you were trying to remove.

*On membership change:* recompute with a rule that moves as few assignments as
possible — this is `consistent-hashing`'s monotonicity requirement, in a new
place. A naive recomputation reshuffles every subset at once and produces a
fleet-wide connection storm on every deploy.

*Failure mode of overlap:* if the assignment degenerates so that many clients pick
overlapping subsets, some backends are in nobody's subset (idle, wasted) while
others are in everybody's (hot). The symptom is a bimodal per-backend load graph
that does not change over time. Guard it by asserting that every backend appears
in at least *k* subsets, and alert if that ever drops.

**7.5 Rules for retry-on-another-backend.**

1. **Budget, do not count.** Cap total retries at a percentage of active requests
   (20% is a reasonable default). *Prevents:* the 3× fleet-load multiplication of
   6.5, which happens exactly when you are already overloaded.
2. **Never retry a 503 from a shedder or a 429.** Those mean "there is no
   capacity", and retrying is the definition of the storm. *Prevents:*
   `load-shedding`'s and `distributed-rate-limiting`'s retry storms.
3. **Retry once, on a different backend.** *Prevents:* re-sending into the same
   failing queue, which is the case where a retry cannot possibly help.
4. **Only retry idempotent requests, or ones carrying an idempotency key.**
   *Prevents:* double-charging. Your `idempotency` lab is the prerequisite here,
   not an optional extra.
5. **Jitter every delay.** *Prevents:* your own `Retry-After` becoming the
   synchroniser that converts many independent retries into one simultaneous
   wave.
6. **Do not retry at more than one layer.** Client retries × balancer retries ×
   library retries multiply. *Prevents:* a 3-deep stack turning one failure into
   27 requests.

---

## Rung 8 — Design

**8.1 The gRPC hot pod.**

*Diagnosis, from the description alone:* **F6 — the balancer is placing
connections, not requests.** Three details give it away: the hot pod is
*consistently* hot (so it is not a transient herd), it changes identity
*weekly* (roughly the pod or client restart cadence, which is the only thing that
can re-place a connection), and *restarting moves the problem* (the connection
lands somewhere else). A Kubernetes `Service` sends gRPC over a small number of
long-lived HTTP/2 connections placed once by kube-proxy.

With 6 callers and 12 pods, if each caller holds one connection then **6 of the 12
pods get no traffic at all** and the busiest gets whatever share of connections it
happened to win.

*Confirming measurement:* per-pod **request rate** at 1-second resolution (not
CPU), plus the count of established connections per pod. You expect to see a small
integer number of connections per pod, several pods at zero, and a request rate
that is flat for hours.

*Three fixes, increasing in how much they change:*

1. **`max_connection_age` on the server** (e.g. 30 minutes, with jitter). Costs:
   nothing architecturally; a reconnect blip per interval. Buys: the imbalance
   reshuffles constantly so no pod stays hot. **This is a mitigation, not a fix** —
   the fleet is still unbalanced at every instant.
2. **Client-side balancing** — gRPC's `round_robin` policy with a headless
   Service so the resolver returns all 12 pod IPs. Costs: 6 × 12 = 72
   connections (fine at this size), and every client must watch every pod. Buys:
   real per-request balancing with no extra hop. **This is the right answer at
   this scale.**
3. **An L7 proxy or service mesh** — Envoy sidecars, or a gRPC-aware ingress.
   Costs: a hop, memory per pod, and an operational surface you did not have.
   Buys: per-request balancing plus outlier ejection, retry budgets, slow start
   and observability, and it scales to the point where option 2's connection
   count stops being acceptable.

**8.2 Choosing a rule with a straight face.**

The questions, in order, each with a determinate answer:

1. **How many dispatchers are there, and does each see the whole fleet's
   traffic?** One dispatcher with live state → least-connections is genuinely
   best (93.9 ms vs 108.6). More than about 16 → power of two wins (159.5 vs
   292.4 at 32).
2. **How old is the dispatcher's view of backend load?** Live (its own in-flight
   counts, updated synchronously) → least-connections. Anything on a refresh
   schedule → **the crossover is about 30 ms**, so in practice: power of two.
3. **How variable is the cost of a request?** Genuinely uniform (c²ₛ ≈ 0) → round
   robin ties the optimum and beats both load-aware rules. Anything else → a
   load-aware rule.
4. **Can any backend serve any request?** No → you are sharding, and the rule
   choice does not exist; go and read F5.

Combined: *uniform requests* → round robin. *Variable requests, one live
dispatcher* → least-connections. *Variable requests, many dispatchers or any
staleness* → power of two. That last case is most production systems, which is
why it is most products' recommended setting.

**The four things to fix before the rule matters at all:**

1. Health checks that exercise a real dependency, with asymmetric thresholds and
   an ejection cap (F1, F7).
2. L7 or client-side balancing for any HTTP/2 or gRPC traffic (F6).
3. Fleet headroom below `(N−1)/N` (F4).
4. Slow start on a fleet where restarts are routine (v2).

Justification for the ordering: the four items above are worth two orders of
magnitude (a 12.5% outage vs a 100% one; a 569 ms p99 vs a 338-second one) and
the rule choice is worth 1.7×.

**8.3 Sticky sessions you cannot remove.**

*Today:* 8 backends, ρ = 0.75, largest tenant at 18% of traffic. Fair share is
12.5%. The backend owning the largest tenant carries at least 18% *before* any
other tenant lands on it — and with ordinary skew in the rest, its realistic share
is 22–25%. That is 0.75 × 8 × 0.22 ≈ **1.3× its own capacity**. Its queue is
growing; the other seven have spare capacity they cannot lend.

*When they add a 9th backend:* with plain modulo hashing, almost every session
moves and every in-memory session is lost at once. With consistent hashing, ~1/9
move. **Either way the largest tenant simply relocates** — one backend is still
over capacity, just a different one. Adding capacity does not fix a hot key; that
is the property `consistent-hashing` established and it has not changed.

*Options, ranked:*

1. **Bounded-load consistent hashing** (HAProxy's `hash-balance-factor`). Hash to
   the preferred backend unless it is already above `(1+ε)×` the fleet average,
   then fall through. You keep affinity for the overwhelming majority of requests
   and give it up exactly where it would have hurt. **This is the only option
   that bounds the worst backend**, and it needs no application change.
2. **Split the whale.** Give the largest tenant its own dedicated pool, sized for
   it. The remaining 82% of traffic over 8 backends is much closer to even. Costs
   capacity and a routing rule; buys blast-radius isolation as well.
3. **Sub-key the session.** If the session key can carry a suffix
   (`tenant:shard-3`), the whale spreads across several backends. Only works if
   the application does not need all of one tenant's state in one place — often it
   does not.
4. **Overprovision and wait.** Run the whole fleet at ρ = 0.5 so that a 25% share
   is survivable. Expensive, does nothing about the next larger tenant, but it is
   the option that requires no change this quarter and it should be stated
   honestly as the fallback.

**8.4 Three pods, 70% CPU target, five deploys a day.**

*What happens during each deploy.* `maxUnavailable: 1` means one of three pods is
gone, so the fleet is at 2/3 capacity for the duration. If they were at ρ = 0.70
on three pods, they are now at **0.70 × 3/2 = 1.05**. That is above capacity: the
queue grows for as long as the rolling update takes, on every pod in turn, five
times a day. The `(N−1)/N` ceiling for three pods is **66.7%**, and their scaling
target is *above* it. And that ignores the new pod's cold-start period, during
which it is effectively worth much less than one pod (v2's measurement: a 5×-slow
cold node handed a full share produced a 76-second p99).

*A plan, cheapest first:*

1. **Set `maxSurge: 1, maxUnavailable: 0`.** Bring the new pod up *before*
   removing the old one. Cost: one extra pod for the duration of a deploy, i.e.
   minutes a day. This alone removes the capacity dip entirely and is the single
   highest-value change.
2. **Add slow start** (or a readiness probe that genuinely waits until the pod is
   warm). Cost: nothing. Prevents the new pod from being handed a third of the
   traffic while it is still interpreting bytecode.
3. **Lower the CPU target to 55–60%**, or set `minReplicas: 4`. With four pods the
   ceiling is 75%, so a 60% target has real margin. Cost: roughly one additional
   pod's worth of spend. Do this if step 1 is not available.
4. **Do not** raise the target or reduce replicas to save money; three pods is
   already the fragile end of the curve.

*What to monitor to know it worked:* per-pod request rate and per-pod p99 during
a deploy, at 1-second resolution, plus the healthy-pod count. Healthy: the count
never drops below 3, and no pod's p99 exceeds the fleet's by more than ~2×.
Currently they will see the count hit 2 and one pod's latency spike each time.

**8.5 What to put on the dashboard.**

*Invisible on fleet rate + fleet p50/p99 + per-pod CPU + error rate:*

- **F3 (herd)** — completely invisible. Every pod's *average* rate is exactly fair
  because they take turns; a one-minute bucket shows a flat line.
- **F6 (connection imbalance)** — invisible unless you look per pod, and per-pod
  CPU only shows it once it is already bad.
- **F5 (sticky skew)** — same.
- **F2 (gray failure)** — a fleet-wide p99 buries one bad pod among seven good
  ones; per-pod CPU shows the sick pod at 100%, which most people read as "that
  pod is working hard."
- **F7 (health-check flapping)** — invisible; nobody graphs pool membership.
- **F4 (node loss headroom)** — invisible until it happens.

*The smallest additional set:*

| graph | healthy shape | what wrong looks like |
|---|---|---|
| **max ÷ mean in-flight across backends** | a flat line at ~1.0–1.5 | sustained > 2 (imbalance) or spiky > 3 (herd) |
| **per-backend request rate, 1-second buckets**, one line per backend | eight overlapping lines | one line permanently above (F5/F6); lines taking turns spiking (F3) |
| **per-backend p99**, one line per backend | eight overlapping lines | one line orders of magnitude above (F2) |
| **healthy backend count** | a flat line at N | any sawtooth at all (F7) |
| **fleet ρ, with an `(N−1)/N` line drawn on it** | below the line | above it means you cannot survive a node loss (F4) |

If they only accept one: **max ÷ mean in-flight**. It is 1.0 for a perfect
balancer, was 3.4 in F6's eight-connection case, and it degrades *before* latency
does — which no other graph on the list can say.

**8.6 Counterfactual — what if the view were live, free and exact?**

*What survives unchanged:*

- The pooling result. N queues are still not one queue, and the ideal is still
  unreachable — a request must be committed to a backend before anyone knows who
  frees up first. Least-connections with a perfect view still measured 93.9 ms
  against the shared queue's 91.5.
- **F1, the failure magnet, entirely.** This is the important part of the answer.
  A perfect view of *outstanding requests* still shows a fast-failing backend as
  the emptiest one in the fleet; a perfect view of *response time* still shows it
  as the fastest. The information was never stale — it was answering a different
  question than the one you needed. Least-response-time sends it 100% with a
  live view.
- F4 (`(N−1)/N`), F5 (sticky skew), F6 (connection placement), F7 (health checks)
  and F8 (retries). None of them is an information problem.

*What disappears:* F3 and the whole of stage 10's second half. No staleness means
no herd, so least-connections would simply be the best rule and power of two would
be a curiosity — "89% of the benefit for 1/4 of the lookups", interesting only
where the fleet is huge enough that an O(n) scan per request is itself the cost.

*What you would still need in production:* health checking and outlier ejection
(F1 is untouched), slow start (a cold backend has low in-flight *because it has
just arrived*, so a perfect view actively directs traffic at it), connection
draining, weights or per-request balancing for HTTP/2, and retry budgets.

The lesson to take from that: **most of what a real load balancer does is not
dispatch.** Perfect information would delete one stage of this lab and leave the
other seven failures exactly where they are.
