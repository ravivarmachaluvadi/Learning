# Queueing theory — exercises

Eight rungs, from "say it back" to "design the capacity plan". Everything here
is pencil-and-paper or `visualization.html`. **No code to write.**

Answers in `SOLUTIONS.md`. Attempt first — several of these are designed so that
being wrong is the lesson.

**Constants used throughout,** unless a question says otherwise:
one server, service time **S = 80 ms**, so capacity **μ = 12.5 requests/second**.

---

## Rung 1 — EXPLAIN (say it back, no notation)

**1.1** A colleague says: *"CPU is at 80%, so we have 20% headroom before we
have a problem."* In your own words, and without using a single symbol, explain
what is wrong with that sentence. Your explanation must contain the reason a
queue forms while there is still spare capacity.

**1.2** Explain to someone who has never heard of it why Little's Law
(`stuff inside = rate in × time inside`) is useful. Give one thing you could
work out with it that you could not measure directly.

**1.3** In one sentence each, distinguish:
- utilisation from throughput
- throughput from goodput
- waiting time from service time
- backpressure from load shedding

**1.4** Why is a queue that is 5% over capacity a categorically different
problem from a queue that is 5% under capacity, rather than a slightly worse
version of the same problem?

---

## Rung 2 — IDENTIFY (point at the pieces)

Open `visualization.html`. Leave every dial at its default (10 requests/second,
80 ms of work, one server, random arrivals, random work). Press
**Measure 20 min**.

**2.1** Read these four numbers off the screen and write them down:
utilisation, average wait, average total time, 99th percentile.

**2.2** Which of those four is `Wq` and which is `W`? What is the difference
between them equal to, and does your reading confirm it?

**2.3** Press **Reset**, then **Play**. Click a bar in the line while it is
waiting. From the inspector alone, say: how long it has been waiting, what
position it is in, and whether it has any chance of being served soon.

**2.4** Find the dot on the "where you are standing on the cliff" chart. Now
drag the *requests per second* slider slowly from 1 to 12.4. At roughly what
value does the curve stop looking like a straight line?

**2.5** Set *how long each takes* to **one slow tenant (c²=16)**. Read the hint
under the dropdown. What fraction of jobs are slow, how slow are they, and what
happened to the **average** service time?

---

## Rung 3 — PREDICT (commit, then check)

Write your answer down *before* checking. Every one of these can be checked in
the visualization.

**3.1** A service is at 10 req/s with 80 ms of work (80% busy). You make the
handler twice as fast — 40 ms — and traffic is unchanged. By what factor does
the **average total time** improve? *(Careful: it is not 2×.)*

**3.2** Two services. Both 80% busy, both averaging 80 ms of work. Service A's
work takes exactly 80 ms every time; service B's varies with c²ₛ = 16. Predict
the ratio of their average waits, to the nearest whole number.

**3.3** You have 4 servers at 80% busy, arranged as 4 separate queues. You
merge them into one shared queue. Predict the new average wait as a fraction of
the old. *(Scenario 4.)*

**3.4** A queue is 5% over capacity and has been for 10 minutes, with 685 jobs
waiting. You cut traffic to 85% of capacity. Predict how long until the queue is
empty. Now predict it again for a cut to 95% of capacity.

**3.5** A service at 85% busy, clients time out at 600 ms and retry twice. A
one-off burst of 400 requests arrives and never repeats. Predict the queue
length 20 minutes later. *(Scenario 6. Almost everyone gets this wrong.)*

**3.6** 9,000 requests arrive at a service that can complete 7,700, with a
1-second client deadline. Rank these queue caps by how many customers get an
answer *in time*: unbounded, 100, 20, 5, 0. *(Scenario 7.)*

---

## Rung 4 — MODIFY (change one thing, name what breaks)

**4.1** In the visualization, set arrivals to **clockwork (c²=0)** and work to
**identical (c²=0)** at 80% busy. Record the average wait. Now change *only*
the arrival pattern to **random**. What broke, and which term of Kingman's
formula did you just move?

**4.2** Set the maximum line length to **unbounded** and the give-up time to
**1000 ms**, with load at 15 req/s (120% of capacity). Run it. Now set the
maximum line length to **5** and run again. Name the metric that got 100×
better and the metric that did not change at all. Why did throughput not change?

**4.3** Set 4 servers, one shared line, 80% busy. Now switch to **a line per
server**. Which property did you lose? Is it a safety property (something bad
now happens) or a liveness property (something good now takes longer)?

**4.4** With the retry switch **on** and a 600 ms give-up time at 85% load,
press **⚡ Drop in 40 at once** repeatedly until the queue stops recovering.
Roughly how many bursts did it take? Now turn retries **off** and try to
reproduce it. Can you?

**4.5** Set the ordering to **newest first** with a bounded queue under
overload. One metric gets dramatically better and one gets dramatically worse.
Name both, and say which customers pay for the improvement.

---

## Rung 5 — TRACE (execute it by hand)

**5.1** One clerk, 80 seconds per person, serving in arrival order. These people
arrive (times in mm:ss): `0:00, 0:30, 0:55, 4:10, 9:00, 9:20, 9:35, 9:50`.

Fill in the table by hand — no simulator:

| # | arrives | clerk free at | starts | **waits** | leaves |
|---|---|---|---|---|---|
| 1 | 0:00 | | | | |
| … | | | | | |

Then answer: how much total idle time did the clerk have, and how much total
waiting did the eight people do? Why can't the first number be used to reduce
the second?

**5.2** Same eight arrivals, but now there are **two** clerks sharing one line.
Redo the table. How much waiting is left?

