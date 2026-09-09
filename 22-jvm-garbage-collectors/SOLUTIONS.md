# Solutions — JVM garbage collectors

Rungs 7 and 8 have no single right answer; those are worked discussions, one
defensible position each. Everywhere a claim is about the real JVM rather than
the lab's model it is marked **[JVM]**; figures from the visualization are marked
**[MODEL]**; hand-worked traces are **[HAND]** and you can check every step.

---

## Rung 1 — Explain

**1.1** Whether an object is still in use depends on things stored elsewhere in
the program — fields you have never seen, in objects the object itself has no
knowledge of. Nothing you could store inside the object could ever answer the
question, because the answer is a property of the whole graph rather than of the
object.

**1.2** Finding the dead means proving a negative about every object: that
nothing anywhere will ever name it again. Finding the living is a mechanical
walk — start from the handful of places the program can reach memory from (thread
stacks and static fields), follow every reference outward, and mark what you
touch. Whatever you did not touch cannot be named by any line of code, so it is
dead, and you never had to prove anything about it individually. Cycles stop
being a problem for free: the walk simply never arrives.

**1.3** The reason a young collection is cheap is that its cost is proportional
to the number of objects that **survived**, and completely independent of how
much garbage there was — the survivors are copied out, then the whole space is
declared free in one operation, so the dead are never visited at all. Size is
almost incidental. Keep the young generation exactly the same size but make 90%
of the objects in it survive, and the same collection now copies forty-odd
objects instead of two, promotes many of them into the old generation, and forces
the old generation to be collected sooner. **[MODEL]** Scenario 2 does exactly
this and the "Cells copied" counter is where you watch it happen.

**1.4**
- **GC write barrier** — a couple of instructions the JIT injects after every
  reference *store*, recording that a reference was written so the collector can
  find it later (card marking, SATB).
- **GC load barrier** — code injected on every reference *read*, which checks
  whether the reference is stale and, if so, follows a forwarding pointer and
  repairs the field. It is what makes moving objects concurrently possible.
- **Memory barrier** (`java-memory-model`) — a CPU instruction constraining the
  *ordering* and visibility of memory operations between threads. It has nothing
  to do with garbage collection; the shared word is a historical accident.

---

## Rung 2 — Identify

**2.1** Reachable objects are drawn in the solid indigo fill; allocated-but-
unreachable ones are the faded rust fill; free cells are blank. The page knows
which is which because it recomputes reachability from the roots on every render
— that is *the truth about liveness*, independent of whether any collector has
noticed yet. The gap between the rust cells and the next collection is exactly
the memory your program has abandoned but not yet got back.

**2.2** The inspector prints the chain of references from a root, for example
`staticCache → #7 → #23`. The point to take away is in the wording: the object is
alive **because that chain exists**, not because anything about the object itself
says so. Break any single link in that chain and it becomes garbage at that
instant, without anything happening to the object.

**2.3** An `old` region becomes `free` only by being chosen for a collection set
and evacuated: its live objects are copied out into other regions, and the whole
region is then freed in one operation. It will only be chosen if it is
garbage-rich enough to be worth the copying — a region that is 90% live gives
back very little for a lot of copying, so G1 leaves it alone, possibly forever.

**2.4** The tooltip shows the pause in model milliseconds. It is built from
**objects scanned** (marking and card scanning) and **cells copied** (evacuation
or compaction), per the formula in the page footer. Raising the survival share
increases both — more objects survive to be marked, and more of them have to be
copied.

**2.5** Under Mark–sweep the "Occupied" figure drops after each collection but
"Largest free gap" stays small and gets worse over time. Under Mark–compact,
"Occupied" behaves the same way but "Largest free gap" jumps to nearly all of the
free memory, because compaction slides every survivor together. **Occupancy is
about how much you freed; largest gap is about whether you can use it.**

---

## Rung 3 — Predict

**3.1** Far more. **[MODEL]** With 45 objects in Eden, going from 2 survivors to
~40 multiplies the copying by roughly twenty. Watch **Cells copied** and
**Promoted to old**: both climb sharply, and the pause bars grow with them.
Within a few more collections the old generation fills and a full stop-the-world
collection appears — which is the thing the generational design existed to avoid.

