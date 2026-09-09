# Solutions — linearizability

Every number here was produced by running the code in this lab. Where something
is reasoned rather than measured, it says so.

---

## Rung 1 — Explain

**1.1** Your colleague has described *convergence*: given enough quiet time,
the replicas stop differing. That says nothing about any particular moment.
Meera was told PASS and Raghu, looking afterwards, was told "not published" —
both replicas will agree eventually, and the system still showed a later
observer an earlier world. The guarantee your colleague needs a word for is
about *now*, not about the limit.

**1.2** It means the system can be *summarised*. If every operation took effect
at one instant, then everything that ever happened can be written down as a
single list, and any question about the data has one answer. "Reads return
recent data" is weaker in two ways: *recent* has no definition, and it is a
promise about one read at a time — it never says two different readers are
looking at the same world. The instant rule does, because two operations cannot
both be "the one at that instant".

**1.3**
- **Sequential consistency** — same idea, minus the requirement that an
  operation already answered before yours began must come before yours.
- **Serializability** — orders whole *transactions* rather than single
  operations, and has no real-time requirement at all.
- **Strict serializability** — serializability with the real-time requirement
  put back; linearizability is its single-operation, single-object case.
- **Eventual consistency** — a promise about the limit with no promise at any
  moment; it cannot be violated by any finite observation, which is why it
  cannot be tested.
- **Read-your-own-writes** — a promise to one client about its own operations;
  linearizability holds *between* clients, which is the part that is hard.

**1.4** Because it makes the property **checkable against a system you did not
write and cannot see inside**, and because it is what users actually experience.
Practical consequence: you can run a black-box audit of a commercial database
and produce a defensible bug report — which is exactly what Jepsen does. A
second consequence, less obvious: *your* code is part of the observation.
Putting a cache in your client turns a linearizable store into a
non-linearizable system, and no change was made to the store.

---

## Rung 2 — Identify

**2.1**
- **Concurrent pairs:** `put` with `get()→0`, `put` with `get()→1`. (`put` ends
  at 20; C4's read begins at 26, so those are not concurrent. The three reads
  do not overlap each other.)
- **Real-time edges:** `get()→0` before `get()→1` (10 < 14); `get()→0` before
  `get()→0`(C4); `get()→1` before C4's read (22 < 26); `put` before C4's read
  (20 < 26).
- **The put's point** must be after 10 (C2 was told 0, so the put had not
  happened) and before 14 (C3 was told 1, so it had). Anywhere in **(10, 14)**.
- **C4 is not explainable.** The put finished at 20, C4 began at 26, so the put
  must come first — and then C4 must return 1. The history is not linearizable.

**2.2** Situation D is `put(1) [4,92]`, `get()→1 [10,30]`, `get()→0 [46,66]`.
The write is C1's bar, spanning almost everything. C2's read was answered first,
at 30. The killing fact: **C3's read began at 46, sixteen ticks after C2 was
already told 1.** Once C2 has been told the new value, no later reader may be
shown the old one. To flip the verdict, drag C3's read left until it overlaps
C2's — then neither is "later" and both answers are defensible.

**2.3** In `Checker.java`:
- real-time rule: `if (o.invoke < minResp) out.add(i);` in `candidates`
- sequential specification: the `apply` method — three lines, one per operation
  kind
- discarding a timed-out operation: the `allPending` block in `search`, which
  accepts as soon as everything left is pending

**2.4** In `Sim.addQuorumPhases`:
`if (secondPhase) task.phases.add(new Phase(true, p1.decideT, w, all));`
That is the reader's write-back. With it, 0 violations in 600; without it, 36.

---

## Rung 3 — Predict

**3.1** Verified with the checker:

| | verdict | why |
|---|---|---|
| (a) | **linearizable** | the put spans both reads; its point sits between 10 and 12 |
| (b) | **neither** | one client is shown 1 and then 0 — that breaks even its own order |
| (c) | **sequentially consistent, not linearizable** | different clients, so per-client order is fine; the real-time edge (10 → 12) is what convicts it |
| (d) | **sequentially consistent, not linearizable** | `put(2)` finished at 10 and the read began at 12, so 1 is impossible; but the order `put(1), get()→1, put(2)` satisfies both clients' own order |

