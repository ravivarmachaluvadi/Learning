# Split Brain — Solutions

Attempt [EXERCISES.md](EXERCISES.md) first. Numbers quoted here come from the
simulator with its default settings; yours may differ by a few if you changed a
slider, but the *shape* of every result should match.

---

## Rung 1 — Explain

**1.1** Split brain is when two machines that are supposed to take turns being in
charge both think it is their turn, at the same time, and both act on it.

You cannot fix it with a more careful failure detector because the detector is being
asked an unanswerable question. The only evidence one machine has about another is
messages arriving. When they stop arriving, two completely different situations
produce identical evidence: the other machine died, or the path between you broke.
There is no observation that separates them — not a longer timeout, not more retries,
not a smarter algorithm. A longer timeout makes false positives rarer and real
failures slower to detect; it does not make the question answerable. This is a limit
on what is knowable, not a gap in the implementation.

**1.2** Wrong because you cannot control what a machine believes: belief is a variable
in its own memory, it survives being unplugged, it survives being frozen, and the
machines you would need to correct are precisely the ones you cannot reach.

The goal that replaces it: **at most one authority's actions may ever take effect on
the shared resource, and once a newer authority has acted, nothing from an older one
may act again.** Effects, not beliefs; ordered, so it is checkable by comparing two
integers; and *at most* one, because "nobody may act" is sometimes the correct answer.

**1.3**
- **Partition** — the network has broken so that two groups of machines cannot reach
  each other. A cause.
- **Split brain** — two machines simultaneously believe they hold a role that is
  supposed to be exclusive, and act on it. A state of belief.
- **Divergence** — two versions of the data that share a prefix and then differ, both
  containing writes that were acknowledged to somebody. The damage.
- **Data loss** — somebody has discarded one of those branches. A later, separate,
  usually manual event.

They are independent: you can have a partition with no split brain (a well-designed
cluster), split brain with no divergence (fencing working), and divergence with no
partition (a frozen process).

**1.4** Prevention would require stopping a machine from holding a belief, which is
impossible, so any design aiming at it is aiming at something it cannot reach and will
fail in ways it did not plan for. Harmlessness is achievable with one integer
comparison performed by the resource, and — the part people miss — it is *cheaper in
availability* as well. The measured comparison in stage 10: a lease reaches one leader
by making everyone wait and serves 118 writes; fencing leaves two leaders in existence
and serves 133, with the same zero damage. Prevention costs you availability to buy
something you cannot have; harmlessness gives you correctness and keeps more of your
service running.

---

## Rung 2 — Identify

**2.1** Two servers believe they are in charge: `S1` in London, still in **epoch 1**,
and a Manchester server in **epoch 2**. The database reports it will accept anything —
"nothing" as its floor — because fencing is switched off in this scenario, so writes
carry no token for it to compare. That is exactly why the damage counter climbs: the
database has no basis on which to refuse anybody.

**2.2** The split brain begins when the new leader wins its election, roughly 40 ticks
after the freeze starts (one silence limit). It ends when the new leader's next
heartbeat reaches `S1` after `S1` wakes — typically within about 7 ticks of waking.

It was ended by **a message arriving**, not a timer. `S1` saw an epoch higher than its
own and stood down immediately. This is worth internalising: most split brains in real
systems end in milliseconds, as soon as any packet gets through. The dangerous window
is not "until the lease expires" — it is "until the first message crosses", which is
usually much shorter and occasionally much longer.

**2.3** A hollow square is a refusal. It was sent by `S1` carrying **epoch 1**, and the
database had already served **epoch 2**. The comparison: `1 < 2` → refuse. That single
comparison is the entire safety mechanism, and note what is *not* in it: no clock, no
timeout, no assumption about how long anything took.

**2.4** `S1`'s inspector still says `role: leader`, `epoch: 1`, and "believes: that it
is in charge" — right up until the heartbeat reaches it. That is the whole point of
the two counters. The left counter is reading `S1`'s *belief*, which is wrong and
which nothing could have prevented. The right counter is reading the database's
*behaviour*, which is correct. Belief and authority are separate things, and only one
of them is worth defending.

**2.5** In the non-transitive run the epochs climb to around 6 within 400 ticks; on a
healthy cluster the epoch stays at 1 for the entire run. The difference is election
churn: nobody can hold the job, because the witness that both sides can reach keeps
voting for whoever bids higher. It is a symptom of a partition that is not a clean
split — and the reason to alarm on election rate rather than on partitions, since
nothing here reports itself as broken.