**3.2** **Mark–sweep: it fails.** 35% free says nothing about whether eight
adjacent cells exist, and sweeping never moves anything, so the free space is
scattered exactly where the dead objects happened to lie. **[MODEL]** Scenario 1
ends in `OutOfMemoryError` with a largest gap of 2 cells.
**Mark–compact: it succeeds immediately.** Compaction slides every survivor to
one end, so all 35% is one contiguous block. Same amount of free memory, entirely
different answer — which is the whole reason moving collectors exist.

**3.3** The new object is freed while the old object still points at it. The
marking walk began at the thread stacks and static fields, followed references
only into the young generation, and never entered the old generation — so the
only reference to the new object was never seen. Afterwards the old object's
field is a **dangling pointer** into an Eden slot that will shortly hold a
completely different object. **[MODEL]** Scenario 3, and the page's audit reports
it as CORRUPTION.

**3.4** **With SATB off: `Z` stays white and is reclaimed** even though it is
reachable through `X`. `X` was already black, so the collector will never look at
its fields again; `Y` was grey, but by the time it is scanned `Y.child` is null.
**With SATB on: `Z` survives.** The barrier records the overwritten value of
`Y.child` before letting it go, pushing `Z` onto the marking worklist.

**3.5** **Both.** Many small bars — the short initial-mark and remark pauses,
which is what a concurrent collector is supposed to produce — and then one bar an
order of magnitude taller, when the collector loses the race and the JVM falls
back to a full stop-the-world compaction. **[MODEL]** In one run of scenario 6
the small bars were 1.2–1.5 model-ms and the fallback bars 3.8–5.3. The lesson is
the shape: concurrency did not remove the pause, it converted it into a race, and
the pause returns whenever the race is lost.

**3.6** Every collector dies, and roughly as quickly, because the cost of a
collection is proportional to the **live** data and the benefit is proportional
to the **garbage** — and here there is no garbage. Each collection does full
price for nothing. The differences are only in how they die: sweep-based ones
fragment on the way, moving ones spend everything on copying, and a concurrent
one stalls threads first. **[JVM]** Parallel GC has an explicit heuristic for
this — `OutOfMemoryError: GC overhead limit exceeded` when it spends over 98% of
its time collecting and recovers under 2% of the heap — which exists to turn a
slow death into a fast, diagnosable one.

---

## Rung 4 — Modify

**4.1** **Safety** is restored — the property that a reachable object is never
reclaimed. (Liveness was never in danger here; the broken collector was perfectly
prompt, it was just wrong.) The price is a couple of instructions after *every
reference-field assignment in the entire program*, forever, dirtying one byte in
the card table. **[JVM]** That is the fee that makes partial collection possible
at all, and it is why the barrier is not optional in any real JVM.

**4.2** Three costs a plain Mark–compact collector would not charge:
1. **Repeated copying of the same objects.** Survivors bounce between the two
   survivor spaces, being copied again on every collection until they are
   promoted. A non-generational collector moves each survivor at most once per
   collection.
2. **The write barrier on every reference store**, plus the card-scanning work at
   the start of every young collection. Pure overhead if the young/old split
   is not paying for itself.
3. **Premature promotion**, which fills the old generation with objects that were
   about to die — so you end up doing the whole-heap collection anyway, *and* you
   paid for the generational machinery on the way there.
The general statement: generational collection is a bet on your program's
behaviour, and when the bet loses you pay the overhead and get none of the win.

**4.3** At allocation 2 the collector finishes its cycle comfortably before the
heap fills; at 12 it does not, and you get stalls followed by full collections.
The race is `heap_free` versus `allocation_rate × cycle_duration + floating
garbage`. Three levers: **more heap** (raise the left-hand side — usually the
most effective and the least clever), **start the cycle earlier** so the
right-hand side has fewer bytes to consume before the cycle ends
(**[JVM]** `-XX:InitiatingHeapOccupancyPercent` in G1, though G1 adapts this
automatically by default), and **allocate less** — the only fix that is free at
runtime and the only one that requires changing code.

**4.4** Nothing goes wrong because SATB exists solely to protect **concurrent
marking** — the case where the program mutates the graph while the collector is
walking it. Mark–sweep, Mark–compact, Copying and Generational all mark with
every mutator thread suspended, so the graph cannot change mid-walk and there is
nothing to lose. The general rule: **you need a write barrier whenever you
collect only part of the heap, and a marking barrier (SATB or incremental update)
whenever you mark while the program runs.** They are answers to different
questions and a collector may need one, both, or neither.

