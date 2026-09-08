# Highly Scalable URL Shortener — High-Level Design

| Section | What it answers |
|---|---|
| §1–2 WHY / the problem | Why does this product exist, and what does "highly scalable" mean in numbers? |
| §3–5 Naive design → failure | What would you build in an afternoon, and where does it fall over? |
| §6–7 The real requirement | What must actually be true, and what's the first fix? |
| §8–11 The ID-minting story | The one genuinely hard problem: unique short codes, minted without a bottleneck |
| §12 Sharding & self-routing | How the code itself replaces a lookup |
| §13 Failure scenarios | What breaks, one piece at a time, and what survives |
| §14 Correctness & guarantees | What's actually promised, CP vs AP, per path |
| §15 Production bridge | 301 vs 302, abuse, expiry, analytics, edge deployment |
| §16 Wrong models | The misconceptions this lesson exists to prevent |
| §17 Knowledge map | Prerequisites, related labs, what to learn next |
| §18 Glossary | Every load-bearing term, in order of appearance |

**Interpretation note:** "URL shortener" here means the generic product (think TinyURL/Bitly-shaped), not any single company's real internal architecture. Neither Bitly nor TinyURL publish detailed system-design papers, so every number below is an *estimation exercise* built from stated assumptions — the kind an interviewer expects you to derive live, not a fact about a real company's infrastructure. Every assumption is named so you can redo the arithmetic with different ones.

---

## 1. Why this exists

Long URLs are unwieldy. `https://example.com/products/electronics/laptops?category=gaming&sort=price&filter=in-stock&page=3&session=a8f3e9c2...` doesn't fit in a text message, gets mangled by some email clients, and used to blow past Twitter's old 140-character limit entirely. A URL shortener maps that whole string to something like `acme.ly/x7Bq2` — short enough to say out loud, print on a billboard, or paste into an SMS — and transparently sends anyone who visits it to the original destination.

That's the whole product idea. It sounds almost too simple to be an interesting system-design problem — "store a mapping, look it up" is something you could build in twenty minutes with one database table. And for a personal project, that build is correct and complete. The interesting design work only starts once you ask what happens at the scale implied by "highly scalable": millions of links created a day, and — this is the detail that reshapes every decision that follows — a redirect click landing on your system possibly *years* after the link was created, from a corner of the internet you don't control (a printed flyer, an old text thread, a Wikipedia citation) that has no way to know or care that your system changed underneath it.

## 2. The concrete problem, in numbers

**Acme.ly** is a URL-shortening product. To make "highly scalable" concrete rather than a vibe, put stated assumptions behind it:

- 100 million new short links created per day, system-wide. *(A round planning number for this exercise — not a measured figure.)*
- A 100:1 read-to-write ratio: every link, on average, gets clicked about 100 times before people stop clicking it. *(A commonly used rule-of-thumb for link-shortening workloads — some links get one click ever, some go viral and get millions; 100:1 is the average across the whole corpus.)*
- Links must keep working for years. There's no natural expiry — a link is a promise made once and expected to be kept indefinitely.

From those three assumptions alone, arithmetic gives you the shape of the system before you've designed a single component:

| Quantity | Formula | Result |
|---|---|---|
| Average write QPS | 100,000,000 ÷ 86,400 s/day | ≈ 1,157 writes/sec |
| Average read QPS | write QPS × 100 | ≈ 115,700 reads/sec |
| Peak read QPS | average × assumed 5× peak factor | ≈ 580,000 reads/sec |
| 5-year corpus size | 100M/day × 365 × 5 | ≈ 182.5 billion links |
| Storage (rough) | 182.5B rows × ~500 bytes/row (code + URL + metadata + index overhead) | ≈ 90 TB |

The peak factor (5×) is an assumed heuristic for time-of-day plus the occasional viral spike concentrated on a handful of links — flagged explicitly as an assumption, not a derived number, because that's exactly the kind of figure a real system would get from actual traffic logs, not from a whiteboard.

