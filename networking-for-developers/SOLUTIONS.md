# Solutions

Every number below was produced by running the modification described. Where an
answer is a design discussion (rungs 7–8) there is no single right output —
those are worked arguments with the trade-offs named.

---

## Rung 1 — Explain

**1.1** The two questions are: **which layer failed**, and **is the request safe
to send twice?**

A retry helps exactly one class of problem — a transient one. It is actively
harmful in two others. Against a permanent failure such as a firewall DROP rule,
each attempt costs a *full timeout* and learns nothing: measured, one attempt
took 3,001 ms, so three attempts hold a thread for nine seconds and fail
identically. And if the request is not idempotent, a retry can double-execute it,
because a failure after the connection was established tells you nothing about
whether the server processed it.

**1.2** A failure at 0 ms means **something answered you**. A machine received
your packets and actively replied "nothing is listening here". That is good news:
the network path works end to end, so the problem is small and local — a wrong
port, or a process that is down.

A failure at 3,000 ms means **nothing answered you at all**, and 3,000 ms is
simply how long you were willing to wait. That is worse news, because you have
learned almost nothing: the packets may have been dropped by a firewall, or gone
to an address that routes nowhere, or reached a machine that is wedged. You have
no evidence anything you sent ever arrived anywhere.

**1.3**

| | Promises | Explicitly does not promise |
|---|---|---|
| A successful TCP connection | A path exists and a process accepted it | That the process will ever reply |
| A TCP acknowledgement | The *kernel* at the far end received the bytes | That the application read them, or processed them, or survived |
| A 200 response | The application processed it and said so | Anything about requests that *failed* — those remain genuinely unknown |

---

## Rung 2 — Identify

**2.1** At the default 16 ms RTT: name lookup 22 ms + TCP 16 ms + TLS ~33 ms ≈
**71 ms before your request body moves at all**. It buys you a resolved address,
an open connection, and a verified encrypted channel. You pay it **once per
connection**, not once per request — which is the entire argument for pooling.

**2.2** Name lookup, TCP handshake and TLS handshake all go grey. The connection
was already established and still open, so there is nothing to resolve and
nothing to negotiate. Only "send request", "server works" and "response" remain.

**2.3** **The server** lights up red, not the firewall. This is the point of the
distinction: the packets got all the way there. The machine is up, reachable, and
answered — with a refusal, because nothing is listening on that port. A firewall
would not have answered at all.

**2.4** The **TCP handshake** row changes, from 2,000 ms to 10,000 ms — and so
does the total. Nothing about the fault changed. **You are controlling the
duration of this failure, not the network.** When elapsed time tracks your
configured timeout, that is the signature of silence: nobody is answering, so
your own patience is the only thing setting the clock.

**2.5** In `Probe.diagnose`:

```java
if (e instanceof SocketTimeoutException) {
    boolean atLimit = elapsedMs >= timeoutMs * 0.9;
```

It distinguishes **"nobody answered"** (elapsed ≈ your timeout, so the timeout is
what ended it) from **"your timeout is simply too short for this server"**
(elapsed well under the limit, meaning something was happening). Same exception
type, opposite problems — only the elapsed time separates them.

---

## Rung 3 — Predict

**3.1** With no read timeout: **no exception at all**, and the request never
returns. In the lab, experiment 3 bounds it with a 4-second watchdog and reports
*"still waiting after 4,096 ms"*; the real call has no upper bound. With a 3 s
read timeout: `SocketTimeoutException` at ~3,000 ms. One dropdown converts an
unbounded hang into a bounded, catchable error.

**3.2** Send 1 **succeeds** — the connection is being created right then, so
there has been no idle period for a middlebox to notice. Send 2 **fails with
Connection reset**, because between the two requests the connection sat idle long
enough to be evicted, and nobody told your end. This is the defining property of
the failure: it is created by request 1 and paid for by request 2.

**3.3** Request 1: **~5,100 ms** (the 5-second resolver timeout plus normal
work). Request 2: **~56 ms**. The name lookup is skipped entirely on a reused
connection, so the fault simply is not on the path. That is why this class of bug
looks intermittent — it only bites cold connections.

**3.4** TCP and TLS grow (they are round trips); name lookup, server processing
and the request send stay flat (they are not). At 250 ms RTT: TCP 250 ms + TLS
~314 ms ≈ **564 ms of setup** against ~40 ms of actual server work. Setup becomes
roughly **90%** of the request. This is why connection reuse stops being an
optimisation at distance and becomes the design.