**4.5** **No, it would not get shorter.** A pause is *time-to-safepoint plus the
collector's work*, and every thread waits for the slowest to arrive. If one
thread takes 800ms to reach a safepoint, a sub-millisecond ZGC pause still costs
you 800ms. **[JVM]** This is the failure most often misdiagnosed as "GC is slow"
when GC is barely involved; `-Xlog:safepoint` prints the two components
separately, and the culprit is frequently a long counted loop that HotSpot
compiled without a safepoint poll.

---

## Rung 5 — Trace

### 5.1 Mark–sweep by hand **[HAND]**

Roots `{A}`; edges `A→B`, `B→C`, `C→B`, `D→E`, `E→D`, `F→A`.

| step | grey worklist | action | marked so far |
|---|---|---|---|
| 1 | `[A]` | pop A → black; child B is white → grey | `{A}` |
| 2 | `[B]` | pop B → black; child C is white → grey | `{A,B}` |
| 3 | `[C]` | pop C → black; child B already black → skip | `{A,B,C}` |
| 4 | `[]` | worklist empty → marking done | `{A,B,C}` |

Layout after sweeping cells 0–15:

```
before:  A  B  C  D  E  F  .  .  .  .  .  .  .  .  .  .
after:   A  B  C  .  .  .  .  .  .  .  .  .  .  .  .  .
free list: [3..15] — 13 cells, contiguous
```

Survivors: `A`, `B`, `C`. Reclaimed: `D`, `E`, `F`.

**`D` and `E` are the pair reference counting could never touch.** Each holds a
reference to the other, so both counters sit at 1 forever, even though nothing
outside their little island points at either. Tracing never arrives at them, so
they are never marked, so they are collected — the cycle cost the walk nothing at
all. `F` is different: nothing points at `F`, so its count is 0 and reference
counting *would* have freed it. Note also that `C→B` terminated at step 3 for one
comparison against the mark bit.

**One honest caveat about this trace:** the free space came out contiguous only
because the dead objects happened to be lying at the top of the heap. That is
luck, not a property of sweeping. Move `D` and `E` to cells 1 and 3 and the same
collection leaves you with holes — which is exactly what scenario 1 shows after
many cycles.

### 5.2 Generational aging **[HAND]**

Eden = 6, `S0` = `S1` = 2, promotion threshold = age 3. The survivor spaces
alternate: each collection copies into whichever one is empty.

| collection | sources | ages after | S0 | S1 | Old |
|---|---|---|---|---|---|
| 1 | Eden | `p`→1 | `p@1` | — | — |
| 2 | Eden + S0 | `p`→2, `q`→1 | — | `p@2, q@1` | — |
| 3 | Eden + S1 | `p`→3 **promoted**, `q`→2, `r`→1 | `q@2, r@1` | — | `p` |
| 4 | Eden + S0 | `r`→2; `q` died | — | `r@2` | `p` |

**Nothing is promoted early**, and it is worth seeing why it was close. At
collection 3 there are three survivors and the survivor space holds two. It fits
only because `p` reached age 3 on that very collection and was promoted **on
merit**, leaving exactly `q` and `r`.

**If the threshold were 4**, `p` would still be young at collection 3, three
objects would be competing for two cells, and one of them would be promoted at
age 1 or 2 purely because there was nowhere else to put it. That object then
lives in the old generation, where only an expensive collection can reach it —
which is failure F3, and it is why the tenuring threshold, the survivor size and
the survival rate are one interacting system rather than three independent knobs.
**[JVM]** HotSpot recognises this and computes a *dynamic* tenuring threshold,
lowering it when the survivor space is over-full — which can make the problem
compound rather than self-correct.

At collection 4, notice what did **not** happen: `p` is in the old generation, so
the young collection did not scan it, did not copy it, and did not look at it at
all. That is the entire payoff of the design.

### 5.3 Card table **[HAND]**

Card index = `(addr − 0x2000) / 512`. The old generation is `0x1000` = 4096 bytes
= 8 cards.

| write | offset | card | card table after |
|---|---|---|---|
| — | — | — | `[0 0 0 0 0 0 0 0]` |
| `0x2100` | 256 | 0 | `[1 0 0 0 0 0 0 0]` |
| `0x2A40` | 2624 | 5 | `[1 0 0 0 0 1 0 0]` |
| `0x2104` | 260 | 0 | `[1 0 0 0 0 1 0 0]` — already dirty |
| `0x2FF0` | 4080 | 7 | `[1 0 0 0 0 1 0 1]` |
| `0x2A48` | 2632 | 5 | `[1 0 0 0 0 1 0 1]` — already dirty |