Two of those numbers are the whole story before you've drawn a single box: **reads outnumber writes by two orders of magnitude**, and **the corpus is enormous and permanent**. Every design decision below is downstream of those two facts.

## 3. The obvious first design

A reasonable engineer, asked to build this in an afternoon, would reach for exactly this:

```sql
CREATE TABLE links (
  id        BIGSERIAL PRIMARY KEY,   -- auto-incrementing integer
  long_url  TEXT NOT NULL,
  created_at TIMESTAMP DEFAULT now()
);
```

To create a short link: `INSERT` the long URL, get back the auto-generated `id` (say, `125,000,042`), and convert that integer to base62 (digits 0–9, letters a–z, A–Z — 62 symbols, all URL-safe with no punctuation to escape). Base62 is just positional-notation encoding, the same idea as hex or binary, with a bigger alphabet: `125,000,042` becomes something like `8P1Ug`. To redirect: decode the base62 code back to the integer, `SELECT long_url FROM links WHERE id = ...`, and issue an HTTP redirect to whatever comes back.

This is a genuinely good design for the first version. It's also the design most people would independently arrive at, because it uses the one thing a database gives you for free — a monotonically increasing, guaranteed-unique integer — and gets the shortest possible code for free as a side effect (base62 of a small number is a short string; you never pay for length you don't need yet). Nothing about this is naive in a "beginner mistake" sense. It's naive only in the specific sense that it hasn't yet met the requirement from §2.

**🔮 Predict:** This design is one server with one database. Given the QPS numbers from §2, where do you expect it to break first — on writes, or on reads?

## 4. Where it breaks

Two independent things go wrong, and they go wrong for different reasons.

**Read throughput.** A single Postgres instance, well-tuned, can serve on the order of tens of thousands of simple indexed point-reads per second. §2's peak estimate is ~580,000 reads/sec. That's not "needs tuning" — it's multiple orders of magnitude past what one machine can do, full stop, no matter how good the query plan is.

**The hot-link case makes it worse, and sooner.** Say one link gets tweeted by someone with a large following and receives 50,000 clicks in the first ten seconds. That's 50,000 near-simultaneous point-reads for one row on one machine — the *average* QPS budget for the whole system doesn't even matter here; a single link can locally exceed what one database connection pool can push through, while every other link on the service sees no unusual load at all. (This is the exact shape of problem the [[caching]] lab calls a **hot key**: a workload where the interesting failure is about a small number of keys receiving disproportionate traffic, not about the aggregate.)

**Single point of failure, with an unusual blast radius.** If this one database goes down, *every previously shared link stops working*, everywhere on the internet, simultaneously — the flyer printed three years ago, the citation in a 2019 blog post, the SMS from last week. A URL shortener has an unusually strong version of the ordinary "don't have a SPOF" rule: outages don't just degrade *today's* traffic, they retroactively break every promise the product has ever made.

Neither of these is solved by "get a bigger database." They're solved by not putting every read on the database at all.

## 5. The real requirement

State it precisely, because it will drive every decision from here on:

> Redirects must be servable — fast, and almost all the time — from something other than the single system of record, because a redirect is a promise made in the past that must be honored regardless of what's currently happening to the write path.

Two consequences fall out of that sentence immediately:

1. **The read path needs its own copy of the data**, one that can absorb far more traffic than the source of truth and that doesn't go down when the source of truth has a bad day.
2. **The read path's availability requirement is higher than the write path's.** If link *creation* is briefly degraded during a database blip, that's an inconvenience. If link *redirection* is degraded, every already-published link everywhere breaks at once. Design the two paths to fail independently.

## 6. First fix: cache in front of the database

This part doesn't need reinventing — it's a direct application of the [[caching]] lab, so only the application is new here, not the mechanism. Put a cache-aside cache (Redis or similar) in front of the redirect path: on a redirect request, check the cache first; on a hit, redirect immediately without touching the database at all; on a miss, read from the database, populate the cache, then redirect.