**3.5** **About 3,000 ms** — because that is the configured timeout, and
`10.255.255.1` is a non-routable address that will never answer. You are not
predicting the network's behaviour; you are reading your own configuration. That
is precisely the tell: *when you can predict a failure's duration from your own
settings, nobody is answering you.* Measured: **3,001 ms**.

---

## Rung 4 — Modify (all measured)

**4.1 — `RTT_MS` 16 → 150**

| | 16 ms RTT | 150 ms RTT |
|---|---:|---:|
| New connection each time | 682 ms | **6,164 ms** |
| One connection reused | 38 ms | **312 ms** |
| Ratio | 17.9× | **19.8×** |

The ratio barely moves; the absolute numbers move by ~9×. The saving is
`(n−1) × setup`, and setup is proportional to RTT — so **the further away the
dependency, the more absolute time pooling saves**, even though the multiple
looks similar. Pooling matters most for cross-region and third-party calls, and
least for a sidecar on the same host.

**4.2 — Is there a break-even point?** **No, and the exercise is a trap.**

Theory first: `fresh = n × (setup + work)`, `reused = setup + n × work`, so the
difference is exactly `(n−1) × setup`. At n=1 they should tie, and reuse can
never lose.

The naive measurement disagrees:

| n | fresh | reused | ratio |
|---:|---:|---:|---:|
| 1 | 113 ms | 35 ms | 3.2× |
| 2 | 158 ms | 36 ms | 4.4× |
| 20 | 791 ms | 40 ms | 19.8× |

**A 3.2× win at n=1 is impossible**, so the measurement is wrong, not the theory.
The cause is **JVM warmup**: the fresh loop runs first and pays for class loading
and JIT compilation of the socket path, which the reused arm then enjoys for
free. Any benchmark that runs arm A before arm B is biased toward B.

Re-measured after 50 warmup iterations, with the simulated RTT removed:

| n | fresh | reused |
|---:|---:|---:|
| 1 | 0 ms | 0 ms |
| 5 | 2 ms | 0 ms |
| 20 | 6 ms | 2 ms |

At n=1 the difference is **zero**, exactly as predicted. Two lessons: there is no
break-even (reuse never loses, and its benefit grows linearly in both `n` and
RTT), and **the first measurement in a JVM is not a measurement.**

**4.3 — `SILENT` closes instead of sleeping**

*Measured:* the request **finished in 74 ms** instead of hanging, and the
read-timeout case "succeeded" in 1 ms — `readLine()` returned `null`.

The server is equally broken in both versions: it never answers the question. But
closing the connection sends a FIN, which reaches the client as **end-of-stream —
an event**. The client learns immediately that no answer is coming and can fail,
retry, or fall back.

Leaving it open sends nothing at all, and a healthy open connection with no
traffic on it is indistinguishable from one that is about to reply.

**This is the single most useful thing a failing server can do for its callers:
fail loudly rather than quietly.** It is also why `Server.SILENT` deliberately
sleeps rather than closes — the whole point of experiment 3 is the case where the
server denies you even that.

**4.4 — Adding `flush()` after `BETA`.** Five identical runs:

| Run | Reads | Grouping |
|---|---:|---|
| 1 | 1 | `ALPHABETAGAMMA` |
| 2 | 1 | `ALPHABETAGAMMA` |
| 3 | 2 | `ALPHA` \| `BETAGAMMA` |
| 4 | 2 | `ALPHABETA` \| `GAMMA` |
| 5 | 1 | `ALPHABETAGAMMA` |

**Three different groupings from identical code**, including one (`ALPHA` |
`BETAGAMMA`) that the un-flushed version never produced. Adding `flush()` did not
make boundaries predictable — it changed the odds.

The conclusion is stronger than "boundaries are not preserved": **they are not
even deterministic**. Any parser that works because messages happened to arrive
whole is a parser that will fail in production, on a different network, under
load, at 3 a.m. — and it will be blamed on the network.

**4.5 — Server idle 2,000 ms, client gap 900 ms**

*Measured:* request 1 → `ok:first`; request 2 → `ok:second`. **No failure**,
because the client came back before the server gave up on it.

The production rule: **the side that holds the connection must give up on it
sooner than the side in the middle does.** Concretely, your pool's idle-eviction
must be *shorter* than the shortest idle timeout anywhere in the path — the load
balancer's, the NAT gateway's, the server's. If your pool evicts at 50 s and the
ALB evicts at 60 s, you always discard connections before they can be silently
killed, and failure 6 becomes impossible. Reverse those numbers and it is
guaranteed.

---

## Rung 5 — Implement

