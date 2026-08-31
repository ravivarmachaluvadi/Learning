# Solutions

Every number below was produced by running the modification described, not
estimated. Where an answer is a design discussion (rungs 7–8) there is no single
correct output — those are worked arguments with the trade-offs named.

---

## Rung 1 — Explain

**1.1** Something like:

> When you solve a problem with an AI agent, the code gets saved but the
> *reasoning* doesn't — the dead ends, the trap you nearly fell into, why you
> chose this approach. Next month someone hits the same trap and pays the same
> cost. This tool makes "write down what we just learned" a step in the process
> rather than a good intention, and stores it in a shape that the *next* task can
> search — so the discovery gets made once instead of every time.

The test of a good answer: no jargon, and it names both halves. Writing it down
is only half; the other half is that a future task, which doesn't know what it
doesn't know, can find it.

**1.2** The arrow from the last step back to the first — `/ce-compound` writes to
`docs/solutions/`, and the next run's `/ce-plan` searches it. Without that arrow
the six steps are an ordinary development checklist: brainstorm, plan, build,
tidy, review. Useful, but the hundredth task would cost exactly what the first
one did. The arrow is the only thing that makes the word "compound" mean
anything.

**1.3** Two reasons:

1. **Cost.** A big instructions file is loaded on *every* task regardless of
   relevance. It grows without bound, and eventually crowds out the work — a repo
   that has learned more is punished for it. The corpus is *searched*, so
   consulting 96 documents costs about what consulting 12 does (4,723 vs 3,979
   tokens, measured).
2. **Granularity and maintenance.** One giant file has no structure to search, no
   per-item labels, and nothing to validate. A corpus of labelled documents can be
   pre-filtered, checked against the code, and weeded individually.

The instructions file isn't replaced — it's the right home for a handful of
always-relevant facts. It's the wrong home for three hundred.

---

## Rung 2 — Identify

**2.1** **Seven** documents from 14 tasks. Because a document is only written the
first time a given trap is met *in a given module* — a repeat has nothing new to
capture. Six traps, one of which (`n+1`) appears in two different modules, gives
seven.

**2.2** Read: everything between the two `---` lines — `title`, `category`,
`module`, `problem_type`, `tags`. Not read (unless the labels earn a shortlist
place): the prose under the heading. That asymmetry *is* the mechanism.

**2.3** At task 4 (the first repeat of the N+1 trap):

- **Blue** — documents whose labels overlapped the query. They made the shortlist
  and their bodies were read.
- **Green, lifted** — the one that actually addressed this trap and prevented it.
- The difference: blue cost reading time and changed nothing; green is the
  payoff. In a healthy run most cards are blue and one is green. That ratio is
  precision, and it's what one-learning-per-file protects.

**2.4** The log line reads `N of M matched`. **M** — files scanned — grows with
the corpus. **N**, and more importantly the number of *bodies read*, stays
roughly constant. That is the whole design in one line of output.

**2.5** `module`, `problemType`, `component` — each a single `String` or enum.
Only `tags` is a `List`. The constraint: a document can be *labelled* for exactly
one module, one component and one problem type, which is why a file covering five
problems is findable for at most one of them (experiment 3).

---

## Rung 3 — Predict

**3.1** **No — and the reason is more interesting than the number.** Measured:

| Capture | Forgetful team | Compounding team | Docs |
|---|---:|---:|---:|
| Every time | 8 | **0** | 7 |
| Half the time | 8 | **3** | 6 |
| Never | 8 | **8** | 0 |

Half the discipline gave 3 repeats, not 4, and cost only one document. The
relationship isn't linear, because **which** capture you skip matters enormously:
skipping capture on the *first* encounter with a trap loses every future avoidance
of it, while skipping on a later encounter costs nothing at all — the document
already exists.

The practical consequence: partial discipline is worth much more than it sounds,
and the runs that matter are the ones on problems you have never seen before.

**3.2** **Yes, it is found.** The `tags` carry the match (`n-plus-one` appears in
both), while `module` does *not* — the document says `payments`, the task is about
`catalog`.

This is exactly what the two kinds of label are for: `module` records *where it
happened*, `tags` record *what shape of problem it is*. Only the second transfers.
A corpus labelled only by module would be a corpus that never generalises. In the
simulator this is task 10, and it is the second prediction checkpoint.

**3.3** Measured: symptom words with no glossary gives **8 repeats against 8** —
seven documents sitting in the store, providing exactly nothing. Turn the glossary
on and it drops to **2**.

