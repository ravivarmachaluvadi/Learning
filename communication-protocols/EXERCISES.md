# Exercises — communication protocols

Climb in order; each rung uses the previous. Attempt before opening
[`SOLUTIONS.md`](SOLUTIONS.md). Rungs 3–7 use the experiments and
`visualization.html`; rungs 7–8 are design discussions.

## Rung 1 — Explain (no notation allowed)

1a. In plain words a non-engineer would follow: why do polling-based "live"
features waste money AND feel slow at the same time — and what do push
protocols change?

1b. Explain why "just open a TCP socket and send JSON" is not a protocol yet.
Name the four things that are still missing (§4–§5 if stuck — but try first).

1c. A colleague says "GraphQL is the modern replacement for REST." Correct
them in three sentences.

## Rung 2 — Identify

2a. Open `visualization.html`, pick **Webhook**, press Play. Which lane is
your system? Who initiates every message? What do the red dots on the bottom
lane represent, and what does a green ring on the top lane mean?

2b. In experiment 01's Part 1 output, point to: (i) the framing device in the
*request*, (ii) the framing device in the *response*, (iii) the line that lets
infrastructure (not just your app) understand the outcome.

2c. Place each on the five axes (initiation / cardinality / connection
lifetime / encoding / sync-async): SSE, gRPC unary call, Kafka topic.

## Rung 3 — Predict, then run

3a. `experiments/Exp02RoundTrips.java` simulates 30 ms latency. Predict the two
timings and the ratio **before** running. Then edit `SIMULATED_RTT` to 0.200
(mobile). Predict again, run again. What changed, what didn't?

3b. In the visualization, set "news happens every" to **10 s** and run
**Polling** for ~30 s. Predict first: which counter grows fastest, and what
will "asks with no news" divided by "client asks" roughly be?

3c. Run scenario **"Train tunnel (WebSocket)"**, but predict *both* counters
before: "reached screen" and "lost forever". Then run **"Train tunnel, again
(SSE)"** and explain the difference in one sentence naming the exact protocol
feature responsible.

## Rung 4 — Modify

4a. In `experiments/Exp03PollVsPush.java`, change `POLL_INTERVAL` to `0.1`.
Predict: what happens to detection latency, and what happens to request
count? Which production resource did you just spend to buy that latency?
Now set it to `5.0` and answer the same. State the general law this
demonstrates (one sentence).

4b. In experiment 01 Part 2, change `GULP_BYTES` from `120` to `4096`. Do the
two responses now arrive "one per read"? Run it. Is that behavior *guaranteed*,
or a coincidence of small local messages? Why does this distinction produce
bugs that only appear in production?

## Rung 5 — Implement

5a. Extend `experiments/Exp03PollVsPush.java` with a **long-polling** client
and endpoint (`/status-wait` that holds the request until the event fires or
8 s pass) and add it to the race. It should approach SSE's detection latency
while still being plain request/response. Verify: its detection latency ≈
SSE's, its request count ≈ 1–2.

5b. In experiment 01, replace the `Content-Length` parser with one that
handles a response with no `Content-Length` and `Connection: close` (read
until the socket closes). Both framing rules are real HTTP — name the third
one this lab mentioned but didn't implement (hint: SSE).

## Rung 6 — Debug

6a. A teammate ships this webhook endpoint:

```java
@PostMapping("/callbacks/payflow")
ResponseEntity<Void> payflowCallback(@RequestBody String body) {
    Event event = mapper.readValue(body, Event.class);  // 1
    if (event.type().equals("payment.succeeded")) {     // 2
        markOrderPaid(event.orderId());                 // 3
        shipOrder(event.orderId());                     // 4  (takes ~45 s: talks to 3 services)
    }
    return ResponseEntity.ok().build();                 // 5
}
```

Orders occasionally ship twice, and PayFlow's dashboard shows Tiffin's
endpoint as "degraded, frequent timeouts." Find **three** distinct protocol-
level bugs (line numbers), explain the mechanism of each, and fix the design.

6b. In the visualization, run **WebSocket** with "news every 2 s", press
"Cut the connection", and watch the log. A junior engineer concludes "the
protocol is broken — it dropped our data." What is the correct statement of
whose responsibility the loss is, and what two mechanisms would a production
client add?

## Rung 7 — Handle failure

7a. Design the reconnect story for Tiffin's order-tracking screen (SSE
today): the phone sleeps for 3 minutes, reconnects. Requirements: no missed
transition ever shown out of order, no full-history re-send, server keeps
≤ 100 events per order. Specify: what the client stores, what it sends on
reconnect, what the server does in the two cases (history still covers the
gap / history was truncated).

7b. Tiffin's checkout POST sometimes times out at 10 s, and support tickets
show occasional double-charged customers. Using §11.1's machinery, write the
exact client behavior and server behavior (including what is stored, keyed by
what, for how long) that makes retrying always safe.

## Rung 8 — Design (production scale)

Tiffin has grown: 2M customers, 40k restaurants, 60k riders, a partner API
for grocery chains, and a data team. Design the full protocol architecture:

- customer app: browse/search, order placement, live order tracking
- rider app: GPS upstream every 3 s over flaky mobile networks, dispatch
  offers downstream that must be answerable in ≤ 8 s
- restaurant dashboard (browser): new orders appear ≤ 2 s after placement
- partner grocery chains: get order events into *their* systems
- internal: order-service ↔ pricing-service (5k req/s), plus every order
  event reaching billing, notifications, analytics
- data team: wants every state transition, replayable

For each edge: pick the protocol, name the runner-up, and state the failure
mode that most influenced the pick, plus the one metric you'd put on the
on-call dashboard for it.
