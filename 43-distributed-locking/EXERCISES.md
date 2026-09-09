# Exercises — distributed locking

Climb in order. Answers in [SOLUTIONS.md](SOLUTIONS.md) — commit to yours
first, in writing.

## Rung 1 — Explain

1.1 Without the words "lease", "TTL" or "fencing": why is a lock between
two machines fundamentally harder than a lock between two threads? Name the
two facts about the world that do the damage.

1.2 The TTL is one number with two opposed jobs. State both jobs, and
explain why "set it long enough to be safe" is not a sentence an engineer
can finish.

1.3 A teammate says: "our lock is on ZooKeeper, which is consensus-backed,
so a GC pause can't hurt us." What did consensus actually buy, and what did
it not?

## Rung 2 — Identify

2.1 In [World.java](src/World.java), point at: (a) the line that makes a
crash recoverable, (b) the line that makes fencing's arithmetic sound
across every grant, (c) the single comparison that carries the entire
safety guarantee, (d) the line added because of the bug this lab found in
itself.

2.2 In the visualization, run scenario 3 (the pause). At the moment the
lock server re-grants: what does Pia's card believe, what does the lease
bar show, and which on-screen number is the split-brain?

2.3 In the `herd` experiment's output, the poll and watch columns finish at
the same tick. So what exactly did the 2,641 extra messages buy? (Nothing
is the wrong answer — they bought something *negative*.)

## Rung 3 — Predict (then run)

3.1 TTL 30, fencing fully on. Pia's pause is 300 ticks — ten times the
TTL. How many updates are lost, and what does Pia experience when she
wakes? Run `java src/Experiments.java pause` (the P=90 column generalizes)
and reason from the token arithmetic.

3.2 In the Redlock clock-jump test, suppose the jump happens on S4 (a
server Pia never reached) instead of S1. Does the double-majority still
form? Work it out from the reachability masks in
`majority_alone_is_not_exclusive_after_a_clock_jump`, then edit the index
and run `java src/Tests.java` to check your reasoning against the failure.

3.3 A client acquires (token 7), writes successfully, releases. Its
*retry* logic then accidentally re-sends the same write (token 7) after
the next holder (token 8) has already written. Fencing checks
`token < maxToken`. Is the duplicate accepted or rejected — and is that
the right outcome? What single change would make duplicates of *accepted*
writes idempotent instead?

3.4 Two *different* resources are both protected by the same lock (a file
and a search index). Fencing is implemented on the file only. Which of
§11's failures now applies to the index, and why does "but they're updated
together" not help?

## Rung 4 — Modify

4.1 In `pauseRun`, set `msgDelay = 0` and re-run `pause`. Predict which
single row changes and why — then explain what that row was teaching (it
is the lab's own discovered bug).

4.2 Make renewal *unconditional* in `World.renew` (drop the holder/token
check) and write a two-client scenario where that "optimization" steals a
live lock. Which existing test fails first?

4.3 Change the watch queue to notify ALL waiters instead of the first.
Everything still completes and no test asserts message counts except one —
which check catches it, and what production symptom did you just recreate?

## Rung 5 — Implement

`java src/YourLock.java` starts at 2/6. Implement the grant rule and the
fencing rule per the TODOs (6/6). Then answer in one sentence each: why
must the token bump on expiry-takeovers specifically, and why does check 6
pass a *read* into the fence?

## Rung 6 — Debug

Run `java src/Tests.java broken`. For each of the four planted bugs:
(a) the innocent code-review diff that introduces it, (b) whether it breaks
safety or availability, (c) the production symptom you'd see first. Then
rank them by time-to-detection in production and defend the ranking. (One
of them announces itself loudly; one is nearly perfectly silent.)

## Rung 7 — Handle failure

The lab's resource conveniently understands tokens. Yours doesn't: the
critical section sends an email through a third-party API (box G of §15's
flowchart). Design the safest achievable "send the daily digest exactly
once across 3 instances" and implement its core as a small Java sketch:
what does the lock become, where does an idempotency key live, who is
allowed to call the API, and — the hard part — write down the residual
failure that your design does NOT close, and the monitor that would catch
it.

## Rung 8 — Design

Payments platform, 3 services, and three locking questions to settle:
(1) 20 payout workers must each drain distinct merchant queues (a worker
per merchant, no merchant drained twice); (2) a nightly interest batch must
run exactly once across 4 instances and writes only to Postgres; (3) a
"close the ledger month" operation must be exclusive across the company for
minutes at a time, and parts of it call external tax APIs.
For each: efficiency or correctness? which mechanism (yesterday's lab, this
lab's, or box G)? what is the fencing token and who checks it? what do you
monitor? Then the cross-question: the platform team proposes "one Redisson
RLock wrapper library for all three, for consistency." Write the two-
paragraph review comment.