---

## Rung 3 — Predict

**3.1** Manchester's application gets **errors**, not stale successes. Its servers
cannot form a majority, so no Manchester server is ever elected, and a follower with no
reachable leader has nothing to forward a write to. This is the CP behaviour: the
minority refuses rather than guesses.

Worth noting what would change the answer. If the system served *reads* from local
replicas without a leader check, Manchester would get stale successes on reads while
erroring on writes — which is the far more common real-world shape, and why a "read
from any replica" setting is a consistency decision disguised as a performance one.

**3.2** Manchester's servers receive nothing from London, so their failure detectors
fire and they campaign. Their vote requests *do* reach London, so London's servers see
a higher epoch, step down, and vote — but their votes never come back. The result:
Manchester never wins, and London's leader has been repeatedly unseated by vote
requests it can hear but cannot answer.

This is the nastiest case in the whole lab: a node that cannot receive is invisible to
itself and disruptive to everyone else. London absolutely notices — it loses its
leader repeatedly — while the *cause* is entirely in Manchester and shows up nowhere in
Manchester's own metrics. This is what pre-vote exists to stop.

**3.3** London wins, always, in every partition. That arrangement is **not** a
tiebreaker. It is a configuration that says "London is the primary site and Manchester
is a warm standby that can never take over", which may well be what you want — but you
should know that is what you have built, because it means a London outage is a total
outage that no automatic failover will rescue.

The witness must be able to fail *independently of both* main sites for it to be a
tiebreaker. That is a physical constraint, not a config value.

**3.4** A 30-tick freeze with a 40-tick limit: **zero damage** — nobody times out, so
no election happens, so there is no second authority. A 70-tick freeze: **damage**,
measured at 2 writes.

The rule separating them is "is the freeze shorter than the silence limit". You should
trust it about as far as you can throw it. It requires you to know your worst-case
process stall in advance, and the [distributed-locking](../distributed-locking/) lab
put a number on how well that goes: *"longer than my longest pause" is a number nobody
has.* Your longest pause is set by the garbage collector, the hypervisor, the disk and
your noisiest neighbour, none of which consulted you. This is a timing bet, and stage
10's point is that you want a rule with no timing in it at all.

**3.5** **Two** servers believing they are in charge, and **zero** damage. The
stranded incumbent keeps its belief until its lease expires, and meanwhile its writes
bounce off the database's epoch floor. That combination — belief duplicated, authority
not — is the healthy shape of split brain and the target you should be designing for.

---

## Rung 4 — Modify

**4.1** The damage disappears, because the old leader's lease has not expired by the
time it wakes, so its authority is arguably still valid and the new leader has not yet
started acting.

You paid for it with a longer mandatory dead window on every single failover — the new
leader must wait out the whole lease before touching anything, on every leadership
change, forever, including the ordinary ones. And it is not a real fix, for two
reasons. First, you have set a timing parameter based on a freeze length you happened
to choose; a freeze one tick longer defeats it. Second — and worse — a *longer* lease
means a *longer* period during which a genuinely dead leader has not yet been replaced.
You have made the rare bad case less likely and the common case slower. That is the
lease's whole trade, and it is why fencing is the better instrument: fencing has no
parameter to get wrong.

**4.2** Better: far fewer false failovers. A GC pause or a slow disk no longer triggers
an election, so the cluster is much more stable, epochs stay flat, and you stop
generating split brains for no reason.

Worse: a genuinely dead leader is now unnoticed for up to 90 ticks, so every real
failure costs a much longer outage.

You traded **liveness for liveness**, not safety for liveness — and that is the
subtlety worth naming. Safety was never on the table here: with fencing on, no setting
of this dial can corrupt anything. What you are choosing between is *false-failover
churn* and *real-failure detection latency*. Both are availability. This is exactly
why the raft lab's finding matters — election churn is always a timing problem, never
a correctness one.

**4.3** Routing changes **how much** damage occurs and, in some configurations,
whether it occurs at all — but it never changes whether the system is *safe*. If both
apps talk to servers in the same city, only one authority is receiving writes and no
divergence appears, even though split brain exists. Point them at different cities and
both authorities are fed.

