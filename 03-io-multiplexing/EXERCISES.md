# Exercises — I/O Multiplexing

Eight rungs, easiest to hardest. Attempt before opening `SOLUTIONS.md`.
Rungs 7–8 have no single right answer — they are design discussions.

Run everything from the lab directory:

```bash
cd ~/Documents/Learning/03-io-multiplexing
```

---

## Rung 1 — Explain

No notation, no jargon. Say these out loud or write them down.

1. Your chat server has 10,000 connected users and 5 of them are typing.
   Explain to a competent engineer who has never heard of any of this **why
   giving each user their own thread is a bad idea** — and be precise about
   which resource actually runs out. (Hint: it is not CPU. Say why not.)

2. Explain **why the obvious fix — one thread that checks every connection in a
   loop — is also wrong**, and name the thing it wastes.

3. In two sentences, what does the kernel do for you that neither design could
   do for itself?

4. A colleague says "I/O multiplexing means the reads happen asynchronously in
   the background." They are wrong. Correct them in one sentence.

---

## Rung 2 — Identify

Point at the actual pieces.

1. Open `src/SelectorEchoServer.java`. Find and quote the line that puts a
   connection into the **interest set**, and the line that reads the **ready
   set**. Which one runs once per connection, and which runs on every loop pass?

2. In the same file, what is the `ByteBuffer` passed as the third argument to
   `register(...)` for? Which README section explains why it has to exist at all?

3. Open `visualization.html`. Run the "Idle crowd" scenario. Which panel's
   thread counter climbs, and which stays at 1? What does the CPU meter show for
   each of the three designs?

4. In `src/Experiments.java`, find the field that counts how many times the
   event loop woke up. In experiment 2 it reported `0` for 3 seconds and 2,000
   connections. Explain what a `0` there actually proves.

---

## Rung 3 — Predict

Commit to an answer, **then** run the command.

1. `java src/Experiments.java threads 5000` — predict the two thread counts
   before running.

2. In experiment 2, suppose we raise the connection count from 2,000 to 20,000.
   Predict what happens to each of the three polling numbers (CPU ms, loop
   passes, readiness questions), and *which direction* loop passes moves. Then
   run `java src/Experiments.java spin 20000` and explain any surprise.
   *(If your `ulimit -n` is low, the run will stop early and say so.)*

3. `java src/Experiments.java readiness` sent one 512 KB message and needed 76
   readiness events. Predict what happens to that number if the message is 5 KB
   instead. What is the smallest message size that reliably needs only one?

4. Experiment 3 measured B's ping at 905 ms behind a 1-second handler. Predict
   B's latency if **three** clients each send a 1-second request just before B's
   ping, on the event-loop server. Then predict it for the thread-per-connection
   server. What general rule do the two answers give you?

5. You register a channel for `OP_WRITE` and never deregister it. Predict what
   your CPU graph looks like, and say which failure in README §13 it matches.

---

## Rung 4 — Modify

Change the code, observe, explain.

1. In `src/SelectorEchoServer.java`, change `selector.select()` to
   `selector.selectNow()`. Run `java src/EchoTest.java`. Do the tests still
   pass? Now run the server and watch CPU (`top -pid <pid>`). What have you
   turned this server into, and which section of the README describes it?

2. Start `SelectorEchoServer` with a slow handler
   (`new SelectorEchoServer(3000)`) and connect two terminals with
   `nc localhost <port>`. Send `SLOW` in one, then immediately type in the
   other. Describe exactly what the second terminal experiences and why.
   Which guarantee is lost — safety or liveness?

3. Delete the `it.remove()` line from the event loop in `SelectorEchoServer`.
   Predict first, then run `java src/EchoTest.java`. Explain the behaviour in
   terms of the interest set versus the ready set.

4. In `src/SpinEchoServer.java`, add `Thread.sleep(1)` at the end of each loop
   pass. Re-run `java src/Experiments.java spin 2000`. CPU drops enormously —
   so why is this *not* an acceptable fix? Name the two things it costs.

