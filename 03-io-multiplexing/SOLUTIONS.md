# Solutions — I/O Multiplexing

Attempt `EXERCISES.md` first. Every number quoted here was produced by running
the code in this lab on the author's machine; yours will differ in magnitude but
not in shape. Rungs 7–8 are discussions, not single right answers.

---

## Rung 1 — Explain

**1. Why thread-per-connection fails at 10,000 users.**

The resource that runs out is **memory and scheduler capacity, not CPU**.

A thread waiting inside `read()` uses no CPU at all — the OS has descheduled it
entirely and will not touch it until bytes arrive. That is exactly why the
answer surprises people. What it *does* consume is a call stack (about 1 MB of
reserved address space per thread on a 64-bit JVM), a kernel thread structure,
and a slot the scheduler must consider. Ten thousand connections means ten
thousand of each, so you exhaust memory and drown the scheduler in bookkeeping
while the CPU sits idle. There is also a hard OS limit on thread count that
arrives regardless of how much RAM you buy.

The one-line version: **we are paying 10,000 salaries for 10,000 people to each
be individually informed of something.**

**2. Why the polling loop is also wrong.**

It wastes **CPU and system calls**. To avoid parking, each connection must be
non-blocking, so the loop asks every connection "anything for me?" and gets "no"
almost every time. Measured: 4.2 million questions and a full core pinned at
100% across three seconds in which *nothing happened*. Each question is a real
syscall — cheap individually, ruinous in millions. It also degrades as you grow:
a full lap costs one question per connection, so latency for any given user gets
worse as the server gets more popular.

**3. What the kernel does that neither design could.**

The kernel receives the packets, so at the instant data lands it already knows
which connection it belongs to — no asking required. So it can hold your entire
list of connections, let your thread sleep, and wake it only when something
actually happens, handing back exactly which connections are ready.

**4. Correcting the colleague.**

"No — multiplexing tells you *you may read now*; **you** still call `read()`
yourself and your thread waits for that copy. It is synchronous, non-blocking
I/O with a blocking wait. The thing you are describing, where the kernel
performs the read and notifies you on completion, is asynchronous I/O —
`io_uring`, Windows IOCP, or Java's `AsynchronousSocketChannel`."

---

## Rung 2 — Identify

**1.** Interest set:
`client.register(selector, SelectionKey.OP_READ, ByteBuffer.allocate(4096));`
(also `serverChannel.register(selector, SelectionKey.OP_ACCEPT)` at startup).
Ready set: `Iterator<SelectionKey> it = selector.selectedKeys().iterator();`

Registration runs **once per connection**; reading the ready set runs **on every
loop pass**. That asymmetry is the whole efficiency argument for `epoll`/`kqueue`
over `select` (README §12): the expensive list is stated once and lives in the
kernel, rather than being re-sent on every call.

**2.** It is the **attachment** — that connection's private state, and here
specifically its read buffer. It must exist because with one thread serving
every connection there is **no per-connection stack** to hold a half-arrived
message. README §13.3 is the section: a 512 KB message arrived across 76
readiness events, so the fragment has to live on the heap between them. This is
the structural cost of the technique — stack-held state becomes an explicit
state machine.

**3.** The thread-per-connection panel's counter climbs to match the connection
count; the multiplexing panel stays at **1**. The CPU meter reads roughly zero
for thread-per-connection (blocked threads are free in CPU terms), pinned for
the polling panel, and roughly zero for multiplexing. The lesson is that the two
"cheap" designs are cheap on *different* axes, and only multiplexing is cheap on
both.

**4.** The field is `loopIterations`. A `0` proves the thread was **never woken**
across those 3 seconds — it was asleep inside `select()`, not looping quickly.
This is the distinction between "efficient polling" and "not polling at all",
and it is why idle connections are genuinely free rather than merely cheap.

---

## Rung 3 — Predict

**1.** 5,000 handler threads versus 1 event-loop thread — exactly linear versus
exactly constant.

**2.** Verified by running `java src/Experiments.java spin 20000` against the
2,000-connection baseline. The measured numbers:

| | 2,000 conns | 16,334 conns |
|---|---|---|
| CPU burned in 3 s | 2,998 ms | 3,001 ms — still exactly one core, pinned |
| Loop passes | 2,111 | **217** |
| Readiness questions | 4,225,570 | 3,547,626 |

