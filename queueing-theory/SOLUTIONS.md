# Queueing theory — solutions

Every number below was checked against the simulator in `visualization.html`
(4 simulated hours × 4 seeds unless noted). Where an answer is a judgement call
rather than a calculation, it says so.

Constants: **S = 80 ms**, so one server's capacity is **μ = 12.5 req/s**.

---

## Rung 1 — EXPLAIN

**1.1** Two things are wrong with it.

First, *busy-ness is not the same as the delay people feel.* At 80% busy on a
single server, the average request already spends **five times its own work
time** inside the service — 406 ms measured, for 80 ms of actual work. There is
no headroom left in the sense the sentence means; the damage has already
happened.

Second, *the remaining 20% is not usable in the way it sounds.* Work does not
arrive evenly. When two requests turn up in the same instant, one has to wait —
and the quiet second that came before cannot be spent on it, because idle time
cannot be saved. Every burst leaves a backlog, and the backlog is only paid off
out of future idle time. At 80% busy there is little of that left, so the
backlogs last longer and land on more people. Take it to 90% and the average
total time doubles again; take it to 95% and it doubles again after that.

The useful reframing: **what sets latency is spare capacity, not capacity.**
Going 72% → 90% did not remove 18% of anything. It removed 64% of the slack
(3.5 spare req/s down to 1.25), and the latency multiplied by almost exactly the
inverse of that.

**1.2** It says the number of things inside a system equals how fast they arrive
times how long each one stays. Its value is that you can usually measure two of
those three cheaply and the third not at all.

Concrete example: **how many worker threads you need.** You can measure requests
per second at the load balancer (100/s) and average handler time in a profiler
(200 ms). You cannot easily measure "how many requests are in flight at once" —
but Little's Law gives it to you: 100 × 0.2 = **20**. (See 1.3's sibling warning
in 6.2: that is the *floor*, not the setting.)

The other everyday use is as a **consistency check**: if your queue-depth metric
and your latency metric do not satisfy `depth = rate × latency`, one of them is
instrumented wrongly, and finding out which is usually a good afternoon's work.

**1.3**
- **Utilisation vs throughput.** Throughput is how much you finish per second;
  utilisation is what fraction of the time you were busy doing it. Two systems
  can have the same throughput at wildly different utilisation, and one of them
  is in trouble.
- **Throughput vs goodput.** Throughput counts answers produced. Goodput counts
  answers somebody was still waiting for. Under overload the first stays flat and
  healthy-looking while the second goes to zero — measured, F6: identical 7,711
  throughput at every queue size, goodput ranging from 64 to 7,033.
- **Waiting vs service time.** Service time is your code. Waiting is the queue in
  front of your code. Your latency dashboard shows their sum and cannot tell you
  which one moved, which is why "latency went up" is not yet a diagnosis.
- **Backpressure vs load shedding.** Backpressure tells the sender to slow down
  and keeps the work. Shedding throws the work away. You shed when you have no
  way to make the sender listen — which, for an HTTP service facing the public
  internet, is always.

**1.4** Because they are not two points on one curve; they are two different
kinds of system.

Below capacity, the queue length is a random walk with a **downward drift**. It
wanders up during a burst and comes home during the quiet. There is a long-run
average, formulas produce finite numbers, and waiting it out works.

At or above capacity the drift is **upward** and there is no home to return to.
The queue grows linearly and forever, at exactly (arrivals − capacity) jobs per
second. There is no average to compute; every formula in the lab returns
infinity. Waiting does not help, because the queue has no memory of being small.

The practical consequence is that the *only* useful actions change sides: below
capacity you tune, above it you must reduce arrivals or raise capacity, and
nothing else will ever work. Measured at just 5% over: 27 waiting after one
minute, 385 after five, **1,619 after thirty**, with the oldest job having waited
two minutes and still climbing.

---

## Rung 2 — IDENTIFY

**2.1** With default settings and a 20-minute measurement you should see
approximately: utilisation **80%**, average wait ≈ **320 ms**, average total ≈
**400 ms**, 99th percentile ≈ **1,900 ms**. (Shorter runs come out lower,
because the simulation starts with an empty queue and that transient is a real
part of a short window — a 60-second run typically reports 70% and 234 ms.)

**2.2** Average wait is `Wq`; average total is `W`. Their difference is exactly
the service time, `W = Wq + S`, by definition — 400 − 320 = 80 ms. If your two
readings do not differ by about 80 ms, you misread one of them.

**2.3** Judgement question — the point is that the inspector answers all three
from the screen: it prints how long the job has waited, its position in the line,
and (when a give-up time is set) how many milliseconds remain before its client
abandons it. A job at position 12 with one server needs 12 × 80 = 960 ms before
it even starts.

