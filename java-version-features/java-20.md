# Java 20 — a holding release

**GA:** March 2023 · **LTS:** no · **Class file major version:** 64 ·
**Oracle support:** ended September 2023 **[documented — Oracle roadmap]**

**Previous:** [Java 19](java-19.md) · **Next:** [Java 21](java-21.md) ·
**Back to:** [the story](README.md)

---

## The one thing to remember

**Java 20 finalises nothing.** Every one of its seven JEPs is a preview,
a re-preview, or an incubator. It is the release immediately before an LTS, and
its entire job is to let four major features take one more iteration before
[Java 21](java-21.md) freezes them.

That makes it a genuinely useful illustration of what the release train buys.
Under the old model, a release with nothing final in it would have been an
embarrassment or simply wouldn't have shipped. Under the train it is normal and
correct: **a tick where features advance one step is a productive tick**, and
it is precisely because 20 existed that 21 could ship virtual threads, record
patterns and pattern matching for `switch` as *final* rather than as a fourth
preview.

## What you actually get

Nothing you can use in production. The complete JEP list:

| JEP | Feature | State |
|---|---|---|
| 429 | Scoped values | 1st incubator |
| 432 | Record patterns | 2nd preview |
| 433 | Pattern matching for `switch` | 4th preview |
| 434 | Foreign Function & Memory API | 2nd preview |
| 436 | Virtual threads | 2nd preview |
| 437 | Structured concurrency | 2nd incubator |
| 438 | Vector API | 5th incubator |

### Scoped values — JEP 429 (**first incubator**)

The only feature making its debut here. A `ScopedValue` is an immutable value
bound for the dynamic extent of a block and visible to everything called
beneath it — the replacement for `ThreadLocal` in a world with millions of
virtual threads.

```java
private static final ScopedValue<Principal> USER = ScopedValue.newInstance();

ScopedValue.where(USER, principal).run(() -> handleRequest());
// deep inside, with no parameter threading:
Principal p = USER.get();
```

You have the full treatment in the [scoped-values](../scoped-values/) lab.
Its ladder: incubator in 20 → preview in 21 → 2nd preview in 22 → 3rd in 23 →
4th in 24 → **final in [Java 25](java-25.md)** (JEP 506). Six ticks, five
years.

Worth contrasting with structured concurrency, which entered the pipeline
*earlier* (19) and is *still* previewing in 26. Two Loom features, adjacent in
scope, one finalised and one not — because scoped values' API surface is small
and its semantics were settled early, while structured concurrency's joiner
design took several attempts.

### The rest

Each of the other six is one step further along a ladder described elsewhere:
record patterns and pattern matching for `switch` are heading for
[21](java-21.md); FFM for [22](java-22.md); virtual threads for
[21](java-21.md); structured concurrency for somewhere past 26; the Vector API
for whenever Valhalla lands.

## Pipeline movement this release

Every JEP in the release is pipeline movement — see the table above. Zero
finalisations.

## What broke

Nothing.

## Verdict for your day job

**Don't target it.** Six months, ended September 2023 — the same month
[Java 21](java-21.md) shipped.

Read this file for one idea: **a release with nothing final in it is the system
working, not failing.** If you take one thing from the whole lab about how
Java's process differs from a feature-driven release model, it is that the
train can depart carrying only work-in-progress, and that this is what lets the
*next* train carry finished work.