**5.3** Using only the M/M/1 formulas, compute by hand for λ = 11 req/s and
S = 80 ms: ρ, W, Wq, L, Lq. Then compute what the 99th percentile of total time
would be. Check every one against the visualization (**Measure 20 min**).

**5.4** A service completes 400 requests/second and each spends an average of
250 ms inside. How many requests are inside at any moment? Which law did you
use, and what did you have to assume about the service to use it? *(Trick: the
honest answer to the second part is "almost nothing".)*

**5.5** Trace the metastable loop of failure F3 as a cycle of five sentences,
each starting with "which causes…". Start from "a burst arrives".

---

## Rung 6 — SPOT THE FLAW

Each of these is a described design, not code. Find the concrete failure and
name the guarantee it violates.

**6.1** A team configures their executor like this, and reasons: *"the queue is
unbounded so we never reject a customer — rejecting is rude, and this way
everyone eventually gets served."*

```java
new ThreadPoolExecutor(8, 8, 60L, SECONDS, new LinkedBlockingQueue<>());
// callers: pool.submit(handleRequest);  clients time out at 2 seconds
```

Construct a concrete run — with numbers — where this design serves **fewer**
customers successfully than one that rejects people. What is the name for the
work it is doing?

**6.2** A capacity plan reads: *"Peak is 4,000 req/s. Each request takes 50 ms.
So we need 4,000 × 0.05 = 200 threads. We'll provision 200 and set the queue to
10,000 so nothing is ever dropped."*

There are **three** separate mistakes. Find all three.

**6.3** A client library is configured: *"On timeout, retry immediately, up to 3
times. Timeout is 1 second."* The service behind it is healthy at 70%
utilisation. Describe the specific sequence of events that takes this system
from healthy to permanently down, and identify the moment after which turning
off the original trigger no longer helps.

**6.4** A team splits their single shared thread pool into one pool per tenant,
"for isolation", keeping the same total thread count and the same total traffic.
They are surprised that average latency got worse. Explain why, and say under
what circumstance their change was nevertheless the right call.

**6.5** A load test reports p99 = 95 ms using 100 threads, each of which sends a
request, waits for the response, and immediately sends another. Production, at
the same requests-per-second, shows p99 = 1.8 s. The test harness has no bug.
Explain the discrepancy precisely, and describe what the harness would have to
do differently.

**6.6** A monitoring runbook says: *"If p99 latency exceeds 2 s for 5 minutes,
page. If throughput drops below 5,000 req/s, page."* A service saturates at
110% of capacity at 02:00 and stays there. Which alert fires, when, and what
does the on-call engineer see on the throughput graph? What should the runbook
have alerted on instead?

---

## Rung 7 — HANDLE FAILURE (extend the design on paper)

**7.1** Your service has an unbounded queue, no deadlines, and FIFO ordering. On
paper, add **three** mechanisms so that under a 20% overload it still answers
most customers in time. For each: what it does, what it costs, and what it
cannot save you from.

**7.2** Design a retry policy for a client fleet of 5,000 instances calling one
service. It must (a) recover from a genuinely transient single-request failure,
and (b) be incapable of producing the F3 storm. State the rules precisely enough
that someone could implement them, and explain which rule is the one that
actually prevents the storm.

**7.3** Your service has one slow tenant: 3% of requests take 16× the average,
and they are all from one customer. Everyone else's p99 has tripled. Propose two
different fixes — one that isolates, one that reorders — and for each, name who
is worse off afterwards.

**7.4** An autoscaler adds a new instance 3 minutes after it decides to. Traffic
step-changes from 80% to 130% of current capacity at 09:00. Describe minute by
minute what happens to the queue, and state what the autoscaler should be
scaling on (and why CPU is the wrong signal here).

**7.5** Deadline propagation: a request arrives with 900 ms of budget and must
visit three services in series. Describe the rules each service follows, and
explain what each one can now do that it could not before.

---

## Rung 8 — DESIGN (production scale)

**8.1 — The payments gateway.**
Design the capacity and admission-control plan for a card-authorisation gateway.

Constraints:
- Peak **6,000 authorisations/second**; the daily mean is 2,200/s.
- Each authorisation makes one call to a card network taking **220 ms ± a lot**
  (c²ₛ ≈ 6: most are 120 ms, a few are 3 seconds).
- Arrivals are bursty (c²ₐ ≈ 3) — merchants batch.
- The merchant's client times out at **2 seconds**, hard.
- A rejected authorisation costs money; a *late* one costs more, because the
  merchant retries and you may double-charge.

Produce: the concurrency you need and why; the utilisation you will target and
what fixed it; the queue bound; the ordering policy; what you shed and when;
what you monitor and what you alert on; and the one number that tells you the
plan is failing.

**8.2 — Explain the incident.**
Write the postmortem paragraph for this: at 14:00 a marketing campaign raised
traffic 30%. Latency p99 went from 400 ms to 6 s. At 14:12 the team scaled from
10 to 15 instances; latency did not improve for 9 minutes, then dropped to
normal within 90 seconds. Explain, quantitatively, both the delay and the
suddenness. Then say what alert would have caught this at 13:00.

**8.3 — The knob argument.**
Your platform team wants a global rate limit of 8,000 req/s on a service.
Another engineer wants a concurrency limit of 400 in-flight requests. Argue for
one, using Little's Law, and name the specific scenario in which the one you
rejected fails badly. Then say what you would do if you could only pick one and
the downstream dependency's latency is known to vary by 4× across the day.

**8.4 — Where are my queues?**
For a Spring Boot service behind an application load balancer, calling
PostgreSQL through HikariCP and publishing to Kafka: list every queue a request
passes through, name the configuration property that bounds each one, and give
its default. Mark the ones whose default is effectively unbounded. Then say
which single one you would fix first and why.