So client routing is a **liveness and blast-radius** matter. It determines who gets
served and how bad a split brain becomes; it cannot make an unsafe design safe. Which
is why it must never be load-bearing: a design that is only safe because of where the
load balancer happens to point is a design that breaks when someone edits the load
balancer.

**4.4** Two servers believe they are in charge (quorum is off, so both sides take
over), and **zero damage** (fencing refuses the older one). Writes served will be
noticeably lower than the healthy baseline, because one side's writes are all bouncing.

This is the cleanest demonstration in the lab of the division of labour:

- **Fencing does the safety work.** It alone reduces the damage to zero, with no
  quorum anywhere in the system.
- **Quorum does the availability work.** It stops the losing side from taking over in
  the first place, so that side's clients get a clean error instead of a stream of
  refused writes, and so the cluster does not thrash.

Neither substitutes for the other. If you can only have one, and correctness is what
you care about, fencing is the one that protects your data.

**4.5** Writes served drop sharply. A 10-tick lease must be renewed constantly, and
every renewal round-trip that is even slightly delayed causes the leader to stand
itself down, triggering a fresh election and a fresh mandatory quiet period before the
new leader may act. You spend the cluster's time on leadership rather than work.

The general shape: lease too long, real failures take too long to recover; lease too
short, you fail over constantly and pay the quiet period every time. There is no
setting that is good at both, which is the same conclusion the gossip lab reached
about its detection dial.

---

## Rung 5 — Trace

**5.1** Quorum on, lease off, fencing off. `S3` leads in epoch 4; silence limit 25;
`S3` freezes at `t=10` for 60 ticks (wakes at `t=70`).

```
t=0..9    Normal.  S3 leads in epoch 4.  London app → S3 → database.
          Manchester app → S5 → forwarded to S3 → database.
          Database's newest served epoch: 4.

t=10      S3 freezes.  Not crashed; the network is untouched.  It stops sending
          heartbeats because it stops executing.

t=35      S1, S2, S4, S5 have all now seen 25 ticks of silence.  The first one
          to reach its (randomised) limit — say S2 — becomes a candidate in
          epoch 5 and requests votes.

t=38      S1, S4, S5 grant votes: epoch 5 > the epoch 4 they hold.
          S2 has 4 votes of 5, needs 3.  S2 is leader in epoch 5.
          The cluster is entirely correct at this moment.

t=40      Both apps now route to S2 (their local servers forward to the leader
          they believe in).  S2 writes.
          Database's newest served epoch: 5.

t=70      S3 wakes.  Nothing informs it that 60 ticks passed.  Its role variable
          still says leader; its epoch variable still says 4.

t=70      The London app's next write reaches S3.  S3 believes it is the leader,
          accepts it, and sends it to the database carrying epoch 4.
          Fencing is OFF, so the database accepts it.
          Database had already served epoch 5.
          ---> DAMAGE.  Epoch 4 has overwritten epoch 5's work.

t=75      Same again, if another write arrives before the next heartbeat.

t=77ish   S2's heartbeat (epoch 5) reaches S3.  S3 sees 5 > 4, steps down to
          follower, adopts epoch 5.  Split brain over.

t=90      One leader, S2, epoch 5.  Total damage: 2 writes (1–3 depending on how
          the write timer and the heartbeat timer line up).
```

The honest note to write down: the damage count is set by a **race** between `S3`'s
first post-wake write and `S2`'s next heartbeat. That is why this design cannot be
reasoned about.

**5.2** Identical up to `t=70`. Then:

```
t=70      S3 accepts the write and sends it to the database with epoch 4.

          The database performs:   is 4 < floor(5) ?
                                   4 < 5  →  TRUE  →  REFUSE

          The write is rejected.  The refusal counter increments.  The value in
          the database is untouched.  S3 still believes it is the leader — the
          refusal changes S3's authority not at all, only its effectiveness.

t=77ish   S2's heartbeat reaches S3; S3 steps down.

          Total damage: 0.  Total refusals: 1–2.
```

The comparison to write out is `4 < 5 → refuse`. Note there is no clock in it, no
timeout, and no assumption about how long `S3` was frozen — it would be correct if
`S3` had been frozen for a millisecond or a fortnight.

**5.3** Four servers 2|2, quorum and lease on, `S1` leading in epoch 7 with a lease to
`t=150`. Link cut at `t=100`.

