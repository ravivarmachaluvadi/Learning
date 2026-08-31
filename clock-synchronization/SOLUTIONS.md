# Clock Synchronisation — Solutions

Attempt [`EXERCISES.md`](EXERCISES.md) first. Levels 7–8 are design discussions:
what follows is a worked reasoning process, not the only defensible answer, and
where that is true it is said so.

---

## Level 1 — Explain

**1.1** Two reasons.

1. **The answer is stale.** Bruno's reply was true when he wrote it, and it spent
   the return leg travelling. Alba would set her clock behind by exactly the
   return-leg delay — systematically, in one direction, never averaging out.
   **This one is fixable**: measure the round trip and add half of it (Cristian's
   algorithm, §5).
2. **She does not know how the round trip split between the two legs.** Half is
   a guess. If the outbound leg was 4.5 ms and the return 34.5 ms, her answer is
   15 ms wrong and every number she can observe is identical to the case where it
   is exactly right. **This one is not fixable** from round-trip measurements —
   one equation, two unknowns (§6).

**1.2**
- *ε is not a hardware quality problem* because its floor is δ/2 — half the
  network round trip — and that is a property of the path, not of the clock at
  either end. Two atomic clocks connected by a 40 ms link still cannot determine
  their offset to better than ±20 ms by talking to each other.
- *A better oscillator does not remove the need for ε* because it only flattens
  the drift term between polls; it cannot tell you which direction the network
  delay went. And the deeper reason: a design that never reads ε is unsafe at
  *any* ε — the number being small does not make the code that ignores it
  correct.

**1.3**
> "NTP does not keep clocks equal — it keeps each clock within a bound of UTC and
> tells us what that bound currently is. Until we know that number for both
> hosts, two timestamps closer together than the bound tell us nothing about
> which event came first."

And if it matters for correctness rather than debugging:

> "For anything where getting the order wrong is a bug rather than an
> inconvenience, don't order by timestamp at all — use a fencing token, an epoch,
> or a single sequencer."

**1.4** Three questions physical time answers and logical time cannot:

1. **"Has this lease/certificate expired?"** — expiry is not an event anybody
   sent a message about, so there is no happens-before edge leading to it.
2. **"How long did this take?"** — a Lamport counter difference of 4 could be
   4 µs or 4 hours. The `hybrid-logical-clocks` lab measured one counter value
   spanning 34 ticks of real time.
3. **"What happened between 09:00 and 09:05?"** — auditors, regulators and
   billing systems ask this, and "between counter 4100 and 4900" is not an
   answer to it.

What these have in common: logical time is built **only** out of messages that
were actually sent. All three questions are about events that generated no
message, or about a quantity (duration, calendar position) that message-passing
does not carry.

---

## Level 2 — Identify

**2.1**

(a) `ε = system time + ½ × root delay + root dispersion`
```
  = 0.247 ms  +  (0.5 × 4.820 ms)  +  0.934 ms
  = 0.247     +   2.410            +  0.934
  = 3.591 ms
```

(b) **½ × root delay = 2.41 ms**, which is 67% of the total. This is §6's
irreducible term: the machine is several network hops from its reference.

(c) The refinement is **§9.8 — move the reference clock next to the machine**
(or §9.7, PTP, if you own the switches). Dropping root delay from 4.82 ms to a
cloud-local source's ~0.112 ms takes the half-delay term from 2.410 ms to
0.056 ms; dispersion falls with it. The bound goes from ~3.6 ms to well under
100 µs — the figure AWS publishes for its local NTP path. Nothing about the
crystal changed.

**2.2**
1. **Drift** (a rate, in ppm).
2. **Skew** (a difference *between two clocks*; says nothing about whether
   either is right).
3. **Offset** (a position error against a reference).
4. **Jitter** (variability of the *measurement*, not of the clock).
5. **Drift**, expressed in seconds per day instead of ppm. 20 ppm and 1.73 s/day
   are the same statement.

**2.3** **v7 (PTP transparent/boundary clocks)** and **v8 (GPS/PPS/PHC)**.

What they have in common: both **obtain information the round trip cannot
provide**. A transparent clock supplies the missing second equation by reporting
its own residence time; GPS supplies it by geometry — the receiver knows where
the satellite is, so it can *compute* the propagation delay instead of halving a
round trip. Every other refinement takes the same information and processes it
better; these two change what information exists.

