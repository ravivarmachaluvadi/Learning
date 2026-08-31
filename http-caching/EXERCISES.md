# Exercises — HTTP caching

Climb in order. Rungs 2–4 use `visualization.html`; 4–6 use `experiments/`;
7–8 are design discussions. Attempt before opening [`SOLUTIONS.md`](SOLUTIONS.md).

## Rung 1 — Explain (no header names allowed)

1a. Explain to a non-engineer why a website can show you a price that no
longer exists, even though the company fixed it an hour ago — and why the
company often cannot do anything about it for that specific person.

1b. HTTP caching has exactly two mechanisms. Name both in plain words, and say
what each one saves. (Hint: one of them saves something the other cannot.)

1c. A teammate says "we set `no-cache` on the account page so it's never
stored anywhere." What are they wrong about, and what did they probably mean?

## Rung 2 — Identify

2a. Open the visualization, set "reuse freely for" to 60 s, and click
**Alice → /menu** twice. Which box changed colour, what does its bar mean, and
why was the second request faster? Now click **Bob → /menu**: which cache
answered him, and why could it, given the answer was fetched for Alice?

2b. In the visualization, look at the two kinds of travelling dot (hollow vs
filled). Which is a question and which is an answer? What does the label
"still good?" on a dot tell you about what the cache already has?

2c. Run experiment 01 and find, in its output: (i) the moment the contract was
honoured at the cost of correctness, (ii) the header that tells a client how
old its answer is, (iii) the header a CDN uses to tell you it didn't do any
real work.

## Rung 3 — Predict, then run

3a. Set max-age to 15 s. Predict: after clicking **Alice → /menu**, then
**Skip ahead 30 s**, then **Alice → /menu** again — how many round trips does
the third request make, and how many bytes of menu content travel? Run it and
check the counters.

3b. Same as 3a but **untick the ETag box** first. Predict what changes in both
numbers. Which of the two mechanisms did you just disable?

3c. Predict the outcome of the **"Bob sees Alice's account"** scenario before
running it. Then switch the audience control to *that user only*, reset, and
manually redo the same two requests. What is different in the CDN box, and
which counter proves the fix?

3d. Run experiment 03. Before you do: 30 readers arrive one millisecond after
a copy expires. Predict the number of origin requests, then the number after
single-flight is enabled. Were you within 5?

## Rung 4 — Modify

4a. In `experiments/Exp01ARealCache.java`, set `MAX_AGE = 2` and re-run.
Describe precisely what changes in Phase B and Phase C, and state the general
rule linking max-age to the two things it trades off.

4b. In `experiments/Exp02The304Diet.java`, change the padding
(`ITEM_COUNT = 800`) to `ITEM_COUNT = 5`. Predict first: does the *percentage*
saved by a 304 go up, down, or stay the same? Run it. What does that tell you
about which resources ETags are most worth having on?

4c. In the visualization, turn on **stale-while-revalidate** and re-run the
stampede scenario. Compare the origin counter with the previous run, and
explain in one sentence who waits and who doesn't under each setting.

## Rung 5 — Implement

5a. `experiments/Exp01ARealCache.java`'s proxy has no validator support: when a
copy goes stale it refetches the whole body. Add ETag handling — store the
ETag with the entry, send `If-None-Match` when stale, and on a `304` renew
`storedAtMillis` and serve the stored body. Verify: after expiry, the origin's hit
counter still increments but the transferred body is zero, and the price only
changes when the origin's content actually changed.

5b. Add `Vary: Accept-Encoding` support to that proxy: make the cache key a
pair of (path, whether the client sent `Accept-Encoding: gzip`). Prove it
works by requesting the same path with and without the header and showing two
separate entries are stored.

## Rung 6 — Debug

6a. A team ships this API. Find **four** distinct caching bugs, name the
symptom each produces in production, and give the corrected header for each.

```text
# 1. the public restaurant menu — changes twice a day
GET /menu       →  Cache-Control: no-cache
                   (no ETag)

# 2. the logged-in user's order list
GET /orders     →  Cache-Control: public, max-age=600

# 3. the JS bundle, filename never changes across deploys
GET /app.js     →  Cache-Control: public, max-age=31536000, immutable

# 4. the search endpoint, response differs by Accept-Language
GET /search?q=x →  Cache-Control: public, max-age=300
                   (no Vary)
```

6b. Support reports: "we deployed the fix 40 minutes ago; most users see it,
one user still sees the bug, and it works for her in a private window." Walk
through the diagnosis: what does the private window prove, what does it rule
out, and what is the one header you'd ask her to screenshot from DevTools?

## Rung 7 — Handle failure

7a. Tiffin's origin goes down for 20 minutes during a regional outage. Design
the caching contract for `/menu` so that customers keep seeing *something*
rather than errors, state exactly which directive does it, and name the cost
you just accepted. Then state what you would NOT apply this to, and why.

7b. Tiffin's prices must never be shown wrong for more than 60 seconds, but
the menu endpoint takes 400 ms and gets 30k requests/second. Design the
contract and the surrounding machinery (what the CDN does, what happens at
expiry, what happens on a price change) so that both the 60-second bound and
the origin's survival hold. Name every mechanism you use.

## Rung 8 — Design (production scale)

Tiffin now serves 40 countries. Design the complete caching architecture:

- the marketing site (static, changes weekly)
- the SPA's JS/CSS bundles, deployed ~10× a day
- `GET /restaurants?city=X` — same for everyone in a city, changes hourly
- `GET /restaurants/42/menu` — changes twice daily, must be correct within 5
  minutes of a restaurant editing it
- `GET /orders/97` — one customer's live order, changes every few seconds
- `GET /me` — personal profile
- `POST /orders` — placing an order

For each: the exact `Cache-Control`, whether an ETag is worth it, what the
cache key must include, and how a change propagates (expiry? purge? new URL?).
Then: name the three metrics on the on-call dashboard and what each one
failing would look like.
