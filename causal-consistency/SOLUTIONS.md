# Solutions — causal consistency

Every number below came from running the lab. Where an answer is a design
discussion rather than an output, it is marked as such.

---

## Rung 1 — Explain

**1.1** The comment only exists because its author read the post first, but the
message announcing the comment says nothing about that — it just says "set
comment to this text". So when the two updates travel to a third region by
routes of different speeds, the comment can arrive first and be shown on its
own. Every machine did exactly what it was told; the information that one
depended on the other was destroyed when the message was built.

**1.2** A version vector orders the copies of **one key** and tells you when two
updates to that key conflict. The post and the comment are different keys, and
nothing in a per-key structure has an opinion about the order between two keys.
`java src/Tests.java broken` reproduces exactly this mistake: the
`same-key-only` rule produces anomalies in **99 of 200** runs.

**1.3** It says nothing at all about them — that is the definition. Two writes
neither of whose authors had seen the other are *concurrent*, and causality
leaves them unordered; something else (a timestamp tie-break, or a CRDT merge)
has to decide, and adding that is what makes it causal+.

**1.4** Not a violation. Causal consistency says nothing about recency — there is
no bound at all, and `Tests.a_causal_read_can_be_arbitrarily_stale` returns a
value 100 ticks old with zero violations recorded. **Linearizability** is the
model that forbids it: there, an operation already answered must precede
anything sent afterwards, which pins reads to real time.

**1.5**
- **read-your-writes** — you edit your profile, reload, and the old name is back.
- **monotonic reads** — you see a comment, refresh, and it disappears.
- **monotonic writes** — you rename a file then move it, and remotely the move
  lands first and fails.
- **writes-follow-reads** — you reply to a message, and someone elsewhere sees
  your reply before the message you replied to. (This is the one the whole lab
  is about; it is the only one stickiness cannot give you.)

---

## Rung 2 — Identify

**2.1** Ben's comment turns amber (it is in Amira's causal *future*). Amira's
own circle is highlighted. Every other circle stays grey, meaning **concurrent**
— neither in her past nor her future, and therefore something the causal rule
will never hold back or reorder. Grey is where causality has no opinion, and it
is usually most of the graph.

**2.2** The dashed line is a change that has **arrived and is being deliberately
held**. Its left end is the moment the copy landed; its right end is either the
moment it was released or the current time if it is still waiting. Singapore's
card says it is waiting for `R0 up to #1` — the post. Nothing arrived faster;
the region simply declines to show what it already has.

**2.3** `Cluster.deliverable(Replica r, Write w)`. The branch COPS implements is
`case DEPS` with `nearestOnly` — name the dependencies explicitly, and prune the
ones another dependency already implies.

**2.4** `deps` is the subject. `nearest` and `vv` are two compressed encodings
of the same information (exact and over-approximate respectively). `lamport` is
**not** an encoding of it: it is a total order that happens to be consistent
with causality, which is a strictly weaker thing — it can tell you an order but
never tell you whether two writes were related.

**2.5** Effect: `R1#1`, `comment = "lovely dog!"`. Cause: `R0#1`,
`post = "photo-of-a-dog"`. The checker knows because `R0#1 ∈ R1#1.deps` — Ben's
client context contained the post when he wrote, because he had read it, and
`Cluster.get` adds every write it returns (and that write's own past) to the
reader's context.

---

## Rung 3 — Predict

**3.1** **No, not on its own.** Measured:

```
Lamport order, reordering links      3/300 runs      3 anomalies
Lamport order, FIFO links            0/300 runs      0 anomalies
```

It is causal only if every link delivers in send order. The leak: a region
decides a write stamped 7 is safe once it has heard from everyone at 7 or
higher — but if a later write from London (stamped 9) overtakes an earlier one
(stamped 4) on the same link, the evidence says London is at 9 while London's
write 4 is still in the air. This is why "we use TCP, so we're causal" is wrong:
TCP is FIFO **per connection**, and a sharded store uses many.

**3.2** The **vector clock** holds writes longer — 16,279 hidden write-ticks
versus 7,428, more than double, for the same guarantee. The two structures
answer different questions: the dependency list records *what the client saw*
(exact), the vector clock records *what the client's region had applied* (a safe
superset). Experiment 9 measures the over-approximation: of 17,163 pairs where
neither author had seen the other, the vector clock still puts **68.9%** in an
order, and each of those is a write waiting for something it never depended on.
**Precision costs bytes; imprecision costs latency.**