**2.4** **v4 — slew instead of step.** It makes the clock *less* accurate in the
short term, because the correction is applied gradually instead of instantly.
It is in the list because accuracy is not the only property a clock has: v4 buys
**monotonicity**, without which every duration measured on the machine is
unreliable. It is the refinement whose absence has caused the most production
outages, and the one that improves no metric anybody puts on a dashboard.

---

## Level 3 — Predict

**3.1**
```
δ = (T4 − T1) − (T3 − T2) = (12.000 − 0) − (250.900 − 250.400) = 12.000 − 0.500 = 11.500 ms
θ = ((T2 − T1) + (T3 − T4)) / 2 = (250.400 + 238.900) / 2 = 244.650 ms
honest interval = [T2 − δ, T2] = [238.900, 250.400] ms      (ε = 5.750 ms)
```
The sentence Alba is entitled to say:

> "Bruno's clock is ahead of mine by somewhere between 238.9 ms and 250.4 ms. My
> single best guess is 244.65 ms, and nothing in this exchange can narrow it
> further."

Note that the interval is exactly `[T2 − δ, T2]`: at one extreme all the delay
was outbound, at the other all of it was on the return.

**3.2**
(a) 15 ppm × 1024 s = **15.36 ms**.
(b) 15 ppm × 64 s = **0.96 ms** — sixteen times tighter.
(c) The long interval saved fewer than **10 KB per host per hour** of network
traffic (an NTP exchange is roughly 90 bytes each way). It bought that with
**15 ms of uncertainty** on every timestamp the machine produces. If anything
downstream consumes ε — a commit wait, an uncertainty restart, a decision about
whether two events can be ordered — the operator traded a rounding error in
bandwidth for a first-order cost in latency and resolution. Poll interval is not
a bandwidth setting; it is an accuracy setting that happens to consume
bandwidth.

**3.3** With ε = 12 ms on each machine, two clocks can differ by up to **24 ms**
(ε + ε) while both remain within their bounds.

- **3 ms apart:** you can conclude **nothing**. Either order is consistent with
  both clocks being correct. Ordering these two events is a coin flip that
  produces a confident-looking answer.
- **40 ms apart:** 40 > 24, so the order is real — *provided both bounds
  actually hold*, which is a conditional worth saying out loud, because it fails
  exactly in the cases (§12.2, §12.3) where you most want the answer.

The general rule: **you can only order events separated by more than the sum of
the two machines' error bounds.**

**3.4** 4.32 s ÷ (1/12) = **51.84 seconds** minimum, and in practice longer,
because `corrtimeratio 3` makes chronyd prefer slower corrections.

During that period the clock is running at up to 1/12 off nominal rate — so
**every duration the machine measures is wrong by up to 8.3%**, and it is wrong
in a way no error will reveal. Note the trade this makes explicit: the clock is
never discontinuous (good), but it is knowingly running at the wrong *rate* for
almost a minute (bad). Neither choice is free, which is why distributions ship
`makestep 1.0 3` — step once at boot, slew forever after.

**3.5** The two questions have **opposite answers**, which is the point.

- **ε goes up.** If you average *n* estimates each bounded by δᵢ/2, the tightest
  bound you can honestly claim on the average is mean(δᵢ)/2 — and that is always
  ≥ min(δᵢ)/2. Averaging can only *loosen* the provable bound. (Panel 1's
  sampling table shows this on every draw.)
- **The actual error may well go down**, if the delay split varies randomly
  between samples, because random errors partly cancel.

So averaging buys you a typically-better answer and a definitely-worse
*guarantee*. NTP chooses the guarantee, and it is right to: the actual error is
something you got lucky about and cannot publish, whereas the bound is something
you can put in a contract. And note the case that destroys the consolation
prize entirely — a **fixed** path asymmetry (§12.2) — where averaging improves
neither the bound nor the error.

---

## Level 4 — Modify

