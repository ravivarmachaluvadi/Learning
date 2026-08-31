# Java 23 — the release where a feature was taken back

**GA:** September 2024 · **LTS:** no · **Class file major version:** 67 ·
**Oracle support:** ended March 2025 **[documented — Oracle roadmap]**

**Previous:** [Java 22](java-22.md) · **Next:** [Java 24](java-24.md) ·
**Back to:** [the story](README.md)

---

## The one thing to remember

**Java 23 is the release where string templates were withdrawn.** Previewed in
[21](java-21.md), previewed again in [22](java-22.md), then removed from the
JDK with no replacement and no successor JEP.

This is the event that makes the preview mechanism *mean* something. If every
previewed feature eventually shipped, preview would be a delay with extra
flags. String templates are the proof that the brake works — and that the
warning attached to every preview feature is literal.

It is also where `sun.misc.Unsafe`'s memory-access methods start their removal
ladder, made possible by the FFM API finalising in [22](java-22.md).

## What you actually get

### String templates: **withdrawn**

There is no JEP for this in the JDK 23 list, because withdrawal doesn't get
one. `STR."Order \{id}"` compiled on JDK 21 and 22 with `--enable-preview`.
On JDK 23 it does not compile at all.

**Why it was pulled:** the design used a *template processor* applied to a
template expression (`STR`, `FMT`, or your own), which allowed
injection-safe interpolation — a genuinely valuable property, since the whole
point was to avoid people concatenating SQL. But the machinery required to
express that (a new kind of expression, a processor type, interaction with
`invokedynamic`) was judged too much complexity for the benefit, and rather
than finalise it or preview it a third time, it was removed while a simpler
design is sought.

**What this cost the people who used it:** exactly what the preview contract
said. Code using `STR."..."` became uncompilable. No deprecation, no ladder, no
migration path — because preview features are explicitly outside the removal
ladder that protects the specified API. See the README's
[compatibility contract](README.md#12-correctness--the-compatibility-contract).

**What it should change about your behaviour:** treat "it's only a preview
feature, we'll deal with it later" as a decision with a real, demonstrated
cost. This is not hypothetical any more.

### Markdown in Javadoc — JEP 467

```java
/// Returns the total, in minor units.
///
/// - Never negative
/// - See [Order#lines()]
public long total() { ... }
```

`///` comments are Markdown instead of HTML. A small quality-of-life change
that removes a great deal of `<p>` and `<ul>` noise from documentation.

### Module import declarations — JEP 476 (**preview**)

```java
import module java.base;      // every package exported by java.base
```

Instead of a screen of individual imports. Aimed mainly at scripts and
teaching, where the import block is a large fraction of a short program. Final
in [Java 25](java-25.md) — and **[measured, JDK 25.0.4]**, `import module
java.base;` compiles with no flags on 25.

### Generational ZGC by default — JEP 474

`-XX:+UseZGC` now means generational ZGC. The non-generational mode still
exists here behind `-XX:-ZGenerational`; it is **removed** in
[24](java-24.md) ([F12](README.md#f12--non-generational-zgc-removed-jdk-24-jep-490)).

### Primitive types in patterns — JEP 455 (**preview**)

Extends pattern matching to primitives:

```java
switch (x) {
    case int i when i > 100 -> "big";
    case int i              -> "small";
}

if (anInt instanceof byte b) { ... }     // exact-value conversion test
```

The interesting part is that `instanceof` on primitives tests whether the value
*fits* without loss, not whether the runtime type matches. Third preview in
[25](java-25.md) (JEP 507), fourth in 26 (JEP 530) — still not final, which
makes it another long-runner.

## Pipeline movement this release

| Feature | State here | Ends up |
|---|---|---|
| **String templates** | **WITHDRAWN** | gone |
| Markdown Javadoc (JEP 467) | **FINAL** | done |
| Generational ZGC (JEP 474) | **Default** | Only mode in [24](java-24.md) |
| Module imports (JEP 476) | 1st preview | Final in [25](java-25.md) |
| Primitive patterns (JEP 455) | 1st preview | 4th preview in 26 |
| Class-File API (JEP 466) | 2nd preview | Final in [24](java-24.md) |
| Stream gatherers (JEP 473) | 2nd preview | Final in [24](java-24.md) |
| Flexible constructor bodies (JEP 482) | 2nd preview | Final in [25](java-25.md) |
| Scoped values (JEP 481) | 3rd preview | Final in [25](java-25.md) |
| Structured concurrency (JEP 480) | 3rd preview | Still preview in 26 |
| Vector API (JEP 469) | 8th incubator | Still incubating in [25](java-25.md) |

## What broke

- **String templates withdrawn.** See above.
- **`sun.misc.Unsafe` memory-access methods terminally deprecated — JEP 471.**
  The first rung of the ladder. `@Deprecated(forRemoval=true)` means removal is
  committed. [Java 24](java-24.md) adds the runtime warning; removal comes
  later.

  Note the sequencing, which is the whole point of the README's
  [removal ladder](README.md#11-failure-scenarios--what-actually-breaks-and-why):
  `Unsafe` could not be deprecated until FFM finalised in
  [22](java-22.md). Eight years of API work had to complete before one
  deprecation was allowed to start.

## Verdict for your day job

**Don't target it.** Six months, ended March 2025.

But this is the file to read if you want to know whether preview features are
safe to adopt. The answer, demonstrated here rather than asserted: **for an
application you rebuild on every JDK upgrade, they are a manageable risk. For a
published library, they are not, and for anything you cannot afford to rewrite
on six months' notice, they are not.**
