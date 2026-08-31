# Exercises — Terraform

Eight rungs. Rungs 1–2 need only the README. Rungs 3–5 use
`visualization.html`. Rungs 6–8 are pencil and paper.

Attempt before opening `SOLUTIONS.md`. Rung 3.4 in particular is worth being
wrong about first.

---

## Rung 1 — Explain

**1.1** A colleague says: *"Why does Terraform need a state file at all? It
could just look at my config and look at AWS and work out the difference."*
Answer them. Give the two independent reasons, and make each one concrete.

**1.2** Explain the difference between **desired**, **recorded** and **actual**
state, and give one question that can only be answered by comparing the second
and third.

**1.3** In one sentence each: (a) what a plan is, (b) why `-/+` is different in
kind from the other three symbols, (c) what drift is and why it is not the same
as a config change.

**1.4** Someone says: *"Terraform is idempotent, so it's safe to run apply
whenever."* Name two things wrong with the inference, using stage 12.

---

## Rung 2 — Identify

Open `visualization.html` in its starting state.

**2.1** Point at each of the three columns and say which of §7.3's three states
it is. Which column can Terraform *write* to, and which two can it only read?

**2.2** Click the `aws_lb.app` row. What are its three states, and what does the
row's colour mean?

**2.3** Which rows appear in the Cloud column but not the State column, and what
does Terraform do about them? What is that behaviour protecting you from?

**2.4** Find the resource address for the database, then find its AWS
identifier. Which of the two did you choose, which did AWS choose, and which one
appears in your git history?

---

## Rung 3 — Predict

Write your prediction *and your reason*, then run it.

**3.1** Everything currently matches. You press **Plan** without changing
anything. What does the plan say, and how many API reads happen?

**3.2** You edit `idle_timeout` in Config from 60 to 90, then **Plan**. Now
instead, someone edits it *in the console* from 60 to 90 and you **Plan**. Both
end with reality at 90. Are the two plans the same? Predict both before running
either.

**3.3** Someone deletes the load balancer in the console. You **Plan**. What
does Terraform propose, and why is that different from what it proposes when you
delete the block from your Config?

**3.4** Five subnets under `count`. You remove the **second** entry from the
list. Before running: how many resources will Terraform destroy, and how many
will it create? Write both numbers down. Then run it. Then switch to `for_each`
and repeat, predicting again first.

**3.5** State lock is **off**. Two engineers apply at once — one changing a
timeout, one adding a subnet. Both succeed with no errors. What is wrong
afterwards, and where exactly is it wrong: the cloud, the record, or the config?

**3.6** You run **Plan**, then someone edits a resource in the console, then you
run **Apply** from the plan you already have. What does Terraform do?

---

## Rung 4 — Modify

Change something, run it, then name which property was lost — convergence,
reviewability, safety, or blast-radius containment.

**4.1** Turn the state lock off and reproduce 3.5. Which property is lost, and
which one is *not* damaged at all?

**4.2** Set the apply to fail at step 4 of 7 and run it. Which resources exist?
Which are recorded? Now fix the config and apply again — what does the second
plan contain, and why is it shorter than the first?

**4.3** Delete the state file, then plan. Read the number at the top. What
property is lost, and what — importantly — is *not* lost?

**4.4** Put `prevent_destroy` on the database, then try to change an immutable
attribute. What happens, and at which stage of the workflow does it stop you?

**4.5** Merge all resources into one root module, then change one tag. What
happens to the lock and the plan, and which two things degrade that have nothing
to do with correctness?

---

## Rung 5 — Trace

Execute the mechanism by hand, then check against the simulator.

**5.1** Given these three states, produce the plan by hand — every resource,
every action, with the reason:

| Address | Desired | Recorded | Actual |
|---|---|---|---|
| `aws_vpc.main` | `10.0.0.0/16` | `10.0.0.0/16` | `10.0.0.0/16` |
| `aws_subnet.a` | `10.0.1.0/24` | `10.0.1.0/24` | *(deleted in console)* |
| `aws_lb.app` | `idle_timeout = 90` | `idle_timeout = 60` | `idle_timeout = 60` |
| `aws_s3_bucket.logs` | *(not in config)* | `marigold-logs` | `marigold-logs` exists |
| `aws_db_instance.main` | `az = ap-south-1b` | `az = ap-south-1a` | `az = ap-south-1a` |
| — | — | *(not recorded)* | `sg-0999` exists, made by hand |

**5.2** Trace the §10.5 concurrent-apply timeline with these events, writing the
state file's serial and contents after each line: Asha reads (serial 12), Ravi
reads (serial 12), Ravi applies + writes, Asha applies + writes. Then say what
the *next* plan proposes and why it is wrong.

**5.3** Five resources under `count` with values `[a, b, c, d, e]`. Someone
reorders the list to `[a, c, b, d, e]` — no additions, no removals, same five
values. Produce the plan. How many resources are disturbed? Now explain, in one
sentence, why this is the same phenomenon as 3.4.