The pattern: (c) and (d) are the shapes that survive every test that does not
look at the clock.

**3.2** **Zero.** 0 violations in 600 runs at 0% hiccups, and 0 at 2%. A design
with no ordering guarantee whatsoever passed 600 consecutive runs.

What that means for your tests: a green consistency test on a healthy network is
close to worthless. The violation needs a write in flight *and* two reads
separated in real time *and* their quorums covering different replicas. On a
fast network the window between a write reaching its first and second replica is
a couple of milliseconds. Jepsen's entire method is "inject partitions and
pauses, *then* check", and that is why.

**3.3** Around **10,000** — not 30,000. Measured for 2/4/8 threads at 20,000
each: **20,112 of 40,000**, **21,921 of 80,000**, **21,051 of 160,000**. Note
that the survivor count barely moves as you add threads: with more contention,
more of every thread's work is overwritten. `compareAndSet` gives exactly the
expected value every time.

**3.4** Low — typically 0 to 2 broken runs out of 120 for the naive quorum at
*occasional* hiccups. Use **Find a broken run** and read the number of runs it
had to search. That number is the point of the exercise.

---

## Rung 4 — Modify

**4.1** The checks that fail are the ones whose whole content is the real-time
rule: `stale_read_is_not_linearizable`, `two_readers_disagreeing_is_not_
linearizable`, `same_answers_separated_in_time_are_illegal`, and the design
checks for `ASYNC`, `QUORUM_NAIVE` and `CACHED`.

**The system did not get worse — the judge got weaker.** Not one line of
`Sim.java` changed. You swapped the specification you are holding it to.

Both linearizability and sequential consistency are **safety** properties:
"nothing bad ever happens", violated by a finite observation. Neither says
anything about liveness. You did not trade safety for liveness; you traded a
strong safety property for a weaker one.

**4.2** Measured, 600 histories each at 15% hiccups:

| change | violations |
|---|---|
| R=2, W=2, N=3 (baseline) | **36** |
| R=2, **W=3**, N=3 — writes wait for every replica | **32** |
| **R=3**, W=2, N=3 — reads ask every replica | **0** |
| R=3, W=3, N=5 | **15** |

The counter-intuitive result is the second row: **making writes wait for
everyone barely helps.** The violation is caused by a write *in flight*, and a
slower write is in flight for longer, not less.

Making reads ask everyone does fix it, and here is why: if reader X was shown
timestamp `T`, X saw `T` on some replica `j`. Replica `j`'s timestamp never goes
down. A later reader that asks *every* replica necessarily asks `j` too, at a
later moment, so it sees at least `T`. Reads can no longer go backwards.

That is also why it is not a fix you can ship: R=N means a single unreachable
replica blocks every read. You have bought ordering with the availability that
made you use quorums in the first place. Row 4 shows the cost is not about the
numbers being "high": R=3 of N=5 is a perfectly good quorum and still leaves 15
violations, because there are still two replicas a read can miss.

The write-back gets you the same ordering at R=2 — one extra round trip instead
of all your failure tolerance.

**4.3** **332 of 600** — more than half — with a **one-tick** cache. The store
underneath is 0 of 600 at every TTL. "We only cache for a second" is not a
mitigation; a cache of any duration is a second copy that nobody is keeping in
order.

**4.4**
- The memo remembers: *"from this exact set of remaining operations, with the
  notebook holding exactly this value, I have already failed."* It cannot change
  a verdict because the future of the search depends on nothing else — the set
  of legal next operations depends only on which operations remain, and the
  outcome of each depends only on the notebook value.
- Dropping the notebook value would be **unsound**: two different orders can
  consume the same set of operations and leave different values behind, so
  "this set failed" would prune a branch that had a different, possibly
  workable, value. The error direction is a **false alarm** — the checker would
  reject histories that are in fact linearizable.
