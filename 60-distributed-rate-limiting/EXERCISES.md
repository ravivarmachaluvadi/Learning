# Exercises — Distributed Rate Limiter (HLD)

Eight rungs, easiest first. Rungs 1–4 need only `README.md` and
`visualization.html`. Rungs 5–8 are design work — the kind of thing you would
actually write in a doc or say at a whiteboard.

Answers in [SOLUTIONS.md](SOLUTIONS.md). Attempt first; the rungs above 5 have
no single right answer, so the "solution" there is a worked discussion you can
argue with.

---

## Rung 1 — Explain

**1.1** In your own words, with no jargon at all: why does a rate limiter that
works perfectly on one server stop working when you run twenty of them?

**1.2** Name the two opposite ways a distributed rate limiter can be wrong.
For each: who is harmed, and which one is more likely to reach you as a support
ticket rather than as a graph?

**1.3** Complete this sentence in one line: *"Distributed rate limiting is
fundamentally a decision about ______."*

**1.4** A colleague says "we'll just put the counter in Redis, then it's
shared, so it's correct." What is wrong with that sentence?

---

## Rung 2 — Identify

Open `visualization.html`.

**2.1** Select **v4 (atomic at the store)**. Point at the three planes from the
README's engineering model on the screen: which lines are the data plane, which
are the coordination plane, and which plane is not drawn at all — and why not?

**2.2** Switch to **v1** and then **v7**. In each, look at the caption under
each gateway's bar. What is each gateway actually storing, and why does v7's
number keep resetting?

**2.3** With v5 selected, the store shows several small bars. What does one bar
represent, and what is each one's individual limit when the sub-key count is 4
and the promise is 60/s?

**2.4** Set the design to **v6** and read the store-ops number. Compare it with
v4's. Now explain where the requests that are *not* producing store operations
are being decided.

---

## Rung 3 — Predict

Commit to an answer before you touch the page.

**3.1** Design **v1**, 1 gateway, round robin. You slide the node count from 1
to 8. Predict the admitted-per-second number at 8 nodes, and predict what
happens to store operations. Then run it.

**3.2** Design **v2**, 4 gateways, round robin — it is holding at 60/s
exactly. You change the router to **sticky**. Predict the new admitted number
before you switch.

**3.3** Design **v7** with a 1000 ms sync interval. Predict what happens to
(a) admitted/s and (b) store ops/s when you drag the interval down to 100 ms.
Predict the *direction and rough magnitude* of both.

**3.4** Design **v3**, 180 rps, store hop 1 ms. It looks nearly correct. Now
predict what happens when you drag the store hop to 12 ms — and explain the
mechanism before you look.

**3.5** Design **v4**. Press **Kill the store** with the fallback set to
*degrade*. Predict admitted/s. Then predict it again for *fail open* and *fail
closed*.

---

## Rung 4 — Modify

**4.1** Find a combination of design + router policy that gives **exact
enforcement with zero coordination traffic and zero added latency**. It exists.
Having found it, name the three costs the README lists for it, and say why it
cannot be the only mechanism for our payments API.

**4.2** Set v7 and find the largest sync interval that still holds the promise
within about 15% at 180 rps and 4 gateways. Now double the traffic to 360 rps
without changing the interval. What happens, and what does that tell you about
sizing a sync interval from a single load test?

**4.3** Set v6 and raise the lease batch from 2 to 30 while watching store ops
and admitted/s. Describe the shape of the trade you just traced out. At what
point does raising it stop helping?

**4.4** Break v5 deliberately: set the sub-key count to 8 with the promise at
60/s and traffic at 80 rps. Why does under-admission appear at *low* traffic
and disappear at high traffic? What does that imply about when it is safe to
split a key?

---

## Rung 5 — Specify

Produce concrete design artifacts. Write them out; do not just think them.