This works unreasonably well here specifically *because* of the skew the caching lab already measured: a small fraction of the keyspace accounts for most of the traffic (that lab measured 10% of keys serving 67.3% of requests on a representative skewed workload). A URL shortener's access pattern is skewed the same way — most links get a handful of clicks and fade, a few links go viral and get most of the traffic — so a cache sized to hold only the *currently hot* fraction of a 182-billion-row corpus, not the whole corpus, still absorbs the large majority of read traffic. The hot-link scenario from §4 is now the cache's best case, not the database's worst case: 50,000 clicks on one link in ten seconds means one cache miss and 49,999 cache hits, not 50,000 database queries.

This single change gets the read path most of the way to the requirement in §5 — but it doesn't touch the write path at all, and the write path has its own scaling story that's specific to *this* product in a way caching isn't. That's where the rest of this lesson lives.

## 7. Why the write path is the interesting problem

Here's the detail that makes a URL shortener a genuinely different design exercise from "a CRUD app with a cache in front" — and the reason this concept earns its own lesson instead of being three bullet points inside a generic web-scaling lab.

The naive design's cleverest trick — using the database's auto-increment counter to generate both a **unique** id and, via base62, the **shortest possible** code — depends on there being exactly one counter. That's fine with one database server. But §5 already told you the write path needs to survive the database having a bad day too (creation shouldn't go down just because one machine is unhealthy), and §2's write volume (1,157/sec average, with its own spikes) is exactly the kind of load you'd normally handle by adding more application servers and more database capacity in parallel.

The moment you have more than one place capable of accepting a "create a link" request — multiple app servers, or a sharded database — "give me the next integer" stops being a question one machine can answer alone. You've reintroduced, in miniature, the exact global-coordination problem the [[consistent-hashing]] lab spent its whole lesson eliminating for *key placement*. Here it shows up for *key generation* instead: uniqueness is a global property, and a global property under concurrent, distributed writers is where the hard part of this design actually lives.

**🔮 Predict:** If a single shared counter is the problem, what's the most obvious way to remove it — and what do you think goes wrong with that fix?

## 8. Attempt two: skip the counter, guess randomly

The obvious fix for "one shared counter is a bottleneck" is: don't share a counter. Let every app server generate its own random 7-character base62 string locally — no coordination, no shared state, trivially parallel — and `INSERT ... ON CONFLICT DO NOTHING`, retrying with a new random string if the insert reports a collision.

This removes the single-counter bottleneck. It has a different cost, and the size of that cost is a number worth actually computing rather than waving at.

A 7-character base62 code can represent 62⁷ = **3,521,614,606,208** distinct values (~3.52 trillion). That looks enormous next to the 182.5-billion-link, 5-year corpus from §2. But the question that matters for a random-guess scheme isn't "is the keyspace big enough in total" — it's "what's the chance my next random guess collides with something already stored," and that's simply (codes already used) ÷ (total keyspace):

| Corpus size (already stored) | Code length | Collision probability on next random guess |
|---|---|---|
| 182.5 billion (5-year corpus) | 7 chars (62⁷ ≈ 3.52T) | 182.5B ÷ 3.52T ≈ **5.2%** |
| 182.5 billion (5-year corpus) | 6 chars (62⁶ ≈ 56.8B) | keyspace exhausted before you get there |

The 6-character row isn't a rounding error — it's a hard wall. 62⁶ ≈ 56.8 billion codes, and at 100M new links/day the *entire* 6-character keyspace is exhausted in 56.8B ÷ 100M ≈ **568 days**, well under two years. Every insert after that point collides with certainty, forever, no matter how many retries you allow. This is the concrete version of an abstract worry ("what if we pick too short a code") turning into an exact, computable, and avoidable failure — one interview candidates often gesture at without ever running the number.

