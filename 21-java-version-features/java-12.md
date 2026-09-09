# Java 12 — the first preview feature in Java's history

**GA:** March 2019 · **LTS:** no · **Class file major version:** 56 ·
**Oracle support:** ended September 2019 **[documented — Oracle roadmap]**

**Previous:** [Java 11](java-11.md) · **Next:** [Java 13](java-13.md) ·
**Back to:** [the story](README.md)

---

## The one thing to remember

**Java 12 is where preview features start.** JEP 325, switch expressions, is
the first feature ever shipped under JEP 12's preview mechanism — and it is
also the feature that *proved the mechanism was necessary*, because its syntax
was wrong and got changed in the next release.

If you read one section of this file, read the switch-expressions one below.
The `break value` → `yield` story is the single clearest justification for the
entire preview system, and it is told in full in the README's
[mechanism v1](README.md#mechanism-v1-just-release-every-six-months).

## What you actually get

### Switch expressions — JEP 325 (**first preview**)

A `switch` that produces a value, with arrow syntax and no fall-through:

```java
// the shipped-in-12 preview form
int numLetters = switch (day) {
    case MONDAY, FRIDAY, SUNDAY -> 6;
    case TUESDAY                -> 7;
    default -> {
        int len = day.toString().length();
        break len;         // <-- this is the syntax that got thrown away
    }
};
```

Three improvements over statement `switch`, all of which survive to the final
form:

1. **It is an expression** — it produces a value, so you can assign it.
2. **No fall-through** with `->`, so no forgotten `break` bugs.
3. **Exhaustiveness is checked** when switching over an enum — omit a constant
   and it will not compile (for the expression form). This is the property that
   later combines with sealed types in [Java 21](java-21.md) to make pattern
   matching safe.

**And the part that mattered:** `break len;` overloaded `break`, which already
means "leave the enclosing loop or switch". Inside a loop inside a switch, you
could not tell which was meant. Feedback said so. The
[second preview in Java 13](java-13.md) replaced it with `yield`, and the
final form in [Java 14](java-14.md) shipped with `yield`.

Total cost of shipping a wrong language syntax under this system: **one
release, in preview, behind a flag, affecting only people who opted in.**
Under the old model it would have been permanent.

### Default CDS archives — JEP 341

**The performance feature you have been getting for free ever since.** The JDK
now ships with a pre-built class-data-sharing archive of core classes, and it
is **enabled by default**. Startup gets faster with no flags, no configuration
and no awareness on your part.

This lab measured its effect on JDK 25.0.4: of 1003 classes loaded by a small
program, **956 came from the shared archive**. See the README's
[production section](README.md#the-performance-features-you-get-for-free-and-what-theyre-worth)
for the full numbers and how the JDK 24/25 AOT cache builds on this.

### Shenandoah (experimental) — JEP 189

A second low-pause concurrent collector, developed at Red Hat, with a different
design from ZGC (Brooks pointers / load reference barriers rather than coloured
pointers). Production-ready in [15](java-15.md), generational in
[24](java-24.md)/[25](java-25.md).

Note that OpenJDK builds from some vendors historically excluded Shenandoah;
check `java -XX:+PrintFlagsFinal -version | grep Shenandoah` on your actual
distribution before planning around it.

### G1 improvements — JEP 344, 346

**Abortable mixed collections (344):** if G1 predicts a mixed collection will
overshoot the pause target, it can now abort part of it rather than blow the
target. Fewer pause-time outliers.

**Promptly return unused committed memory (346):** G1 will now give memory back
to the OS during idle periods. This matters specifically in containers, where
an idle JVM holding its peak RSS forever is the difference between fitting in
your memory limit and being OOM-killed.

Both are automatic. No flags.

### `String` and `Files` additions

```java
"hi".indent(4);                      // add 4 spaces to each line
"abc".transform(s -> s + "!");       // apply a function; useful in chains
Files.mismatch(path1, path2);        // -1 if identical, else first differing byte offset
```

`Collectors.teeing` also arrives — feed one stream into two collectors and
merge the results:

```java
var stats = orders.stream().collect(Collectors.teeing(
        Collectors.counting(),
        Collectors.summingDouble(Order::total),
        (count, sum) -> new Summary(count, sum)));
```

### JVM Constants API — JEP 334

`java.lang.constant` — nominal descriptions of class-file constants. You will
almost certainly never use it directly; it exists for tools and for the
Class-File API that arrives in [Java 24](java-24.md).

### Microbenchmark suite — JEP 230

JMH-based benchmarks *inside* the JDK, so JDK developers can catch performance
regressions. Not something you consume, but it is why performance claims about
newer JDKs are more trustworthy than they used to be.

## Pipeline movement this release

| Feature | State here | Ends up |
|---|---|---|
| **Switch expressions (JEP 325)** | **1st preview** — the first ever | Final in [14](java-14.md) with `yield` instead of `break` |
| Shenandoah (JEP 189) | Experimental | Production in [15](java-15.md) |

## What broke

Almost nothing. This is a small, safe release.

## Verdict for your day job

**Don't target it.** Six months of support, ended September 2019.

Read it for the history: this is the release where Java learned to ship
language features it could still take back, and the very first thing it shipped
under that system turned out to need taking back. The mechanism paid for itself
immediately.
