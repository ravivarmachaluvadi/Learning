# Microservices — Exercises

Eight rungs, easiest to hardest. Everything here is pencil-and-paper or done in
`visualization.html`. Nothing is code.

Worked answers are in [SOLUTIONS.md](SOLUTIONS.md). **Attempt rungs 1–5 before
opening it** — for rung 3 in particular, write your prediction down first. Being
wrong on paper is the cheapest learning available.

Numbers used throughout, from the lab:
`orders` has **200 threads**; checkout traffic is **100 req/s**; a healthy
`payments` call is **20 ms**; each service is **99.9%** available; a year is
**8760 hours**.

---

## Rung 1 — Explain

**1.1** Explain to a product manager, in under 100 words and with no technical
nouns, why the company is considering splitting its system — and what it will
cost. Do not use the words *microservice*, *service*, *deploy*, *API* or
*database*.

**1.2** A colleague says: *"We're microservices now — we have twelve
repositories and twelve pipelines."* In two sentences, say what question you
would ask to find out whether that is true, and why that question and not
another.

**1.3** Why is a **slow** dependency more dangerous than a **dead** one? Answer
in three sentences, and make sure one of them contains a multiplication.

---

## Rung 2 — Identify

Open `visualization.html`.

**2.1** Tab **3 · The Cascade**, before touching anything. Read off the screen:
how many of `orders`' threads are in use, how many of those are inside a
`payments` call, and what the current checkout success rate is.

**2.2** Same tab. Run scenario **S2 (Payments SLOW)** and pause it the moment
the thread gauge turns amber. Which endpoint fails first — `POST /checkout` or
`GET /orders/{id}` — and does the screen tell you *why* the second one fails?

**2.3** Tab **2 · The Arithmetic**. With five services at 99.9%, read off the
downtime per year. Now find the smallest number of services you could have and
still be under 20 hours per year.

**2.4** Tab **5 · Boundaries**. In the "cut by layer" arrangement, which single
feature touches the most services, and how many? Which arrangement makes the
median feature touch exactly one?

---

## Rung 3 — Predict

Write your answer down, then check it in the simulator.

**3.1** `payments` latency goes from 20 ms to **800 ms** (not 5 s), traffic
unchanged. Does `orders` survive? Show the arithmetic before you run tab 3.

**3.2** Same 5-second `payments` slowdown, but `orders` has been given **2,000**
threads instead of 200. Predict what happens now — and whether "more threads" is
a fix.

**3.3** A timeout of 1 s is in place, no retries, `payments` is sick at 5 s.
Predict the checkout success rate and the fate of `GET /orders/{id}`.

**3.4** Tab **4 · The Tail**. `orders` fans out to 6 services in parallel. Each
one exceeds 200 ms for 1 request in 100. What fraction of checkouts exceed
200 ms? Predict, then check. Now predict for 40 services before you move the
slider.

---

## Rung 4 — Modify

**4.1** In tab 3, turn the circuit breaker **on** but the bulkhead **off**,
while `payments` is sick. Then the reverse. Which one keeps
`GET /orders/{id}` alive, and which one makes checkout fail *quickly*? Name the
property each is protecting.

**4.2** Set the retry count to 3 with **no** backoff and no retry budget, with a
1-second timeout, while `payments` is sick. Describe what happens to two
numbers: `orders`' thread occupancy, and the request rate arriving at
`payments`. Which of safety or liveness is lost?

**4.3** In tab 3 scenario **S7**, move `payments` off the critical path so
checkout returns before it is charged. Now name three things that became the
product's problem rather than the engineer's, and say who has to decide each.

**4.4** In tab 1, switch from private stores to a shared store and rename a
field. Then switch back and rename the same field. The rename is identical in
both cases — describe precisely what is different about the *consequence*, and
why the difference is not visible in any code.

---

## Rung 5 — Trace

Do this one entirely by hand, on paper, then check against tab 3.

Starting state at `t = 0.0 s`:

- `orders`: 200 threads, 2 in use, no timeout, no retries, no bulkhead, no
  breaker.
- Arrival rate: 100 checkout req/s, steady, plus 20 req/s of `GET /orders/{id}`
  which never touches `payments`.
- `payments` latency: **20 ms** until `t = 1.0 s`, then **5.0 s** from
  `t = 1.0 s` onwards.

Write one line per step giving the time, the number of threads in use, and what
a customer experiences:

**5.1** `t = 1.0 s` — the moment `payments` degrades. Threads in use?
**5.2** `t = 2.0 s` — threads in use? Is checkout still succeeding?
**5.3** `t = 3.0 s` — threads in use? What happens to a `GET /orders/{id}`
arriving now?
**5.4** `t = 6.0 s` — what is the request queue doing, and what does the load
balancer's health check see?
**5.5** At what time does the *first* `GET /orders/{id}` fail, and why is that
time the important number in the whole trace?
**5.6** Now repeat 5.1–5.3 with a 1-second timeout in place. Give the steady
state.

---

## Rung 6 — Spot the flaw

Each design below is described, not coded. Each contains one specific mistake.
For each: construct a concrete run where it produces a bad outcome, and name the
guarantee or property that is violated.

**6.1 — The shared library.**
> Riverbend's platform team notices all seven services need the same `Order`
> class, the same validation rules, and the same database entity mappings. They
> publish `riverbend-common` as a shared library, versioned, and every service
> depends on it. "It's just code reuse — the services still have their own
> databases."

**6.2 — The one breaker.**
> `orders` wraps all of its outbound HTTP calls in a single circuit breaker,
> configured to open after 20 consecutive failures with a 30-second cooldown.
> The team is pleased: "one place to configure it, and it protects every
> dependency."

**6.3 — The helpful retry.**
> `orders` calls `POST /payments` with a 2-second timeout. On timeout it retries
> up to 3 times, with exponential backoff and jitter, and a retry budget capping
> retries at 10% of traffic. The team has read the SRE book carefully. Customers
> start reporting duplicate charges.

**6.4 — The reporting shortcut.**
> The analytics team needs order data. Rather than build an integration, they
> are given a **read-only** credential on the `orders` database. "It's read-only,
> so it can't break anything."

---

## Rung 7 — Handle failure

Extend a design, on paper, to survive something it currently does not. There is
no single right answer; there are answers that name the right mechanism.

**7.1** Checkout has been moved off the critical path (10g): `orders` writes the
order and an outbox row, returns `PENDING`, and `payments` charges the card
asynchronously. Design what happens when the charge is **declined** twenty
minutes later. Cover: what the customer sees and when, what the reservation in
`inventory` does in the meantime, what happens if the customer has already been
sent a confirmation email, and what a support agent needs to be able to do.

**7.2** `catalog` is used by six services and by the mobile app. It has become
the highest-risk node in the architecture (F8). Design the changes that make a
`catalog` outage survivable for at least the order-history page and the
product-detail page. State for each change what becomes stale, for how long, and
what must never be served from the stale copy.

**7.3** Your team owns `orders`. You need to rename the event field `status` to
`state`, and you do not know who consumes your events. Write the full plan:
how you find out who your consumers are, the deploy sequence, what you measure
before each step, and what you do if one consumer never migrates.

**7.4** Design the monitoring for the outbox relay in 10g such that you would be
paged within five minutes of it dying silently. Say explicitly why a queue-depth
alarm is the wrong instrument, and give the three alarms you would actually set.

---

## Rung 8 — Design

**8.1 — The decomposition brief.**

You have joined a company with a seven-year-old Rails/Java monolith:

- 900k lines, one Postgres database with 340 tables, one deploy per week on
  Wednesdays, a 4-hour release process with a go/no-go meeting.
- 55 engineers in 9 teams. The last four incidents were all caused by one team's
  change breaking another team's feature.
- Peak traffic 4,000 req/s. The product-search path is 70% of traffic and is
  CPU-bound; everything else is comfortable.
- Leadership has read an article and wants "microservices by Q3".

Produce: (a) what you would actually do in the first quarter and why; (b) which
one or two services, if any, you would extract first, with the criteria you used;
(c) the metric you would report monthly to show whether it is working; (d) the
three things you would refuse to do, and how you would explain each refusal to
leadership in one sentence.

**8.2 — Availability budget.**

The product owner promises customers **99.95% checkout availability** (about
4.4 hours per year). Checkout currently requires `gateway`, `orders`,
`inventory` and `payments` synchronously.

(a) What availability must each of the four have, if they are equal, to hit
99.95%? (b) `payments` depends on an external card network that publishes a
99.9% SLA and is not under your control — what does that alone do to your
number? (c) Given (b), state the *only* two structural options that reach
99.95%, and pick one with reasoning. (d) Write the three SLOs you would actually
put on a dashboard, and say which one you would page on at 3am.

**8.3 — Three regions.**

Riverbend expands to Europe, North America and Asia, 150 ms apart. Which parts
of this lab's design survive unchanged, which must change, and what new failure
modes appear? Address specifically: where `orders` data lives, whether
`inventory` reservations can be regional, what happens to the outbox and the
saga during a cross-region partition, and which of your prior labs
([cap-theorem](../cap-theorem/), [pacelc](../pacelc/),
[causal-consistency](../causal-consistency/)) decides each answer.
