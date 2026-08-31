# Paxos — Exercises

Work top to bottom. Most are runnable against [`Paxos.java`](Paxos.java) or the
simulator ([`visualization.html`](visualization.html)). Answers in
[`SOLUTIONS.md`](SOLUTIONS.md) — attempt each before looking.

A scratch harness for the Java ones — save it as `Scratch.java` in this
directory and run `java Scratch.java`:

```java
public class Scratch {
    public static void main(String[] args) {
        var c = new Paxos.Cluster(5, 0, true, true);  // 5 acceptors, seed 0, durable, trace
        c.addProposer("P1", "Zoo"); c.addProposer("P2", "Museum");
        c.propose("P1", 1);
        c.deliverMany("ABC", "PREPARE", 1);     // deliver to specific acceptors
        c.deliverTo("P1", "PROMISE", 1);        // deliver one reply
        c.dropTo("C", "ACCEPT", 1);             // lose a specific message
        System.out.println(c.table());
        System.out.println(Paxos.reprSet(c.chosenValues()) + " " + Paxos.reprBool(c.safetyHolds()));
    }
}
```

## Part 0 — Explain & identify

**0.1 (explain)** In four sentences or fewer, and without using the words
*prepare*, *promise*, *accept*, or *quorum*, explain to a colleague: what
problem Paxos solves, why "just tell everyone" fails, and what the one key
idea is. (If you need protocol vocabulary to say it, the idea hasn't landed
yet — the school-trip story is enough.)

**0.2 (explain)** "Accepted by an acceptor" and "chosen" — state the
difference in one sentence each, and name the experiment in the simulator
that exists purely to demonstrate the gap.

**0.3 (identify)** Open the simulator and run guided experiment 2, answering
its prediction prompt first. When it finishes, read the *screen*, not the
doc: (a) which teachers carry a star, and what does the star mean? (b) which
teacher's box has the amber "witness" outline, and what did it witness?
(c) find, in each teacher's box, the two facts every acceptor must remember
— what are they called on screen, and what are they called in the doc's
engineering model (§7)?

**0.4 (identify)** In [`Paxos.java`](Paxos.java), find (a) the line where an
acceptor decides whether to grant a Prepare, (b) the line that forces a
proposer to abandon its own value, and (c) the code that concludes a value
is chosen. For each: which glossary term does that line implement?

## Part 1 — Predict

**1.1** Acceptor A has `promised_n = 7` and `accepted = (5, "Zoo")`. It now
receives `Prepare(9)`. Write down its reply exactly — including which value it
reports and why it is the `n=5` one rather than the `n=7` one. Then check:

```java
var a = new Paxos.Acceptor("A");
a.promisedN = 7; a.acceptedN = 5; a.acceptedValue = "Zoo";
System.out.println(a.receive(Paxos.Message.prepare("P1", "A", 9)).get(0));
```

**1.2** Same acceptor, but it receives `Accept(7, "Museum")` instead. Accepted
or refused? The condition in the code is `n >= promisedN`, not `n >`. Explain
in one sentence why `>=` is necessary — what would break if it were `>`?

**1.3** Five acceptors. P1 completes phase 1 with A, B, C at `n=1` and gets
`Accept(1,"Zoo")` written by **A only**. P2 then completes phase 1 with
**A, D, E** at `n=2`. Predict P2's proposed value. Now predict again for the
case where P2 uses **C, D, E** instead. Explain why the answers differ even
though nothing was *chosen* in either case, and run both.

**1.4** Simulator, experiment 4 (livelock). Before running it: does the
"one decision only?" light ever turn red? Does the "decision" field ever leave
`none`? State which property is being violated and which is being preserved,
using the words *safety* and *liveness*.

**1.5** A cluster of **4** acceptors. What is the quorum? How many failures
can it tolerate? Compare with 3 and with 5 acceptors, and state what the
fourth machine actually bought you.

## Part 2 — Modify

**2.1** In `Paxos.java`, change `pickValue` so that the proposer always
returns `ownValue` (delete the adopt behaviour). Run
`java PaxosTest.java`. Exactly which tests fail, and which single
sentence of the safety argument in README stage 11 have you invalidated?
Restore it afterwards.

**2.2** Change `quorumSize` to `numAcceptors / 2` (half, not a majority).
Run the test suite. Find the test that now fails and explain the failure in
terms of two specific sets of acceptors that no longer overlap.

**2.3** In `Acceptor.receive`, make the `PREPARE` branch reply with
`lastN=0, lastValue=null` always — i.e. the acceptor promises correctly but
never reports what it accepted. Predict which tests fail before running.
This models a real bug class: a phase-1 reply that forgets to include the
accepted value.

**2.4** Give `Proposer` a randomized backoff: on NACK, retry only after
skipping a random number of rounds. Then reproduce experiment 4's duel and
show it terminates. Roughly how many rounds does it take, and why is this a
*probabilistic* fix rather than a guarantee? (Relate your answer to FLP.)

