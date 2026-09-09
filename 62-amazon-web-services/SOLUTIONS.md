# Solutions — Amazon Web Services

Attempt the exercises first. For rungs 7–8 there is no single right answer;
what follows is a worked position with the reasoning visible, and the wrong
answers named.

---

# Rung 1 — Explain

## 1.1
Renting changed *who owns the metal* and *how the bill arrives*. It did not
change the two things that actually cost Asha the festival:

1. **Capacity stopped being a purchase and became a setting.** With the hosting
   company, more capacity was an order with a human and a lead time of hours or
   days, in units of one whole machine for one whole month. On AWS it is a
   message that returns in a minute, and the meter stops when she gives it
   back. Demand that changes in minutes can now be matched by supply that
   changes in minutes.
2. **The failure boundaries became visible and contractual.** The hosting
   company could not tell her whether her two machines shared a rack, a power
   feed or a switch. AWS names those boundaries — availability zones — commits
   in writing that they do not share power, cooling or a network path, and puts
   the choice on the screen. That is what turns "two machines" into "two
   chances".

*(A third, honest answer: she can now rent finished distributed systems — an
object store, a replicated database, a queue — instead of building them. But
that is a consequence of the first two, not a separate idea.)*

## 1.2
An availability zone is one independently-powered wing of a region: one or more
data centres with their own power, own cooling and own network, kilometres away
from the other zones, joined to them by a fast private link.

It exists because **redundancy is a count of independent things that can fail,
not a count of machines.** Two servers in one rack, behind one switch, on one
power feed, are one server with extra steps — when the feed dies, both die.
Your two servers are only two chances if something guarantees they do not share
the thing that kills them. The zone is that guarantee, written down.

So "we have two servers" is not the question. "Are they in two zones?" is.

## 1.3
- **(a)** Durability is whether the bytes still exist. Availability is whether
  you can reach them right now. S3's famous eleven nines is durability; its
  availability SLA is 99.9%, six orders of magnitude weaker, and they are
  measuring different things.
- **(b)** Replication copies your data — including your mistakes — immediately
  and perfectly to another place. A backup is a *point in time* you can return
  to. Replication protects you from infrastructure; only a backup protects you
  from yourself.
- **(c)** The control plane changes your setup (create an instance, modify a
  table). The data plane uses it (serve a request, read an object). They fail
  separately, the control plane fails more often and more widely, and data
  planes are built to keep serving from their last known configuration when it
  does.

## 1.4
Two errors, and a third worth mentioning.

**Error 1 — an SLA is a refund, not a promise.** Missing 99.99% obliges AWS to
credit a percentage of that service's bill. It does not oblige reality to
cooperate. It is a bound on your *compensation*, not on your *downtime*.

**Error 2 — the number belongs to one component, and your system is a chain.**
If the request path touches an ALB, compute, a database and a queue, and each
is independently 99.95%, the path is `0.9995⁴ ≈ 99.80%` — roughly 17 hours a
year, from four components that each promised under five. Availability
multiplies downward. You do not get the best number in your architecture; you
get something worse than the worst one.

**Error 3 (the one that catches people in practice)** — the 99.99% EC2 figure
describes a deployment across multiple zones. If everything is in one zone, as
in §10.3's version 2, that number was never yours to quote. And none of these
figures cover *your* bugs, *your* deploys, or *your* misconfiguration, which is
where the great majority of real downtime comes from.

---

# Rung 2 — Identify

## 2.1
**Inside a zone:** application instances, the database primary and standby, the
NAT gateway, the subnets. These are AZ-scoped resources — they die when their
zone dies, and placing them is your job.

**Outside a zone:** the load balancer, S3, DynamoDB, and the control plane box.

- **ALB, S3, DynamoDB** are drawn outside because they are *region-scoped*:
  AWS runs them across zones for you, and there is no screen on which you
  choose their zone. Losing one zone does not lose them.
- **The control plane** is drawn outside and above because that is exactly
  where it lives conceptually: it is regional, it is the thing you ask for new
  resources, and — the whole point of the drawing — it fails on its own
  schedule, independently of the zones below it.

That layout is the lesson. If a box is in a column, you placed it and you own
its failure. If it is above the columns, AWS placed it.

## 2.2
The panel names the zone (`ap-south-1a` in v3) and shows `standby: none`. In
v4 it shows a primary in one zone and a standby in another, with the
replication marked synchronous. The give-away that v3 is dangerous is that a
single box carrying all the state sits in one column.

## 2.3
The connection line from the load balancer to that instance. A live line means
the target is in rotation; a dimmed or cut line means it has been removed. The
instance's own colour tells you *health*; the line tells you *routing*, and the
gap between the two — an unhealthy instance that is still receiving traffic — is
the health-check detection window from F1.

