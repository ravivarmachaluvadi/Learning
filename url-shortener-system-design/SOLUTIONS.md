# Solutions — Highly Scalable URL Shortener

Attempt each exercise before reading its answer.

## 1. Explain

A typical CRUD row's primary key can be anything unique — a UUID generated
locally with no coordination at all works fine, because nothing about the
*value* of the key matters to the product. A URL shortener's short code *is*
the product-facing value: it has to be short, which historically meant "dense"
(no gaps, assigned close to sequentially) to get the most mileage out of a
small alphabet — and density is exactly the property that needs a shared,
ordered counter, which is the one thing that doesn't parallelize for free.
That's the specific tension: the write path needs both "many independent
writers" (for throughput and availability) and "one shared sequence" (for
short, dense, unique codes) at once, and reconciling those two is the actual
design problem — not the write *volume* by itself.

## 2. Identify

- **The Key Generation Service (KGS)** — if it's gone for an hour, any app
  server that exhausts its current block has nowhere to get a new one, so
  *new* link creation stalls or fails. Every already-issued short code still
  resolves normally, because the read path (§6) never talks to the KGS.
- **One database shard** — if it's gone for an hour, every code that hashes
  to that shard fails to redirect (and fails to be created, if its counter
  range happens to route there), but the other (N−1)/N of the corpus is
  completely unaffected, because §12 sharded by code specifically so that one
  shard's fate is independent of the others'.

## 3. Predict

Ids 900,003–900,499 (497 of them) are simply never minted by anyone. Nobody
reclaims them, and no other server will ever be handed them either, because
the KGS's counter has already moved past 900,499 — it gave that whole range to
the crashed server and will never hand out any id inside it again.

This does **not** cause a correctness problem. The one invariant the design
promises (§14) is that two different long URLs never share one short code.
Wasting a range doesn't touch that invariant at all — it only means the
keyspace fills up very slightly faster than the theoretical minimum, which is
irrelevant at a keyspace measured in trillions (§8). The design deliberately
accepts this waste in exchange for never needing a mechanism to "return"
unused ids, which would itself be extra coordination (exactly what block
allocation exists to avoid).

## 4. Modify

62⁵ = **916,132,832** (≈ 916 million).

At 100,000,000 new links/day: 916,132,832 ÷ 100,000,000 ≈ **9.16 days**. A
5-character keyspace at this write rate is completely exhausted in under two
weeks — dramatically faster than the 6-character wall from §8 (568 days),
because dropping one character divides the keyspace by 62, not by some small
constant.

The change that has to happen *before* that: either accept a longer code
(defeating the point of the request), or move the block-allocation and
sharding scheme (§9, §12) to actively track fill percentage and alert well
before exhaustion — because unlike the 7-character design where exhaustion is
theoretical over the planning horizon, a 5-character keyspace's exhaustion
date is a real, near-term operational event, and finding out by having every
insert start failing is far too late; you want alerting at, say, 50% fill,
which here would be about 4.5 days after launch.

## 5. Trace

Counter starts at 100. Block size = 4.

| Step | Action | Counter after | A has minted | B has minted |
|---|---|---|---|---|
| 1 | A requests block | 104 (A holds [100–103]) | — | — |
| 2 | A mints | 104 | {100} | — |
| 3 | B requests block | 108 (B holds [104–107]) | {100} | — |
| 4 | A mints | 108 | {100, 101} | — |
| 5 | B mints | 108 | {100, 101} | {104} |
| 6 | A mints twice (block exhausted) | 108 | {100, 101, 102, 103} | {104} |
| 7 | A requests new block | 112 (A holds [108–111]) | {100, 101, 102, 103} | {104} |

No id appears in both sets. This holds regardless of the interleaving of steps
2, 4, 5, 6 — A's and B's ranges were made disjoint the moment each block was
requested (step 1 and step 3), before either server minted anything from it.
That's the load-bearing point: uniqueness is decided at **allocation time**,
not at **mint time**, so the order in which two servers happen to actually use
their already-disjoint ids never matters.

## 6. Spot the flaw

Smallest scenario, counter starting at 100:

