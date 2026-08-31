# Exercises — the compound-engineering plugin

Eight rungs, easiest first. Rungs 1–3 need nothing but the README and the
[simulator](https://claude.ai/code/artifact/a640ed3e-d372-4daa-b234-024b9d097420)
(or `visualization.html`). Rungs 4–6 need `java CompoundLab.java`. Rungs 7–8 are
design work with no single right answer.

Attempt before opening `SOLUTIONS.md`.

---

## Rung 1 — Explain

**1.1** In your own words, no jargon at all, explain to a colleague who has never
heard of this plugin: what problem does it solve, and what is the one idea behind
its solution? Two or three sentences. If you find yourself saying "skill",
"artifact" or "frontmatter", you are not at rung 1 yet.

**1.2** The README calls one arrow "the whole point". Which arrow, and what would
the plugin be without it?

**1.3** Someone says: *"We already have a big `CLAUDE.md` with all our conventions
in it — isn't this the same thing?"* Give the two specific reasons it isn't.

---

## Rung 2 — Identify

Open the simulator and press **Run all 14 tasks**. Let it finish.

**2.1** How many documents are in the compounding team's store at the end? Why is
that number smaller than 14?

**2.2** Click any card. Point at the part of what you see that a search actually
reads, and the part it does not.

**2.3** Step back to task 4 (use **← Back**). At that moment: which cards are
lit blue, which is green, and what is the difference in what happened to them?

**2.4** In the event log, find the line that names how many documents were
scanned versus how many were read. Which of the two numbers grows as the corpus
grows?

**2.5** In `Learning.java`, list the fields that can hold only one value. What
does that constrain?

---

## Rung 3 — Predict

Write your answer down *before* running anything.

**3.1** In the simulator, set "Does the team write the learning down?" to **Half
the time**. Before running: will the compounding team's repeat count be roughly
half of the forgetful team's? Run it. Were you right, and if not, why not?

**3.2** A learning was stored with `module: payments` and
`tags: [n-plus-one, jpa, lazy-loading]`. A new task arrives: *"the catalog
listing is doing one query per row"*. Will retrieval find it? Which label carries
the match, and which one doesn't?

**3.3** Set vocabulary to **symptom words** and the shared vocabulary file to
**No**. Predict both numbers before you run. Then turn the vocabulary file back
on and predict again.

**3.4** You press **Rename the payments module** at task 3, before any payments
document exists. Predict what the log says.

**3.5** `java CompoundLab.java`, experiment 1. Before running: at a corpus of 200
documents rather than 96, roughly what would the two columns read? Which one can
you predict confidently, and which one can't you?

---

## Rung 4 — Modify

**4.1** In `Retrieval.java`, change `TOP_K` from 5 to 1. Re-run. Which experiment
changes most, and which barely moves? Explain the difference in terms of what
each experiment is measuring.

**4.2** Change `SHORTLIST_CAP` from 25 to 3. Re-run experiment 2. Which recall
column suffers most — house vocabulary, symptom words, or symptom words with the
glossary? Why that one?

**4.3** In `Retrieval.score`, the weights are: title 3, tag 4, module 4,
component 2, body term 1. Set every weight to 1. Re-run experiment 2. What does
the change tell you about which labels are doing the real work?

**4.4** In `Concepts.java`, delete every alias whose value mentions a module name
(`payments`, `catalog`, …). Re-run experiment 2. How much of the glossary's
recovery came from mapping symptoms to *causes*, and how much from mapping them
to *places*?

**4.5** In `Corpus.one`, change `bodyChars` from 2600 to 12000 — long, thorough
learnings. Re-run experiment 1. Does grep-first still stay flat? Which of the two
strategies is hurt more, and what does that say about how long a learning should
be?

---

## Rung 5 — Implement

**5.1** Retrieval currently returns any document whose labels overlap the query.
Real corpora go stale, and a document last touched two years ago deserves less
weight than one from last month. Add a `date` field to `Learning`, populate it in
`Corpus.one` (vary it), and add recency to `Retrieval.score`. Then answer with
evidence: does recency weighting help or hurt experiment 2's recall, and why?

**5.2** Implement `Retrieval.grepFirstTwoPass`: if the first pre-filter returns
more than `SHORTLIST_CAP` candidates, narrow by intersecting with a second, more
specific pattern (say, `module` must also match) instead of truncating. Show the
before/after candidate counts on a query that currently overflows the cap.

**5.3** Write `CorpusHealth.java` reporting, for any corpus: total documents,
documents per category, the stale fraction, and the count of documents whose tag
sets are more than 60% identical to another document's — your duplicate-drift
detector. Run it against a corpus built by `Corpus.build(96)` and say which
number would worry you most in a real repository.

---

## Rung 6 — Debug

**6.1** Run `java BrokenRetrieval.java`. One check passes and one fails.

- Which check fails, and what does the number in it actually mean?
- Which single line in `grepFirstBroken` causes it?
- Which invariant from `Retrieval.java` does that line violate?
- The retrieved documents are still *correct*. Explain, in one paragraph, why
  this bug nevertheless destroys the entire point of the system.

**6.2** The bug in 6.1 is invisible to any test that only checks correctness.
Write the smallest assertion you would add to a real test suite to catch it, and
say where in a real system you would measure the equivalent quantity.

**6.3** Here is a second, subtler bug. Suppose the pre-filter were written as:

```java
.filter(l -> queryTerms.containsAll(l.frontmatterTerms()))
```

instead of using `Collections.disjoint`. Without running it: what does this
version actually match, and would recall go up or down? Now check yourself by
running it.

---

## Rung 7 — Handle failure

**7.1 — The silent miss.** Failure 3 in the README (vocabulary drift) is the most
dangerous, because a retrieval miss and "there was genuinely nothing to find" look
identical from the outside. Design a detection mechanism: what would you record,
where, and what signal would tell you the corpus has stopped being reachable? Your
answer must work without a human labelling anything by hand.

**7.2 — Trust decay.** Right now a learning is either present or absent. Design a
scheme where a learning carries a confidence that decays with evidence — the code
it cites has changed, it has not been retrieved in a year, a later learning
contradicts it. What decays it, what restores it, and what does a *low-confidence*
learning do differently when retrieved? Name the failure mode your scheme
introduces that doesn't exist today.

**7.3 — Capture that survives a crashed session.** Capture happens at the end of a
task. Sessions get abandoned, interrupted, or context-limited before that. Design
capture that survives — and be specific about the trade you are making, because
the obvious answer (capture continuously) reintroduces the batching problem from
experiment 3.

---

## Rung 8 — Design

**8.1 — Roll it out to forty engineers.** You are introducing this to a team of 40
across 8 services, each with its own repository. Decide and justify:

- One shared corpus or one per repository? What breaks either way?
- What is the retrieval story for a learning that lives in service A's repo but
  would prevent a bug in service B's?
- Which of the four monitoring signals from README stage 15 do you instrument
  first, and what threshold triggers what action?
- What is your answer to the engineer who says "this is just extra process"? Note
  that the honest answer includes a cost.

**8.2 — The staleness budget.** Your corpus is 400 documents and grows by ~20 a
month. A full maintenance pass costs real money in model calls. Design the policy:
what triggers a refresh, what scope does it cover, and how do you avoid both
extremes — never refreshing (failure 4) and refreshing everything constantly (all
cost, no benefit)? Which documents are worth checking most often, and why?

**8.3 — Argue the other side.** Make the strongest honest case *against* adopting
this on a specific real project you know. Not a strawman: use the trade-offs from
README stage 12 and 15, and name the conditions under which you would change your
mind.
