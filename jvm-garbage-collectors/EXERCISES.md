# Exercises — JVM garbage collectors

Eight rungs, easiest first. Nothing here asks you to write code. Rungs 1–3 need
only the README; rungs 3–5 use `visualization.html`
(<https://claude.ai/code/artifact/2bd0ad6c-047c-44bf-aacf-48cdc2b2b866>); rungs
6–8 are pencil-and-paper design work.

Answers are in `SOLUTIONS.md`. **Attempt each one before reading it** — for the
prediction rungs especially, a wrong guess you committed to teaches far more than
a right answer you agreed with.

---

## Rung 1 — Explain

Say it back in your own words, out loud or in writing. No notation, no diagrams.

**1.1** Why can a collector not simply ask each object whether it is still being
used? Answer in two sentences, without using the word "reachable".

**1.2** Explain to somebody who has never seen a garbage collector why tracing
looks for the *living* rather than the dead, and why that turns out to be easier.

**1.3** A colleague says: "Generational collection is faster because the young
generation is small." That is not quite the reason. What is the actual reason a
young collection is cheap, and what would make it stop being cheap even if the
young generation stayed exactly the same size?

**1.4** In one sentence each, distinguish: a GC **write barrier**, a GC **load
barrier**, and a **memory barrier** from the `java-memory-model` lab.

---

## Rung 2 — Identify

Open the visualization. Pick the **Generational** collector and press Step about
twenty times.

**2.1** Point at the Eden band. Which cells are reachable objects and which are
garbage the collector has not yet noticed? What is the visual difference, and why
does the page know which is which before any collection has run?

**2.2** Click a cell in the Old generation band. What does the inspector say
about how that object is kept alive? Read the chain out loud.

**2.3** Switch to **G1**. Find a region whose label is `free` and one whose label
is `old`. What has to happen for an `old` region to become `free` again?

**2.4** Run any collector until a pause bar appears. Hover it. Which two
quantities went into that number, and which of the two would grow if you raised
the "share that stay reachable" slider?

**2.5** Switch to **Mark–sweep** and let it run through two collections. Compare
the "Largest free gap" figure with the "Occupied" figure. Now do the same with
**Mark–compact**. Which number differs, and which stays roughly the same?

---

## Rung 3 — Predict

Commit to an answer before you run anything.

**3.1** Generational collector, survival slider at 5%. Eden fills with ~45
objects and 2 survive. Now you raise the slider to 90% and let Eden fill again.
Roughly how much *more* work does the collector do, and which specific counter on
the page will show it? (Scenario 2.)

**3.2** Mark–sweep, a heap that is 35% free, and a request for an 8-cell object.
Does it succeed? Now answer the same question for Mark–compact with the same 35%
free. (Scenario 1.)

**3.3** The write barrier is off. An object in the old generation is pointed at a
brand-new object in Eden, and nothing else refers to the new object. A young
collection runs. What is the state of the old object's field afterwards?
(Scenario 3.)

**3.4** Concurrent marking. `X` is black, `Y` is grey, `Z` is white and only
`Y.child` points at it. The program runs `X.field = Y.child;` then
`Y.child = null;`. What colour is `Z` when marking ends — with the SATB barrier
on, and with it off? (Scenario 4.)

**3.5** ZGC-style collector, allocation slider at maximum, survival at 75%. The
concurrent cycle takes many ticks. Predict what the pause meter looks like after
40 ticks: many small bars, one huge bar, or both? (Scenario 6.)

**3.6** You allocate one object per tick and *every* object stays reachable
forever. Which collector survives longest, and does the answer depend on the
collector at all?

---

## Rung 4 — Modify

Change one thing and reason about what breaks. Use the controls, then check
against what the page reports.

**4.1** Take scenario 3 and switch the write barrier back **on** before running
it. Which property is restored — safety or liveness? What extra work is the JVM
now doing on every single reference assignment in your program, forever, to buy
it?

**4.2** Set the survival slider to 90% and run the **Generational** collector for
60 ticks. The generational hypothesis is now false for this workload. List three
concrete costs you are paying that a plain Mark–compact collector would not
charge you.

**4.3** Run **G1** with the allocation slider at 2, then at 12, and compare the
number of full stop-the-world collections. Explain the difference in terms of a
race, and name the three levers that could win that race.

**4.4** Switch the SATB barrier off and run any *non*-concurrent collector
(Mark–sweep, Mark–compact, Copying, Generational) for 100 ticks. Nothing goes
wrong. Why not? What does that tell you about which collectors need which
barrier?

**4.5** Press "Stick one thread outside a safepoint" and then force a collection.
The pause bar is much taller than the collector's work explains. If you switched
to ZGC, would this pause get shorter? Why or why not?

---

## Rung 5 — Trace

Work these by hand on paper first, then check.

**5.1** A 16-cell heap under Mark–sweep. Objects are one cell.
Roots = `{A}`. References: `A→B`, `B→C`, `C→B`, `D→E`, `E→D`, `F→A`.
Layout: `A B C D E F . . . . . . . . . .` (cells 0–5 occupied).
Write out the mark worklist step by step, then the layout after sweeping, then
the free list. Which objects survived, and which pair did the collector reclaim
that a reference-counting collector could never have touched?

**5.2** Generational, Eden = 6 cells, each survivor space = 2 cells, promotion
threshold = age 3. Four collections happen. Survivors are:
collection 1 → `{p}`; collection 2 → `{p, q}`; collection 3 → `{p, q, r}`;
collection 4 → `{p, r}`.
Track where each object lives and its age after each collection, naming which
survivor space is in use at each collection. Does anything get promoted *early* — that is, before reaching the
threshold? Justify your answer, and say what would change if the threshold were 4
instead of 3.

**5.3** Card table trace. The old generation spans `0x2000`–`0x3000` with
512-byte cards, so card index = `(addr − 0x2000) / 512`. During one cycle the
program performs these reference writes, in order:
`0x2100`, `0x2A40`, `0x2104`, `0x2FF0`, `0x2A48`.
Write the card table after each write. How many cards are dirty at the end, how
many *distinct object writes* did they represent, and how much of the old
generation must the next young collection re-scan?

**5.4** Tri-colour by hand. Roots = `{R}`. Edges: `R→A`, `R→B`, `A→C`, `B→C`,
`C→D`. Marking proceeds one object at a time in worklist order. After `R` and
`A` have been blackened (so `B` and `C` are grey, `D` is white), the program
executes `A.child = C.child;` followed by `C.child = null;`.
Walk marking to completion and state `D`'s final colour, with and without an SATB
barrier. Is this the same failure as scenario 4, or a different one? Justify
your answer by checking the *two* conditions the lost-object problem requires.

---

## Rung 6 — Spot the flaw

Each of these is a plausible design with something wrong in it. Construct a
concrete run that breaks it, and name the guarantee it violates.

**6.1** *"Card tables are wasteful — they make us re-scan whole 512-byte cards.
Let us record the exact field address instead of a card index, in a hash set."*
Name two things that get worse, and one workload where this design collapses.

**6.2** *"Floating garbage is a bug. We should fix it by re-running the marking
phase at the end of each cycle to catch objects that died during marking."*
Construct a run where this never terminates. What property have you traded away,
and which of stage 5's four requirements does the original design deliberately
weaken instead?

**6.3** *"Since a black object is never re-scanned, we can skip the write barrier
entirely as long as the application only ever assigns references to objects it
just allocated."* Find the hole. (Hint: what colour is a just-allocated object,
and what happens on the *next* cycle?)