**4.1**
(a) 5 s × 200 µs/s = 1 ms of drift growth. The sawtooth becomes **1 ms → 2 ms**
(the 1 ms floor is the communication delay to the time masters, which the poll
rate does not touch).
(b) The paper's ε averages ~4 ms, so commit wait ≈ 8 ms. The new ε averages
~1.5 ms, so commit wait ≈ 3 ms. **About 5 ms saved on every write**, fleet-wide.
(c) Two costs, and a third worth noticing:
   - **6× the load on the time-master tier**, which must be sized for the whole
     fleet's poll rate. Time masters are special hardware; this is a capital cost.
   - **6× the polling traffic**, on every machine, forever.
   - **Diminishing returns.** The 1 ms communication floor now dominates the
     sawtooth. Halving the interval again to 2.5 s takes the peak from 2 ms to
     1.5 ms — a quarter of the benefit for the same doubling of cost. The next
     real improvement has to attack the floor (§9.6–§9.8), not the poll rate.
(d) Panel 2 → drift 200 ppm, poll interval to its lowest, network uncertainty
1 ms, strategy "slew + correct the frequency". The steady-state ε readout is the
answer.

**4.2** **The bound stops being a bound.**

ε is only meaningful as a *promise*, and the promise is conditional: it holds
while the oscillator drifts no faster than the assumed worst case. Measuring
your crystals today tells you what they do today, in this rack, at this
temperature, at this age. It does not tell you what a machine in a hot aisle does
in eighteen months, or what a badly virtualised timer does under a noisy
neighbour, or what a part does as it degrades toward failure.

**When would you find out?** Not from the clock system — every indicator stays
green, because nothing in the daemon is checking the assumption. You would find
out from a **downstream correctness violation**: a read that returned stale data
when the protocol said it could not, a commit that appeared out of order. That is
the worst possible detector — indirect, rare, non-reproducible, and it looks
exactly like an application bug.

This is why Google assumes 200 µs/s when its crystals do far better (§9.5), and
why CockroachDB makes a node **kill itself** rather than continue outside its
max-offset window. The gap between typical and guaranteed *is* the safety margin.

**4.3** `makestep 0.001 -1` means: step whenever the offset exceeds 1 ms, with no
limit on how many times.

A healthy host's offset crosses 1 ms constantly — that is just the normal
sawtooth between polls. So the fleet now **steps continuously**, many times an
hour, in both directions, on every host.

They have enabled: **every wall-clock duration on every host is now unreliable,
all the time.** Negative durations (the Cloudflare failure mode) stop being a
once-a-decade leap-second event and become a routine occurrence. Mass
simultaneous expiries (§12.1) happen hourly.

And it does not even achieve its stated goal. Stepping on each measurement
**chases the noise** instead of filtering it — the daemon's clock filter and
frequency discipline (§9.2, §9.5) exist precisely to avoid reacting to individual
samples, and constant stepping perturbs the discipline loop so it never settles.
They will end up with a *less* accurate clock that also moves backwards.

**4.4** From the public NTP pool (ε ≈ 18.2 ms):
- terms are system time 0.51 ms, ½ root delay **15.62 ms**, dispersion 2.10 ms.
- **Single largest win: move to a nearby source.** Root delay 31 ms → 0.11 ms
  takes the dominant term from 15.62 ms to 0.056 ms.
- **Second: the dispersion base** (2.10 ms), which largely falls out for free
  with the same change, because a nearby well-disciplined source has a much
  smaller accumulated error to report.

Once on the cloud-local deployment (ε ≈ 86 µs): terms are 8.3 µs / **56 µs** /
22 µs. The dominant term is *still* half the root delay — so the answer's
**identity** is unchanged, but the **remedy** is completely different. You can no
longer fix it by moving the server closer; you fix it by changing where the
timestamp is taken (§9.6 hardware timestamping), then by making the switches
report residence time (§9.7), then by removing the network (§9.8). That shift —
same diagnosis, different cure at a different scale — is the useful thing to take
from this exercise.

---

## Level 5 — Trace

**5.1**

| Quantity | Value | Working |
|---|---|---|
| out | 9.2 ms | given |
| back | **2.3 ms** | 11.5 − 9.2 |
| Bruno's true offset | **241.2 ms** | T2 − out = 250.4 − 9.2 |
| Alba's estimate θ | **244.65 ms** | from 3.1 |
| Alba's error | **+3.45 ms** | 244.65 − 241.2 |
| Interval reported | **[238.9, 250.4] ms** | [T2 − δ, T2] |
| Truth inside it? | **yes** | 238.9 ≤ 241.2 ≤ 250.4 |