**2.4** Around **7–8 req/s** (ρ ≈ 0.6) it stops being visually straight, and past
**10** (ρ = 0.8) it climbs steeply. Note how much of the x-axis looks safe: the
curve is nearly flat across the entire left two-thirds, which is exactly why the
cliff is invisible on a dashboard that plots utilisation linearly.

**2.5** About **3%** of jobs are slow, and they take roughly **16.5×** the
average — with S = 80 ms that is 97% at ~42 ms and 3% at ~1,320 ms. The average
service time is **unchanged at 80 ms**. That is the whole trap: the average is
identical, utilisation is identical, and the average wait goes from 326 ms to
2,730 ms.

---

## Rung 3 — PREDICT

**3.1 — 6×, not 2×.** Halving service time does two things at once: it halves
the multiplier `S` *and* it halves utilisation (0.8 → 0.4), which collapses the
`1/(1−ρ)` factor from 5 to 1.67.

```
  before:  W = 1/(12.5 − 10) = 400 ms      measured 401 ms
  after:   W = 1/(25   − 10) =  67 ms      measured  67 ms
```

The *waiting* component improves even more — 321 ms → 27 ms, a factor of
**12**. This is the single best argument for performance work: at high
utilisation the payoff is quadratic-ish, not linear.

**3.2 — About 8×.** Kingman's variability factor is `(c²ₐ + c²ₛ)/2`, so going
from (1, 0) to (1, 16) takes it from 0.5 to 8.5. Measured: **156 ms → 2,692 ms**,
a factor of 17 against the clockwork-service case — and **326 ms → 2,730 ms**,
a factor of **8.4**, against the fully-random baseline, which is what the
question's "both averaging 80 ms" most naturally means. Either way, an order of
magnitude, at unchanged load and unchanged average work.

**3.3 — About one fifth.** Measured at 80% busy: 4 separate lines give 320 ms of
waiting, one shared line gives 61 ms — **5.2×** better. The advantage is largest
at *low* utilisation (11× at 50% busy), which is counter-intuitive until you see
why: at low load a server is often idle, and separate lines make that idleness
unreachable by the people waiting.

**3.4 — About 6 minutes, and about 18 minutes.**

```
  cut to 85%:  drain = 12.5 − 10.625 = 1.875/s  →  685 / 1.875 = 365 s ≈  6.1 min
  cut to 95%:  drain = 12.5 − 11.875 = 0.625/s  →  685 / 0.625 = 1096 s ≈ 18.3 min
```

Recovery time is `backlog ÷ spare capacity`, and spare capacity is small
precisely because you were operating near the edge. **The lesson is that a timid
load shed is barely a shed at all** — halving how much you cut roughly triples
how long you stay broken.

**3.5 — Tens of thousands, still growing.** Measured: 3,249 after 10 seconds,
9,983 after 4 minutes, **37,115 after 20 minutes**, with no sign of turning. The
identical burst with retries switched off is fully absorbed within four minutes
(403 → 6). The burst is not the failure; the retry loop is, and the burst merely
started it.

**3.6 — 5 > 20 > 0 > 100 = unbounded.** Measured "answered in time" out of 9,122
arrivals: cap 5 → **7,033**; cap 20 → 2,407; cap 0 → 4,199; cap 100 → **64**;
unbounded → **64**.

Two things usually surprise people here. First, that 100 is exactly as useless
as unbounded — once the back of the queue cannot make its deadline, extra queue
adds nothing but corpses. Second, that **zero queue is also wrong**: a small
queue's actual job is to absorb bursts, and removing it costs you 2,800
customers.

---

## Rung 4 — MODIFY

**4.1** The wait goes from **0 ms to about 156 ms** — from nothing to two full
service times, at unchanged load and unchanged utilisation. You moved **c²ₐ**,
the arrival-variability term, from 0 to 1.

What broke is the naive capacity plan itself: "demand fits inside capacity, so
nobody waits" is *true* in a clockwork world and false everywhere else. The
queue you just created is made entirely of the clumps that randomness produces,
paid for out of idle time that has already been thrown away.

**4.2** The metric that got ~110× better is **answered in time** (64 → 7,033).
The metric that did not change is **throughput** (7,711 either way).

Throughput did not change because both configurations are *work-conserving* —
the server is never idle while work waits, so it completes exactly as many jobs
per second either way. Capacity is capacity. What the queue bound changed was
**which** jobs got that capacity: with an unbounded queue every completion went
to someone who had already left. You did not make the machine faster; you stopped
it wasting itself.

**4.3** You lost **latency**, by roughly 5× — a *liveness* degradation, not a
safety one. Nothing incorrect happens; every job still gets exactly one correct
answer in a legal order. It simply takes longer, because a server can now sit
idle while somebody waits in another line and there is no path between them.

