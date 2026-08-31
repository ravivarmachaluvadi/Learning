# Solutions — Terraform

Attempt first. Rungs 7–8 are worked positions with the reasoning visible, not
single right answers — but there are wrong answers, and they are named.

---

# Rung 1 — Explain

## 1.1 Why a state file is unavoidable
Two independent reasons, and it is worth giving both because people usually
only know one.

**Reason one: without a record, Terraform cannot delete anything.** You remove
the cache-cluster block from your config. Comparing config to cloud, the cache
cluster is "in the cloud, not in the config" — but so is the database another
team owns, the bucket your CI system made, and the default VPC that came with
the account. "Delete everything not in my config" is the most destructive
command anyone has ever written. The record is what makes deletion safe, because
it contains *only things Terraform created*, so "in the record, not in the
config" is a precise and small set.

**Reason two: matching by name is a lie.** To compare config to cloud, Terraform
would have to find "the load balancer belonging to `aws_lb.app`" by some
attribute. Every candidate fails: most resource types have no unique name; tags
are mutable and unenforced, so two teams can tag two objects identically; and if
you *rename* something in your config, name-matching finds nothing and creates a
second copy rather than recognising the first.

Both are the same missing thing — a mapping from *your* stable name to the
cloud's identifier, which the cloud never knew about because it has never seen
your file.

## 1.2 The three states
- **Desired** — what the configuration files say should exist.
- **Recorded** — what Terraform believes it created, with a full attribute
  snapshot: the state file.
- **Actual** — what is really in the cloud right now.

**The question only recorded-vs-actual can answer: "did something change behind
my back?"** That is drift. Desired-vs-recorded tells you what *you* changed;
only recorded-vs-actual tells you what *someone else* changed. This is why a
two-way tool is blind to the 2am fix and a three-way tool is not.

## 1.3
- **(a)** A plan is the computed difference between the three states, expressed
  as an action per resource address, printed before anything happens.
- **(b)** `+`, `~` and `-` each do one thing. `-/+` does **two** — it destroys
  and then creates — so it is the only symbol under which an object with data
  in it can silently become a different, empty object. It is also caused by the
  *provider*, not by anything you asked for, which is why it appears in plans
  people did not expect it in.
- **(c)** Drift is *reality* moving away from the record without Terraform doing
  it. A config change is *intent* moving. They appear in the same plan output in
  the same notation, and telling them apart is a review skill, not a tool
  feature.

## 1.4
**First error: "idempotent" describes the outcome of applying, not the safety of
applying.** An apply converges reality to your configuration — which is exactly
the problem when reality contains a deliberate emergency change that the
configuration does not know about. F1: running apply is how the 2am fix gets
reverted. Idempotence guarantees you land on the config; it says nothing about
whether the config is currently what you want.

**Second error: the guarantee has four conditions** (§12.1) and "safe to run
whenever" assumes all of them hold. In particular the apply is not atomic
(§10.6) and the plan is a prediction (§10.8), so "run it and see" can leave you
in a partial state that is worse than either endpoint — for instance a security
group created before the rules that were going to constrain it.

---

# Rung 2 — Identify

## 2.1
Left is **desired** (config), middle is **recorded** (state file), right is
**actual** (cloud).

Terraform **writes** only the middle one. It writes to the cloud too, of
course — but it writes the *cloud* by making API calls that it hopes succeed,
and it writes the *record* as its own authoritative bookkeeping. It never writes
the config: you do. That asymmetry is why config lives in git and state does
not, and why editing state by hand is a last resort.

## 2.2
`aws_lb.app` shows `idle_timeout` in all three columns. Green means all three
agree; amber means desired differs from recorded (you asked for a change); red
means recorded differs from actual (drift — someone else changed it). The
distinction between amber and red is the whole point of the three columns.

## 2.3
The hand-made security group appears in Cloud with no State row. **Terraform
does nothing with it, ever.** That protects you from the reason-one catastrophe
in 1.1: a tool that acted on everything it could see, in a shared account, would
delete other teams' infrastructure. The cost of the protection is F5 — the
resource is invisible, unmanaged and billed, and your configuration is no longer
a description of your infrastructure.