Identity check: `(out − back)/2 = (9.2 − 2.3)/2 = 3.45` ✓

Why it had to hold:
```
θ         = T2 − δ/2                    (the estimate)
truth     = T2 − out                    (the definition of the offset)
θ − truth = out − δ/2
          = out − (out + back)/2
          = (out − back)/2
```
The error is *exactly* half the asymmetry. When the path is symmetric it is zero;
when all the delay is on one leg it reaches ±δ/2, which is why ε = δ/2 is the
tightest honest bound and not a conservative one.

**5.2** Drift 50 ppm = 0.05 ms/s; poll 32 s ⇒ 1.6 ms accumulates per interval at
full drift.

**(a) Step only — the treadmill.**

| Cycle | freq correction | error at end of cycle |
|---|---|---|
| 1 | 0 | 1.60 ms |
| 2 | 0 | 1.60 ms |
| 3 | 0 | 1.60 ms |

It never improves. Every poll returns the clock to zero and nothing has told the
kernel that the crystal is fast, so the next interval accumulates exactly the
same 1.6 ms. **This is §9.5's whole argument in three rows.**

**(b) Slew + frequency correction, gain 0.6.**

Residual drift after *n* corrections is `50 × 0.4ⁿ` ppm; error in the following
interval is `1.6 × 0.4ⁿ` ms.

| After poll *n* | freq correction | residual drift | error in next interval |
|---|---|---|---|
| 0 (start) | 0 ppm | 50.0 ppm | **1.600 ms** |
| 1 | 30.0 ppm | 20.0 ppm | **0.640 ms** |
| 2 | 42.0 ppm | 8.0 ppm | **0.256 ms** |
| 3 | 46.8 ppm | 3.2 ppm | **0.102 ms** |
| 4 | 48.7 ppm | 1.28 ppm | **0.041 ms** |

**Under 100 µs from the fifth interval onward — about 128 seconds in, after four
corrections.** A 39× improvement over strategy (a), achieved with a few bytes of
persisted state and no change to any hardware. This is why the drift file exists,
and why a freshly booted machine without one has a measurably worse clock for the
first few minutes.

**5.3**

Check which intervals share a common point:
```
S1 [ 42,  50]  ─────────
S2 [ 47,  55]        ─────────
S3 [ 44,  49]     ──────
S4 [ 46,  61]       ──────────────
S5 [138, 146]                                    ─────────
                     ^^^^
                   [47, 49]
```
S1, S2, S3 and S4 all contain every point in **[47, 49]**. S5 shares no point
with any of them.

- **Survivors:** S1, S2, S3, S4. **S5 is the falseticker**, discarded.
- **Resulting interval: [47, 49] ms**, so **ε = 1 ms**.
- **Best single source** was S3 at [44, 49] — 5 ms wide, ε = 2.5 ms.

**Why the comparison is the interesting part.** The intersection is *narrower
than any individual source*. Combining honest sources is not merely a liar-
detection mechanism that costs you nothing; it is an **ε-reduction mechanism**
that leaves you more certain than your single best source could make you. That is
the second, less-quoted reason the conventional advice is "configure at least
four servers" — and it is the argument against exercise 6.1's single excellent
appliance.

---

## Level 6 — Spot the flaw

**6.1 — the single excellent source.**

*Smallest failing scenario:* the appliance's GPS antenna cable is disconnected
during rack work. The receiver enters holdover, keeps serving time from its
internal oscillator, keeps advertising stratum 1, and keeps reporting a small
dispersion. It drifts. Every host follows it precisely and confidently.

*Guarantee violated:* the ε bound itself — the true error leaves the interval,
and **no observation available to any host reveals it**, because they all agree
with each other perfectly. (This is §6's structure a second time: identical
evidence, different worlds.)

*The correct reading:* quality of a source and *independence* of sources are
different purchases. Four mediocre servers can detect a liar; one excellent
server cannot. Note also 5.3's result — four sources would have given a *tighter*
ε than the single appliance, not merely a safer one. Google's Armageddon masters
run on atomic clocks rather than GPS specifically so that a GPS-correlated
failure — jamming, spoofing, a week-number rollover bug — cannot make every
source lie in the same direction at once.

