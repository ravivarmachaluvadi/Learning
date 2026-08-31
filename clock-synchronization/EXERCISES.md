# Clock Synchronisation — Exercises

Work these before opening [`SOLUTIONS.md`](SOLUTIONS.md). Levels 1–3 check that
you have the model; 4–5 check that you can operate it; 6–8 check that you know
*why the design is the way it is*, which is the only part that transfers to a
system nobody has taught you.

Everything is reasoning, tracing or design. Nothing here asks you to write code.

Where a number is needed, [`README.md`](README.md) has it and
[`visualization.html`](visualization.html) will check your arithmetic.

---

## Level 1 — Explain

**1.1** Alba asks Bruno what time it is, and Bruno tells her, truthfully. Give
**two distinct reasons** why Alba still cannot set her clock correctly. One of
them is fixable and one is not — say which is which.

**1.2** In one sentence each, explain to a colleague why ε is **not** a hardware
quality problem, and why buying a better oscillator does not remove the need for
it.

**1.3** Your team lead says: *"Our clocks are synchronised by NTP, so I can just
compare the timestamps in the two services' logs to see which happened first."*
Write the two-sentence reply. Then write the one sentence you would add if the
ordering mattered for correctness rather than for debugging.

**1.4** Physical time and logical time both order events. Name **three questions**
a physical clock can answer that a Lamport or vector clock provably cannot, and
say what it is about those questions that logical time cannot reach.

---

## Level 2 — Identify

**2.1** Here is the tracking output from a production host:

```
Stratum         : 3
System time     : 0.000247000 seconds slow of NTP time
Last offset     : -0.000191000 seconds
Frequency       : 8.104 ppm fast
Skew            : 0.310 ppm
Root delay      : 0.004820000 seconds
Root dispersion : 0.000934000 seconds
Update interval : 64.0 seconds
Leap status     : Normal
```

(a) Compute this machine's clock error bound.
(b) Which single term dominates it?
(c) Name the one refinement from §9 that would attack that term, and roughly
what it would reduce the bound to.

**2.2** For each quantity, say whether it is **offset**, **drift**, **skew** or
**jitter**:

1. "This host is 8.104 ppm fast."
2. "London and Frankfurt disagree by 41 ms."
3. "This host is 191 µs behind UTC."
4. "Ten consecutive measurements to the same server ranged over 6 ms."
5. "The clock gains 1.73 seconds a day."

**2.3** Of the eight refinements in §9, exactly **two** reduce the *irreducible*
part of ε rather than the noise around it. Which two, and what do they have in
common that the other six do not?

**2.4** One refinement in §9 improves accuracy by **exactly zero**. Which one,
and why is it in the list at all?

---

## Level 3 — Predict

**3.1** An NTP exchange records:

```
T1 = 0.000 ms     (Alba's clock, on send)
T2 = 250.400 ms   (Bruno's clock, on receive)
T3 = 250.900 ms   (Bruno's clock, on reply)
T4 = 12.000 ms    (Alba's clock, on receive)
```

Compute δ, θ, and the honest interval Alba should report. Then state, in plain
English, the sentence Alba is entitled to say about Bruno's clock.

