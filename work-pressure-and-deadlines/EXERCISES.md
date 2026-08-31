# Exercises

Eight rungs, easiest first. Rungs 1–3 need nothing but the
[README](README.md) and the
[visualization](https://claude.ai/code/artifact/29d16479-2b42-4dd0-bffc-9d1d8332ae7e).
From rung 4 on you are editing `src/`.

Attempt before opening [SOLUTIONS.md](SOLUTIONS.md). For rungs 7–8 the "answer" is a
worked discussion, not a single right output.

Run anything with:
```bash
java src/Tests.java
java src/Experiments.java <name>     # estimate overtime people scope wip
                                     # utilization interrupts forecast
```

---

## Rung 1 · Explain

**1.1** In four sentences, to someone who does not write software: why did a team of
five reasonable engineers, given an honest estimate of 5.1 weeks, take 15.7? No jargon,
no numbers beyond those two.

**1.2** Your manager says "the team just needs to focus". Using nothing but §4's
decomposition of where the paid time went, explain what is wrong with that diagnosis —
in three sentences, without being defensive.

**1.3** Explain the difference between an estimate, a forecast and a commitment, and
give one example of each from work you have actually done.

---

## Rung 2 · Identify

**2.1** Open the visualization. Step to week 8. Read the "Where week 8 went" bar and
name the three largest things that were **not** feature work. Which one is biggest, and
is that a problem or just a fact about this team?

**2.2** Set hours per week to 60 and step through. Find the first week where the
"hours lost to tiredness" segment appears, and the week where it exceeds the
coordination segment.

**2.3** In `src/Project.java`, find the single line that makes bug fixes preempt
feature work. Then find the line that decides how long a bug stays invisible. Why does
that second line matter more than the size of the bug?

---

## Rung 3 · Predict

Write your answer down before running anything.

**3.1** A team of 5 with 40 tasks open at once versus 5 open at once. Same work. By
week 6, how many features work in each case? Then: `java src/Experiments.java wip`.

**3.2** It is week 4 and the project is behind. Rank these four by ship date, best
first: hold the plan · 60-hour weeks · add 2 engineers · cut 40% of unstarted work.
Then check with the visualization's four "what could you do now" boxes.
*Most people get the ordering of the middle two wrong. Which two did you swap?*

**3.3** A team hands 25% of its capacity to support. The project takes ___% longer.
Give a number before running `java src/Experiments.java interrupts`.

**3.4** Ten weeks of history: `[4, 6, 3, 7, 5, 2, 6, 5, 4, 8]` items finished. 60 items
left. Someone promised week 10. What is your gut probability of hitting it? Then:
`java src/Experiments.java forecast`.

---

## Rung 4 · Modify

**4.1** *(the one worth doing)* In `Plan.java`, set `skewOn = false` — every task now
takes exactly the average duration, no surprises at all. Predict the new ship date,
then run it. The answer is much smaller than most people expect. What does that tell
you about "we need to estimate better" as a strategy?

**4.2** Set `interruptFraction = 0`. How much of the total overrun does that one change
recover? Which of §11's failure modes does it eliminate entirely?

**4.3** Change `promisedWeeks` from 6 to 3, then to 20. Run each. What happens to the
ship date? Explain in one sentence what that proves about deadlines — and then explain
why the model is *right* to behave that way and where it is *wrong*.

**4.4** Set `hoursPerWeek = 60` but `overtimeStartsOnDay` to two weeks before the
baseline ship date, so the team only crunches at the end. Does the late crunch beat the
all-project crunch? Does it beat not crunching at all?

---

## Rung 5 · Implement

**5.1** Add a `estimatePadding` field to `Plan` and use it in `pointEstimateWeeks()` so
the team quietly doubles every estimate before reporting it. Now run the project. What
changed about the ship date, and what changed about the promise? Write the one-sentence
lesson about padding.

**5.2** Write `Forecast.scopeForDate(int[] history, double week, double confidence)`
that returns the largest number of items that hits `week` with at least `confidence`
probability. Check it against the table at the end of
`java src/Experiments.java forecast` (40 items should give ~97% for week 10).
This is the function you will actually use at work.

**5.3** Goldratt's argument is that per-task buffers get eaten (Parkinson's Law,
Student syndrome) while a single project-level buffer does not. Model both: (a) give
every task 30% more estimated time and let the work expand to fill it; (b) leave
estimates alone and add a 30% buffer at the end of the project. Which protects the date
better, and why?

---

## Rung 6 · Debug

**6** `java src/BrokenPlan.java` prints a capacity spreadsheet that looks completely
reasonable and commits to 7 weeks for a project that takes 15.7.

**Three assumptions in the sheet are wrong, and the recovery plan contains a fourth.**
Name all four, and for each one: which experiment in this lab measures it, and roughly
what it is worth in weeks.

Then the harder half: **which of the four would you correct first in a real planning
meeting, and which one would you not raise at all?** (There is a defensible answer for
leaving one alone.)

---

## Rung 7 · Handle failure

**7.1** The model has no bottleneck person. Add one: an engineer whose review is
required before any task can be marked done, who can review at most 3 tasks per day.
Measure the ship date at team sizes 5, 8 and 12. What does this predict about "we'll
just add people"?

**7.2** `interruptFraction` removes a smooth 25% from every day. Real on-call is
bursty: nothing for four days, then a whole day gone. Change the model to lose whole
days at random with the same 25% average. Does the ship date get better, worse or stay
the same — and why? (Predict before you run it. The answer is about variance, and it
connects to §11/F6.)

**7.3** No code. You are in §11/F7 — it is week 6, your gut says the date is gone, and
your evidence is soft. You have not sent a weekly forecast so far. Write down exactly
what you do this week, in order, including who you talk to and in what form. Then
compare with [PLAYBOOK.md](PLAYBOOK.md) items 2, 3 and 9.

---

## Rung 8 · Design

**8.1** *(do this one)* You have this data: 60 items left, throughput
`[4, 6, 3, 7, 5, 2, 6, 5, 4, 8]`, promised week 10, and 1.5 items a week of new scope
arriving. **Write the actual message** you would send your manager tomorrow morning.
Then check it against [PLAYBOOK.md](PLAYBOOK.md) item 2 — not for wording, but for
whether yours contains: the evidence, a probability, the size of the gap in units of
work, and a decision that belongs to them.

**8.2** A regulatory deadline, 6 months out, genuinely immovable: fail to ship and the
company cannot operate in that market. Three teams, two of them also on-call for
existing products, one new team hiring two engineers next month. Design the plan.
Which parts of this lab's model apply unchanged, which break, and what do you do in
month 1 that you would never do in month 5? What is the one number you would report
every week to the executive who owns the risk?

**8.3** Design the weekly signal set. For each of §11's seven failure modes, name the
one measurement that would catch it *early* — while it is still cheap — and where that
number comes from in a real team's tooling. Which of the seven has no good early
signal, and what do you do about that one instead?