**6.2 — the monitoring agent.**

*Smallest failing scenario:* the monitoring server's own clock drifts by 500 ms.
Every host in the fleet alerts simultaneously. The on-call engineer sees a
fleet-wide clock event and starts investigating the fleet — which is fine.

*The worse scenario:* the monitoring server takes time from the **same upstream**
as the fleet, and that upstream becomes a falseticker. Everything drifts
together, the differences stay under 100 ms, and **nothing alerts at all** while
the entire fleet is wrong.

*A third flaw:* the reported difference includes the heartbeat's network delay,
which is unbounded and asymmetric. A congested link is indistinguishable from a
clock problem.

*Guarantee violated:* none — it never had one. This metric is a **comparison of
two clocks**, which §12.6's rule says can never be a correctness signal.

*The fix:* alert on each host's **self-reported error bound** (root distance from
`chronyc tracking`). That is a local, absolute quantity that requires no
comparison, includes the network term honestly, and grows on its own when a host
loses contact with its sources.

**6.3 — the distributed rate limiter.**

*Smallest failing scenario:* limit is 100 requests/second. Host A's clock is
5 ms fast, host B's is 5 ms slow. At true time 10:00:00.999, A computes bucket
`10:00:01` while B is still computing bucket `10:00:00`. For the 10 ms window
around each second boundary, **two buckets are simultaneously live across the
fleet**, so up to 200 requests can be admitted within one real second.

Symmetrically, at the far edge of the window the effective second is shortened
and the limiter under-admits.

*Guarantee violated:* the rate limit — the one property the component exists to
provide. And note the shape of the failure: it is worst exactly at the boundary,
which is where bursty traffic tends to land.

*The fix:* there must be **exactly one clock** in the decision. Have the store
assign the bucket (Redis `TIME`, or a Lua script using server time), or use a
token bucket whose refill is computed from server-side timestamps. The
`distributed-rate-limiting` lab's designs all have this property; this exercise
is what happens when you lose it.

**6.4 — measuring one-way delay directly.**

It is circular. The receiver computes `receive_time − send_time`, but those two
readings are on **different clocks**, so the result is:

```
observed = one-way delay + clock offset
```

One equation, two unknowns — the *same* unknown pair §6 started with. To extract
the delay you would need the offset, which is what you were trying to compute
from the delay.

It becomes possible only if you already have synchronised clocks (circular), or
if something outside the round trip supplies the missing equation: PTP's
transparent clocks reporting residence time, or a hardware clock on the machine
with no network in between. This is exactly why AWS lists *"measure one-way
network latency"* as a **benefit** of the PTP Hardware Clock and hardware packet
timestamping, rather than as something you can do with plain NTP.

**6.5 — timestamps as the log's ordering key.**

*Smallest failing scenario:* ε is 12 ms. Service A emits `order-created` at its
10:00:00.100; service B, having received the order, emits `order-paid` at its
10:00:00.090. B's event sorts **before** its own cause. The UUID tiebreak does
not fire (the timestamps differ), so the system produces a stable, deterministic,
**wrong** order — and reproduces it identically on every replay, which is what
makes it so hard to diagnose.

*Counterfactual — if wall-clock timestamps were unique and monotonic per
machine:* **the design is still broken.** Per-machine monotonicity guarantees
that A's own events are correctly ordered among themselves, and says nothing at
all about A's events relative to B's. Machine A's 10:00:00.100 can still be
genuinely later than machine B's 10:00:00.050.

*What is missing:* a **causal** link — an ordering derived from the messages that
actually passed between the services, not from two independent readings of two
independent rocks. That is Lamport's clock, a vector clock, or an HLC. **This is
the exact gap HLC was invented to close**, and it is the reason the
`hybrid-logical-clocks` lab exists: keep a stamp that reads like a wall-clock
time so "as of 5 p.m." still works, while guaranteeing that a cause always stamps
below its effect.

---

## Level 7 — Handle failure

**7.1 — a lease design safe at any ε.**

The honest starting point: **you cannot make lease *expiry* safe when ε is
unknown.** The exercise's real answer is to stop asking expiry to carry the
safety property.

*The design:*