**5.4** An apply of 6 resources in this graph fails at the 4th:
`vpc → subnet_a, subnet_b → sg → lb → asg`. Write the record's contents at the
moment of failure, and then the plan produced by the very next run.

---

## Rung 6 — Spot the flaw

Each is a described setup with a planted mistake. Find it, construct a concrete
run where it hurts, and name the guarantee violated.

**6.1** *The tidy repository.*
> One root module for all of production. State in S3 with versioning and a lock
> table. Every resource uses `for_each`. Plans run in CI on every PR and are
> posted as comments. Applies run on merge. Provider versions pinned in the lock
> file, committed.
>
> One thing here will make the team miserable within six months, and it is not a
> correctness bug. What is it, and what is the symptom they will report?

**6.2** *The Tuesday afternoon.*
> A PR retags resources for a new cost-allocation policy: it adds
> `tags = { cost_centre = "payments" }` to every resource, including
> `aws_db_instance.main`. CI posts the plan. It is 340 lines. The reviewer scans
> it, sees only tag changes, and approves. The apply destroys the production
> database.
>
> What did the plan contain that the reviewer missed, what caused it, and name
> three independent controls that would each have stopped this alone.

**6.3** *The clean-up.*
> "We had drift because people edit things in the console, so we added
> `lifecycle { ignore_changes = all }` to the resources that kept showing up in
> plans. Now our plans are clean."
>
> Say what this actually achieved and what it destroyed. Which of the three
> states is now unmanaged, and what does the next disaster recovery attempt
> produce?

**6.4** *The refactor.*
> An engineer moves the networking resources into a `modules/network` module.
> The addresses change from `aws_subnet.private["app_a"]` to
> `module.network.aws_subnet.private["app_a"]`. They open a PR; CI posts a plan;
> the plan says `Plan: 14 to add, 0 to change, 14 to destroy`. The engineer
> reasons that the resources are identical either way and approves.
>
> What happens, and what should they have written instead?

**6.5** *The disaster recovery drill.*
> "Our DR plan is tested: every quarter we run `terraform apply` against the
> `dr/` directory in `eu-west-1` and it completes successfully. Recovery time
> objective: 45 minutes."
>
> Two independent reasons this drill does not test what they think. One comes
> from this lab; one comes from the AWS lab.

---

## Rung 7 — Handle failure

Extend a design on paper to survive something it currently does not.

**7.1** Marigold's state file was deleted by a misconfigured lifecycle rule on
the S3 bucket, and versioning was not enabled. 47 resources exist and are
serving production. Write the recovery plan: the order of operations, what you
do first, how you avoid making it worse, and how you would have prevented it for
about ten minutes of work.

**7.2** Design the review control that would prevent F6 (the replaced database)
*without* relying on a human reading 340 lines carefully. Your answer must be
mechanical, must not block ordinary changes, and must still allow a genuinely
intended replacement to happen.

**7.3** Your team's on-call engineers need to make emergency changes at 3am when
CI is not an option, but every such change becomes permanent invisible drift
(F1). Design the process. It must let them act fast, and it must guarantee the
change is either adopted into the configuration or removed — with the guarantee
coming from a mechanism, not a promise.

**7.4** A `terraform apply` in CI is cancelled mid-run because someone stopped
the pipeline. Describe every state the system could now be in, how you would
determine which one you are actually in, and what you do in each case.

---

## Rung 8 — Design

Production-scale prompts; the answer is a defended position.

**8.1** **The state architecture.** Marigold now has 4 teams, 2 regions, 3
environments and about 900 managed resources. Design the state boundaries: how
many root modules, split on what axis, what is in each, how values cross between
them, and where the backends live. Justify each boundary by naming the failure
from stage 11 that it contains. Then name the one boundary you deliberately
*refuse* to draw, and why.

**8.2** **Adopting an existing estate.** You join a company with three years of
click-built AWS infrastructure and no Terraform. Design the adoption programme.
What do you bring under management first and why? How do you avoid a
half-managed estate being worse than an unmanaged one? What do you deliberately
never import? What does "done" look like, and how long is this honestly going to
take?

**8.3** **Terraform or CloudFormation.** An AWS-only team of six, no existing
IaC, one region, moderate complexity. Argue for one. Then argue the strongest
case against your own choice, using the specific failures from stage 11 that
your choice does and does not remove.

**8.4** **The reconciler runs itself.** Design a version of Marigold's workflow
where infrastructure changes are applied automatically on merge with no human
approval step. What must be true for this to be safe? Which of stage 11's
failures get worse, which get better, and which controls become mandatory rather
than advisable? Then say whether you would actually do it, for which of the
three layers in §13.3, and why the answer is different per layer.

**8.5** **Read a real plan.** Find a Terraform plan in your own or a public
repository — or write out what one would look like for the AWS lab's version 4
architecture. Then answer: how many resources does it touch, how many are
`(known after apply)`, is there a `forces replacement` anywhere, and could a
reviewer who did not write the change tell what it does? If not, what would you
change about the pull request — not the code — to make it reviewable?