**Three cards dirty, from five reference writes.** The next young collection
re-scans 3 × 512 = **1536 bytes** of old generation instead of all 4096 — 37.5%
rather than 100%.

Two things to take from this. First, the win: the collector examines a third of
the old generation instead of all of it, and on a real 4GB old generation with a
handful of dirty cards the ratio is thousands to one. Second, the deliberate
imprecision: a card records *"something in these 512 bytes was written"*, not
*which field*. Writes to `0x2100` and `0x2104` are two different fields, almost
certainly in the same object, and they cost exactly the same as one. The
collector must re-scan every object in a dirty card. Smaller cards mean more
precision and a bigger table; this is a tuning trade-off, not a solved problem.

### 5.4 Tri-colour with a mutator **[HAND]**

Roots `{R}`; edges `R→A`, `R→B`, `A→C`, `B→C`, `C→D`.
State given: `R` black, `A` black, `B` grey, `C` grey, `D` white.

The program runs `A.child = C.child;` — `C.child` is `D`, so this **overwrites**
`A`'s reference to `C` with a reference to `D`. Then `C.child = null;` removes
`C→D`.

Marking continues **without SATB**:

| step | grey worklist | action | result |
|---|---|---|---|
| 1 | `[B, C]` | pop `B` → black; child `C` already grey → skip | `D` still white |
| 2 | `[C]` | pop `C` → black; `C.child` is now null → no children | `D` still white |
| 3 | `[]` | worklist empty → marking ends | **`D` is white** |

`D` is reclaimed. It is reachable the whole time: `R → A → D`.

**With SATB**, the second statement is the one that matters. Before `C.child` is
overwritten, the barrier records its old value `D` and pushes it onto the
worklist, so `D` goes grey, is scanned, and survives. (The first statement also
overwrote `A`'s reference to `C`, and the barrier records `C` too — harmless,
`C` was already grey.)

**Is this the same failure as scenario 4?** Yes — check both required conditions:
1. *A reference to a white object was written into a black object.* `A` is black,
   `D` is white, and `A.child = D` created exactly that edge. ✓
2. *Every path from a grey object to that white object was destroyed before the
   grey object was scanned.* The only grey path was `C → D`, and `C.child = null`
   destroyed it while `C` was still grey. ✓

The only difference from scenario 4 is cosmetic: here a single assignment did
double duty, both creating the black→white edge and removing `A→C`. The shape is
identical, and so is the fix.

---

## Rung 6 — Spot the flaw

**6.1 Exact field addresses instead of cards.**
Two things get worse. **Footprint and allocation:** a hash set of addresses grows
with the number of *writes*, not with the size of the heap, and it must be
maintained — inserting into a concurrent hash set is dramatically more expensive
than storing one byte at a computed index. A card table's cost is bounded and
known in advance; this structure's is neither. **Barrier cost:** the card-marking
barrier is two instructions with no branch and no allocation. A hash-set insert
in the barrier is orders of magnitude more code on the hottest path in the
runtime.
**The workload where it collapses:** any program that repeatedly writes the same
fields — a loop updating an array of references, a counter object being
reassigned in a tight loop. The card table absorbs a million writes to the same
region into one dirty byte; the exact-address set either grows without bound or
pays deduplication costs on every write. The card table's imprecision is not a
weakness it tolerates, it is **the mechanism by which the cost is bounded**.

**6.2 "Re-run marking to eliminate floating garbage."**
Construct the non-terminating run: the program allocates steadily, and every
marking pass takes longer than the interval in which new garbage appears. Pass 1
marks the graph and finds floating garbage. Pass 2 runs — and during pass 2 the
program creates more objects that die after that pass began. Pass 3, and so on.
Any program whose allocation rate is non-trivial gives you an infinite regress,
because **the only way to have zero floating garbage is to stop the program**,
and stopping the program is precisely what you gave up to run concurrently.
What you traded away is **liveness** (the guarantee that collection terminates),
in exchange for a stronger completeness property nobody needs. The original
design instead deliberately weakens **requirement 2 of stage 5**: it promises
that unreachable objects are *eventually* reclaimed, not immediately. That word
"eventually" is the entire price of concurrency, and it is a good deal — a little
extra memory held for one extra cycle, in exchange for pauses that no longer
scale with the heap.