1. The lock service (backed by something linearizable — Raft, ZooKeeper, etcd)
   issues, with every lock grant, a **monotonically increasing fencing token**.
2. Every write to the protected resource carries its token.
3. **The resource** — the storage layer, the database, the file service —
   remembers the highest token it has accepted and **rejects any write carrying a
   lower one**.

*Which component enforces safety:* the **resource**, not the lock holder and not
the lock service. This is the crucial relocation. A paused, slow, or
wrong-clocked holder cannot know it has lost the lock — that is the whole problem
— so the check must live somewhere that *does* know, and the only place that
knows is the thing being written to.

*What the lease duration is now for:* **liveness, not safety.** It determines how
long the system waits before handing the lock to somebody else — i.e. how long an
outage lasts when a holder dies. Getting it wrong now costs availability, which
is a tuning problem, rather than correctness, which is a bug. That is exactly the
§12.6 split: physical time for timeouts and heuristics, logical mechanisms for
safety.

*What to say if the resource cannot check tokens* (a plain S3 bucket, a
third-party API): then you do not have a safe design, and you should say so
rather than tune the lease. The remaining options are to partition the work so
that only one writer can ever touch a given key, to use a compare-and-set on a
version the resource does support, or to accept and document the risk. Shortening
the lease is not on the list — it reduces the *window*, not the *possibility*,
and it makes spurious lock loss more frequent.

**7.2 — the mixed smeared/unsmeared fleet.**

*Now (six months out):*
- **Audit which hosts take time from which sources.** The mixed configuration is
  the actual defect; find every instance of it.
- **Pick one regime fleet-wide.** Either everything follows the smeared NTP
  endpoint, or everything follows the unsmeared PHC and the software is verified
  to tolerate a repeated second. Do not run both. AWS says this plainly: because
  smeared and non-smeared time differ during the event, do not configure both.
- **Verify `tzdata` / the leap-second table is current** on every host *and
  inside every container image* — a stale image is a host that does not know the
  leap is coming.
- **Confirm stepping is off** (or bounded to the first few updates after boot),
  so that whatever happens, nothing jumps.
- **Audit code for wall-clock durations.** Every `currentTimeMillis()`
  subtraction is a latent Cloudflare. This is the item with the longest lead
  time, which is why it starts now.

*On the day:*
- Freeze deploys across the window.
- Watch `Leap status`, per-host offset, source reachability, and — the one people
  forget — **a counter of negative durations** anywhere in the fleet.
- Do not run reconciliation, ordering-sensitive batch jobs, or anything that
  compares timestamps across services.

*What to tell dependent teams:*
> "For 24 hours starting at midnight UTC, our clocks are *deliberately* wrong by
> up to one second, and that is the correct behaviour. Any design assuming
> millisecond-scale skew is suspended for that day. Nothing will alert, because
> nothing is broken."

*If the configuration cannot be changed in time:* remove **one** of the two
sources from clients — either one. The mixed configuration is strictly worse than
either regime alone, because it defeats the intersection algorithm (§9.3): the
client sees two mutually inconsistent sources and no majority to break the tie.
And disable stepping, so that whatever disagreement remains is absorbed as a rate
error rather than a jump.

**7.3 — the 4.3-second p99.**

*What is happening:* this is not latency. `request_sent_at` is read from the
client's clock and `request_received_at` from the server's, so the difference is

```
observed = true latency + (server clock − client clock)
```

and the second term is the fleet's skew (§2). A 5 ms hop between two machines
4.3 s apart reports as 4.3 s. Nobody can find the slow path because there is no
slow path.

*The one measurement that confirms it:* the **falsifying** one. A true latency is
positive in **both** directions; a clock skew is positive one way and negative the
other. So measure the same pair of services in reverse — client → server and
server → client — using the same cross-clock subtraction. If one direction shows
+4.3 s and the other shows −4.3 s, it is skew, conclusively. (The weaker
confirmation is to measure the round trip entirely on the client's own monotonic
clock: one clock, no skew, and if that shows 5 ms you are done.)

