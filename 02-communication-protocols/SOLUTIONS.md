# Solutions

Attempt [`EXERCISES.md`](EXERCISES.md) first. Rungs 7–8 are worked
discussions, not single correct answers.

## Rung 1

**1a.** Polling means asking "anything new?" on a fixed clock. Between asks,
news just sits there — so the user sees stale data (feels slow). And most
asks find nothing — but each one still costs a full request: connection,
authentication, a database check (wastes money). Push flips it: the user's
device keeps one open channel, and the server speaks only when there *is*
news. You pay once for the channel instead of repeatedly for empty answers,
and news travels the moment it exists.

**1b.** Missing: (1) **framing** — the receiver can't tell where one message
ends (TCP is a byte stream); (2) **shared meaning** — nobody else's code or
infrastructure understands your invented phrases; (3) **conversation shapes**
— broadcast, mailbox, doorbell all have to be hand-built; (4) **failure
rules** — nothing says what a dropped connection implies or what may be
safely retried.

**1c.** GraphQL solves one specific REST pain — many round trips and
over-fetching when diverse frontends aggregate data — not REST in general.
For public APIs, third-party integrators, and cacheable resources, REST
remains the better fit (curl-able, CDN-cacheable, per-endpoint cost
control). They coexist in the same company: GitHub ships both.

## Rung 2