But even the 7-character row, at "only" a 5.2% collision rate, exposes the deeper problem with this whole approach, and it's not the collision *rate* — it's what a collision costs you. Every single insert, colliding or not, still needs a **uniqueness check against shared state**: a database unique constraint, checked synchronously, on every write. Random generation didn't remove the dependency on a shared coordination point at all — it just replaced "ask for the next number" with "guess, then ask if you were wrong," which is the same round trip to the same database, now sometimes paid twice or three times per successful insert. You've made the coordination *probabilistic and retried* instead of *deterministic and singular* — which is a strictly worse trade, not an escape from the problem.

## 9. The refinement: batch the coordination, don't eliminate it

The insight the last two sections are building toward: you don't actually need to remove the shared counter. You need to stop paying its coordination cost **on every single write**.

This is the same move as batching in general — do the expensive, coordinated thing rarely, and let each participant work from a private supply the rest of the time — applied to id generation specifically. It's commonly called a **Key Generation Service (KGS)**, sometimes a "ticket server" (the pattern predates URL shorteners; Flickr described a version of it for their photo-id ticket servers).

The mechanism:

1. A small, separately-scaled coordination service owns the one true counter (or a small number of them — more on that in §12).
2. When an app server needs ids, it asks the KGS for a **block** — say, "give me the next 1,000 ids" — in a single request.
3. The KGS atomically advances its counter by 1,000 and hands the *range* (e.g., `125,001,000`–`125,001,999`) to that app server. No other app server will ever be handed any id in that range — the KGS's job is exclusively to make range ownership globally non-overlapping.
4. The app server mints ids 999 more times **entirely locally**, from memory, with zero network calls, until the block runs out — at which point it asks for another block.

This changes the *frequency* of coordination, not its *existence*. Coordination is still required for correctness — uniqueness is still a global property, and global properties still need a single source of truth somewhere — but instead of paying for it once per write, you pay for it once per **block**. With a block size of 1,000, that's a **1,000× reduction** in coordination traffic (100 million writes/day become 100,000 block-fetches/day, system-wide, independent of how many app servers you run), and it eliminates the collision-and-retry problem from §8 entirely — allocated ranges never overlap, so there's nothing to collide with.

**🔮 Predict:** An app server crashes right after fetching a fresh block of 1,000 ids, having used only 12 of them. What happens to the other 988?

The other 988 are simply never used. Nobody reclaims them; nobody needs to. This is the trade the design is explicitly making: a crash wastes at most (block size − 1) ids, which is a rounding error against a keyspace measured in trillions, in exchange for a coordination service that never needs to track *which specific ids* are outstanding — only the next range to hand out. Contrast this with the naive single-counter design, where the same crash mid-transaction risks a gap that some other piece of code might feel obligated to "fix" by reusing — exactly the kind of fragile, stateful bookkeeping the block design was built to avoid.

**Block size is a real dial, not a free win**, and it trades against the crash cost you just computed:

| Block size | Coordination calls, system-wide (100M writes/day) | Max ids wasted per app-server crash |
|---|---|---|
| 10 | 10,000,000/day | 9 |
| 1,000 | 100,000/day | 999 |
| 100,000 | 1,000/day | 99,999 |

There's no universally correct choice — it's a genuine tradeoff between how rarely you want to hit the coordination service and how many ids you're willing to burn on every crash, chosen by scale and by how "expensive" wasted keyspace actually is (in a 3.52-trillion-code space, it essentially never is, which is why production systems tend to pick block sizes on the larger end of this table).

## 10. Signposted walkthrough: the visualization

**Open `visualization.html`.** Before you look at it, here's what to watch for and why it's there.

The panel lets you mint a chosen number of short codes under three strategies side by side — *naive shared counter*, *random-guess-and-retry*, and *block allocation* — and count the actual coordination round trips and (for the random strategy) collisions each one produces, against the same total workload. The random-guess strategy runs against a deliberately small demo keyspace (a few hundred thousand codes, not 62⁷) so that collisions are visible within a demo-sized run — **this is a pedagogical simplification, called out explicitly**: only the *mechanism* (probability of collision = fill fraction of the keyspace) is real; the specific numbers you'll see on screen are a scaled-down teaching model, not a claim about how a 7-character production keyspace behaves (that arithmetic is in the table in §8, computed exactly).