*What the dashboard should be built from:* durations must be measured **on one
machine, on its monotonic clock**. That means client-side round-trip time, or
server-side processing time, or a proper distributed trace in which each span's
*duration* comes from a single machine's monotonic clock and only the span
*nesting* is cross-machine. Cross-clock subtraction is never a latency
measurement; at best it is a skew measurement wearing a latency label — which,
incidentally, makes it a perfectly good **clock-skew monitor** if you relabel the
axis.

---

## Level 8 — Design

**8.1 — 100 µs to UTC, three datacenters plus a colo, provable.**

*What survives from the lab's model:* everything in §6 — ε is still δ/2 plus
dispersion, and the interval is still the honest output. All of §9's ladder still
describes your options.

*What changes:* the requirement is **accuracy**, not merely precision (§3). It is
not enough for your machines to agree with each other; they must agree with UTC,
and that means a traceable link to a national standard. Internal consistency, the
thing most distributed systems actually need, is not the deliverable here.

*Which refinements you need:*
- **§9.8 GPS + PPS at each site** — mandatory. 100 µs is not reachable over any
  wide-area network path, so each site needs its own reference. Two independent
  references per site (GPS plus a rubidium or caesium holdover oscillator), so a
  constellation-level failure degrades to holdover instead of to drift.
- **§9.7 PTP with boundary or transparent clocks** inside each site — mandatory,
  because the last hops must not reintroduce δ/2 at millisecond scale.
- **§9.6 hardware timestamping** — mandatory, and it is what makes §9.7 worth
  anything.
- **§9.3 multiple sources / intersection** — needed, but for a different reason
  than usual: not only falseticker rejection but the *independence* the regulator
  will ask about.
- **§9.2, §9.5** — still running underneath, unremarkable.
- **What you do not need:** the public NTP pool, anywhere, for anything.

*What "prove it" adds beyond "achieve it":*
- **You must record ε continuously**, not just achieve it. The error bound
  becomes a logged, retained compliance artifact — ClockBound-style interval
  logging is now the deliverable, and this is the requirement most teams miss
  because it is invisible until an audit.
- **Traceability**: a documented chain from your clock to a national standard,
  including the holdover specification — how long, and how badly, you degrade
  when GPS is lost.
- **Evidence of the failure modes**, not just the happy path: what the record
  looks like during a holdover event.

*The hard part is the colo.* You do not own those switches, so PTP transparent
clocks may simply be unavailable, and the answer becomes a GPS antenna in your
own cage — which is a contractual and physical problem (roof access, cable runs,
lightning protection) long before it is an engineering one. Budget for it as a
lease negotiation, not a sprint.

**8.2 — external consistency on stock VMs with ε = 18 ms.**

| Option | Mechanism | Cost | Honest assessment |
|---|---|---|---|
| **1. Commit wait as-is** | Stamp with `latest`, hold the ack until `earliest` has passed | **~36 ms added to every write** | Correct, no clock spend, and usually unacceptable |
| **2. Shrink ε first, then commit wait** | Move to the platform's local time service (ε → ~86 µs) or a PHC (→ ~40 µs) | A config change, or an instance-type constraint | Commit wait becomes ~80 µs. **Usually the right answer if the platform offers it** |
| **3. Don't use clocks at all** | Route the ordering decision through a single sequencer or timestamp oracle (Percolator/TiDB's PD) | One coordination hop; a scaling bottleneck to manage | Zero clock dependence. Correct even at ε = 3 s |
| **4. Causal tokens (HLC)** | Client carries a token from its write; reads wait for it | Almost nothing | **Partial — and you must say so.** Covers a client that can see its own write; does *not* cover a stranger who learned of the write by some other path, which is precisely what external consistency means |

*Choice:* **(2), falling back to (3).** If the platform has a local time service,
option 2 turns a 36 ms tax into a rounding error and the design stays
decentralised — this is the single biggest practical change between 2012 and now,
and it is why Spanner's design stopped being exotic. If it does not, take (3):
one coordination hop is almost always cheaper than 36 ms on every write, and it
degrades predictably.

*Option 4 is included because it is what teams reach for first*, and the
important skill is being able to say exactly which guarantee it does and does not
provide. `hybrid-logical-clocks` measured that gap directly: **86 of 86 reads
stale** for a fast-clock writer and a slow-clock reader with no causal path.

**8.3 — Counterfactual: a perfectly symmetric network.**

