# Exercises — Amazon Web Services

Eight rungs, easiest first. Rungs 1–2 need only the README. Rungs 3–5 use
`visualization.html`. Rungs 6–8 are pencil and paper — no correct single
output, but there are wrong answers and the solutions say why.

Attempt before opening `SOLUTIONS.md`. The value is entirely in being wrong
first.

---

## Rung 1 — Explain

No notation, no jargon you cannot define. Say it to an imaginary colleague who
has never touched AWS.

**1.1** Asha already rented a server from a hosting company and it worked.
In two or three sentences, what did moving to AWS actually give her that
renting did not? Do not use the word "scalable".

**1.2** Explain what an Availability Zone is, and why it exists, to someone who
thinks "we have two servers" already means they are safe.

**1.3** In one sentence each, state the difference between:
(a) availability and durability, (b) replication and backup, (c) the control
plane and the data plane.

**1.4** Someone says: "we're on AWS with a 99.99% SLA, so we'll have at most
about 52 minutes of downtime this year." Explain what is wrong with that
sentence — there are at least two distinct errors.

---

## Rung 2 — Identify

Open `visualization.html`. Select **architecture v3** and let it run for a few
seconds.

**2.1** Which boxes on the screen are drawn *inside* an availability zone, and
which are drawn outside it? For each of the outside ones, say why it is drawn
there.

**2.2** Click the database box. What does its state panel say about which zone
it is in and whether it has a standby?

**2.3** Where on the screen would you look to answer "is the load balancer
routing to that instance right now?" — and what visual tells you the answer?

**2.4** Switch to **v4**. Name every box that changed compared to v3, and say
which stage of the README introduced each change.

---

## Rung 3 — Predict

For each, write your prediction *and your reason*, then run it.

**3.1** v2 (two instances, one AZ). You kill AZ `1b`, which contains neither
instance. What happens to the success rate, and what happens to the load
balancer's health?

**3.2** v3 (three AZs, ASG, single-AZ database in `1a`). You kill AZ `1c`.
Then reset and kill AZ `1a`. Predict both success rates before running either.
Why are they so different when the same *number* of things died?

**3.3** v4 with static stability **off**, running at festival traffic. You kill
AZ `1b` and freeze the control plane at the same moment. What is the success
rate two minutes later, and what specific API call is the system stuck on?

**3.4** Same as 3.3, but static stability **on**. Predict the success rate
before you run it. Then say, in one sentence, what physically changed about
the system — not what setting you flipped.

**3.5** v4, healthy, no failures injected — but you switch the database from
Multi-AZ to single-AZ *and* turn the traffic up to festival. Does anything go
wrong? Should it?

---

## Rung 4 — Modify

Change something, then explain what broke and which property was lost —
availability, durability, correctness, or elasticity. Name it explicitly.

**4.1** In v3, move all three instances into one AZ (the simulator lets you).
Which of the four properties is lost, and which are untouched?

**4.2** In v4, turn static stability off and kill an AZ *without* freezing the
control plane. Then do it again *with* the freeze. The architecture diagram is
identical in both runs. In one sentence: what is the difference between the two
systems?

**4.3** Set the ASG maximum to exactly the number of instances needed at normal
traffic, then run the festival. Which property is lost? Note that nothing has
failed and no error is red.

**4.4** Turn the quota cap on and set it *above* the number of instances the
festival needs. Does anything change? What does that tell you about when a
quota is a problem?

**4.5** In v5 (multi-region), what happens to the *write* success rate if you
choose the synchronous cross-region option versus the asynchronous one? Which
number in the README explains the difference?

---

## Rung 5 — Trace

Execute the mechanism by hand. Write each step. Then check against the
simulator or §10.1.

**5.1** Trace a `PutItem` call from Marigold's application to DynamoDB, all
seven steps of §10.1, but this time the instance's clock is 12 minutes fast.
At which step does it fail, what is the error, and — the interesting part — how
would this failure *look* to someone reading only application logs?

**5.2** Trace the same call where the role's policy allows `dynamodb:GetItem`
but not `dynamodb:PutItem`. At which step does it fail? How many milliseconds
of database work were done before it failed? What does that imply about how
quickly you will detect this in a deploy?

**5.3** Trace the timeline of §10.6 (AZ failure at festival traffic, control
plane frozen, static stability off) with these numbers: 12 instances, 4 per AZ,
each instance serves 100 requests/second, and festival traffic is 1,000
requests/second. At `18:04:30`, `18:05:02` and `18:11:00`, state: instances
serving, capacity in requests/second, and requests/second being shed.

**5.4** Now do 5.3 again with static stability on — 18 instances, 6 per AZ.
Same three timestamps. State the same three numbers, plus the monthly cost
ratio versus the 12-instance design.

---

## Rung 6 — Spot the flaw

Each of these is a described design with a planted mistake. Find it, construct
a concrete run where it produces a bad outcome, and name the violated
guarantee.