- And yet: 3,700 histories, including deliberately fully-concurrent ones, never
  triggered it. That is the lesson. A checker's soundness is an argument about
  the algorithm, not a test result — and a checker that is wrong in the
  false-alarm direction will send an engineer hunting a database bug that does
  not exist.

---

## Rung 5 — Implement

**5.1 (queue specification).** Widen the model state to a small list, then:

```java
// enqueue(v): append v, return nothing
// dequeue():  remove and return the oldest, or 0 if empty
```

The history to write:

```
C1 enqueue(1) [0,4]
C2 dequeue()→1 [6,14]
C3 dequeue()→1 [8,16]
```

Both dequeues are concurrent with each other, so real time does not order them
— but no queue containing one `1` can hand it out twice, so no order works and
the checker rejects it. The point of the exercise: **the specification is a
parameter.** Linearizability is not a property of registers; it is a property
of *any* object against *any* one-at-a-time definition of that object. Change
`apply` and you have a checker for sets, queues, locks or counters.

**5.2 (minimum overlap).** After minimising to the smallest broken subset, for
each operation try widening its interval by 1, 2, 4… ticks and re-check;
report the smallest widening that makes the history acceptable.

Why it is useful in a bug report: it converts *"this is a consistency
violation"* into *"the two reads were separated by 6 ms, and the system needed
them to overlap"*. That tells the reader whether they are looking at a genuine
protocol bug or at a measurement artefact — see 6.4.

**5.3 (explain).** Run both checkers. If linearizable fails and sequential
consistency passes, the culprit is a real-time edge; find it by scanning the
minimised subset for a pair `(a, b)` with `response(a) ≤ invocation(b)` where
extending `b`'s invocation back to before `response(a)` makes the history
acceptable. That pair is the sentence you print. If *both* checkers fail, no
real-time edge is involved — some client saw its own value change and change
back, and the message should say that instead.

---

## Rung 6 — Debug

**6.1** Caught by `buggy_checker_raises_a_false_alarm`, which is paired with
`good_checker_accepts_the_timed_out_write` so the contrast is visible. It is a
**false alarm**: the checker rejects history F, which is perfectly explainable
by "the write did happen, the client just never heard back".

False alarms are the more damaging failure for an auditing tool, and it is worth
being clear why. A missed violation leaves you where you started. A false alarm
sends an engineer to hunt a bug that does not exist in a database they did not
write — and when they eventually discover the tool was wrong, every genuine
finding it ever produced becomes arguable. **The credibility of the tool is the
product.**

**6.2** Caught by `dropping_the_writer_tiebreak_breaks_it`. Physically: two
writers query at the same moment, both get "the highest timestamp is 5", and
both write timestamp 6 — with different values. Replicas now accept whichever
arrives first, so replica A holds `(6, "x")` and replica B holds `(6, "y")`.
Neither can ever displace the other, because acceptance requires a *strictly*
higher timestamp. The register has silently become two registers.

What a user reports: "the value keeps flipping between two things". Reads
alternate depending on which replicas answered, forever, with no error anywhere
and no way for the system to notice — the two replicas both believe they are
current. This is the same failure as the previous lab's sibling conflict, except
that with last-write-wins-by-timestamp there is no sibling to surface.

**6.3** Two checks fail: `overlapping_write_read_before_and_after_is_ok` and
`same_answers_overlapping_in_time_are_legal`. The direction is a **false
alarm** — the invocation-order checker rejects histories that are genuinely
linearizable.

This is version 1 from stage 10.1, and the failure is exactly the one predicted
there: it assumes the order is *given* by when requests were sent. In scenario C
the write is sent first and takes effect last, which is completely normal and
which the invocation-order rule cannot express.

**6.4** **Whether the two timestamps came from the same clock.**

The entire case against this history is the one-tick gap: C1 was answered at 10,
C2 asked at 11. If those two numbers were recorded on two different client
machines whose clocks differ by more than one tick, the "edge" is an artefact of
the measurement, not a fact about the system.