The surprise is that **loop passes go DOWN by 10×, not up**. CPU was already
saturated at 2,000 connections, so 8× the connections buys no extra CPU — it
just makes each lap 8× longer. The syscall count stays the same order of
magnitude for the same reason: it is capped by how many syscalls a core can
issue per second, not by how many connections you have.

**Why 16,334 and not 20,000 — an unplanned bonus lesson.** The run stopped
early with `Can't assign requested address`. That is **not** the file-descriptor
limit (`ulimit -n` is over a million on this machine). It is **ephemeral port
exhaustion**: every client connection consumes a source port from a range of
roughly 16k–28k ports, and closed sockets hold their port in `TIME_WAIT` for
minutes afterwards. So the *client* side hit a ceiling long before the server
did — which is why the second half of that run reported 0 connections and its
comparison should be ignored.

Worth internalising, because it is a real and frequently misdiagnosed
production ceiling (README §16 lists it among the operational limits): when
load-testing a server from one machine, **you usually measure your load
generator's port limit, not your server's capacity.** Real gateways see 2M
connections because those arrive from millions of distinct client IPs, each
with its own port range.

That is the real lesson, and it is worse than "it uses more CPU": **your
per-connection latency degrades linearly with popularity.** A user's message
waits for the loop to walk every other connection first. The design does not
fall over — it gets quietly, uniformly slower for everyone.

**3.** A 5 KB message will almost always need **one** readiness event: it fits
comfortably inside the kernel's receive buffer and inside a single `read()`.
Roughly, anything below the socket receive buffer size (tens of KB by default,
tunable via `SO_RCVBUF`) that is also sent as one write will usually arrive as
one event.

But "usually" is the trap. A single TCP segment is typically ~1.4 KB on
Ethernet, so even a 5 KB message physically crosses the network in several
packets — you got one event only because they arrived faster than your loop
came round. Under a different network, a slower sender, or a busier loop, the
same 5 KB message splits. **There is no message size that guarantees one
event**, which is why framing is mandatory rather than an optimisation.

**4.** Event-loop server: roughly **3 seconds** — the three slow handlers run
back to back on the one thread, and B waits behind all of them. It is additive.
Thread-per-connection: still roughly **0 ms** — B has its own thread and is
unaffected no matter how many slow requests are in flight.

The general rule: **on a shared event loop, latency for any connection is a
function of the total work queued on that loop, not of that connection's own
work.** Slow handlers do not just hurt themselves; they serialise everyone.

**5.** A core pinned at 100% while the connection is idle. It matches **README
§13.2** (the busy loop) — a socket's send buffer is empty almost all the time,
so it is writable almost always, so a permanently-registered `OP_WRITE` makes
`select()` return instantly forever. Same failure as the unclosed dead
connection, from the write side. The rule: register `OP_WRITE` only while you
have leftover bytes, and deregister the instant they drain.

---

## Rung 4 — Modify

**1. `selectNow()`.** Verified — the tests come back:

```
4 passed, 1 failed
FAIL closed_connection_is_deregistered_and_loop_sleeps (wakeups in 500 idle ms = 28853)
```

Four still pass because `selectNow()` is functionally correct: it returns the
same ready set, just without waiting. But it never sleeps, so the loop spins —
28,853 wakeups in 500 idle milliseconds, and `top` shows a core pinned.

**You have turned `SelectorEchoServer` back into `SpinEchoServer`** (README §5),
with one improvement: the kernel still does the readiness scan, so it is a
cheaper spin than asking each connection individually. It is still a spin.

The detail worth keeping: **the same test caught this as caught the planted bug
in rung 6.** A "did the loop actually go to sleep?" assertion is a single test
that catches an entire family of event-loop bugs. Worth stealing for real code.

**2. Two terminals.** The second terminal sees its echo delayed by the remainder
of the 3-second sleep — it types instantly, and nothing comes back until the
first client's handler finishes. Its bytes reached the machine within a
millisecond and sat unread in a kernel buffer because the only thread that could
read them was asleep in someone else's handler.

**Liveness is lost, safety is kept.** Nothing incorrect happens — no bytes are
lost, corrupted, or mixed between connections; the echo is perfectly correct when
it eventually arrives. What fails is *progress within a useful time*. That
mapping matters: event-loop blocking is a liveness bug, which is why it produces
latency alarms and timeouts rather than data corruption, and why it can hide in
production for a long time.