**6.1** *The design that passes every review.*
> Marigold runs 9 application instances in an ASG across `ap-south-1a/1b/1c`,
> min 9, max 30, scaling on CPU. Aurora with two reader instances. S3 for
> receipts with versioning on. DynamoDB for idempotency keys. An ALB across all
> three zones. Everything in Terraform. Backups retained 7 days. The team ran a
> game day and killed one AZ; the system recovered in 90 seconds.
>
> This design has a flaw that the game day could not have found. What is it?

**6.2** *The disaster recovery plan.*
> "Our RPO is 5 minutes and our RTO is 1 hour. We take an RDS snapshot every 5
> minutes and copy it to `eu-west-1`. If `ap-south-1` fails, we run our
> Terraform in `eu-west-1`, restore the latest snapshot, and repoint DNS."
>
> Find two independent reasons this plan does not achieve a 1-hour RTO. One is
> about §7.4; the other is about the difference between having a plan and
> having tested one.

**6.3** *The idempotency table.*
> "Every charge writes a row to a DynamoDB table `idempotency` with partition
> key `date` (e.g. `2026-08-30`) and sort key `charge_id`, using a conditional
> write so a duplicate charge fails. This gives us exactly-once payments."
>
> Two flaws — one about §F5, one about the word "exactly-once". Name both.

**6.4** *The health check.*
> "Our `/health` endpoint runs `SELECT 1` against the database and calls
> DynamoDB `DescribeTable`, so we know the instance is genuinely able to serve
> traffic. If it fails, the ALB removes the instance and the ASG replaces it."
>
> Construct the run where this makes a minor incident into a total outage.

**6.5** *The permission.*
> "The payment service's role has `dynamodb:*` on `table/charges` and
> `s3:*` on `marigold-receipts`. It's scoped to just our two resources, so the
> blast radius is small."
>
> Say what an attacker with shell on that instance can do that the author did
> not intend. There are at least two things, and one of them is not about
> reading data.

---

## Rung 7 — Handle failure

Extend a design, on paper, to survive something it currently does not.

**7.1** Marigold's `/charge` endpoint is not idempotent: a retry after a
timeout can charge twice. The client (a shop's terminal) retries on any
timeout. Design the server-side change that makes retries harmless. Specify:
what the client must send, what the server stores, in which store, what the
conditional write looks like conceptually, and what happens when two retries
arrive *simultaneously*. Then say what happens if the idempotency store is
unavailable — and defend fail-open or fail-closed for a payments API.

**7.2** Take the v4 design and make it survive **F3** (control plane frozen
during an AZ loss) *without* 50% overprovisioning. There is more than one
answer; give two and say what each costs.

**7.3** RDS Multi-AZ failover completes in 90 seconds but Marigold's Java
application stays broken for 8 minutes (F11). List every layer that could be
holding the stale endpoint, and the specific change at each layer.

**7.4** Design the guardrail that would have prevented F8 (the deletion that
replicated perfectly) — assuming the person who ran the delete had legitimate
production access and made an honest mistake. Your answer must not be "be more
careful", and it must still let that person do their job.

---

## Rung 8 — Design

Production-scale prompts. The answer is a discussion with a defended position,
not one output.

**8.1** **Marigold goes multi-region.** Marigold Pay now processes ₹40 crore a
day for 12,000 shops, all in India. The board wants "survive losing a region".
Design it. You must state and defend: which region pairing; where the write
path lives; your RPO and RTO as *numbers*; what you do about the `charges`
table specifically given that last-writer-wins is unacceptable for money; how a
failover is triggered and by whom; and what you *refuse* to do and why. Include
the sentence you would say to the board about cost.

**8.2** **The account structure.** You are the first platform engineer at a
40-person company with everything in one AWS account. Design the account
structure and the migration path. Justify each account you create in terms of
blast radius. Name the three SCP guardrails you would apply first, and say what
each one prevents. Then name the thing that will actually be hard about the
migration (it is not technical).

**8.3** **Choose the compute.** A Spring Boot service handles 200 requests/sec
average, 2,000 at peak for 40 minutes twice a day, with a 400ms p99 budget and
a 6-second JVM startup. Argue for EC2+ASG, ECS Fargate, EKS, or Lambda. Then
argue the strongest case *against* your own choice.

**8.4** **The bill doubled.** Month-on-month AWS spend went from $48k to $96k
with no traffic change and no deploy that anyone remembers. Describe your
investigation, in order, naming the specific things you would check and why
that order. Then name the three architectural patterns most likely to be
responsible, given the §F9 list.

**8.5** **Read the postmortem.** Find the AWS public post-event summary for the
November 2020 Kinesis event in `us-east-1` or the December 2021 `us-east-1`
event. Then answer: which of this lab's eleven failure modes does it match?
Which customer designs would have been unaffected? And which sentence in the
summary is the one that maps to §7.3?