**3.2** A machine polls its time source every **1024 seconds** (NTP's maximum).
Using RFC 5905's PHI of 15 ppm:

(a) How much dispersion has accumulated just before the next poll?
(b) The operator reduces the poll interval to 64 s. What is the new figure?
(c) The operator's stated reason for the long interval was "reducing load on the
time servers". What did the long interval actually cost, in terms a designer
would care about?

**3.3** Your fleet's measured ε is **12 ms**. Two events are logged on two
different machines, 3 ms apart. What can you conclude about their order? Now the
same question with the events 40 ms apart.

**3.4** chrony's `maxslewrate` default is 83333.333 ppm ("one twelfth"). A
machine wakes from a long maintenance window **4.32 seconds** behind. With
stepping disabled, what is the *minimum* time before its clock is correct, and
what is true about every duration the machine measures during that period?

**3.5** 🔮 Before checking: you replace your NTP daemon with one that takes 1,000
samples per poll instead of 8, and averages all of them. Does ε go down, up, or
stay the same — and does the *actual* error go down, up, or stay the same? These
are two different questions.

---

## Level 4 — Modify

**4.1** Google's time-slave daemon polls every 30 s and assumes 200 µs/s drift,
giving the 1–7 ms sawtooth.

(a) The poll interval is changed to **5 seconds**. What is the new sawtooth?
(b) Spanner's commit wait is ~2ε on every write. What does the change buy, in
milliseconds per write?
(c) What did it cost? Name two distinct costs.
(d) Use Panel 2 of the visualization to check (a).

**4.2** Someone proposes changing the assumed drift rate from 200 µs/s to
20 µs/s, on the grounds that "our crystals are actually ±20 ppm, we measured
them". ε would shrink by a factor of ten. What exactly breaks, and *when* would
you find out?

**4.3** A team changes `makestep 1.0 3` to `makestep 0.001 -1` — step the clock
whenever the offset exceeds 1 ms, forever. Their reasoning: "we want the most
accurate clock possible at all times". Describe what their fleet now does, and
name the class of bug they have just enabled on every host.

**4.4** Take Panel 3 of the visualization. Starting from the public-NTP-pool
deployment, what is the **single change** that buys the largest reduction in ε,
and what is the second? Does the answer change once you are on the cloud-local
deployment?

---

## Level 5 — Trace

**5.1** Using the exchange from **3.1**, suppose the true outbound leg was
**9.2 ms** of the 11.5 ms of network time.

Fill in the table:

| Quantity | Value |
|---|---|
| out | 9.2 ms |
| back | ? |
| Bruno's true offset from Alba | ? |
| Alba's estimate θ | ? |
| Alba's error | ? |
| The interval Alba reported | ? |
| Is the truth inside the interval? | ? |

Then: verify that your error figure equals `(out − back) / 2`, and say in one
sentence why that identity had to hold.

**5.2** A machine drifts at **+50 ppm** and polls every **32 seconds**. The
network measurement is perfect (no noise). Trace three poll cycles under two
strategies, giving the error at the end of each cycle:

- **(a) Step to the measured value each poll, no frequency correction.**
- **(b) Same, plus a frequency correction that moves 60% of the way to the
  observed rate at each poll** (i.e. `freq += 0.6 × observed_ppm`).

For (b), give the frequency correction after each poll and the error accumulated
in the following interval. How many polls until the per-interval error is under
100 µs?

**5.3** Four time servers report these intervals, in ms ahead of your clock:

```
S1 [ 42,  50]
S2 [ 47,  55]
S3 [ 44,  49]
S4 [ 46,  61]
S5 [138, 146]
```

Apply the intersection rule. Which sources survive, what interval do you end up
with, and what is your ε? How does that ε compare with the *best single source*
in the list, and why is the comparison the interesting part?

---

## Level 6 — Spot the flaw

Each of these is a design a competent engineer might propose. For each,
construct the **smallest concrete scenario** where it fails, and name the
guarantee that is violated.

**6.1** *"We dropped the intersection algorithm and configured a single time
source. It's a GPS-backed stratum-1 appliance in our own rack — far better than
any of the four internet servers we used to average. Fewer moving parts."*

**6.2** A monitoring agent alerts when a host's clock is bad. It works by having
each host report `System.currentTimeMillis()` in its heartbeat; the monitoring
server compares that to its own clock and alerts if the difference exceeds
100 ms.

**6.3** A distributed rate limiter running on 20 hosts. Each host computes its
bucket key as `floor(System.currentTimeMillis() / 1000)` and increments a counter
in Redis under that key, rejecting requests once the counter exceeds the limit.

**6.4** *"We don't need Cristian's algorithm. We'll measure the one-way delay
directly: the sender puts its send time in the packet, the receiver subtracts it
from its own receive time, and now we know the one-way delay exactly."*

**6.5** An event-sourcing system assigns each event a wall-clock timestamp at the
producing service and uses it as the ordering key in the log. To handle
collisions, ties are broken by a random UUID. **Counterfactual:** if wall-clock
timestamps were guaranteed unique and monotonic *per machine*, would this design
be correct? Explain what is still missing.

---

## Level 7 — Handle failure

Design discussions, not single answers. Aim for half a page each.

**7.1** You are designing a lease-based leader election. You are told ε is
"probably a few milliseconds, but nobody measures it and it isn't monitored".

Describe a design that is **safe regardless of ε** — including when ε is
temporarily enormous — while still being live when clocks are behaving. Be
explicit about which component enforces the safety property, and about what the
lease duration is actually *for* if it is no longer providing safety.

**7.2** Your fleet runs on cloud instances that expose a PTP Hardware Clock
(which does not smear leap seconds) and also has chrony configured against the
provider's public NTP endpoint (which does smear). A leap second is announced
for six months' time.

Write the plan: what you change now, what you change on the day, what you
monitor, and what you tell the teams whose services assume "clocks are within a
few milliseconds". Include what you would do if you *cannot* change the
configuration in time.

**7.3** A service computes request latency as the difference between a
`request_sent_at` timestamp set by the client and a `request_received_at`
timestamp set by the server. The p99 latency dashboard has shown 4.3 seconds for
a week. Nobody can find the slow path.

Explain what is happening, how you would confirm it in one measurement, and what
the dashboard should be built from instead.

---

## Level 8 — Design

**8.1** A trading platform must timestamp orders to within **100 µs of UTC**, and
prove it to a regulator. The platform runs across three of your own datacenters
plus one colocation facility you rent a cage in.

What survives from this lab's model, what changes, what do you buy, and what do
you monitor? Be specific about which of §9's eight refinements you need and which
you don't, and about what "prove it to a regulator" adds that "achieve it" does
not.

**8.2** You are asked to add **external consistency** (a reader that starts after
a write completes must see it) to a service running on stock cloud VMs whose
measured ε is 18 ms.

Lay out the options with their costs. At least one option should not involve
improving the clocks at all. State which you would choose and why.

**8.3** **Counterfactual.** Suppose the network guaranteed perfectly symmetric
one-way delay — outbound and return legs always exactly equal, enforced by the
fabric.

(a) What happens to ε?
(b) Which of §9's eight refinements become unnecessary?
(c) What does Spanner's commit wait cost now?
(d) Is anything in this lab still true? Be careful here — the interesting part of
the answer is what *does not* go away.

**8.4** You inherit a system whose design document says: *"All services
synchronise via NTP, so cross-service event ordering by timestamp is reliable to
within 10 ms."*

You have one week. Describe how you would find out whether that sentence is true,
what you would measure, and what you would do with each of the two possible
answers. Note in particular what you would check **first**, and why the obvious
first check is the wrong one.
