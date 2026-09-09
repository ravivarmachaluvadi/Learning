# Java 15 — sealed classes, text blocks final, and ZGC grows up

**GA:** September 2020 · **LTS:** no · **Class file major version:** 59 ·
**Oracle support:** ended March 2021 **[documented — Oracle roadmap]**

**Previous:** [Java 14](java-14.md) · **Next:** [Java 16](java-16.md) ·
**Back to:** [the story](README.md)

---

## The one thing to remember

**Java 15 introduces sealed classes, the missing third piece of pattern
matching.** Records ([14](java-14.md)) say *what data is*. Sealed types say
*what the alternatives are*. Pattern matching ([16](java-16.md),
[21](java-21.md)) is what you do with both. Sealed types are the piece that
lets the compiler prove a `switch` is exhaustive — and exhaustiveness is what
makes the whole design safe rather than merely convenient.

Two low-pause garbage collectors also become production-ready here, and helpful
NullPointerExceptions become the default.

## What you actually get

### Sealed classes — JEP 360 (**first preview**)

A sealed type declares exactly which types may extend it:

```java
public sealed interface PaymentResult
        permits Approved, Declined, PendingReview { }

public record Approved(String authCode)          implements PaymentResult { }
public record Declined(String reason)            implements PaymentResult { }
public record PendingReview(Duration expectedIn) implements PaymentResult { }
```

Every permitted subtype must itself be `final`, `sealed`, or `non-sealed` — the
hierarchy has to terminate somewhere, or the seal means nothing.

**Why this exists — and it is not "to stop people extending my class".** That
is what `final` is for. Sealing exists to give the *compiler* a complete list of
alternatives, so it can check that you have handled all of them:

```java
String describe(PaymentResult r) {
    return switch (r) {                        // no default needed
        case Approved a      -> "authorised: " + a.authCode();
        case Declined d      -> "declined: " + d.reason();
        case PendingReview p -> "pending, expect " + p.expectedIn();
    };                                          // compiler proves this is exhaustive
}
```

Now add a fourth result type, `Refunded`, to the `permits` clause. **Every
switch over `PaymentResult` that doesn't handle it stops compiling.** That is
the payoff: adding a case to a domain becomes a compile-time task list instead
of a hunt for runtime `IllegalStateException`s.

If you have written Kotlin's `sealed class` or Scala's sealed traits, this is
the same idea. If you have written a `switch` with a `default -> throw new
IllegalStateException("unreachable")`, sealing is how you delete that line and
have the compiler enforce it instead.

**One practical note:** the permitted subtypes must be in the same module (or
the same package, if unnamed). You cannot seal across a module boundary you
don't control.

### Text blocks — **final** — JEP 378

Two previews ([13](java-13.md), [14](java-14.md)), done. No flag from here on.

### Helpful NullPointerExceptions on by default

The [Java 14](java-14.md) feature (JEP 358) that had to be enabled with
`-XX:+ShowCodeDetailsInExceptionMessages` is now the default. This is a
behaviour change in what your logs contain — a good one, but note that NPE
messages now include field and method names from your code.

### ZGC is production-ready — JEP 377

Out of experimental (from [Java 11](java-11.md)), no
`-XX:+UnlockExperimentalVMOptions` needed. `-XX:+UseZGC` is enough.

### Shenandoah is production-ready — JEP 379

Same graduation, from [Java 12](java-12.md).

**Choosing between them, briefly:** G1 (the default) is the right answer for
most services — good throughput, pause targets in the tens of milliseconds. ZGC
and Shenandoah trade some throughput for pauses that stay in the single-digit
milliseconds regardless of heap size. Reach for them when a pause-time SLO is
being missed, not by default, and measure. See `/learn jvm-garbage-collectors`
in the README's next steps for the full treatment.

### Hidden classes — JEP 371

Classes that cannot be discovered or linked by name, intended for frameworks
that generate classes at runtime — lambdas, proxies, mocking libraries,
scripting engines. The supported replacement for `Unsafe.defineAnonymousClass`,
and therefore another prerequisite for
[deprecating `Unsafe`](README.md#f4--sunmiscunsafe-is-on-its-way-out-jdk-23-24-and-beyond).

### `DatagramSocket` reimplemented — JEP 373

The UDP counterpart to [Java 13](java-13.md)'s socket rewrite. Same reason:
groundwork for virtual threads.

### EdDSA — JEP 339

Edwards-curve signatures (Ed25519, Ed448) in the standard crypto providers.
Faster and smaller than equivalent-strength ECDSA. Relevant if you issue or
verify JWTs.

## Pipeline movement this release

| Feature | State here | Ends up |
|---|---|---|
| Text blocks (JEP 378) | **FINAL** | done |
| ZGC (JEP 377) | **Production** | Generational in [21](java-21.md) |
| Shenandoah (JEP 379) | **Production** | Generational in [24](java-24.md)/[25](java-25.md) |
| **Sealed classes (JEP 360)** | 1st preview | Final in [17](java-17.md) |
| Records (JEP 384) | 2nd preview | Final in [16](java-16.md) |
| Pattern matching for `instanceof` (JEP 375) | 2nd preview | Final in [16](java-16.md) |
| Foreign-Memory Access (JEP 383) | 2nd incubator | FFM final in [22](java-22.md) |

## What broke

- **Nashorn removed — JEP 372.** Deprecated in [11](java-11.md). This is
  [F7 in the README](README.md#f7--nashorn-the-javascript-engine-is-gone-jdk-15-jep-372):
  `getEngineByName("nashorn")` returns **null**, and you get an NPE on the next
  line rather than anything mentioning JavaScript. Fix: add
  `org.openjdk.nashorn:nashorn-core`, or move to GraalVM JS.
- **Biased locking disabled and deprecated — JEP 374.** This is
  [F8](README.md#f8--biased-locking-removed-jdk-15-jep-374--a-silent-performance-change)
  — a **silent performance change** on uncontended `synchronized`. No error, no
  warning in your code. Measure if you have a lock-heavy hot path.
- **Solaris and SPARC ports removed — JEP 381.**
- **RMI Activation deprecated for removal — JEP 385.** Removed in
  [17](java-17.md).

## Verdict for your day job

**Don't target it.** Six months, ended March 2021.

Read the sealed-classes section properly, though — it is the conceptual keystone
of modern Java's data modelling, and most explanations of it get the *why*
wrong. You get the final version in [Java 17](java-17.md).