## 2.4
Changed from v3 to v4: the database gains a **standby in a second zone** with
synchronous replication (§10.5a), and the **static stability** control becomes
meaningful (§10.6). The ASG and the three-zone spread were already there from
v3 (§10.3). Nothing about the application tier changes — which is itself the
point: version 4 is entirely a story about state.

---

# Rung 3 — Predict

## 3.1
**Success rate: unchanged at ~100%. Load balancer: healthy.** Killing a zone
that contains none of your resources does nothing to you. The instructive part
is what this proves: *an AZ failure is only your problem to the extent you had
something in it*, which is exactly why the placement decision is the whole
game. It also shows why "AWS had an AZ event" and "we had an outage" are
different sentences.

## 3.2
- **Kill `1c`:** roughly two thirds capacity immediately, recovering as the ASG
  replaces the lost instance. Payments keep working.
- **Kill `1a`:** **everything fails**, and slowly.

The same *number* of things died — one zone, one instance. But `1a` held the
single database. The application servers in `1b` and `1c` are perfectly
healthy; they accept requests, try to reach a database that is gone, and block
until their connection times out. So shops get 30-second waits ending in 500s,
which is worse for them than a fast failure.

The lesson is §10.4's: **elasticity is a property of the stateless tier and
buys you nothing while state has a single home.** Spreading nine application
servers across three zones is decoration if the one thing they all need is in
one.

## 3.3
**Success rate stays at roughly two thirds — about 660 of 1,000 requests/sec —
and stays there.** The system is stuck on `RunInstances`, which returns
capacity errors and is retried with backoff.

The precise shape: the ALB does its job (data plane, works fine) and stops
routing to the dead zone within about 30 seconds. The database fails over
(data plane-ish — the standby already exists). Then recovery stops, because the
remaining step needs the control plane to give the ASG four new machines, and
the control plane is exactly what is unavailable. Nothing is broken. Nothing is
retrying wrongly. The design simply asked for something that is not being
handed out tonight.

## 3.4
**Success rate: ~100%, essentially immediately.**

What physically changed: **you were already running 50% more machines than you
needed, so losing a third left you with exactly enough.** The `RunInstances`
call still fails — that has not changed at all. It no longer matters, because
nothing was waiting on it; recovery consisted entirely of the load balancer
*stopping* sending traffic to dead targets, which is a data-plane action that
needs no one's permission.

That is static stability in one sentence: **the response to a failure is to
keep doing what you were already doing with what you already have.**

## 3.5
**Nothing goes wrong, and that is the trap.** A single-AZ database at festival
traffic performs fine — arguably marginally better, since there is no
synchronous cross-AZ write to wait for. The design is now one zone failure away
from total loss and every dashboard is green.

This is the general shape of an availability bug: it is invisible until the
fault arrives. It is why game days exist, and why "it's been fine for a year"
is not evidence about a design's failure behaviour.

---

# Rung 4 — Modify

## 4.1
**Availability is lost. Durability, correctness and elasticity are untouched.**

The data is still safe (it is in a region-scoped store or on EBS with
snapshots), answers are still right, and the ASG still scales perfectly well.
The system simply now has a single failure domain containing 100% of the
application tier. It will scale beautifully right up until the zone dies, at
which point it will serve nothing.

Worth noticing: three of the four properties are fine, so three quarters of
your monitoring will say everything is excellent.

## 4.2
**One system recovers by using capacity it already has; the other recovers by
asking for capacity it does not have.**

That is the whole difference, and it does not appear on any architecture
diagram — which is precisely why F3 keeps catching well-designed systems. Two
identical boxes-and-arrows drawings, opposite outcomes, and the distinguishing
property is a number (how much headroom) rather than a shape.

## 4.3
**Elasticity is lost.** Correctness, durability and availability-under-failure
are all intact; the system just cannot get bigger. Festival traffic arrives,
the ASG dutifully scales to its maximum, and the maximum is the normal-traffic
number.

The reason this deserves its own exercise: **nothing is red.** There is no
failed health check, no exception, no alarm. There is a line in the ASG
activity history saying it wanted to scale and could not. This is the same
failure shape as F4, and it is the argument for alarming on *headroom* rather
than on errors.

## 4.4
**Nothing changes.** A quota is only a problem when it is below what you need,
and — the useful corollary — **you cannot tell whether a quota is a problem by
looking at it.** You can only tell by comparing it to your peak requirement,
in that region, including the requirement your *failover* plan would create.

That is why F4's production advice is to enumerate the quotas your recovery
path needs in every region you would fail over to. A quota that is generous in
your primary region and default in your DR region is a plan that works in
tests and fails in reality.

