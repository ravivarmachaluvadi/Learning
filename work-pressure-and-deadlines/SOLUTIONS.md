# Solutions

Every number here was produced by running the code. Where a solution required code the
lab does not ship, the patch is given and the measured result comes from running it.

---

## Rung 1 · Explain

**1.1** One model answer:

> The estimate covered the time spent typing the code, and only in the version where
> nothing goes wrong. It did not include the quarter of every week that goes to
> support, meetings and reviews; or the bugs the team made while working, which came
> back three weeks later as new work at the front of the queue; or the simple fact
> that when a task can only be early by a day but late by two weeks, the average is
> higher than the number anyone pictures. Add those up and the honest 5.1 weeks
> becomes 15.7. Nobody was slow — the plan measured one part of the job and paid for
> all of it.

**1.2** The decomposition (§4) says **57.4%** of paid time became features. Focus does
not move that number, because the other 43% is not distraction — it is support,
coordination, teaching, the team's own rework, and time spent waiting for something to
pick up. The version to say out loud: *"we are focused; the plan assumed 100% of our
time and we have 57% — here is where the rest goes, and which parts of it you can
decide to reduce."*

**1.3** *Estimate*: "that migration is about three days." *Forecast*: "the 60 remaining
items land on week 14 with 85% confidence, based on our last ten weeks." *Commitment*:
"we will ship on 12 October", said to someone who then books a marketing campaign.
The failure mode is the silent upgrade from the first to the third with no forecast in
between.

---

## Rung 2 · Identify

**2.1** In the default project the three largest non-feature slices are **support and
interrupts** (25% of capacity, and by far the biggest), **coordination between people**
(2% per engineer per other engineer — 8% for a team of five), and **nobody had anything
to pick up** (idle capacity at the tail, when fewer tasks remain than there are
engineers). The largest is a fact about this team, not a problem — until it is left out
of the plan, at which point it becomes 3.7 weeks of overrun.

**2.2** Fatigue appears in the first overtime week and grows every week
(`energy = 1 − 0.03 × weeksOfOvertime × (overtime/20)`, floored at 0.60). For a team of
five on 60 hours it passes the coordination slice within the first few weeks and keeps
climbing — the exact week is a good thing to read off the chart yourself rather than
take from here.

**2.3** The preemption line in `Project.java`:

```java
backlog.add(0, new Task(nextTaskId++, d.reworkDays, true));   // index 0 = front
```

and the invisibility line:

```java
int disc = day + (int) Math.round(p.discoveryDelayDays * (0.5 + qa.next()));
```

The delay matters more than the size because it decides *when* the work lands. 20.9
engineer-days of rework is only 5% of the budget, but it arrives near the end, at the
front of the queue, after the plan has already committed. Work you know about early is
a scheduling input; work that appears late is a slip.

---

## Rung 3 · Predict

**3.1** WIP 5 → **17.1 features by week 6**. WIP 40 → **1.4**. Ship week 15.7 vs 26.3.

**3.2** Measured from week 4 (average of 40 runs):

```
  cut 40% of unstarted work    12.0     <- best, by a distance
  hold the plan                15.7
  60-hour weeks                14.6
  add 2 engineers              14.9
```

The pair most people swap is **crunch and hiring** — hiring feels like the responsible
answer and crunch feels like the desperate one, and from week 4 they are worth roughly
the same, with crunch slightly ahead *and* costing 2.7× the escaped bugs. The real
lesson is the size of the gap to the first row: the only intervention that removes work
beats every intervention that moves it.

**3.3** **31% longer**, not 25%. `1/(1−0.25) = 1.33`, and the simulation gives 1.31.

**3.4** **4%.** Most people guess somewhere between 40% and 60%, because average
velocity says week 12 and week 10 "feels close". P85 is week 14.

---

## Rung 4 · Modify

**4.1** `skewOn = false` (every task exactly average, zero surprises):

```
  baseline (real variability)   15.7 weeks
  no variability at all         15.3 weeks
```

**Four tenths of a week.** Perfect estimation of every single task — a superpower no
team has ever had — buys almost nothing here, because with 40 independent tasks the
overruns and underruns largely cancel.

What it tells you: *"we need to estimate better"* is aimed at the wrong target. The
gap is not noise around a correct number, it is a **biased** number (the median-vs-mean
undercount, §10 Correction 1) meeting a **capacity** figure that was never real (§10
Correction 2). Precision does not fix bias.

The honest caveat: this model has **no dependencies between tasks**. On a real critical
path variance does not cancel, it accumulates — every task waiting on a late one
inherits the delay. So read this result as "variance is not the main term *for
independent work*", not as "variance never matters".