The lesson: the corpus was never the problem. Both runs stored identical
documents. The only variable was whether the words in the new task could reach the
words in the labels.

**3.4** *"Nothing to rot yet — no payments documents have been written."* Nothing
breaks, because staleness is a property of stored documents and there aren't any
yet. Worth noticing: rot is a hazard that *grows with the size of the corpus*, so
it is a problem you earn by succeeding.

**3.5** At 200 documents:

- **Read-everything is confidently predictable**: it is linear, so ≈142,000
  tokens (68,187 × 200/96). You can extrapolate it because it depends only on
  corpus size.
- **Grep-first is not confidently predictable**, and that is the point. It depends
  on how many documents *match the query*, which depends on the corpus's label
  distribution, not its size. It stays near 4,700 as long as the shortlist cap
  holds — but a corpus where every document shares a tag would degrade toward the
  left column.

The general lesson: the flat column is flat because of *selectivity*, not because
of the algorithm. Lose selectivity and you lose the property.

---

## Rung 4 — Modify (all measured)

**4.1 — `TOP_K` 5 → 1**

| | Baseline | TOP_K=1 |
|---|---:|---:|
| Exp 1, 96-doc grep-first | 4,723 tok | **2,123 tok** |
| Exp 2 recall, house words | 12/12 | 12/12 |
| Exp 2 recall, symptom + glossary | 9/12 | **7/12** |
| Exp 3, on-topic share | ~15% | **~33–40%** |

Experiment 3 moves most; experiment 1's *shape* barely moves. Reading one body
instead of five halves the cost and doubles precision — but recall on the hardest
queries drops from 9 to 7, because a query with weak vocabulary ranks the right
document second or third rather than first. `TOP_K` is a direct precision/recall
dial, and the honest reading is that 5 is buying insurance against imperfect
ranking.

**4.2 — `SHORTLIST_CAP` 25 → 3**

Recall: house **12/12** (unchanged), symptom **0/12** (unchanged), symptom +
glossary **9/12 → 7/12**.

The glossary column suffers, and that makes sense: it is the column with the
*weakest* query signal. A precise query puts the right document at rank 1, so
truncating at 3 costs nothing. A vague query spreads probability across many
candidates, and cutting the shortlist to 3 cuts off exactly the tail where a
vague query's answer lives. The cap hurts precisely the queries that need the most
help.

**4.3 — flat scoring weights**

Recall: **12/12, 0/12, 9/12 — completely unchanged.**

This is a genuinely surprising and useful result. The scoring weights do almost
nothing here; the *filter* is doing all the work. Whether a document is a
candidate at all matters vastly more than how it ranks among candidates.

Read across to the real system: this is why the plugin's retrieval spends its
detailed instructions on **pattern construction** — which fields to grep, which
synonyms to include — rather than on ranking. Getting into the shortlist is the
hard part.

**4.4 — remove module-name aliases from `CONCEPTS.md`**

Recall with glossary: **9/12 → 8/12** (36 aliases remaining).

So of the 9 recoveries, **8 came from mapping symptoms to causes** ("twice" →
`duplicate`, `idempotency`) and only **1 from mapping symptoms to places**
("login" → `auth`).

The practical guidance: a shared vocabulary file earns its keep mostly by
connecting *how a problem looks* to *what it is called*. Mapping colloquial names
to module names is nearly free but nearly worthless. Spend the effort on the
symptom→cause direction.

**4.5 — `bodyChars` 2600 → 12000**

| Corpus | Read-everything | Grep-first |
|---:|---:|---:|
| 12 | 36,729 | 15,729 |
| 40 | 122,374 | 16,242 |
| 96 | 293,787 | **16,473** |

Grep-first is still flat **in corpus size** — 12 documents and 96 documents cost
within 5% of each other. But it rose from 4,723 to 16,473 (3.5×) because the
`TOP_K` bodies now dominate the bill.

Both strategies got worse in absolute terms; read-everything got worse *faster*
(4.3× vs 3.5×). The real lesson is about how to write:

> Corpus size is free. Document length is not. A learning should be short enough
> that reading five of them is cheap, because five of them is what gets read every
> time.

That is an argument for one-problem-per-file that is independent of the labelling
argument in experiment 3 — they happen to point the same way.

---

## Rung 5 — Implement

**5.1 — recency weighting.** Expect it to *hurt* recall while improving trust.
Recency is a proxy for correctness, not a measure of relevance, so weighting it
into the same score conflates two different questions. The better design is to
keep it out of ranking and use it as a **presentation** signal — surface the
document, flag it as old — or as a trigger for the maintenance pass. Anything that
lets a fresh-but-irrelevant document outrank a stale-but-exactly-right one has
made the system worse. Report your measured numbers either way; being surprised
here is the point.