## 4.5
Synchronous cross-region writes make **every write** slower by at least one
cross-region round trip; asynchronous ones do not slow writes at all but
introduce a window of possible data loss.

The number from the README: Mumbai to Ireland is roughly **120ms one way**, so
a consensus round trip is ~240ms and a realistic protocol needs more than one.
A payment API with a 400ms budget has spent all of it before doing any work.
Against that, asynchronous replication with sub-second lag adds **0ms** to
writes and costs you an **RPO of about one second** — up to a second of
committed payments that a sudden region loss would take with it.

There is no third option; this is PACELC's "else" branch with the actual speed
of light substituted in. Which you choose is a business decision about whether
one second of lost payments is worse than half a second added to every payment.

---

# Rung 5 — Trace

## 5.1 — the fast clock
```
Step 1  CREDENTIALS   ok - role supplies temporary credentials
Step 2  ENDPOINT      ok - dynamodb.ap-south-1.amazonaws.com resolves
Step 3  SIGNING       the request is signed with a timestamp 12 minutes ahead
Step 4  TRANSPORT     ok - the request reaches AWS
Step 5  AUTHORIZATION FAILS. The service rejects the signature because the
                      request timestamp is outside the permitted skew window
                      (a few minutes). Error: SignatureDoesNotMatch or an
                      InvalidSignatureException naming the time difference.
```
**How it looks in application logs:** like a permissions problem, or like
"AWS is broken". The message says the signature did not match, which reads as
*wrong credentials* to anyone who has not met this before — so the team spends
an hour rotating keys and re-checking IAM policies for a fault that lives in
NTP.

Two things make it worse: it affects **every** AWS call from that host, for
every service, simultaneously, which looks like a platform-wide outage from one
machine's perspective; and if it is one host out of twelve behind a load
balancer, it presents as an intermittent failure rate of about 1/12 with no
pattern in the request data. The tell is that the failure is per-host, not
per-request-type.

## 5.2 — the missing permission
```
Steps 1-4  ok
Step 5     AUTHORIZATION - evaluate PutItem on table/charges
             SCP                 -> pass
             identity policy     -> GetItem allowed; PutItem NOT allowed
             explicit deny?      -> none, but there is also no allow
             default             -> DENY
           FAILS. AccessDeniedException.
Steps 6-7  never reached.
```
**Database work done: zero milliseconds.** The request never reached the
storage layer, the partition, or any replica.

**What that implies:** the failure is instant, total and unambiguous — 100% of
writes, from the first request after the deploy, with an error message that
names the action and the resource. You will find it in ninety seconds. This is
F7's "too narrow" flavour, and it is the *good* kind of IAM failure precisely
because deny-by-default makes it loud. The dangerous flavour is a policy that
is too wide, which produces no signal at all, ever, until something else goes
wrong.

## 5.3 — the frozen control plane, static stability OFF
Capacity: 12 instances × 100 rps = 1,200 rps. Festival demand: 1,000 rps.

| Time | Instances serving | Capacity | Shed |
|---|---|---|---|
| 18:04:30 | 8 (4 in `1b` failing health checks, removed) | 800 rps | ~200 rps |
| 18:05:02 | 8 (`RunInstances` returned InsufficientInstanceCapacity) | 800 rps | ~200 rps |
| 18:11:00 | 8 (still retrying with backoff) | 800 rps | ~200 rps |

Roughly **20% of payments failing, continuously, for as long as the event
lasts.** Note that the numbers do not change across the three timestamps —
that is the signature of F3. A normal incident's graph moves; this one is flat,
because the recovery mechanism is not slow, it is *blocked*.

## 5.4 — the same, static stability ON
Capacity: 18 instances × 100 rps = 1,800 rps. Festival demand: 1,000 rps.

| Time | Instances serving | Capacity | Shed |
|---|---|---|---|
| 18:04:30 | 12 (6 in `1b` removed) | 1,200 rps | **0** |
| 18:05:02 | 12 (`RunInstances` still failing — irrelevant) | 1,200 rps | **0** |
| 18:11:00 | 12 | 1,200 rps | **0** |

**Cost ratio: 18/12 = 1.5, i.e. 50% more compute spend** on the application
tier, permanently, for every hour of every day.

Say the trade in the form a business can answer: *"We can pay 50% more for
compute, or we can accept roughly 20% of payments failing for the duration of
any zone event."* That is the real decision, and it belongs to someone with a
revenue number, not to the engineer holding the Terraform.

Note the design rule that generalises: with `N` zones, static stability means
any `N−1` of them must carry 100% of peak. At `N=3` that is 50%
overprovisioning; at `N=4` it is 33%; at `N=2` it is 100% — which is the real
reason three zones is the standard and two is a trap.

---

# Rung 6 — Spot the flaw