**4.2** `interruptFraction = 0` → **12.0 weeks**, recovering 3.7 of the 9.7 weeks of
overrun — the single largest correctable term. It eliminates §11/F4 entirely and
shrinks F6, because a team that is not carrying unplanned work has slack.

**4.3** The ship date is **15.7 weeks whether you promise 3, 6 or 20**.

One sentence: *a deadline is a statement about what someone wants, and has no causal
connection to how long the work takes.*

The model is right about that, and it is also incomplete in two known directions.
Deadlines do change behaviour: they focus decisions, they trigger scope cuts that would
otherwise never happen, and — the effect this model deliberately omits — a *distant*
deadline invites Parkinson's Law and Student syndrome, where work expands and starts
late. So a deadline can genuinely change the outcome. What it cannot do is change the
amount of work, and that is the mechanism people are relying on when they set an
aggressive date and expect it to be met.

**4.4** Crunching only the last two weeks:

```
  no crunch at all             15.7 weeks, 2.5 bugs escaped
  crunch the whole project     16.4 weeks, 6.7 bugs escaped
  crunch the last two weeks    15.1 weeks, 4.0 bugs escaped
```

The bounded end-of-project push **works**: 0.6 weeks earlier than not crunching, and
1.3 weeks better than crunching throughout. It costs 1.5 extra escaped defects.

This is the honest version of the crunch argument and worth carrying into a real
conversation: a short, bounded push before a real date is a legitimate engineering
decision with a known price. A quarter of 60-hour weeks is a way of paying more for
less.

---

## Rung 5 · Implement

**5.1** Padding changes `pointEstimateWeeks()` from 5.1 to 10.2 weeks and changes the
ship date by **exactly nothing** — the work is what it is.

The one-sentence lesson: **secret padding changes the promise, not the project**, and
it costs you the only thing that makes a real forecast believable. Pad *explicitly*,
as a confidence level ("week 14 at 85%"), which is the same number with its reasoning
attached.

The sting is in Exercise 5.3: padding is not merely useless, it can be actively
harmful.

**5.2** Reference implementation:

```java
/** The largest scope that still hits `week` with at least `confidence` probability. */
static int scopeForDate(int[] history, double week, double confidence) {
    int lo = 0, hi = 10_000;
    while (lo < hi) {
        int mid = (lo + hi + 1) / 2;
        double[] sim = simulate(history, mid, 0, 20_000, 42);
        if (chanceOfHitting(sim, week) >= confidence) lo = mid; else hi = mid - 1;
    }
    return lo;
}
```

Checked against the table in `java src/Experiments.java forecast`: with that history,
40 items gives 97% for week 10 and 50 items gives 54%, so `scopeForDate(history, 10,
0.85)` lands between them — around 43 items. The binary search works because the
probability is monotonic in scope.

**5.3** The patch that makes work expand into a per-task buffer:

```java
// in Project.run, where task durations are drawn:
if (p.parkinson > 0)                       // an early finish is never reported
    d = Math.max(d, p.medianTaskDays * (1 - p.estimateOptimism) * (1 + p.parkinson));
```

Measured, 30% buffer both ways:

```
  pad every task by 30%, work expands to fill it  -> ships 18.6 weeks
  no padding, 30% buffer held at project level    -> ships 15.7 weeks
  against an honest week-16 promise:
      no padding   -> met 60% of the time
      padded tasks -> met 10% of the time
```

Per-task padding cost **2.9 weeks and 50 percentage points of reliability**. The
mechanism is asymmetry: a padded task can absorb its buffer but can never give it back,
because early finishes are not reported. A project-level buffer is shared — one task's
disaster is paid for by another task's good day — so the same nominal 30% covers far
more risk. This is Goldratt's critical chain argument, and it is the strongest
practical case for estimating honestly and buffering once, in public.

---

## Rung 6 · Debug — the four planted mistakes

```
  Scope        40 tasks x 3.2 days   = 128 engineer-days
  Capacity     5 engineers x 5 days  =  25 engineer-days/week
  Build        128 / 25              = 5.1 weeks
  Test & fix   fixed allowance       = 1.0 weeks
  Contingency  10%                   = 0.5 weeks
  COMMITTED DATE                     = 7 weeks
```

**Mistake 1 — capacity is booked at 100%.** "5 engineers × 5 days" assumes every
engineer spends every working day on this project. Measured by
`java src/Experiments.java interrupts`: at 25% unplanned work the project takes 31%
longer. **Worth ~3.7 weeks.** This is the biggest single error and the easiest to fix,
because the number is measurable from last quarter.

