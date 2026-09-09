# Load Shedding — Exercises

Eight rungs, easiest first. Nothing here asks you to write code. Everything is
reasoning, tracing, prediction, or design, and most rungs can be checked against
`visualization.html`.

**The standing setup**, unless an exercise says otherwise:

> A service with **4 workers**, each request taking **80 ms** on average.
> Capacity = **50 requests/second**. Clients give up after **1 second**.

Work them before opening `SOLUTIONS.md`. Rungs 6, 7 and 8 have no single right
answer; the solutions give a worked line of reasoning instead.

---

## Rung 1 — Explain

**1.1** In your own words, and without using the word "shed": why does rejecting
two-thirds of arriving requests *increase* the number of customers served?

**1.2** A colleague says: *"Load shedding is just rate limiting applied to
yourself."* Give the sharpest one-sentence correction you can, then name a
situation where a rate limiter is running correctly and does nothing to prevent
an overload.

**1.3** Explain to someone who has never heard of it why **throughput** was
50.1/s during a total outage and 50.0/s during optimal operation, and what number
they should have been looking at instead.

**1.4** Why is *"we're already at capacity, so shedding would only make things
worse"* backwards?

---

## Rung 2 — Identify

**2.1** For each of these, say which of the three levers it is — **admission
control**, **queue order**, or **drop rule** — and what it protects:

  a. `if (queue.size() >= 20) return 503;`
  b. serving the newest request in the queue first
  c. discarding a request at the head whose client gave up 200 ms ago
  d. a connection pool with 10 connections and no acquire timeout
  e. `Retry-After: 2`

**2.2** In the control-loop model, name the **signal**, the **set point** and the
**actuator** for: (i) a queue cap of 20, (ii) CoDel with target 5 ms, (iii) an
AIMD concurrency limit.

**2.3** A service reports: throughput 50/s, worker utilisation 100%, median
latency of served requests 210 ms, queue depth 14, rejection rate 67%. Is this
service healthy? What single extra number would settle it?

**2.4** Which of these five errors should a client retry, and which not?
`Connection refused` · `503 with Retry-After from a shedder` · `504 gateway
timeout` · `429` · `500 from an unhandled exception`.

---

## Rung 3 — Predict

Predict first, then check with the visualization.

**3.1** Offered load 60/s against capacity 50/s, no protection. What share of
customers get an answer? (The arithmetic answer is 83%. The measured answer is
not.)

**3.2** Same service, offered 150/s. You set the queue cap to **100**, which is
600× smaller than the 60,000-deep queue you saw without it. What success rate do
you get?

**3.3** At 150/s with a queue cap of 20 and clients configured for 3 attempts,
goodput is 50/s. Now the clients switch from 200 ms backoff to 1 second with full
jitter. What happens to (a) goodput, (b) the number of admission decisions per
second?

**3.4** A service is at **exactly** 100% utilisation — offered 50/s, capacity
50/s — with no shedding. You run the same configuration twenty times. How much do
the results vary, and does running longer make the answer more reliable?

**3.5** At 3× overload, with **no admission control at all**, you change one word
so the queue is served newest-first. Predict the effect on: success rate, median
latency, wasted work, and queue depth. (One of those four does not improve.)

---

## Rung 4 — Modify

**4.1** Your queue cap of 20 is measured optimal. A downstream database slows from
80 ms to 400 ms per request while traffic stays at 60/s. Without re-reading the
lab: what is the new capacity, what is the new correct cap, and by what factor is
your configured cap wrong?

**4.2** You double the worker pool from 4 to 8. Which of these settings must
change, and which are unaffected? (a) a queue cap of 20, (b) an in-flight limit
of 12, (c) a CoDel target of 50 ms, (d) the client's 1-second timeout.

**4.3** Rejections currently cost 2 ms of CPU each. You move shedding to the
gateway, where a rejection costs 0.4 ms. By what factor does the offered load you
can survive change, and what is the new ceiling?

**4.4** Your criticality fractions are checkout 100%, browse 60%, recommendations
25%, applied to a fixed in-flight limit of 12. Traffic is a healthy 35/s and the
workers are 69% busy. Recommendations are being rejected. Explain exactly why,
and give two different fixes — one that changes a number and one that changes the
design.

---

## Rung 5 — Trace

Work these by hand. Show the state after each step.

**5.1 — the arithmetic of a doomed request.** 4 workers, 80 ms each, client
deadline 1,000 ms. A request arrives and finds **46 requests** already queued.

  a. How long until a worker picks it up?
  b. When does it complete?
  c. Does its client still care?
  d. The formula `q_max = c·(D/S − 1)` gives 46 for these numbers. The measured
     best cap is around 20. Reconcile those two facts in one sentence.

**5.2 — where the wasted work comes from.** Offered 150/s, capacity 50/s, no
shedding, FIFO, run for 60 seconds.

  a. How many requests arrive? How many can be finished?
  b. How long is the queue at t = 60 s?
  c. A request that joins the queue at t = 60 s — when does it reach a worker?
  d. From roughly what time onward is *every* newly-queued request already
     certain to fail? What does that imply about the fraction of worker time that
     is useful after that moment?