**2.5** In the simulator, use the queue's `lose` buttons to construct this by
hand: a value that is accepted by exactly 2 of 5, followed by a *different*
value becoming decided. Then do it again with 3 of 5 for the first value and
show you cannot.

## Part 3 — Debug

**3.1** A colleague's Paxos implementation passes all its tests in CI and
corrupts data roughly once a month in production, always after a machine
reboot, and always with the log showing two different committed values for the
same slot. Their acceptor code is:

```java
void onAccept(int n, Object v) {
    if (n >= this.promisedN) {
        this.promisedN = n;
        this.acceptedN = n;
        this.acceptedValue = v;
        reply(new Accepted(n, v));     // reply first, for latency
        db.writeAsync(n, v);           // persist in the background
    }
}
```

Name the bug, name the exact invariant it breaks, and explain why the test
suite never caught it. Which experiment in this lab reproduces it?

**3.2** Run this and explain the result:

```java
var c = new Paxos.Cluster(5);
c.addProposer("P1", "Zoo"); c.addProposer("P2", "Museum");
c.propose("P1", 1); c.deliverMany("ABC", "PREPARE", 1);
for (int i = 0; i < 3; i++) c.deliverTo("P1", "PROMISE", 1);
c.propose("P2", 2); c.deliverMany("ABC", "PREPARE", 2);
c.deliverMany("ABC", "ACCEPT", 1);          // P1's phase 2, arriving late
System.out.println(Paxos.reprSet(c.chosenValues()));
System.out.println(c.table());
```

Why is `chosenValues()` empty, and what is each acceptor's `promised` value?
Which single line of the acceptor's logic rejected P1?

**3.3** Someone proposes this "optimization": *"Phase 1 is a waste of a round
trip. Since acceptors already refuse anything below their promise, let's skip
Prepare entirely and just send Accept(n, our_value) with a big n."* Construct
a concrete two-proposer schedule, with real acceptor names and numbers, in
which this chooses two different values. Then explain in one sentence what
phase 1 is actually *for* — it is not permission.

**3.4** Below is a subtly wrong proposer. It is wrong in a way that preserves
safety but can lose liveness in a specific scenario. Find it, and describe the
scenario.

```java
public List<Message> receive(Message msg) {
    if (msg.kind().equals("PROMISE") && msg.n() == n && phase.equals("prepare")) {
        promises.put(msg.src(), new Promise(msg.lastN(), msg.lastValue()));
        if (promises.size() == acceptorIds.size()) {          // <-- changed
            proposedValue = pickValue();
            phase = "accept";
            return List.of(...);
        }
    }
}
```

**3.5** In the simulator, produce a state where teacher C shows
`wrote: Zoo` while teachers D and E show `wrote: Museum`, **without** the
safety light turning red. Explain why this disagreement is not a violation,
and state precisely what would have to be true for it to become one.

## Part 4 — Implement

**4.1** [`ImplExercise.java`](ImplExercise.java) contains empty versions of
the two pure functions that hold the entire intellectual content of Paxos:
`pickValueFromPromises` (the phase-2 value rule) and `chosenValue` (the
decision rule). Implement both until

```bash
java ImplExerciseTest.java
```

is green — 8 tests. Two of them encode the classic wrong guesses: that the
*most common* reported value wins (it is the *highest-numbered*), and that
three acceptors holding the same value is a decision (it must be the same
value **under the same proposal number**). Expect to be caught by at least
one.

## Part 5 — Handle a failure

**5.1** `Paxos.java`'s acceptors live in memory: a crash erases them, and you
saw in experiment 5 (and predicted in README §10) what that allows. Extend
the `Acceptor` class so that a crash-and-restart is *safe*: give it a
`stableStore` map standing in for disk, decide **which fields** must be
written there and **at which exact moment relative to sending each reply**,
and make restart reload them. Then answer: what breaks if you flush *after*
replying instead of before — and which existing test or experiment would
catch it? (Design your change so experiment 5's scenario, replayed against
your acceptor, ends with one decision instead of two.)

**5.2** A client sends "set config=blue" to proposer P1 and hears nothing
back — P1 crashed somewhere between maybe-finishing and replying. The client
retries through P2. What must the retry propose, what may the system NEVER
assume about the first attempt, and what is the only honest way for the
client to learn the outcome? (Hint: the answer is a read, not a cleverer
write.)

## Part 6 — Design

**6.1** Design the configuration store for a company with **three
datacenters, 50 ms apart**, that must keep serving decisions if any one
datacenter is lost. Decide and justify: (a) how many acceptors and where;
(b) where proposals run (hint: the duel from experiment 4 is now a latency
bill — what kills it?); (c) the latency of one decision in the steady state
and during leader failover; (d) the three graphs you put on the on-call
dashboard; (e) which parts of this lab's design survive unchanged into your
answer, and which had to be added. Write it as half a page; the worked
discussion is in SOLUTIONS.md.