## 2.4
The address is `aws_db_instance.main`; the AWS identifier is something like
`marigold-prod-db` / an ARN. **You** chose the address; **AWS** chose the
identifier. Only the address appears in git — which is precisely why it must be
stable, and precisely why §10.7's renumbering is so destructive.

---

# Rung 3 — Predict

## 3.1
`No changes. Your infrastructure matches the configuration.` — and it still
makes **one read API call per managed resource**, because the refresh happens
first (§10.4). This is why a plan on an 800-resource root module is slow even
when the answer is "nothing", and it is one of the two arguments for splitting
root modules (F10).

## 3.2
**No, the plans are different, and this is the most useful thing in rung 3.**

- *You edit the config to 90:* desired 90, recorded 60, actual 60. Plan:
  `~ idle_timeout: 60 -> 90`. One change, which you asked for.
- *Someone edits the console to 90:* desired 60, recorded 60, actual 90. The
  refresh pulls 90 into the record, then desired (60) differs from it. Plan:
  `~ idle_timeout: 90 -> 60` — Terraform proposes to change it **back**.

Same end value in reality, opposite plans. The lesson: **the plan's direction is
determined by the config, always.** Terraform has no concept of "the newest
change wins"; the configuration is the definition of correct, and everything
else is something to be corrected.

## 3.3
- *Deleted in the console:* recorded says it exists, actual says it does not,
  desired says it should. The refresh removes it from the record; the diff then
  finds a config block with no record entry. Plan: `+ create`. Terraform rebuilds
  it.
- *Deleted from your config:* recorded says it exists, actual says it exists,
  desired does not mention it. Plan: `- destroy`.

Opposite actions from what looks like the same event, because the *source* of
the disappearance is what determines the meaning. The record is what lets
Terraform tell those two situations apart at all.

## 3.4 — the centrepiece
**With `count`: 4 destroyed, 3 created.** One deletion you asked for, and three
resources destroyed and recreated for nothing. The reason is in the address
table in §10.7 — removing element 1 renumbers 2→1, 3→2, 4→3, so three addresses
now point at objects whose attributes no longer match, and `cidr_block` is
immutable, so each is a replacement.

**With `for_each`: 1 destroyed, 0 created.**

If you predicted "1 destroyed" for the `count` case, you have just experienced
the exact failure this section exists for — and so has everyone else. The number
is not intuitive because nothing in the configuration *looks* positional; the
positionality is in the record, which you do not normally read.

The connection to name: this is [consistent-hashing](../consistent-hashing/)'s
opening bug. Identity as a function of a global property (position in a list)
versus identity as a function of the thing itself (a key). That lab measured 99%
of keys moving on a 100→101 machine change where 1% had to; here it is 80% of
resources disturbed where 20% had to. Same shape, and here the objects being
destroyed are not cache entries.

## 3.5
The cloud has **both** changes — the timeout is 90 and the subnet exists. The
**record** has only one of them, because the second writer computed its update
from a copy of the record read before the first writer saved. The **config** is
fine; both changes are in git.

So: reality is right, intent is right, and the bookkeeping is wrong. The next
plan will propose to "fix" whichever change got lost — reverting a timeout
nobody touched, or creating a subnet that already exists (which will fail with
`already exists` from the provider, and is the *lucky* version).

This is the lost update from
[optimistic-vs-pessimistic-locking](../optimistic-vs-pessimistic-locking/), and
that lab's central trap applies here too: **nothing errors.** Both applies said
"Apply complete!".

## 3.6
**With a saved plan (`plan -out=tfplan` then `apply tfplan`)**, Terraform checks
that the state's serial has not moved and refuses a stale plan. But a console
edit does not move the state serial — it moves the *cloud*. So the saved plan is
still considered valid, and the apply proceeds against a world that has changed.
Depending on what was edited, the apply may succeed and quietly overwrite the
console change, or fail with a provider error about an unexpected current value.

**Without a saved plan** (`terraform apply` typed at a prompt), Terraform
re-plans from scratch and shows you the *new* plan for confirmation — which is
safer in this specific respect and much worse in the respect that matters in
CI, because the thing a human approved and the thing that runs are then two
different computations (F7).

The production rule follows: in CI, always apply a saved plan, and keep the gap
between plan and apply short.

---

# Rung 4 — Modify