**3. Deleting `it.remove()`.** Verified — the server **dies**, and the test suite
hangs rather than failing:

```
Exception in thread "selector-loop" java.lang.NullPointerException:
    Cannot invoke "SocketChannel.configureBlocking(boolean)" because "<local1>" is null
	at SelectorEchoServer.handleNewConnection(SelectorEchoServer.java:89)
loop wakeups after 1 msg: 2
loop wakeups after 2 msgs: 2      ← frozen
event-loop thread still alive: false
```

The mechanism, in interest-set/ready-set terms: **the selector adds to
`selectedKeys()` but never removes from it.** Clearing handled entries is the
application's job. So the accept key, handled once when the client connected,
stays in the ready set forever and is re-dispatched on every subsequent pass.
The second time `handleNewConnection()` runs there is no pending connection, so
`accept()` correctly returns `null`, and the code dereferences it.

Three lessons: the ready set is a mutable buffer you own, not a fresh snapshot;
readiness is a **hint** — a "ready" `accept()` may legitimately yield nothing
(the same reason thundering herd is survivable, README §13.5); and the event
loop catches only `IOException`, so a `RuntimeException` kills the thread and
silently takes every connection with it. Real event loops wrap dispatch in
`catch (Throwable)` for exactly this reason.

**4. `Thread.sleep(1)` in the spin loop.** CPU collapses because the loop now
runs ~1,000 times a second instead of millions. It is not acceptable because it
costs two things:

- **Latency.** Every message now waits up to 1 ms (plus a full lap) before
  anyone looks at it. You have traded a CPU problem for a latency floor that no
  amount of hardware removes.
- **The scaling problem is untouched.** Each lap still costs one syscall per
  connection. At 20,000 connections a lap cannot finish inside 1 ms anyway, so
  the sleep becomes irrelevant and you are back to §6.

This is the honest reason polling-with-a-sleep is not "good enough": it is a
tunable trade between wasted CPU and added latency, and multiplexing refuses the
trade entirely — zero CPU *and* immediate wakeup.

---

## Rung 5 — Implement

Verified: this implementation takes `FramingTest` from `0 passed, 5 failed` to
`5 passed, 0 failed`.

```java
static byte[] nextCompleteMessage(Conn c) {
    if (c.len < 4) return null;                       // can't even read the length yet
    int n = ByteBuffer.wrap(c.buf).getInt(0);         // big-endian length prefix
    if (c.len < 4 + n) return null;                   // prefix known, payload incomplete
    byte[] payload = new byte[n];
    System.arraycopy(c.buf, 4, payload, 0, n);
    c.consume(4 + n);                                 // drop prefix AND payload
    return payload;
}
```

Four details carry the whole exercise:

- **Two separate incompleteness checks.** `c.len < 4` (cannot know the length
  yet) and `c.len < 4 + n` (know the length, lack the body) are different states
  and both must return `null`. The dribble test deliberately splits the 4-byte
  prefix across two writes to force the first case.
- **Returning `null` is the normal case, not an error.** Being handed half a
  message is the ordinary condition of a byte stream.
- **`consume(4 + n)`** must drop the prefix as well as the payload. Dropping
  only the payload leaves 4 stray bytes that corrupt every subsequent frame — a
  bug that presents as "the protocol desynchronises after the first message".
- **The caller loops** (`while ((payload = nextCompleteMessage(conn)) != null)`).
  Handling one message per wakeup is the bug the second test is built to catch:
  two glued messages leaves the second stranded in the buffer until unrelated
  traffic arrives, which in production looks like random, unreproducible hangs.

**Why a byte-for-byte echo can never pass:** the contract requires replying
`"<n>:<payload>"` where `n` counts *complete messages*. Producing that number
requires knowing where messages end. An echo copies bytes without ever forming
the concept of a message, so it has no `n` to report. That is the entire point:
readiness gives you bytes, and **only framing gives you messages.**

---

## Rung 6 — Debug

**1.** `closed_connection_is_deregistered_and_loop_sleeps` fails with **351,609
wakeups in 500 idle milliseconds** (correct server: 0).

