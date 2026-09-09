# Load Balancing — Exercises

Eight rungs, easiest first. Nothing here asks you to write code. Everything is
reasoning, tracing, prediction or design, and most rungs can be checked against
`visualization.html`.

**The standing setup**, unless an exercise says otherwise:

> **Eight backends, named A to H.** Each has **four workers**, each request takes
> **80 ms** on average. So each backend can finish **50 requests a second** and
> the fleet can finish **400**. `ρ = 0.9` means 360 requests a second arriving.
> The p99 of the service time alone is about **370 ms** — that is the floor, and
> everything above it is queueing.

Work them before opening `SOLUTIONS.md`. Rungs 6, 7 and 8 have no single right
answer; the solutions give a worked line of reasoning instead.

---

## Rung 1 — Explain

**1.1** In your own words, and without using the words "balance" or "spread":
what is a load balancer actually for? Your answer should survive the objection
*"but round robin already gives every server exactly the same number of
requests, and the fleet still missed its SLO."*

**1.2** A colleague says: *"We added a load balancer, so we have more capacity
now."* Give the sharpest one-sentence correction you can, then say what the
load balancer **did** buy — and name the load level at which it buys nothing.

**1.3** Explain to someone who has never heard of any of this why eight servers
behind a load balancer are **worse** than one server eight times bigger, and why
we build it the worse way anyway.

**1.4** Round robin is described in this lab as "a bet". What is it betting on,
what does it win when the bet is right, and what does it cost when the bet is
wrong? Answer with the two ends of the c²ₛ table.

**1.5** Why is *"our least-connections balancer has a 15-second view of backend
load, but that's fine because load changes slowly"* wrong? Do not answer with a
number — answer with the mechanism.

---

## Rung 2 — Identify

**2.1** For each of these, say whether it is a **dispatch rule**, a **candidate
filter** (something that decides which backends are eligible at all), or
**neither**, and what failure it addresses:

  a. `least_conn` in an NGINX upstream block
  b. `max_ejection_percent: 50`
  c. `slow_start: 30s`
  d. a `preStop` hook that sleeps 15 seconds before the process exits
  e. `random two least_conn`
  f. `ip_hash`
  g. a `readinessProbe` that returns 200 from a handler with no dependencies

**2.2** In the engineering model, name the **view**, the **rule** and the
**backend set** for each of these deployments:

  i. One HAProxy instance with `leastconn` in front of eight app servers.
  ii. Twenty NGINX pods with `least_conn` in front of the same eight.
  iii. A gRPC client using the `round_robin` policy with a resolver returning
       all eight addresses.
  iv. An AWS Network Load Balancer in front of the same eight.

**2.3** Which of the four failures below can each of these signals detect, and
which is it blind to? Signals: (a) queue depth, (b) observed response time,
(c) an HTTP health check on `/health`, (d) error rate per backend.
Failures: (i) a backend that is 5× slow, (ii) a backend returning 500s in 1 ms,
(iii) a backend that has just restarted and is cold, (iv) a backend whose disk
is full but whose `/health` handler does not touch the disk.

**2.4** A fleet is at ρ = 0.8 and one backend is receiving 42% of all traffic.
Give **three different** mechanisms that could produce that number, and say what
you would look at to tell them apart.

---

## Rung 3 — Predict

Commit to an answer before checking. Most of these can be verified in the
visualization.

**3.1** Fleet at ρ = 0.5. You switch from random dispatch to least-connections
with a live view. By what factor does the p99 improve?

**3.2** Same switch, at ρ = 0.95. By what factor now?

**3.3** Every request takes **exactly** 80 ms — no variation at all. Rank these
four on p99, best first: random, round robin, power of two, least connections.
Is any of them equal to the shared-queue ideal?

**3.4** A fleet uses least-connections, and its view of backend load is refreshed
by a metrics scrape every **15 seconds**. Is this better or worse than random
dispatch? By roughly how much? (Extrapolate from the τ table; do not expect a
precise figure.)

