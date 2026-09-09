# Load Balancing — you are not spreading load, you are choosing a queue

> **The one sentence.** A load balancer performs no work, adds no capacity and
> makes nothing faster; every bit of value it produces comes from a single
> negative — not letting a request wait behind a long queue while another queue
> is empty — and everything difficult about the subject follows from the fact
> that it has to make that choice without knowing what is really going on.

> **The measured fact that starts the lesson.** Eight identical servers. The
> same hardware, the same traffic, 90% busy. Route each request to a server
> chosen at random and the mean response is **244 ms** and the 99th percentile
> **995 ms**. Feed the identical eight servers from one shared line instead and
> it is **91.5 ms** and **384 ms**. Nothing broke, nothing was tuned, no code got
> faster. **2.7× the latency, purely from where the line forms.** That gap is
> the entire budget a load-balancing algorithm is competing for.

> **The measured fact that makes it hard.** The obvious way to spend that
> budget is to send each request to whichever server currently has the least
> work. That is the best algorithm on this page — *when its information is
> perfect*. Give it a view of server load that is **250 ms out of date** and it
> becomes **2.9× worse than choosing at random**. Point it at a fleet where one
> backend has started failing instantly instead of working, and it sends that
> backend **66%** of all traffic. Its more sophisticated cousin, which balances
> on observed response time, sends it **100%**.

**Lab contents**

| File | What it is |
|---|---|
| `README.md` | this document — 16 stages, four depth layers, 6 prediction checkpoints |
| `visualization.html` | **Eight Lanes** — race two balancers against each other on the identical traffic, then break a server |
| `EXERCISES.md` | eight rungs, pencil-and-paper plus the visualization |
| `SOLUTIONS.md` | worked answers |

---

## Where this sits in your other labs

**Nine** of your labs named this one as the thing they were missing —
`load-shedding`, `consistent-hashing`, `distributed-rate-limiting`, `caching`,
`networking-for-developers`, `software-architecture-patterns`,
`amazon-web-services`, `uber-system-design` and `whatsapp-system-design` — which
is more than any other open topic in your map, and why `LEARNING-PATH.md` has had
it at the top of the queue. They arrived at it from four different directions,
and it is worth knowing which of your existing results this lab is standing on.

- **`queueing-theory`** gave you the two facts this entire lab is made of. First,
  `W = 1/(μ−λ)` — waiting is set by *spare* capacity, not capacity, and it has a
  vertical asymptote. Second, and much more directly: it measured **four servers
  sharing one line at ρ = 0.8 → 60 ms of queueing**, where a single server at
  the same 80 ms service time and the same 0.8 utilisation waits **320 ms**.
  That lab presented pooling as a curiosity in passing. **This lab is that one
  measurement turned into a subject**, because "eight servers behind a load
  balancer" is not one shared line — it is eight separate lines, and the whole
  job of the balancer is to fake the shared one.
- **`load-shedding`** (yesterday) is the layer *below* this one and shares its
  standing example. There, one service — four workers, 80 ms each, 50 requests a
  second, clients giving up after a second — was pushed past capacity and kept
  alive by a doorman. **In this lab that exact service is one of eight backends.**
  Shedding decides *whether* a request gets in; balancing decides *which door* it
  walks up to. Its closing section named `/learn load-balancing` as the highest-
  value next step, and stage 13 here closes the loop by showing what happens when
  you install a balancer and a shedder that disagree.
- **`consistent-hashing`** answered "which machine owns this key" and warned that
  a **hot key** is unsolvable by any hashing scheme because every scheme agrees
  one key has one owner. Stage 11's F5 is that warning with a load balancer's
  numbers on it: hashing sessions to backends looks like balancing and is not.
  Its mention of **bounded loads** as "a load balancer's tool, never a database's"
  gets explained here.
- **`distributed-rate-limiting`** listed four siblings needed to survive —
  timeout, breaker, bulkhead, shedding — and put load balancing on its "next"
  list twice. Its central lesson, *the only real dial is how stale each
  enforcement point's view of shared state may be*, turns out to be **the exact
  same dial** here, and stage 10 measures what it costs in this setting.
- **`caching`**, **`networking-for-developers`**,
  **`software-architecture-patterns`**, **`amazon-web-services`**,
  **`uber-system-design`** and **`whatsapp-system-design`** all assume a load
  balancer exists and none say what it does. `io-multiplexing` is the one that
  explains what a single backend is doing *inside* the box while its queue
  builds.

**New here:** why N queues are not one queue and what that costs; the four
dispatch rules and the specific condition each one is a bet on; the power-of-two-
choices result and why it beats better information; the failure magnet; and the
measured discovery that a *sicker* backend attracts *more* traffic under every
load-aware rule.

**Not here, deliberately:** the packet-level mechanics of how a balancer forwards
bytes (NAT, direct server return, connection tracking) get one production
section, not a treatment — this lab is about the *decision*, not the plumbing.
Autoscaling is out (`load-shedding` explains why it is a different lever with a
different time constant). Global traffic management across regions gets a
production paragraph and is a lab of its own.

---

## How the numbers in this document were produced

Every measured figure below comes from one discrete-event simulator: an arrival
stream, a set of backends each with its own workers and its own FIFO queue, a
dispatcher with a configurable and deliberately imperfect view of backend state,
and pluggable failure injection. It was run headlessly on JavaScriptCore.

Unless a table says otherwise, every figure is the **mean of six seeds** over
**ten minutes of simulated time**, discarding the first minute.

**The identical engine is embedded in `visualization.html`**, and given the same
run length and seed count it reproduces every figure in this document to the
digit — that was checked, not assumed. The page's *sweep buttons* deliberately
run shorter (five simulated minutes, three seeds) so that a press returns in
about twenty seconds rather than a minute; those results land within roughly 1%
of the tables here, except very close to saturation where the honest caveat
below applies.

Before any teaching number was taken from it, the engine was checked against the
cases where queueing theory already knows the exact answer — including the
specific figures your own `queueing-theory` lab established.