**2. The production symptom:** one CPU core pinned at 100%, with **normal
throughput, normal latency, and nothing in the logs**. Possibly triggered hours
after the client that caused it disconnected. Nothing looks broken — which is
precisely what makes it hard. Eventually you accumulate enough of these zombie
registrations that real work starts to suffer.

**3. The mechanism**, step by step:

- The client closes its socket. TCP delivers the close to the server.
- The kernel now reports that connection as **permanently readable** — because
  end-of-stream *is* a readable condition. A read on it returns `-1` immediately,
  forever. Java's `Selector` is **level-triggered**, so it re-reports the
  condition on every call for as long as it holds.
- `BrokenSelectorServer` does `if (n <= 0) return;` — folding `-1`
  (end-of-stream, permanent) together with `0` (nothing right now, transient)
  and treating both as "try again later".
- But the registration is never cancelled, so the condition never clears.
  `select()` returns instantly, the handler returns instantly, `select()` returns
  instantly — 351,609 times in half a second.

The bug is fundamentally a **conflation of two different meanings of "no data"**.

**4. The line that prevents it:**

```java
if (n == -1) { key.cancel(); ch.close(); return; }
```

`cancel()` removes it from the interest set; `close()` releases the descriptor.
Either alone is insufficient — leaking descriptors will eventually hit
`ulimit -n`.

**5. Why the other four still pass, and why that matters.**

Every other test exercises **live** connections doing normal work, and the bug
only manifests on a **dead** one. Echo works, concurrency works, per-connection
buffers work. The happy path is completely intact.

This is the general shape of event-loop bugs and why they reach production:
they are not correctness failures but **resource-consumption failures on the
cleanup path**. Code review sees a plausible null-ish guard; functional tests
pass; staging with a handful of well-behaved clients shows nothing. It surfaces
only under real traffic where clients disconnect abruptly.

The defence is the test itself: assert that **the loop actually goes back to
sleep**. It is cheap, and it catches this bug, the `selectNow()` mistake in rung
4.1, the permanent-`OP_WRITE` mistake in rung 3.5, and the JDK epoll spin bug of
§13.8 — four unrelated causes, one assertion.

---

## Rung 7 — Handle failure

### 7.1 Idle timeouts

**Where the timestamp lives:** on the per-connection state object — the
`SelectionKey` attachment. Extend the attachment from a bare `ByteBuffer` to a
small `Conn { ByteBuffer buf; long lastActivityNanos; }` and stamp it on every
successful read. This is the same "no stack, so state goes on the heap" move as
framing.

**How the sweep ever runs** — the real subtlety. `selector.select()` with no
argument sleeps indefinitely, so if every connection is idle, nothing wakes you
and your timeout never fires. Two workable answers:

1. **`select(timeout)`.** Use `selector.select(1000)`, and after each return —
   whether it woke for events or timed out — sweep `selector.keys()` for
   connections past the deadline. Simple, self-contained, and costs one wakeup
   per second per loop even when totally idle.
2. **A separate timer thread plus `selector.wakeup()`.** A scheduler computes
   the next deadline and calls `wakeup()` to break the loop out of `select()`.
   More precise and no idle wakeups, at the cost of a second thread and
   cross-thread care (mutating a `Selector`'s registrations from another thread
   needs the wakeup, or you can deadlock against a concurrent `select()`).

**Comparison:** (1) is right for almost everyone — its cost is one wakeup per
second, which is nothing next to the 351,609 in rung 6. (2) is what high-end
frameworks do; Netty's `HashedWheelTimer` amortises thousands of timers into
coarse buckets so a million connections do not mean a million timer objects.
Sweeping *all* keys every second is O(total connections) — the very cost model
`epoll` was invented to escape (§12) — so at very large scale you need the wheel
or a deadline-ordered structure rather than a linear scan.

**The test:** open two connections; keep one active with traffic every second;
leave the other silent. Assert that after the timeout the silent one is closed
(its client read returns `-1`) and the active one still echoes.

### 7.2 The write side

```java
// on write-readiness, or when new data is produced:
int written = ch.write(pending);
if (pending.hasRemaining()) {
    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);   // more to send
} else {
    key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);  // drained — MUST clear
}
```

**When to deregister:** the instant `pending` is fully drained, in the same
handler pass. **If you forget:** a socket with an empty send buffer is writable
essentially always, so `select()` returns immediately forever — rung 3.5, and
§13.2's busy loop from the write side.