**3.3** **No — 400 of 400 runs end with the regions disagreeing**, wire empty,
nothing left to fix it. Causality has no opinion about concurrent writes, so each
region keeps whichever arrived last, and arrival order differs. With a
`(lamport, replicaId)` tie-break: **0 of 400**. That is the "+" in causal+.

**3.4** **No — 334 of 500 fail.** And nothing is out of order: the write is
simply still in flight. Causal delivery orders regions against each other; it
has nothing to say about a client that moves between them. The missing piece is
the client's own position in history, travelling with the client.

**3.5** It drops to **28 of 300**, not to zero. The workload still has one other
read path (the 1-in-3 branch that only reads), and every read a client performs
adds that write and its whole past to the client's context — so later writes by
that client still carry dependencies. **Causality is created by reading.** A
workload that never reads before writing has no causality in it and cannot
demonstrate, or test, any of this.

**3.6** **They do not.** Two asserted checks:

```
PASS  healing alone does NOT deliver what was lost
PASS  an anti-entropy round does
```

Causal delivery decides *when* a received write may be shown; it never decides
*whether* it arrives. The background repair loop from
[`gossip-protocols`](../gossip-protocols/) is not optional here.

---

## Rung 4 — Modify

**4.1** Only the Lamport row changes: **3/300 → 0/300**. The dependency-based
rows are already 0 and stay 0; apply-on-arrival is already broken and stays
broken.

The lesson: **a design that infers order from a counter has a hidden dependency
on your transport; a design that names what it is waiting for does not.** When
you evaluate a store's consistency claim, "under what network assumptions?" is
the question that separates the two.

**4.2** You recreate `vv-no-gap-check`, which produces anomalies in **10 of
200** random runs.

