# Paxos — Solutions

Every "which tests fail" answer below was produced by actually applying the
mutation and running the suite, not from reasoning about it.

## Part 0 — Explain & identify

**0.1** One honest version: *Several machines must end up believing the same
single answer even though any of them can be off at any moment and their
messages can vanish. Just announcing an answer fails because two announcers
can each convince a different part of the group, and nobody can even tell
whether "the" answer exists yet. The fix: before pushing your answer, ask
more than half the group what they have already written down — because any
two more-than-half groups must share someone, you are guaranteed to meet a
witness of any earlier answer, and the rule is that you must then push THAT
answer instead of yours. So the first answer to reach more than half can
never be displaced — everyone later just ends up spreading it further.*

**0.2** *Accepted*: one acceptor wrote the value in its notebook — a local
fact, reversible in effect because it may never be joined by enough others.
*Chosen*: a majority wrote the same proposal — a global, permanent fact that
no one may even know yet. Experiment 3 exists purely for this gap.

**0.3** (a) The stars sit on the three teachers of the first deciding
majority (A, B, C) — members of the quorum that made the decision. (b) C has
the amber witness outline: it is the overlap member that saw the Zoo decided
and reported it to Rep 2. (c) On screen: "promised #n" and "wrote: value /
under slot #n" — in the engineering model: `promised_n` and
`(accepted_n, accepted_value)`.

**0.4** (a) The `PREPARE` branch of `Acceptor.receive` — the comparison
`n > promised` implements the *promise*. (b) In the proposer's
promise-handling: the code that scans promises for the highest `accepted_n`
and overwrites its own value — *adoption*. (c) The bookkeeping that counts
acceptors per `(n, value)` pair and compares against the majority —
*chosen*. (Exact names differ slightly from the doc's pseudocode; the point
is matching line to invariant.)

## Part 1 — Predict

**1.1** `A->P1  PROMISE(n=9) [already accepted (n=5, 'Zoo')]`

It reports the **accepted** pair `(5, "Zoo")`, not the promise `7`. Those are
two different facts: `promised_n = 7` means "I told someone they could have
slot 7", which may have gone nowhere — the proposer holding slot 7 might have
crashed before phase 2. `accepted = (5, "Zoo")` is the last thing actually
written down, and it is the only thing that could be part of a decision. A
proposer needs to know what might already have been chosen; an unused promise
never was.

**1.2** **Accepted** — `B->P1 ACCEPTED(n=7, 'Museum')`.

`>=` is necessary because the acceptor sets `promisedN = n` when it grants
the promise. With a strict `>`, the acceptor would reject the phase-2 message
from the very proposer whose phase-1 request it just granted — `n == promisedN`
is precisely the expected case for a well-behaved proposer, so `>` would make
Paxos unable to complete a single round ever.

**1.3** With **A, D, E**: P2 proposes **"Zoo"** (`adoptedFrom = 1`). With
**C, D, E**: P2 proposes **"Museum"** (`adoptedFrom = null`).

The difference is which acceptors were asked. Only A had written "Zoo" down,
so a quorum containing A discovers it and a quorum avoiding A does not. This
is the honest, slightly uncomfortable truth about Paxos: when nothing has been
*chosen*, the outcome genuinely depends on who you ask, and that is fine —
there is no decision to protect. The moment a majority has accepted, no quorum
can avoid at least one of them, and the dependence on "who you ask" disappears.
That transition is the entire safety property.

**1.4** The light never turns red and the decision field never leaves `none`.
**Safety** is preserved (at most one value is ever chosen — zero is at most
one); **liveness** is violated (no value is ever chosen despite no crashes and
no lost messages). Paxos guarantees the first unconditionally and the second
not at all.

**1.5** Quorum for 4 is `4//2 + 1 = 3`, tolerating **1** failure — exactly the
same as a cluster of 3 (quorum 2, tolerates 1). A cluster of 5 has quorum 3 and
tolerates 2. So the fourth machine bought you **nothing**: no extra fault
tolerance, one more machine that can fail, one more machine to pay for, and a
new failure mode where a 2–2 split leaves *neither* side able to form a quorum.
This is why real deployments are 3, 5 or 7.

