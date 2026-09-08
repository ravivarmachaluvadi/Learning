# Exercises — Highly Scalable URL Shortener

Work through these roughly in order — later ones assume the earlier ones. Attempt
each before checking `SOLUTIONS.md`.

## 1. Explain

In your own words, in 3–4 sentences: why is a URL shortener's *write* path a
genuinely different scaling problem from a typical CRUD app's write path, even
though both are "just inserting a row"? Your answer should mention the specific
thing that makes generating the id itself hard, not just "high write volume."

## 2. Identify

Given the design in §9–§12 of the README (block-allocation KGS + sharded storage,
codes carrying a shard id), answer:

- Which single component, if it disappears entirely for an hour, stops new link
  creation but leaves every existing redirect working?
- Which single component, if it disappears entirely for an hour, breaks roughly
  1/N of all redirects (N = shard count) but leaves link creation for everyone
  else unaffected?

## 3. Predict

An app server has just been handed a fresh block of 500 ids (say, ids
900,000–900,499). It successfully mints 3 short links, then its process is
killed (deploy, crash, doesn't matter).

- What happens to ids 900,003–900,499?
- Does this ever cause a correctness problem (two different long URLs sharing
  one short code)? Justify your answer from the mechanism, not just "no."

## 4. Modify

Suppose product wants short codes limited to **5 characters** instead of 7, to
make links even more shareable, on the same 100M-links/day, 5-year corpus
assumption from §2 of the README.

- Compute 62⁵.
- At 100M new links/day, how many days until that keyspace is exhausted?
- Name one design change (from §8–§9) that would need to happen *before* that
  exhaustion date, and why waiting until the keyspace is actually full would be
  too late.

## 5. Trace

Walk through the block-allocation mechanism (§9) by hand for **block size = 4**,
starting from counter value 100, with two app servers, A and B, making requests
in this order:

1. A requests a block.
2. A mints one code.
3. B requests a block.
4. A mints one code.
5. B mints one code.
6. A mints two more codes (its block is now exhausted after this).
7. A requests a new block.

For each step, write down: the counter's value immediately after any block
request, which ids A has minted so far, which ids B has minted so far, and
confirm no id is ever minted by both.

## 6. Spot the flaw

A colleague proposes this variant of block allocation to "make it simpler":
instead of each app server tracking its own remaining count locally, every app
server calls the KGS on every mint, and the KGS returns the next id **plus** a
"hint" saying how many more the caller is *allowed* to mint without asking
again — but the KGS doesn't actually reserve those ids; it just increments its
counter by 1 each time, same as the naive design, and trusts the app server to
not ask again until the hint's count is exhausted.

Find the smallest concrete scenario (how many app servers, doing what, in what
order) where this produces a duplicate id. Name exactly which line of reasoning
in §9 this variant violates.

## 7. Handle failure

The README's failure-scenario table (§13) states that when the KGS is
unreachable, existing redirects are unaffected. Extend the design one step
further: what should an app server do if it receives a *create link* request
while its local block is exhausted **and** the KGS is unreachable? List at
least two different acceptable behaviors a real product might choose between,
and name the tradeoff each one makes (in terms of the CP/AP framing from §14).

## 8. Design

A stakeholder asks for a new feature: **link ownership and per-user link lists**
("show me every link I've ever created"), sorted by creation time, for accounts
that may have created millions of links.

- What does this requirement do to the sharding decision in §12 (which shards
  by short code)? Is "shard by code" still sufficient, or does something need
  to change?
- Sketch, in 4–6 sentences, how you'd support this feature without turning
  every redirect into a two-shard lookup. (Hint: think about what data the
  *read-heavy redirect path* needs versus what the *much rarer* "list my links"
  page needs, and whether they have to live in the same table.)
- What would you monitor in production to know this feature is healthy?

## Counterfactual (folds into 6–8)

If block allocation didn't exist — if every design were forced to choose
between "one global counter" and "random guess and retry" — what's the
smallest concrete write-scaling requirement (stated as a QPS number, referencing
§2) that would make the naive single-counter design *impossible to run at all*,
regardless of how good the database is? Be specific about what physically
serializes.