The deterministic comment-before-post story does not reproduce it because the
bug removes only the *own-author sequence* check. In that story the missing
dependency is a write from a **different** region (London's post), which the
surviving `w.vv[j] > r.vv[j]` clause still catches. To trigger this bug you need
a gap in one author's **own** sequence — the author's write #3 arriving while #2
is still in flight — which a two-write story never creates.

That is the general shape of the bug: the vector-clock check is two conditions
doing two different jobs, and removing either leaves the other looking like it
still works.

**4.3** *Never accept a consistency result from a test that did not inject
network asymmetry.* Measured on one unchanged design: **30/300** at no extra
delay, **214/300** at 20 ticks. A design with no ordering guarantee at all is
indistinguishable from a correct one on a calm network.

**4.4** Eleven tests fail, and they split cleanly into two groups:

```
FAIL  200/200 sticky reads see the client's own write (62/200)
FAIL  causal + sticky: read-your-writes holds
FAIL  causal + sticky: monotonic reads holds
FAIL  causal + sticky: writes-follow-reads holds
```
— these are the **client's** failures. Nothing is out of order in the store; a
client keeps being handed to a replica that has not caught up to where the
client already is.

```
FAIL  your own write is visible to you immediately
FAIL  the isolated minority answers all 100 operations
FAIL  causal delivery: Cara sees neither, not the wrong one
```
— these look like store failures but are the same cause wearing a disguise: the
tests assume a named client keeps talking to a known region, and with roaming
the client is somewhere else by the time it reads.

The takeaway is the one from §10 v8: **session stickiness is doing real
consistency work**, and removing it "for better load balancing" is a correctness
change, not an infrastructure tweak.

---

## Rung 5 — Implement

**5.1** The blank starts at **2 / 6** (checks 1 and 2 pass trivially for
`return true`). The whole rule:

```java
static boolean safeToShow(Set<Dot> applied, Write w) {
    for (Dot d : w.deps()) if (!applied.contains(d)) return false;
    return true;
}
```

**6 / 6.** Check 4 is the exercise: it fails for the version that filters
`w.deps()` down to dependencies on the same key, which is failure 10 and the
most common real-world mistake in this area.

**5.2** A workable shape — count how many held writes are blocked on each id and
report the worst:

```java
public Dot oldestBlockedDependency(int replica) {
    Map<Dot, Integer> blocking = new TreeMap<>();
    Replica r = reps[replica];
    for (Write w : r.pending)
        for (Dot d : w.deps())
            if (!r.applied.contains(d)) blocking.merge(d, 1, Integer::sum);
    return blocking.entrySet().stream()
        .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
}
```

At a slow route of 32, one id sits at the head of many queues — which is exactly
the signature of the slowdown cascade, and why §15 nominates *oldest blocked
dependency age* as the one alarm to have if you may have only one. A queue that
is deep but has many different heads is normal load; a queue that is deep behind
**one** id is a stuck upstream.

**5.3** Change `allHeardAtLeast` to skip a replica you have never received
anything from. The new problem is immediate: you can no longer distinguish
*"that region has nothing to say"* from *"that region's message has not arrived
yet"*, so you release writes that a slower message would have preceded — you are
back to failure 1. This is failure 4 from the other direction, and it is why
systems that need a total order send **heartbeats**: not to carry data, but so
that silence can never be mistaken for lag. Raft's empty `AppendEntries` exists
for the same reason (see the [`raft`](../raft/) lab).

---

## Rung 6 — Debug

**6.1** It encodes *"we use TCP, so messages from a given sender arrive in
order, so we are causal."* It is nearly true — it does give you **monotonic
writes**, one of the four guarantees — which is exactly why it survives review.
Smallest breaking story: Amira writes the post (R0), Ben **reads** it and writes
the comment (R1), Cara's region receives the comment. The comment's only
dependency is from a *different* replica, so the rule waits for nothing.
Anomalies in **98 of 200** runs; the deterministic story reproduces it exactly.

**6.2** [`quorums-and-replication`](../quorums-and-replication/) teaches you to
build precisely this, and it is correct for what it was built for: **detecting
conflicting updates to one object**. It is the most dangerous of the three
because it is not an implementation slip — it is a correct tool applied to the
wrong question, by someone who can defend it. Anomalies in **99 of 200** runs.
The one-sentence corrective: *causality is a statement across keys.*

**6.3** It is rare because it only fires when one author's **own** writes arrive
with a gap — #3 landing while #2 is in flight — which needs that author to have
written twice in quick succession over a link that reorders. Two writes by
different people never trigger it.

Rarer is worse, for three reasons: it survives every test suite and every
staging environment; when it does fire it is unreproducible, so it gets closed
as "could not repeat"; and it fires most under load, which is when the
consequences are largest. The general rule — the same one experiment 2 teaches —
is that **in consistency work, a low failure rate is evidence about your test
environment, not about your design.**

**6.4** (a) **Clocks disagree.** You measured this twice already: a replica 60
seconds fast destroyed **1,000 of 1,000** genuinely newer writes in both the
[`quorums-and-replication`](../quorums-and-replication/) and
[`crdts`](../crdts/) labs. If the commenter's machine is slightly behind the
poster's, the ordering inverts with no error. (b) **A timestamp does not tell
you the order is complete.** Holding a write stamped 14:32:09, a replica has no
way to know whether something stamped 14:32:07 is still in flight — so it either
shows things out of order or waits forever. Fixing (a) with NTP does nothing
about (b), which is the deeper of the two.

---

## Rung 7 — Worked design discussions

### 7.1 The session token

**What the token contains:** the client's causal position — the set (or, in
practice, the vector-clock summary) of everything it has seen or written. In
MongoDB this is `afterClusterTime`, a hybrid-logical-clock timestamp; in Bayou
terms it is the session's read-set and write-set summary.

**What the replica does when behind:** it waits until its own applied state
covers the token, then answers. It does not answer from a stale state and it
does not error immediately — it converts a correctness problem into a latency
problem, which is the same trade the whole lab makes.

**Measured, on the roaming workload that scores 334/500 failures today:**