(The reason anyone would still do this is in 6.4: separate lines buy isolation,
and isolation is a safety property for *tenants* even though it costs liveness
for everyone.)

**4.4** Judgement question, but the shape of the answer is fixed: with retries
**on** at 85% base load, it typically takes only one or two 40-job bursts before
the queue stops returning to its old level, because each burst pushes latency
past the 600 ms deadline, which manufactures retries, which raise the arrival
rate above capacity and keep it there.

With retries **off** you cannot reproduce it at all, no matter how many bursts
you inject. Each burst is absorbed and the system returns to its old state. The
difference is not the size of the disturbance — it is the presence of a
**feedback loop with gain above 1**. This is the definition of a metastable
failure, and it is why "we were only slightly over" is not a defence.

**4.5** **Answered in time** gets dramatically better (64 → 6,660, and server
time wasted drops from 99% to 15%). The **99th percentile** gets dramatically
worse (18.2 s → 36.4 s).

The customers who pay are the ones who arrived earliest and are now at the
bottom of the stack — they may never be served at all. That is a real cost, and
it is why newest-first is an overload *mode* rather than a default: run
oldest-first normally, switch above a depth threshold, and pair it with dropping
jobs whose deadline has already passed, which converts most of that starvation
into a fast rejection the client can act on.

---

## Rung 5 — TRACE

**5.1**

| # | arrives | clerk free at | starts | **waits** | leaves |
|---|---|---|---|---|---|
| 1 | 0:00 | 0:00 | 0:00 | 0 s | 1:20 |
| 2 | 0:30 | 1:20 | 1:20 | 50 s | 2:40 |
| 3 | 0:55 | 2:40 | 2:40 | 105 s | 4:00 |
| 4 | 4:10 | 4:00 | 4:10 | 0 s | 5:30 |
| 5 | 9:00 | 5:30 | 9:00 | 0 s | 10:20 |
| 6 | 9:20 | 10:20 | 10:20 | 60 s | 11:40 |
| 7 | 9:35 | 11:40 | 11:40 | 125 s | 13:00 |
| 8 | 9:50 | 13:00 | 13:00 | 190 s | 14:20 |

**Total idle time: 220 s** (10 s between 4:00 and 4:10, plus 210 s between 5:30
and 9:00). **Total waiting: 530 s.**

The clerk had more than three minutes of nothing to do and the customers still
waited nearly nine minutes between them. The first number cannot reduce the
second because **idle time is perishable**: it exists at 5:30, and the waiting
exists at 9:35, and there is no mechanism that moves capacity from one moment to
the other. Only the reverse works — a *backlog* travels forward in time, which
is why a queue is a memory of past bursts charged to future arrivals.

**5.2** Two clerks, one shared line:

| # | arrives | starts | **waits** | leaves |
|---|---|---|---|---|
| 1 | 0:00 | 0:00 | 0 s | 1:20 |
| 2 | 0:30 | 0:30 | 0 s | 1:50 |
| 3 | 0:55 | 1:20 | 25 s | 2:40 |
| 4 | 4:10 | 4:10 | 0 s | 5:30 |
| 5 | 9:00 | 9:00 | 0 s | 10:20 |
| 6 | 9:20 | 9:20 | 0 s | 10:40 |
| 7 | 9:35 | 10:20 | 45 s | 11:40 |
| 8 | 9:50 | 10:40 | 50 s | 12:00 |

**Total waiting: 120 s**, down from 530 s. Doubling the servers cut the waiting
by **4.4×**, not 2× — the same super-linear effect as the pooling table, on eight
customers you can count on your fingers.

**5.3** λ = 11/s, μ = 12.5/s, so μ − λ = 1.5/s.

| | theory | simulated |
|---|---|---|
| ρ | 0.88 | 0.88 |
| W | 1/1.5 = **667 ms** | 685 ms |
| Wq | 0.88 × 667 = **587 ms** | 605 ms |
| L | 0.88/0.12 = **7.33** | 7.54 |
| Lq | 0.7744/0.12 = **6.45** | 6.66 |
| p99 | 667 × ln(100) = **3,070 ms** | 3,162 ms |

Simulation runs a few percent high at this utilisation because a finite window
under-samples the long excursions — which is itself the honest lesson: near the
cliff, *measurements* get noisy for exactly the same reason *latency* gets bad.

**5.4** **100 requests inside.** L = λW = 400 × 0.25.

What you had to assume: only that the system is in **steady state** over the
window you measured (nothing accumulating without bound) and that "inside" means
the same boundary for both numbers. Nothing about the arrival pattern, the
service-time distribution, the service order, or the number of servers. That is
what makes Little's Law an identity rather than a model, and why it is the one
formula in this lab you can quote without qualification.

**5.5** The loop, five links:

1. A burst arrives, **which causes** the queue to grow past what one service
   time can absorb.
