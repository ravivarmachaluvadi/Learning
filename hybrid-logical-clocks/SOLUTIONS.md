# Solutions — hybrid logical clocks

## Rung 1 — Explain

**1.1** Way one (needs skew): machine B's clock is behind machine A's, so an
event on B *caused by* an event on A gets a smaller timestamp — sort the logs
and the effect precedes its cause (measured: 65 such inversions per run at
skew 8, 341 at 20). The support engineer traces a request "backwards through
time" and concludes the impossible. Way two (needs no skew at all): a clock
has finite resolution, so two related events inside one tick get the *same*
stamp — 79 unorderable pairs per run at skew zero. Fifty-millisecond NTP
accuracy fixes neither: the first needs zero error, the second needs infinite
resolution.

**1.2** `l` may contain only a value some physical clock actually displayed —
yours, or one carried in on a message. It may never contain an *arithmetic
invention* (no `+1`). Since `l` is the max over a set of genuine readings
taken "now-ish" on your causal path, it can exceed your own reading by at
most the gap between the fastest and slowest clock — the spread. The moment
you allow one `+1` (the naive hybrid), `l` stops being a reading and the
bound is gone (118→898 in experiment 3).

## Rung 2 — Identify

**2.1** The message's `l` won: `l' = max(own 0, msg 16, wall 8) = 16`. The
value 16 was physically produced by *Asha's* wall clock (running +5 at true
tick 11) — Ben is now keeping time with a reading someone else's clock took,
which is exactly what "freshest reading on the causal path" means.

**2.2** `tick 11: wall reads 16 > l. l advances, and c owes nothing: 16.`
Asha's own wall clock overtook her `l` (15) — when the wall part genuinely
moves, the counter resets, which is the rule that keeps `c` tiny (maxC 31
over 2,629 events) and the 16-bit budget effectively infinite.

**2.3** `Fuzz.build` pins node 0 to `+skew` and node 1 to `-skew`, so the
spread is exactly `2*skew`. The drift maximum is achieved when the slow node
absorbs the fast node's freshest reading — but a message takes ≥1 tick to
arrive, during which the slow node's own `pt` advanced by 1. Hence
`spread − 1` in every row: the measurement is tight to the mechanism, not
noisy.

## Rung 3 — Predict

**3.1** Three-way tie at `l = 50`: `c' = max(own 2, msg 7) + 1 = 8` → stamp
**(50, 8)**. Anything that only beats your own counter — `(50, 3)` — stamps
the receive *below* its own send `(50, 7)`: causality inverted.

**3.2** NAIVE roughly doubles (470 → 898): its drift is accumulated `+1`s, so
it scales with total events, i.e. with run length. HLC stays exactly 15: its
drift is the clock spread, which time does not change. The syntactic
difference: `max(l + 1, pt)` versus `max(l, pt)` — the `+1` is on the
wall-valued number in one design and banished to a separate field in the
other.

**3.3** The harness pins the writer to the `+skew` node and the reader to the
`-skew` node, and the read follows the write within a tick — so the reader's
"now" is *always* about `2*skew` behind the stamp the writer just issued.
Staleness isn't a race you sometimes lose; it is arithmetic. The real-world
shape: a client whose writes go to one region (or primary) and whose reads
go to a follower in another — precisely the MongoDB topology that made them
ship clusterTime/afterClusterTime on by default.

## Rung 4 — Modify