## 6.1 — the design that passes every review
**The flaw: backups retained 7 days, and no mention of where they live or of a
restore ever having been performed.**

Everything in that description defends against *infrastructure* failure, and it
defends well — the game day proved it. Nothing in it defends against F8: a bad
migration, a wrong `WHERE` clause, a compromised credential issuing legitimate
delete calls. Aurora's six copies will replicate the damage to all six.

**A concrete run:** a migration on a Friday drops a column that a rarely-used
reconciliation report depends on. Nobody notices, because the report runs
monthly. On day 9 finance runs it, it fails, and the data needed to reconstruct
the column is now beyond the 7-day window. The system was 100% available
throughout.

**The guarantee violated:** none of AWS's — every one held. The violated
assumption is the customer's, and it is §7.5's: *replication is not backup, and
"managed" never moved the question "is my data correct?"*

**Why the game day could not find it:** the game day tested a failure mode that
was designed for. The retention window is a bet about *detection time*, and no
infrastructure fault exercises it.

**What else is thin, secondarily:** min 9 / max 30 with three zones means
losing a zone leaves 6 instances and requires launching 3 more — F3 territory.
And there is no mention of the backups living in a separate account, so a
credential compromise that can delete the database can probably delete the
backups too.

## 6.2 — the disaster recovery plan
**Reason 1 (§7.4 — quotas are per-region).** The `eu-west-1` account has never
run production. Its vCPU quota, its RDS instance quotas, its Elastic IP quota
are all at defaults. `terraform apply` will get partway through and start
returning limit errors, and a quota increase is a support ticket with a human
on the other end, measured in hours to days. Your one-hour RTO is now a
multi-day RTO, discovered during the incident.

**Reason 2 (a plan is not a rehearsal).** Restoring an RDS snapshot is not
instant — for a database of meaningful size it is tens of minutes on its own,
before the DNS change, before connection pools recover (F11), before caches
warm. Nobody in the plan has ever measured it. "One hour" is an aspiration
someone typed, and the first measurement of it will be taken under maximum
pressure with an audience.

**The generalisable rule:** an untested RTO is not a number, it is a wish. The
minimum credible version of that plan includes a restore rehearsal on a
calendar and the measured duration written next to the target.

*(A third, if you found it: snapshots every 5 minutes is not what an RPO of 5
minutes requires — you also need the copy to `eu-west-1` to have completed,
which adds its own lag. The true RPO is snapshot interval **plus** copy time.)*

## 6.3 — the idempotency table
**Flaw 1 (F5 — the partition key is a hot key by construction).** Partition key
`date` means **every charge in the entire system on a given day shares one
partition key**. That is not skew; that is a design that guarantees a single
partition takes 100% of write traffic. It will hit the per-partition write
ceiling and throttle, and because the SDK retries with backoff, the first
symptom will be p99 latency on the payment path rather than errors.

**The fix** is the sub-key splitting from your distributed-rate-limiting lab:
make the partition key the `charge_id` itself (naturally uniform), or if you
need date-range queries, `date#<shard 0..N>` with the charge id hashed into the
shard.

**Flaw 2 — "exactly-once payments" is the wrong claim.** A conditional write
gives you *at-most-once execution of the effect for a given key*, which is
idempotency, and that is genuinely valuable. It does not give exactly-once,
because the step *after* the conditional write — actually calling the bank —
can still fail after the key is claimed, leaving a key that says "done" for a
charge that never happened. Real designs store the *outcome* against the key
and make the whole sequence resumable, so a retry of a half-finished charge
completes it rather than skipping it.

The correct sentence is: *"duplicate requests do not produce duplicate
charges"*, not *"exactly once"*. Exactly-once delivery does not exist; exactly-
once *effect* is something you construct.

## 6.4 — the health check
**The run:** the database gets slow — not dead, slow. Say a bad query plan
after a statistics refresh doubles latency.

1. `SELECT 1` starts taking longer than the health check timeout on some
   instances.
2. The ALB marks those instances unhealthy and removes them.
3. Remaining traffic concentrates on fewer instances, which now issue *more*
   queries each to the already-struggling database.
4. Those instances' health checks start failing too.
5. The ASG terminates the "unhealthy" instances and launches replacements —
   which boot, connect to the same slow database, fail their first health
   checks, and are terminated in turn.
6. Every instance is now unhealthy, the target group is empty, and the ALB
   returns 503 to everything.

A dependency that was **degraded** has become a **total outage**, and the
mechanism was the recovery system. The database was never down.

**The rule:** a health check answers "should this instance receive traffic?",
not "is the whole system well?". Make it shallow — process alive, can accept a
connection — and detect dependency problems with monitoring and circuit
breakers, which degrade rather than amputate. A deep health check converts a
partial degradation into a fleet-wide, self-inflicted outage, and it does so
fastest exactly when the dependency is under most stress.

