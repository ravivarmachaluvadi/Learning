# Exercises — gossip protocols

Eight rungs. Rungs 1–3 need only the page and your head; 4–7 need the code; 8 is
a design conversation. Answers in [`SOLUTIONS.md`](SOLUTIONS.md) — attempt first,
especially the predictions.

---

## Rung 1 — Explain

**1.1** Explain to a colleague who has never heard of this why a cluster of 500
machines does not just keep the membership list on one machine. Do not use the
words CAP, quorum or consensus.

**1.2** In two sentences, why does telling two random peers per round reach
everybody in about five rounds rather than 250?

**1.3** A colleague says: "Gossip is just eventual consistency for the membership
list, so we can use it to elect the leader too." What is wrong with the second
half of that sentence?

---

## Rung 2 — Identify

Open [`visualization.html`](visualization.html) and reset it.

**2.1** Press **Tell n1 something new** and run three rounds. How many machines
have the news, and what is the ratio between consecutive rounds?

**2.2** Click any machine. What does the inspector show it believes about the
other 19? Now look at the ring: whose opinion is it showing while a machine is
selected — the cluster's or that machine's?

**2.3** In the belief grid, find a column that is not all one colour. What does a
mixed column mean, and which stat box in the panel above puts a number on it?

**2.4** In [`src/PeerState.java`](src/PeerState.java), find the four lines that
decide every disagreement in the whole protocol. In
[`src/Node.java`](src/Node.java), find the one place a node writes a number about
*itself* that every other node must accept.

---

## Rung 3 — Predict

Write the answer down before running the command.

**3.1** 5,000 nodes, fanout 3, push-pull. If 10 nodes converge in 2 rounds, how
many rounds for 5,000? `java src/Experiments.java 2`

**3.2** 1,000 nodes, fanout 1, and each node stops repeating a fact once it has
heard it back **once**. How many nodes never learn it, and can they recover?
`java src/Experiments.java 5`

**3.3** Which is slower to reach the *last* node: push-only or pull-only? By how
much? `java src/Experiments.java 4`

**3.4** 100 machines, none of which crash, on a network dropping 90% of packets,
with a 3-round suspicion timeout. After 40 rounds, what fraction of all beliefs
say DEAD? `java src/Experiments.java 10`

**3.5** The cable between n1 and n5 is cut; every other link is fine. Under
Cassandra-style heartbeat gossip, how many of the 20 nodes end up suspecting n5?
`java src/Experiments.java 8`

---

## Rung 4 — Modify

For each: what breaks, why, and is what you lost **safety** (something wrong
happens) or **liveness** (something good stops happening)?

**4.1** In `Experiments.convergenceVsSize`, change fanout from 3 to 1. Predict
the new rounds column before running, then explain the relationship between the
two columns in one sentence.

**4.2** In `Sim.java`, set `c.suspectAfter = 1`. Run it. What happens in scene 4
(the paused node) and what would this setting do to a cluster whose nodes garbage
collect for 200 ms every few seconds?

**4.3** In `Cluster.java`, set `maxEntriesPerMessage = 3` and run
`java src/Experiments.java 9`. What happens to the disagreement window, and what
real constraint would make you accept that cost?

**4.4** In `Node.detect`, delete the `SUSPECT` state — go straight from `ALIVE`
to `DEAD` on timeout. Which test fails first, and what has the cluster lost?

---

## Rung 5 — Implement

**5.1 — the digest exchange (F8).** Right now every message carries the whole
membership table. Implement a two-step exchange: a node sends
`peer → (incarnation, heartbeat)` only, the receiver replies asking for the rows
where its own copy is older, and only those rows are sent in full. Measure the
bytes moved (use `entriesTransferred`) at n = 500 before and after, and the cost
in rounds. Add this test:

```java
static void a_digest_exchange_converges_like_a_full_one() {
    Cluster full = new Cluster(200, 3), digest = new Cluster(200, 3);
    digest.useDigests = true;                       // your new knob
    for (Cluster c : List.of(full, digest)) {
        c.fanout = 3; c.suspectAfter = 10_000;
        c.publishConfig("n1", "v", 2);
    }
    int rf = 0, rd = 0;
    while (full.nodesWith(2) < 200 && rf < 50) { full.runRound(); rf++; }
    while (digest.nodesWith(2) < 200 && rd < 50) { digest.runRound(); rd++; }
    check("a_digest_exchange_converges_like_a_full_one",
            digest.nodesWith(2) == 200 && rd <= rf + 2,
            "full: " + rf + " rounds / " + full.entriesTransferred + " entries; "
          + "digest: " + rd + " rounds / " + digest.entriesTransferred + " entries");
}
```

**5.2 — an adaptive timeout (a poor engineer's phi-accrual).** Instead of a fixed
`suspectAfter`, have each node track the mean gap between updates it has seen for
each peer, and suspect when silence exceeds, say, four times that peer's mean.
Re-run `java src/Experiments.java 7`. Does any row now look good in both columns?
What new failure have you introduced for a peer that has been silent since boot?

---

## Rung 6 — Debug

[`src/BrokenMerge.java`](src/BrokenMerge.java) contains one planted bug in the
merge rule.

```bash
java src/Tests.java broken
```

**6.1** Which tests fail? What is the single wrong idea behind all of them?

**6.2** The buggy rule reads perfectly sensibly out loud — say it out loud, then
say why it is wrong.

**6.3** Which invariant from README §12 does it violate, and which *user-visible*
symptom would you see in a real cluster running it? (Hint: think about what
happens to a node after a garbage-collection pause.)

**6.4** Write the smallest possible test that catches it.

---

## Rung 7 — Handle a failure the lab does not survive

**7.1 — the detector meltdown (F5).** At 90% packet loss, 100 healthy machines
write each other off and the cluster goes silent, permanently. Design a
mitigation and argue its cost. Consider: a floor on how many peers may be
suspected at once; requiring a second opinion before a verdict becomes binding
(what does Redis Cluster do?); making a node that cannot reach *anyone* distrust
its own judgement rather than the cluster's (what does Lifeguard do?). Which of
these can a node decide with purely local information?

**7.2 — a node rejoins with the same name and a fresh memory.** A machine is
rebuilt: same hostname, same id, `incarnation` back to 1. The cluster still holds
`n7: DEAD, incarnation 9`. Trace what happens to the rebuilt node, step by step.
Then design the fix — and say what it costs you in operations (what must now be
generated, stored, or coordinated?).

**7.3 — a malicious or buggy node.** One node starts gossiping `everyone: DEAD,
incarnation 999`. Trace the damage. Then answer: which of this lab's mechanisms
limit it, which amplify it, and what class of protocol would you need to survive
it properly?

---

## Rung 8 — Design

**8.1 — three datacentres.** 300 nodes, 100 per DC, 80 ms between DCs, and the
cross-DC links are the flaky ones. Design the gossip: what is the fanout within
and between DCs, which nodes talk across, what are the timeouts, and what happens
when a DC is cut off entirely? Which parts of this lab's design survive, and
which must change?

**8.2 — 10,000 nodes.** Which numbers in this lab break first at that size, in
order? For each: the mechanism that breaks, the symptom you would see, and the
production technique that addresses it.

**8.3 — the alert you would actually page on.** You must choose one metric that
pages a human at 3am for membership problems. Which, at what threshold, and why
not the obvious alternatives? What failure would your choice miss?

**8.4 — gossip or a coordinator?** You are designing the control plane for a
service mesh: 2,000 proxies that must learn about each other and about routing
config. Argue both designs, then commit to one and name the specific failure that
would make you regret it.