## 4.1
**Lost: the trustworthiness of the record.** **Not damaged: the cloud.** Both
changes really happened and both are correct in reality. Nothing is broken in
production at the moment of the failure — which is exactly why it goes
unnoticed. The damage surfaces later, when someone applies a plan computed from
the false record and it undoes real work.

Worth noticing which property survived: **convergence**, in the sense that each
individual apply did converge its own resource. What was lost is the record's
role as a *shared* description, which is requirement 5 from stage 5.

## 4.2
At the moment of failure: resources 1–3 exist in the cloud **and** are in the
record; resource 4 does not exist; 5–7 were never attempted.

The second plan is shorter because **the record is accurate** — Terraform knows
resources 1–3 already exist and are correct, so it proposes only 4–7. This is
the property that makes forward recovery work, and it is why the record being
written incrementally during an apply (rather than only at the end) is a
deliberate and important design choice. If the record were written only on
success, a failed apply would leave three orphans (F5) every time.

## 4.3
`Plan: 47 to add, 0 to change, 0 to destroy.`

**Lost: the mapping — your ability to manage what you own.** **Not lost: the
infrastructure, which is untouched and still serving traffic, and the
configuration, which is still in git.** Say both halves out loud, because the
panic of this moment suggests the first sentence without the second, and people
have made it much worse by acting on the panic — the truly catastrophic version
of F3 is applying that plan and building a second copy of everything alongside
the first.

## 4.4
The apply is refused, and — the important part — **it is refused at plan time,
before anything happens**:
`Error: Instance cannot be destroyed … Resource has lifecycle.prevent_destroy set`.

So the control fires at the earliest possible moment, in CI, on the pull
request, without a human needing to notice a `-/+` in 340 lines. That placement
is what makes it valuable: it converts F6 from "a reviewer must be careful" into
"the pipeline fails".

## 4.5
The lock is now held for every change to anything, so two teams cannot ship
concurrently; and the plan refreshes all 900 resources, so it takes minutes.

**Degraded: throughput and blast-radius containment.** **Not degraded:
correctness** — a giant root module produces correct plans and correct applies.
That is what makes F10 insidious: there is no bug to find, no error to search
for, and no moment at which anyone decides the architecture is wrong. It just
gets slower and scarier until someone names it.

---

# Rung 5 — Trace

## 5.1
```
aws_vpc.main
  desired 10.0.0.0/16 = recorded = actual                     -> no action

aws_subnet.a
  recorded says it exists; actual says it does not
  -> refresh REMOVES it from the record
  -> desired has a block with no record entry
                                                              -> + create

aws_lb.app
  desired 90, recorded 60, actual 60 (no drift)
  idle_timeout is updatable in place                          -> ~ update in-place

aws_s3_bucket.logs
  in the record, in the cloud, NOT in the config              -> - destroy

aws_db_instance.main
  desired az=ap-south-1b, recorded/actual az=ap-south-1a
  availability zone is immutable                              -> -/+ REPLACE
                                                                 (this is F6:
                                                                  the production
                                                                  database)

sg-0999
  in the cloud, not in the record                             -> ignored entirely
```
`Plan: 2 to add, 1 to change, 2 to destroy.` — and the two most important facts
about that line are that the database replacement is hiding inside it, and that
the hand-made security group is not mentioned at all.

## 5.2
```
serial 12  {lb.idle_timeout: 60}
Asha reads  -> her copy: serial 12, {lb: 60}
Ravi reads  -> his copy:  serial 12, {lb: 60}
Ravi applies (creates subnet_d in AWS), writes
           -> serial 13, {lb: 60, subnet_d: subnet-0ddd}
Asha applies (sets timeout 90 in AWS), writes from HER copy
           -> serial 13 or 14 depending on backend, contents {lb: 90}
              subnet_d is GONE from the record
```
Reality: timeout 90, subnet_d exists. Record: timeout 90, no subnet_d.

**The next plan proposes `+ create aws_subnet.private["d"]`** — and it is wrong,
because the subnet already exists. Best case the provider refuses with
`already exists` and someone investigates. Worst case the resource type permits
duplicates and you now have two subnets, one of which nothing manages (F5).

## 5.3 — reordering with no additions or removals
```
[0] a -> a   no change
[1] b -> c   -/+ replace
[2] c -> b   -/+ replace
[3] d -> d   no change
[4] e -> e   no change
```
**Two resources destroyed and recreated to change nothing at all.** The set of
values is identical; only the order changed.