## 6.5 — the permission
Two things, at least.

**One (data):** `s3:*` includes `s3:DeleteObject` and `s3:PutObject`. The
attacker can not only read every receipt, they can **delete or overwrite them
all**, and `dynamodb:*` includes `dynamodb:DeleteTable`. The author was
thinking about read access and wrote a wildcard that authorises destruction.
This is F8 with a hostile operator.

**Two (not about data at all):** `dynamodb:*` includes
`dynamodb:UpdateTable` — the attacker can raise provisioned capacity, and on
other services the equivalent wildcard authorises creating expensive resources.
More generally, wildcards routinely include the *management* actions of a
service, not just its data actions: changing replication, disabling
point-in-time recovery, altering encryption configuration, removing a resource
policy. Turning off PITR before deleting is the difference between an incident
and an extinction event.

**Three, if you want it:** the reasoning "it's scoped to two resources so the
blast radius is small" measures blast radius in *resources* when it should
measure in *actions*. `s3:GetObject` on one bucket and `s3:*` on one bucket are
the same resource scope and wildly different blast radii.

---

# Rung 7 — Handle failure

## 7.1 — making retries harmless
**What the client sends:** an idempotency key it generates once per *logical
charge attempt* and reuses across every retry of that attempt — typically a
UUID in a header. The crucial property is that the key is generated **before**
the first send and does not change on retry; a key generated per HTTP request
is not an idempotency key, it is a request id.

**What the server stores, and where:** a record keyed by the idempotency key,
in a store that is strongly consistent and supports conditional writes.
DynamoDB with a conditional put is the natural AWS answer; the row holds the
key, a state (`in_progress` / `succeeded` / `failed`), the request fingerprint
(a hash of the important request fields), the resulting charge id, and a TTL.

**The conditional write, conceptually:** *"insert this row only if no row with
this key exists"* — DynamoDB's `attribute_not_exists(idempotency_key)`. Exactly
one writer wins.

**Two simultaneous retries:** one wins the conditional write and proceeds. The
other gets the condition failure, reads the existing row, and finds state
`in_progress`. It must **not** proceed and must **not** return success. The
correct response is to wait briefly and re-read, and if still in progress,
return a retryable status (409, or 202 with a location to poll) so the client
tries again shortly. Returning success for work that has not finished is the
subtle bug here.

**A request fingerprint matters:** if the same key arrives with a *different*
amount, that is a client bug or an attack, and the right answer is a 422, not
a silent replay of the original.

**When the idempotency store is unavailable — fail open or closed?**
For a payments API: **fail closed.** Reject the charge with a retryable error.
The reasoning is asymmetric cost. Fail-open means processing charges with no
duplicate protection, during an incident, which is exactly when clients are
retrying most aggressively — the failure mode is double-charging real customers
with real money, which is a regulatory and trust problem you cannot undo with
a deploy. Fail-closed means some payments do not happen for a few minutes,
which is revenue you recover when the shop retries.

Note this is the *opposite* of the usual advice for a rate limiter, where
fail-open is often right — and it is the same reasoning both times: ask what
the wrong answer costs. This is the "fail-open/fail-closed chosen per limit,
never once for the whole system" rule from your rate-limiting lab, applied to a
different resource.

## 7.2 — surviving F3 without 50% overprovisioning
**Option A — pre-provisioned warm capacity that is already running but not
serving.** Keep instances launched and registered but weighted to near-zero
traffic, or in a second target group. On failure, shift weight — a data-plane
action. Cost: you are still paying for the machines, so this is 50%
overprovisioning wearing a different hat *unless* you use the spare capacity
for something interruptible (batch, analytics) that you are willing to evict.
Then the marginal cost is genuinely lower, and the price is operational
complexity plus the risk that the batch workload does not actually yield fast
enough.

**Option B — four zones instead of three.** With `N=4`, surviving `N−1`
requires only 33% overprovisioning rather than 50%. Cost: not all regions have
four usable zones, and every cross-AZ chat now has more paths (F9's transfer
charges). This is the cheapest option where it is available and it is
underused.

**Option C — degrade instead of overprovision.** Accept that a zone loss means
reduced capacity, and make the reduction *graceful* rather than random: shed
low-value traffic first (reporting, analytics, non-critical merchants), keep
the payment path whole. Cost: you must have built prioritised load shedding,
which is real work and must be tested; and you have accepted that some
customers get a worse experience during the event. This is often the *right*
answer and it is the least fashionable, because it requires admitting in
advance which traffic matters less.