**5.3 — the rejection ceiling.** 4 workers. Each rejection costs 2 ms of worker
time; each served request costs 80 ms.

  a. How much worker time exists per second?
  b. At an offered load of 1,000/s with an in-flight limit of 12, roughly how
     many rejections per second, and how much worker time do they consume?
  c. At what offered load does rejection alone consume the whole machine?
  d. Now suppose you also want to keep serving 40 requests/s. What is the highest
     offered load at which that is still possible?

**5.4 — the retry multiplier.** 150 user requests/s arrive at a service with a
queue cap of 20 that admits 50/s and rejects the rest. Clients make up to 3
attempts and retry both rejections and timeouts.

  a. Attempt 1: how many admitted, how many rejected?
  b. Assume every rejected request retries. Attempt 2: how many arrive?
  c. Continue to attempt 3. What is the total arrival rate at the door?
  d. A first pass usually gives about 317/s. The measured figure is **394/s** —
     *higher*. What feedback did the first pass leave out?

---

## Rung 6 — Spot the flaw

Each design below is subtly wrong. Find the smallest concrete scenario where it
fails, and name the guarantee it breaks.

**6.1** *"We shed when CPU exceeds 85%. It's a single line in the load balancer
config and it needs no application changes."*

**6.2** *"Our shedder is off by default. When we get paged for an overload, the
on-call engineer sets `shedding.enabled=true` and picks a threshold appropriate
to the incident."*

**6.3** *"We return 503 with `Retry-After: 1` to everyone we shed. Our client
library honours `Retry-After` exactly."*

**6.4** *"We shed uniformly at the edge — a fixed 30% of all requests during
overload — so every service downstream gets 30% less traffic. It's fair and it's
simple."*

**6.5** *"Our in-flight limit is 50, computed properly from Little's Law:
capacity 50/s × a 1-second deadline = 50 requests in flight."*

**6.6** *"We put a load shedder in front of every endpoint, including
`/healthz`, so the protection is uniform and there are no gaps."*

**6.7** *"We use LIFO under overload — the lab measured it reaching the
theoretical maximum goodput with no configuration at all. So we don't need an
admission controller."*

---

## Rung 7 — Handle failure

Design answers, not single values.

**7.1** Your shedder is working: at 3× overload it holds goodput at the ceiling.
Then the *rejections themselves* start timing out. What has happened, how would
you confirm it from metrics, and what are your options — given that you cannot
add capacity in the next five minutes?

**7.2** A one-off burst knocked your service into overload forty minutes ago. The
cause is long gone — the batch job that fired it finished, and offered load is
back to normal — but the service is still failing every request. Explain the
mechanism precisely, then give the *minimum* intervention that restores service
and say why the obvious alternative (waiting) does not work.

**7.3** Service A calls B calls C. C becomes overloaded and starts shedding
correctly. Describe what each of A and B sees, and where each of these belongs:
a timeout, a retry, a circuit breaker, a bulkhead, an inbound shedder, an
outbound concurrency limit. Which of them protects **C**?

**7.4** You ship an adaptive concurrency limit. In staging it is excellent. In
production it settles at its minimum value of 2 and stays there, and your
goodput is a fifth of what a fixed limit of 12 achieved. Give three distinct
hypotheses consistent with the lab's findings, and the measurement that
distinguishes each.

**7.5** Your load shedder is now the reason a customer's daily 09:00 batch fails.
They send 20,000 requests in a burst; you shed most of them; they escalate.
Nothing is broken. What do you actually do — and what is the argument for *not*
simply raising the limit?

---

## Rung 8 — Design

**8.1 — A payments API.** 30,000 requests/minute at peak, of which ~4% are
payment authorisations and the rest are status polls from merchant dashboards.
Authorisations call an external card network with a 3-second SLA and a p99 of
1.2 s; polls read your own database in 15 ms. Design the shedding scheme.
Address: where shedding happens, what signal, priority classes, what the poll
traffic should be told, why the two request types cannot share a single limit,
and what you monitor. Then say what breaks if the card network's latency
doubles.

**8.2 — Choosing a signal, with evidence.** You have one quarter and can ship
exactly one of: (a) a static in-flight limit, (b) adaptive LIFO + CoDel, (c) a
Netflix-style adaptive concurrency limit. Make the case for one, using measured
figures from the lab, and state the specific circumstances in which your choice
would be the wrong one.

**8.3 — The dashboard that would have caught it.** An incident review concludes
"the service was overloaded for 40 minutes and no alert fired." You are asked for
the metrics and alerts that would have caught it in under two minutes. Give at
most four metrics and at most two alerts, and for each metric say which failure
from stage 11 it detects. Explain why a p99 latency alert would *not* have
fired.

**8.4 — Counterfactual.** Load shedding, as a mechanism, is entirely about the
fact that a server cannot see the client's deadline. Suppose it could — every
request arrives carrying exactly how many milliseconds of budget it has left, and
the number is always honest. Redesign the service's overload behaviour from
scratch. Which of the lab's mechanisms become unnecessary, which become simpler,
and which are still required? Is admission control still needed at all?

**8.5 — Multi-tenant fairness.** Your shedder is blind to identity by design. One
customer is 80% of your traffic and causes an overload; every other customer is
degraded so that one can continue at four-fifths strength. Design a fix. State
what it costs you when nothing is wrong, and what the failure mode of your fix
is.