**2a.** Your system is the **top** lane — in webhook mode you are the
*receiver* of calls, i.e. the server, even though you're the "customer" of
PayFlow. The **provider (bottom)** initiates every data message; your only
messages are 2xx acknowledgements. Red dots on the bottom lane = events
occurring at the provider (news you don't know yet). Green ring on top =
the moment that event was accepted by your endpoint.

**2b.** (i) The blank line (`\r\n\r\n`) ending the request's headers.
(ii) `Content-Length: 75` — exactly 75 body bytes follow. (iii) the status
line `HTTP/1.1 200 OK` — a cache, proxy, or monitor knows the outcome
without understanding the body.

**2c.**
- SSE: client-initiated then server-push · one→one, stream of many events ·
  persistent (one never-ending response) · text · synchronous (both alive).
- gRPC unary: pull · one→one, single answer · persistent HTTP/2 connection
  *reused* across calls, but the call itself is per-request · binary,
  schema-checked · synchronous.
- Kafka topic: push into broker, consumers pull at their own pace · one→many
  (consumer groups) · persistent broker connections · binary · asynchronous
  — the only one of the three where the consumer may be down while the
  producer sends.

## Rung 3

**3a.** At 30 ms: ~4×30=120+ ms vs ~30+ ms — measured ~135 ms vs ~34 ms,
ratio ≈ **4×**. At 200 ms: measured ~825 ms vs ~206 ms — the **ratio stays
~4×** (it's the call count), but the absolute gap grows from ~100 ms to
~620 ms. Lesson: chattiness is a *latency multiplier*, so it hurts in
proportion to how far away your users are.

**3b.** "Client asks" grows fastest (one every 2 s regardless of news);
wasted/total ≈ 4/5 = **~80%** (one useful poll per 10 s window of five).

**3c.** WebSocket: "lost forever" equals the events that fired during the
gap (and any mid-flight when it died); SSE: zero lost, all delivered late.
The responsible feature: SSE's **`Last-Event-ID` reconnect replay** — the
client tells the server the last event it saw, and the server re-sends
everything after it; WebSocket has no equivalent, so recovery is app code.

## Rung 4

**4a.** At 0.1 s: detection latency drops to ~70 ms average (measured
45–105 ms), and request count grows ~10× — from 4–5 to ~35–41 — so you
bought latency with server capacity (and phone battery). At 5 s: requests
drop to 2, latency balloons to ~2 s average (measured 1.0–2.6 s). The law:
**polling trades staleness against waste on one dial; no setting removes
both** — removing both requires changing who initiates (push).

**4b.** Probably yes, one response per read (measured: 183 bytes, then
134 bytes — exactly one response each) — but it is a **coincidence**:
both responses are small, the server wrote them separately, and loopback
rarely fragments. Nothing in TCP guarantees it; under load, across real
networks, with larger bodies, the boundaries move. That's why framing bugs
pass every local test and explode in production — the classic "works on my
machine" with a protocol-shaped cause.

## Rung 5

**5a.** Sketch of the endpoint and client:

```java
// server, a third context alongside /status and /events:
server.createContext("/status-wait", exchange -> {
    exchange.getRequestBody().readAllBytes();
    boolean fired = EVENT.await(8, TimeUnit.SECONDS);   // HOLD the request (long poll)
    byte[] body = ("{\"status\": \"" + (fired ? "DELIVERED" : "COOKING") + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    // ... send 200 + Content-Length + body as in /status
});

// client:
static void longPoller() throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    int requests = 0;
    while (true) {
        requests++;
        HttpRequest req = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/status-wait")).GET().build();
        if (client.send(req, HttpResponse.BodyHandlers.ofString()).body().contains("DELIVERED")) {
            LP_RESULT.put("latency", (System.nanoTime() - eventFiredAt) / 1e9);
            LP_RESULT.put("requests", requests);
            return;
        }
    }
}
```

Expected: latency within a few ms of SSE (the request is already parked at
the server when the event fires), requests 1–2 (one per 8 s hold window).
Measured: **1 request, ~1 ms** — against SSE's ~0.3 ms and the 1 s poller's
~750 ms over 5 requests.

**5b.** The third framing rule: a **delimiter** — SSE ends each event with a
blank line (`\n\n`); header blocks end with `\r\n\r\n`. (HTTP also has a
fourth, chunked transfer encoding — length-prefixed pieces — if you found
that, better still.)

## Rung 6

**6a.**
1. **Line 5 reached only after line 4 — the ack is ~45 s late.** Webhook
   providers time out in seconds; a timeout = "not delivered" = retry. Every
   delivery looks failed, hence "degraded". Fix: **ack first, work later** —
   validate, enqueue, return 200 in milliseconds (the queue from §10.9 is
   exactly the right tool downstream of a webhook).
2. **No idempotency (lines 3–4).** Since retries are contractual, the same
   event arrives more than once — and each arrival ships the order again.
   Fix: dedupe on the provider's event ID (store processed IDs; skip
   repeats), and make `ship_order` refuse an already-shipped order
   (state-machine guard), since "at-least-once + non-idempotent = double
   effect" (§11.4, §12).
3. **No signature verification (line 1).** Anyone who discovers the URL can
   POST `payment.succeeded` and get free food shipped. Fix: verify the HMAC
   signature header against the shared secret before parsing anything.
   (Bonus 4th: trusting arrival order — a retried `payment.succeeded` can
   arrive after `payment.refunded`; re-fetch current truth or compare event
   timestamps before acting.)

**6b.** Correct statement: *the protocol never promised delivery* — WebSocket
is a pipe with framing, not a delivery contract; loss during disconnection is
the application's to handle by design. Production clients add (1) heartbeats
+ reconnect with exponential backoff and jitter, and (2) resynchronization —
sequence-numbered messages with a "give me everything after N" request on
reconnect (or a full state re-fetch if N is too old).

## Rung 7 (worked discussion)

**7a.** Client stores the **last applied event's sequence number** per order
(monotonic, assigned by the server: 1, 2, 3…). On reconnect it sends it —
with SSE this is literally `Last-Event-ID: 17`. Server case A (history
covers it): replay events 18…now, in order, then continue live — the client
buffers live events that race the replay and applies strictly by sequence
number, which is what guarantees no out-of-order display. Server case B
(truncated, i.e. gap > 100 events): send a **snapshot** (current full order
state + current sequence number) instead of a replay; client replaces its
state wholesale and resumes from there. Note this is the
snapshot-vs-replay design straight from [event-sourcing](../50-event-sourcing/),
reappearing at the protocol layer — same problem, same solution.

**7b.** Client: generate a UUID **before** the first attempt; send it as
`Idempotency-Key` on the attempt *and every retry* (same key!); retry on
timeout with backoff, max ~3 attempts, then surface "we're checking your
order" and reconcile via GET. Server: atomically (unique constraint) record
the key with the request's outcome *in the same transaction as the order
insert*; on a duplicate key, return the stored response without re-executing;
retain keys ≥ 24 h (Stripe's order of magnitude). Result: any number of
retries produces exactly one charge and identical responses — the §12 atom
(timeout + retry + idempotency) fully assembled.

## Rung 8 (worked discussion — defensible, not unique)

- **Browse/search/order placement (app → backend): REST over HTTP/2-3**, with
  idempotency keys on order placement. Runner-up: GraphQL — adopt it if the
  home screen's aggregation pain (exp 02) shows up across many client types.
  Deciding failure mode: timed-out POST double-ordering (§11.1). Metric: p99
  latency + duplicate-key hit rate (a rising hit rate = users retrying =
  something upstream is slow).
- **Order tracking (backend → app): SSE**, sequence numbers per order,
  snapshot fallback (7a). Runner-up: WebSocket — only if the client later
  needs high-frequency upstream too. Deciding failure mode: train-tunnel
  reconnect; SSE's replay is free. Metric: reconnect rate + replay-gap size.
- **Rider app: MQTT** (QoS 1 upstream GPS, downstream dispatch offers on a
  per-rider topic), designed for flaky links and battery; last-will detects
  dead riders. Runner-up: WebSocket with app-level acks. Deciding failure
  mode: 60k mostly-idle flaky connections. Metric: undelivered-offer rate
  (offers expiring unanswered) and rider disconnect churn.
- **Restaurant dashboard (browser): SSE** (one-way, browser-native,
  reconnect built in; 2 s budget is easy). Runner-up: WebSocket if order
  accept/reject flows get chatty enough to justify it (they're fine as plain
  POSTs). Metric: event delivery lag p99.
- **Partner grocery chains: webhooks** (signed, event IDs for dedupe,
  documented retry schedule) + a **GET events feed** as the reconciliation
  path for partners who were down past retry expiry. Runner-up: partner
  pulls from the feed alone (simpler for you, worse freshness). Deciding
  failure mode: partner endpoint down (§11.4). Metric: delivery success
  rate + retry-queue depth per partner.
- **order ↔ pricing (5k req/s, needs answer now): gRPC** unary; typed
  contract, cheap encoding, deadline propagation. Runner-up: REST+JSON if
  the team values curl-debuggability over the CPU/latency win. Metric: p99
  with per-call deadlines; deadline-exceeded rate.
- **Order events → billing/notifications/analytics/data team: Kafka**, fed
  via the **transactional outbox** (never dual-write, as you learned);
  consumers idempotent; data team replays from offset 0. Runner-up:
  RabbitMQ if replay weren't required — but the data team's "replayable"
  requirement decides it. Metric: consumer lag per group, DLQ depth.