**Option D, mentioned to reject it:** "use a different instance type as a
fallback in the ASG's mixed-instances policy". This genuinely helps — capacity
shortages are usually per-instance-type — and it costs almost nothing, so do
it. But it is a *probability* improvement, not a structural one: it makes
`RunInstances` more likely to succeed rather than making you independent of it.
Take it as a cheap supplement, never as the plan.

## 7.3 — why the app stays broken after failover
Layers holding the stale endpoint, outermost first:

1. **JVM DNS cache.** `networkaddress.cache.ttl` — historically the JVM cached
   successful lookups **forever** under a security manager. Set it to something
   like 5–30 seconds for any application that depends on DNS failover.
2. **The OS / local resolver / any caching resolver in between**, each with its
   own TTL, none of which respect your intentions.
3. **The connection pool.** This is the big one. Even with perfect DNS, the
   pool holds *established TCP connections* to the old address. It will happily
   hand out a socket to a machine that is gone; `isConnected()` returns true
   until something writes. Fix: a validation query on borrow (or at minimum on
   idle), a bounded max connection lifetime so connections are recycled
   routinely, and fast failure detection so a broken connection is discarded
   rather than retried.
4. **Socket-level timeouts.** Without a read timeout, a thread blocked on a
   dead peer waits for the OS TCP retransmission timeout — minutes. Set an
   explicit read timeout.
5. **The application's own retry policy.** Retries against a cached bad address
   just multiply the damage. Retries should re-resolve.

The meta-point, from your networking lab: **DNS failover updates a name, and
your process resolved that name a long time ago.** Every layer between the name
and the socket is a place the old answer can hide. And the only way to find out
which of the five layers is biting you is to trigger a failover on purpose,
which RDS lets you do on demand.

## 7.4 — the guardrail against the honest mistake
The constraint is the interesting part: the person had legitimate access and
made an honest error, and must keep being able to do their job. So "remove
their access" and "be more careful" are both non-answers.

A layered answer, cheapest first:

1. **Make destruction reversible by default.** S3 versioning on anything
   irreplaceable — a delete becomes a delete *marker*, and the object is still
   there. Point-in-time recovery on databases with a retention window chosen
   against *detection* time (how long until someone notices?), not against a
   default. This is the single highest-value item and it is a checkbox.
2. **Make destruction slower than a keystroke.** Deletion protection on
   production databases and tables; `prevent_destroy` in Terraform. Neither
   stops a determined operator; both stop an accidental one, which is who we
   are defending against.
3. **Put the backups where production's credentials cannot reach them.** A
   separate account, with a role that production cannot assume. This is the one
   that also defends against a compromised credential, and it is the reason the
   account boundary is the cheapest blast-radius control AWS sells.
4. **Make the dangerous action visible before it happens.** Migrations that
   drop data go through a review that specifically asks "is this reversible?";
   a Terraform plan showing a destroy is a blocking review, not a scroll-past.
5. **Change the shape of the action.** Two-phase deletes: rename or mark
   `deprecated_` and wait a fortnight before dropping. This one costs almost
   nothing and catches the largest class of these mistakes, because the honest
   error is nearly always about *something else still using it*.

Note what the list has in common: none of them add a human check to the moment
of the action, because humans are unreliable at exactly that moment. They all
either make the action reversible, slow, or contained.

---

# Rung 8 — Design

## 8.1 — Marigold goes multi-region

**Region pairing.** `ap-south-1` (Mumbai) primary, `ap-south-2` (Hyderabad)
secondary. Reasoning: Marigold's customers are all in India, and Indian
payments data is subject to data-localisation expectations — putting the
secondary in Ireland would be a regulatory conversation before it is a
technical one. Same-country pairing also keeps the inter-region round trip in
the tens of milliseconds rather than 240ms, which makes some designs viable
that would not be transcontinentally. *(Verify current region availability and
the applicable regulatory position — this is a design assumption, not a fact I
am asserting.)*

**Where the write path lives: one region at a time. Active-passive.** Every
write goes to Mumbai. Hyderabad runs the full stack, warm, serving nothing (or
serving reads only). This is the position to defend hardest, because
"active-active" sounds better in a board slide and is the wrong answer here.

**Why not active-active:** the `charges` table is money. Multi-active
replication resolves conflicts, and every available resolution — last-writer-
wins in DynamoDB Global Tables, or anything else automatic — can silently
discard one of two concurrent writes. There is no acceptable version of
"silently discarded" for a payment. To do active-active safely you would need
partitioned ownership (each merchant's writes always go to one region, so there
are no concurrent writes to the same row) — which is achievable, is what
sophisticated shops actually do, and is a much bigger programme than the board
is currently authorising. State that as the upgrade path, not as v1.

