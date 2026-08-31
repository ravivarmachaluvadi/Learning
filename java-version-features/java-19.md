# Java 19 — virtual threads appear

**GA:** September 2022 · **LTS:** no · **Class file major version:** 63 ·
**Oracle support:** ended March 2023 **[documented — Oracle roadmap]**

**Previous:** [Java 18](java-18.md) · **Next:** [Java 20](java-20.md) ·
**Back to:** [the story](README.md)

---

## The one thing to remember

**Java 19 is where Project Loom becomes visible.** Virtual threads, structured
concurrency and record patterns all arrive as preview or incubator here. None
of them are usable in production from this release — that is the point of the
gates — but this is the release where the shape of Java's concurrency future
became public.

You already know two of these features from other labs:
[java-virtual-threads](../java-virtual-threads/) and
[structured-concurrency](../structured-concurrency/). What this file adds is
*where they sit in the pipeline*, which turns out to matter a great deal for
whether you should use them.

## What you actually get

### Virtual threads — JEP 425 (**first preview**)

A thread that is scheduled by the JVM onto a small pool of OS carrier threads,
so that blocking becomes cheap and thread-per-request scales to hundreds of
thousands of concurrent operations.

```java
Thread.startVirtualThread(() -> handle(request));

try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
    for (var r : requests) exec.submit(() -> handle(r));
}   // close() waits for all tasks
```

You have the full treatment in the
[java-virtual-threads](../java-virtual-threads/) lab. The relevant fact *for
this lab* is the ladder: preview in 19, second preview in [20](java-20.md),
**final in [21](java-21.md)** (JEP 444). Three ticks, eighteen months — the
healthy shape from the README.

**One correction to that lab, caused by a later release:** it describes
`synchronized` blocks **pinning** a virtual thread to its carrier, and
recommends `ReentrantLock` instead. That was correct for JDK 21. **JEP 491 in
[Java 24](java-24.md) fixed it** — `synchronized` no longer pins. On 24+, the
pinning-avoidance advice no longer applies for that reason. (Native frames and
JNI still pin.)

### Structured concurrency — JEP 428 (**first incubator**)

Confine concurrent subtasks' lifetimes to a syntactic block, so that errors
propagate up and cancellation propagates down.

Note the gate: **incubator**, `jdk.incubator.concurrent`, requiring
`--add-modules`. It later moves to *preview* and into `java.util.concurrent`.

The ladder for this one is the outlier in the whole dataset:
19 (incubator) → 20 (2nd incubator) → 21 (preview) → 22 (2nd preview) →
23 (3rd preview) → 24 (4th preview) → **25 (5th preview, JEP 505)** →
26 (6th preview, JEP 525). **Seven years and still not final.**

And the API was substantially redesigned in the middle: JDK 21's
`StructuredTaskScope.ShutdownOnFailure()` became JDK 25's
`StructuredTaskScope.open(joiner)`. The [structured-concurrency](../structured-concurrency/)
lab documents the JDK 25 shape. **Do not build a library on it** — this is
exactly the situation the README's
[F5](README.md#f5--the-preview-jar-that-runs-on-exactly-one-jdk) warns about.

### Record patterns — JEP 405 (**first preview**)

Destructure a record inside a pattern:

```java
// without record patterns
if (o instanceof Point p) {
    int x = p.x(), y = p.y();
    ...
}

// with them
if (o instanceof Point(int x, int y)) { ... }

// and nested
if (o instanceof Line(Point(var x1, var y1), Point(var x2, var y2))) { ... }
```

This is the third of Project Amber's three pieces — records
([16](java-16.md)) say what data is, sealed types ([17](java-17.md)) say what
the alternatives are, and patterns take them apart. Final in
[21](java-21.md).

### Foreign Function & Memory API — JEP 424 (**preview**)

The transition from *incubator* to *preview*, which is the graduation that
**renames the package**: `jdk.incubator.foreign` → `java.lang.foreign`. Every
early adopter's imports break here, by design — this is the incubator mechanism
from the README doing its job.

### Linux/RISC-V port — JEP 422

## Pipeline movement this release

This is the busiest pipeline release in the lab:

| Feature | State here | Ends up |
|---|---|---|
| **Virtual threads (JEP 425)** | 1st preview | **Final in [21](java-21.md)** |
| **Structured concurrency (JEP 428)** | 1st incubator | Still preview in [25](java-25.md) and 26 |
| **Record patterns (JEP 405)** | 1st preview | Final in [21](java-21.md) |
| Pattern matching for `switch` (JEP 427) | 3rd preview | Final in [21](java-21.md) |
| **FFM (JEP 424)** | **Preview** — graduated from incubator, package renamed | Final in [22](java-22.md) |
| Vector API (JEP 426) | 4th incubator | Still incubating in [25](java-25.md) |

Look at that table next to the "healthy shape is three ticks" rule from the
README. Virtual threads and record patterns take three. Structured concurrency
takes more than seven. Same team, same release, same process — the difference
is entirely in how much design risk each carries.

## What broke

Nothing significant.

## Verdict for your day job

**Don't target it.** Six months, ended March 2023.

Read this file for the pipeline table. It is the single clearest snapshot of
"four features enter, at four different maturity levels, and end up in four
different places" — which is the mechanism this lab is about, visible in one
release.