What to look at: predict, before running, which of the three strategies will need the fewest round trips to the shared coordination point for a fixed number of minted codes — then run it and check the round-trip counter. Then increase the block size for the block-allocation strategy and watch that counter fall further, while the naive and random counters (which don't have a block size to adjust) stay fixed. That single relationship — round trips scale down linearly with block size, everything else held constant — *is* §9's argument, made visible instead of asserted.

## 11. What the block design still needs: it isn't Snowflake, and that's a choice

Worth knowing the shape of the well-known alternative, briefly, because interviewers expect you to know it exists and to be able to say why you would or wouldn't pick it here.

Twitter's **Snowflake** scheme generates ids with *no* coordination service at all: pack a timestamp, a fixed worker-id, and a per-millisecond sequence number into one integer, and any worker can mint ids forever without ever contacting anything else, because uniqueness comes from the worker-id field being disjoint by construction rather than from a range being handed out. This is strictly simpler operationally (no KGS to keep available), which is a real advantage this design should take seriously.

The trade a URL shortener specifically cares about: a Snowflake-style id is a 64-bit integer carrying a timestamp and a worker id inside it, so it's *longer* once base62-encoded — you lose the "shortest possible code for the current corpus size" property that made base62-of-a-dense-counter attractive in the first place (§3). For a product whose entire value proposition is "short," that's not a free trade. This lesson uses block allocation because it keeps codes dense and short; a design that didn't care about code length (an internal-only id service, say) might reasonably pick Snowflake instead and skip §9–§10 entirely. Both are legitimate answers to "how do you mint unique ids without a bottleneck" — they just optimize for different things.

## 12. Sharding, and making the code route itself

Once the corpus outgrows one database (§2's ~90 TB and ~580K peak reads/sec both argue for this well before 182.5 billion rows are reached), the database gets sharded — the same idea the [[consistent-hashing]] lab covered for placing keys across machines, applied here to placing *rows* across database shards.

The natural sharding key is the short code itself, because that's what every read arrives with. But there's a small addition worth making, directly reusing the block-allocation mechanism from §9: instead of one single KGS counter, run a small fixed number of counters, each pre-assigned to a shard — for example, reserve two low-order bits of the minted id to name one of four shards. Now decoding a code back into "which database shard owns this row" is pure arithmetic (`id & 0b11`), performed by whichever service received the redirect request — **no lookup table, no metadata service, nothing that itself needs to be replicated and kept consistent.**

This is the exact idea the [[consistent-hashing]] lab's takeaway is built around — *the machine count should appear nowhere in the placement rule, so there's nothing global left to keep in sync* — reused for id-to-shard placement instead of key-to-cache-node placement. It's also precisely how Snowflake's own worker-id field works (§11): the field exists specifically so an id is **self-describing** about where it came from, without anyone needing to ask.

## 13. Failure scenarios

Work through these one at a time — each reveals a different part of the design, and a couple of them are places where a design that "sounds right" quietly isn't.

**The KGS is unreachable.** New links can't be created (an app server that's exhausted its current block has nowhere to get another one) — but every existing redirect keeps working, because the read path (§6) never talks to the KGS at all. This is the payoff of separating the read and write paths as sharply as §5 demanded: a coordination-service outage degrades *creation*, which is inconvenient, and leaves *redirection* — the higher-stakes path, per §4's blast-radius argument — completely unaffected.

**One database shard goes down.** Only the redirects whose codes hash to that shard fail; every other shard keeps serving normally. This is a direct benefit of sharding by code (§12): the blast radius of one shard's outage is 1/N of the corpus, not all of it — a sharp contrast with the single-database SPOF from §4.

**A link goes viral (the hot-link / hot-key case, revisited).** The cache from §6 absorbs the read storm for that one code. But — and this is a genuine limit worth stating precisely, not glossing over — sharding by consistent hashing does **not** spread a single hot key's load across multiple shards, no matter how good the hashing scheme is. The [[consistent-hashing]] lab's own list of failure modes it does *not* solve names this explicitly: every scheme agrees a key has exactly one owner, and that agreement is the feature, not a bug to route around. The fix for one code getting an enormous share of traffic is a bigger/faster cache in front of that one shard (or a dedicated local cache on each redirect server for the currently-hottest handful of codes), never "reshard so the hot key spreads out" — resharding a hash-partitioned key doesn't spread one key across multiple owners, it just changes which single owner has to deal with it.

**Cache stampede on cache eviction.** If a viral link's cache entry expires (or the caching layer restarts cold) while it's still receiving thousands of requests per second, a naive cache-aside implementation lets every one of those requests miss simultaneously and hit the database at once. This is the exact failure the [[caching]] lab covers under **single-flight** (collapse concurrent misses for the same key into one database read, fan the answer out to all waiters) — reused here without modification, because the mechanism doesn't care whether the cached value is a URL or anything else.

**A malicious actor abuses the create API.** Nothing in §3–§12 stops someone from scripting a million link creations a minute for spam or phishing redirects. This is a write-path abuse problem, and the fix is the [[distributed-rate-limiting]] lab's whole subject applied directly: rate-limit the create API per API key/IP, and — a URL-shortener-specific addition — screen the destination URL against a known-malicious-URL list (e.g., a Safe Browsing–style API) synchronously before minting a code, so the product doesn't become a phishing redirector.

**Click-counting shouldn't sit on the redirect's critical path.** If every redirect synchronously increments an analytics counter in the same transaction that serves the redirect, analytics-store latency or an outage now directly slows or breaks redirects — reintroducing exactly the fragile coupling §5 was designed to avoid. The fix: the redirect responds immediately from cache, and a click event is emitted asynchronously (a message onto a queue, processed later into whatever analytics store makes sense — see [[database-landscape]] on choosing an OLAP-shaped store for that kind of write-heavy, rarely-point-read data) so a slow or down analytics pipeline never touches redirect latency at all.

## 14. Correctness and guarantees

Pull together what's actually promised, because "highly available" and "strongly consistent" don't both apply uniformly across this design — they apply *per path*, and mixing them up is the most common way this design gets described sloppily in an interview.

**The one real invariant, and its exact scope.** Two short codes must never point to two different long URLs at the same code, ever. That's the *entire* correctness requirement for the write path — noticeably narrower than "the database must be strongly consistent about everything." The block-allocation mechanism from §9 buys exactly this and nothing more: non-overlapping ranges guarantee non-colliding ids, full stop, independent of how many app servers are minting concurrently or how staggered their clocks are.

**Write path: closer to CP.** Creating a link needs the KGS's counter advance to actually be durable and unique before an id is handed out — a lost or double-counted range advance would violate the one invariant above. This is worth naming in [[cap-theorem]] / [[pacelc]] terms: under a partition that isolates the KGS, the write path should refuse rather than risk issuing overlapping ranges (favor consistency over availability, for this one narrow operation).

**Read path: unapologetically AP.** A redirect should almost always answer, even from a slightly stale cached copy, rather than block or fail waiting to confirm the mapping is fresh (§5's whole argument). Concretely: if someone edits a custom alias's destination and a cache entry serves the old destination for a few more seconds until its TTL expires, that's a fully acceptable, deliberately chosen staleness window — not a bug. This is exactly [[pacelc]]'s else-clause (favor latency over consistency on a healthy network) applied to a system that, unlike most of that lab's other examples, essentially never has a legitimate reason to pick the other side for this particular operation.

**What none of this guarantees:** *ordering* of link creation (two links created in the same millisecond on different app servers may land in either block — nothing in this design promises or needs a global creation order), or *exactly-once* delivery of click analytics (§13's async event pipeline is at-least-once, same as every asynchronous pipeline in [[communication-protocols]] — analytics counts are treated as approximate, which is an acceptable trade for a metric that doesn't gate anything).

## 15. Production bridge

The design above is complete as a teaching model. Real products add several more pieces on top of it — named here explicitly rather than left as an implied gap.

**301 vs. 302, and it's a real tradeoff, not a default.** A `301 Moved Permanently` tells the visitor's browser to cache the redirect itself — the *next* time that same browser visits the same short link, it goes straight to the long URL without ever hitting your servers again, which is a genuine, free reduction in your infrastructure's load. The cost: you now can't see that second click (it never reached you) or ever repoint the link to a different destination for that browser (it's cached the old answer, possibly permanently). A `302 Found` costs you a hit on every single click, forever, but keeps full control and full click visibility. Products that sell click analytics as a feature (most commercial URL shorteners) use 302 for exactly this reason, deliberately paying for load they could have avoided.

**Custom/vanity aliases** (`acme.ly/summer-sale` instead of a random code) break the "just mint from a range" story from §9 — a user is now requesting a *specific* string, so uniqueness has to be checked against the exact one they asked for, which is a synchronous existence check against the sharded store, plus a reserved-word/profanity blocklist, plus a race-condition guard for two users requesting the same alias at once (a unique constraint, same tool as §8's naive design — narrower in scope here because it's the rare path, not every single write).

**Expiration and cleanup.** Not every link lives forever by design — some products offer TTLs. Expired rows need a background reaper (never on the read path — an expired-but-not-yet-reaped link should either redirect one last time or return a clean "expired" page, but should never block a live request waiting for cleanup to run).

**Global edge deployment.** For a product with a worldwide audience, the redirect service itself — not just the cache — benefits from being deployed at multiple geographic points of presence (anycast DNS routing a click to the nearest edge location), so the speed-of-light cost of a redirect doesn't dominate over the actual lookup cost, especially once that lookup is a cache hit and takes microseconds.

**What this lesson deliberately did not build:** authentication/ownership of links, a management UI, QR-code generation, per-link analytics dashboards, or link-editing/versioning history. None of them change the core mechanism above; they're additive features layered on top of a design that's already correct without them.

## 16. Common wrong models

**"It's just a hash map — this is a trivial design."** The *storage* half is genuinely trivial (key → value, done). The entire interesting design surface is §7–§12: minting globally unique keys under concurrent, distributed writers without a coordination bottleneck. A candidate who jumps straight to "we'll use a NoSQL database" without ever discussing how ids get generated has skipped the actual hard problem.

**"Shorter codes are always better."** Wrong once you connect code length to keyspace size: §8's table showed a 6-character keyspace exhausting in under two years at a modest write rate. Code length has to be chosen against *projected corpus lifetime*, not against "as short as looks nice." (And in production, the *usable* keyspace is smaller still than 62ⁿ, once you reserve blocklisted/profane strings and anything that collides with existing routes.)

**"Consistent hashing / sharding solves the hot-link problem."** Directly contradicted in §13: sharding by code spreads *different* codes across shards, but a *single* code, by design, always lives on exactly one shard — that's the guarantee, not a gap in it. The fix for one overloaded key is caching in front of it, not redistributing it (which is structurally impossible for a single key under any hash-based scheme).

**"301 redirects are obviously the right choice — they're faster."** True for load, false for the whole story: §15 showed the real cost is losing click visibility and the ability to ever repoint the link. "Faster" and "better" aren't the same axis here, and a design that silently picks 301 for performance reasons has silently also given up an entire analytics feature.

**"A URL shortener needs strong consistency because it's dealing with money-adjacent redirects (affiliate links, etc.)."** §14 draws the line precisely: the *write* path (issuing a code) needs strong guarantees about uniqueness; the *read* path (serving a redirect) is explicitly and correctly AP. Conflating the two leads to over-engineering the read path with coordination it doesn't need and can't afford at 580K reads/sec.

## 17. Knowledge map and next steps

**Built on directly:**
- [[caching]] — cache-aside for the redirect path (§6), single-flight for stampedes (§13), and the skew measurement that explains why a small cache absorbs most traffic.
- [[consistent-hashing]] — sharding by code (§12), and its explicit "hot key is not solved by hashing" finding, reused unchanged in §13.
- [[cap-theorem]] / [[pacelc]] — the write-path-CP / read-path-AP split in §14.
- [[distributed-rate-limiting]] — abuse protection on the create API (§13).
- [[database-landscape]] — choosing an OLAP-shaped store for async click analytics (§13), separate from the OLTP-shaped store for the mapping itself.
- [[communication-protocols]] — at-least-once delivery for the async click-event pipeline (§14).

**Related but not covered here:**
- [[load-balancing]] — distributing redirect traffic across app-server replicas; this lesson assumed that layer exists without re-deriving it.
- [[quorums-and-replication]] — if the mapping store itself is replicated for durability (not just sharded), that lab's R+W>N reasoning applies to reads of the mapping the same way it applies anywhere else.
- [[http-caching]] — the 301-vs-302 discussion in §15 is this lab's subject (`Cache-Control`, browser-side caching of a *response*) applied to a redirect response specifically.

**Commonly confused with each other:**
- *Sharding* (§12, spreading rows across databases by key) vs. *caching* (§6, keeping a small hot copy in front of the database) — sharding scales storage and write throughput; caching scales read throughput. This design needs both, for different reasons.
- *Block allocation* (§9, this lesson's mechanism) vs. *Snowflake* (§11, the well-known alternative) — both solve "mint unique ids without a bottleneck," trading code length for operational simplicity in opposite directions.

**Suggested next labs**, as ready-to-type commands:

- `/learn distributed-id-generation` — a full treatment of Snowflake, ULIDs, and other id schemes, if you want the deep dive §11 only sketched.
- `/learn database-sharding` — if you want the sharding mechanics (resharding, rebalancing, cross-shard queries) in more depth than §12's short version.
- `/learn cdn` — the edge-deployment idea from §15, opened up properly.

## 18. Glossary

- **Short code** — the compact string (e.g., `x7Bq2`) that maps to a long URL.
- **Base62 encoding** — positional notation using 62 URL-safe symbols (0–9, a–z, A–Z); the same idea as hex, with a bigger alphabet, used here to turn an integer id into a short string.
- **Hot key / hot link** — a single key (here, one short code) receiving disproportionate traffic relative to the rest of the keyspace.
- **Cache-aside** — a caching pattern where the application checks the cache first, falls back to the source of truth on a miss, and populates the cache with the result.
- **Single-flight** — collapsing multiple concurrent cache misses for the same key into one source-of-truth read, fanning the result out to all waiters.
- **Key Generation Service (KGS) / ticket server** — a small coordination service that hands out non-overlapping ranges ("blocks") of ids to writers, so writers don't need to coordinate with each other on every write.
- **Block allocation** — the pattern of pre-reserving a range of ids in one coordination call, then minting from that range locally with no further coordination until it's exhausted.
- **Snowflake-style id** — an id built from a timestamp, a fixed worker/shard identifier, and a per-interval sequence number, requiring no shared coordination service at all.
- **Self-routing (self-describing) id** — an id that encodes, in its own bits, which shard or partition owns it, so routing a request needs only arithmetic on the id, not a lookup.
- **301 (permanent) vs. 302 (temporary) redirect** — HTTP status codes distinguishing a redirect a browser may cache and reuse forever (301) from one it must re-request every time (302).
- **Vanity / custom alias** — a user-chosen short code (e.g., `acme.ly/summer-sale`) instead of a system-generated one.
- **CP vs. AP** — shorthand from [[cap-theorem]] for a component that refuses to answer under a partition to preserve consistency (CP) versus one that answers with a possibly-stale value to preserve availability (AP).