This is why Jepsen records every operation's times on a single node, and why
`5.2`'s "how much overlap was needed" number matters: if the answer is "6 ms"
and your clients' clocks are synchronised to within 20 ms, you have not found a
bug, you have found your clock skew.

---

## Rung 7 — Handle failure

**7.1 (deposed leader).** The shape: replica 0 serves everything until `T`;
from `T`, writes go to replica 1 while replica 0 keeps answering reads from its
own state for `lag` ticks. The checker convicts it, and the minimised violation
is the same three-bar shape as Meera and Raghu — a completed write on the new
leader, and a later read from the old one returning the old value.

Adding a lease means replica 0 stops serving reads at `T` even though nothing
has told it to, because its grant has expired and it has not been renewed. The
violations go to zero.

**What the lease cost you, and it is three things.**
1. **Availability**: there is now a window where *nobody* serves reads — the old
   leader has stopped and the new one has not started. That window is the lease
   length, and it is why lease lengths are a tuning fight.
2. **A clock assumption**: the lease is a duration, so the two leaders must
   agree on how long a second is. If the old leader's clock runs slow, it holds
   its lease past the point where the new one begins, and the violation comes
   back. **Clock skew has become a correctness input.**
3. **Latency on renewal**: the leader must keep renewing, which is traffic that
   exists purely to hold the guarantee.

**7.2 (a cheaper write-back).** The correct optimisation: **skip the write-back
when every replica in the read quorum already agreed.** If all `R` replicas
returned the same timestamp, then that value is already on `R` replicas, and if
any two read quorums must intersect — `2R > N` — a later read must touch one of
them and cannot go backwards.

Measured (`Sim.lazyWriteBack`, 300 histories each, and locked in as tests):

| configuration | violations |
|---|---|
| N=3, R=2, W=2 — always write back | 0 |
| N=3, R=2, W=2 — skip when the quorum agreed (`2R = 4 > 3`) | **0** |
| N=5, R=2, W=4 — skip when the quorum agreed (`2R = 4 ≤ 5`) | **6 of 600** |
| N=5, R=2, W=4 — always write back | 0 |

So: the assumption is **`2R > N`**, not `R + W > N`. When it is violated — a
configuration tuned for fast reads, R=2 on N=5, which is a perfectly sensible
thing to want — the optimisation silently stops being correct while the counting
rule you were relying on still holds. Two read quorums that do not intersect
each other cannot keep each other in order.

The other cheap scheme is the **leader lease** from 7.1: one machine answers
reads locally with no quorum round trip at all. The assumption there is bounded
clock drift, and 7.1 lists what breaks when it fails.

**7.3 (retry after a timeout).** A pending `put(1)` may or may not have taken
effect, and the client cannot tell. Retrying `put(1)` is safe because the second
execution leaves the value exactly where the first one would have: the operation
is **idempotent**, so "once" and "twice" are indistinguishable in the final
state.

`increment` is not. If the first attempt succeeded and the client retries, the
counter goes up twice, and the client — which never got either answer — has no
way to detect it. This is the previous lab's *"a failed write is never rolled
back"* finding, arriving from the other direction.

What the server must add: **a per-operation identity that survives the retry**.
The client generates a request id, sends it with the operation, and the server
records "I have already applied request `abc123`" and returns the original
result instead of applying it again. Note where the state has to live — on the
server, durably, ordered with the write itself, which is why an idempotency key
table is written in the same transaction as the payment.

---

## Rung 8 — Design

These are discussions, not single answers. What follows is one defensible
position with its reasoning exposed.

### 8.1 — Payments, three regions

