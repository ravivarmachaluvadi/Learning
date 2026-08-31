# Solutions

Attempt [`EXERCISES.md`](EXERCISES.md) first. Rungs 7–8 are worked
discussions, not single right answers. Numbers quoted here were produced by
actually running the experiments on this machine.

## Rung 1

**1a.** When you visit a site, your phone (and often a helper server in your
city) keeps a copy of the answer so it doesn't have to ask again — that's why
pages load instantly the second time. Each copy comes with an expiry time
chosen by the company *when the copy was made*. If they said "good for an
hour" and then changed the price ten minutes later, every copy already handed
out keeps showing the old price for the remaining fifty minutes. The company
can delete copies on machines it owns, but it has no way to reach into your
phone and erase what's there — the internet has no "recall" button. All it can
do is choose shorter expiry times next time, which costs speed.

**1b.** **Freshness** — "reuse your copy for N seconds without asking me" —
saves the entire request: no network trip, no bytes, zero milliseconds.
**Validation** — "ask me cheaply whether your copy is still right" — saves
only the *content*: you still pay a full round trip to hear "still good," but
almost no bytes come back. Freshness is the one that saves the trip; that's
why `max-age` still matters even when you have ETags.

**1c.** They're wrong that `no-cache` prevents storage: `no-cache` means
*store it, but revalidate with the origin before every single reuse*. The copy
is written to disk; it's just never reused blindly. What they meant is
`no-store` — never write it down at all. This exact confusion is everywhere in
real config files. (README §10.4)

## Rung 2

**2a.** Alice's browser-cache box gained a coloured border and a draining bar:
the bar is the remaining freshness, shrinking teal → amber → clay as the copy
ages. The second request was faster because it never left the phone — the copy
was fresh, so the decision procedure stopped at the first step. Bob was
answered by the **CDN**, which stored the copy while relaying Alice's response;
it's allowed to serve it to Bob because the contract said `public` and the
menu is the same answer for everybody — nothing about it is Alice-specific.

**2b.** Hollow dots are requests (questions travelling right); filled dots are
responses (answers travelling left). "still good?" means that cache *already
holds a copy* and is sending only its fingerprint, asking whether it may keep
using it — i.e. this is a validation, not a fetch, so if the answer is yes,
nothing but headers comes back.

**2c.** (i) Phase C — the proxy serves ₹250 while the origin says ₹280; that
is the max-age promise being kept, not a malfunction. (ii) `Age: 4s` — how
many seconds old the served copy is. (iii) `X-Cache: HIT` — the CDN-style
debug header announcing it answered from storage.

## Rung 3

**3a.** One round trip, **zero** bytes of menu content. The copy is stale
(30 s > 15 s) so it can't be reused blindly, but the ETag lets the cache ask
"still good?" and get a `304`. The "still good" replies counter increments;
"content bytes moved" does not.

**3b.** Without the ETag: still one round trip, but now the **full body**
travels again — "content bytes moved" jumps by another 6.4 KB. You disabled
**validation**, leaving only freshness; once a copy without a fingerprint goes
stale, there is no cheap question to ask, so the whole thing must be refetched.

**3c.** Bob sees Alice's account (leak counter = 1). With audience set to
*that user only*, the CDN box no longer shows the `+ /account` line at all —
it refused to store a `private` response — so Bob's request travels to the
origin and he gets his own page. The **privacy leaks** counter staying at 0 is
the proof. Note what this costs: Bob's request is now slow, which is the
honest price of correctness here.