2. The queue grows, **which causes** total latency to exceed the client's
   deadline.
3. Latency exceeds the deadline, **which causes** clients to give up and retry.
4. Retries arrive, **which causes** the offered arrival rate to rise above
   capacity.
5. Arrivals exceed capacity, **which causes** the queue to grow — back to
   link 1, now with no external input required.

Once the loop closes, removing the original burst changes nothing, because the
loop's input is its own output.

---

## Rung 6 — SPOT THE FLAW

**6.1 — This design's failure is that it never rejects anyone, which is exactly
how it ends up serving nobody.**

Concrete run, using the measured numbers: 15 requests/second arrive at a service
that can complete 12.5, with a client deadline. Over ten minutes, 9,122 requests
turn up.

- With the **unbounded** queue: 7,711 completed, **64 of them before the
  deadline**, 7,647 delivered to clients that had closed the connection, and
  1,410 still queued at the end. Median latency **51 seconds**. Every worker
  thread is 99% occupied producing answers nobody will read.
- With a queue bounded at 5: identical 7,711 completions, of which **7,033 arrive
  in time**, and 2,060 people get an immediate rejection they can act on.

The work it is doing has a name: it is **not throughput, it is waste** — the
lab's term is that its **goodput** is 64. The design confused "nobody is
rejected" with "everybody is served", and those become opposites under overload.

The second, slower failure: `LinkedBlockingQueue` with no capacity argument is
unbounded in *memory* too, so a long enough overload ends in `OutOfMemoryError`
rather than a graceful degradation.

**6.2 — Three mistakes.**

1. **200 threads is the floor, not the setting.** λ × S = 4,000 × 0.05 = 200 is
   the concurrency at which utilisation is exactly **1.00** — the saturation
   point, where latency has no steady value at all. Measured directly (λ=100/s,
   S=200 ms, so the floor is 20): at 20 servers, W = 3.2 s and rising; at 25, W =
   209 ms. The rule is `λS ÷ ρ_target`; for ρ_target = 0.8 they need **250**.

2. **A queue of 10,000 guarantees the back of it is answered far too late.** With
   200 threads at 50 ms each, the queue drains at 4,000/s, so job number 10,000
   waits **2.5 seconds** before it starts. Any client deadline under that makes
   the whole tail of the queue pure waste. The bound should come from the
   deadline: `queue ≈ servers × (deadline/S − 1)`.

3. **Nothing in the plan mentions variability.** "Peak is 4,000/s and each takes
   50 ms" fixes ρ and S and says nothing about `c²ₐ` or `c²ₛ` — the middle term
   of Kingman's formula, which is worth up to 8× on its own (measured: 326 ms
   versus 2,730 ms at identical ρ and identical mean service time). If their
   traffic is bursty or their service time has a fat tail, a correctly-sized pool
   still misses its latency target.

A fourth, if you want it: "peak is 4,000/s" is almost certainly a one-minute
average, and the *within*-minute peak is higher.

**6.3 — The sequence, and the point of no return.**

1. The service is at 70% utilisation; total latency averages ~167 ms, p99 around
   770 ms. Some requests already exceed 1 s.
2. A minor disturbance — a garbage-collection pause, a slow database query, a
   deploy — pushes latency up briefly. Now a few percent of requests cross the
   1-second timeout.
3. Each of those becomes up to **four** requests (the original plus three
   retries), sent *immediately*, with no backoff, so they land in the same
   congested moment that caused the timeout.
4. Offered load rises. Utilisation rises. Latency rises. More requests cross 1 s.
5. **The point of no return is the moment the retry-inflated arrival rate exceeds
   capacity** — the moment utilisation reaches 1.00. From then on the queue grows
   without bound, every request times out, every timeout produces three more, and
   the system's own output is its input.

After that moment, removing the original trigger does nothing: the GC pause is
long over and the system stays down. The only exit is to stop the traffic
entirely — which is precisely why "turn it off and on again" is the fix that
works when nothing else does.

The three flaws in the policy, in order of severity: **retrying immediately with
no backoff** (concentrates retries into the worst moment, and raises `c²ₐ`);
**three retries** (a 4× load multiplier, when a healthy system has ~30% headroom);
and **no fleet-wide budget**, so nothing caps the total.

**6.4 — Because they turned one shared line into N separate ones, and idle
capacity in a separate line is unreachable.**

Measured at 80% busy with four servers: one shared line waits 61 ms, four
separate lines wait 320 ms — **5.2× worse**, with the same threads and the same
traffic. The mechanism is that tenant B's thread can now sit idle while tenant
A's request waits, and there is no path between them. The effect is *worse* at
low utilisation (11× at 50% busy), because that is when idleness is most common.