Same phenomenon as 3.4 in one sentence: **with `count`, identity is position, so
any operation that changes positions changes identities** — and insertion,
deletion and reordering all change positions. `for_each` is immune to all three
because a key does not care where it sits.

This is also why sorting a list, or switching to a data source that returns AZs
in a different order, can produce a destructive plan out of a change that
touched nothing.

## 5.4
Graph: `vpc → {subnet_a, subnet_b} → sg → lb → asg`. Failure at the 4th
operation (`sg`).

At the moment of failure the record contains: `vpc` (id recorded),
`subnet_a` (recorded), `subnet_b` (recorded). It does **not** contain `sg`,
`lb` or `asg` — `sg` failed, and `lb`/`asg` were never attempted because they
depend on it.

The next run's plan: `Plan: 3 to add, 0 to change, 0 to destroy` — `sg`, `lb`,
`asg`. The first three are absent from the plan entirely, which is the record
doing its job.

One subtlety worth stating: `subnet_a` and `subnet_b` have no edge between them,
so they ran in parallel. If the failure had been in one of *them*, the other may
or may not have completed — parallel operations do not stop instantly — so the
record after a failure can contain a resource whose sibling is missing.

---

# Rung 6 — Spot the flaw

## 6.1 — the tidy repository
**The flaw: one root module for all of production** (F10). Everything else
listed is genuinely good practice.

**The symptom they will report, in about six months:** *"Terraform is slow"* and
*"we keep waiting for each other"*. A plan refreshes every resource in the state
(§10.4), so plan time grows linearly with the estate; and the state lock is held
per root module, so a one-line tag change on a service blocks a networking
change. Neither presents as a correctness problem, which is why it is never
prioritised — it presents as the tool being annoying, and teams respond by
running plans less often, which is the actual damage.

**The second-order harm, which is worse:** a plan that takes eight minutes and
touches 900 resources will not be read. F1 and F6 both depend on someone reading
the plan, and this design guarantees nobody does.

**The fix** is §13.3: split by rate of change and blast radius, pass values
between layers explicitly.

## 6.2 — the Tuesday afternoon
**What the plan contained:** a `-/+ aws_db_instance.main (forces replacement)`
line among 340 lines of `~ tags`.

**What caused it:** almost certainly *not* the tag. The tag change is what
brought the resource into the plan; the replacement came from something else in
that resource's configuration that had drifted from, or never matched, reality —
or from a provider version upgrade that changed how an attribute is handled.
A resource that has not been in a plan for a year can carry a latent
replacement that only surfaces the next time anything touches it. **This is why
"it's only a tag change" is not a safe sentence.**

**Three controls, each sufficient alone:**
1. **`lifecycle { prevent_destroy = true }` on the database.** The plan fails in
   CI with an explicit error. Cheapest, most effective, and should have been
   there from day one.
2. **A CI rule that fails the build when the plan contains `forces replacement`
   on any resource in a protected list**, requiring an explicit override label
   on the PR. Mechanical, and it makes intent visible.
3. **Splitting `data/` into its own root module** (§13.3). A tagging PR against
   `services/` then *cannot* produce a plan that touches the database, because
   the database is not in that state.

Note what is *not* on the list: "review more carefully". The reviewer behaved
reasonably; 340-line plans are not reviewable, and a control that depends on
human vigilance at the end of a long day is not a control.

## 6.3 — the clean-up
**What it achieved:** the plans are clean. **What it destroyed:** the
configuration is no longer a description of the infrastructure.

`ignore_changes = all` tells Terraform to stop comparing desired to actual for
those resources. The record still exists, so Terraform still owns them and will
still destroy them if you remove the blocks — but it will never *correct* them,
and it will never *report* that they differ. **The desired state is now
unmanaged**: what is in git and what is in production have no enforced
relationship at all.

**The next disaster recovery attempt produces the infrastructure as *written*,
not as it actually ran.** Every console change made over two years — every
widened rule, every raised timeout, every added subnet — is absent, silently,
and nobody knows which ones mattered. The team believed they had a tested
recovery path; what they have is a configuration that has diverged from
production by an unknown amount in an unknown direction.