**6.3 "No barrier if we only assign freshly allocated objects."**
The hole is in the phrase "just allocated". **[JVM]** During concurrent marking,
newly allocated objects are typically **allocated black** — treated as live for
this cycle without being scanned, precisely so they cannot be missed. So within
one cycle the claim looks true. The failure is on the *next* cycle: the object is
no longer fresh, it is now an ordinary white object at the start of the new
marking, and the black object holding the only reference to it is an ordinary
black object with no record that its field was ever written. The lost-object
problem arrives one cycle late, which makes it harder to diagnose rather than
less severe. The design also breaks immediately for a generational collector,
where a barrier is needed for a completely different reason — recording
old→young references — that has nothing to do with marking colours at all.

**6.4 "ZGC pauses are sub-millisecond, so it must be the database."**
Two GC-related explanations consistent with both statements:
1. **Allocation stalls.** When the collector cannot free memory fast enough,
   allocating threads block waiting for it. The thread is stopped for hundreds of
   milliseconds and no GC *pause* was ever recorded, because it was not one.
   **[JVM]** ZGC logs these separately as allocation stalls; they will not show
   up if you are only grepping for pause lines.
2. **Time to safepoint.** The pause is time-to-safepoint plus the collector's
   work. A thread stuck in a counted loop with no safepoint poll makes every
   other thread wait, and the collector's own contribution really is
   sub-millisecond. **[JVM]** `-Xlog:safepoint` separates the two numbers.
A third, worth mentioning: **throughput**. ZGC's load barrier costs CPU on every
reference read, so under saturation the service can be slower everywhere without
any single pause being long.

**6.5 `-Xmx` set to the container limit.**
What changed: nothing about the leak or the memory pressure — only *who kills
the process*. A JVM's footprint is the heap **plus** metaspace, the JIT code
cache, thread stacks (roughly 1MB per platform thread), GC data structures like
card tables and remembered sets, direct and mapped `ByteBuffer`s, and native
library allocations. With `-Xmx` equal to the whole container limit, the process
exceeds the cgroup limit long before the heap is full, so the kernel's OOM killer
gets there first.
Why there is no stack trace: the kernel sends `SIGKILL`. The JVM is not asked, it
is terminated. There is no `OutOfMemoryError`, no heap dump, no shutdown hook —
just exit code 137 and a `Killed` in the kernel log. **That silence is the
diagnostic signature**: a clean application log with an abrupt end means the
process was killed from outside.
The correct setting: **[JVM]** stop setting `-Xmx` from the container limit by
hand. The JVM has been container-aware by default since JDK 10
(`UseContainerSupport`), so set `-XX:MaxRAMPercentage=70` (lower with many
threads or heavy direct-buffer use) and let it read the cgroup limit itself,
leaving native memory the headroom it needs. Note also that ZGC's larger
footprint brings this ceiling closer than G1's.

---

## Rung 7 — Handle failure (worked discussions)

### 7.1 From card tables to remembered sets

**The problem.** A card table answers "was anything in this 512-byte card
written?" That is enough for a fixed young/old split, because there is only one
direction that matters: old → young. A collector that evacuates an *arbitrary
subset* of regions needs to answer a different question: **"which references
point *into* region R from outside it?"** — for any R it might choose. Dirtiness
alone does not say where a reference points.