## Part 2 — Modify

**2.1** Four tests fail:

```
test_a_later_proposer_must_abandon_its_own_value_and_carry_the_chosen_one
test_proposer_adopts_the_highest_numbered_accepted_value_not_the_first_it_hears
test_the_same_crash_is_harmless_when_acceptors_persist_their_state
test_safety_survives_hundreds_of_random_schedules
```

The invalidated sentence is the last step of the stage-11 argument: *"The
proposer is then forced by the phase-2 rule to propose `v`."* Quorum overlap
still happens — the overlapping acceptor still **reports** the chosen value —
but the proposer now ignores the report. Overlap only buys you safety if you
act on what it tells you. Note that the chaos test catches it too: this is a
bug that randomized testing finds, which is the point of having that test.

**2.2** Five tests fail, the interesting ones being
`test_any_two_quorums_always_overlap` and
`test_a_value_accepted_by_only_a_minority_may_still_be_replaced`.

With 5 acceptors, `quorum = 2`. Now `{A, B}` and `{C, D}` are both "quorums"
and they share nobody. So P1 can get "Zoo" chosen by A and B while P2 gets
"Museum" chosen by C and D, each without ever hearing of the other. The
overlap property is not a nice-to-have that makes Paxos faster — it is the
load-bearing wall, and "more than half" is the smallest rule that guarantees it.

**2.3** The same four tests as 2.1.

That is the instructive part: **2.1 and 2.3 are the same bug seen from
opposite ends.** In 2.1 the proposer refuses to listen; in 2.3 the acceptor
refuses to speak. Safety needs the information to be both reported *and*
acted on, so breaking either half breaks the identical set of guarantees.

**2.4** Implementation sketch: on NACK, instead of retrying immediately, wait
a random number of rounds (`random.randint(0, 2**attempts)` is the usual
exponential-backoff shape) before issuing the next `Prepare`. The duel ends as
soon as one proposer happens to get through both phases before the other's
next prepare — with independent random delays that typically takes a handful
of rounds and the probability of continuing decays geometrically.

It is *probabilistic*, not a guarantee, and FLP says it cannot be otherwise:
in an asynchronous system with a possible crash failure, **no** deterministic
protocol guarantees termination. Backoff does not defeat that theorem, it
just makes the bad case vanishingly unlikely in practice. The stronger
practical fix is a distinguished proposer (leader), which removes the
contention rather than randomizing it — but electing a leader is itself
subject to FLP, so the impossibility is relocated, never removed.

**2.5** Achievable for 2 of 5, impossible for 3 of 5 — and "impossible" here
is not "hard", it is ruled out by the protocol. Once 3 of 5 have accepted the
same proposal number, every future quorum of 3 contains at least one of them,
that acceptor reports the value in its promise, and `pickValue` forces the
new proposer to re-propose it. You will find you cannot construct the schedule
no matter how you order or lose messages, which is exactly what the proof says.

## Part 3 — Debug

**3.1** The bug is **replying before the state is durable** (`reply()` before
`write_async()` completes). It breaks the durability obligation from stage 11,
and through it the safety invariant: an acceptor that acknowledges a promise or
an acceptance and then reboots before the write lands comes back believing it
never voted. The quorum-overlap argument then fails silently, because the
overlapping acceptor no longer remembers what it must report.

CI never caught it because the tests never crash a node *between* the reply and
the flush — the window is invisible unless the test harness models durability
explicitly. The test that would have caught it is exactly
`test_an_acceptor_that_forgets_after_a_crash_breaks_consensus`, and the
reproduction is **experiment 5**:

```
 chosen value(s): {'Museum', 'Zoo'}
 safety holds (at most one value ever chosen): False
```

The fix is a synchronous `fsync` before every reply. Yes, it costs a disk
round trip per message; that cost *is* the algorithm. Batching many proposals
into one flush is the legitimate optimization; replying early is not.

**3.2** Output:

```
chosen: set()
 acceptor | up?  | promised | accepted
        A |  up  |        2 | -
        B |  up  |        2 | -
        C |  up  |        2 | -
```