**Backpressure at 100 MB pending.** You are queueing faster than one client can
consume, and the queue is on your heap. Options, with the trade-off named:

- **Drop the connection.** Safest for the server, worst for that user. Correct
  for a fan-out feed where a client that far behind is useless anyway.
- **Stop reading from the source** (deregister `OP_READ` on the upstream, or
  stop pulling from the producer). Correct for a proxy: propagates the pressure
  to whoever is producing, which is the only place it can genuinely be resolved.
- **Drop messages, keep the connection** — newest-wins or sample. Correct for
  telemetry or price ticks, where stale data has no value.
- **Grow the buffer.** Not an option; it converts a slow client into an
  out-of-memory kill that takes down every other connection on the machine.

The general principle: **an unbounded queue is a deferred crash.** You must
choose in advance who suffers — the slow client, the producer, or the data —
because the one unacceptable answer is "the whole process". Netty encodes this
as `WriteBufferWaterMark` plus `Channel.isWritable()`, which is a mechanism for
asking the question, not an answer to it.

---

## Rung 8 — Design

Discussion answers. Yours will differ; the reasoning is what counts.

### 8.1 Push notification gateway, 2M connections

**The binding constraint — do the arithmetic first.** Traffic is trivial: 2M
devices heartbeating every 30 s is ~67k heartbeats/second across the fleet, and
a handful of messages per device per day is negligible. So the constraint is
**not** CPU or bandwidth. It is **memory per connection**, plus file descriptors.

Budget per connection: kernel socket buffers (the dominant term — tune
`SO_RCVBUF`/`SO_SNDBUF` down hard, since these are tiny messages), plus your own
per-connection object (identity, subscriptions, last-seen, partial-frame
buffer). Get that to a few KB and 500k connections per machine is ~2–4 GB of
connection state, which is comfortable. So: **4–6 machines for capacity, more
for redundancy and headroom** — say 10, so losing one costs 10% and the
reconnect storm is survivable.

- **Event loops per machine:** one per core (8–16), each owning a disjoint set of
  connections so no locking is needed on connection state. Accept via
  `SO_REUSEPORT` so the kernel balances new connections (§13.5).
- **Where state lives:** the connection object on the owning loop. Nothing
  shared, nothing locked.
- **Finding device X:** a routing registry — `device_id → gateway_instance`, in
  Redis with a TTL refreshed by heartbeat. Publishers look up X and forward to
  that gateway. Alternative: consistent hashing on device id, which removes the
  registry but makes rebalancing on scale-up painful, since every connection is
  long-lived and must be dropped to move. Given devices reconnect anyway, the
  registry is the safer choice.
- **In-flight messages on machine death:** connections are lost, so delivery
  must not depend on them. Persist per-device pending messages with an ack from
  the device; on reconnect, drain the pending queue. Accept at-least-once and
  make the client deduplicate — the same conclusion as the
  `transactional-outbox` lab. Never treat "written to socket" as delivered.
- **OS limits:** `ulimit -n` well above 500k; `net.ipv4.ip_local_port_range`;
  `net.core.somaxconn` for the accept backlog; conntrack table size if a
  stateful firewall is in the path (a classic silent ceiling); ephemeral port
  exhaustion on any intermediate proxy.

**Dashboard — five metrics, each tied to a §13 failure:**

| Metric | Detects |
|---|---|
| Event loop lag p99 | §13.1 — someone blocked the loop |
| CPU per loop thread with flat traffic | §13.2 / §13.8 — busy loop |
| Pending write bytes per connection, p99 | §13.4 — slow consumers, pre-OOM |
| Open fds vs limit, and connections per loop | capacity ceiling and imbalance |
| Connection age histogram / reconnect rate | mass disconnects, thundering-herd reconnect storms |

The reconnect storm deserves emphasis: if one gateway dies, 500k devices
reconnect at once. Mandatory client-side **exponential backoff with jitter**, or
the failure of one machine cascades into the failure of the rest.

### 8.2 The Netty → virtual threads migration

**Recommendation: do not migrate the existing service. Adopt virtual threads for
new services and for any rewrite you were already going to do.**

The honest reasoning:

**What genuinely gets simpler:** business logic that currently spans callbacks
becomes straight-line code. Stack traces become readable. `try/finally` and
try-with-resources start working. The worker-pool hop for JDBC disappears —
you can block directly. Debugging and profiling get dramatically better.

**What does not get simpler:** framing decoders (§13.3) are still needed —
`LengthFieldBasedFrameDecoder` does not vanish, you just write the loop by hand.
Backpressure (§13.4) is still yours and is arguably *harder*, because a blocking
write hides the queue rather than exposing a watermark. TLS, protocol codecs,
idle timeouts, and connection limits are all unchanged. You would be rewriting a
large amount of well-tested protocol code to get an ergonomic improvement in the
business layer.

**Which §13 failures disappear:** §13.1 only — blocking no longer poisons
neighbours, which is the big win. **Which survive verbatim:** §13.3 framing,
§13.4 backpressure, §13.7 timeouts, fd limits. Note §13.2 transforms rather than
vanishes: you will not busy-loop on a dead connection, but you can leak virtual
threads blocked forever on connections nobody closes — the same resource leak
wearing different clothes.

**What would make me recommend against it outright:** heavy use of
`synchronized` around I/O on a JDK before 24 (pinning, from the
`java-virtual-threads` lab, puts the old ceiling straight back); native
libraries in the I/O path; or a genuinely latency-critical path where Netty's
pooled, reference-counted `ByteBuf` is doing real work that plain blocking code
would replace with garbage.

**De-risking:** pick one new low-traffic endpoint, build it thread-per-request
on virtual threads, run both in production, and compare. Do not convert the
protocol layer at all in the first step.

**Measure before and after:** p99 and p99.9 latency (not the mean — the whole
argument is about tail behaviour), allocation rate and GC pause time, peak
memory at target connection count, thread/carrier counts and pinning events
(JFR `jdk.VirtualThreadPinned`), and — the one everyone forgets — **developer
time to ship a feature**, since that is the actual benefit being purchased.

### 8.3 The 100% CPU page

**First three commands:**

1. `top -H -p <pid>` (or `jcmd <pid> Thread.print`) — **which thread?** If it is
   one named `nioEventLoopGroup-*` / `selector-loop`, you are in §13.2/§13.8. If
   it is a GC thread, this is not an event-loop problem at all and the rest of
   the runbook does not apply.
2. `jcmd <pid> Thread.print` a few times in a row — **what is that thread
   doing?** Repeatedly parked in `epoll_wait` returning instantly means a spin.
   Sitting in application code means §13.1 (a blocked loop), though note §13.1
   usually raises latency, and this page says latency is *fine*.
3. `strace -c -f -p <pid>` for a few seconds (or `perf top`, or an async-profiler
   flame graph) — **count the syscalls.** Millions of instant `epoll_wait`
   returns is the signature of a busy loop and distinguishes it from real work.

**Telling the candidates apart:**

| Cause | Distinguishing evidence |
|---|---|
| §13.2 dead connection not deregistered | `epoll_wait` returns instantly with a ready fd; the fd maps to a closed peer; CPU stepped up at a time matching a client disconnect |
| §13.4 permanent `OP_WRITE` | `epoll_wait` returns instantly reporting *writable*; correlates with a large-response endpoint |
| §13.8 JDK epoll spin | `epoll_wait` returns **0 keys** repeatedly — nothing is ready at all, which is the giveaway that your code is not at fault |
| §13.1 blocked loop | thread is in application frames, not `epoll_wait`; latency would be elevated, so this is inconsistent with the page |

The fact that **throughput and latency are normal** is the strongest clue: the
loop is doing something pointless *quickly*, rather than doing something slow.
That points at §13.2 or §13.8 and largely rules out §13.1.

**Mitigation before root cause:** take the instance out of the load balancer and
restart it. That is the right first move because the CPU is being burned by
accumulated bad state (a zombie registration, a wedged selector) that a restart
clears, and you have N other instances serving traffic — availability first.

**Why it is insufficient:** the bad state accumulates again. You have bought
hours, not a fix, and you have destroyed the evidence. So before restarting,
capture: a thread dump, a flame graph, and the fd list
(`lsof -p <pid>` / `ls /proc/<pid>/fd`). Then restart. If you skip the capture,
you will be paged again at 3 a.m. with exactly the same information you have
now.