**When it is nevertheless right:** when one tenant can hurt the others. If 3% of
one customer's requests take 16× the average, a shared pool spreads that damage
across everybody — measured, 326 ms → 2,730 ms for *all* tenants. Separate pools
convert a shared latency regression into a private one. That is a deliberate
trade of average latency for **isolation**, and it is the right call exactly when
you have a noisy neighbour and not before. Splitting "for isolation" with no
noisy neighbour is a pure loss.

**6.5 — Coordinated omission.** The harness is closed-loop: each of its 100
threads sends a request, waits, and only then sends another. That means **the
harness's arrival rate is a function of the system's speed.**

Trace it. Suppose the service stalls for one second.

- **Real users** keep arriving throughout the stall. At 100 req/s that is 100
  more requests entering the queue, the last of which waits nearly a full second
  *plus* whatever backlog the first hundred created. All of those slow samples
  belong in the p99.
- **The harness**: all 100 threads are blocked on their in-flight requests. It
  sends **zero** new requests during the stall. It records exactly 100 slow
  samples instead of ~200, and — the bigger error — the queue those extra
  requests would have built was never created, so its knock-on latency never
  existed to be measured.

The harness is arithmetically correct about the requests it sent. It is wrong
about which requests a real system would have received.

**What it must do differently:** drive **open-loop** — send at a fixed arrival
rate regardless of whether earlier responses have come back (wrk2, Gatling's
constant-rate injection, k6's `constant-arrival-rate`, JMeter's throughput
shaper), letting requests pile up when the system is slow, exactly as users do.
Failing that, correct after the fact by back-filling the requests that should
have been sent during each stall. And measure at the **client**, since a
server-side timer that starts when the handler picks the request up excludes the
accept backlog and the load balancer queue — the two places the waiting actually
happened.

**6.6 — The throughput alert never fires, and that is the problem.**

At 110% of capacity, throughput sits pinned at **exactly 100% of capacity** —
higher than it was at 90% load, and completely flat. The graph looks like the
healthiest thing on the dashboard. It will not drop below 5,000 because
saturation does not reduce throughput; it caps it.

The p99 alert does fire, and it fires late and then stays useless: past ρ = 1
latency has no steady value to compare against a threshold — it rises **linearly
with elapsed time**, so it will cross 2 s, then 20 s, then 200 s. By the time the
5-minute condition is satisfied the backlog is already thousands deep, and the
recovery time is `backlog ÷ spare capacity`, which is now the metric that
matters.

What the runbook should alert on, in order of how early it fires:

1. **Queue depth and its first derivative.** A queue that is rising steadily for
   60 seconds is proof of ρ ≥ 1 and it leads latency by minutes.
2. **Spare capacity (`capacity − arrivals`)** falling below a floor. This is the
   quantity that sets both latency and recovery time, and unlike utilisation the
   threshold is not arbitrary.
3. **Goodput ratio** — answers delivered inside the client's deadline, divided by
   requests received. It is the only metric that distinguishes "busy" from
   "failing", and in the F6 measurement it ranged from 0.7% to 77% while
   throughput never moved.

---

## Rung 7 — HANDLE FAILURE

These are design discussions, not single answers. What follows is one good
version of each.

**7.1 — Three mechanisms.**

1. **Bound the queue** at roughly `servers × (deadline/S − 1)`, and reject
   instantly when it is full with a fast 503 and an honest `Retry-After`.
   *Cost:* you now turn people away, and someone must decide that is acceptable.
   *Cannot save you from:* a client fleet that retries the 503 immediately —
   which converts your shed into a storm. The `Retry-After` must be honest and
   jittered, or your own header becomes the synchroniser.

2. **Drop work whose deadline has already passed, before starting it.** A two-line
   check at the moment a worker picks a job up: if `now − arrivedAt > deadline`,
   discard and count it. *Cost:* almost nothing, and it is the highest
   value-per-line change in this whole lab. *Cannot save you from:* work that
   expires *during* service, which needs cancellation propagation.

3. **Switch to newest-first above a depth threshold.** Measured: 64 → 6,660
   answered in time. *Cost:* the p99 doubles and the oldest jobs may starve —
   mitigated by mechanism 2, which rejects them instead of stranding them.
   *Cannot save you from:* sustained overload; it improves who gets served, not
   how many.

None of the three raises capacity. If the overload is permanent, all three buy
you a graceful failure and nothing else — you still need more servers or less
traffic.

**7.2 — A retry policy that cannot storm.**

Rules:
1. **Retry only idempotent operations**, and only on errors that are evidence the
   work did not happen (connection refused, fast 503, 429). See the
   `idempotency` lab for what makes retrying safe at all.
2. **Never retry a slow timeout.** A timeout caused by queueing is direct
   evidence that the queue is full; adding to it is the storm. A *fast* failure
   is safe to retry; a slow one is not. This distinction is the single most
   important rule here.