**5.2 — two-pass narrowing.** The key property to demonstrate: intersecting with a
second pattern is *not* the same as truncating. Truncation drops whatever sorts
last, which is arbitrary with respect to relevance; narrowing drops documents that
genuinely fail a second criterion. Compare candidate counts and, more importantly,
whether the gold document survives each approach. This is exactly the bug I hit
while building this lab — the shortlist cap was originally applied in file order,
before ranking, and the newest documents fell off the end.

**5.3 — `CorpusHealth`.** Against `Corpus.build(96)` after the experiment-4
refactor, the stale fraction is **38%**. That is the number that would worry me
most in a real repository, for the reason in README failure 4: a stale document is
worse than a missing one, because it arrives confident. Duplicate drift is the
second concern but it degrades slowly; staleness degrades trust in the whole
corpus at once.

---

## Rung 6 — Debug

**6.1** Measured output:

```
CHECK 1  retrieval_returns_the_preventing_doc     -> PASS
CHECK 2  context_cost_is_flat_in_corpus_size
         12-doc corpus :  8,529 tokens
         96-doc corpus : 17,723 tokens
         growth        : 2.1x   (must stay under 1.5x)   -> FAIL
reference implementation: 3,979 -> 4,723 tokens (1.2x)
```

- **The failing check** is the cost one. `2.1×` means the cost of consulting the
  past **grew with how much had been learned**.
- **The line** is:
  ```java
  int chars = candidates.stream().mapToInt(Learning::totalChars).sum();
  ```
  `totalChars` instead of `frontmatterChars`. The comment above it —
  *"Pull the shortlist in so it can be ranked properly"* — sounds entirely
  reasonable, which is what makes it a good bug.
- **The invariant violated** is the one stated in `Retrieval.grepFirst`: *the
  pre-filter scans file contents without reading them into context; only the
  shortlist is read.* The broken version reads all 25 shortlisted bodies rather
  than the best 5.
- **Why it destroys the system despite being correct:** the value of the corpus is
  not that it *can* answer, it is that it can answer *affordably enough that you
  keep adding to it*. Once cost scales with corpus size, the incentive inverts:
  every learning you write makes every future task more expensive. A rational team
  responds by capturing less, and the loop dies — not from a wrong answer, but
  from a right answer that costs too much. The measured 2.1× is only 96
  documents; at 500 it is a hard wall.

**6.2** The assertion:

```java
assert cost(corpus96) < 1.5 * cost(corpus12);
```

The general shape — *a cost that must not scale with a quantity that is expected
to grow* — deserves a test, not a dashboard, because it fails silently and slowly.

In a real system the equivalent quantity is **tokens loaded before the first tool
call of a task**, tracked against corpus size. If that ratio trends upward you
have this bug, whatever caused it.

**6.3 — `containsAll` instead of `disjoint`.** Measured:

```
96 docs | 68,187 tok (96 files) | 0 tok (0 bodies) | 100.0% saving
recall  | 0/12  | 0/12  | 0/12
```

`queryTerms.containsAll(l.frontmatterTerms())` requires the query to contain
**every** label the document has, rather than *any*. Since documents carry 8–10
label words and a query carries a handful, essentially nothing ever matches.

Recall goes to **zero** — and note the trap: the cost column reads **100%
saving**, the best number in the entire lab. Optimising the metric while
destroying the function. If you had shipped this and watched only the cost
dashboard, it would look like your best week ever.

---

## Rung 7 — Handle failure (worked discussions)

**7.1 — Detecting the silent miss.** The core difficulty: a miss and a genuinely
empty corpus produce identical output. So you cannot detect it from a single
retrieval — you need a *base rate*.

A workable design: log every retrieval as a structured record (query terms,
candidate count, bodies read, whether the resulting plan cited any of them).
Then two derived signals:

- **Shortlist starvation** — the fraction of retrievals returning zero
  candidates, tracked over time. A corpus that is growing while this rises is
  drifting out of reach. This is the cheap signal and it needs no labelling.
- **Citation rate** — how often a plan actually references a retrieved document.
  Near zero against a large corpus is the alarm.

The clever cheap trick: periodically re-run retrieval using the *titles of
existing documents* as synthetic queries. Every one should retrieve itself. Any
document that cannot retrieve itself has labels disconnected from its own content,
and needs no human labelling to detect.