**RPO and RTO, as numbers.**
- **RPO: 0 for the authorisation record, ~1 second for everything else.**
  The trick that makes this affordable: not all data has the same value. The
  `charges` table gets synchronous cross-region durability on the *decision
  record only* — a small, append-only write — accepting the added latency on
  the one write that must never be lost. Everything else (merchant profiles,
  settlement staging, receipts in S3, analytics) replicates asynchronously with
  ~1s lag, RPO 1s. If even that is too expensive at Marigold's latency budget,
  the fallback is RPO ~1s for everything **plus** a reconciliation process
  against the bank's own records, which is the answer the payments industry has
  actually used for decades: the bank is a second source of truth, and you
  reconcile.
- **RTO: 15 minutes**, achieved by having Hyderabad already running with
  sufficient capacity (static stability across regions, not just zones) so
  failover is a promotion plus a traffic shift, not a build.

**How failover is triggered, and by whom: a human, with a runbook, on a
documented decision.** Not automatic. Automatic cross-region failover is a
machine that can start a split brain over money on the basis of a network
blip, and the blast radius of a false positive is larger than the cost of ten
minutes of human latency. The runbook must assume the console is unusable and
must not require any `us-east-1` control-plane call it can avoid; Route 53
health-check-based failover between existing endpoints keeps working, so
pre-create the records and the health checks rather than planning to edit DNS
during the incident.

**What I refuse to do, and why:**
- **Refuse synchronous cross-region for the whole write path** — the latency
  budget cannot absorb it and it makes the *normal* case worse to improve a
  rare one.
- **Refuse last-writer-wins on money.**
- **Refuse automatic failover** (above).
- **Refuse to call it done before a live failover test.** An untested failover
  is a hypothesis. Schedule it, quarterly, in business hours, with the board
  informed.

**The sentence for the board:** *"Surviving the loss of a region roughly
doubles our infrastructure bill and adds a class of data-consistency risk we
do not have today. I recommend we buy it, because a full-day outage would cost
us more than that in a single day and would probably cost us the licence
conversation as well — but I want us to agree the number we are protecting
before we spend it, and I want a quarterly test in the budget, because an
untested failover is not protection."*

## 8.2 — the account structure

**The accounts, each justified by blast radius:**

| Account | Why it exists |
|---|---|
| **Management / root** | Owns the Organization and nothing else. No workloads. Its credentials are the keys to everything, so it should have almost no reason to be logged into |
| **Security / audit** | Centralised logs (CloudTrail, config) that other accounts can write to and **not delete**. Survives a compromise of any workload account |
| **Log archive / backups** | Backup copies that production's credentials cannot reach — this is 7.4 item 3, made structural |
| **Production** | The workload. Its quotas, its IAM, its blast radius, alone |
| **Staging** | Separate *specifically* because quotas are per-account: a load test here must not be able to consume production's capacity headroom (§7.8) |
| **Development / sandbox** | Per-team or per-engineer, with hard spend guardrails. People learn by doing dangerous things; give them somewhere it is safe |
| **Shared services** | Networking, CI/CD, artefact registries, DNS. Things production depends on but should not own |

**Three SCP guardrails first:**
1. **Region restriction** — deny all actions outside the regions you actually
   use. Prevents both cost surprises in unwatched regions and a whole class of
   crypto-mining abuse, and it is one policy.
2. **Deny disabling security controls** — no principal in any workload account
   may stop CloudTrail, delete log buckets, disable GuardDuty or turn off
   default encryption. This is the guardrail that survives a compromised admin,
   because *explicit deny cannot be overridden by any allow* (§7.6).
3. **Deny root user actions** — the account root user should never be used for
   day-to-day anything; deny its use outside a break-glass procedure.

**The thing that will actually be hard, and it is not technical:** *ownership
and the migration of shared resources.* One VPC, one database, one S3 bucket
that four teams use, all created before anyone thought about accounts, all with
IAM policies naming principals that will cease to exist. Deciding which account
owns each shared thing means deciding which team owns it, and that is an
organisational negotiation that will take longer than the Terraform. Start
there, not with the accounts. The usual accelerant is to create the new
accounts, put all *new* workloads in them from day one, and migrate the old
estate opportunistically — a big-bang migration of a live estate is rarely
worth it.

## 8.3 — choosing the compute

Traffic: 200 rps average, 2,000 rps peak for 40 minutes twice a day. p99 400ms.
JVM start 6 seconds.

**The case for ECS Fargate (my choice).** The 10× peak is real but *predictable
and scheduled*, which means scheduled scaling handles it without any cold-start
question at all — scale out 10 minutes before each peak. Fargate removes the
instance fleet without removing the container model, so the 6-second JVM start
happens at scale-out time, not on the request path. No Kubernetes to operate at
40 people. The 400ms p99 budget is comfortable for a warm JVM.