```
token=false   read-your-writes=3759   monotonic-reads=1611   writes-follow-reads=356   anomalies=726
token=true    read-your-writes=0      monotonic-reads=0      writes-follow-reads=0     anomalies=0
```

and on the isolated read-your-writes test, **334/500 → 0/500**. Note the guard
is needed on **both** paths: on reads so the client does not go backwards, and
on writes so a write made from a fresh region still carries the dependencies the
client acquired elsewhere. Guarding only reads leaves writes-follow-reads broken.

**When the replica can never catch up** (the write went to a region that is now
partitioned away): the wait must be bounded. After the timeout you have three
honest options and one dishonest one. Honest: fail the request with a
retryable error; route the client back toward a region that can satisfy the
token; or degrade explicitly and tell the UI it is showing a stale view. The
dishonest one — answer anyway — silently converts a latency problem back into
the correctness problem you built the token to fix, and it is what happens by
default if nobody chooses.

**What the client is told:** something actionable and specific — "this view may
be a few seconds behind" for the degraded path, not a generic error. Note the
irony worth stating in a design review: **the client-side half is where causal
consistency stops being always-available.** The store still answers; the session
guarantee is what makes you wait.

### 7.2 Dependency garbage collection

**The fact that must be true:** every replica that could ever receive a write
naming this dependency has already applied it. Then no receiver can ever need it
to decide whether to wait, and it can be dropped from the list.

**The mechanism:** each replica publishes what it has applied (a vector clock);
take the pointwise minimum across the membership — the **stable frontier** — and
prune any dependency below it. This is *causal stability*, structurally
identical to the tombstone-GC problem you solved in the [`crdts`](../crdts/)
lab, and it needs the same two things: membership and liveness, from
[`gossip-protocols`](../gossip-protocols/).

**What breaks if you drop one too early:** a replica that never received the
dependency receives the dependent write with nothing telling it to wait, applies
it, and shows an effect without its cause — failure 1, reintroduced by the
optimisation meant to make it cheaper. This is the same shape as the
too-early-tombstone-drop failure in the crdts lab, and it is equally
unrecoverable: nothing later will notice.

**What stalls it:** one unreachable replica freezes the minimum, so the frontier
stops advancing and lists grow again. The answer is the same as for tombstone
GC — after a timeout, evict the silent member and require it to re-bootstrap.
Which means, once again, a coordination-free property leaning on a coordinated
membership service.

### 7.3 The slowdown cascade

**Two mitigations that keep the guarantee:**

1. **Reduce over-ordering.** Half of the waiting in the vector-clock design is
   for writes the author never saw — 68.9% of concurrent pairs treated as
   ordered. Switching to pruned explicit dependencies more than halves the
   hidden write-ticks (16,279 → 7,428, measured). You pay in bytes and get
   latency back.
2. **Make the slow route not be on the critical path.** Deliver each write over
   more than one route, or have a fast replica forward on behalf of a slow one
   (COPS-style dependency propagation). The dependency then arrives by whichever
   path is quickest, and one slow shard stops gating unrelated writes.
   *Measure:* oldest-blocked-dependency age (exercise 5.2), per replica.

**One that abandons it in a controlled way:** cap the hold. If a write has
waited longer than N, apply it anyway and record the event. You have chosen to
serve an anomaly rather than a stall — which is defensible for a like counter
and indefensible for a privacy setting. *Measure:* the rate of forced releases,
alarmed as a correctness metric, and **broken out by key class** so the privacy
keys can be excluded from the policy entirely.

**What the user experiences** in each case: (1) and (2) are invisible — things
appear sooner. (3) is invisible until the day it fires, and then it is failure 1
in production with a metric that tells you it happened, which is strictly better
than the silent version but is not "no anomaly".

---

## Rung 8 — Worked design discussions

### 8.1 A multi-region social product

**Needs causal ordering, across keys:**
- **post → comment → reply.** The whole point; a reply visible before its parent
  is the bug this lab exists for.
- **privacy setting → post.** "Remove my manager from who-can-see-this" then
  "publish" is two writes to two keys with one obvious order, and this is the
  COPS example. This is the security item.
- **block → post.** Same shape: block someone, then post. If the post overtakes
  the block, the blocked user sees it.