**3d.** Measured on this machine: **30** origin requests without the fix (all
30 concurrent — the origin's peak in-flight was 30), and **1** with
single-flight. The wall-clock for all 30 readers was ~320 ms without the fix
and ~305 ms with it, so single-flight cost the users nothing (it was in fact
marginally faster) and saved the origin 29 duplicate queries.

## Rung 4

**4a.** Phase B: the first user still MISSes, but the copy expires after 2 s,
so with ~1 s spacing you'll see a MISS on every other user — 3 of the 5 —
instead of one MISS total; the origin count rises and average latency
worsens. Phase C: the
wrong price is served for at most 2 s instead of 60. **The rule:** max-age
buys speed and origin offload with staleness — one dial, two opposed costs.
There is no setting that's good at both; you choose the maximum wrongness
you can tolerate, then take whatever performance that allows.

**4b.** The percentage saved goes **down**. Measured: with the large body,
6601 → 89 bytes = **98.7% saved**; with the tiny body, 239 → 89 bytes =
**62.8% saved**. The 304's ~89 bytes of headers are a fixed floor, so the
saving is proportionally huge on big resources and modest on small ones.
Conclusion: ETags earn their keep most on large bodies (bundles, images,
long JSON); on tiny responses the round trip dominates and you should be
reaching for freshness (a longer max-age) instead.

**4c.** With stale-while-revalidate on, the origin counter drops sharply
(11 → ~2 in the packaged scenario) because the cache serves the slightly-stale
copy instantly to everyone and sends exactly one refresh in the background.
Without it, every one of the concurrent requests waits for the origin and all
of them arrive there together. In one sentence: **without SWR the users wait
and the origin is crushed; with SWR nobody waits and one background request
does the work** — paid for by everyone briefly seeing data a few seconds past
its declared lifetime.

## Rung 5

**5a.** Give `Entry` an `etag` component and a `renewed()` helper, then change
the proxy's `proxy(HttpExchange)`:

```java
record Entry(byte[] body, long storedAtMillis, int maxAge, String etag) {
    Entry renewed() { return new Entry(body, System.currentTimeMillis(), maxAge, etag); }
}

Entry entry = STORE.get(path);
if (entry != null) {
    double age = (System.currentTimeMillis() - entry.storedAtMillis()) / 1000.0;
    if (age < entry.maxAge()) {
        reply(ex, entry.body(), true, age);
        return;
    }
    // stale, but we have a fingerprint: ask the cheap question (§10.2)
    HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(originBase + path));
    if (entry.etag() != null) req.header("If-None-Match", entry.etag());
    HttpResponse<byte[]> r = CLIENT.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
    if (r.statusCode() == 304) {                 // nothing changed
        STORE.put(path, entry.renewed());        // renew freshness, keep the body
        reply(ex, entry.body(), true, 0);
        return;
    }
    // 200: the content moved on — fall through and store the new body + new ETag
}
```

(The origin must also send `ETag` and answer 304 on a match — copy that logic
from `Exp02The304Diet.java`.) Verification: after expiry the origin's counter
increments (the check happened) but the proxy transfers no new body, and the
served price only changes after you actually mutate `price` at the origin.

**5b.** Make the key a record and vary on it (`Map<Key, Entry> STORE`):

```java
record Key(String path, boolean wantsGzip) { }

static Key cacheKey(HttpExchange ex) {
    String ae = ex.getRequestHeaders().getFirst("Accept-Encoding");
    boolean wantsGzip = ae != null && ae.contains("gzip");
    return new Key(ex.getRequestURI().getPath(), wantsGzip);  // Vary: Accept-Encoding, as a key
}
```

Request `/menu` twice — once with `Accept-Encoding: gzip`, once without — and
print `STORE.keySet()`: you'll see two entries, `Key[path=/menu,
wantsGzip=true]` and `Key[path=/menu, wantsGzip=false]`. That separation is
precisely what `Vary` buys, and its absence is the §11.3 garbage bug.

## Rung 6

**6a.**
1. **`/menu` with `no-cache` and no ETag** — the worst of both worlds: every
   single request goes to the origin (no freshness) *and* must transfer the
   whole body (no validator). Symptom: full origin load, no savings, and you
   probably believe you're "cached." Fix:
   `Cache-Control: public, s-maxage=300, stale-while-revalidate=600` + an ETag.
2. **`/orders` with `public, max-age=600`** — a personalized response marked
   shareable. Symptom: the §11.2 privacy leak — one customer's order list
   served to another from a shared cache. Fix: `Cache-Control: private,
   no-store` (or `private, no-cache` + ETag if the payload is big).
3. **`/app.js` immutable at a stable filename** — `immutable` promises the
   bytes at this URL will never change, and the deploy breaks that promise.
   Symptom: users stuck on last week's JavaScript for a year, unfixable
   remotely; mixed old-JS/new-HTML breakage. Fix: fingerprint the filename
   (`/app.9f3a2c.js`) and keep the long immutable lifetime — or, if the name
   must stay, `Cache-Control: no-cache` + ETag.
4. **`/search` without `Vary`** — one key, several right answers. Symptom: a
   French user gets the English results a German user's request cached (same
   class of bug as the gzip garbage). Fix: `Vary: Accept-Language`. Beware the
   opposite failure: `Vary: Cookie` on a shared cache gives every user a
   private variant and drops the hit ratio to near zero.

**6b.** The private window working proves the *server and CDN are serving the
correct new content* — a private window has an empty browser cache but uses
the same network path. So the problem is downstream of the CDN: a copy in her
normal browser profile (or a service worker). It rules out a bad deploy, a
stale CDN edge, and a code bug. Ask her to open DevTools → Network → the
failing request and screenshot the response headers, specifically **`Age`**
(with `X-Cache`/`CF-Cache-Status` if present): a large `Age` with a `HIT`
tells you which cache is holding it and how long it has left. The lesson from
§11.6: never say "the cache" — name which one, then check its headers.

## Rung 7 (worked discussion)

**7a.** Add **`stale-if-error=1800`** to `/menu`'s contract:
`Cache-Control: public, s-maxage=300, stale-if-error=1800`. When the origin
returns 5xx or is unreachable, caches may serve their expired copy for up to
30 minutes instead of failing. The cost you accepted: during the outage
customers see data up to 30 minutes old with no indication it's stale — you
have explicitly chosen availability over consistency, the AP corner of
[cap-theorem](../cap-theorem/), declared per response. **Do not apply it to**
anything where a wrong answer is worse than no answer: payment status, order
state, inventory-at-checkout, anything a customer will act on financially.
For those, failing loudly is the correct behaviour, and `must-revalidate` is
the directive that enforces it.

**7b.** The contract: `Cache-Control: public, s-maxage=60,
stale-while-revalidate=30, must-revalidate` plus an ETag, and `max-age=0` so
browsers always defer to the CDN tier you control.

- **The 60-second bound** comes from `s-maxage=60`: no shared copy is served
  as fresh beyond a minute. The `stale-while-revalidate=30` window is a
  deliberate, bounded extension — if your bound is *hard* 60 s, either drop
  SWR or set `s-maxage=30, stale-while-revalidate=30` so the worst case stays
  inside 60.
- **Origin survival at expiry** comes from three things working together:
  the CDN's **request coalescing** (its built-in single-flight — one miss
  refills the entry while the other 29,999 requests wait or get served stale),
  **stale-while-revalidate** so nobody actually waits, and **jittered
  lifetimes** across restaurants so 40,000 menus don't expire on the same
  second. Experiment 03 is the small proof of the first mechanism: 30 → 1.
- **On a price change**, don't wait for expiry: fire a **purge by surrogate
  key** (`restaurant-42`) off the same event feed your
  [transactional-outbox](../transactional-outbox/) already publishes. Expiry
  is then just the safety net for missed purges, not the primary path — which
  is what lets you keep the 60 s bound without setting a punishing TTL.
- **A shield/mid-tier** in front of the origin means a miss at 200 edge
  locations becomes ~1 origin request rather than 200.

## Rung 8 (worked discussion — defensible, not unique)

| Endpoint | Contract | ETag? | Key must include | How a change propagates |
|---|---|---|---|---|
| Marketing site HTML | `public, s-maxage=3600, stale-while-revalidate=86400` | yes | URL | expiry + purge on publish |
| JS/CSS bundles | `public, max-age=31536000, immutable` | pointless | URL (already unique) | **new URL** — never updated in place |
| `/restaurants?city=X` | `public, s-maxage=600, stale-while-revalidate=3600` | yes | URL incl. query | expiry; purge by `city-X` tag |
| `/restaurants/42/menu` | `public, s-maxage=60, stale-while-revalidate=30` | yes | URL | **purge by `restaurant-42`** on edit; 5-min requirement met with margin |
| `/orders/97` | `private, no-cache` + ETag | yes — it's polled often and rarely changes | URL + user identity | revalidate every request; 304s make polling cheap |
| `/me` | `private, no-store` (or `private, no-cache`) | optional | URL + user | never shared, never stale |
| `POST /orders` | `no-store` | n/a | n/a | not cacheable; it also invalidates cached copies of the URL it writes |

Two decisions worth defending explicitly. First, **`/orders/97` gets
`no-cache` rather than a short max-age**: a customer watching their order
would tolerate 5 s of staleness, but the revalidation is nearly free (a 304
on a small payload) and "never wrong" is easier to reason about than "wrong
for up to 5 seconds" on the screen people stare at. Second, **the menu uses
purge-on-write plus a short TTL rather than a long TTL alone** — TTL alone
would force a choice between origin load and the 5-minute correctness
requirement, whereas purge decouples them: the TTL is now the safety net,
not the mechanism.

**Three on-call metrics:**
1. **Edge hit ratio** (and origin-shield ratio separately). A sudden dip means
   someone changed URLs, headers, or `Vary` — the classic cause is a new
   `Vary` header exploding the variant count, or a personalization cookie
   leaking into the key.
2. **Origin offload / origin RPS.** This is your capacity plan; a spike shaped
   like a sawtooth is a stampede (synchronized TTLs), a step change is a
   caching regression.
3. **Purge propagation latency** and **`Age` p95.** If purges are slow or
   failing, your "5 minutes" guarantee is silently broken and only `Age`
   percentiles will show it — nothing errors when caching is too *effective*,
   which is exactly what makes it dangerous.