**The case against my own choice** — and it is a real case:
- **Fargate costs meaningfully more per unit of compute than EC2.** At a
  sustained 200 rps baseline running 24/7, that premium is paid every hour of
  every day for the convenience of not managing instances. EC2 with an ASG and
  a Savings Plan for the baseline plus Spot for the peak could be substantially
  cheaper, and at sufficient scale that difference funds an engineer.
- **If the company is already running Kubernetes elsewhere**, adding a second
  orchestration model is a real cost — one deployment pipeline, one set of
  runbooks, one thing to learn is worth a lot, and consistency can beat local
  optimality.
- **Fargate's task startup is slower than an already-warm EC2 host**, so an
  *unpredicted* spike — a marketing email, an outage at a competitor — is
  handled less gracefully than by a statically-stable EC2 fleet. Since we
  learned in §10.6 that headroom beats scaling, the design that carries
  headroom is the more robust one, and headroom is cheaper on EC2.

**Lambda, rejected with reasons:** 200 rps sustained is not a spiky workload,
it is a steady one — that is the regime where per-request pricing loses to
per-hour pricing. And a 6-second Spring Boot cold start against a 400ms p99
budget means you are buying provisioned concurrency, which is Lambda pretending
to be a server at a worse price. Lambda is right for the *event glue* around
this service, not for the request path.

## 8.4 — the bill doubled

**Investigation, in order, and why this order.**

1. **Cost Explorer, grouped by service, daily granularity, last 60 days.** This
   takes two minutes and answers the only question that matters first: *is it
   one thing or everything?* A step change on one date in one service is a
   different investigation from a gradual rise across many.
2. **Then group by the same service, by usage type.** "EC2" doubling could be
   instance hours or it could be data transfer, and those have entirely
   different causes. This step is where most investigations actually end.
3. **Then by region.** Spend appearing in a region nobody uses is either a
   forgotten resource or a security incident, and you want to know which within
   the hour.
4. **Then by cost-allocation tag / account.** This is where having mandated
   tags pays for itself: with them, "which team?" is a filter. Without them,
   you are correlating resource ids by hand for a week — which is itself the
   finding, and the recommendation writes itself.
5. **Then correlate the step date against CloudTrail** for creation events, and
   against the deploy history. "No deploy anyone remembers" is not evidence;
   CloudTrail is.

**The three most likely patterns, from F9:**
- **Data transfer, not compute.** Cross-AZ chatter (a service moved, or a new
  replica landed in a different zone, and now a hot path crosses a zone
  boundary on every call), NAT Gateway per-GB processing, or internet egress
  from something newly chatty. This is the most common invisible doubling
  because no resource was created — a path just changed.
- **Something that scaled and never scaled back.** An auto-scaling group whose
  scale-in policy is broken or whose cooldown is wrong; a DynamoDB table
  switched to on-demand during an incident and never switched back; a log
  volume increase (a debug log level left on in production is a classic, and
  it shows up as CloudWatch Logs ingestion, which people forget is metered).
- **An abandoned resource.** An idle cluster, unattached EBS volumes, old
  snapshots, an environment spun up for a test in a region nobody watches. This
  is the boring one and it is very often the answer.

**The fix that outlasts the investigation:** mandatory tagging enforced by SCP,
a budget alert per account, and an anomaly-detection alert. The goal is that
the next time this happens you find out on day two, not on the invoice.

## 8.5 — reading a real postmortem

*(This is a reading exercise; the value is in doing it. What follows is what to
look for, not a substitute for reading the source.)*

**Which failure mode:** both the November 2020 `us-east-1` Kinesis event and
the December 2021 `us-east-1` event map primarily to **F3 — control plane
unavailable while data planes are substantially healthier** — with strong
elements of **F6** (the `us-east-1` global-service dependency, since services
whose control planes concentrate there were affected far outside that region's
workloads) and of the *dependency cascade* pattern in **F10** (one internal
service's degradation propagating to the many services that depended on it).

**Which customer designs were unaffected:** ones that were already running
enough capacity, already had their configuration in place, and needed nothing
new from AWS during the event. Statically stable designs (§10.6) had a quiet
night. Designs whose recovery, deployment, or scaling depended on control-plane
calls did not — and notably, so did designs whose *incident response tooling*
depended on the console.

**The sentence that maps to §7.3:** look for the paragraph distinguishing what
kept serving from what could not be changed — the postmortems are usually
explicit that existing resources continued operating while creation, scaling
and describe operations were impaired. That sentence is §7.3 in AWS's own
words, and it is the justification for everything in §10.6.

**A worthwhile extra question to ask yourself while reading:** how long was it
between customer impact starting and the status page reflecting it? That gap is
why the personalised AWS Health Dashboard and your own monitoring matter more
than the public page.