**5.1** Write the contract for the single atomic store operation in v4. Specify
exactly: its inputs, its outputs, what state it reads and writes, and the one
property it must guarantee. Two of the outputs exist only so the gateway can
build an honest HTTP response — name them and say why they cannot be computed
by the gateway itself.

**5.2** Design the rule schema for the control plane (10.10). It must express:
who the limit applies to, the limit and window, the burst, cost weights per
endpoint group, the fallback behaviour when the store is unreachable, and
whether the rule is enforcing or in shadow mode. Write it as YAML or JSON with
one realistic filled-in example.

**5.3** Specify the hot/cold tier promotion policy from stage 10.7: what
signal is measured, at what grain, over what period, what the promotion and
demotion thresholds are, and what happens to in-flight state at the moment a
key is promoted. Name one way a naive version of this policy oscillates.

---

## Rung 6 — Debug a design

Each of these is a description of a real production symptom. Find the design
flaw, name which README failure it corresponds to, and give the fix.

**6.1** "Our limiter is exact in staging and lets through roughly 3× the limit
in production. The code is identical. Redis is healthy in both."

**6.2** "Rate limiting works fine. But every Tuesday at 14:00 our API latency
p99 goes from 90 ms to 2.5 seconds for about four minutes, and then recovers.
Redis reports no errors and no downtime the whole time."

**6.3** "We limit login attempts to 5 per minute per IP address. An attacker
tried 400,000 passwords against one account last night and we never rejected a
single request."

**6.4** "We told customers 'retry after 30 seconds' when rate limited. Our
total inbound request volume went *up* 40% after we shipped the limiter."

**6.5** "We deployed a new rule that lowered a plan tier's limit. Half our
gateway nodes enforced the new limit and half enforced the old one for about
ninety minutes. Customers reported that whether a request succeeded seemed
random."

---

## Rung 7 — Handle a failure the design does not survive

**7.1** Our design promotes hot keys to the local-plus-sync tier (v7). A
gateway node in that tier is killed mid-second, having spent 40 requests of
allowance it never published to the store. Describe exactly what the other
gateways now believe, whether the resulting error is over- or under-admission,
and propose two mechanisms that bound the damage. State the cost of each.

**7.2** Extend the design so that a customer whose traffic is rejected can tell
*which* of the three simultaneous limits (merchant, endpoint, IP) they hit —
without leaking information that helps an attacker map your limits. What must
the store operation return, and what must the gateway suppress?

**7.3** The design assumes the gateway knows the caller's identity. Design the
limiting strategy for your **unauthenticated** endpoints (signup, login,
password reset), where you have no API key. State your keys, why each one alone
is insufficient, and what an attacker with 50,000 IP addresses does to your
scheme.

---

## Rung 8 — Design

**8.1 — The main one.** Design the rate limiter for a payments API serving
**three regions** (US, EU, India), 80,000 rps peak globally, 3 million
merchants, with these product commitments:

- Enterprise merchants buy a contractual limit that must be **globally**
  accurate to within 1%.
- Free-tier merchants get 100 requests/minute, and accuracy there matters far
  less than cost.
- A single global cap of 25,000 rps on `POST /payments` protects a card-network
  integration that will suspend you if you exceed it.

Produce: the architecture, the coordination design **per limit class** with
justification, your capacity numbers, the failure behaviour for each class,
and the four things you would put on the on-call dashboard. State explicitly
which of the three commitments you cannot fully meet and what you would
negotiate instead.

**8.2** Your company is moving from 20 gateway nodes in one region to a
cell-based architecture: 50 independent cells, each with its own gateway pool
and its own Redis, and a router that maps each merchant to exactly one cell.
Which parts of this lab's design survive unchanged, which become unnecessary,
and which new problem does the cell router create that did not exist before?

**8.3** Argue the opposite case: write the three-paragraph argument for
**not** building any of this — for using your API gateway's built-in
per-node rate limiting and accepting the N× error. Be specific about the
scale, the limit values, and the business context in which that is genuinely
the right engineering decision.