| What was checked | Known answer | Simulator | |
|---|---|---|---|
| M/M/1, ρ = 0.5, S = 80 ms | 160 ms in system | 160.7 ms | ✅ |
| M/M/1, ρ = 0.8 | 400 ms | 398.6 ms | ✅ |
| M/M/1, ρ = 0.9 (20 seeds, 60 min) | 800 ms | 803.5 ms | ✅ |
| M/D/1, ρ = 0.5 (identical service times) | 120 ms | 119.7 ms | ✅ |
| M/D/1, ρ = 0.8 (20 seeds, 30 min) | 240 ms | 235.0 ms | ✅ (−2%) |
| D/D/1 (perfectly regular everything) | 80 ms, no waiting | 80.0 ms | ✅ |
| **M/M/4, one shared line, ρ = 0.8** | 140 ms (`queueing-theory`'s 60 ms wait + 80 ms work) | 139.8 ms | ✅ |
| Kingman's approximation, c²ₛ = 4, ρ = 0.8 | ≈ 880 ms | 868.2 ms | ✅ |

**Two honest caveats.**

*One.* Near saturation a ten-minute run genuinely scatters — that is a property
of nearly-full queues, not a defect in the engine, and it is why every figure is
a multi-seed mean. Where a backend is pushed **past** capacity its queue never
stops growing, so any "mean latency" for it is a statement about the length of
the run rather than about the system. Stage 10 shows this explicitly by running
the same scenario over three different horizons.

*Two.* This is a model of dispatch and queueing. It does **not** model TCP,
connection setup, TLS handshakes, kernel scheduling, or the fact that a real
server gets slower — not just busier — as its queue grows. Those all make the
real numbers worse than these, never better. Where a claim is **reasoning** or
comes from a **published source** rather than from this simulator, it says so.

---

# BEGINNER MODEL — stages 1 to 6

## 1. WHY — the outage where every server was fine

It is 14:00. You run a checkout API on **eight identical servers**. Each one can
finish about **50 requests a second**. Eight of them is 400 a second, and you are
receiving 320 — **80% of what you can do**, which is a load level most teams
would describe as comfortable.

You get paged. The mobile app is timing out for a noticeable slice of customers.

You open the dashboard, and this is what makes the incident memorable:

- CPU on all eight servers: between 74% and 86%. Nothing near a limit.
- Error rate: zero. Nothing is throwing.
- Requests per second, per server: 40, 40, 40, 40, 40, 40, 40, 40. Perfectly even.
- Mean response time across the fleet: 140 ms. Your SLO is 500.
- **99th percentile response time: 561 ms.** Your SLO is 500.

Nothing is broken. Every server is healthy, every server is getting exactly its
fair share, and about one request in a hundred is still missing its deadline.
There is no faulty machine to reboot and no bad deploy to roll back.

Now the part that should feel wrong. Take those same eight servers, the same
320 requests a second, change **nothing** about the code or the hardware, and
have all eight of them feed from a **single shared queue** — one line, eight
counters, the way a bank does it rather than the way a supermarket does it.

| Same fleet, same traffic, ρ = 0.8 | mean | p99 |
|---|---:|---:|
| Eight separate queues, request sent to a random server | 139.9 ms | 561.5 ms |
| **One shared queue feeding all eight** | **82.1 ms** | **371.5 ms** |

The p99 goes from a breached SLO to a comfortable one. No new server, no faster
code. The only thing that changed is **where the line forms**.

That is what load balancing is for. Not "spreading load" — the load was already
perfectly spread, 40 requests a second to every server, and the fleet was still
missing its SLO. The job is subtler than spreading, and stage 4 is where the
difference becomes visible.

> **A note on the p99 numbers before they confuse you.** Even at 30% load the
> p99 in this lab is around 368 ms, against a mean service time of 80 ms. That
> is not queueing — that is the *service time itself*. Requests here take 80 ms
> **on average**, exponentially distributed, so one in a hundred naturally takes
> 80 × ln(100) ≈ 368 ms of pure work with nobody waiting at all. Treat ~370 ms
> as the floor. Everything above it is queueing, and queueing is the only thing
> a load balancer can do anything about.

---

## 2. THE CONCRETE PROBLEM — eight checkouts and one customer

Give the eight servers names: **A, B, C, D, E, F, G, H**. Each has four worker
threads and takes about 80 ms per request, so each can finish 50 a second.

A customer's request arrives at 14:00:00.000. Something has to decide, in
microseconds and with no time to think, which of A–H it goes to. That decision
is made by a **load balancer**: a box (or a library, or a kernel routing rule)
that sits in front of the eight and forwards each arriving request to one of
them.

Here is the state of the fleet at the exact instant our request arrives —
these are real queue depths lifted from a run at ρ = 0.9:

```
   A       B       C       D       E       F       G       H
 ██████  ░       ███     ██████████████  ░       ██      ░       ████
   6       0       3            14        0       2       0        4
```

Thirty-five requests are waiting across the fleet. Three servers are **idle**.
One server has **fourteen** requests queued, which at 80 ms each and four workers
is about 280 ms of waiting before its next arrival is even looked at.

This is the whole subject in one picture. If our customer is sent to D, they wait
280 ms before work starts. If they are sent to B, they wait zero. Nothing about
the fleet is unhealthy; the *average* queue is 4.4 deep, which is fine. The
problem is the **spread**, and a customer does not experience the average.

**Why is D so far ahead of B?** Nobody did anything wrong. Requests arrive at
random moments and take random amounts of time. Over a few seconds, some servers
get unlucky twice in a row and others get a gap. Randomness clumps. That is all.

> **The analogy, and it is only an entry point.** This is a supermarket. Eight
> checkouts, and you have to pick a line the moment you walk up, with a trolley
> you cannot re-park. Everyone knows the feeling of picking the line that
> stalls. The bank across the road solved it decades ago: one line, and the next
> free teller takes the front of it. The bank's arrangement is strictly better
> and everybody knows it. **The rest of this lab is about why a web service
> cannot simply have the bank's arrangement, and how close it can get.**

---

## 3. THE OBVIOUS SOLUTION — take turns

The first idea any engineer has, and it is a genuinely good one: **round robin.**
Keep a counter. Send request 1 to A, request 2 to B, … request 8 to H, request 9
back to A. Perfectly even. Impossible to argue with.

It has real virtues, and they are not small:

- It needs **no information at all** about the servers. No health data, no load
  data, no round trip to ask. One integer.
- It is **exactly fair in counts** — over any window, every server gets 1/8 of
  the requests, to within one.
- It is trivially cheap and it is what almost every load balancer ships with as
  the default. NGINX's default is round robin. HAProxy's `roundrobin` is its
  most-used algorithm.

And it *does* work. Measured against random dispatch at ρ = 0.9:

| ρ = 0.9, 8 backends | mean | p99 | worst queue seen |
|---|---:|---:|---:|
| random | 244.0 ms | 994.9 ms | 80 |
| **round robin** | **156.8 ms** | **605.7 ms** | **42** |
| one shared queue (the unreachable ideal) | 91.5 ms | 384.1 ms | — |

Round robin removes about half the penalty that random dispatch pays. It is a
real improvement obtained for the price of one counter. So why does the story
not end here?

> ### 🔮 Predict — checkpoint 1
>
> Suppose you removed *all* the randomness from the work itself: every single
> request takes **exactly** 80 ms, no variation whatsoever. Arrivals are still
> unpredictable, as they always are, but the work is perfectly uniform.
>
> Under those conditions, how close does round robin get to the shared-queue
> ideal? Within 50%? Within 10%? Commit to a number before reading on.

---

## 4. FAILURE — round robin is a bet, and you can watch it lose

Here is the answer to the prediction, measured at ρ = 0.8, varying only how
much request *sizes* vary. The measure of that variation is **c²ₛ**, the squared
coefficient of variation of service time, which you already have from
`queueing-theory`: 0 means every request is identical, 1 is the ordinary
exponential case, 16 is a service where a small tail of requests takes many
times longer than the rest.

| c²ₛ | what it means | random p99 | **round robin p99** | ideal (one queue) p99 |
|---:|---|---:|---:|---:|
| 0 | every request takes exactly 80 ms | 277.7 ms | **99.7 ms** | 99.7 ms |
| 1 | ordinary variation (exponential) | 561.5 ms | 429.5 ms | 371.5 ms |
| 4 | some requests much bigger | 1,324.9 ms | 1,231.2 ms | 855.8 ms |
| 16 | a heavy tail — 3% of requests cost 16× | 3,847.2 ms | **3,863.0 ms** | 1,460.0 ms |

Read the two ends of the round-robin column, because that is the finding.

**At c²ₛ = 0, round robin ties the ideal exactly: 99.7 ms against 99.7 ms.** It
is not an approximation of the shared queue — under identical work it *is* the
shared queue. Sending every 8th request to A when every request costs the same
is precisely equivalent to A taking the front of one shared line.

**At c²ₛ = 16, round robin is 3,863 ms and pure random dispatch is 3,847 ms.
Round robin has become slightly worse than not thinking at all**, and both are
2.6× the ideal.

So round robin is not an algorithm with a quality. It is a **bet**: *all requests
cost about the same*. It pays out in exact proportion to how true that bet is,
and in a real HTTP service it is usually not very true. `GET /health` and
`POST /checkout` do not cost the same. A cache hit and a cache miss do not cost
the same. A customer with 3 items and a customer with 300 do not cost the same.

### The second, deeper failure — and it is not about request sizes at all

Look again at the c²ₛ = 0 row: random dispatch is **277.7 ms** where the ideal is
**99.7 ms**. Every request costs exactly 80 ms. There is no variation in the work
whatsoever. And random dispatch is still 2.8× off the ideal.

Where does that come from? **Arrivals clump even when work does not.** In any
short window, some servers get three requests and others get none, purely by
chance — the classic balls-in-bins effect. Round robin fixes this one, which is
exactly why it ties the ideal in that row. But it tells you something important
about what the enemy actually is:

> There are **two independent sources of imbalance** — randomness in *where
> requests go*, and randomness in *how big they are* — and a dispatch rule has
> to defeat both. Round robin defeats the first completely and the second not at
> all. Random defeats neither.

### The third failure, and it is the one that pages you

Both failures above cost you percentiles. This one costs you the service.

Take the eight-server fleet at ρ = 0.8 and make **server A five times slower**
than the rest — a bad disk, a noisy neighbour on the hypervisor, a GC pathology,
a config that didn't roll back cleanly. A **gray failure**: A still answers, still
passes any health check that just asks "are you there", and is simply five times
slower. Round robin, knowing nothing, sends it exactly 12.5% of traffic as always.

| ρ = 0.8, server A is 5× slow | share sent to A | fleet mean | fleet p99 |
|---|---:|---:|---:|
| round robin | 12.5% | 8,629 ms | **330,967 ms** |
| random | 12.5% | 8,692 ms | 332,577 ms |
| least connections | 3.0% | 102.7 ms | 571.0 ms |
| power of two choices | 3.1% | 131.5 ms | 978.2 ms |

A p99 of **331 seconds**. Not milliseconds.

Do not read that number as a measurement of anything, because it is not one — it
is a statement about how long the simulation ran. Server A at 5× service time can
finish 10 requests a second. Round robin sends it 40. Its queue grows by 30
requests every second forever. Run it longer and the number just gets bigger:

| simulated horizon | round robin, fleet p99 |
|---:|---:|
| 5 minutes | 172,782 ms |
| 10 minutes | 330,000 ms |
| 20 minutes | 652,294 ms |

It doubles when the window doubles. That is the signature of an unbounded queue —
`queueing-theory`'s ρ ≥ 1 cliff, arrived at not because the fleet was overloaded
(the fleet is at 80%) but because **the dispatcher insisted on giving a sick
server a healthy server's share of the work.**

And this is 12.5% of your customers. Not 12.5% slower — 12.5% *never answered*.
The fleet has seven perfectly good servers with spare capacity sitting right
next to the queue that is eating your traffic.

---

## 5. THE REAL REQUIREMENT — say it in plain words first

Three failures, one underlying demand. Before any vocabulary, here it is in
plain language:

> **No request should be made to wait behind a long queue while a short queue
> exists.**

That is the whole requirement. Notice what it does *not* say.

- It does not say "give every server an equal number of requests." That is what
  round robin guarantees and it was not enough — it is what your dashboard showed
  during the stage-1 outage while the SLO was breached.
- It does not say "give every server an equal amount of work." That is closer,
  but it is still a statement about servers. Your customer does not care whether
  the fleet is balanced. They care whether *their* request is behind a wall.
- It says nothing about efficiency, throughput, or utilisation. **A load
  balancer cannot change how much work gets done** — all eight servers are
  finishing requests as fast as they can either way. It can only change *who
  waits*.

Stated as an engineering property, the requirement is about **variance, not
mean**. The mean queue length is fixed by arithmetic: total work in, total
capacity available. `queueing-theory`'s Little's Law says so and it is an
identity, not something an algorithm can argue with. The only thing a dispatch
rule controls is how that unavoidable total is **distributed across the eight
queues**, and the closer to equal it keeps them, the closer the fleet gets to
behaving like one big server.

Which gives the sharp version:

> **A load balancer's job is to make N separate queues behave as much like one
> shared queue as it can, using information it does not really have.**

The second half of that sentence is where the next ten stages live.

---

## 6. THE KEY IDEA — stop guessing and look

If the problem is that some queues are long while others are short, the fix
writes itself: **before dispatching, look at the queues, and send the request to
the shortest one.**

That is it. That is the central idea of load balancing, and the rest of the
subject is consequences.

It has an immediately attractive property that round robin lacks. Round robin
had to *assume* all requests cost the same. Looking at the queue does not assume
anything: a server that got three expensive requests has a long queue, and a
server that got three cheap ones has a short queue, and the rule sees the
difference without ever being told what a request costs. **A measured signal
beats an assumed one.**

Measured at ρ = 0.9, sending every request to the server with the fewest
outstanding requests:

| ρ = 0.9 | mean | p99 | worst queue |
|---|---:|---:|---:|
| random | 244.0 ms | 994.9 ms | 80 |
| round robin | 156.8 ms | 605.7 ms | 42 |
| **fewest outstanding requests** | **93.9 ms** | **390.8 ms** | **9** |
| one shared queue (the ideal) | 91.5 ms | 384.1 ms | — |

**93.9 against an unreachable ideal of 91.5.** It captures 98% of the available
gap. It handles the 5×-slow server automatically (3.0% share instead of 12.5%,
because a slow server's queue stays long and so it stops being picked). It
handles heavy-tailed request sizes (c²ₛ = 16: 1,472 ms against an ideal of
1,460 ms, while round robin was at 3,863 ms).

It looks finished.

> **Checkpoint — what you can explain now.** You can say why a fleet where every
> server has identical CPU and identical request counts can still miss its SLO;
> why round robin is a bet on uniform request sizes and what it costs when that
> bet is wrong; and why "send it to the shortest queue" is not one heuristic
> among several but the direct expression of the requirement.
>
> **What the next layer adds.** Everything above assumed the dispatcher can see
> the true state of all eight queues, instantly and for free. It cannot. Stage
> 10 removes that assumption one piece at a time, and the algorithm that is
> essentially optimal above becomes, under entirely ordinary conditions, **worse
> than random**.

---

# INTERMEDIATE MODEL — stages 7 to 9

## 7. TERMINOLOGY — six words, and two pairs that get confused

You have now met every idea in this lab at least once. Here are the names, with
the distinctions that actually carry weight.

### Backend (also: upstream, real server, origin, target, endpoint, pod)

**Plain meaning.** One of the servers behind the balancer. A, B, C … H.

**Precise meaning.** An independently queueing unit of capacity. That last phrase
is the load-bearing one: what makes something a backend is not that it is a
machine but that it has **its own queue** which the balancer cannot see into and
cannot reach across.

**Why the distinction matters.** It tells you where the real backend boundary is,
and it is often not where the org chart says. Eight pods on one node that share
a CPU are eight backends to the balancer and roughly one queue in reality. One
pod with 200 threads is one backend with a large internal shared queue. If two
"backends" share a bottleneck, balancing between them buys you nothing, and this
is the most common reason a balancer change produces no improvement.

**Vocabulary you already have:** `consistent-hashing` called these *nodes*;
`microservices` called them *instances*; Envoy calls the set of them a *cluster*
and each one a *host*; NGINX calls the set an *upstream*.

### Dispatch rule (also: algorithm, balancing policy, `lb_policy`)

**Plain meaning.** The rule that picks which backend gets this request.

**Precise meaning.** A function from *(the dispatcher's view of backend state,
some randomness, the request itself)* to one backend. The three inputs are the
three axes on which all the algorithms in this lab differ, and the first one —
**the view** — is where all the difficulty is.

### Utilisation, ρ (rho)

You have this from `queueing-theory`: arrival rate ÷ service capacity. Here it
always means the **fleet's** ρ unless it says otherwise. 320 requests a second
against a fleet that can do 400 is ρ = 0.8. The individual backends' ρ can be —
and in every interesting scenario in this lab *is* — wildly different from the
fleet's.

### The view, and staleness (τ)

**Plain meaning.** What the dispatcher believes about each backend, and how old
that belief is.

**Precise meaning.** Three genuinely different situations, and mixing them up is
the single most common source of wrong reasoning about load balancers:

| view | what the dispatcher knows | where you actually find it |
|---|---|---|
| **exact** | the true, current state of every backend | only if there is one dispatcher and it is the sole source of traffic |
| **stale (τ)** | a snapshot of the truth, refreshed every τ ms | any design that gossips or scrapes load stats between nodes |
| **local** | only the requests *this dispatcher* has outstanding | every real multi-instance load balancer, by default |

The third is the normal case and it is the one people forget. If you run 20
NGINX instances with `least_conn`, **none of them knows the fleet's least-loaded
backend** — each knows only the least-loaded of its own 1/20th share. Stage 10
measures what that costs.

### The two pairs that get confused

**Load balancing vs. sharding (partitioning).** Both send a request to one of N
machines. The difference is whether the choice is **free**.

- *Balancing*: any backend can serve this request, so the dispatcher may choose
  on the basis of load. Choice is free; use it.
- *Sharding*: only one machine has the data (or the lock, or the session), so
  the destination is determined by the key. Choice is not free; load is whatever
  the key distribution makes it.

This is exactly the boundary `consistent-hashing` was working on, seen from the
other side. That lab was about making the sharding function well-behaved when
the machine set changes. **When you enable sticky sessions on a load balancer,
you are converting a balancing problem into a sharding problem** — and inheriting
every hot-key problem that lab described. F5 in stage 11 puts numbers on it.

**Load balancing vs. load shedding.** From `load-shedding`, sharpened:

- *Balancing* decides **which** queue a request joins. It assumes the request is
  going to be served and only asks where. It cannot help you when ρ ≥ 1, because
  at that point every queue is growing and there is no good one.
- *Shedding* decides **whether** a request joins any queue at all. It is the only
  tool that works when the fleet genuinely cannot do the work.

The clean statement: **balancing buys you the last 15% of your fleet's usable
capacity; shedding is what happens after that.** They are complements and they
are usually the same box, which is why they get conflated. Stage 13 shows what
goes wrong when they are configured without reference to each other.

### Two terms you will meet in stage 10, defined when earned

*Herd* (or stampede, or thundering herd) and *power of two choices*. Both are
introduced at the moment the problem that names them appears.

---

## 8. MENTAL MODELS

### The simple model: the supermarket and the bank

Eight checkouts. You must pick a line on arrival and you cannot switch. Some
customers have three items, some have three hundred, and you cannot tell from
behind.

The bank across the road has one line and eight tellers. When a teller frees up,
the person at the front walks over. Nobody ever waits behind a 300-item trolley
while a teller stands idle.

**The bank arrangement is strictly better and it is not close.** The measured gap
between the two, on identical hardware and identical traffic, is stage 1's table:
2.7× the mean and 2.6× the p99 at ρ = 0.9.

So why does every web service use the supermarket? Because the bank's one line
requires the *tellers* to pull work when they are free, and a backend server
cannot pull — the network only goes one way. The request has to be sent
somewhere before anyone knows who will be free. **A load balancer is a
supermarket trying to behave like a bank, using only what it can see from the
door.**

### The engineering model: N queues and a function with bad eyesight

```
                    ┌─────────────────────────────────────┐
   arrivals  ───►   │  DISPATCHER                          │
   λ req/s          │    view v[] : belief about each      │
                    │              backend, age τ          │
                    │    rule f(v, random, request) → i    │
                    └───────┬──────┬──────┬────────────────┘
                            │      │      │
                   ┌────────▼┐  ┌──▼─────┐│  ┌─────────┐
                   │ queue A │  │queue B ││  │ queue H │   … 8 of them
                   │ ██████  │  │        ││  │ ████    │
                   ├─────────┤  ├────────┤│  ├─────────┤
                   │ w w w w │  │w w w w ││  │ w w w w │   4 workers each
                   └─────────┘  └────────┘└──└─────────┘
                    μ = 50/s      μ = 50/s     μ = 50/s
```

The state that matters is small:

- `q[i]` — requests waiting at backend i, plus those being worked on. The truth.
- `v[lb][i]` — what dispatcher `lb` *believes* `q[i]` is. What the rule can use.
- `τ` — how far behind `v` runs. Zero only in textbooks.
- `μ[i]` — how fast backend i actually is. Not necessarily the same for all i,
  and not necessarily constant in time.

The rule is one line of code. Every algorithm in this lab differs only in it:

```
random          →  i = randInt(n)
round robin     →  i = counter++ % n
least conn      →  i = argmin over all n of v[i]
power of two    →  a = randInt(n); b = randInt(n); i = v[a] < v[b] ? a : b
```

And here is the frame that makes the rest of the lab predictable:

> **Every dispatch rule is a bet about the world, and its performance is exactly
> how right that bet is.**
>
> - *random* bets nothing, and gets nothing.
> - *round robin* bets **all requests cost the same**.
> - *least connections* bets **my view of the queues is true**.
> - *power of two* bets **almost nothing, and only about two backends at a time**.
>
> The order of that list is not increasing quality. It is increasing *exposure*.
> Stage 10 collects on each bet in turn.

### Mapping the two models

| supermarket | engineering model | measured in this lab |
|---|---|---|
| eight checkouts | eight backends, each with own queue | the standing 8 × 4-worker fleet |
| picking a line at the door | the dispatch rule `f` | stages 3, 6, 10 |
| glancing down the aisles | the view `v` | stage 10, v3 |
| the view being out of date | staleness τ | stage 10, the herd |
| trolley size you cannot see | service-time variability c²ₛ | stage 4's table |
| one till with a broken scanner | a slow backend (`μ[i]` small) | stage 4's 331-second p99 |
| the bank's single line | shared queue / M/M/k | the "ideal" column everywhere |

### Where the supermarket analogy breaks — four places, all load-bearing

**1. You can see all eight lines. A load balancer usually cannot.** This is the
single biggest lie in the analogy, and it is the subject of stage 10. A customer
gets a free, instant, perfectly accurate view of every queue. A dispatcher gets a
view that is late, partial, or both — and the whole difficulty of load balancing
is that acting confidently on a bad view is worse than acting humbly on none.

**2. There is only one of you. There are usually many dispatchers.** In the
supermarket, when you pick the shortest line you are the only person making that
decision at that instant. If two hundred customers walked in simultaneously and
all picked "the shortest line", they would all pick the *same* line. That is the
herd, and it does not exist in the analogy but it dominates the engineering.

**3. A checkout does not get slower when the queue is long. A server does.**
Deeper queues mean more memory held, more context switching, worse cache
locality, longer GC pauses, more connection state. Real backends have a
`μ` that *falls* under load, which turns "slightly overloaded" into "collapsing"
faster than any model here shows. `queueing-theory` and `load-shedding` both
measured versions of this. **Every number in this lab is therefore optimistic.**

**4. A checkout is either open or closed. A server has a third state.** It can be
*answering instantly and uselessly* — returning 500s in one millisecond. To every
load-aware rule in this lab, that looks exactly like the fastest, emptiest,
healthiest server in the fleet. Stage 11's F1 measures the consequence and it is
the most surprising number in the lab.

---

## 9. VISUALIZATION — what to open and what to look for

Open **`visualization.html`**. It runs the same engine as this document — the
live panels show a rolling window of the last few thousand requests, and the
three *sweep* buttons re-run the measurements behind the tables above.

It is built around one deliberate choice: **two dispatch rules race on the
identical arrival stream.** Same requests, same sizes, same arrival times — the
only difference is the rule. When the two panels diverge, nothing but the
decision caused it.

Four tabs, in the order this document introduces them:

1. **The eight lanes** — watch queues form in real time. Pick a rule, pick a load
   level, and watch the bars. *What to look for:* at 50% load every rule looks
   identical and the bars stay short; at 90% the random and round-robin lanes
   develop tall spikes that persist for seconds while other lanes sit empty. The
   number to watch is not the average bar height — it is **the gap between the
   tallest and the shortest.**

2. **Race two rules** — the same traffic, two rules, side by side, with a running
   p99 for each. *What to look for:* run round robin against least-connections
   with the "request sizes vary" slider at its lowest setting and they tie. Push
   the slider up and watch round robin come apart while least-connections barely
   moves. You are watching a bet lose.

3. **The stale view** — the herd, made visible. There are two sets of bars: what
   is **true** and what the dispatcher **believes**. *What to look for:* with the
   staleness slider at 0 they are the same picture. Push it to 250 ms and watch
   the dispatcher pour every arrival into whichever lane was empty at the last
   refresh, long after it has stopped being empty. The lanes take turns being
   crushed. This is the one animation in the lab that explains something words
   struggle with.

4. **Break a server** — failure injection. Make one backend slow, or make it fail
   instantly, and watch the share of traffic each rule sends it. *What to look
   for:* the direction flips. Under "slow", the load-aware rules protect you and
   round robin destroys you. Under "fails instantly", the load-aware rules feed
   the corpse and round robin is the safest thing on the page.

Each tab has a **predict before you run** panel. Use it. The stage-11 results in
particular are much more memorable if you have committed to a wrong number first.

---

# ENGINEERING MODEL — stages 10 to 12

## 10. THE ACTUAL MECHANISM — four rules, each forced by the failure of the last

We now build the dispatcher for real. Each version is the answer to something
that broke in the previous one.

**The standing setup for everything below**, unless stated: eight backends, four
workers each, 80 ms mean service time, exponentially distributed. Each backend
can finish 50 requests a second; the fleet can finish 400. `ρ = 0.9` means 360
requests a second arriving.

---

### v1 — choose at random

```
i = randInt(8)
```

Stateless. No coordination. Correct with any number of dispatchers, any fleet
size, no configuration, no failure modes of its own. It is also what you get for
free from DNS round robin, from ECMP hashing in a router, and from any hash of
the client's address — so it is much more widely deployed than anyone chooses to
deploy it.

| ρ = 0.9 | mean | p50 | p95 | p99 | worst queue |
|---|---:|---:|---:|---:|---:|
| random | 244.0 | 184.5 | 672.2 | 994.9 | 80 |
| ideal (one queue) | 91.5 | 68.5 | 254.7 | 384.1 | — |

**2.7× the mean and 2.6× the p99 of the ideal.** Where does it go? Entirely into
imbalance. At any instant some backends have queues of 15 and others are idle,
and the ones that are idle are wasted capacity you paid for.

**What breaks v1:** nothing dramatic. It just wastes about 60% of the difference
between "eight servers" and "one server eight times bigger". It is the baseline,
not a failure.

---

### v2 — take turns

```
i = counter++ % 8
```

One integer of state. Removes randomness from *placement* — every backend gets
exactly one request in eight.

| ρ = 0.9 | mean | p99 | worst queue |
|---|---:|---:|---:|
| random | 244.0 | 994.9 | 80 |
| **round robin** | **156.8** | **605.7** | **42** |
| ideal | 91.5 | 384.1 | — |

It recovers roughly 40% of the gap. And, from stage 4, when every request costs
exactly the same it recovers **all** of it — 99.7 ms against an ideal of 99.7 ms.

**What breaks v2 — three things, in increasing order of severity.**

*(a) Request sizes vary, and that is the normal case.* From stage 4: at c²ₛ = 16
round robin (3,863 ms) is indistinguishable from random (3,847 ms). Round robin
balances *counts*, and counts are only a proxy for work when work is uniform.

*(b) Backends are not identical.* Fleets are heterogeneous more often than
anyone admits — mixed instance types after a capacity top-up, one node on
degraded hardware, one node in a different AZ with different network latency, one
node that has just started and has not JIT-compiled anything yet. Round robin
sends every one of them exactly 1/8 of the traffic. Stage 4's 5×-slow server
produced an unbounded queue on a fleet running at 80%.

*(c) The deploy case, which is (b) but happens to everyone, every week.* Take
the eight-backend fleet at ρ = 0.8 and restart one backend. For its first two
minutes it runs 5× slow — a cold JVM with an interpreted hot path, empty caches,
a connection pool still filling. Measured over that two-minute window:

| ρ = 0.8, backend A cold for 2 minutes | share sent to A | p99 | p99.9 | worst |
|---|---:|---:|---:|---:|
| **round robin** | **12.5%** | **76,293 ms** | 88,633 ms | 89,968 ms |
| power of two choices | 3.1% | 1,002.6 ms | 1,913.4 ms | 3,244.1 ms |
| least connections | 3.0% | **580.3 ms** | 1,524.9 ms | 2,989.1 ms |

**A 76-second p99 caused by a routine restart**, because the dispatcher insisted
on giving a machine that could do 10 requests a second exactly 40 of them. The
load-aware rules noticed within a few hundred milliseconds and backed off to
3%. This is not an exotic failure. It is Tuesday.

---

### v3 — look at the queues

```
i = argmin over all backends of v[i]      // v[i] = outstanding requests
```

Called **least connections** in every product that ships it (`least_conn` in
NGINX, `leastconn` in HAProxy, `LEAST_REQUEST` in Envoy — Envoy's name is the
honest one, and stage 14 explains why).

Assume for now the view is perfect: one dispatcher, instant and exact knowledge
of all eight queues.

| ρ = 0.9, exact view | mean | p99 | worst queue |
|---|---:|---:|---:|
| random | 244.0 | 994.9 | 80 |
| round robin | 156.8 | 605.7 | 42 |
| **least connections** | **93.9** | **390.8** | **9** |
| ideal | 91.5 | 384.1 | — |

**It captures 98% of the gap.** And unlike round robin it makes no assumption
about request sizes, so it survives the c²ₛ table (1,472 ms at c²ₛ = 16, against
an ideal of 1,460 and round robin's 3,863) and it survives the slow backend
(3.0% share) and the cold restart (580 ms p99).

Why does counting *outstanding requests* work so well when counting *dispatched
requests* worked so badly? Because outstanding is a **feedback** signal. A
request leaves the count when the backend finishes it, so a backend that is slow
for any reason at all — big requests, slow disk, cold cache, a neighbour stealing
CPU — accumulates outstanding requests and stops being chosen. **The dispatcher
never needs to know why.** That is the whole reason to prefer measurement over
assumption, and it is a principle far bigger than load balancing.

> ### 🔮 Predict — checkpoint 2
>
> Least connections is within 3% of a theoretical optimum you cannot otherwise
> reach. Before reading on: what is the **smallest, most ordinary change** to the
> situation that would make it perform *worse than choosing at random*?
>
> Not a bug, not a broken server, not a pathological workload. Something present
> in essentially every production deployment.

---

### What breaks v3 — the answer is: you have more than one load balancer, or any delay at all

The perfect view in the table above required **one dispatcher** with **instant**
knowledge. Remove either assumption.

#### Break 1 — the view is τ milliseconds old

Real dispatchers do not have a live feed of every backend's queue. They have
statistics, and statistics arrive on a schedule: Envoy's load-report interval,
a Kubernetes metrics scrape, a gossip round, a health-check response carrying a
load header. Call the age of the view **τ**.

Everything else identical, ρ = 0.9, sweeping τ:

| view age τ | least connections: mean | p99 | worst queue |
|---:|---:|---:|---:|
| 0 ms (perfect) | 93.9 | 390.8 | 9 |
| 10 ms | 106.2 | 409.9 | 15 |
| 50 ms | 196.2 | 614.6 | 36 |
| **100 ms** | **330.7** | 942.2 | 56 |
| 250 ms | 708.4 | 1,915.7 | 117 |
| 500 ms | 1,274.9 | 3,497.2 | 205 |
| 1,000 ms | 2,335.7 | 6,606.9 | 382 |
| *(random dispatch, for comparison)* | *244.0* | *994.9* | *80* |

Read the bolded row against the italic one, and be precise about which statistic
crosses over when. **At τ = 100 ms least connections is already worse than random
on the mean** (330.7 against 244.0), though its p99 is still marginally better.
**By τ = 250 ms it is worse on both** — 708.4 against 244.0 on the mean, which is
**2.9× worse than a dispatcher that knows nothing at all and admits it**, and
1,915.7 against 994.9 on the p99. At τ = 1,000 ms it is 9.6× worse than random,
and 25× worse than itself with a perfect view.

**The mechanism, concretely.** At 14:00:00.000 the snapshot is taken. Backend E
happens to be empty. For the next 250 ms, `argmin` returns E for **every single
arrival** — there are 90 of them — because the view still says E is empty. E's
real queue goes 0 → 90 while the other seven drain to nothing. At 14:00:00.250
the new snapshot arrives, E is now catastrophically the worst, and the entire
stream swings onto whichever backend is now emptiest. And then does it again.

The fleet oscillates. Every backend takes a turn being crushed. **The average
utilisation is still exactly 90%** — the dashboard shows nothing — while the p99
is 5× what it should be.

This is a **herd** (also *stampede*, *thundering herd*): many independent
deciders, given the same information, making the same choice at the same moment,
and thereby destroying the thing they were choosing.

> **The general law, worth carrying out of this lab.** Stale load information is
> not merely less useful than fresh — it is **actively harmful, in a way that
> gets worse the more decisively you act on it.** A rule that commits fully to
> the best-looking option (`argmin`) converts a small delay into a large
> oscillation. This is a positive feedback loop, and it is the same shape as
> `load-shedding`'s F3 and the retry storms in `distributed-rate-limiting`.

#### Break 2 — there are many dispatchers, and none of them sees the fleet

Even with **zero** delay, a real deployment has several load balancers, and each
one knows only about **its own** outstanding requests. Twenty NGINX pods running
`least_conn` are twenty separate dispatchers, each perfectly informed about its
own 1/20th of the traffic and blind to the other 95%.

Same fleet, ρ = 0.9, each dispatcher tracking only its own requests:

| dispatchers | least connections mean / p99 | power of two mean / p99 | round robin mean / p99 |
|---:|---:|---:|---:|
| 1 | **93.9** / 390.8 | 108.6 / 413.9 | 156.8 / 605.7 |
| 2 | 97.3 / 394.4 | 116.7 / 427.7 | 163.1 / 638.5 |
| 4 | 104.6 / 406.4 | 125.3 / 441.9 | 159.6 / 604.5 |
| 8 | 126.6 / 439.6 | 132.6 / 459.7 | 166.6 / 622.6 |
| 16 | 178.1 / 535.0 | **147.0** / 494.4 | 169.6 / 644.0 |
| 32 | 292.4 / 774.8 | **159.5** / 531.2 | 173.7 / 639.8 |

Least connections degrades from 93.9 ms to 292.4 ms — a **3.1× loss purely from
horizontal scaling of the load balancer tier**, with the backends unchanged. And
it crosses over: **at 16 dispatchers, power of two is better; at 32 it is
1.8× better.**

Notice also that round robin barely moves (156.8 → 173.7). A rule that uses no
shared information cannot be damaged by not having it. That is not an argument
for round robin — it is 1.8× worse than the alternatives throughout — but it is
the reason round robin remains the sane default for people who do not know how
many dispatchers they will end up with.

> ### 🔮 Predict — checkpoint 3
>
> You need the quality of least-connections without its fragility. Two obvious
> repairs come to mind: **refresh the view faster**, or **share one view between
> all dispatchers**.
>
> Before reading on: what does each of them actually cost, and what is the fleet
> size at which each stops being possible?

---

### v4 — ask two servers, not eight

The two obvious repairs both fail for the same reason.

*Refresh faster* means every dispatcher polls every backend more often. That is
`dispatchers × backends × frequency` messages a second, and it grows with the
square of your fleet. Driving τ to 1 ms across 20 dispatchers and 200 backends is
4 million probes a second, and — worse — the probes themselves are requests,
which consume the capacity you are trying to measure.

*Share one view* means a coordination service in the path of every dispatch
decision. `distributed-rate-limiting` measured exactly this trade and the answer
is the same: **per-request coordination costs a round trip per request.** You
have added a network hop and a single point of failure to make a routing choice
whose entire value is a few hundred milliseconds of tail latency.

The actual repair is neither, and it is one of the most surprising results in
distributed systems.

```
a = randInt(8)
b = randInt(8)
i = v[a] < v[b] ? a : b
```

**Pick two backends at random. Send the request to whichever of the two has the
shorter queue.** Ignore the other six entirely.

That is the **power of two choices** (Azar, Broder, Karlin & Upfal 1994;
Mitzenmacher's 2001 survey *The Power of Two Choices in Randomized Load
Balancing* is the readable reference). At ρ = 0.9 with a perfect view:

| ρ = 0.9 | mean | p99 | worst queue |
|---|---:|---:|---:|
| random (`d = 1`) | 244.0 | 994.9 | 80 |
| **power of two (`d = 2`)** | **108.6** | **413.9** | **13** |
| least connections (`d = 8`) | 93.9 | 390.8 | 9 |
| ideal | 91.5 | 384.1 | — |

**Looking at two backends out of eight captures 90% of what looking at all eight
captures.** And the returns after the second are almost nothing:

| choices sampled `d` | mean | p99 | worst queue |
|---:|---:|---:|---:|
| 1 (random) | 244.0 | 994.9 | 80 |
| **2** | **108.6** | **413.9** | **13** |
| 3 | 100.2 | 399.9 | 12 |
| 4 | 96.2 | 392.0 | 10 |
| 8 (all of them) | 93.9 | 390.8 | 9 |

The step from one choice to two is worth 135 ms. The step from two to *all eight*
is worth 15 ms. **The first extra choice does 90% of the work that perfect
information does.**

#### Why it works, in one paragraph you can reconstruct later

With one random choice, a backend's queue length is decided by luck alone, and
luck has a long tail: the worst of `n` independent random draws grows like
`log n / log log n`. With two choices you only land on a long queue if **both**
your samples were long, which squares a small probability. That squaring
compounds at every step, and the worst queue length collapses from `log n /
log log n` to `log log n / log d`. That is a **doubly** logarithmic quantity — it
is essentially a constant for any fleet size you will ever operate. Going from
`d = 2` to `d = 3` only changes the denominator from `log 2` to `log 3`, which is
why the table above flattens instantly.

You can watch the theory in the measurements. Fleet size varying, ρ = 0.9,
one worker per backend so that queue depth is directly comparable:

| backends | random: worst queue | **power of two: worst queue** | least conn: worst queue |
|---:|---:|---:|---:|
| 4 | 58.5 | 18.5 | 14.8 |
| 8 | 58.0 | 11.0 | 7.8 |
| 32 | 82.5 | 7.5 | 3.0 |
| 128 | 85.5 | **6.8** | 1.0 |

**Random's worst queue grows as the fleet grows. Power of two's shrinks.** By 128
backends, random is 12.6× worse. This is the theoretical result made visible, and
it is the reason the technique is everywhere: NGINX ships it as `random two
least_conn`, and HAProxy as `balance random`, whose `random(<draws>)` defaults to
two draws. Envoy's default `lb_policy` is `ROUND_ROBIN`, and its `LEAST_REQUEST`
is implemented as **power of two choices with a configurable `choice_count`, not
as a full scan** — the documentation says so, and people who assume otherwise
mispredict its behaviour under staleness.

#### The part that matters more than the performance

Power of two is not chosen because it is 89% as good as least connections. It is
chosen because **it does not herd**, and that is a difference in kind rather than
degree.

The same staleness sweep, both rules, ρ = 0.9:

| view age τ | least connections mean | **power of two mean** | ratio |
|---:|---:|---:|---:|
| 0 ms | 93.9 | 108.6 | 0.86× |
| 50 ms | 196.2 | **118.8** | 1.65× |
| 100 ms | 330.7 | **130.4** | 2.54× |
| 250 ms | 708.4 | **159.9** | 4.43× |
| 500 ms | 1,274.9 | **207.8** | 6.14× |
| 1,000 ms | 2,335.7 | **295.5** | 7.90× |

At τ = 0, least connections wins by 14%. At τ = 500 ms, power of two wins by 6×.
**The crossover is at about 30 milliseconds of staleness**, which is less than
one metrics scrape, less than one gossip round, less than one health-check
interval — less, in most systems, than a single network round trip.

The reason is structural, not statistical. A herd requires that all dispatchers,
looking at the same stale picture, *reach the same conclusion*. `argmin` over all
eight does exactly that: there is one minimum and everybody finds it. Power of
two draws a **different random pair for every single request**, so two
dispatchers a microsecond apart are comparing different backends and cannot
agree on a victim. **The randomness is not a compromise forced by cost. It is the
mechanism that breaks the feedback loop.**

> This deserves to be the sentence you remember from this lab:
>
> **Using less information, less decisively, produced a system that survived bad
> information. The randomness was the feature.**

#### What breaks v4

Two things, and both are stage 11.

First, it does **not** fix the failure magnet — it only dilutes it (23.1% instead
of least connections' 66.1%, where a fair share would be 12.5%). Choosing a
broken backend less often is not the same as not choosing it.

Second, it says nothing about backends of different sizes, sessions that must
stay put, backends that have just started, or backends that are about to be
shut down. Those are v5.

---

### v5 — the four things a real balancer adds, and which failure each one answers

Every one of these exists because of something in stage 11. None of them is a
dispatch rule; they are all modifications to *which backends are candidates* and
*how much they count*.

**Weights — for fleets that are not uniform.** Give backend `i` a weight `w[i]`
and compare `v[i]/w[i]` instead of `v[i]`. A machine with twice the cores gets
twice the traffic. This is the same idea as `consistent-hashing`'s
weight-as-position-count, and it has the same trap: weights are static
configuration, and static configuration describes the fleet you had when you
wrote it. A weight of 2 on a node that is currently degraded is worse than no
weight at all. **Prefer a rule that measures over a weight that assumes** — which
is stage 6's principle applied to configuration.

**Health checking and outlier ejection — for backends that are broken.** A
separate loop from dispatch: probe each backend on a schedule, and remove
failing ones from the candidate set entirely. **This is the only mechanism in
the entire lab that addresses the failure magnet**, and stage 11's F1 shows that
no dispatch rule can substitute for it. *Outlier ejection* (Envoy's term; HAProxy
and NGINX have equivalents) is the passive version — instead of probing, watch
the real traffic and eject a backend whose error rate or latency is an outlier
against its peers. It catches things a synthetic probe never will, because it
tests the actual code path with actual data.

**Slow start — for backends that have just arrived.** Ramp a newly-healthy
backend from 0 to its full weight over some seconds rather than handing it a full
share immediately. v2's 76-second p99 on a routine restart is what this exists
to prevent. NGINX Plus and HAProxy both call it `slow-start`; Envoy calls it
`slow_start_config`; Kubernetes has no equivalent, which is why `readinessProbe`
tuning is such a recurring source of deploy-time latency spikes.

**Connection draining — for backends that are leaving.** Stop sending *new*
requests but let in-flight ones finish before the process exits. The absence of
this turns every deploy into a burst of 502s. It is the reason a graceful
shutdown handler and a `preStop` hook are not optional.

> **Checkpoint — what you can explain now.** You can derive the four dispatch
> rules from the failures that force them; you can say precisely why least
> connections degrades to worse-than-random under staleness and why power of two
> does not; and you can state the crossover conditions (τ ≈ 30 ms, or ≈ 16
> dispatchers) rather than repeating a preference.
>
> **What the next layer adds.** Stage 11 is where the direction reverses. Every
> result so far says load-aware beats load-blind. Two of the eight failures below
> say the opposite, for reasons that are not a special case.

---

## 11. FAILURE SCENARIOS

Eight, ordered so that the most surprising comes first.

---

### F1 — the failure magnet: the sicker a backend gets, the more traffic it attracts

**How it happens.** One backend loses its database connection pool. It does not
crash and it does not hang; it fails fast, returning a 500 in about a
millisecond. Every load-aware rule reads that as the emptiest, fastest,
healthiest server in the fleet.

> ### 🔮 Predict — checkpoint 4
>
> Eight backends, ρ = 0.8, one of them returning errors in 1 ms instead of
> working for 80 ms. Under **least connections**, what share of the fleet's
> traffic does the broken backend receive? Fair share is 12.5%.
>
> Then answer the same question for an EWMA-of-response-time rule — the "smart"
> one that products advertise as adaptive. Commit to both numbers.

**What happens.** Measured, ρ = 0.8, backend A errors in 1 ms:

| dispatch rule | share of all traffic sent to the broken backend |
|---|---:|
| round robin | 12.5% |
| random | 12.5% |
| power of two choices | **23.1%** |
| least connections | **66.1%** |
| least response time (EWMA latency) | **100.0%** |

**The better the load signal, the more completely the fleet commits to the
corpse.** Least response time sends it literally everything: a backend answering
in 1 ms will always beat a backend answering in 80 ms, and it will keep winning
for exactly as long as it stays broken.

**Why it happens.** Every load-aware rule in this lab optimises a proxy for
*capacity available* — outstanding requests, or observed latency. Both proxies
have the same blind spot: **they measure how fast the backend gets rid of
requests, not whether it did anything useful with them.** Failing is the fastest
possible way to get rid of a request. A rule that seeks speed will seek failure,
because failure is fast.

**What guarantee remains.** Round robin and random still give it exactly 12.5%,
because they are not optimising anything. In this one scenario, and only this
one, the dumbest rules are the safest.

**The fix, and it is not a dispatch rule.** Health checking and outlier ejection,
which are a *separate mechanism* precisely because they answer a question
dispatch cannot: not "which backend is fastest" but "which backends are
allowed to be candidates at all." This is why every production balancer has two
layers, and why a load balancer with a beautiful algorithm and a badly configured
health check is worse than one with round robin.

**How it appears in production.** As a partial outage that is far larger than the
broken component. One pod out of eight loses its DB credentials after a secret
rotation; error rate should be 12.5%; it is 66%, or 100%, and the graph looks
like a total outage caused by a single unhealthy pod. Anyone reasoning "only one
of eight pods is bad, so at most one in eight requests can fail" will spend the
first twenty minutes of the incident looking in the wrong place.

**A specific configuration trap worth naming.** If your health check is
`GET /health` and it returns 200 from a handler that touches nothing, it will
pass on exactly the backend that is failing every real request. Health checks
that do not exercise the dependency they are certifying are the mechanism by
which F1 survives the thing designed to prevent it. Envoy's outlier ejection —
which watches real traffic rather than a synthetic probe — exists because of
this.

---

### F2 — the gray failure: the same signal that caused F1 is the only thing that saves you here

**How it happens.** Backend A does not fail. It gets slow — 5× slow — for any of
the ordinary reasons: a degraded EBS volume, a noisy neighbour, a memory leak
pushing it into constant GC, a cache that got evicted.

**What happens**, ρ = 0.8, measured:

| dispatch rule | share sent to A | fleet mean | fleet p99 |
|---|---:|---:|---:|
| round robin | 12.5% | 8,629 ms | 330,967 ms → unbounded |
| random | 12.5% | 8,692 ms | 332,577 ms → unbounded |
| power of two choices | 3.1% | 131.5 ms | 978.2 ms |
| least connections | 3.0% | 102.7 ms | 571.0 ms |

**This is F1 exactly backwards.** The load-blind rules — the heroes of F1 — send
a machine that can do 10 requests a second exactly 40 of them, and its queue
grows without bound on a fleet running at 80% of capacity. The load-aware rules
notice within milliseconds and back off to a quarter of a fair share.

**Why the two failures point in opposite directions.** Both rules are answering
"how fast does this backend clear requests?" That signal correctly identifies a
*slow* backend and catastrophically misidentifies a *broken* one, because broken
is fast. The signal is not wrong; it is answering a different question than the
one you need.

**The resolution, and it is the design principle of the whole stage.**

> **Balancing and health are two different jobs and must be two different
> mechanisms.** Dispatch answers *"which of my healthy backends is least busy?"*
> Health checking answers *"which of my backends are healthy?"* Trying to make
> one rule do both gives you either F1 or F2, and which one you get depends on
> whether today's failure happens to be fast or slow.

**How it appears in production.** As the incident where the fleet is at 80% CPU,
one node is at 100%, and total fleet throughput has collapsed — because the queue
on that node is absorbing an eighth of all traffic and returning none of it. The
give-away is a per-backend latency graph where one line is three orders of
magnitude above the others, and a per-backend request-rate graph where all eight
lines are identical. **Identical request rates during an incident are a symptom,
not reassurance.**

---

### F3 — the herd: acting confidently on stale information

Covered mechanically in stage 10; the operational shape is what matters here.

**How it happens.** Any design where dispatchers learn backend load on a schedule
rather than continuously: a metrics scrape, a gossip round, a load header on
health-check responses, an Envoy load report. Or, equivalently, any design where
several dispatchers share a view that updates periodically.

**What to watch.** Per-backend request rate at **one-second resolution or
finer**. At one-minute resolution the herd is completely invisible — every
backend's *average* rate is exactly fair, because they take turns. You are
looking for eight lines that are individually spiky and collectively flat, with
the spikes anti-correlated.

**What happens.** From stage 10: at τ = 250 ms, mean latency 708 ms against 244
for random dispatch and 94 for the same rule with a live view. Worst queue depth
117 against 9.

**What guarantee remains.** Correctness — every request still reaches a healthy
backend and gets a right answer. Throughput is unaffected. **Only the tail
degrades**, which is exactly why this survives so long undetected: dashboards
built on means and one-minute buckets cannot see it at all.

**How it appears in production.** As p99 latency that is several times p50 with
no identifiable cause; as a fleet where every backend periodically shows a CPU
spike but never the same one twice; and, most characteristically, as an
improvement that arrives when someone changes the algorithm to something
*simpler*. If switching from least-connections to round robin made your tail
latency better, you had a herd.

---

### F4 — losing one backend, and the arithmetic of N+1

**How it happens.** A node dies, or is cordoned for a kernel patch, or fails a
health check. The remaining seven absorb its traffic. Everyone knows this.
Almost nobody does the arithmetic.

> ### 🔮 Predict — checkpoint 5
>
> Eight backends. The fleet is running at **87.5%** of capacity — high, but you
> have seen higher, and every backend is healthy. One node is lost.
>
> Seven-eighths of the capacity remains, so the fleet goes to 100% of capacity
> rather than 87.5%. How bad is that? Commit to a p99 before reading.

**What happens**, measured with power-of-two dispatch throughout:

| fleet ρ with 8 nodes | 8 nodes: mean / p99 | ρ after losing one | 7 nodes: mean / p99 |
|---:|---:|---:|---:|
| 0.60 | 81.5 / 372.4 | 0.69 | 83.8 / 374.9 |
| 0.70 | 84.0 / 373.9 | 0.80 | 91.1 / 385.5 |
| 0.80 | 90.4 / 384.3 | 0.91 | 115.7 / 427.3 |
| **0.875** | **101.1 / 402.0** | **1.00** | **983.9 / 2,082.5** |
| 0.90 | 108.7 / 414.2 | 1.03 | 9,015.9 / 16,649.7 |

At ρ = 0.875 the fleet looks entirely healthy — a p99 of 402 ms against a floor
of ~370. Losing one node takes it to exactly ρ = 1.0, and the p99 goes to 2,082
ms and is still climbing when the run ends. At ρ = 0.9 it is 16.6 seconds.

**Why.** This is `queueing-theory`'s cliff, reached by subtraction. Nothing about
load balancing failed; the fleet simply no longer has the capacity. The rule
is arithmetic:

> **To survive losing one of N backends, the fleet must run below `(N−1)/N`.**
> Eight nodes: below 87.5%. Four nodes: below 75%. **Three nodes: below 66%.**
> And because latency is set by *spare* capacity, "below" needs real margin, not
> a rounding error — at ρ = 0.80 the loss is survivable (384 → 427 ms), at 0.875
> it is not.

**The trap for small fleets.** A three-pod deployment must run below 66% to
tolerate a single pod restart. Most three-pod deployments are scaled on a CPU
target of 70–80%, which means **the deployment cannot survive its own rolling
update** — and since the update takes pods down deliberately, the failure is
self-inflicted and reproducible on every release.

**What load balancing contributes here.** Only this: it determines how quickly
the survivors absorb the load, and whether they absorb it evenly. Compare
`consistent-hashing`'s finding — with one position per node, a dead node's
*entire* share landed on one arbitrary neighbour, measured at +483% — which is
the same failure in a sharded system, and much worse, because there the load
cannot be spread at all.

**How it appears in production.** As a cascade. One node dies; survivors slow
down; slower survivors start failing health checks or timing out; they get
removed; the remaining fleet is smaller still. `load-shedding`'s F3 is this loop
at the request level and explains why removing the original cause does not end
the outage.

---

### F5 — session affinity, and why it is not load balancing

**How it happens.** Someone needs a user to keep hitting the same backend — an
in-memory session, a local cache, a WebSocket, a stateful upload. So they turn on
sticky sessions: `ip_hash` in NGINX, a session cookie, `balance source` in
HAProxy, `RING_HASH` in Envoy.

**What this actually did.** It converted a balancing problem into a **sharding**
problem. The dispatcher no longer chooses; the key chooses. And a key
distribution is whatever your users make it, which is never uniform.

**What happens.** Requests hashed to 8 backends by tenant, ρ = 0.8, where tenant
sizes follow Zipf — the ordinary shape of real tenant traffic, where the largest
tenant is many times the median:

| distinct tenants | busiest backend's share (fair = 12.5%) | median p99 |
|---:|---:|---:|
| 64 | 27.4% | 246,482 ms → unbounded |
| 256 | 24.1% | 201,952 ms → unbounded |
| 4,096 | 20.5% | 133,957 ms → unbounded |
| 4,096, **all tenants equal size** | 12.6% | 553.8 ms |

Read the last two rows together, because that is the finding. **Four thousand
tenants is not enough keys to balance, and four thousand *equal* tenants is
plenty.** The number of keys is almost irrelevant; the *skew* is everything.
With realistic skew the busiest backend gets 20.5% of a 320/s stream — 65
requests a second onto a machine that can do 50 — and its queue never stops
growing while three other backends idle.

**Why.** `consistent-hashing` established this and it has not changed: **every
hashing scheme agrees that one key has one owner, and that agreement is the
feature.** You cannot spread a key without giving up the property you enabled
stickiness to get.

**What guarantee remains.** The stickiness itself works perfectly. What you lost
is the ability to balance, and you lost it in proportion to your traffic skew.

**How it appears in production.** As one pod permanently hotter than the others,
which no amount of restarting fixes, and which moves to a different pod when the
fleet size changes. If your remedy for a hot pod is "restart it" and the heat
reappears somewhere else, you have a key-distribution problem, not a pod problem.

**What to do instead.** Move the state out — a shared session store, a shared
cache — so that any backend can serve any request and the choice becomes free
again. Where that is genuinely impossible, **bounded-load consistent hashing**
(the technique `consistent-hashing` flagged as "a load balancer's tool, never a
database's") gives you affinity with a cap: hash to a preferred backend, but if
that backend is already above `(1+ε)` times the average load, fall through to
the next one on the ring. HAProxy ships this as `hash-balance-factor`. You keep
stickiness for most requests and give it up exactly where it would have hurt.

---

### F6 — the balancer is balancing connections, and your traffic is not connections

**How it happens.** This one is nobody's fault and it catches almost everybody.

An **L4** load balancer — a network load balancer, ECMP in a router, `iptables`
in kube-proxy, an AWS NLB — makes its decision **when a TCP connection is
opened**, by hashing the 5-tuple. Once placed, that connection stays where it is
for its lifetime.

That was fine when one connection carried one request. But HTTP/2, gRPC, and any
connection-pooling client multiplex **thousands** of requests over a **single**
long-lived connection. The balancer made eight decisions at process start and
then never balanced anything again.

> ### 🔮 Predict — checkpoint 6
>
> Eight backends, each able to do 50 requests a second. Eight client services,
> each holding **one** long-lived gRPC connection, placed by a hashing L4
> balancer. Fleet load is 320 requests a second — ρ = 0.8, comfortable.
>
> What share of the traffic does the busiest backend get? Fair share is 12.5%,
> and **62.5% is its capacity**. Commit to a number.

**What happens.** Measured, connections placed by hash over 8 backends, ρ = 0.8:

| long-lived connections | busiest backend's share | worst seed | load on it | median p99 |
|---:|---:|---:|---:|---:|
| **8** | **42.2%** | 62.5% | 200 req/s | 337,729 ms → unbounded |
| 16 | 28.1% | 43.7% | 140 req/s | 221,443 ms → unbounded |
| 32 | 24.2% | 31.2% | 100 req/s | 211,545 ms → unbounded |
| 64 | 18.7% | 21.8% | 70 req/s | 127,897 ms → unbounded |
| 256 | 15.8% | 17.8% | 57 req/s | 2,150 ms |
| 1,024 | 14.0% | 14.7% | 47 req/s | 899.9 ms |
| *(balancing every request instead)* | *12.6%* | — | *40 req/s* | *569.0 ms* |

With eight connections over eight backends, the busiest backend gets **42%** of
all traffic — 3.4× its fair share — and in the worst run it got 62.5%, which is
200 requests a second onto a 50-request-a-second machine. **You need on the order
of a thousand connections before hash placement is safe**, and even 256
connections leaves the busiest backend at 57 req/s against a capacity of 50.

**Why.** Placing `k` connections at random into `n` bins is balls-in-bins, and
the imbalance shrinks only as `1/√k`. Eight balls into eight bins is
spectacularly uneven — the expected maximum is around 3 — and every request on a
connection inherits its connection's placement. **You are not balancing 320
requests a second; you are balancing 8 objects, once, at startup, and living with
the result for the process's lifetime.**

**What guarantee remains.** Connections are spread. Requests are not. The
balancer is doing exactly what it says on the tin.

**How it appears in production.** As the single most common "why is one pod at
100% CPU when the others are at 40%" question in any gRPC or HTTP/2 system. The
give-away is that the hot pod stays hot for hours and changes identity only when
something reconnects — because the only thing that can rebalance is a
reconnection.

**The fixes, in order of how much they change.**

1. **Balance at L7, per request.** Envoy, NGINX, HAProxy in HTTP mode, or a
   service mesh sidecar all terminate HTTP/2 and dispatch each *request*
   independently. This makes the problem vanish (the last row of the table). It
   is the reason service meshes exist for gRPC-heavy systems, and it is worth the
   hop.
2. **Client-side load balancing.** Let the client hold a connection to *every*
   backend and choose per request — gRPC's `round_robin` policy, Finagle, and
   Spring Cloud LoadBalancer all do this. Now the connection count is
   `clients × backends`, which balances beautifully and scales badly: 500 clients
   and 200 backends is 100,000 connections and every client must watch every
   backend.
3. **Subsetting**, which is what large deployments actually do: each client
   connects to a random *subset* of, say, 20 backends, and balances across those.
   You get most of the balancing quality with a bounded connection count.
   Google's *Site Reliability Engineering* book describes deterministic
   subsetting; Twitter/Finagle's *deterministic aperture* is the same idea with
   the subsets chosen to tile evenly rather than randomly. This is the standard
   answer at scale and it is worth knowing the name.
4. **Force reconnection periodically** — `max_connection_age` in gRPC servers,
   `max_requests_per_connection` in Envoy. This does not fix the imbalance, it
   just reshuffles it often enough that no single pod stays hot. It is a
   mitigation, not a solution, and it is frequently the right one.

---

### F7 — the health check that lies, in both directions

**How it happens.** Health checking is the mechanism F1 and F2 depend on, and it
has two failure modes that point opposite ways.

*Too lenient.* `GET /health` returns `200 OK` from a handler that allocates a
string. It will pass on a backend whose database pool is exhausted, whose disk is
full, and whose every real request is failing — the exact backend F1 needs
removed. **A health check that does not exercise the dependency it certifies is
decorative.**

*Too strict.* A check with a 100 ms timeout against a backend whose p99 is 400 ms
under load will fail intermittently. The backend gets ejected, its load moves to
the others, they get slower, their checks start failing too. This is F4's cascade
with the health checker as the amplifier — and it is worse than the original
problem, because the machines being removed were working.

**What to watch.** The count of backends currently in the pool, as a time series.
If it oscillates, you have this. A flat line at N is the only acceptable shape,
and most teams never graph it.

**What guarantee remains.** Neither failure loses data or corrupts anything. Both
convert a small problem into a fleet-wide one.

**The design rules that come out of it.**

- Check something real, but check it **cheaply** — a check that queries the
  database on every probe from every balancer becomes its own load problem.
  A common shape is a handler that reports a cached health status refreshed
  every few seconds by the application itself.
- **Asymmetric thresholds.** Require several consecutive failures to eject
  (HAProxy's `fall`, typically 3) and fewer successes to restore (`rise`,
  typically 2). Slow to condemn, quick to forgive.
- **Never eject below a floor.** Envoy's `outlier_detection` has
  `max_ejection_percent`, and it exists precisely because a checker that removes
  everything has converted a latency problem into a total outage. If the whole
  fleet looks unhealthy, the checker is what is broken.
- **Separate liveness from readiness.** Liveness answers "should this process be
  killed"; readiness answers "should this process get traffic". Wiring a
  dependency check into liveness means a database blip restarts your entire
  fleet.

---

### F8 — the retry that multiplies, and the balancer that helps it

**How it happens.** A backend gets slow. Clients time out and retry. The balancer
faithfully dispatches every retry — including to the backend that is already
struggling, or, worse, spreading the amplified load across a fleet that was
already at 80%.

**What happens.** `distributed-rate-limiting` and `load-shedding` both measured
this loop; the numbers are theirs, not this lab's. The relevant point here is
what a **load balancer** contributes to it, which is two things:

*It hides the amplification.* Offered load has tripled; every backend's request
rate has tripled evenly; nothing on a per-backend graph distinguishes "three
times the customers" from "the same customers asking three times."

*It can turn a partial failure into a total one.* Retries are dispatched
independently of the original, so a request that failed on backend A retries onto
backends B and C — spreading the load of a failing region across the healthy one.
This is why **retrying at the load balancer is more dangerous than retrying at
the client**: the balancer's retry has no knowledge of how many other retries are
in flight, and its position in the path means every client's retries are pooled.

**What to do**, in the order that matters:

- **Budget retries, do not count them.** Envoy's retry budget caps retries at a
  percentage of active requests (default 20%) rather than "3 attempts per
  request". A per-request count multiplies fleet load by 3 in exactly the
  situation where it must not; a budget cannot exceed the percentage no matter
  how many requests fail.
- **Never retry into an overload.** A 503 from a shedder means "there is no
  capacity", and retrying it immediately is the definition of the retry storm.
  `load-shedding` measured `Retry-After` becoming the synchroniser of the storm
  when everyone honours the same value; jitter is not optional.
- **Retry on a different backend, and only once.** This is the one thing the
  balancer is uniquely well placed to do, and it is genuinely valuable: a single
  immediate retry on a *different* backend converts a single-backend blip into a
  small latency bump. Its cousin, **hedging** — send a second copy after the p95
  and take whichever answers first — is the same idea used proactively, and it is
  a lab of its own.

---

## 12. CORRECTNESS — what you are actually guaranteed

Load balancing has few guarantees and it is important to be precise about which
ones exist, because most of the intuitive ones do not.

### What is guaranteed

**Every request reaches exactly one backend.** The dispatcher is a function; it
returns one answer. (Not true once retries are involved — see below.)

**No request is lost by the choice itself.** A load balancer, in the model of
this lab, never drops a request. It only decides where it waits. Real balancers
*do* drop — connection limits, queue limits — but that is shedding wearing a
balancer's clothes, and it should be reasoned about as shedding.

**Round robin gives exactly equal request counts.** To within one, over any
window, per dispatcher. This is a real, hard guarantee, and stage 4 is the
argument that it is not the guarantee you wanted.

**Sticky hashing gives stable placement.** The same key reaches the same backend
as long as the backend set does not change — and `consistent-hashing` is the
lesson on what "does not change" costs.

### What is *not* guaranteed, and is routinely assumed

**Equal load.** Nothing in this lab guarantees equal *work* per backend. Every
load-aware rule is a statistical improvement, not a bound. The measured spreads:
power of two's worst queue at 128 backends was 6.8 against a mean near 1.

**Any bound on the worst backend.** Except with bounded-load consistent hashing,
which is the only technique here that enforces a cap — that is precisely what
"bounded load" means and why it has that name.

**That the least-loaded backend is chosen.** Only true with `d = n` *and* an
exact view. Both fail in production, and stage 10 is the measurement of what
each costs.

**That balancing helps at all.** From the load sweep — the same eight backends,
same fleet, varying only ρ:

| fleet ρ | random p99 | round robin | power of two | least conn | ideal | random ÷ ideal |
|---:|---:|---:|---:|---:|---:|---:|
| 0.30 | 372.2 | 368.1 | 369.9 | 370.4 | 368.2 | **1.0×** |
| 0.50 | 383.4 | 371.4 | 370.1 | 367.2 | 369.2 | **1.0×** |
| 0.70 | 441.6 | 387.9 | 374.0 | 369.6 | 369.4 | 1.2× |
| 0.80 | 561.5 | 429.5 | 384.4 | 372.6 | 371.5 | 1.5× |
| 0.90 | 994.9 | 605.7 | 413.9 | 390.8 | 384.1 | 2.6× |
| 0.95 | 1,834.3 | 1,134.4 | 475.8 | 435.2 | 424.5 | **4.3×** |

> **At 50% utilisation, every algorithm on this page is within 4% of the
> theoretical optimum, and within 5% of each other.** The choice is worth nothing.
> At 95% it is worth 4.3×.
>
> **The algorithm only matters when you are already in trouble.** Which is both
> a reason not to agonise over it on a comfortable fleet, and a reason to have
> the right one configured before the day you need it — because the day you need
> it is the day you cannot safely change it.

**That it can save you above ρ = 1.** It cannot, and this is absolute. Above
capacity every queue grows without bound and there is no good one to choose. From
F4: eight nodes at ρ = 0.875 losing one gives ρ = 1.0 and a p99 of 2,082 ms with
the *best* rule in the lab. `load-shedding` is the only tool for that region, and
this is the sharp boundary between the two labs.

### The assumptions every number here depends on

1. **Backends are independent.** If two share a bottleneck — same node, same
   database, same NAT gateway — balancing between them buys nothing. This is the
   most common reason a real change produces no measured improvement.
2. **Any backend can serve any request.** The moment that is false you are
   sharding (F5).
3. **Service rate does not fall as the queue grows.** False in reality, in the
   direction that makes everything worse.
4. **The dispatcher itself is not the bottleneck.** True for these rules — all
   are O(1) or O(d) — but a rule that scans 200 backends per request at 50,000
   requests a second is 10 million comparisons a second in the request path, and
   that cost is one of the reasons Envoy implements `LEAST_REQUEST` as power of
   two rather than a full scan.

### The tradeoff that produced the design

Every dispatch rule spends **information** to buy **tail latency**, and pays for
the information in **coordination**.

| rule | information used | coordination cost | tail benefit | fails when |
|---|---|---|---|---|
| random | none | none | none | never (it has no assumptions to violate) |
| round robin | one local counter | none | ~40% of the gap | request sizes vary; backends differ |
| power of two | 2 backends' state | O(1) per request | ~90% of the gap | the backend is broken rather than busy |
| least connections | all backends' state | O(n) per request, and a fresh view | ~98% of the gap | τ > ~30 ms, or > ~16 dispatchers, or a fast-failing backend |

**Reading down the "fails when" column is the lab.** The rules that use more
information do not merely stop helping when the information is bad — they
actively cause harm, and the harm grows with how decisively the rule acts. That
is the single transferable idea here, and it is not really about load balancing
at all.

---

# PRODUCTION MODEL — stages 13 to 16

## 13. PRODUCTION — from this lab's model to what you actually operate

### What the lesson deliberately left out

- **Everything below the request.** TCP handshakes, TLS termination, connection
  reuse, keep-alive, HTTP/2 flow control, NAT vs direct server return. All of it
  matters for throughput and none of it changes the dispatch decision.
- **Service discovery.** This lab assumed the dispatcher knows the backend set.
  Producing that set — DNS, EDS, Consul, the Kubernetes Endpoints API — is a
  distributed-systems problem in its own right, and `gossip-protocols` is the
  lab that covers how such membership propagates.
- **Autoscaling.** Adding capacity is the other answer to "the fleet is at 90%",
  with a multi-minute time constant. `load-shedding` explains why that time
  constant means it cannot be your only answer.
- **Global traffic management.** Everything here is one pool in one place. DNS
  weighting, anycast, and latency-based routing across regions are a different
  problem with different failure modes.
- **The balancer as a component that can itself fail.** A hardware appliance is a
  single point of failure; a fleet of software balancers is not, but then you
  have the multi-dispatcher problem of stage 10 by construction.

### The layers, and what each can and cannot do

| layer | decides at | can see | balances | typical |
|---|---|---|---|---|
| **DNS** | name resolution | nothing | *clients*, at TTL granularity | Route 53, any GSLB |
| **L4** | TCP connect | 5-tuple | *connections* | AWS NLB, ECMP, kube-proxy/IPVS |
| **L7** | every request | headers, path, method | *requests* | Envoy, NGINX, HAProxy, ALB |
| **client-side** | every request | its own latencies | *requests*, no extra hop | gRPC, Finagle, Ribbon |

The important column is "balances". **Only the bottom two rows balance
requests**, and F6 is the measurement of why that distinction is the difference
between a working fleet and one hot pod. DNS is the coarsest of all: it balances
*resolvers*, its unit of change is a TTL, and clients cache past it — treat DNS
round robin as a way to spread clients across regions, never as load balancing.

### Where the algorithms actually live

Concrete, so you can find them:

- **NGINX**: `round-robin` (default), `least_conn`, `ip_hash`, `hash … consistent`,
  and `random two least_conn` — that last one is power of two choices, and the
  `two` is not a coincidence.
- **HAProxy**: `roundrobin`, `leastconn`, `source`, `random` (which is
  power-of-two by default via `random(2)`), and `hash-balance-factor` for
  bounded-load consistent hashing.
- **Envoy**: `ROUND_ROBIN`, `LEAST_REQUEST`, `RING_HASH`, `MAGLEV`,
  `RANDOM`. **`LEAST_REQUEST` is implemented as power of two choices** with a
  configurable `choice_count`, not as a full scan — the documentation says so,
  and people who assume otherwise mispredict its behaviour under staleness.
  Envoy is also where `outlier_detection`, `slow_start_config` and retry budgets
  live.
- **AWS**: ALB is L7 and does round robin or least-outstanding-requests; NLB is
  L4 and hashes the 5-tuple, so F6 applies to it in full.
- **Kubernetes `Service`**: `kube-proxy` in iptables mode picks a backend
  **at random** per *connection*. It is v1 of stage 10, at L4. Every gRPC-in-
  Kubernetes hot-pod story starts here.
- **gRPC client-side**: `pick_first` (default — one connection, no balancing at
  all) and `round_robin`. The default catches people constantly.
- **Maglev** (Google, NSDI 2016) and **Ring Hash** are consistent-hashing
  variants for when you need affinity plus minimal disruption on membership
  change — `consistent-hashing` is the lab for the mechanism.

### The single highest-value thing to configure, in order

1. **Health checks that exercise a real dependency, with asymmetric thresholds
   and a `max_ejection_percent` floor.** F1 and F7. Everything else is
   optimisation; this is the difference between a 12.5% outage and a 100% one.
2. **L7 or client-side balancing for any HTTP/2 or gRPC traffic.** F6. If you
   change one thing after reading this lab and you run gRPC, change this.
3. **Fleet headroom below `(N−1)/N`.** F4. This is a capacity decision, not a
   balancer setting, and it is the one that determines whether a node loss is an
   event or an outage.
4. **Slow start on any fleet where restarts are routine**, which is all of them.
   v2's 76-second p99.
5. **Then** pick a dispatch rule: power of two if you have more than a couple of
   balancer instances or any staleness in your view, least-connections if you
   have exactly one dispatcher with live state, round robin if your requests are
   genuinely uniform in cost. All three are within 1.7× of each other; the four
   items above are worth two orders of magnitude more.

### Balancing and shedding in the same box

They are usually the same process, and they interact in a way worth stating
explicitly.

A shedder measures *this backend's* queue delay and rejects when it exceeds a
target. A balancer moves work *between* backends. If the balancer is doing its
job, every backend's queue delay is similar, and the shedder sees a single
coherent signal. If the balancer is *not* doing its job — a herd, a hot shard, a
hash imbalance — then one backend sheds heavily while seven sit idle, and your
error rate is caused by imbalance rather than by insufficient capacity.

> **The diagnostic that separates them, and it is worth memorising:** if you are
> shedding while the fleet has spare capacity, **you have a balancing problem,
> not a capacity problem.** Adding servers will not help, because the new ones
> will be as idle as the current idle ones.

Concretely: graph shed rate *per backend*, not fleet-wide. A fleet-wide shed rate
of 5% can be 5% everywhere (genuine overload — add capacity or shed harder) or
40% on one backend and 0% on seven (an imbalance — fix the routing). Those two
have the same number on the dashboard and completely different remedies.

### What to put on the dashboard

Everything in this lab is invisible on a standard dashboard, which is the main
reason it stays broken for so long.

| graph | what it catches | why the usual graph misses it |
|---|---|---|
| **per-backend request rate**, 1-second resolution | F3 (herd), F6 (connection imbalance) | a 1-minute average of a herd is perfectly flat |
| **per-backend p99**, one line per backend | F2 (gray failure) | a fleet-wide p99 buries one bad node in seven good ones |
| **max ÷ mean backend queue depth** (or in-flight) | everything | there is no standard metric for "spread", and spread is the subject |
| **healthy backend count** | F7 (flapping) | almost nobody graphs it, and it should be a flat line |
| **per-backend shed rate** | balancing vs capacity | the fleet-wide number cannot distinguish them |

If you add one thing, add **max ÷ mean in-flight requests across backends**. It
is 1.0 for a perfect balancer, it was about 3.4 in F6's eight-connection case,
and it is the only number here that goes bad *before* latency does.

---

## 14. COMMON WRONG MODELS

**Wrong: "A load balancer spreads load evenly."** It spreads *requests*. Stage 1
is a fleet with perfectly even request counts, perfectly even CPU, and a breached
SLO. Round robin's guarantee is exactly this and it is not enough.
**Right:** it tries to equalise *queues*, and equal request counts only equalise
queues when all requests cost the same (c²ₛ = 0), which stage 4 shows is where
round robin ties the theoretical optimum and c²ₛ = 16 is where it becomes
indistinguishable from random.

**Wrong: "Least connections is the best algorithm."** True with one dispatcher
and a live view — 93.9 ms against an ideal of 91.5. With a 250 ms-old view it is
708 ms, which is 2.9× **worse than random**. With 32 dispatchers it is 1.8×
worse than power of two.
**Right:** least connections is the best algorithm *when its bet holds*, and its
bet — "my view of every queue is current" — is one that most production
deployments violate by construction.

**Wrong: "More information about backend load produces better routing."** F1 is
the counterexample and the numbers are not subtle: a backend failing in 1 ms
receives 12.5% under round robin, 23.1% under power of two, 66.1% under least
connections, and **100%** under least-response-time.
**Right:** more information produces better routing *when the information means
what you think it means*. All these signals measure how fast a backend disposes
of requests, and failing is the fastest disposal there is. Distinguishing broken
from fast is a different mechanism — health checking — not a better signal.

**Wrong: "Adding a load balancer adds capacity."** It performs no work. Every
"ideal" column in this lab is a fleet doing the same total work as every other
column.
**Right:** it recovers capacity you already own and were wasting on idle
backends while other backends had queues. That recovery is worth 0% at ρ = 0.5
and 4.3× at ρ = 0.95, and it is worth exactly nothing above ρ = 1.

**Wrong: "Health checks tell you which servers are healthy."** They tell you
which servers pass *your specific check*. A `/health` endpoint that touches no
dependency passes on exactly the backend F1 needs removed.
**Right:** a health check is a hypothesis about what "broken" means, and it is
only as good as the hypothesis. This is why outlier ejection — judging on real
traffic rather than a synthetic probe — exists.

**Wrong: "Sticky sessions are a small compromise."** F5: with realistically
skewed tenants, 4,096 distinct session keys still put 20.5% of traffic on one of
eight backends — 65 requests a second onto a 50-per-second machine — and its
queue grows without bound while others idle.
**Right:** enabling stickiness converts balancing into sharding and inherits
every property of sharding, including that hot keys are unfixable by definition.
The number of keys is nearly irrelevant; the skew is everything.

**Wrong: "We're only at 80%, so losing a node is fine."** At ρ = 0.875, losing
one of eight takes you to exactly ρ = 1.0 and a p99 of 2,082 ms that is still
climbing when the measurement stops.
**Right:** `(N−1)/N` is the ceiling, and because latency is governed by *spare*
capacity, you need real distance below it. For a three-pod deployment that
ceiling is 66%, which is below most autoscaling targets — meaning many small
deployments cannot survive their own rolling update.

**Wrong: "Our L4 load balancer distributes traffic across the fleet."** It
distributes *connections*, once, at connect time. With eight long-lived gRPC
connections over eight backends, the busiest backend measured **42.2%** of
traffic, and 62.5% in the worst run.
**Right:** if requests-per-connection is large, an L4 balancer is balancing a
handful of objects at startup and nothing thereafter. Balance at L7, in the
client, or force reconnection.

**Wrong: "Power of two choices is a cheap approximation of least connections."**
That is how it is usually introduced and it gets the point backwards. It is
**better** than least connections in most real deployments — 6× better at
τ = 500 ms, 1.8× better at 32 dispatchers.
**Right:** the randomness is not a cost-saving compromise, it is the mechanism.
Drawing a fresh random pair per request means independent dispatchers cannot
converge on the same victim, so there is no herd to have. Using less information,
less decisively, is what makes it robust.

**Wrong: "This is why we should switch to `<algorithm>`."** At ρ = 0.5 every
algorithm in this lab is within 5% of every other one, and within 4% of a
theoretical optimum it cannot beat.
**Right:** the ordered list in stage 13 is the real priority — health checks, L7
for HTTP/2, headroom, slow start, and only then the algorithm. The four items
above the algorithm are worth two orders of magnitude more than the algorithm is.

---

## 15. KNOWLEDGE MAP — where this sits

**Prerequisites you already had.** `queueing-theory` (the ρ = 1 cliff,
`W = 1/(μ−λ)`, Little's Law, Kingman, c²ₛ, and the M/M/k pooling result this lab
is built on); `load-shedding` (goodput, admission control, the standing 4-worker
80 ms service, metastable failure); `consistent-hashing` (hot keys, monotonicity,
bounded loads, ring hashing); `distributed-rate-limiting` (the staleness budget,
fail-open vs fail-closed, retry storms); `microservices` (timeouts, breakers,
bulkheads).

**What this lab adds to them.** The pooling result becomes a design constraint;
"stale shared state" acquires a measured crossover point (τ ≈ 30 ms); and the
hot-key lesson gains a second home in session affinity.

**Commonly confused with — one line each.**

| that | this |
|---|---|
| **sharding / partitioning** | the destination is fixed by the key; balancing exists only when the choice is free |
| **load shedding** | decides *whether* a request is admitted; balancing decides *which queue* it joins, and cannot help above ρ = 1 |
| **rate limiting** | enforces a promise per identity; balancing makes no promise to anyone |
| **autoscaling** | changes the number of backends over minutes; balancing changes their utilisation in microseconds |
| **failover** | picks a *different* pool when one is dead; balancing picks within a live pool |
| **reverse proxy** | the deployment shape (a hop in front of your fleet); balancing is one of the things that hop does |
| **service mesh** | where client-side balancing is implemented; not itself an algorithm |
| **circuit breaker** | protects the *caller* from a failing callee; health checking protects *the fleet* from one bad member |

**Where to go next**, in the order that will pay off most given what you now
have:

- `/learn tail-latency-and-hedging` — the other half of the deadline story that
  both this lab and `load-shedding` deferred: hedged requests, tied requests,
  Dean & Barroso's *The Tail at Scale*, and what it costs to ask twice. It is the
  natural sequel to F8.
- `/learn backpressure` — the mechanism this lab and `load-shedding` both keep
  pointing at. Reactive streams, TCP windows and Kafka consumer lag as one idea
  in three places.
- `/learn service-discovery` — this lab assumed the backend set was known. How
  it is actually produced, propagated and (crucially) how stale it is.
- `/learn observability-slos` — five labs now assume percentiles, error budgets
  and the difference between a mean and a tail; none of them teaches it, and
  stage 13's dashboard section is the shape of the gap.
- `/learn metastable-failures` — F4's cascade and `load-shedding`'s F3 as a
  subject in its own right; the 2021 HotOS paper *Metastable Failures in
  Distributed Systems* is the reference.

---

## 16. GLOSSARY

Terms in order of first appearance.

**Backend** (upstream, real server, origin, target, pod, host) — one
independently queueing unit of capacity behind the balancer. The definition that
matters is *has its own queue*, not *is a machine*: eight pods sharing one CPU
are one queue wearing eight names.

**Dispatcher / load balancer** — the component that chooses a backend per
request. Performs no work of its own.

**Dispatch rule** (algorithm, `lb_policy`, balancing policy) — the function from
(view, randomness, request) to a backend. All of stage 10.

**Shared queue / pooling** — the arrangement where all backends draw from one
line. Unreachable in a network service, and the "ideal" column in every table
here. `queueing-theory`'s M/M/k.

**ρ (rho), utilisation** — arrival rate ÷ capacity. In this lab, the *fleet's*
unless stated. The single most important number: below 0.7 the algorithm is
irrelevant, above 0.9 it is worth several×.

**c²ₛ** — squared coefficient of variation of service time. How unequal your
requests are. 0 = identical, 1 = exponential, 16 = a heavy tail. The number
round robin is betting on.

**Round robin** — take turns. Exactly equal request counts, zero information
required, and a bet that all requests cost the same. Ties the theoretical optimum
at c²ₛ = 0 and is indistinguishable from random at c²ₛ = 16.

**Least connections / least outstanding requests** — send to the backend with the
fewest requests in flight. Essentially optimal with a live view (98% of the
available gap); worse than random with a 100 ms-old one.

**The view (v), and staleness (τ)** — what the dispatcher believes about backend
state, and how old that belief is. Three regimes: exact (one dispatcher, live
state), stale (a snapshot refreshed every τ), local (each dispatcher sees only
its own requests — the normal case).

**Herd / stampede** — many independent deciders, given the same information,
choosing the same target simultaneously and destroying it. What `argmin` over a
stale view produces. Invisible on a one-minute dashboard.

**Power of two choices (`d = 2`)** — sample two backends at random, use the
shorter queue. Captures 89% of what perfect information captures, and is 6×
better than least connections at τ = 500 ms because a fresh random pair per
request leaves nothing for dispatchers to agree on. Azar et al. 1994;
Mitzenmacher 2001.

**Failure magnet** — a backend that fails fast looks like the fastest backend, so
every load-aware rule sends it more traffic the sicker it gets. Measured: 12.5%
under round robin, 66.1% under least connections, 100% under least-response-time.

**Gray failure** — a backend that is degraded rather than down. Passes naive
health checks, ruins load-blind dispatch, is handled automatically by
load-aware dispatch. The exact mirror image of the failure magnet.

**Health check / outlier ejection** — the separate mechanism that decides which
backends are *candidates*, as opposed to which candidate is *least busy*. The
only defence against the failure magnet. Outlier ejection is the passive form,
judging on real traffic rather than a synthetic probe.

**Slow start** — ramping a newly-healthy backend up to full weight over seconds
instead of instantly. Prevents the 76-second p99 that a routine restart produces
under round robin.

**Connection draining** — letting in-flight requests finish on a backend that is
being removed. The absence of it is why deploys produce 502s.

**Session affinity / sticky sessions** — pinning a key to a backend. Converts
balancing into sharding and inherits the hot-key problem: with realistic tenant
skew, 4,096 keys still gave one of eight backends 20.5% of traffic.

**Bounded-load consistent hashing** — affinity with a cap: hash to the preferred
backend unless it is already above `(1+ε)×` average, then fall through. HAProxy's
`hash-balance-factor`. The only technique in this lab that *bounds* the worst
backend rather than improving it statistically.

**L4 vs L7** — L4 decides once per TCP connection by hashing the 5-tuple; L7
decides per HTTP request. With HTTP/2 or gRPC, one connection carries thousands
of requests, so L4 balances a handful of objects at startup and nothing after
that.

**Subsetting / deterministic aperture** — each client connects to a random or
deterministically-assigned subset of backends rather than all of them. The
standard answer to client-side balancing's `clients × backends` connection
explosion. Google SRE book; Twitter/Finagle.

**Retry budget** — capping retries as a percentage of active requests (Envoy's
default is 20%) rather than as a count per request. A per-request count
multiplies fleet load exactly when it must not.

**N+1 / `(N−1)/N`** — the utilisation ceiling for surviving the loss of one
backend. Eight nodes: 87.5%. Three nodes: 66%. Below it with real margin, because
latency is governed by spare capacity.

**Max ÷ mean in-flight** — the spread metric nobody graphs and everybody needs.
1.0 for a perfect balancer, 3.4 in F6's eight-connection case, and it degrades
before latency does.