| Read | Linearizable? | Why |
|---|---|---|
| dashboard balance | **No** | a human reading a number; a second of staleness is invisible and the extra cross-region round trip is not. Show a timestamp ("as of 10:04:12") and the staleness stops being a lie |
| balance check inside an authorisation | **Yes** | this read *decides* whether money moves. A stale balance authorises an overdraft, and the loss is real money. This is the read you pay for |
| statements page | **No** | historical data; immutable once written. Nothing to be stale about |
| fraud feature lookup | **No** | a model input, already probabilistic, already computed from a window. Making it strongly consistent adds latency to the hot path for no decision quality |
| idempotency-key check | **Yes**, and more | this decides *whether a payment already happened*. Getting it wrong is a double charge. It also needs to be atomic with the capture itself, which means a compare-and-set or a transaction — not just a linearizable read (experiment 8 is the reason) |

**Placement.** Shard by account, and put each shard's leader in the region that
account is used from — this is what "region affinity" buys, and it means the
common case never crosses an ocean. The consensus group still spans three
regions for durability, so a *write* pays one cross-region round trip to reach a
majority.

**Numbers.** With regions ~110 ms apart, a majority for a leader in Mumbai is
Mumbai plus the nearer of the other two: roughly **110–120 ms** for a write, and
for a linearizable read a lease read is local (single-digit ms) while a
read-index read costs one round trip to a majority (~110 ms). Budget p99 higher
than that; queueing under load is what actually sets p99, not the speed of
light.

**What to monitor.** Leader elections per hour, lease expiries, read-index round
trip, replication lag, and **clock skew across the fleet** — the last one is a
correctness metric because leases depend on it. Alert on non-monotonic reads
observed by a canary client if you can afford one; that is the only symptom that
a stale cache and a stale replica cannot disguise.

**What you tell the product team.** Not "consistency is slow". Say: *the
dashboard reads from the nearest copy and is up to a second behind, which is why
it is fast. Authorisation reads from the copy that decides, which is why it
takes an extra 110 ms. If we made the dashboard read the deciding copy, every
page load would cost that 110 ms and the number on screen would still be stale
by the time it reached the browser.* That last clause is the one that ends the
argument, and it is true: **linearizability is a guarantee about the moment the
answer was produced, not about the moment the human read it.**

### 8.2 — A lock you can trust

Do not rely on the lock for correctness. Rely on the resource.

- **The lock** is a lease with a TTL, used to make double-running *rare* — a
  performance optimisation, not a guarantee.
- **The token**: acquiring the lock returns a monotonically increasing number.
- **The guarantee**: every write in the capture path carries the token, and the
  storage layer **rejects any write whose token is lower than the highest it has
  seen**. Or, equivalently and often more simply, every capture is written
  against an idempotency key so a second attempt is a no-op.

**Clock assumption:** none for correctness. The TTL affects only how long you
wait before a second worker starts; if the clocks are wrong you get more
double-runs, not incorrect ones.

**A 90-second pause mid-run:** the lease expires, worker B starts and captures
with token 8. Worker A wakes up believing it still holds the lock and tries to
write with token 7. The storage rejects it. A discovers it has been fenced and
stops. **Nothing incorrect happened, and no clock had to be right.**

The general shape is worth carrying: *a guarantee enforced at the place the
damage would occur beats a guarantee enforced at the place the decision was
made.*

### 8.3 — The read that must not be linearizable

**A read whose job is to be available.** The clearest example in a payments
system is the **card authorisation path during a regional partition**, and the
answer is a business decision, not a technical one.

If the region holding the leader for an account is unreachable, a linearizable
read *must* refuse — that is the CAP theorem, not a design flaw. The result is
that every card transaction for those accounts declines. For a small window and
a large exposure, declining is right. For a large window and small transactions,
declining every coffee purchase in a country is worse for the business than
accepting a bounded overdraft risk — which is why real card networks have
**offline authorisation floor limits**: below a value threshold, approve locally
without contacting the issuer, and reconcile later.

What you break by making it strongly consistent: **availability during exactly
the failure the design exists to survive**, in exchange for eliminating a risk
you had already priced and capped. The second clearest example is a rate limiter
or fraud counter — both are already approximations, and paying a consensus round
trip per request to make an approximation exact is spending latency on the
wrong thing.
