# Java 17 — the LTS the ecosystem actually moved to

**GA:** September 2021 · **LTS:** **yes** · **Class file major version:** 61 ·
**Oracle Premier Support:** September 2026 · **Extended:** September 2029
**[documented — Oracle roadmap]**

**Previous:** [Java 16](java-16.md) · **Next:** [Java 18](java-18.md) ·
**Back to:** [the story](README.md)

---

## The one thing to remember

**Java 17 is the release where "modern Java" became available on a supported
LTS.** Records, sealed classes, pattern matching for `instanceof`, text blocks
and switch expressions are all final by 17. None of them are *new* in 17 — they
arrived across 14, 15 and 16 — but 17 is the first release with long-term
support that has them all.

That is why 11→17 feels like a huge jump: **you are swallowing six releases at
once**, which is exactly the problem the README's
[stage 4 ratchet](README.md#4-failure--why-cutting-the-feature-doesnt-help-either)
describes. And it is why the LTS cadence subsequently changed from three years
to two.

The other thing 17 is remembered for: **JEP 403 removes the encapsulation
escape hatch.** After 17 there is no global `--illegal-access=permit`.

## What you actually get *that is new in 17 itself*

Most of 17's value is inherited from 14–16. Here is what is genuinely new here.

### Sealed classes — **final** — JEP 409

Two previews ([15](java-15.md), [16](java-16.md)), done. See
[Java 15](java-15.md) for what sealing is actually for — giving the compiler a
complete list of alternatives so exhaustiveness can be checked.

With records (16) and sealed types (17) both final, the modern Java domain
model is now expressible without flags:

```java
sealed interface PaymentResult permits Approved, Declined, PendingReview { }
record Approved(String authCode)          implements PaymentResult { }
record Declined(String reason)            implements PaymentResult { }
record PendingReview(Duration expectedIn) implements PaymentResult { }
```

The `switch` that consumes it exhaustively still needs
[Java 21](java-21.md) — pattern matching for `switch` is only a *first preview*
here (JEP 406). On 17 you get the data model but not yet the ergonomic way to
take it apart; you use `if (r instanceof Approved a)` chains instead.

### Pattern matching for `switch` — JEP 406 (**first preview**)

The start of the five-preview run: 17 → 18 → 19 → 20 → final in
[21](java-21.md). Preview only here, so not usable in production code.

### Enhanced pseudo-random number generators — JEP 356

A proper interface hierarchy (`RandomGenerator`) plus modern algorithms
(LXM, Xoroshiro, Xoshiro) with a factory:

```java
RandomGenerator g = RandomGenerator.of("L64X128MixRandom");
RandomGenerator.getDefault();
```

Why it matters: `java.util.Random` is a 1995 linear congruential generator with
a 48-bit seed. It is fine for a shuffle and unfit for simulation or sampling.
`ThreadLocalRandom` and `SplittableRandom` now fit into one hierarchy, so
splittable/jumpable generators for parallel streams are expressible.
(Still not for cryptography — that is `SecureRandom`.)

### Context-specific deserialization filters — JEP 415

Java deserialization is a long-running source of remote-code-execution
vulnerabilities. JEP 290 (Java 9) added filters; JEP 415 lets you set a filter
*per deserialization context* rather than only globally.

If your service deserializes Java-serialized data from anywhere untrusted, this
is the mitigation. The better fix remains: **don't use Java serialization for
data crossing a trust boundary** — use JSON, Protobuf, Avro.

### macOS/AArch64 port — JEP 391

Native Apple Silicon. Practical consequence: on an M-series Mac, JDK 17 is the
oldest release with an official native build. Java 11 on Apple Silicon runs
under Rosetta unless you use a vendor backport.

### Always-strict floating point — JEP 306

`strictfp` becomes the only behaviour; the keyword is now redundant. A 1990s
compromise for x87 hardware, finally retired.

### Foreign Function & Memory API (incubator) — JEP 412

The point at which the separate foreign-memory and foreign-linker incubators
**merge into one API**. See the README's
[eight-year ladder](README.md#the-very-long-case--the-foreign-function--memory-api-eight-years).

## Pipeline movement this release

| Feature | State here | Ends up |
|---|---|---|
| Sealed classes (JEP 409) | **FINAL** | done |
| **Pattern matching for `switch` (JEP 406)** | 1st preview | Final in [21](java-21.md) — five previews |
| FFM (JEP 412) | 1st incubator *as FFM* | Final in [22](java-22.md) |
| Vector API (JEP 414) | 2nd incubator | Still incubating in [25](java-25.md) |

## What broke — the reason 11→17 is a project

- **JEP 403: strong encapsulation, no escape hatch.** `--illegal-access` is
  **removed as an option**. [Java 16](java-16.md) flipped the default to `deny`
  but let you set `permit`; 17 deletes the switch. From here the only way in is
  a narrow, explicit `--add-opens java.base/java.lang=ALL-UNNAMED` per package.
  This is [F3](README.md#f3--inaccessibleobjectexception-strong-encapsulation-jdk-1617),
  measured on JDK 25.0.4 in the README.

  **In practice this is a dependency-upgrade problem, not a code problem.** The
  offenders are old versions of reflection-heavy libraries. Run
  `jdeps --jdk-internals` before you start.

- **RMI Activation removed — JEP 407.** Deprecated in [15](java-15.md).
- **The experimental AOT and JIT compiler removed — JEP 410.** `jaotc` and the
  Graal JIT integration. Note the naming collision: this is *not* the AOT cache
  of [Java 24](java-24.md)/[25](java-25.md), which is a different mechanism
  from Project Leyden. GraalVM native image is also unaffected — it is a
  separate product.
- **Applet API deprecated for removal — JEP 398.** Actually removed in JDK 26
  (JEP 504).
- **Security Manager deprecated for removal — JEP 411.** This starts the ladder
  that ends in [Java 24](java-24.md)'s permanent disabling
  ([F9](README.md#f9--the-security-manager-is-permanently-disabled-jdk-24-jep-486)).
  Seven years of notice.

## Verdict for your day job

**A perfectly reasonable place to be right now, and the right *waypoint* if you
are on 11.**

Oracle Premier Support runs to September 2026 and extended to 2029; other
vendors differ. Spring Boot 3.x requires 17 as a minimum, which is why so much
of the ecosystem consolidated here.

**If you are on 11:** go to 17 as its own piece of work. The risk is
concentrated in JEP 403 and the dependency upgrades it forces, and doing that
in isolation is much easier to reason about than doing it inside an 11→25 jump.

**If you are on 17:** your next move is [21](java-21.md) or straight to
[25](java-25.md). Both are far gentler than 11→17 was — the hard structural
change is already behind you. Virtual threads
([java-virtual-threads](../java-virtual-threads/)) alone justify the move if
you run a request-per-thread service.