---

## Rung 5 — Implement

**The task:** implement message framing.

`src/FramingServer.java` has one unimplemented method, `nextCompleteMessage()`.
Everything else — event loop, per-connection buffer, reply encoding — is
written for you. The wire format and the contract are documented at the top of
that file.

```bash
java src/FramingTest.java     # 0 passed, 5 failed  ← your starting point
```

Make all five pass. The three tests attack framing from different directions:
one message per write, two messages glued into one write, and a 200 KB message
dribbled out in chunks (with the 4-byte length prefix itself split in half).

You are done when:

```
5 passed, 0 failed
```

Then answer: **why can a plain byte-for-byte echo never pass these tests?**

---

## Rung 6 — Debug

`src/BrokenSelectorServer.java` contains exactly one realistic bug. Do not diff
it against `SelectorEchoServer` until you have answered these.

```bash
java src/EchoTest.java broken
```

1. Which test fails, and what is the actual number it reports?
2. What is the **symptom** an operator would see on a production dashboard —
   with no test suite, no stack trace, and no error in the logs?
3. What is the **mechanism**? Walk it: what does the client do, what does the
   kernel then report about that connection, and why does the handler's response
   to that report cause the observed number?
4. Which single line of `SelectorEchoServer` prevents it?
5. Why do the other four tests still pass — and what does that tell you about
   how this class of bug survives code review and reaches production?

---

## Rung 7 — Handle failure

Extend the system to survive something it currently does not.

1. **Idle timeouts.** `SelectorEchoServer` holds a silent connection forever
   (README §13.7). Add a mechanism that closes any connection idle for more than
   10 seconds.
   - Where does the "last activity" timestamp live, given there is no
     per-connection thread?
   - `selector.select()` sleeps indefinitely. If every connection is idle,
     nothing wakes the loop — so how does your sweep ever run? (There are at
     least two workable answers; find both and compare them.)
   - Write a test that proves an idle connection is closed and an active one is
     not.

2. **The write side.** Our echo assumes every write completes
   (`while (buf.hasRemaining()) ch.write(buf);`). Against a client that never
   reads, that loop spins forever on a full send buffer.
   - Rewrite it to buffer the remainder and register `OP_WRITE`.
   - State precisely when you must *deregister* `OP_WRITE`, and what happens if
     you forget.
   - Now add backpressure: what do you do when a single connection's pending
     output reaches 100 MB? Name the trade-off in your answer.

---

## Rung 8 — Design

No code. Write the design; a page each is plenty.

1. **The push notification gateway.** Design a service holding **2 million**
   concurrent WebSocket connections from mobile devices. Each device sends a
   heartbeat every 30 seconds and receives a handful of small messages per day.
   Fan-out publishes arrive from an internal service.

   Cover: how many machines and why (do the arithmetic — what is your real
   binding constraint?); event loops per machine; where connection state lives;
   how a publish for device X finds the machine holding X; what happens to
   in-flight messages when a machine dies; the OS limits you must raise; and the
   five metrics on your dashboard. For each metric, say which README §13 failure
   it detects.

2. **The migration decision.** You own a 200k-line Java service built on Netty,
   written in 2018: callbacks, framing decoders, a worker pool for JDBC. It
   works. Your team now runs on Java 25, and README §16 says virtual threads
   give you the blocking model back with the same efficiency.

   Write the recommendation you would actually give your tech lead. Cover: what
   genuinely gets simpler and what does not; which of §13's failures disappear
   and which survive verbatim; how you would de-risk it; what would make you
   recommend *not* migrating at all; and what you would measure before and after
   to know whether you were right.

3. **The 100% CPU page.** 3 a.m. One instance of your event-loop service is at
   100% CPU on a single core. Request throughput is *normal*. Latency is fine.
   No errors in the logs.

   Write the triage runbook: your first three commands, what each one would show
   for each of the candidate causes in README §13, and how you tell them apart.
   What is your mitigation before root cause, and why is "restart it" both the
   right first move and an insufficient answer?