- **follow → timeline entry**, **upload → attach**, **delete account → any
  subsequent write.**

**Does not need it:**
- **like counts** — a CRDT counter; nothing causally precedes a like that
  matters, and delaying likes for ordering is pure cost.
- **presence / "last seen"** — an LWW register; losing a concurrent update is
  fine and staleness is expected.
- **view counts, analytics** — no ordering, no per-user guarantee.

**The session token** lives at the edge: issued and carried by the client (a
cookie or header), presented on every request, and enforced by the region. On a
load-balancer failover, the new region reads the token and waits — which is the
whole reason to have it, and the reason stickiness alone is not a design. Budget
for it: a bounded wait plus an explicit stale-view path, per 7.1.

**Must NOT be causal:**
- **username / handle uniqueness** — needs the ability to refuse. Coordinated
  store, conditional write.
- **payments, credits, anything counted down** — the crdts lab's −2 bags of
  flour, in a different costume.
- **auth token revocation** — this one is subtle and worth arguing in the
  review: revocation must be *fast and fail-closed*, and a causal store gives
  you neither bound. Short token lifetimes plus a coordinated deny-list.

**What the user sees when a region is behind:** their own writes, always
(session token). Other people's, late. Never an effect without its cause. The
one thing to design deliberately is the *stale-view* indicator, because the
alternative is a user who does not know whether the feed is empty or just behind.

**Three metrics before launch:** (1) oldest blocked dependency age, p99 per
region — the cascade alarm; (2) session-token wait time and timeout rate — the
client-side cost, which is where users feel it; (3) privacy-setting propagation
lag specifically, separated from everything else, because it is the item where
being late is an incident.

**The security item:** the privacy/block ordering. Getting the like counter
wrong is a bug report. Getting "remove access, then publish" wrong publishes
private content to the person it was hidden from, and no amount of eventual
convergence undoes the moment they saw it.

### 8.2 Turning it on in a real stack

MongoDB has supported causally-consistent sessions since 3.6, so the change is
to open a session per user request and pass it through every read and write —
the driver then attaches `afterClusterTime` and secondaries wait until they have
caught up before answering, which is precisely the fix in 7.1. The cost is
latency on reads that land on a lagging secondary, and a new failure mode
(waiting, then timing out) that your code must handle rather than ignore. Before
and after, measure secondary read latency at p99 and the rate of session waits
that exceed your budget — the second number tells you whether replication lag,
not the feature, is your actual problem. "Just read from the primary" *is* the
answer if your read volume fits on one node: it gives you a strictly stronger
guarantee for less complexity, and the honest version of this proposal says so
out loud. It stops being the answer when read volume or geography forces
secondaries, and the point of causal sessions is that they let you keep the
secondaries without the complaints. One caveat to state explicitly: this fixes
the *client's* view, and says nothing about ordering between two different
users' writes across regions — if your product has the privacy-setting problem
from 8.1, sessions alone do not solve it.

### 8.3 Where causal consistency is the wrong choice

**Seat reservation** (the easy one). Booking seat 14C must be able to *refuse*,
and refusing requires knowing what everyone else has done. Causal consistency
never blocks and never refuses, so two regions will both accept 14C and then
faithfully converge on a state where both did. It is right for everything around
the booking — the seat map, the browsing, the party size, a "requested" state —
with a single coordinated write at the moment of commitment. Same boundary as
the crdts lab: **let the weak model carry the intent, and consensus carry the
commitment.**

**Rate limiting** (the interesting one). At first it looks like the seat
problem — "at most 100 requests per minute" is a global invariant, so it needs
coordination. But your
[`distributed-rate-limiting`](../distributed-rate-limiting/) lab already
established that nobody actually pays for that: the limit is a business policy
with slack in it, so approximate counting with local buckets and background
reconciliation is the *correct* engineering answer, not a compromise. So the
verdict is neither "causal works" nor "causal fails" — it is that **the invariant
was never really an invariant**, and the right question is not which consistency
model to buy but how much overshoot the policy tolerates. That distinction —
between a rule that must hold and a rule that must mostly hold — is worth more
in design reviews than any consistency model on the ladder.