**6.4** *"We run ZGC and our p99 is still 300ms during GC. ZGC's pauses are
sub-millisecond, so the GC is clearly not the problem — it must be the database."*
Give two GC-related explanations that are entirely consistent with both
statements being true.

**6.5** *"Our service was OOMing, so we set `-Xmx` to the full container memory
limit. Now it does not OOM any more, it just gets killed."* Explain exactly what
changed, why there is no stack trace, and what the correct setting is.

---

## Rung 7 — Handle failure

Extend a design on paper so that it survives something it currently does not.

**7.1** A young collection is sound only because the write barrier tells it about
old→young references. Now consider a collector that can evacuate an *arbitrary
subset* of regions, the way G1 does. The old design's card table records only
"something in this card was written" — it has no idea *where the reference
points*. Design what has to be recorded instead, say what maintains it, and
explain what this costs on every reference write. Then say what happens to that
cost as the number of regions grows, and how a real implementation keeps it
bounded.

**7.2** Your payment service is on G1 with a 200ms pause target and meets its
SLO — except when a merchant uploads a bulk file, at which point one request
allocates a 40MB byte array and the service does three full GCs in a row. The
region size is 4MB. Walk through exactly what is happening, then design three
different fixes: one JVM flag change, one code change, one architectural change.
For each, state what it costs and what it does *not* fix.

**7.3** A concurrent collector loses the race when
`heap_free < allocation_rate × cycle_duration + floating_garbage`. You cannot
change the code and you cannot get more memory. What can you still change on the
right-hand side, and what is the risk of each move?

---

## Rung 8 — Design

Full design prompts. There is no single right answer; the worked discussions in
`SOLUTIONS.md` are one defensible position each.

**8.1 — The payment authorization service.**
Design the memory profile for a payment authorization service:
- 8,000 requests/second per instance, p99 latency budget **50ms end to end**, of
  which the JVM may spend at most **8ms** on GC pauses.
- Each request allocates ~40 objects totalling ~12KB, of which essentially
  nothing survives the response.
- A merchant cache of 200,000 entries, ~2KB each (~400MB), refreshed every 6
  hours by rebuilding the whole map and swapping the reference.
- Runs in a container with a hard 4GB memory limit, 4 CPUs.

Choose a collector and justify it against the alternatives. Size the heap and say
what you are leaving for non-heap memory, itemised. Predict the failure mode of
the 6-hourly cache rebuild specifically, and say what you would change to survive
it. List the five things you would put on a dashboard and the threshold that
would page someone.

**8.2 — Three datacentres.**
The same service now runs in three regions, 50ms apart, with a shared read-mostly
reference dataset. Someone proposes keeping the full 30GB dataset in-heap in
every instance to avoid cross-region reads. Evaluate that proposal in GC terms
specifically: what happens to marking cost, to pause time, to the choice of
collector, and to your container sizing. Then propose an alternative and say what
it costs in latency. Connect your answer to `caching` and to `pacelc`.

**8.3 — The 3am page.**
You are paged: p99 for the payment service has gone from 30ms to 2.4s, starting
40 minutes ago. Throughput is unchanged. CPU is up ~15%. The last deploy was
three days ago. Write the sequence of things you would look at, **in order**, and
for each one say what result would move you to which branch. You have
`-Xlog:gc*`, JFR, and the ability to take a heap dump on one instance. Name at
least four distinct GC failure modes from stage 11 that fit the symptoms, and the
single observation that separates each pair.