- **`t=110`** — `S1` still believes it leads and its lease is still valid, so it is
  still serving London's writes. It is trying to renew against a majority (3 of 4) and
  can reach only `S2`, so its renewals are failing. `S2` is following `S1` normally.
  `S3` and `S4` have heard nothing since `t=100`; they have not yet timed out. **The
  service is up for London and down for Manchester.**

- **`t=155`** — `S1`'s lease has expired with no successful renewal, so `S1` has stood
  itself down and is now a follower. `S3` and/or `S4` timed out somewhere around
  `t=140` and are campaigning in epoch 8, but they can only reach two votes and need
  three, so they lose and try again with a higher epoch. `S1` and `S2` will also
  eventually campaign and also lose. **The service is down everywhere.**

- **`t=300`** — Unchanged in substance. All four servers are alive and healthy, all
  four are campaigning in ever-higher epochs, none can win. Epoch numbers are climbing
  steadily. **Total outage, zero damage, four working machines.**

That last line is the 2|2 lesson: an even split converts a network fault into a
complete outage while keeping every guarantee perfectly intact. Safety is not the
thing you lost.

---

## Rung 6 — Spot the flaw

**6.1** The pre-write check dies in its own gap.

A concrete run: worker A holds the lock, TTL 30 s, renewing every 10 s. At `t=0` A
checks the lock and sees it holds it. At `t=0.001` A's JVM enters a 35-second
stop-the-world pause. Its renewal thread is also stopped — it cannot heartbeat,
because being stopped is exactly what makes the lease expire. At `t=30` the lock
expires. At `t=31` worker B acquires it and starts writing. At `t=35` A resumes,
completes the instruction after its check, and writes.

Both wrote. The check was true when performed and false when it mattered.

**Violated:** mutual exclusion, and through it whatever invariant the lock protected.

The general principle: **a check before acting cannot protect you, because the gap
between the check and the act is unbounded.** The check has to be performed by the
thing being affected, atomically with the effect. This is the
[distributed-locking](../distributed-locking/) lab's measured result, and adding
renewal does not move the cliff — the pause that expires your lease is the same pause
that prevents you renewing it.

**6.2** The counter is not durable.

A concrete run: the election service issues epochs 1 through 12 over some months. The
store's floor is 12. The election-service process restarts — a deploy, a crash, a pod
reschedule. Its in-memory counter starts at 0. The next election issues epoch 1. Every
write from the new, entirely legitimate leader carries epoch 1, and the store refuses
all of them because `1 < 12`. The system is down, with a correct leader that cannot do
anything, until the counter climbs back past 12 — which takes twelve more elections.

**Violated:** liveness. Note carefully that *safety was preserved* — nothing was
corrupted. That is the signature of this bug class and why it is so confusing in
practice: a total outage with a perfectly healthy cluster and no data damage.

There is a worse variant. If the store's floor is *also* reset or the store is
replaced, the reissued epoch 1 collides with the original epoch 1 and two different
authorities become indistinguishable — and then you lose safety too.

**The rule:** the epoch must be persisted before it is used, which is precisely why
real systems derive it from something already durable — a Raft term written to disk, a
ZooKeeper `zxid`, an etcd revision — instead of minting their own.

**6.3** Fencing writes but not reads loses updates via read-modify-write.

A concrete run, which is the [distributed-locking](../distributed-locking/) lab's
planted bug #4 and the most invisible failure in that lab: leader A (epoch 5) reads
`balance = 100`, intending to write `110`. A pauses. Its lease expires. B is elected
in epoch 6. B reads `balance = 100` — the read is not fenced, so nothing about B's read
raises the floor. B writes `120` with epoch 6; accepted, floor becomes 6. A wakes and
writes `110` with epoch 5 — refused, correctly.

So far so good. Now the variant that actually bites: A's *write* was already in flight
when its lease expired, so it arrives carrying epoch 5 at a time when the floor is
still 5, and is accepted. Then B's read (unfenced, so it raised nothing) had already
returned the pre-A value. Both writes carry valid, increasing tokens; zero refusals;
one update silently lost.

**Violated:** the effect-ordering invariant, in substance if not in letter — the
database's state no longer reflects a single serial authority lineage.

**The fix:** fence reads too. A read presents its token and raises the floor, so
older in-flight writes bounce. Chubby checks its sequencer on *every* request for
exactly this reason.

**6.4** Six servers tolerate the same number of failures as five and offer more
things that can break.