3. **Exponential backoff with full jitter**: `sleep = random(0, base × 2^attempt)`.
   The backoff caps the rate; the jitter lowers `c²ₐ` and prevents the fleet from
   re-synchronising into a batch. Both halves matter.
4. **A fleet-wide retry budget**: each client tracks a rolling ratio of retries to
   total requests and stops retrying above ~10%. **This is the rule that actually
   prevents the storm**, because rules 1–3 bound each *request's* behaviour while
   the budget bounds the *aggregate*, and the aggregate is what the feedback loop
   is made of. Without it, 5,000 clients each behaving reasonably still sum to a
   4× load multiplier.
5. **A circuit breaker** as the backstop: on sustained failure, stop sending
   entirely for a cool-down, then probe with a single request. This sets the retry
   rate to zero at the exact moment it is most dangerous.
6. **At most one retry** for interactive requests. The second retry arrives after
   the user has usually already given up, so it is pure added load.

**7.3 — Two fixes for the slow tenant.**

*Isolate.* Give the noisy tenant its own pool with its own concurrency limit — a
bulkhead. The other tenants' `c²ₛ` collapses back toward 1 and their p99 returns
to roughly where it was.
**Worse off:** the noisy tenant, whose own latency gets worse (they now queue
behind themselves in a smaller pool), and *everyone*, slightly, because you have
given up pooling — the measured cost of separate lines is ~5× on average wait,
which the isolation gain has to exceed.