**What has to be recorded.** A **remembered set per region**: for each region,
the set of locations elsewhere in the heap that hold references into it. Real
implementations store card indices rather than exact addresses, at coarsening
levels — sparse (a few cards), fine (a bitmap), and coarse ("some card in this
whole region points here, rescan it") — trading precision for space as a region
accumulates more incoming references.

**What maintains it.** The write barrier still runs on every reference store, but
it now does more: it checks whether the store crosses a region boundary, and if
so enqueues the card onto a queue. **[JVM]** G1 hands that queue to background
**concurrent refinement threads**, which do the expensive part — working out
which region is referenced and updating that region's remembered set — off the
application thread. That is a deliberate design decision: keep the barrier itself
short, and move the real work to threads that are not on the request path.

**What it costs.** The barrier is noticeably more expensive than Parallel GC's
single card mark, plus you now pay refinement CPU and the memory for the
remembered sets themselves. **[JVM]** This is the main reason G1 has lower
throughput than Parallel GC on batch workloads: the barrier cost is the fee for
pause control.

**As the number of regions grows.** The naive cost is quadratic-ish — more
regions means more sets, and a region referenced from everywhere has a huge one.
Real implementations bound it by **coarsening** (degrading a precise set to
"rescan this whole region" once it passes a threshold, trading scan time for
memory) and by never building remembered sets for young regions at all, since
every collection includes all of them and they are always scanned anyway.

### 7.2 The 40MB byte array on G1 with 4MB regions

**What is happening, step by step.** 40MB with 4MB regions is at least ten
adjacent regions, and the object is enormously larger than half a region so it is
**humongous**: it cannot go in Eden, it needs a run of contiguous free regions of
its own. In a heap that has been running a while, the region map is not empty in
one clean block — free regions are scattered among live old ones. The allocation
fails; G1 collects; an ordinary evacuation frees whichever regions were
garbage-rich, which does not necessarily produce **ten in a row**; the allocation
still fails; G1 escalates to a full compacting collection. And because the upload
is a burst, this happens again on the next one — hence three in a row. Humongous
regions are also reclaimed less flexibly than ordinary ones, which makes the
region map worse over time rather than better.

**Fix 1 — a flag.** `-XX:G1HeapRegionSize=32m` (the maximum). A 40MB object still
needs two adjacent regions rather than ten, which is far more likely to be
available. **Cost:** larger regions mean coarser collection-set granularity, so
each evacuation pause has less freedom to hit its target, and small heaps waste
more per region. **Does not fix:** the underlying pattern. A 100MB upload will
break it again.

**Fix 2 — code.** Stop materialising the whole file in one array: stream it, or
process it in bounded chunks, or hold it in a pooled direct `ByteBuffer` outside
the heap. **Cost:** real engineering work, and off-heap buffers move the problem
to native memory where you must manage the lifetime yourself and where a leak has
no heap dump to find it in. **Does not fix:** anything else that allocates large
arrays — this is one call site.

**Fix 3 — architecture.** Bulk uploads do not belong on the request path of a
latency-sensitive service. Accept the file to object storage, enqueue a job, and
process it in a separate worker fleet — where you can run Parallel GC and let it
pause for two seconds because nobody is waiting. **Cost:** an extra service, an
extra queue, and the upload becomes asynchronous, which changes the API contract.
**Does not fix:** anything, if a single upload is still 40MB in one array — it
just moves the pain to a place where the pain is affordable. That is usually the
correct move, and it is worth saying explicitly: **most GC problems in a
latency-sensitive service are fixed by not doing that work in that process.**

### 7.3 Winning the race without more memory or code changes

The inequality is `heap_free < allocation_rate × cycle_duration + floating
garbage`. With the left side fixed and the code fixed, three things remain:

1. **Shorten `cycle_duration`** by giving the collector more CPU — more
   concurrent GC threads (**[JVM]** `-XX:ConcGCThreads`). **Risk:** those cores
   come out of your application's budget. If you are CPU-bound, throughput drops
   and the effective allocation rate may not fall as much as you hoped; you can
   make things worse.
2. **Start the cycle earlier** so less is consumed before it ends
   (**[JVM]** `-XX:InitiatingHeapOccupancyPercent` in G1, though it adapts
   automatically by default). **Risk:** more cycles per unit time, so more total
   GC CPU and more floating garbage per unit of reclaimed memory. Start far too
   early and you thrash.
3. **Reduce `allocation_rate` from outside the code** — lower the concurrency
   limit, add admission control, or shed load. **Risk:** this is a throughput
   reduction by another name, and you must be honest that you have chosen to
   serve fewer requests in order to serve them predictably. Sometimes that is
   exactly right, and it should be a stated decision rather than an accident.

Worth stating plainly: if none of these is acceptable, the honest answer is that
**the instance is under-provisioned for its workload**, and the fix is memory or
fewer requests per instance. Tuning cannot create headroom that does not exist.

---

## Rung 8 — Design (worked positions)

### 8.1 The payment authorization service

**Collector: G1**, and I would want measurements before moving off it.

*Why not the others.* Parallel GC is out immediately — a multi-second full GC
against a 50ms budget is not a trade-off, it is a failure. Serial is out at 4
CPUs and this allocation rate. ZGC is the tempting alternative and the honest
reason to hold off is that its 5–15% throughput cost and its larger footprint are
both expensive in a 4GB container, and an 8ms pause budget is comfortably within
what a well-sized G1 achieves with a ~600MB live set. Revisit ZGC if the live set
grows or the budget tightens.

*Heap sizing, itemised against the 4GB limit.* The unavoidable non-heap costs:
metaspace and class metadata (~150MB with a Spring application), JIT code cache
(~100MB), thread stacks (~1MB × platform thread count — 200 threads is 200MB),
GC data structures including card table and remembered sets (roughly 3–5% of
heap), direct byte buffers used by the HTTP and database layers, and native
library allocations. That is comfortably 600–800MB before the heap. So
`-XX:MaxRAMPercentage=65`, giving roughly a 2.6GB heap — set as a percentage
rather than `-Xmx` so the JVM reads the cgroup limit itself (failure F7).

*The steady state is easy.* The generational hypothesis holds almost perfectly
here: ~12KB per request × 8,000 rps = ~96MB/s of allocation, essentially all of
which dies with the response. That is exactly the workload young collection is
built for — the cost is proportional to the handful of survivors, not the 96MB.

**The 6-hourly cache rebuild is the interesting failure, and it is the answer to
the question.** Rebuilding a 400MB map and swapping the reference means that for
the duration of the rebuild **both copies are live** — 800MB of a 2.6GB heap —
and then 400MB becomes garbage *in the old generation*, which is the expensive
place for garbage to be. Concretely: the new entries are allocated fast, survive
every young collection (they are reachable from the map under construction),
overflow the survivor space, get promoted early (F3), and fill the old
generation. Then the swap makes the old 400MB unreachable all at once, and only
an old-generation collection can reclaim it. The likely result is a mixed-
collection storm or, if headroom is tight, a full GC — every six hours, at a
predictable time, which is exactly the signature people misattribute to
"something upstream".

*What I would change, in order of preference:*
1. **Refresh incrementally** rather than rebuilding. Update entries in place or
   in batches, so there is never a second full copy and the garbage is produced
   gradually. This removes the problem rather than accommodating it.
2. **Move the cache off-heap** — a bounded Caffeine cache with a maximum size, or
   an external store per the `caching` lab. 400MB the collector never has to mark
   is 400MB of marking cost that disappears from every cycle, not just the
   rebuild.
3. If neither is possible: size the heap for two copies plus headroom, and accept
   that the rebuild window is a degraded period. Schedule it off-peak and stagger
   it across instances so they do not all degrade at once.

*Dashboard — five things, with paging thresholds:*
1. **Count of `Pause Full`** — page on any occurrence. On G1 with a 50ms budget
   this is never routine.
2. **p99 and max GC pause** (not the mean) — page if max exceeds 8ms sustained
   over 5 minutes.
3. **Heap occupancy immediately after each collection** — page on a rising floor
   over 6 hours, which distinguishes a leak from normal sawtooth (F1).
4. **Allocation rate and promotion rate** — page if promotion rate rises sharply
   without a corresponding traffic change; that is F3 starting.
5. **RSS against the container limit**, not just heap — this is the one people
   omit, and it is the only warning you get before an exit-137 OOM kill (F7).

### 8.2 Thirty gigabytes in-heap, in every instance

**The proposal is bad for GC-specific reasons, and it is worth being precise
about which.**

*Marking cost.* Marking is O(live objects and references). A 30GB live dataset is
perhaps hundreds of millions of objects that are **always live** — the collector
proves the same thing about them on every single cycle, forever, and never
reclaims a byte of it. This is exactly the pathology from stage 10 v1 that
generational collection was invented to escape, restored at scale.

*Pause time.* G1's evacuation pause is proportional to the volume of live data
copied, so a large live set makes even a well-tuned G1 pause tens to hundreds of
milliseconds. Under G1 this design does not meet a tight SLO.

*Collector choice.* This is genuinely ZGC's niche — sub-millisecond pauses that
do not scale with heap or live-set size. So the proposal is not impossible; it
just forces the collector choice and brings ZGC's costs with it: 5–15% throughput
lost to load barriers on a workload that is now mostly reading that dataset, plus
significant extra footprint.

*Container sizing.* 30GB live needs meaningful headroom — for ZGC, plan on
roughly 45–50GB of heap, plus non-heap, so ~56GB+ per instance, in **every**
region, replicated across every instance. Startup also becomes a real event: the
dataset must be loaded and the pages touched.

**The alternative.** Keep the dataset **off-heap** or **out of process**. A
memory-mapped read-only file (or the Foreign Function & Memory API) gives you the
30GB without a single byte of it being a GC root — the collector never marks it,
never copies it, never sees it, and the OS page cache manages residency. Or keep
it in a local Redis-style store per the `caching` lab, or a same-zone replica
with a small in-heap hot-set cache in front.

**What that costs, and the connection to your other labs.** Off-heap costs you
serialisation at the boundary and manual lifetime management — a leak there has
no heap dump to find it in. Out-of-process costs you a network hop: single-digit
milliseconds same-zone, which is affordable, versus the 50ms cross-region hop
that the whole proposal existed to avoid. That is a `pacelc` argument in its
exact form: **in the absence of a partition you are trading latency against
consistency and cost**, and the answer here is to keep the replica local (paying
in staleness and memory) rather than to read across regions (paying in latency).
And it is the `caching` lab's core trade too — the hot-set-in-front design says
you do not need 30GB of locality, you need the 2% that is actually hot, and the
GC cost of the other 98% is pure loss.

### 8.3 The 3am page

The symptom set matters: **p99 up 80×, throughput unchanged, CPU up 15%, no
recent deploy.** Throughput unchanged with latency up means requests are being
*delayed*, not rejected — a pause-shaped problem, not a capacity-shaped one. No
deploy means look for something that accumulates (state, data volume, uptime) or
something that changed outside your code (traffic mix, a dependency, the host).

**In order:**

1. **`grep 'Pause Full' gc.log`.**
   *If yes* → you have a full stop-the-world collection on a collector that
   should not be doing one. Branch to step 2.
   *If no* → the long pauses are not full collections. Branch to step 4.

2. **Look at heap occupancy immediately after each collection, over the last few
   hours.**
   *Rising floor* → **F1, a memory leak**, and the 40-minute onset is when it
   crossed the point where collections stopped keeping up. Take the heap dump,
   open the dominator tree, look for *what holds this* rather than *what is big*.
   *Flat floor* → not a leak; the live set is stable and something else is
   forcing full collections. Go to step 3.

3. **Check the GC cause on the full collections, and the allocation rate.**
   *`G1 Humongous Allocation` or `to-space exhausted`* → **F4**, large-object
   allocation against a fragmented region map. Ask what changed about the input
   size — a customer sending bigger payloads is a change with no deploy.
   *Allocation rate sharply up, survival rate up* → **F2, concurrent mode
   failure**: the collector is losing the race. A traffic-mix change (more
   expensive request type) does this with unchanged request *count*, which fits
   "throughput unchanged" exactly.

4. **Compare pause duration with the work reported in each pause.**
   *Pauses far longer than the work explains* → **F5, time to safepoint**. Turn
   on `-Xlog:safepoint` and read the two components; check what the operation
   actually is, because it is frequently not a GC. This is the one that fits
   "CPU up only 15%" best — the machine is not working hard, it is waiting.
   *Pauses proportional to work* → the collector really is doing more work; go
   back to step 3's allocation-rate question.

5. **Check the host, not the JVM.** Memory pressure, a noisy neighbour, or swap
   activity → **F8**. Enormous, erratic pauses uncorrelated with allocation rate
   are the signature. Look at page faults and at whether the instance is alone in
   this or the whole fleet is affected. *One instance* points at the host; *the
   whole fleet* points at the workload.

6. **If GC is exonerated at every step**, use JFR to check what else stops
   threads — lock contention, a slow dependency saturating a connection pool — and
   remember that a thread waiting on a pool looks exactly like a paused thread
   from the outside.

**The four failure modes that fit, and the one observation that separates each
pair:**

| Pair | The one observation that separates them |
|---|---|
| F1 leak vs F2 concurrent mode failure | Does the post-collection occupancy floor rise, or return to baseline? |
| F2 concurrent mode failure vs F4 humongous | The GC *cause* string on the full collection, and whether a large-object allocation appears. |
| F5 time-to-safepoint vs everything else | Is the pause longer than the work the collector reports doing? |
| F8 swap vs F2 | Does pause length correlate with allocation rate, or is it erratic and uncorrelated? And is it one host or the fleet? |