A majority of six is four, so six tolerates two failures — identical to five, which
also tolerates two. You have added a machine that can fail, a machine to patch, and a
machine to pay for, in exchange for nothing.

Worse in this specific layout: three in each of two data centres means a site link
failure gives 3 and 3, and neither side reaches four. **Total outage on a single link
failure.** Five servers as 3|2 would have kept one side serving. The correct
arrangement is an odd number of voters with no site holding a majority-blocking
share — in practice, three sites.

**Violated:** nothing formal. This is a pure availability own-goal, and the general
rule is: **always an odd number of voters, spread over an odd number of failure
domains.**

**6.5** Two nodes, symmetric fencing, and a partition means both shoot.

A concrete run: the link between them fails. Node A concludes B is dead and sends a
power-off to B's management controller. Node B concludes A is dead and sends a
power-off to A's management controller. If the management network is separate from the
cluster network, both succeed: total outage, caused entirely by the safety mechanism.
If the management network shares the broken path, neither succeeds and you now have
split brain plus two nodes each convinced it has just killed the other.

The ping-pong variant: A shoots B, B reboots, B concludes A is dead, B shoots A, A
reboots, and so on indefinitely.

**Violated:** liveness, catastrophically, by the mechanism installed to protect
safety.

**The mitigations**, all standard in Pacemaker: gate fencing behind quorum so only a
majority side may shoot (which a two-node cluster does not have, hence a
quorum device or `wait_for_all`); add an asymmetric or randomised delay before firing
so one side reliably wins a genuine race; and use a fencing path that does not share
fate with the cluster network.

---

## Rung 7 — Handle failure

**7.1 — The unfenceable payment API.**

Start by naming the situation precisely: **your consensus protects the voters, and the
payment API is not a voter.** It was not asked, it has no idea your cluster exists, and
it will do what it is told by whoever asks. No amount of correctness inside your five
nodes reaches it.

What you can actually build, in order of how much it buys you:

1. **Idempotency at the operation, not the caller.** Generate a stable key derived
   from the business fact — `payment-{orderId}-{attemptNumber}`, not a random UUID per
   request — and send it as the idempotency header. Where the provider honours it, a
   duplicate from a zombie leader is recognised and discarded. This is the only
   mechanism that protects you *at the resource*, and everything else is a way of
   making it less likely you need it.

2. **A local fenced intent log.** Before calling the API, write an intent row to your
   own database with a conditional write fenced on the epoch. If that write is refused,
   you are stale — do not call. This does not close the gap (you can be fenced between
   writing the intent and making the call) but it narrows it from "the whole lease" to
   "one network call", and it gives you a record to reconcile against.

3. **Short leases plus a watchdog.** Reduce the window in which a zombie can act. If
   you can, use a hardware or process watchdog that reboots a node that fails to renew
   — self-fencing, the way Patroni does. This turns "I might be stale" into "I am no
   longer running".

4. **Reconciliation.** Poll the provider for the authoritative list of transactions
   and diff it against your intent log, continuously, not just after incidents.

**The residual risk, stated honestly:** if the provider's idempotency is not reliable,
you have a window — bounded by (freeze duration) + (call duration) — in which a zombie
leader can submit a duplicate that nothing will refuse. You cannot eliminate it. You
*can* size it: instrument your process-stall duration and your API call latency, and
the product of (stalls longer than the lease per month) × (probability a submission is
in flight during one) is your expected duplicate rate. If that number is not
acceptable, the answer is not a better lock — it is to get the provider to honour
idempotency keys, or to move the invariant somewhere you control.

The [distributed-locking](../distributed-locking/) lab called this "box G" and reached
the same conclusion: a resource that cannot check conditions is safe under *no* lock.

**7.2 — Harmless retries.**

**The server** must make the operation idempotent: accept a client-supplied key with
the request, and on receiving a key it has already processed, return the *original
result* rather than performing the work again. Crucially, the key and the effect must
be recorded **atomically** — same transaction, same commit — or the server can perform
the work and then die before recording the key, which is the exact failure you were
trying to fix.

**The client** must generate the key *before* the first attempt and reuse the same key
for every retry of that logical operation. A client that generates a fresh key per
retry has defeated the whole mechanism.