A, B and C have `promised = 2` and have accepted **nothing**. P1 completed
phase 1 at `n=1` and its `Accept(1, "Zoo")` was still in flight when P2's
`Prepare(2)` arrived and moved every promise up to 2. When P1's accepts finally
landed, the rejecting line was:

```java
if (msg.n() >= promisedN) {       // 1 >= 2 is false -> NACK
```

This is the protocol working exactly as intended: a promise is a commitment to
ignore everything older, and it is what makes a late message harmless instead
of catastrophic. It is also the mechanism that produces livelock — the same
line, viewed from the liveness side.

**3.3** Here is the counterexample, actually run:

```
P1 sends Accept(5, "Zoo")    to A, B, C   ->  chosen: {'Zoo'}
P2 sends Accept(9, "Museum") to C, D, E   ->  chosen: {'Zoo', 'Museum'}
safety holds: False
```

C accepts the Museum because `9 >= 5`, and C, D, E form a second majority.
Two different values chosen.

**Phase 1 is not for permission — it is for discovery.** The promise is
almost a side effect; the payload that matters is the acceptor telling the
proposer *what has already been accepted*. Skip phase 1 and a proposer is
choosing a value while blind to the past, which is precisely the broken
"Attempt 3" from stage 4 of the README wearing a proposal number. (Multi-Paxos
*does* skip phase 1 for later slots — but only after a leader has run it once
for that ballot, so the discovery has already happened. It is an amortization,
not a removal.)

**3.4** The bug is waiting for `acceptorIds.size()` promises — **all** of
them — instead of a majority. Safety is untouched (a stricter requirement never
chooses more), but liveness now requires **every single acceptor to be alive
and reachable**, which is the "Attempt 2" failure from stage 4: the system no
longer tolerates any failure at all. Demonstrated:

```
with 1 of 5 down -> chosen: (nothing) | safety: True
```

Nine tests fail, and the one that names the scenario is
`test_a_minority_of_crashed_acceptors_does_not_stop_consensus`. This is a
realistic mistake: "wait for everyone" looks safer, and it is — it is also
useless, because the entire point of a majority is that it is the largest
quorum you can require while still tolerating failures.

**3.5** Yes, easily. One route:

```
 acceptor | up?  | promised | accepted
        A |  up  |        2 | -
        B |  up  |        1 | -
        C |  up  |        1 | 'Zoo' (n=1)
        D |  up  |        2 | 'Museum' (n=2)
        E |  up  |        2 | 'Museum' (n=2)
chosen: (nothing) | safety holds: True
```

C has "Zoo", D and E have "Museum", and nothing is wrong. **Replicas
disagreeing is the normal, expected state of a Paxos cluster** — an acceptor's
notebook is a record of what it was asked to write, not a claim about what was
decided. Only a *majority* holding the same proposal number constitutes a
decision.

It becomes a violation the moment **two different values each reach a majority
at some point in time** — 3 of 5 for "Zoo" and, at any later moment, 3 of 5 for
"Museum". Note "at some point": it is not about the final state. Even if the
cluster later converges to a single value, if two different values were each
chosen at any instant, consensus was broken and something downstream may
already have acted on the wrong one.

## Part 4 — Implement

Reference implementation:

```java
public static Object pickValueFromPromises(List<Promise> promises, Object myValue) {
    int bestN = 0;
    Object bestV = null;
    for (Promise p : promises) {
        if (p.lastAcceptedValue() != null && p.lastAcceptedN() > bestN) {
            bestN = p.lastAcceptedN();
            bestV = p.lastAcceptedValue();
        }
    }
    return bestV == null ? myValue : bestV;
}


public static Object chosenValue(List<AcceptorState> acceptorStates, int quorum) {
    Map<AcceptorState, Integer> counts = new LinkedHashMap<>();
    for (AcceptorState s : acceptorStates) {
        if (s.acceptedValue() != null) counts.merge(s, 1, Integer::sum);
    }
    for (var e : counts.entrySet()) {
        if (e.getValue() >= quorum) return e.getKey().acceptedValue();
    }
    return null;
}
```