**5.1** The key insight to demonstrate: a read timeout resets on *every* read, so
a drip server sending one byte per interval keeps it alive forever while never
completing. Your deadline must be computed **once, before the first read**, and
each subsequent read's timeout set to `deadline − now`. Prove it with a `DRIP`
mode that sends one byte every 200 ms: a 1 s read timeout never fires; a 1 s
*total* deadline fires at ~1 s.

**5.2** The value of `Diagnose.java` is the ordering: resolve, then connect, then
TLS, reporting the **first** failure with its layer. Stopping at the first failure
is the point — once name resolution fails there is nothing meaningful to say
about TCP, and a tool that reports "DNS failed, TCP failed, TLS failed" has
buried the one fact that mattered.

**5.3** The trap: a pooled connection cannot be validated by asking it. There is
no way to check liveness without sending something, and `isConnected()` answers a
different question (experiment 5). So the pool must rely on **time** — evict on
age, not on a health check — plus optionally a cheap retry-once-on-fresh for
idempotent requests. A test proving it never hands out a dead connection must
therefore test the *eviction clock*, not a liveness probe.

---

## Rung 6 — Debug

**6.1** Measured output:

```
CHECK 1  unreachable_host_fails_within_budget
         outcome  : SocketTimeoutException after 1,514 ms      -> PASS
CHECK 2  silent_server_fails_within_budget
         outcome  : STILL BLOCKED — no exception, no return after 5,004 ms
         -> FAIL  (watchdog gave up at 5,000 ms; the real call has no upper bound)
```

- **Which check fails:** check 2, and it fails by **not failing**. There is no
  exception, no return value, and no log line — only a thread that never comes
  back. Check 1 passes, which is what makes the bug so durable: the obvious test
  case is green.
- **The line:** `socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);`
  is correct but insufficient. Missing: `socket.setSoTimeout(TIMEOUT_MS);` before
  the read.
- **What the 1,500 ms bound:** the TCP handshake, and only the handshake. They do
  not bound writing the request, waiting for the server, or reading the response
  — which together are the entire rest of the call.
- **In a Spring Boot service:** each stuck call parks one request thread forever.
  With a 200-thread pool and a dependency in this state, ~200 requests are enough
  to consume every thread. New requests queue, then time out at the load
  balancer. **The service is now down, and it has logged zero errors** — because
  no exception was ever thrown anywhere.

  This is worse than the dependency returning 500s in every operational sense.
  A 500 is fast, it increments an error counter, it trips a circuit breaker, and
  it frees the thread. A hang does none of those. Your dashboards stay green
  while the service dies, and the alert that eventually fires is about *your*
  latency, pointing investigation at the wrong service.

**6.2** The assertion:

```java
assertTimeoutPreemptively(Duration.ofMillis(2000),
    () -> client.call(silentServer.host(), silentServer.port(), "x"));
```

`assertTimeoutPreemptively` matters — a plain `assertTimeout` runs the code to
completion and then complains, which against this bug means never.

The production metric is **active thread count / thread-pool utilisation**. It is
the only signal that moves during this failure. Error rate, latency percentiles
of *completed* requests, and status-code distributions all stay flat, because the
affected requests never complete and therefore never appear in any of them.

**6.3** Two problems.

**Total elapsed.** Adding retries multiplies the bound rather than enforcing it.
Three attempts at a 1,500 ms read timeout is 4,500 ms of reading plus up to
4,500 ms of connecting — the "no call takes more than 1,500 ms" claim is now
violated by a factor of six. A retry policy needs a *total* budget, not a
per-attempt one.

**Correctness.** `setSoTimeout` fires on requests the server may have received
and processed — the timeout tells you only that no answer arrived in time, not
that no work happened. Retrying a non-idempotent request in that state can
double-execute it. Per stage 12, TCP cannot distinguish "not delivered" from
"delivered, processed, response lost", so a blanket retry is only sound for
idempotent operations or ones carrying an idempotency key.

---

## Rung 7 — Handle failure (worked discussions)

**7.1 — Make the silent hang impossible.** The design move is to **invert the
default**: the wrapper requires a total request budget at construction and
refuses to build without one, rather than defaulting to "unbounded" as every
underlying client does.

Escape hatch: an explicit, awkwardly-named opt-out (`unboundedBecause(String
reason)`) that forces the caller to write down *why* — streaming downloads and
long-poll endpoints are legitimate. Making it verbose is the point; the failure
mode is people not thinking about it, not people being unable to.

What breaks: genuine streaming callers must migrate, and anyone who was
accidentally relying on unbounded behaviour will start seeing timeouts. Both are
good — the second group had this bug and did not know.