**Which is load-bearing:** the server. The client's contribution is a discipline that
is easy to state and easy to get right; the server's is a real invariant that has to
survive crashes, and it is where the design work is. A useful sanity check: if the
server is correct and the client is sloppy you get duplicates; if the client is correct
and the server is sloppy you also get duplicates. But only the server can *make* the
guarantee, so that is where it must live.

The framing to carry: a timeout is **not** a failure. An operation that timed out may
still have committed. This is the [transactional-outbox](../transactional-outbox/) and
[idempotency](../idempotency/) labs' central point, and it is why retries must be
harmless rather than merely rare.

**7.3 — The break-glass procedure.**

The design constraint is unusual: it must be *usable* under pressure at 3 a.m. and
*not usable by mistake*. Those pull in opposite directions, so the resolution is to
make the dangerous step easy but make it require a fact that is hard to fabricate.

```
BREAK-GLASS: force quorum onto a surviving site

STOP. This procedure can destroy data. It is safe only if the other site is
genuinely down. It is NOT safe if the other site is merely unreachable, and
the cluster cannot tell the difference — that is why you are here.

1. ESTABLISH DEATH BY OUT-OF-BAND EVIDENCE.
   You must confirm the other site is down using a channel that does NOT
   depend on the link that failed. Acceptable evidence, at least two of:
     - the cloud provider's console shows the instances stopped or the
       region impaired;
     - the hypervisor/BMC reports the hosts powered off;
     - a human physically present reports it;
     - the provider's status page reports a regional outage.
   NOT acceptable: "we cannot reach it", "it is not responding",
   "monitoring is red". That is the same silence the cluster is looking at.

   Record the evidence in the incident channel BEFORE step 2. Name it.

2. FENCE IT. Make the assumption true rather than relying on it.
   Do at least one, ideally more:
     - revoke the other site's credentials to the shared datastore;
     - stop its instances through the provider API;
     - withdraw its routes / drop the BGP session;
     - revoke its storage access.
   If you cannot fence it, escalate. Do not proceed on hope.

3. SNAPSHOT FIRST. Take a backup of the surviving site's data now, before
   forcing anything. Reconciliation later will need the pre-override state.

4. FORCE QUORUM on the survivor.  <exact command for your system>

5. VERIFY: one leader, epoch advanced, writes flowing, and the fenced site
   is confirmed still fenced.

6. PAGE the service owner. This is not a silent operation.

7. BEFORE RECONNECTING the other site: it must be wiped and rebuilt from the
   survivor, never rejoined. A node holding a divergent history that rejoins
   is how a recovered incident becomes a second incident.
```

The two steps people skip are 2 and 7. Step 2 is what converts a guess into a fact.
Step 7 is what prevents the healing problem (11.11) from turning a resolved outage
into a data-reconciliation project.

**7.4 — The JVM with 4-second pauses.**

**Currently guaranteed:** nothing useful. Your observed worst-case pause of 4 s sits
inside your 5 s detector limit with only 1 s of margin, which means an ordinary bad
garbage collection is roughly one bad afternoon away from triggering a failover. And
the 10 s lease means that when it does, the woken leader may still consider its lease
valid for up to 6 s after a replacement has been elected — a 6-second window of two
believed leaders. If nothing fences the resource, that is a 6-second window of
possible corruption.

**What is not guaranteed:** that a stale leader's writes are refused. Nothing in this
setup refuses anything.

**What I would change, in order:**

1. **Add fencing at the resource.** This is first because it removes the timing
   question entirely. With a fenced resource, no combination of pause length, lease
   length and detector limit can corrupt data — the worst case becomes wasted work and
   a refused write. Everything else on this list is then a tuning problem rather than a
   correctness one.
2. **Then measure the pause distribution properly** — a pause monitor, not an
   inference from GC logs — and set the detector limit to something like 3× the
   observed p99.9. Not to make it safe (fencing did that) but to stop needless
   failovers.
3. **Then attack the pauses**: heap sizing, a low-pause collector, or moving the
   coordination role off the JVM that is doing the heavy allocation.
4. **Consider a watchdog** so a node that stalls past its lease is restarted rather
   than resumed.

The ordering is the answer. Every instinct says "tune the timeouts first"; the lab's
point is that timeout tuning buys you *fewer* incidents and fencing buys you
*harmless* ones. Do the one that changes the category.

---

## Rung 8 — Design

These are discussions, not answers. What follows is one defensible design each; yours
may differ and still be right if it survives the same questions.