**3.5** Backend A starts returning HTTP 500 after 1 ms instead of doing 80 ms of
work. Predict A's share of fleet traffic under: round robin, power of two,
least connections, and least-response-time. Fair share is 12.5%.

**3.6** Now backend A instead becomes 5× slower. Predict the same four shares.

**3.7** Eight backends. Sixteen client processes, each holding one long-lived
HTTP/2 connection, placed by an L4 balancer that hashes the 5-tuple. Fleet at
ρ = 0.8. Predict the busiest backend's share of requests.

**3.8** A fleet of eight is at ρ = 0.875 and every backend is healthy, with a p99
of about 400 ms. One backend is lost. Predict the p99 five minutes later.

---

## Rung 4 — Modify

**4.1** Take the power-of-two rule and change `d` from 2 to 4. Using the table in
stage 10, say what you gain, what you pay, and whether you would ship it.

**4.2** Change the fleet from 8 backends to 128, holding ρ = 0.9. What happens to
the **worst queue** under random dispatch, and what happens to it under power of
two? Explain the direction of each.

**4.3** Take the least-connections rule and change the comparison from
`v[i]` to `v[i] / weight[i]`. What problem does this solve, and what new failure
does it introduce that plain least-connections did not have?

**4.4** A team's fleet has c²ₛ ≈ 0 — every request genuinely costs the same,
because it is a fixed-size fixed-work transform. They currently run
least-connections. What would you change, and what would you tell them they are
giving up?

**4.5** You cannot change the dispatch rule (it is in a vendor appliance), but
you *can* change the backends. Name two changes to the backends that would
improve the fleet's p99 without touching the balancer, and say which lever in the
model each one moves.

---

## Rung 5 — Trace

Trace by hand. Show the state after each step.

**5.1** Four backends, one worker each, every request taking exactly 100 ms.
Queues at t = 0 are `A:0  B:2  C:1  D:0` (counting requests in service).
Requests arrive at t = 0, 10, 20, 30, 40 ms. Trace the destination of each under
**round robin** starting at A, and under **least connections**. Which finishes
the fifth request sooner, and by how much?

**5.2** Same four backends and the same arrivals, but the dispatcher's view is a
snapshot taken at t = 0 and refreshed every **50 ms**. Trace least-connections
again. Where do the first five requests go? State the queue depths at t = 45 ms
and compare with 5.1.

**5.3** Eight backends. Backend A fails in 1 ms; the other seven take 80 ms.
Least-connections with a live view. Starting from all queues empty, trace the
first ten arrivals (spaced 3 ms apart) and give A's share. Then explain, in one
sentence, why the share you traced is *higher* than the 66.1% the ten-minute
measurement reports.

**5.4** Power of two, eight backends, current queue depths
`A:1 B:7 C:0 D:3 E:9 F:2 G:0 H:4`. The dispatcher samples these pairs in order:
(B,E), (A,C), (E,H), (G,B), (D,F). Give the destination of each of the five
requests and the depths afterwards. Then state the probability that a single
request under this rule lands on E (the worst queue), and compare it with the
probability under random dispatch.

---

## Rung 6 — Spot the flaw

Each design below is plausible and each is broken. Construct the **smallest
concrete scenario** where it fails, and name the guarantee it violates.

**6.1** *"Our balancer routes to whichever backend has the lowest average
response time over the last 10 seconds. It is strictly better than
least-connections because it accounts for how fast each backend actually is."*

**6.2** *"We run 30 load balancer pods, all using least-connections against a
shared Redis key that every pod increments on dispatch and decrements on
completion. Now every pod has the true global view, so we get the optimal
result."*

**6.3** *"Our health check hits `/health`, which returns 200 if the process is
up. We check every 2 seconds and eject after a single failure, so we detect
problems fast."*

**6.4** *"We use consistent hashing on the user ID so that each user always hits
the same backend and gets a warm local cache. We have 50,000 daily active users
and 10 backends, so the load will be even."*