**7.2 — Trust decay.** A defensible scheme: confidence starts high and is reduced
by *evidence*, never by time alone — cited paths gone, contradicted by a newer
learning, contradicts the guidance layer. Restored by a maintenance pass that
re-verifies the claims.

What a low-confidence learning does differently: it is still retrieved, but
presented as *"this may be out of date; verify before relying on it"* and it never
suppresses a competing higher-confidence document.

**The failure mode you introduce:** confidence becomes a thing to game and a thing
to trust. A document marked high-confidence gets *more* deference than an unmarked
one does today — so a wrong high-confidence document is more dangerous than a
wrong unmarked one. You have converted an unknown-quality signal into a
falsely-precise one. Decaying by time alone is worse still: it makes documents
look untrustworthy exactly when they have been stable and correct for years.

**7.3 — Capture that survives a crash.** The obvious answer — capture continuously
as you go — reintroduces experiment 3's problem, because you cannot label a
learning until you know what it *was*, and mid-session you don't.

A better shape: a **running scratch note** during the session that is not a
learning and is not retrievable, plus the real capture step at the end that turns
it into one properly-labelled document. If the session dies, the scratch note
survives as raw material for a later capture run.

The trade you are making, stated honestly: a scratch note that is never promoted
is clutter, so you need a rule for expiring them. You have traded "learnings are
lost on a crash" for "half-formed notes accumulate", which is a better problem —
it is visible, and it fails toward noise rather than toward silence.

---

## Rung 8 — Design (worked arguments)

**8.1 — Forty engineers, eight repositories.**

*One corpus or one per repo?* **Per repository, with a small shared corpus for
cross-cutting conventions.** The reasons: retrieval quality depends on
selectivity, and a single 8-service corpus makes every query less selective, which
is exactly the property experiment 1 depends on. It also breaks ownership — a
document about service A's database is noise for service B's frontend work.
What breaks: genuinely cross-cutting learnings get duplicated or lost, which is
why the shared corpus exists for exactly that category and nothing else.

*The cross-repo learning.* Honestly: this is the weakest part of the model. The
pragmatic answer is that the shared corpus holds *conventions and patterns* — the
generalised form — while incident-level learnings stay local. A learning that
would prevent a bug in another service is usually a sign it should be promoted to
the shared corpus as a pattern, and that promotion is a judgment call somebody has
to make.

*Instrument first:* **corpus growth rate**, because it detects the failure that
kills everything (capture stops) and costs nothing to measure. Threshold: no new
documents in a repository for 30 days triggers a conversation, not an alarm —
because a quiet repository may simply not have been worked on. Second:
**shortlist starvation** from 7.1.

*The "extra process" objection.* The honest answer concedes the cost. It is real:
tokens, minutes, and a corpus to maintain, paid now, against a benefit that is
delayed and impossible to attribute — nobody can point at the incident that didn't
happen. The case for it is that the cost is proportional to *tasks* while the
benefit is proportional to *repeat encounters with the same codebase*, so it pays
off on long-lived systems and does not on short-lived ones. If the objector's work
is genuinely short-lived, they are right and should not adopt it.

**8.2 — The staleness budget.** Trigger refresh on *events that invalidate
knowledge*, not on a calendar: large renames, module deletions, framework major
upgrades, and a threshold on the stale fraction from a cheap mechanical scan (path
existence is nearly free — it is only the *adjudication* that costs).

Scope: the mechanical check runs over everything, always, because it is cheap. The
expensive semantic pass runs only over documents the mechanical check flagged, and
over the highest-leverage documents regardless.

Which are worth checking most often: **pattern documents** — the ones generalised
from several learnings — because future work treats them as broadly applicable, so
a stale one does proportionally more damage than a stale incident report. After
those, the documents that are actually retrieved most often. A document nobody
retrieves is not worth spending a model call to verify.

**8.3 — The case against.** A strong version:

> The benefit is real but unattributable, and unattributable benefits lose budget
> fights. The cost is immediate, visible, and paid by the person doing the work.
> Meanwhile the corpus is a liability that grows: 38% of it can go stale from a
> single refactor, and a stale learning is worse than none because it arrives
> confident. On a codebase being actively rewritten, most of what you capture will
> be obsolete before it is ever retrieved. And the whole thing depends on
> discipline that decays the moment someone is in a hurry — at which point you
> have the cost of a half-maintained corpus and none of the benefit.

What would change my mind: evidence of *repeat encounters*. If I can point at
three incidents in the last quarter that were the same trap twice, the argument is
over. That is also the cheapest thing to check before adopting — look at your own
incident history first, and if the traps don't repeat, don't adopt this.