The legitimate use of `ignore_changes` is narrow and specific: name the exact
attributes that something else legitimately owns (`desired_capacity` on an
autoscaling group, a tag applied by a compliance system). `= all` is the
difference between a scalpel and switching off the lights.

## 6.4 — the refactor
**14 real resources are destroyed and 14 new ones created.** Subnets, security
groups and anything with data in it are all replaced. If the module contained a
database, the data is gone.

The engineer's reasoning — "the resources are identical either way" — is true
about the *configuration* and irrelevant. The record is keyed by address
(§12.4's second invariant), the addresses all changed, and Terraform therefore
sees 14 records with no config and 14 configs with no record.

**What they should have written:** `moved` blocks, one per resource, telling
Terraform the address changed without the object changing:

```hcl
moved {
  from = aws_subnet.private["app_a"]
  to   = module.network.aws_subnet.private["app_a"]
}
```

The plan then reads `Plan: 0 to add, 0 to change, 0 to destroy` with a note that
14 resources have moved. `moved` blocks are reviewable, are applied
automatically by everyone who pulls the branch, and cannot be forgotten — which
is why they are better than the older `terraform state mv` run by hand.

**The generalisable rule: every refactor that changes an address is a data
migration until proven otherwise.**

## 6.5 — the disaster recovery drill
**Reason one, from this lab:** applying a configuration successfully creates
*empty* infrastructure. The drill proves the configuration is valid and the
quotas are sufficient — genuinely valuable — and proves nothing about the thing
that actually takes the time, which is restoring the data. The 45-minute figure
almost certainly measures `terraform apply` and excludes a database restore that
is measured in hours for anything large.

**Reason two, from the [amazon-web-services](../amazon-web-services/) lab (F4):**
if the drill has been *succeeding* every quarter, the resources it creates are
presumably destroyed afterwards — so the quota headroom exists. But if the drill
runs at a smaller scale than production (a common shortcut, since nobody wants
to pay for a full second copy quarterly), then the quotas that the *real*
recovery needs have never been exercised, and quotas are per account per region.
The drill would then pass forever and the real event would fail on limits.

**A third, if you found it:** nothing in the drill tests the *state* for the
recovery region — where it lives, whether it survived the event, and whether the
team can acquire a lock on it. If the DR state file lives in the region that
just died, the recovery plan begins with a problem nobody rehearsed.

---

# Rung 7 — Handle failure

## 7.1 — the deleted state file
**First, and before anything else: stop all pipelines and tell everyone not to
run Terraform.** The single worst outcome available right now is someone
applying the `47 to add` plan and building a duplicate of production alongside
it. That is a bigger incident than the one you have.

**Then, in order:**
1. **Check for a copy before assuming there is none.** Bucket versioning (not
   enabled here, but check), the CI job artefacts of the last successful apply,
   a `terraform.tfstate.backup` next to a local run, or a developer's laptop
   that ran a plan recently and has a cached copy. Recovering a state file that
   is a week old is enormously better than starting from zero — you re-import
   only the difference.
2. **Confirm the infrastructure is untouched.** It is; say so out loud to the
   people who are panicking, because the recovery is a bookkeeping exercise, not
   an outage.
3. **Rebuild the record by importing**, layer by layer, starting with the things
   nothing depends on (the VPC, then subnets, then security groups, then the
   rest). Use `import` blocks so the work is reviewable and repeatable rather
   than 47 shell commands typed by one person.
4. **After each import batch, run a plan and expect it to be noisy.** Hand-made
   and imported resources rarely match what the configuration says, and each
   difference is a decision: change the config to match reality, or change
   reality to match the config. **Do not blanket-apply.** Resolve them
   individually.
5. **Only after the plan is empty** is the recovery complete.

**Realistic cost:** days for 47 resources, and it is fiddly rather than hard.

**The ten-minute prevention:** remote state in S3, with **versioning** and a
bucket policy that denies deletion, plus MFA-delete on anything you truly cannot
lose. Versioning alone would have turned this entire incident into restoring the
previous object version. This is the cheapest insurance in the tool.

## 7.2 — mechanical prevention of the replaced database
**The control:** in CI, after `terraform plan -out=tfplan`, convert it to JSON
(`terraform show -json tfplan`) and fail the build if any resource whose type or
address is on a protected list has an action of `["delete", "create"]` or
`["delete"]`.

**Why this shape:**
- **Mechanical** — it reads the plan's structured output, not its prose, so a
  340-line plan is exactly as safe as a 4-line one.
- **Does not block ordinary changes** — a plan with only `~` and `+` actions on
  protected resources passes untouched, which is the overwhelming majority.
- **Allows intentional replacement** — the override is a labelled pull request
  (`allow-replace: aws_db_instance.main`) requiring a second approver. Making
  the override *explicit, named and reviewed* is the point; making it impossible
  would just push people to bypass the pipeline.

**Belt and braces:** `prevent_destroy = true` on the resource as well. The two
controls fail differently — `prevent_destroy` fails at plan time in Terraform
itself and cannot be bypassed by a CI misconfiguration; the CI rule catches
resources nobody remembered to annotate. Neither subsumes the other.

**What not to do:** requiring two approvers on all infrastructure PRs. It slows
every change to catch a rare one, and approval fatigue makes the second reviewer
less careful than the first, not more.

## 7.3 — emergency changes without permanent drift
The constraint is the interesting part: they must be able to act *now*, and the
guarantee must come from a mechanism rather than a promise.

**The process:**
1. **Break-glass access exists and is easy.** A role that grants console write
   access in production, assumable by on-call, with no approval step. If it is
   hard to use, people will find another way and you will lose the audit trail
   entirely — that is the failure mode to design against first.
2. **Assuming that role automatically opens a ticket** and posts to the incident
   channel. Not a request for permission; a record that it happened. The
   mechanism, not the person, creates the record.
3. **A scheduled `-refresh-only` run detects the drift** and comments on that
   ticket with the exact diff — the specific attributes that differ from the
   configuration. Now the change is described precisely, by a machine, rather
   than from someone's memory of a 4am hour.
4. **The ticket cannot be closed until the drift is gone**, and there are
   exactly two ways to make it gone: adopt the change into the configuration, or
   revert it. Both are pull requests. This is the guarantee, and it is
   mechanical because the drift report keeps reappearing until it is true.
5. **Escalate on age.** Drift older than, say, 72 hours pages the team that owns
   the resource. Without this the ticket rots and you are back where you started
   with extra process.

**The insight to name:** you cannot prevent emergency changes and should not
try. What you can do is make the *record* of them automatic and make the drift
*self-reporting*, so the only human judgement required is the one that actually
needs judgement — adopt or revert.

## 7.4 — the cancelled apply
**Possible states, and they are genuinely different:**
1. **Terraform caught the signal, finished the in-flight operation, wrote the
   state and released the lock.** Best case, and reasonably likely — Terraform
   handles interrupts gracefully when it can.
2. **Terraform was killed hard.** Resources may have been created in the cloud
   whose IDs never reached the record → orphans (F5). The lock may still be
   held.
3. **The state write itself was interrupted** → a truncated or corrupt state
   file (F2's worst case).
4. **In-flight cloud operations continued after the process died.** An RDS
   instance being created does not stop because your CI job did; it will finish,
   exist, and be unrecorded.

**How to determine which:**
- **Check the lock first.** If it is still held, nothing else is running, and
  you have time. `force-unlock` only after you have *confirmed* the process is
  dead — [distributed-locking](../distributed-locking/) established that slow
  and dead are indistinguishable from outside, and force-unlocking a slow holder
  gives you two concurrent applies with your blessing.
- **Read the CI log** to see the last resource Terraform reported completing.
  That is your boundary between "recorded" and "maybe orphaned".
- **Validate the state file parses** and its serial looks sane. Compare against
  the previous object version in the bucket.
- **Run a `plan`.** A plan proposing to create something you saw succeed in the
  log is the signature of an orphan.

**What to do in each case:** (1) re-run. (2) find the orphans against the cloud
console or a cost report, then either import them or delete them by hand; then
re-run. (3) restore the previous state version from the bucket, then treat as
(2). (4) wait for the operation to finish before doing anything, then treat as
(2).

**The prevention:** make applies non-cancellable in CI, or accept that
cancelling one is an incident and write that in the runbook. And enable bucket
versioning, which is the answer to case 3 and appears in almost every solution
in this file.

---

# Rung 8 — Design

## 8.1 — the state architecture
**The split, on two axes.**

*Primary axis — environment and account.* `prod`, `staging`, `dev`, each in a
**separate AWS account** (straight from the AWS lab's §7.8), each with its own
backend bucket in its own account. This contains F2, F3 and F10 across the
largest boundary, and it makes a catastrophic mistake in staging structurally
incapable of reaching production.

*Secondary axis — rate of change.* Within each environment:

| Root module | Contains | Changes | Contains which failure |
|---|---|---|---|
| `network/` | VPC, subnets, routing, transit | monthly | F10 — a deploy cannot lock or touch the network |
| `data/` | RDS, S3, DynamoDB. `prevent_destroy` on everything | rarely | F6 — a tagging PR cannot reach a database |
| `platform/` | IAM baseline, KMS, logging, shared roles | rarely | F7 — security changes reviewed separately |
| `services/<name>/` | ASG, ALB, ECS per service | hourly | F2 — teams do not queue behind each other |

*Regional axis.* Each of the above exists per region, with its own state. Not a
workspace: a directory, so the two regions can legitimately differ (and they
will — see the AWS lab's §10.7 on multi-region being a different kind of
problem). **The DR region's state lives in the DR region**, which is the
oversight in 6.5.

That is roughly `3 environments × 2 regions × (3 + N services)` root modules.
It sounds like a lot; each is small, fast to plan, and independently safe.

**How values cross:** outputs published to Parameter Store / Terraform Cloud
outputs, and consumed as **explicit inputs** by the layer below, injected by the
pipeline. Not `terraform_remote_state` data sources reaching into another
layer's file — that recreates the coupling you split to remove and adds a
read dependency on a file the owning team believes is private.

**The boundary I refuse to draw: per-resource or per-team state inside a single
service.** Splitting until each root module holds three resources sounds like
maximum containment and is actually the worst outcome: the plumbing between
modules becomes larger than the modules, a single logical change becomes four
coordinated applies in a required order — which is a distributed transaction you
now have to run by hand, with no coordinator and no rollback (your
[two-phase-commit](../two-phase-commit/) lab, arriving somewhere it was not
invited). **The right granularity is the largest unit that one team can deploy
atomically**, and no smaller.

## 8.2 — adopting an existing estate
**First principle: a half-managed estate is genuinely worse than an unmanaged
one**, because nobody can tell which half is which. So the adoption strategy is
organised around never being ambiguous about ownership.

**Order of adoption, and the reasoning:**
1. **New things first.** From day one, everything new is created by Terraform,
   in a new account or a clearly-labelled boundary. This is the highest-value,
   lowest-risk step and it stops the problem growing while you fix it.
2. **Then the stable foundation — networking and IAM.** These change rarely, so
   an import is unlikely to be invalidated while you are doing it, and almost
   everything else depends on them.
3. **Then stateless compute**, service by service, with each team importing its
   own. Stateless resources are the safest to get wrong: a bad plan destroys and
   recreates something that recreates cleanly.
4. **Data stores last, and with `prevent_destroy` written before the import.**
   These are the ones where a mistaken plan is unrecoverable, and by this point
   the team has practised.

**Avoiding ambiguity:** a mandatory tag (`managed_by = terraform`) applied by
every module, and a scheduled report listing every resource in the account
*without* that tag. That report is the adoption backlog, it is generated rather
than maintained, and it goes to zero visibly.

**What I would deliberately never import:** anything created and owned by
another tool that has its own reconciler — resources managed by a Kubernetes
operator, by a vendor's control plane, by CloudFormation stacks somebody else
owns. Two reconcilers pointed at one object is a fight neither wins, and each
will report drift caused by the other, forever.

**"Done" and the timeline:** done is when the untagged report is empty *and* a
plan against every root module is clean. Honestly: 6–18 months of part-time work
for three years of estate, and the schedule risk is not the importing — it is
the negotiation about which team owns each shared resource, which is exactly the
finding from the AWS lab's account-structure exercise.

## 8.3 — Terraform or CloudFormation
**My choice: Terraform**, for a specific reason rather than popularity — the
team will not stay AWS-only. Cloudflare DNS, GitHub repositories, Datadog
monitors, PagerDuty schedules and an identity provider are all things a
six-person team acquires within two years, and having *one* reconciler model for
all of them is worth more than anything CloudFormation offers. The plan/apply
loop is also materially faster to work with, which compounds over thousands of
iterations.

**The strongest case against my own choice**, and it is a real case:
- **CloudFormation removes F2 and F3 completely.** There is no state file, so it
  cannot be lost, corrupted, or written by two people at once; AWS holds it
  inside the service. For a six-person team with no platform engineer, deleting
  two entire failure classes is a serious offer, and every solution in this file
  that ends in "enable bucket versioning" is a problem they simply do not have.
- **It removes the secrets-in-state problem (F9)** in the same stroke.
- **It is free**, has no licensing question hanging over it (§13.7), and needs
  no external backend infrastructure to exist before you can start.
- Against that: CloudFormation's rollback behaviour can leave stacks in states
  requiring manual rescue — a *different* failure from Terraform's forward-only
  model, not obviously a better one — and drift detection is weaker.

**The honest summary:** Terraform trades two removed failure classes for
portability and iteration speed. At six people and AWS-only *today*, that is a
closer call than the industry's enthusiasm suggests, and a team that chose
CloudFormation for those reasons has not made a mistake.

## 8.4 — applying automatically on merge
**What must be true for this to be safe:**
1. **The plan that was reviewed is the plan that runs** — saved artefact, never
   a re-plan (F7). Without this, auto-apply means nobody has reviewed anything.
2. **Mechanical destroy protection** — 7.2's control, plus `prevent_destroy` on
   everything stateful. With no human at the apply step, the last line of
   defence is gone.
3. **Small blast radius per root module** (§13.3), so an unattended mistake is
   bounded by construction.
4. **Fast, reliable rollback-by-roll-forward** — revert the PR, which produces a
   plan that returns the previous state. This works for stateless resources and
   **does not work at all** for anything that was destroyed.
5. **Alerting on apply failures** that reaches a human quickly, because F4's
   partial state is now nobody's problem by default.

**Which failures get worse:** F1 (drift is silently reverted with no human in
the loop at all), F4 (a partial apply happens unattended), F6 (no last-second
human catch). **Which get better:** F2 — the pipeline is the only actor, so
concurrent applies stop existing; and F5 — nobody is clicking in consoles when
merging is easier.

**Would I do it? Per layer, differently, and that is the interesting part:**
- **`services/`** — yes. Changes are frequent, resources are stateless,
  roll-forward works, and the friction of manual applies is a real tax paid
  hourly. This is where auto-apply earns its keep.
- **`network/`** — no. Changes are rare, so the friction costs almost nothing;
  and a bad network change is a total outage that a revert does not fix quickly.
- **`data/`** — absolutely not. The failure mode is unrecoverable, the change
  rate is near zero, and "a human definitely looked at this" is worth far more
  than the convenience it costs.

**The generalisable rule:** automate the apply where the change rate is high and
the failure is recoverable; keep a human where the change rate is low and the
failure is not. Notice this is the same axis as §13.3's state split, which is
not a coincidence — both are asking "what is the blast radius here?".

## 8.5 — reading a real plan
The point of this exercise is the last question, so here is the guidance rather
than an answer.

A plan for the AWS lab's version 4 architecture, from nothing, would be roughly
`Plan: 40-60 to add`, with the great majority of interesting attributes reading
`(known after apply)` — because everything downstream of a not-yet-created
resource is unknowable (§10.8). **That plan is nearly unreviewable and that is
not a fixable property of the plan.** A first-creation plan can only be reviewed
by reading the *configuration*.

The reviewable plans are the incremental ones: `Plan: 0 to add, 1 to change`.
Which produces the actual answer to "what would you change about the pull
request, not the code":

- **Make it smaller.** One logical change per PR. A plan with four lines gets
  read; a plan with ninety gets approved.
- **Say what the plan should say, in the PR description, before CI runs it.** If
  the author writes "this should show exactly one `~` on the load balancer" and
  the plan shows eleven changes, the discrepancy is the review — and it catches
  drift (F1) and latent replacements (6.2) automatically, with no tooling at
  all.
- **Separate refactors from behaviour changes.** A PR containing `moved` blocks
  should contain nothing else, so its plan can be checked against the only
  acceptable outcome: `0 to add, 0 to change, 0 to destroy`.