The two traps, and why the tests force them: **highest-n, not most-common**
— two acceptors reporting `(3, "Zoo")` against one reporting `(5, "Museum")`
means "Museum" *might already be chosen by a majority you did not talk to*
(the n=5 round may have completed elsewhere), while the n=3 value is
provably older news; the counting rule would resurrect a possibly-dead value
over a possibly-decided one. **Same `(n, value)` pair** — three acceptors
holding "Zoo" under different numbers were never one majority agreeing on
one proposal; the decision predicate must count pairs, or you would declare
decisions that no quorum ever made (and, symmetrically, miss the fact that a
re-proposal under a higher n forms its own fresh quorum).

## Part 5 — Handle a failure

**5.1** Persist exactly the three numbers — `promised`, `acceptedN`,
`acceptedV` — and flush them **before** the reply leaves. The write barrier
matters more than the fields: reply-then-flush means a crash can land in the
gap where the outside world holds a `Promise` (or `Accepted`) that the
acceptor itself no longer remembers — which is precisely experiment 5's
amnesia, shrunk from "reboot without disk" to "reboot in a 2-millisecond
window". With flush-before-reply, a restarted acceptor rejoins knowing every
promise it ever made, and experiment 5's replay ends with one decision:
A, B, C come back still remembering the Zoo, and Rep 2's phase 1 meets a
witness instead of a blank room. The check that catches the wrong order is
exactly experiment 5 / the simulator's durability toggle (and in test terms:
crash an acceptor between reply and flush, restart, run a second proposer,
assert `safetyHolds()`).

**5.2** The retry must propose **the same request, identified as the same
request** (a client-chosen request id) — never a "fresh" attempt, because
the first attempt may already be chosen. The system may never assume the
first attempt failed: no-reply means *unknown*, not *no*. The only honest
resolution is to **read**: run the protocol (or ask a learner) and see what
was actually chosen — if it is your request id, your write happened (the
retry is a duplicate and idempotency absorbs it); if a different value won,
your write lost and the client must be told the truth rather than have the
system quietly pretend. This is the same lesson as the event-sourcing lab's
"a conflict means decide again": uncertainty is resolved by consulting the
authority, never by resending harder.

## Part 6 — Design

**6.1** A worked answer to compare against — yours may differ defensibly:

- **(a) Acceptors: 5, spread 2 + 2 + 1** (or 3+3+3 = 9 if budget allows;
  then quorum 5). With 5 across three DCs, losing any one DC leaves ≥ 3 — a
  quorum — alive. 3 acceptors (1+1+1) also survives a DC loss but then a
  single *machine* failure in either surviving DC stalls you; 5 buys real
  headroom. Even counts buy nothing (README §10's odd-size point).
- **(b) One stable leader** (Multi-Paxos), because dueling proposers at
  50 ms per round is experiment 4 with a latency bill attached: every
  gazump costs a cross-DC round trip. Place the leader in the DC nearest
  the write-heavy clients; leader election itself is a Paxos decision.
- **(c) Steady state: ~1 cross-DC round trip (~50–100 ms)** per decision —
  the leader already holds phase-1 rights for the log and needs only
  phase 2 to a quorum, which must include at least one remote DC.
  Failover: detection timeout + a full two-phase round for the new leader
  to re-prepare, so a few hundred ms to seconds, tunable by how twitchy
  you make detection (too twitchy = spurious leader flapping = the duel
  again).
- **(d) Dashboard**: (1) leader identity over time — flapping is the
  system's way of showing you experiment 4; (2) per-decision commit
  latency percentiles — creeping p99 means a slow quorum member;
  (3) proposals refused / NACK rate and stalled log slots — rising NACKs
  mean contention or a partitioned minority proposer that thinks it is
  leader.
- **(e) Survives from the lab unchanged**: acceptor state and its
  flush-before-reply rule, the promise/adopt/chosen logic, quorum
  intersection as the safety argument. **Added for production**: the log of
  instances (Multi-Paxos), leader election + leases, snapshots/compaction,
  membership change, learners/catch-up for lagging replicas, and client
  request ids (your 5.2 answer) — the omissions list in README §14.