*Reorder.* Split the queue by expected size and serve short jobs first, or
preempt long ones. Mean waiting time provably falls, and it falls a lot when the
distribution is this skewed.
**Worse off:** the long jobs, unboundedly — under sustained load they can starve
completely. Fixable with ageing (a job's priority rises with its wait), which
gives back some of the improvement.

The judgement call: isolation is the right default when the slow work belongs to
an identifiable tenant you can bill or throttle; reordering is right when the
slow work is a *kind* of request (a report, an export) rather than a customer,
because then you can move it to an asynchronous path and stop pretending it is
interactive at all.

**7.4 — The autoscaler, minute by minute.**

Capacity C. At 09:00 arrivals jump to 1.3 C.

- **09:00–09:01.** Utilisation hits 1.0 and the queue starts growing at 0.3 C
  jobs per second. Latency begins climbing **linearly**, not to a new plateau.
  CPU reads 100% and stops carrying information — it cannot distinguish 101%
  from 200% offered load. This is the first reason CPU is the wrong signal.
- **09:01–09:03.** The autoscaler's own averaging window (typically 1–3 minutes)
  has to fill before it decides. The queue grows the whole time. This lag is
  *added to* the provisioning lag, and teams routinely forget it.
- **09:03.** Decision made; instances requested.
- **09:03–09:06.** Provisioning: boot, image pull, health checks, JIT warm-up,
  connection pools filling. Three more minutes at 0.3 C accumulation. By now the
  backlog is roughly **six minutes × 0.3 C**.
- **09:06.** New capacity arrives, say 1.5 C total. The system is stable again —
  but latency does **not** return to normal, because the backlog must drain
  first, at `1.5 C − 1.3 C = 0.2 C` per second. Draining six minutes of
  accumulation at 0.2 C takes about **nine minutes**.
- **09:15.** Latency finally normal. Total incident: fifteen minutes, of which
  only six were "waiting for servers".

**Scale on queue depth, or on spare capacity**, not CPU. Queue depth rises
*before* saturation and keeps carrying information after CPU has pinned at 100%;
it is also the quantity that determines how long recovery will take. The
second-order lesson: because recovery costs roughly 1.5× the accumulation
period, the scaling step must be big enough to leave real drain headroom —
scaling to exactly 1.3 C would leave a backlog that never clears.

**7.5 — Deadline propagation.**

The client sends a deadline, not a timeout: an absolute instant, `now + 900 ms`,
carried in a header and passed to every downstream call.

Each service, on receiving a request:
1. **On accept:** if the deadline has already passed, reject immediately without
   doing any work.
2. **On dequeue** — the highest-value rule: if `remaining < expected service
   time`, do not start. Reject with "deadline exceeded". This is what stops the
   99%-wasted-server-time failure.
3. **On calling downstream:** pass `remaining − a small reserve` as the child's
   deadline, so the child inherits a budget it cannot overrun, and so the parent
   keeps enough time to write an error response.
4. **On the way out:** if the deadline passed while it was working, still return
   the answer if it is cheap to do so, but count it as a miss.

What each service can now do that it could not before: **decide.** Without a
deadline a server has no information about whether the work in front of it is
still wanted, so it must assume it is — which is precisely the assumption that
turns an overload into 99% waste. With a deadline, dropping doomed work becomes a
local decision requiring no coordination, and the queue stops being a place where
requests go to die quietly.

---

## Rung 8 — DESIGN

Worked design discussions. Yours may differ; what matters is that every number
traces back to a measurement or a formula.

### 8.1 — The payments gateway

**First, the thing that no amount of capacity fixes.** Some authorisations take
3 seconds at the card network. The merchant's deadline is 2 seconds. **Those
requests cannot be answered in time by any design** — the work itself is longer
than the budget. Say this out loud in the design doc, because otherwise the
capacity plan gets blamed for it. The job is to (a) fail them fast and cleanly
rather than late, and (b) stop them destroying everyone else's latency.

**Concurrency.** Little's Law gives the floor: 6,000/s × 0.22 s = **1,320**
in-flight authorisations. That is the number at which utilisation is exactly 1.0,
so it is not the answer. Divide by a target utilisation.

**Utilisation target.** With this many parallel slots, pooled, the queueing
penalty at a given ρ is far gentler than the single-server tables suggest —
measured: 32 pooled servers at 80% busy wait 2 ms against 316 ms for one server
at 80%. So the target is not set by queueing at all. It is set by two other
things: the burstiness (`c²ₐ ≈ 3` means merchants arrive in batches, and a batch
must fit in the slack) and the autoscaling lag. **Target ρ = 0.7**, giving
`1,320 / 0.7 ≈ 1,900` concurrent slots at peak. Provision for the *peak*, never
the 2,200/s daily mean — that mean is a number about billing, not capacity.

**Queue bound.** Late is worse than rejected, by the problem statement, so bound
tight. The queue drains at `c/S` = 1,900/0.22 ≈ 8,600 jobs per second, so a
queue of Q adds `Q/8,600` seconds of wait. Allowing ~300 ms of queue delay gives
**Q ≈ 2,500**. Anything beyond that is a customer you will answer too late,
which by the stated cost model is worse than the rejection you could have sent.

**Ordering.** FIFO normally, because fairness matters when money does. Adaptive
**newest-first above a queue-depth threshold**, paired with deadline dropping —
measured, that pairing is the difference between 0.7% and 73% answered in time
under overload.

**Admission control.** In priority order:
1. **Deadline-drop on dequeue** — never start an authorisation whose remaining
   budget is under ~250 ms. Highest value per line of code in the whole design.
2. **Bulkhead the slow tail.** If the 3-second responses correlate with a
   particular card network, issuer, or merchant, give that route its own
   concurrency limit. This is the one place where you deliberately give up
   pooling (costing ~5× on average wait for that route) to buy isolation for
   everyone else — justified here because `c²ₛ ≈ 6` is measured to cost roughly
   8× on the p99 of *all* traffic.
3. **Shed at the edge with a fast 503 and a jittered `Retry-After`.**
4. **Concurrency limit, not a rate limit** — see 8.3. The card network's latency
   is the one thing you do not control, and a concurrency limit adapts to it
   automatically.

**Idempotency is mandatory, not optional.** A late answer causes a merchant
retry, and a retry without an idempotency key causes a double charge. Every
authorisation carries a key; the gateway deduplicates on it. (See the
`idempotency` lab.) Without this, the whole design is unsafe under exactly the
conditions it exists to handle.

**Monitoring.** In-flight vs limit; queue depth and its derivative; spare
capacity (`c/S − λ`); the card network's own p50/p99, because a rise in S
silently consumes your concurrency budget; shed rate; and wasted server time.

**The one number that says the plan is failing: the fraction of authorisations
answered inside the merchant's 2-second deadline.** Not throughput, which stays
flat and healthy right through a total outage. Not latency, which has no steady
value once you are saturated. Goodput ratio is the only metric that moves in the
right direction for the right reason, and it is the one to put on the wall.

### 8.2 — The incident paragraph

> At 14:00 a campaign raised arrivals about 30%. The service had been running
> near its limit, so the increase took offered load past capacity. Past that
> point a queue does not settle at a new higher level — it grows at
> (arrivals − capacity) jobs per second, and latency grows linearly with elapsed
> time rather than plateauing. The p99 of 6 s at 14:10 was therefore not a
> measure of how slow the service was; it was a measure of how long it had been
> saturated. At 14:12 we scaled from 10 to 15 instances. Latency did not improve
> for nine minutes and then recovered within ninety seconds. That shape — flat,
> then a cliff — tells us the new capacity was not actually serving traffic
> until roughly 14:21: had the extra capacity been live at 14:12 and merely
> working off a backlog, latency would have declined steadily over those nine
> minutes rather than holding flat. The nine minutes is instance start-up plus
> health-check and warm-up time; the final ninety seconds is the backlog
> draining at the new spare capacity, which is fast precisely because we added
> 50% capacity rather than the 30% that would merely have restored balance.
>
> **What would have caught it at 13:00:** an alert on **spare capacity**
> (`capacity − arrivals`) falling below a floor, or equivalently on the latency
> multiplier `1/(1 − utilisation)` crossing a threshold. At 13:00 the service was
> already close enough to the edge that a 30% increase would saturate it, and a
> spare-capacity alert says so *before* any latency moves. A latency alert
> cannot: latency is nearly flat across the whole safe region and then vertical,
> so by the time it moves, the decision window has closed. The secondary alert is
> **queue depth rising steadily for 60 seconds**, which is direct evidence of
> ρ ≥ 1 and leads latency by minutes.

### 8.3 — Rate limit or concurrency limit

**Take the concurrency limit**, and the argument is Little's Law.

A concurrency limit of N automatically enforces an arrival rate of at most `N/S`,
because `N = λS`. It is therefore a rate limit that **re-derives itself whenever
S changes** — which is what you want, because S is the thing you do not control.

The scenario where the rate limit fails badly: the downstream dependency slows
from 50 ms to 200 ms. The rate limiter happily keeps admitting 8,000 req/s,
because req/s has not changed. But the concurrency required has gone from
8,000 × 0.05 = 400 to 8,000 × 0.2 = **1,600**. You do not have 1,600 threads, so
every one of them is now occupied, the queue in front of them grows without
bound, and the service fails — while the rate limiter reports that everything is
within policy. The limiter was enforcing the wrong invariant.

The concurrency limit of 400 handles the same event without configuration: at
S = 200 ms it simply admits 2,000 req/s instead of 8,000 and sheds the rest. You
have automatically degraded to what the system can actually do.

**Given the stated 4× daily variation in downstream latency, this is not close.**
A static rate limit is correct at exactly one point in that range and dangerous
above it. Go further and use an **adaptive** concurrency limit (Netflix's
`concurrency-limits`, Envoy's adaptive concurrency, or a TCP-Vegas-style
controller) which infers the right N from observed latency and adjusts
continuously.

The one thing the rate limit does better, and why you may end up with both:
concurrency limits are **not** a fairness or billing mechanism. They cannot say
"customer X gets 100 req/s". If you need per-tenant quotas, you need a rate
limiter too — but as a *product* control at the edge, with the concurrency limit
underneath it as the *safety* control.

### 8.4 — Where are my queues?

Every one of these is a real queue with a real bound. Defaults are for recent
versions and **should be verified against yours** — they change between releases,
and a wrong default here is worse than no default.

| # | Queue | Bounded by | Typical default | |
|---|---|---|---|---|
| 1 | Load balancer pending-connection queue | not tunable by you on ALB | — | ⚠️ invisible |
| 2 | Kernel accept backlog | `net.core.somaxconn` | 4096 | |
| 3 | Tomcat connection backlog | `server.tomcat.accept-count` | 100 | |
| 4 | Tomcat worker threads (this is `c`, not a queue) | `server.tomcat.threads.max` | 200 | |
| 5 | `@Async` / `ThreadPoolTaskExecutor` queue | `spring.task.execution.pool.queue-capacity` | **Integer.MAX_VALUE** | ⚠️ **unbounded** |
| 6 | `@Scheduled` pool | `spring.task.scheduling.pool.size` | 1 | ⚠️ serialises |
| 7 | HikariCP connection wait | `maximumPoolSize` / `connectionTimeout` | 10 / **30,000 ms** | ⚠️ effectively unbounded in time |
| 8 | PostgreSQL backend slots | `max_connections` | 100 | |
| 9 | PostgreSQL lock waits | `lock_timeout` | **0 = wait forever** | ⚠️ unbounded |
| 10 | Kafka producer accumulator | `buffer.memory` / `max.block.ms` | 32 MB / **60,000 ms** | ⚠️ 60 s is not a timeout, it is an outage |
| 11 | Outbound HTTP connection pool | client-specific (Apache HttpClient 4: 20 total / 2 per route) | 2 per route | ⚠️ silently serialises fan-out |

**Fix first: number 5**, `spring.task.execution.pool.queue-capacity`. It is the
only one whose default is unbounded *in count*, which means it does not merely
degrade under overload — it converts a latency problem into an
`OutOfMemoryError`, and an OOM takes down the requests that were going to succeed
along with the ones that were not. Set it from the deadline:
`servers × (deadline/S − 1)`, and set a rejection policy (`AbortPolicy` for a
fast 503, or `CallerRunsPolicy` when you want real backpressure on the submitter).

**Close second: number 7's `connectionTimeout` of 30 seconds.** It is bounded, so
it will not OOM, but 30 seconds is 30× any sane request deadline — which means a
five-second database blip holds every request thread for half a minute and
converts a small dependency hiccup into a full outage. Set it below your request
deadline, always. Numbers 9 and 10 are the same mistake in other people's
libraries.
