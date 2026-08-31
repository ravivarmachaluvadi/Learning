# Exercises — networking for developers

Rungs 1–3 need only the README and the
[diagnostic trainer](https://claude.ai/code/artifact/cf035459-a3d8-4a72-9111-1c6680ba8605)
(or `visualization.html`). Rungs 4–6 need `java NetLab.java`. Rungs 7–8 are design
work with no single right answer.

Attempt before opening `SOLUTIONS.md`.

---

## Rung 1 — Explain

**1.1** A colleague says: *"the API is flaky, I added a retry."* In your own
words, no jargon, explain the two questions they should have answered first, and
why a retry can make things worse rather than better.

**1.2** Explain to someone with no networking background why a failure that takes
0 ms and a failure that takes 3,000 ms are *good news and bad news respectively*,
even though both mean "I couldn't reach the service".

**1.3** In one sentence each, say what these three promise you, and name one thing
each explicitly does not promise:
(a) a successful TCP connection, (b) a TCP acknowledgement of your bytes,
(c) a 200 response.

---

## Rung 2 — Identify

Open the trainer. Leave everything at its defaults and press **Send request**.

**2.1** In the waterfall, how many milliseconds elapsed *before* the "Send
request" phase began? What is that time buying you, and how often do you pay it?

**2.2** Press **Send request** a second time without changing anything. Which
three phases went grey, and why?

**2.3** Set the fault to *"the server machine is up, the process is not"*. Before
sending: which box in the path do you expect to light up red — the firewall or
the server? Send, and check. Explain the answer.

**2.4** Set the fault to *"a firewall silently drops our packets"* and send. Now
change the connect timeout from 2s to 10s and send again. Which number in the
waterfall changed, and what does that tell you about who is controlling the
duration of this failure?

**2.5** In `Probe.java`, find the branch that uses **elapsed time** rather than
the exception type to decide what happened. Quote it, and say what it is
distinguishing.

---

## Rung 3 — Predict

Write the answer down *before* you run anything.

**3.1** Fault: *"the server accepts, then never answers"*. Read timeout: none.
Predict what your code sees and how long it waits. Then set the read timeout to
3s and predict again. Run both.

**3.2** Fault: *"a middlebox killed our idle connection"*. You press Send once.
Predict the outcome. Now press Send again. Predict that outcome too. Explain why
the two differ.

**3.3** Fault: *"the name server ignores one question type"* (the real 5-second
one). You press Send twice with connection reuse **on**. Predict the total for
request 1 and for request 2, and explain the difference in one sentence.

**3.4** Drag the round-trip slider from 16 ms to 250 ms with nothing broken.
Before releasing: which phases grow, which stay flat, and roughly what fraction
of the new total is setup? Check against the waterfall.

**3.5** `java NetLab.java 2` connects to `10.255.255.1`. Predict the elapsed
time to within 100 ms *without running it*. What information are you using to
make that prediction so precisely?

---

## Rung 4 — Modify

**4.1** In `NetLab.java`, change `RTT_MS` from 16 to 150 (a cross-continent link)
and re-run experiment 4. Does the *ratio* between fresh and reused change, or
just the absolute numbers? What does your answer imply about where connection
pooling matters most?

**4.2** In experiment 4, change the request count from 20 to 2. Is reuse still
worth it? Find, by experiment, the number of requests at which reuse stops paying
for itself — and explain why that number depends on RTT.

**4.3** In `Server.java`, change `Mode.SILENT` so it calls `c.close()` instead of
sleeping forever. Re-run experiment 3. What does the client see now, and why is
this *far better* for the client even though the server is equally broken?

**4.4** In experiment 6, add `o.flush()` after the `BETA` write. Re-run. Did the
number of reads change? Run it five times. What does the variability tell you
about relying on write boundaries?

**4.5** In experiment 5, change the server's idle timeout from 600 ms to 2000 ms
but leave the client's idle gap at 900 ms. Predict, then run. Now explain the
production rule this demonstrates, in terms of which side's timeout must be
smaller.

---

## Rung 5 — Implement

**5.1** `Probe.java` has no whole-request bound — only connect and read. Implement
`Probe.callWithDeadline(host, port, message, totalBudgetMs)` that fails if the
*entire* operation exceeds the budget, regardless of how many individual reads
succeed. Then prove it works against a server that drips one byte at a time
(add a `DRIP` mode to `Server.java`), where a read timeout alone would never fire.

**5.2** Write `Diagnose.java`: a command-line tool taking a host and port that
reports, separately, whether name resolution succeeded, whether TCP connected,
whether TLS completed, and how long each took — printing the layer diagnosis for
the first thing that fails. This is `Probe` assembled into something you could
genuinely reach for on call.

**5.3** Implement a minimal connection pool: `get()` returns an idle connection or
opens a new one, `release()` returns it, and connections idle for longer than a
configured limit are evicted rather than handed out. Prove with a test that it
never hands out a connection the server has already closed.

---

## Rung 6 — Debug

**6.1** Run `java BrokenClient.java`.

- Which check fails, and what does the failure look like — an exception, or
  something else?
- Which single line in `call()` is wrong, and what is missing?
- The author set `TIMEOUT_MS = 1500`. State precisely what those 1,500 ms bound
  and what they do not.
- In a Spring Boot service on a bounded thread pool, describe what happens to the
  **whole service** when one downstream dependency starts behaving like the
  server in check 2. Why is this worse than that dependency returning 500s?

**6.2** Write the smallest assertion you would add to a real test suite to catch
this class of bug, and name the production metric that would reveal it.

**6.3** A colleague proposes this fix instead:

```java
socket.setSoTimeout(TIMEOUT_MS);
// ... and wrap the whole call in a try/catch that retries 3 times
```

Name two distinct problems with it. (One is about total elapsed time; the other
is about correctness, and connects to stage 12.)

---

## Rung 7 — Handle failure

**7.1 — Make the silent hang impossible.** Failure 4 kills services without
raising a single error. Design a change to a Java HTTP client wrapper so that
this failure *cannot* occur, no matter what a caller forgets to configure. What
is the default you enforce, what escape hatch do you allow, and what breaks for
callers who legitimately need long-running requests?

**7.2 — Retry that is safe by construction.** Failures 2, 3, 4 and 6 all reach
your code as exceptions, and only some are worth retrying. Design a retry policy
that decides from the *diagnosis* rather than from the exception type alone.
Specify: which failures you retry, how you bound total attempts and total time,
and what the caller must guarantee about the request for your policy to be sound.

**7.3 — Survive the middlebox.** Your service calls a dependency through a load
balancer with a 60-second idle timeout, and traffic is bursty with long quiet
gaps. Design the pool configuration so users never see failure 6. Name every
number you would need to find out first, and what you would do if you could not
find one of them.

---

## Rung 8 — Design

**8.1 — The timeout budget.** An API gateway gives a user 3 seconds. The request
fans out to service A, which calls B and C in parallel, and B calls D. Assign a
timeout to every hop. State your rule, then answer: what happens to D's work when
A gives up, and does D know? What should A do about a response arriving after it
stopped waiting?

**8.2 — Diagnose from a description.** For each report, name the most likely layer
and the single command or measurement you would run first:
(a) "It works from my laptop but not from the cluster."
(b) "It started failing at exactly 09:00 and recovered at 09:04, twice this week."
(c) "Small payloads work, anything over about 1 KB times out."
(d) "It fails only on the first request after a deploy, then it's fine."
(e) "p50 is 40 ms, p99 is exactly 5,000 ms."

**8.3 — Argue against retries.** Make the strongest honest case that a service you
know should have *fewer* retries than it does. Use failures 3 and 8 and the
correctness argument in stage 12. Then name the conditions under which you would
be wrong.