### 8.1 — The payments control plane

**The requirement, restated in this lab's terms:** the invariant is "a batch is
submitted at most once". Late is fine; twice is not. So this is a correctness lock, not
an efficiency lock, and by the
[distributed-locking](../distributed-locking/) lab's rule the guarantee must live at
the resource — which here means at the banking partner, or at the closest thing to it
you control.

**Coordination state.** A three-region etcd or Consul cluster, one voter per region,
five voters if you want to survive a voter loss during a region outage (2/2/1 across
three regions). Never an even number, and never a layout where one region holds a
blocking share. At 50 ms between regions, expect roughly 100 ms per consensus write —
fine for a control plane that makes a decision every few minutes, and a good argument
for keeping the *data* plane out of it.

**How a submitter proves authority.** It campaigns for a leadership key with a lease.
On winning, it gets a revision number — etcd's `mod_revision` — which is its epoch.
That number is durable and monotonic because etcd made it so; do not mint your own
(rung 6.2).

**What the banking partner must do.** Ideally: honour an idempotency key on batch
submission, scoped to the batch, so a duplicate is recognised and discarded. Ask for
this explicitly during integration; it is a normal request and most partners support
it.

**If it will not** — and this is common — then you build the check yourself as close to
the partner as you can get:

- A submission-record table in a database you control, with a unique constraint on
  `batch_id`, written in the *same transaction* as the record of the submission.
- The write is conditional on the epoch: `... WHERE current_epoch <= :myEpoch`,
  which fences it.
- Order of operations: claim the batch (fenced, atomic) → submit → record the outcome.
  A crash between claim and submit leaves a claimed-but-unsubmitted batch, which is a
  reconciliation case, not a duplicate.
- Reconcile continuously against the partner's own transaction list. This is the only
  thing that catches a genuine duplicate, and it must run always, not only after
  incidents.

**One region isolated.** It cannot reach a majority, so it never wins leadership, so it
never submits. Its application tier should fail fast with a clear error rather than
queueing silently. Explicitly: do **not** let the isolated region "queue up batches to
submit when it reconnects" without re-checking authority — that is a zombie with a
backlog.

**The coordination cluster unavailable.** Everything stops submitting. This is correct
and you should say so in the design document, because someone will propose a fallback
and the fallback is how you get double submissions. If the business cannot tolerate
that, the answer is more coordination-cluster availability, not less coordination.

**Monitoring.** Leadership changes per hour (should be near zero); the epoch/revision
over time (flat with steps, never a ramp); fenced-write rejections (each one is a
prevented duplicate); reconciliation diffs against the partner (should be zero, and
this is the metric that actually proves the design works); time since last successful
lease renewal per instance; and coordination-cluster quorum health.

**Runbook.** The break-glass procedure from 7.3, plus one specific to this domain:
what to do when reconciliation finds a duplicate. That path must exist before you need
it.

**What survives from this lab, and what changes.** Survives: everything — quorum for
election, lease for expiry, fencing for effects, odd voters across odd failure
domains, monitoring on election rate. Changes: the resource is now a third party you
do not control, so the effect gate has to be relocated into a database you do control,
sitting immediately in front of the call, and backed by continuous reconciliation
because that gate is not *actually* at the point of effect. That relocation is the
design's weakest joint and you should say so out loud.

### 8.2 — The retail edge

**The first move is to notice that this is not a consensus problem.** Two machines, no
third, a link that fails several times a week, and a hard requirement to keep
operating while offline. Every tool in this lab requires a majority, and two machines
cannot produce one. If you try to force a majority-based design onto this, you will
end up with the two-node trap of stage 11.3 — a total outage every time the WAN blinks
— and then somebody will add an override, and then you will have split brain by policy.

**So change the frame.** The shop must keep taking payments while partitioned, which
means you have chosen **AP** — availability over consistency — and the honest thing is
to say so and design for it deliberately rather than discover it during an incident.
That immediately tells you what you need: not a lock, but a data model where
concurrent activity in two places cannot conflict.

**What that looks like:**

- **Make the shop's writes append-only and independently identified.** A card payment
  is an *event*, not an update to a shared counter. Two terminals recording two
  different payments is not a conflict; it is two facts. Give each transaction a
  globally unique id generated at the terminal (store id + terminal id + local
  sequence), so nothing needs coordination to be unique.