**Mistake 2 — the estimate is a sum of most-likely cases.** 3.2 days is the happy-path
median; the mean of that distribution is 5.1 days. `summing_most_likely_estimates_
understates_the_total` measures a **28% undercount** from skew alone, before the 20%
optimism. **Worth ~4.5 weeks.**

**Mistake 3 — "test & fix" is a fixed block at the end.** Defects are not a phase; they
are made continuously and discovered about three weeks after they are made, arriving as
queue-jumping rework. A one-week allowance is not a small buffer, it is a
misunderstanding of when the work happens. **Worth ~1.3 weeks**, and more importantly it
is the term that makes a project sit at "90% done" for a month.

**Mistake 4 (in the recovery plan) — contractors booked at full capacity from day one.**
"2 contractors from week 8, adding 10 engineer-days/week from the day they start"
ignores six weeks of ramp-up and the quarter of a mentor's day each one costs.
Measured: the same project **with** those contractors ships at 15.9 weeks instead of
15.7 — the recovery plan makes it slightly worse.

**Which to raise first, and which to leave alone.**

Raise **Mistake 1** first, always. It is a factual claim about how the team's time was
actually spent last quarter, it is checkable, it is nobody's fault, and it is the
largest term. Nobody has to admit they were optimistic to accept it.

**Mistake 2 is the one to leave alone in the meeting**, and this is the defensible
answer the exercise asks for. Telling a room that their estimates are systematically
biased is an argument about people's judgement, it is unfalsifiable in the moment, and
it invites the useless response "then estimate better". You get the same correction for
free — and without the fight — by forecasting from measured throughput instead of from
the sum of estimates. **Fix the bias by changing the method, not by winning the
argument about it.**

---

## Rung 7 · Handle failure

**7.1 · The bottleneck reviewer.** Patch: a per-day cap on how many tasks can be marked
done.

```java
int reviewsLeft = p.reviewerCapacity > 0 ? p.reviewerCapacity : Integer.MAX_VALUE;
...
if (t.remaining <= 1e-9 && reviewsLeft <= 0) t.remaining = 1e-6;  // done, waiting
if (t.remaining <= 1e-9) { reviewsLeft--; /* ...mark done... */ }
```

Measured (WIP tracks team size, so people always have work):

```
  team       no cap      3/day      2/day      1/day
  5            15.7       15.7       15.8       17.1
  8            11.9       11.9       12.0       13.8
  12           10.1       10.0       10.3       13.0
  16            9.7        9.7        9.8       13.1
```

Read the last column. With a one-review-per-day bottleneck the date **stops responding
to team size** — 12 engineers and 16 engineers both land at ~13 weeks. Read the top row
too: at team size 5 the cap of 3/day costs nothing at all, because the team was never
finishing three things a day.

The prediction about "we'll just add people": **adding capacity past the constraint
buys nothing.** Before you accept more engineers, find out what the constraint is —
review, QA, a shared environment, one person's approval. If the answer is "we'll just
add people" and nobody can name the constraint, the money is being spent on the wrong
thing. (This is Theory of Constraints, arrived at from the other end.)

**7.2 · Bursty interrupts.** Patch: lose whole days at random with the same 25%
average.

```java
if (p.burstyInterrupts && p.interruptsOn)
    interrupts = qa.next() < p.interruptFraction ? 1.0 : 0.0;
```

Measured: smooth 25% → **15.7 weeks**; bursty whole days → **15.8 weeks**.
**Essentially no difference — and if you predicted "much worse", you were reasoning
correctly about the real world and hitting the edge of this model.**

Why the model cannot see it: here an interrupt pauses the whole team together and the
only output measured is the *end date*, over which the bursts average out. In a real
team the damage from burstiness lands somewhere this model does not look — one
engineer is yanked out while the others wait on their review, the half-finished task
sits, and **cycle time** (§7) blows up while throughput barely moves. That is the
mechanism of §11/F6, and it is why on-call teams feel far slower than their utilisation
figure suggests.

To make the model show it you would need two changes: interrupts applied to
*individual* engineers rather than the team, and a measurement of per-task cycle time
rather than the project end date. That is a genuinely good extension, and the fact that
the current model cannot show a real effect is worth more than a wrong number would be.

**7.3 · Week 6, soft evidence, no weekly forecast so far.** A defensible order:

1. **Today, before talking to anyone: build the forecast.** Count what is actually
   finished, count what is left, take the last six weeks of throughput, run E8. You
   cannot have this conversation with a feeling, and you now have the tool.