(a) **ε does not become zero.** The *measurement* term collapses — δ/2 becomes
the exact one-way delay rather than a midpoint — but ε still contains the
dispersion term (drift since the last poll), the resolution of the timestamping
itself, and the reference clock's own uncertainty. ε becomes **the dispersion
term alone**, which with frequent polling and hardware timestamping could be tens
of microseconds. Smaller by orders of magnitude; still not zero, and still
something the API must return.

(b) **Unnecessary: v7 (transparent/boundary clocks)** — its entire job was
recovering the delay split, which the fabric now guarantees. **v8's motivation
weakens but does not vanish**: a symmetric network tells you the *delay*, not the
*time*, so you still need a reference clock somewhere, and still need it to be
traceable to UTC. **v1, v3, v4, v5, v6 all survive unchanged** — you still need
round trips, falseticker rejection, monotonicity, frequency correction, and
low-jitter timestamps. **v2's min-filter matters less** for the bound and still
helps with jitter.

(c) Commit wait = 2ε where ε is now the dispersion term — tens of microseconds
rather than milliseconds. Notably, this is roughly what a PTP Hardware Clock
already achieves today *without* changing the network, which tells you something
about which rung of the ladder was actually load-bearing.

(d) **What is still true — the interesting half of the answer:**
- The **interval API** argument is unchanged. Dispersion is still real, so a
  scalar `now()` still lies; TrueTime, ClockBound and fbclock would still have
  had to be invented.
- **Falsetickers** still exist. A symmetric network does not make sources honest.
- **Steps still break durations**; monotonic vs wall clock is untouched.
- **Leap seconds** are unaffected — they are a definitional problem, not a
  network one.
- §12.6's **lease problem** is smaller in magnitude and identical in kind: a
  paused process still does not know it was paused.
- And the deepest one: **physical time still cannot express causality.** Every
  reason `hybrid-logical-clocks` exists survives this counterfactual completely
  intact.

The lesson: the network asymmetry is the most *famous* obstacle, and it is not
the most *important* one. Most of this lab is not about the network at all.

**8.4 — auditing "reliable to within 10 ms".**

*What to check first — and why the obvious first check is wrong.*

The obvious first check is "is the NTP daemon synchronised?" — `ntpstat`,
`chronyc tracking | grep Leap`, "are all hosts green?". It is the wrong first
check because **it returns green on both of §10.2's hosts**, one of which has
ε = 86 µs and one of which has ε = 18.2 ms. It cannot distinguish a fleet that
satisfies the design document from one that violates it by a factor of two. A
check that cannot fail in the interesting case is not a check.

*The right first measurement:* collect the three terms — `System time`,
`Root delay`, `Root dispersion` — from **every host**, and compute ε per host.
Then look at the **distribution, not the mean**. The design document's claim is a
statement about the worst host, not the median one.

*Then, in order:*
1. **The tail.** The p99.9 host, and specifically hosts in a different region,
   different provider, or different network segment. Fleet-wide averages hide
   exactly the hosts that will break you.
2. **Source diversity.** How many hosts share a single upstream? A common
   upstream is a correlated failure: 6.1's scenario applied to the whole fleet at
   once, and it will not show up in any per-host metric.
3. **Asymmetry.** Compare the offset between two sites *measured from each side*.
   A fixed, opposite-signed disagreement is §12.2, and it is the one condition
   that no health indicator will ever show you.
4. **What consumes the assumption.** Grep for cross-machine timestamp comparison
   — ordering, lease expiry checks, rate-limit buckets, dedup windows. This is
   the list that determines whether the answer matters.

*If the sentence is true* (ε comfortably under 5 ms fleet-wide including the
tail): write the assumption down *as a measured number with a date*, add the
alert from §15 so it stays true, and note explicitly that it is conditional on
the monitoring existing. An unmonitored true assumption becomes a false one
silently.

*If the sentence is false:* do not open with "the clocks are wrong". Open with
the **list from step 4** — the designs that actually depend on it — because that
is the list that carries the cost. Then, for each, choose one of three: shrink ε
(often a one-line config change to a local time service — start here), widen the
design's margin, or replace the timestamp comparison with a fencing token or a
sequencer.

*The deliverable is a measured number and a list of dependent designs* — not a
config change. The config change is the easy part, and it is not what the week
was for.