**6.5** *"When a request fails, the load balancer retries it on a different
backend, up to 3 times. This turns a single-backend failure into a non-event."*

**6.6** *"Our fleet auto-scales on average CPU with a target of 75%. We run 4
pods minimum. We are safe against a single-pod failure because 75% is well below
100%."*

---

## Rung 7 — Handle failure

Design answers, not one-liners.

**7.1** You have exactly the situation in stage 11's F1: one backend of eight is
returning 500s in 1 ms and your least-connections balancer is sending it 66% of
traffic. You cannot deploy a code change for 40 minutes. What do you do in the
next five minutes, and what do you do in the next thirty? Order your actions by
how much they help and how likely they are to make things worse.

**7.2** Design a health check for a service whose real work is
"read from Postgres, transform, write to Kafka". State what the check does, how
often it runs, what the eject and restore thresholds are, and — most
importantly — what failure your check will **not** catch. Then say what second
mechanism covers that gap.

**7.3** A backend is being removed for a deploy. Describe the full sequence from
"we decide to remove it" to "the process exits", such that no in-flight request
is lost and no new request is sent to it. Name what breaks at each step if you
skip it.

**7.4** Your gRPC service has 40 client pods and 200 server pods, and you have
just learned about F6. Client-side balancing would need 8,000 connections. Design
a subsetting scheme: how big is a subset, how is it chosen, what happens when a
server pod is added or removed, and what is the failure mode if two clients
happen to choose overlapping subsets?

**7.5** You are asked to add "retry on a different backend" to your balancer.
Write down the rules you would enforce so that this cannot cause a retry storm,
and justify each rule with a specific failure it prevents.

---

## Rung 8 — Design

Longer, realistic, no single right answer.

**8.1 — The gRPC hot pod.** A payments service runs 12 pods behind a Kubernetes
`Service`. It is called by 6 upstream services over gRPC. One pod is
consistently at 90% CPU while the others sit at 35%, it changes identity roughly
weekly, and restarting it moves the problem elsewhere. Diagnose it from that
description alone, say what measurement would confirm your diagnosis, and give
three fixes in increasing order of how much they change the architecture. For
each, say what it costs.

**8.2 — Choosing a rule with a straight face.** You are asked to pick a dispatch
rule for a new service. Write the decision as a short set of questions whose
answers determine the choice — not a preference. Your questions must include the
number of dispatchers, the staleness of the view, and the variability of request
cost, and must arrive at a *specific* rule for each combination. Then say which
four things you would fix **before** the rule choice matters at all.

**8.3 — Sticky sessions you cannot remove.** A legacy service keeps session state
in each backend's memory. It has 8 backends and ~200,000 sessions, but the
largest tenant accounts for 18% of all requests. Traffic is at ρ = 0.75. What
happens today, what happens when they add a 9th backend, and what are your
options if you are told the in-memory state cannot be moved this quarter? Rank
them.

**8.4 — Capacity for a small fleet.** A team runs a 3-pod deployment scaled on a
CPU target of 70%. They deploy 5 times a day with a rolling update
(`maxUnavailable: 1`). Work out what happens during each deploy, using the
`(N−1)/N` rule and the ρ table. Then give them a plan that keeps their cost
increase as small as you can make it, and say what you would monitor to know
whether the plan worked.

**8.5 — What would you put on the dashboard?** You are handed a service with a
perfectly ordinary dashboard: fleet request rate, fleet p50/p99, per-pod CPU,
error rate. List which of this lab's eight failures are **invisible** on it, and
design the smallest set of additional graphs that would make each of them
visible. For each graph, state the shape you would see when it is healthy, so an
on-call engineer knows what "wrong" looks like.

**8.6 — Counterfactual.** Power of two choices exists because `argmin` over all
backends herds under stale information. Suppose that were not true — suppose
every dispatcher had a genuinely live, free, exact view of every backend. Which
parts of this lab would survive unchanged, which would disappear, and which
production mechanisms would you still need? Be specific about F1.
