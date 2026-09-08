# How a lab is written — the voice contract

Every `README.md` in this folder is a lesson. This file says how a lesson
sounds. It is the source of truth for the `/learn` skill and for anyone (or any
agent) rewriting an existing lab.

**Two rules above all others:**

1. **Depth is the product.** A lab is comprehensive: the what, the why, the
   how, the when, every comparison, every failure, every number. Nothing here
   ever justifies cutting material. If a lab reads badly, add structure,
   examples and questions. Do not shorten.
2. **Delivery is the craft.** A curious twelve-year-old should be able to
   follow the story from start to finish. An engineer should be able to ship
   from the details. Both read the same document. The story carries the
   engineer; the details reward the child who keeps going.

The two labs readers found easiest, `cap-theorem` and `pacelc`, both do this.
The two they found hardest, the old `paxos` and `networking-for-developers`,
both broke the rules below in the same four ways: they front-loaded meta-text
before the story, they dumped terminology in one block, they switched to
symbols before the reader had the picture, and they let the story die halfway
through. Every rule here exists to prevent one of those.

---

## The two teachers we borrow from

**Richard Feynman.** If you cannot explain it in plain words to someone with
no background, you do not understand it yet. So every idea is *first* said in
plain words with a concrete picture, *then* given its real name, *then* made
precise. When the writing reaches for jargon or a symbol to get past a hard
spot, that is the hand-wave. Stop, go back, find the simpler thing underneath.

**Socrates.** Teach by asking. The reader meets the question before the answer,
commits to a guess, and then sees whether the guess survives. A lesson that
only tells is a lecture. A lesson that asks and then checks the reader's answer
is a conversation, and conversations are remembered.

---

## The ten rules

Each rule is checkable. The check is in brackets.

### 1. Story first, meta later

The first thing after the title is the lesson in plain words: three to five
sentences a friend would understand, with the characters already in it. Then
the story starts.

Everything about the lab itself waits: "where this sits among your other labs",
"how the numbers were produced", the lab-contents table, the depth map. Those
go *after* the story has started, or into a short final section called
**"About this lab"**. A reader who opens the file should be inside the problem
within ten lines.

[Check: fewer than 25 lines before the first `## 1` heading.]

### 2. One story, all the way down

The characters and objects introduced in the problem section stay on stage in
every later section. The mechanism is told with them. The failures happen to
them. The proof is about them. The production section says what happens to them
at scale.

When a section has to switch to the engineer's names (acceptor, replica,
socket), the story name rides alongside on the first use in that section:
"teacher C (acceptor C)". The story is never abandoned for a "real" section.

[Check: the story's proper nouns appear in at least 60% of the sections after
the mechanism section.]

### 3. Ask before telling

Every major heading is a question, or the first sentence under it is one.
"Why can't the teacher just cross out Zoo?" beats "Acceptor state rules".

At every real fork, a **🔮 Predict** block asks the reader to commit before the
reveal. The reveal names the wrong guess too: "If you said the Museum wins,
you are treating two votes as a decision. Here is why they are not one."

[Check: at least 60% of `##` headings contain a question mark, or open with a
question in their first line. At least four Predict blocks in a lab over 500
lines.]

### 4. Words before symbols

Any notation is introduced three times, in this order:

1. as a *thing in the story* ("the teacher writes the ticket number at the top
   of her notebook page"),
2. as a *sentence in words* ("the highest ticket number she has promised to
   honour"),
3. and only then as the *symbol* (`promised_n`).

For its first three uses, the symbol carries its words next to it. Compact
state strings such as `A[p=2 wrote=Zoo@1]` never appear in the narrative. They
belong in a table with an "in words" column, or in an appendix, after the
reader has seen the same state drawn or described.

[Check: no `X[k=v ...]` state strings outside tables and appendices; every
`snake_case` identifier is preceded, somewhere earlier, by a plain-words
sentence saying what it is.]

### 5. Earn each term in the story, then name it once

A term appears *after* the reader has felt the problem it names. The pattern
is: the thing happens in the story → "engineers call this a *quorum*" → the
word is used from then on.

A term table is allowed, but it is a *map back to the story*, not a dictionary
dump. Each row says where the thing happened ("the note that got lost in
section 4"). No table has more than six rows; split by theme if there are more
(roles / messages / states). The full precise definitions live in the glossary
at the end.

[Check: no term table with more than 6 rows; every row references a section.]

### 6. No repeated templates

Never repeat the same skeleton for every term or every failure. Ten terms each
with "Plain meaning / Technical meaning / Why it exists / Concrete / Connects
to" is a wall, however good each bullet is. Nine failures each with eight
italic fields is a wall.

Instead: each term is a paragraph in the story where it is earned. Each
failure is a short story (what happened, what you would see, why, what
survived). When the same facts genuinely need to be compared across all
failures, put them in *one* comparison table with at most five columns.

[Check: no bold or italic label string repeats more than twice in the file.]

### 7. Short sentences, one idea each

Average sentence length at most 18 words. No sentence above 35 words. A
sentence carries one new idea. Split at every "and", "which", "because", or
dash that introduces a second idea. Paragraphs stay under about 90 words.

Prefer the plain word: "wait" not "block on", "copy" not "replica" until
replica has been earned, "the moment" not "the instant at which".

[Check: `tools/audit.py` average sentence length ≤ 18, fewer than 10% of
sentences over 30 words.]

### 8. Numbers live in tables

A measured result is a table row with a column that says what it means. It is
not a clause inside a sentence. Prose says "the reused connection was roughly
twenty times faster"; the table says 682 ms vs 38 ms.

Notes about how numbers were obtained ("measured on this machine", "run
headlessly", "verified against the simulator") appear *once*, in the "About
this lab" section at the end, never sprinkled through the story.

[Check: fewer than 5 epistemic asides outside the final section.]

### 9. Say it back

At each depth checkpoint (beginner → intermediate → engineering → production)
there is a **Say it back** box: one or two prompts the reader answers aloud in
their own words, with a model answer in a reveal. This is Feynman's test: if
you cannot say it, you do not have it.

Example:

> **Say it back.** Without using the words *prepare*, *promise* or *quorum*,
> explain to a friend why the second class rep ends up campaigning for the
> Zoo she never wanted.
> <details><summary>A good answer sounds like</summary>
> "She had to ask three teachers before she could ask for anything. Any three
> teachers include at least one who already wrote down Zoo. That teacher tells
> her, and the rule says she has to carry what she was told."
> </details>

[Check: one Say-it-back per checkpoint.]

### 10. Never cut

A rewrite keeps every fact, number, table, diagram, worked example, exercise
reference, related-lab link and glossary entry from the original. It may
reorder, split, retell, add, and move epistemic notes to the appendix. It may
not remove.

[Check: `tools/preserve-check.py <old> <new>` passes.]

---

## The shape of a section

Most sections follow this rhythm. Not every section needs every beat; the
order is what matters.

| Beat | What it is | Example |
|---|---|---|
| **Question heading** | The question the reader has right now | `## 9. What does one round actually look like?` |
| **In one breath** | The answer in 1–3 plain sentences | "The class rep asks three teachers for a ticket. If any of them already wrote something down, she has to campaign for that instead of her own idea." |
| **Story beat** | The thing happening to the characters | "You mail teachers A, B and C. All three write '1' on their page and reply 'nothing here yet'…" |
| **Engineer's version** | The same events in real names, symbols last | "That reply is `Promise(1, none)`. In words: ticket 1 is yours, and I have accepted nothing." |
| **Exhibit** | A table, trace or diagram, signposted first | "Read the third column: it is the teacher's notebook after each step." |
| **Predict or Say it back** | The reader commits | 🔮 *Only A and B received the note. Can Priya's Museum still win?* |

The "In one breath" line is a blockquote at the top of every major section:

> **In one breath:** …

---

## Depth layers and checkpoints

Labs keep the four depth layers, **Beginner → Intermediate → Engineering →
Production**, and the checkpoint at each transition. The depth map is a small
table that appears *after* the opening story, not before it:

| Layer | Sections | After it you can… |
|---|---|---|
| Beginner | 1–8 | tell the story and say why the naive fix fails |
| Intermediate | 9–10 | run one round in your head, message by message |
| Engineering | 11–13 | reason about every failure and both decision rules |
| Production | 14–16 | say what a real system adds, and what the on-call engineer sees |

Each checkpoint has a Say-it-back box (rule 9).

---

## Voice

- Second person, present tense in the story. "You mail five notes." Not "the
  proposer transmits five messages."
- Warm and direct. No throat-clearing ("It is worth noting that"). No
  cleverness that costs a re-read.
- Reuse the workspace's recurring characters where they fit (Asha, Meera,
  Priya, Arjun, Ben, Dev, Omar). A reader who met Asha in `acid` recognises
  her in `pacelc`.
- British and Indian settings are already the house style (rupees, tiffins,
  Mumbai, Delhi). Keep them.
- Analogies are entry points, not the model. Every analogy section ends with
  **"Where the story lies to you"** and the engineering truth that replaces
  each lie.

---

## Before and after

Three fragments from the old labs, and the same content in this voice. The
facts are identical.

**Before (paxos, mental model):**

> The engineering model — the same machine, stated precisely. The entire
> system is three kinds of state plus one rule: each acceptor holds exactly
> three durable numbers: `promised_n` (highest slot it has promised), and
> `accepted_n` / `accepted_value` (the last thing it wrote down)…

**After:**

> Every teacher keeps one notebook page with three lines on it.
>
> | Line | What she writes there | Engineers call it |
> |---|---|---|
> | 1 | the highest ticket number she has promised to honour | `promised_n` |
> | 2 | the ticket number of the last note she actually wrote down | `accepted_n` |
> | 3 | what that note said (Zoo or Museum) | `accepted_value` |
>
> That page is the whole of Paxos's memory. Everything the protocol does is
> either a question about this page or a change to it.

**Before (networking, terminology):**

> ### Refused (RST) versus dropped
> - **Plain meaning:** …
> - **Technical meaning:** …
> - **Why this distinction exists:** …
> - **Concrete, measured in experiment 2:** …

**After:**

> ## 7. Why is a fast failure good news?
>
> > **In one breath:** If the far machine answers "nobody is home", you learn
> > something in a millisecond. If nothing answers at all, you learn nothing
> > and you learn it slowly.
>
> Priya's parcel came back stamped "no such flat" the same afternoon. Her
> second parcel simply never came back. Which of those two helped her more?
> The first. It was a *reply*. Something at the far end read the address and
> answered. Engineers call that reply a **reset**, and the failure "refused".
> …
>
> | What happened | How long it took | What your code sees | What it proves |
> |---|---|---|---|
> | Refused | 0–4 ms | `ConnectException` | the path works; nothing is listening |
> | Dropped | 3,001 ms (timeout 3,000) | `SocketTimeoutException` | nothing answered at all |

**Before (paxos, correctness):**

> Chain the reasoning: a value is chosen when a majority `M1` accepts it at
> number `n1`. Any later proposal `n2 > n1` must first collect promises from
> some majority `M2`. `M1 ∩ M2 ≠ ∅`, so at least one acceptor in `M2`…

**After:**

> Why can the school never end up with two destinations? Try to build it.
> Pick any three teachers who wrote down Zoo. Now pick any three teachers for
> a later attempt. You cannot do it without reusing at least one teacher:
> there are only five. That shared teacher still has Zoo on her page, and the
> rule says she must report it, and the rule says the new class rep must
> carry what she reports. So the later attempt proposes Zoo too. Repeat that
> for every later attempt and there is never a second destination.
>
> That paragraph *is* the proof. Written the way papers write it: the first
> majority is M1, the later one is M2, "at least one shared teacher" is
> M1 ∩ M2 ≠ ∅, and the whole thing is Lamport's invariant P2c.

---

## The checklist a rewrite runs before it is finished

```
python tools/preserve-check.py <old README> <new README>   # nothing lost
python tools/audit.py <lab folder>                          # sentence, template, front-matter signals
```

And by eye:

- [ ] Story in the first ten lines; meta in "About this lab" at the end
- [ ] Characters present in the mechanism, failure, proof and production sections
- [ ] Every `##` a question or followed by one; a Predict at every fork
- [ ] Every symbol shown as a thing, then words, then the symbol
- [ ] No term table over six rows; no template repeated
- [ ] A Say-it-back at each checkpoint
- [ ] Same section numbers as before, so `EXERCISES.md` and `SOLUTIONS.md` references still hold
- [ ] Numbers in tables; epistemic notes in the appendix
