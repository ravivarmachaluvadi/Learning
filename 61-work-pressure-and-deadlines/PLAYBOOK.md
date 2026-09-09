# Playbook — what to actually say

Paste-ready wording for the twelve situations this subject produces. Adjust the tone
to your workplace; keep the **structure**, because the structure is what does the work:
*facts first, then the forecast with its probability, then a decision that belongs to
them, never a problem without options.*

The reasoning behind each one is in [README.md §15](README.md#15--production--the-conversations).

---

## 1 · The date has just landed on you (same day)

> Thanks — before I say yes to the 30th, let me put a proper forecast together so we
> are both working from the same numbers. I'll come back tomorrow with what fits by
> then, what doesn't, and a couple of options. If the 30th is fixed for a reason I
> should know about, tell me now and I'll build the options around it instead.

**Why this works:** you neither accepted nor refused. You bought a day, and you asked
the §15.1 question — *is this date real?* — without making it a challenge.

---

## 2 · Coming back with the forecast (the next day)

> Here's where we are on the 30th.
>
> **The work:** 60 items. **Our actual throughput** over the last 10 weeks: 5 items a
> week on average, between 2 and 8.
>
> **The forecast:** replaying our own history, 60 items lands in week 12 half the
> time, and week 14 is the date I'd hold 85% of the time. The 30th is week 10 — that
> comes true about 4 times in 100.
>
> **What closes the gap:** 20 items out of the 60. At 40 items, week 10 goes to a 97%
> bet. I've marked the 20 I would cut and what each one costs us — happy to go through
> them, and happy to be overruled on which ones.
>
> **Three options:**
> 1. Full 60 items → week 14 with 85% confidence.
> 2. 40 items on the 30th → week 10 with 97% confidence, the other 20 follow in
>    November.
> 3. All 60 by the 30th → possible, roughly a 1-in-25 chance, and everything that goes
>    wrong lands in production.
>
> My recommendation is 2. What would you like to do?

**Why this works:** every number is a fact about the past or a stated probability, and
the decision is explicitly theirs. Option 3 is real, not a straw man — offering it is
what makes 1 and 2 credible.

---

## 3 · The weekly forecast (send this every week, same day, always)

> **Payments release — week 6 update**
>
> - Finished this week: 6 items (39 of 60 done).
> - Throughput: 5.1/week over the last 6 weeks.
> - **Forecast: week 13, 85% confidence** (was week 14 last week).
> - Changed this week: the reconciliation work came in smaller than expected; the
>   two support escalations cost us about a day.

Four lines. Ten minutes. Send it whether or not it moved.

**Why this works:** it makes the week-3 conversation routine and the week-9 crisis
impossible. By the time a number moves, it is the sixth in a series and nobody can be
surprised by it.

---

## 4 · New work arrives mid-project

> Happy to take this on. It's about 4 days, which moves the forecast from week 12 to
> week 13. Do you want it in at that cost, or is there something in the current list
> you'd rather drop for it?

Use it for small things too. Scope creep is entirely made of items that were
individually too small to mention.

---

## 5 · Someone asks you to "just work weekends"

> A hard push for the last two weeks before the date is something I'd support, and it
> would buy us real time. What it won't do is close a six-week gap — when we tried that
> on the last release we shipped a week later than the un-crunched estimate and spent
> the following month on the bugs we made. If the 30th genuinely can't move, the lever
> that works is scope, and I've got the list ready.

**Why this works:** you concede the true part first. Crunch really does work for short
pushes, and denying that costs you the argument with anyone who has seen it work.

---

## 6 · You are told to do it anyway

> Understood — we'll go for the 30th and I'll push for it.
>
> So it's on the record and nobody is surprised later: my forecast is week 14 at 85%,
> the biggest risk is the third-party integration, and I'll keep sending the weekly
> update and flag it the moment it moves. If it does move, the fastest lever will still
> be dropping items, so it's worth knowing now which ones you'd sacrifice.

Say it **once**. Then execute properly and stop relitigating. The engineer who says
"I told you so" in November was never going to be believed in September either.

---

## 7 · Confirming a verbal decision in writing

> Summarising where we landed so we're all working from the same picture:
>
> - We're keeping the 30th and holding all 60 items.
> - Forecast is week 14 at 85% confidence; the 30th is week 10.
> - If it slips, first lever is dropping items from the "nice to have" list.
> - I'll send the forecast every Thursday.
>
> Shout if I've got any of that wrong.

**The test:** the person you are summarising would be comfortable forwarding this. If
it would embarrass them, rewrite it. You are building shared memory, not a case.

---

## 8 · Escalating past your manager (rare, and do it carefully)

Only when: the decision genuinely sits above them, you have already raised it with
them, and you tell them you are doing it.

To your manager first:

> I don't think we can close this at our level — the only lever left is scope and that
> needs Priya's sign-off. Can we take it to her together this week? If it's easier I'll
> write it up and you can send it.

**Why "together"** — an escalation that routes around someone converts the person best
placed to help you into an obstacle.

---

## 9 · Asking for the scope decision from someone senior

> We have a genuine conflict between the date and the list, and it needs your call
> because both sides of it are legitimate.
>
> The date is week 10. Our own throughput puts all 60 items at week 14, 85%
> confidence. 40 items lands on week 10 at 97%.
>
> I'm not asking to move the date. I'm asking which 20 items come out — I've drafted a
> proposal, but you own the trade-off between them.

**Why this works:** senior people say yes to *decisions* and no to *problems*. You have
handed them a decision that only they can make, with the analysis already done.

---

## 10 · Your own load, to your manager

> I want to flag something before it becomes a problem rather than after. The last two
> months have run at about 55 hours a week for me, and the last three weekends. That's
> not sustainable and my work is getting worse, not better — I made two mistakes last
> week I wouldn't normally make.
>
> I'm not asking to drop anything today. I want us to agree what comes off my plate
> once the release lands, and to plan the next one at a rate I can hold.

**Why this works:** specific, measured, forward-looking, and it asks for a plan rather
than sympathy. "I'm stressed" is easy to sympathise with and forget; "55 hours and two
mistakes" is a fact that needs an answer.

---

## 11 · When it is chronic (the 1:1 that matters)

> Something I want to name because it's now a pattern rather than one bad quarter:
> the last four releases have all been committed before we forecast them, and all four
> have run over. That costs us more than the dates — it's why we're carrying the bug
> backlog we are.
>
> I'd like to try one thing for one quarter: no date goes out until we've put a
> forecast against it, and I send that forecast weekly. If it doesn't help, we drop it.
> Would you back that?

**Why this works:** it is a proposal with a scope, an owner, a duration and an exit,
which is a completely different object from a complaint.

---

## 12 · After a crunch, protecting the recovery

> Now the release is out, I want to flag what the next two weeks realistically look
> like. The bugs we made under pressure will surface roughly now — that's the normal
> pattern — so I've planned for reduced feature work while we absorb them. Committing
> the team at full rate straight away is how the next release starts behind.

**Why this works:** it prices the recovery as *project work*, which it is, rather than
as time off, which is how it gets cut.

---

## The four things never to do

1. **Silent slipping.** Hoping through weeks 3 to 8 costs every cheap option you had.
   The whole cost of a slip is in when it is announced.
2. **Padding estimates secretly.** It gets found out, it destroys the only currency
   you have, and it makes real forecasts unbelievable. Pad *explicitly*, as a
   confidence level: "week 14 at 85%".
3. **Martyrdom.** Quietly absorbing an impossible plan teaches the organisation that
   the plan was fine. The next one will be worse, and you will have taught them that.
4. **"I told you so."** Being right in November buys nothing. Being on record in
   September, once, calmly, buys everything.