- **Give the two shop machines disjoint id space**, so they cannot collide even with
  no communication at all. This is the CRDT insight from the [crdts](../crdts/) lab
  applied at the smallest possible scale: design the data so that merging is trivial,
  and you never need to decide anything.
- **Queue locally and forward on reconnect.** The shop's durable local queue is the
  guarantee; the WAN is an optimisation.
- **Head office deduplicates on the transaction id at ingest.** Idempotent ingestion is
  what makes replaying the queue safe, and it is what makes the reconnection safe.

**What you give up:** any invariant that requires a global view *at the time of the
payment*. You cannot enforce a real-time global credit limit, a global fraud counter,
or "this card has already been used at another store in the last minute". Those
require coordination and coordination is exactly what is unavailable. You handle them
after the fact — detection and reversal rather than prevention — and you accept a
bounded exposure, sized by (offline duration) × (transaction rate).

**What you keep:** every payment is durably recorded before the customer is told it
succeeded, and every payment reaches head office exactly once. Those are the two that
matter commercially.

**Where a lock still belongs.** If the two shop machines *do* share something exclusive
— a cash drawer, a printer, a till-reconciliation job — use a lease between them for
efficiency, and make the effect idempotent so that a split brain costs a duplicate
receipt rather than a duplicate refund. That is the efficiency-versus-correctness
distinction, applied per resource rather than per system.

### 8.3 — Reviewing a home-grown leader election

Ask these in order. Each one's bad answer tells you something specific about their
data.

1. **"What decides that the current leader is dead?"**
   *Bad answer:* "if we can't ping it" / "if the file lock is gone". Means the failure
   detector is a bare timeout with no analysis of what stalls the process, and nobody
   has thought about false positives.

2. **"How long can your process stall without being dead?"**
   *Bad answer:* "it doesn't stall" or a number they are guessing. If they cannot
   quote a measured p99.9 pause, their timeout is unrelated to reality and their
   system will split-brain on the first bad afternoon.

3. **"What must a candidate collect before it may lead?"**
   *Bad answer:* "it takes over when the other one stops". No quorum: this is v1 from
   stage 10, and it produces split brain on every partition.

4. **"Does an existing leader ever stand down on its own?"**
   *Bad answer:* "it doesn't need to, it has the lock". No lease: this is stage 11.2,
   which produces two leaders even *with* a quorum. This is the most commonly missing
   piece and the one reviewers skip.

5. **"What number does a write carry, and who checks it?"**
   *Bad answer:* silence, or "the lock guarantees it". No fencing. Everything upstream
   is now a timing bet, and their data has no protection at all against a stale leader.

6. **"Where does that number come from, and does it survive a restart?"**
   *Bad answer:* "we increment a counter in the service". Rung 6.2 — an outage
   waiting for a deploy, and possibly worse.

7. **"Can the shared filesystem refuse a write from a stale owner?"**
   For their specific case — renaming files on a network filesystem — this is the
   decisive question, and the answer is almost certainly **no**. A POSIX-ish network
   filesystem will happily let two machines rename the same file. That means there is
   no effect gate available, which means their invariant cannot be enforced by any
   lock they build.

   What follows from that is the useful part of the review: they need either
   (a) an idempotent design where a duplicate rename is harmless — for example naming
   the target deterministically from the source content so two workers doing the same
   work produce the same result, or (b) a real fencing point, such as staging the
   operation through a database row with a conditional update that records the
   intent before the filesystem is touched, or (c) NFSv4 with server-enforced locks if
   their storage supports it, or (d) self-fencing via a watchdog.

8. **"How many voters, and across how many failure domains?"**
   *Bad answer:* an even number, or all in one rack. Stage 11.3 and 6.4.

9. **"What does your override do, and who can press it?"**
   *Bad answer:* "there's a flag". Stage 11.10 is the most expensive failure in the
   lab and it is always a human one.

10. **"What is your alarm on election rate?"**
    *Bad answer:* "we don't have one". They will not find out about churn until it
    becomes corruption.

The shape of a good review here: questions 1–2 establish how wrong the detector can
be, 3–4 establish whether authority is bounded, 5–7 establish whether anything
actually enforces the invariant, and 8–10 establish whether they will notice. If
question 7 comes back "no" — which for a network filesystem it will — then questions
1 through 6 are all secondary, because no amount of careful election protects a
resource that cannot refuse.
