# Exercises — Google Docs / collaborative editing

Eight rungs. Do them in order; each assumes the one below it. Answers in
[`SOLUTIONS.md`](SOLUTIONS.md) — write yours down before you look, because the
value is in being wrong on paper first.

Everything runs with `java src/<File>.java` from the lab directory.

---

## Rung 1 — Explain

No notation, no jargon. Say it out loud; if you stumble, you don't have it yet.

**1.1** Your manager asks why the team can't just keep emailing the spec around.
Answer in four sentences, including what "lost update" means, without using the
phrase "lost update".

**1.2** Why can't the server simply apply edits in the order it receives them and
send them on? It received them in *some* order — why isn't that enough?

**1.3** Why does your keystroke have to appear on your own screen before any
server has agreed to it? What would the product feel like otherwise, and what
does that decision force the rest of the design to deal with?

**1.4** In one sentence each: convergence, and intention preservation. Then give
an example of a system that has the first and not the second.

---

## Rung 2 — Identify

Open the visualization ([`visualization.html`](visualization.html)) and put it
in "Send edits, rewrite them" mode.

**2.1** Run the third scenario ("The same two edits, rewritten"). On the finished
screen: which panel holds the official order of events? What exactly does
"waiting for confirmation: 1" mean about the state of the world? And what does
the log line `Bob: insert 'S' at 4` prove, given that Bob's finger pressed S at
position 3?

**2.2** In [`src/Ot.java`](src/Ot.java), find the single line that implements the
tie-break. In [`src/Collab.java`](src/Collab.java), find the line that enforces
"at most one edit in flight", and the line that makes a retry safe.

**2.3** In the event feed, find a message that a *client* rewrote after the
server had already rewritten it (the feed says so in words). Why did that
operation need rewriting twice? Which machine knew something the other did not?

**2.4** Take Bob offline, type three characters on his screen, and read his
panel. Which number goes up, and which stays still? What is physically sitting
in his browser at that moment?

---

## Rung 3 — Predict

Write your answer down first, then check.

**3.1** The document is `HAT`. Alice deletes the H (position 0). At the same
moment Bob inserts `!` at position 1. What is the final text on both screens?
Check it in the visualization by hand.

**3.2** Alice is at revision 5, has one edit outstanding and three in her buffer.
A remote edit arrives. How many calls to `transform` happen inside her editor
before it reaches her screen? Now give the general formula.

**3.3** Alice and Bob both go offline, both type five characters, and both come
back at the same moment. Does the document converge? Does anyone see a conflict
prompt? What is the worst thing that can happen to the *meaning* of the text?

**3.4** `java src/Experiments.java raw-vs-ot` runs 500 random schedules under
each design. Before running it: how many of the 500 naive runs do you expect to
diverge — roughly 5, 50, or 500?

---

## Rung 4 — Modify

**4.1** Run `java src/BrokenOt.java`. It removes the tie-break, nothing else.
Which of the two classic properties is lost — **safety** (something wrong
happens) or **liveness** (nothing happens)? Justify with the output.

**4.2** In `Collab.Client.edit`, remove the one-in-flight rule so every keystroke
is sent immediately. Predict what breaks and where, then do it and run
`java src/Tests.java`. Which test fails first, and does the failure message
point at the real cause?

**4.3** Turn `server.dedup = true` on in `Tests.java` and re-run. Nothing
changes. Why not — and what does that tell you about what tests can and cannot
prove about idempotency?

**4.4** In `Ot.transform`, make two deletes of the same character return `a`
unchanged instead of a no-op. Which test catches it? Write down the exact text
you would expect to see instead of `CHAS`, then check.

---

## Rung 5 — Implement

**5.1** Open [`src/YourTransform.java`](src/YourTransform.java) and fill in the
four TODO cases. Run `java src/YourTransform.java`: it plugs your function into
the same server, clients and network as the real one, and runs four named
scenarios plus 1,000 random delivery schedules against it.

It starts at `0 passed, 5 failed`. Get to `5 passed, 0 failed` without looking
at `src/Ot.java`.

**5.2** Once it passes: which of your four cases was the one you got wrong first,
and what does that tell you about which case real implementations get wrong?

---

## Rung 6 — Debug

**6.1** `java src/BrokenOt.java` diverges in 493 of 500 random runs. Which test
in `Tests.java` catches this bug? Which invariant from §12 does it violate — and
which one does it *not* violate?

**6.2** The bug's scenario output shows Alice at `HAT!S` and Bob at `HATS!`.
Both look reasonable. Explain to somebody who has not read this lab why "both
reasonable" is worse than "one crashed".

**6.3** A gap on purpose: no test in this lab asserts that the server's document
equals its own log replayed from the starting text. Write that test. Does it
pass today? Which class of bug would it catch that the existing ten would not?

---

## Rung 7 — Handle failure

Each of these is a small, real piece of engineering. Code them against the
existing simulation.

**7.1 Automatic retry.** Today `Sim.resend(site)` is manual (F1). Give `Client` a
timeout: after N deliveries with no confirmation, it re-sends its outstanding
edit by itself. Prove with a test that (a) it heals a dropped request and (b) it
does *not* duplicate a keystroke when the confirmation was the thing that was
lost. What must be true of the server for (b) to hold?

**7.2 "Your revision is too old".** Today a client that has been away replays
everything it missed (F4, 8,200 transformations). Add a server-side limit: keep
only the last K log entries, and answer a client whose revision is older than
that with a snapshot `{text, revision}` instead of a replay. What must the client
throw away when it accepts that snapshot, and what does the user lose?

**7.3 Backpressure.** Today the server queues broadcasts for a stalled client
forever (F6, 100 queued messages). Cap the queue at K; when it overflows,
disconnect that client and require a fresh open. Which of the three promises in
§5 does this break, for whom, and why is that the right trade?

---

## Rung 8 — Design

No single right answer. Write half a page each; the argument is the deliverable.

**8.1 Three datacentres, 80 ms apart.** Users of one document are spread across
all three. Which parts of the design in §15 survive unchanged, which must move,
and what do you monitor? Be specific about where the document's owning process
lives and what happens to the other two regions' users. What breaks if you try
to run one owner per region?

**8.2 Offline-first, on a phone.** The product requirement is: a week offline,
then a clean merge. Which of OT and CRDT do you choose, and what is the
strongest argument *against* your choice? What does the storage layer look like
now, and what happens to version history?

**8.3 Two hundred editors in one document** — a lecture hall taking notes
together. What is the first thing that falls over? Design the mitigation. Which
of your mitigations would you also apply at *twenty* editors, and which are pure
scale hacks?

**8.4 A different shape of document.** Now design the same product for a
Figma-style canvas: shapes with position, size, colour and z-order, rather than
a string. Which of the four rules in §10 survive? Which conflicts become easy,
and which become harder than text? What would you use instead of insert/delete
operations, and does that change your OT-vs-CRDT answer?

**8.5 The payments crossover.** F2 (lost confirmation → duplicated keystroke) is
the same failure as a lost payment response → duplicated charge. Write down the
three mechanisms this lab used to make retries safe, and say what each one is
called in a payments API.