2. **Same day, to your manager, privately.** Not "we're going to miss it" — *"I've put
   a forecast together and it says week 13, not week 10. I want to show you before I
   send anything wider."* Private first is not politics; it stops them being surprised
   in public, which is the thing that makes people defensive.
3. **Bring the options with it** (PLAYBOOK item 2): the gap in units of work, and the
   20 items whose removal closes it.
4. **Start the weekly note this week** (PLAYBOOK item 3), and say you are starting it.
   The point is that week 7's message is the second in a series rather than another
   alarm.
5. **If scope is not theirs to decide**, ask to take it to whoever owns it, together
   (PLAYBOOK items 8 and 9).

What not to do: wait one more week for firmer evidence. Week 6 evidence with a stated
probability beats week 9 certainty, because §10's scope experiment shows the lever you
are trying to reach is worth 2.5 weeks now and 0.7 weeks then.

---

## Rung 8 · Design

**8.1 · The message.** Model answer is PLAYBOOK item 2. Score yours on four things,
not on wording:

- **Evidence, not assertion** — your own throughput, stated as a range (2–8 items a
  week), not "the team feels stretched".
- **A probability on every date** — "week 14 at 85%", never a bare number. If you wrote
  a single date you have reproduced the original mistake in the opposite direction.
- **The gap in units of work** — "20 of the 60 items", not "we need more time". Work is
  a thing they can act on; time is a thing they must concede.
- **A decision that is theirs** — three options, a recommendation, and a question at
  the end. If your message ends with a problem, rewrite it.

The one most people miss is the third. Also note what the data in the exercise
contains: 1.5 items a week of new scope, which moves P50 from week 12 to week 18. **If
your message did not mention scope creep, you left the largest term out** — and it is
the one term that is nobody's fault and therefore the easiest to fix.

**8.2 · The immovable regulatory date.** What applies unchanged: capacity is not
headcount (the two on-call teams should be planned at their measured availability, not
their headcount); scope is the only fast lever; a hire in month 1 pays back, a hire in
month 5 does not; forecasting from throughput beats forecasting from estimates.

What breaks: this model has no dependencies, and a three-team programme is mostly
dependencies — the integration points between the teams are the schedule, and variance
there accumulates instead of cancelling. It also has no notion of *partial*
compliance, which is the central question here.

Month 1, and never in month 5:
- **Establish what "minimum legally sufficient" means** and get it in writing from
  whoever owns the risk. On an immovable date, scope is the only lever, so the release
  must be ordered by regulatory necessity from day one. Everything else is optional by
  construction.
- **Build the integration path first**, end to end, thin. The cross-team seams are the
  risk; discovering them in month 5 is the classic way these programmes fail.
- **Hire now if at all** (§11/F2).
- **Set the on-call teams' planned capacity to their measured number**, and get that
  written down before anyone commits to anything.

In month 5 you do the opposite of all of it: no new people, no new integrations, no
architecture. You cut, you fix, and you protect the release.

The one weekly number for the executive: **the probability of shipping the mandatory
scope on the date** (from the Monte Carlo, updated weekly). Not percent complete, which
cannot fall and therefore tells them nothing. A number that can move in both directions
is the only one that carries information.

**8.3 · The weekly signal set.**

| Failure | Early signal | Where it comes from |
|---|---|---|
| F1 crunch spiral | Hours worked per person, reported *next to* features delivered | Anywhere honest; the pairing is the point |
| F2 late hire | Time-to-first-meaningful-PR for each joiner | Git history |
| F3 WIP trap | Items in progress ÷ team size, and items finished per week | Board |
| F4 interrupt tax | % of time on unplanned work | Ticket labels; a weekly 2-minute team poll works |
| F5 scope creep | Items added after the plan, as a running total | Board history — the number nobody plots |
| F6 utilization collapse | **Cycle time**, and PR review latency | Board / VCS metadata |
| F7 silent slip | The forecast itself, weekly | The Monte Carlo |

The one with no good early signal is **F7 — the silent slip** — because the failure is
a *social* one: the information exists in week 3 and does not travel. No metric fixes
that, because the metric would have to be volunteered by the person not volunteering it.

The answer is structural, not analytical: **make the forecast an automatic weekly
artefact that goes out whether or not anything changed.** Then not-reporting requires
an action, rather than silence being the default. That is the whole reason §15.3 is a
rhythm rather than a conversation — it converts a courage problem into a calendar
problem, and calendar problems are the only kind that get solved reliably.