1. Server A calls the KGS. The KGS returns id **100** and a hint: "mint up to
   5 more locally before calling back" — but internally advances its counter
   by only 1, to **101** (same as the naive design's single-increment call).
2. Before A has used any of its "allowed" ids 101–104, server B calls the KGS.
   The KGS's counter is 101, so it hands B id **101**, with the same
   "5 more" hint, and advances its counter to 102.
3. A now locally mints its second id, believing it owns 101 (from its
   original hint). B mints its first id, using the 101 it was actually
   handed. **Both servers insert a row for short code 101, with two different
   long URLs.**

The exact line this violates, from §9: *"No other app server will ever be
handed any id in that range — the KGS's job is exclusively to make range
ownership globally non-overlapping."* The proposed variant only advances the
counter by 1 per call, so the "range" a server is told it owns was never
actually reserved anywhere — the hint is an unenforced promise, not a real
allocation. This is exactly the naive single-counter design from §3 wearing
a disguise: the *coordination call* still only advances by one, which is the
one thing block allocation is supposed to change.

## 7. Handle failure

Two legitimate choices, with different costs:

**Fail closed** — reject the create request (e.g., HTTP 503, "try again
shortly") until the KGS is reachable and a new block can be fetched. This
favors correctness of the global counter over availability of link creation —
the CP choice from §14 — and is the safer default, because it never risks
inventing an id through any path that hasn't been proven disjoint from the
KGS's normal ranges.

**Fail open with a pre-provisioned emergency range** — before any outage
happens, each app server is given a small, permanently reserved sub-range of
the keyspace that only it may use, and only during a declared KGS outage
(e.g., a dedicated high bit in the id marks "emergency-minted"). This favors
availability of creation over the simplicity of having one counter — the AP
choice — but it isn't free: now there are two id-issuing mechanisms that must
be proven, by construction, to never collide with each other or with the
KGS's own ranges, which is real design and testing cost paid specifically to
buy creation-availability during a rare event.

Neither choice touches the read path — this exercise only concerns link
*creation*, which is exactly the asymmetry §5 and §14 are built around.

## 8. Design

**Does sharding by code still work?** For redirects, yes — nothing about
adding a "list my links" feature changes how a *redirect* request should be
routed, and §12's whole argument (self-routing from the code, no lookup) still
holds. But it does mean a user's links are scattered essentially randomly
across every shard (code assignment has nothing to do with who created the
link), so "give me all of user X's links" as posed would require asking every
shard and merging — a fan-out query, which is a fundamentally different and
much more expensive access pattern than the point-lookup the sharding scheme
was built for.

**Sketch:** Don't make the redirect-serving table answer this query at all.
Add a second, separate store keyed by `user_id`, holding
`(user_id, code, created_at)` rows, sharded by `user_id` instead of by code —
optimized for "give me this one user's rows, sorted by time," which is a
completely different shape from "give me this one code's row." Populate it the
same way §13 already populates the async click-analytics pipeline: emit an
event when a link is created, and let a consumer write it into the per-user
store, off the create request's critical path. The redirect path never touches
this second store, and the per-user store never needs to serve a single
redirect — this is the same principle as [[database-landscape]]'s "match the
store to the access pattern" applied to a second, unrelated query shape that
showed up after the fact.

**What to monitor:** the lag between a link's creation event and its
appearance in the per-user store (a user who just created a link and
immediately opens "my links" is looking at a consistency window, and that
window should be small and watched, not unbounded); and the per-user list
endpoint's own latency and error rate, tracked and alerted on *separately*
from redirect latency, so a slow or degraded "my links" page can never be
confused with — or, worse, allowed to affect — the health of the redirect
path, which is the metric that actually matters most for this product (§5).

## Counterfactual

What physically serializes in the naive single-counter design (§3) is the
**round trip to the one row holding the counter**: every writer, regardless of
how many writers there are or how fast their own machines are, must complete
a full request/response cycle against that one row before it can safely tell
the next writer to proceed (an auto-increment, or any correctly-implemented
compare-and-swap loop, cannot let two writers both advance from the same
starting value). If that round trip averages, say, 2 ms end-to-end — a
plausible number for a same-datacenter database call, offered here as an
illustrative order of magnitude rather than a measured benchmark — the
absolute ceiling on genuinely serialized increments to that one row is on the
order of a few hundred to roughly 500 per second, no matter how the rest of
the fleet is scaled. §2's average write requirement alone is **≈1,157/sec**,
already past that ceiling before any peak multiplier is applied — which is
exactly the number that makes "just add more app servers in front of one
counter" not a tuning problem but a structural dead end, and it's the concrete
threshold block allocation (§9) exists to remove.