**4.1** Measured: **3 tests fail** — the two rule tests
(`local_wall_advanced_resets_c`, `local_wall_stuck_increments_c`) and
`hlc_maxC_small_relative_to_events` (maxC 31 → 81 in this small run; the
receive branches still reset, which is why it isn't worse). Crucially,
**order, drift, and cut tests all still pass** — the bug is invisible to
every correctness metric. The consumed resource is the 16-bit counter budget
in the packed form: c now grows monotonically with events instead of
resetting thousands of times a second, and at 1M causally-chained events/sec,
65,536 values last **~65 milliseconds**. A bug with zero anomalies and a
hard crash on a timer — the reset-on-advance rule is what makes 16 bits "a
fortune".

**4.2** Random offsets in `[-8, +8]` usually span less than the full ±8, so
WALL's inversions shrink and HLC's measured drift lands below `spread − 1` —
every exhibit gets *softer* while remaining true. The echo: the
linearizability lab's calm-network row and causal-consistency's
no-injected-asymmetry lesson, third appearance — **a test that doesn't force
the adversarial configuration understates the failure**; pinning the
extremes is the harness making the adversary real.

**4.3** Nothing gained, nothing broken — and you can prove it without
running: after every event, the sender's own rule enforced `l ≥ pt`, so the
stamp's `m.l` already dominates any `m.pt` you could ship alongside it.
Absorbing `m.pt` adds a value that is `≤` one already in the max. (Shipping
`pt` *instead of* `l` would be the recvIgnoresPt bug with extra steps.)

## Rung 5 — Implement

```java
long lNew = Math.max(l, Math.max(ml, pt));
if (lNew == l && lNew == ml) c = Math.max(c, mc) + 1;
else if (lNew == l)          c = c + 1;
else if (lNew == ml)         c = mc + 1;
else                         c = 0;          // own wall won outright
l = lNew;
```

Verified 6/6 against the harness. The hints decoded: reset only when `l`
genuinely moved past *everything* it must dominate (the final branch — your
wall reading alone won); when `l` ties with the message you must beat the
*message's* counter, when it ties with both you must beat *both* — cases 3
vs 4 are exactly those two ties.

## Rung 6 — Debug

| bug | guarantee broken | caught by |
|---|---|---|
| `recvIgnoresMsgC` | **order** — 8 real inversions (receive below its own send on l-ties) | any hb fuzz; test `hlc_zero_inversions_zero_ties` |
| `recvIgnoresPt` | **bound (floor)** — minDrift −14: l falls behind your own wall, "as of 5 p.m." misses local 5 p.m. events | test `hlc_l_never_below_own_wall_reading` |
| `compareIgnoresC` | **order (ties)** — 124 unorderable hb pairs; everything one wall tick apart collapses | tie counting; cut checks at `(T, c)` boundaries |
| `neverResetC` | **packing budget** — maxC 31 → 718; 16 bits die in ms at production rates | maxC bound test; overflow test at pack time |

The no-anomaly one is `neverResetC` (rung 4.1's measurement): zero
inversions, zero drift, zero torn cuts — and a guaranteed hard failure at
`pack()` time on a schedule set by your traffic. Production incidents don't
require wrong answers; "correct until the counter wraps at peak load" is a
worse class of bug than one a fuzz can see.

## Rung 7 — Handle failure

**7.1** (a) Correctness of stamps: intact — the rules never depended on the
clock being right, and its first received message hands it a fresh `l`.
(b) Drift: its own *view* is fine (`l − pt` on this node is large but that
is the metric working: 40s slow clock → l runs ~40s "ahead" of its wall —
a dashboard's downstream-of-a-liar signature in reverse). The cluster-wide
bound is violated in the declared sense: this node is outside max-offset.
(c) Local "as of now" queries: its `pt`-based notion of *now* is 40s in the
past — it will serve snapshots that silently exclude 40 seconds of
perfectly-visible history: stale reads with no error. At rejoin, before
serving: re-sync NTP and *verify* offset within bound (or refuse — the
CockroachDB suicide, applied at startup), and wait out max-offset so no
stamp it issues can precede one it issued before the partition —
CockroachDB literally does this sleep on restart.

**7.2** The token enters the receive rule: `l' = max(l, HUGE, pt) = HUGE` —
the first server's clock jumps ~146,000 years ahead; every message it sends
carries HUGE onward; `max` means **no node ever recovers** (l never
decreases). All ordering still "works", but every wall-time promise —
snapshots, TTLs, GC-by-age, uncertainty windows — is destroyed cluster-wide,
permanently, from one packet. That is experiment 7 (the +60 liar) with the
offset chosen by an attacker. Defenses: (1) **authenticate the token** —
MongoDB HMAC-signs clusterTime so only servers can advance it; (2) **bound
the jump** — refuse/clamp any incoming stamp more than max-offset ahead of
local `pt` (a liar can then only push you to the edge of the already-
tolerated spread). Belt and suspenders; production uses both.

## Rung 8 — Design (worked discussion, not a single right answer)

**Clock**: HLC per region-node, 48+16 packed, stamps on every audit entry;
tokens (the entry's stamp) returned to writers and piggybacked on
inter-region replication messages, MongoDB-style, so the regions' clocks
chase each other continuously.

**Requirement mapping.** *Causally-consistent replay*: HLC order is exactly
this — sort a tenant's entries by `(l, c, regionId)`; the one-way property
is fine because replay needs *an* order consistent with causality, not
concurrency detection. *"As of <wall time>"*: the consistent-cut property —
cut at `(T, ∞)`; correct within the clock spread, so publish the caveat "±
clock offset" with the answer, and alarm when NTP degrades. *Dashboard
read-your-writes*: the causality token — client carries its last write's
stamp; any region absorbing it serves reads at ≥ that stamp (or waits the
few ms until its clock passes it). This is the requirement that **cannot be
met by clock quality alone**: no bound short of zero makes a slow-clock
region's "now" cover a fast-clock region's fresh write; you need the token
(per-session, cheap) — or global coordination you explicitly don't want for
an audit log.

**Concurrent entries** (same tenant, two regions, no causal path): both
stamps are valid and unequal; `(l, c, regionId)` totally orders them
arbitrarily-but-consistently everywhere — acceptable for an append-only log
(no conflict to resolve, only an order to agree on). Never present that
order as "who acted first" to regulators when the gap is inside the clock
spread — within ±offset, wall order between unrelated events is fiction;
say so in the product.

**At ±25ms vs ±2s.** Ordering, replay, tokens: unaffected at any skew —
that is the whole point of the design. What degrades at ±2s: "as of <time>"
gains a ±2s ambiguity band (disclose it), and cross-region "first" claims
inside 2s become meaningless. What *breaks* if unpoliced: any component that
trusted the bound (TTL cleanup, retention). So monitor: per-node NTP offset
with a hard eviction threshold (crash beats lie), `l − pt` drift per node
(the downstream-of-a-liar detector), token-wait durations at read time
(rising = spread rising), and c-value distribution (bursts and chained load
made visible). During the ±2s incident you *widen the disclosed band and
keep running* — the availability-shaped answer an audit log wants, and the
exact opposite of commit wait, which would have every writer stall 4s. If
the product ever needs strangers'-order guarantees (billing cutoffs,
auctions), that is external consistency: buy it with commit wait or a
single sequencer for that stream alone — not by pretending the clocks are
better than they are.