**7.2 — Retry from the diagnosis, not the exception.**

| Diagnosis | Retry? | Why |
|---|---|---|
| Refused (fast `ConnectException`) | Yes, briefly | Cheap, and a restart may be in progress |
| Dropped (timeout at your limit) | **No** | Each attempt costs a full timeout and the cause is usually a standing rule |
| TLS failure | **Never** | Deterministic until a certificate changes |
| Unknown host | **No** | The name will not resolve differently |
| Reset after idle | Yes, once, on a **fresh** connection | Almost always a dead pooled connection |
| Read timeout | Only if idempotent | The server may have processed it |

Bound by **total attempts and total time**, with exponential backoff plus
**jitter** (unjittered retries synchronise clients into a thundering herd). The
caller must guarantee the request is idempotent, or supply an idempotency key —
your policy cannot establish that, and must not assume it.

**7.3 — Survive the middlebox.** Numbers to find, in priority order: the load
balancer's idle timeout (60 s here), any NAT gateway's idle timeout, the server's
own keep-alive timeout, and your client's current pool idle setting.

Set pool idle-eviction to roughly **half the shortest** of those — 25–30 s
against a 60 s LB. Half rather than just-under, so a connection cannot be checked
out at the moment it is about to be reaped.

If you cannot find one of the numbers, **do not guess upward**. Take the smallest
value you can confirm and halve it; the cost of evicting too eagerly is a few
extra handshakes, and the cost of evicting too late is user-visible errors. Add
retry-once-on-fresh-connection for idempotent requests as a belt-and-braces
measure — it converts the residual failures into latency rather than errors.

---

## Rung 8 — Design (worked arguments)

**8.1 — The timeout budget.** The rule: **each hop's timeout must be strictly
less than its caller's remaining budget**, and every hop must pass its remaining
budget downstream.

With a 3 s user budget: gateway 3 s → A 2.5 s → B and C 2 s in parallel → D
1.5 s. The shrinking margin absorbs network time and leaves each layer room to
respond rather than being cut off mid-answer.

**What happens to D when A gives up:** D keeps working. Nothing tells it. It will
complete, write to its database, and send a response into a socket nobody is
reading. This is the ordinary state of affairs and it is why *cancellation is a
distributed problem, not a timeout setting* — and why the work D did must be safe
to have happened, which is the idempotency argument again.

**A late response** must be **discarded, not used**. A response arriving after the
deadline is unsafe: the caller has already returned an answer or retried, and
acting on a stale response can produce a second, conflicting action.

**8.2 — Diagnose from a description.**

| Report | Most likely layer | First check |
|---|---|---|
| (a) works from laptop, not cluster | Firewall / security group / egress rules | `nc -vz host port` from inside the cluster — does it refuse fast or hang? |
| (b) fails 09:00–09:04, twice weekly | Something scheduled: DNS TTL expiry, cert rotation, a batch job saturating a link | Correlate against the exact window; check whether failures are fast or slow |
| (c) >1 KB times out | **MTU black hole** — the classic signature | Send increasing payload sizes and find the exact cliff |
| (d) only first request after deploy | Cold connection pool plus a slow cold lookup — failure 1's shape | Instrument DNS lookup time separately |
| (e) p50 40 ms, p99 exactly 5,000 ms | A **timeout**, not slowness — the roundness gives it away | Find what is configured to 5,000 ms; likely a resolver or a retrying client |

The unifying method: **fast or slow, and is the number suspiciously round?**

**8.3 — The case against retries.** The strongest honest version:

> Retries are a correctness liability sold as a reliability feature. They only
> help transient failures, and most of what we retry is not transient: a firewall
> rule, a bad certificate, a wrong port. Against those, each retry costs a full
> timeout and holds a thread — measured, 3 attempts against a DROP rule is 9
> seconds of a thread doing nothing.
>
> Worse, they are unsound by default. TCP cannot tell us whether a failed request
> was processed, so every retry of a non-idempotent operation is a coin flip on
> double execution. And under partial degradation they amplify: the dependency
> slows, everyone retries, offered load multiplies, and a brownout becomes an
> outage.

What would change my mind: evidence the failures actually *are* transient — a
measurable success rate on second attempts. If retry #2 succeeds 40% of the time,
retries are earning their keep. If it succeeds 2% of the time, they are pure cost
and the honest fix is a circuit breaker plus a fast, clear error.

That is also the cheapest thing to instrument before arguing either way: **count
retry outcomes, not just retry attempts.**
