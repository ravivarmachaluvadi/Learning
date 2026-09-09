# Java 25 — the current LTS, and where you should be going

**GA:** September 2025 · **LTS:** **yes** · **Class file major version:** 69 ·
**Oracle Premier Support:** September 2030 · **Extended:** September 2033
**[documented — Oracle roadmap]**

**Previous:** [Java 24](java-24.md) · **Next:** JDK 26 (March 2026, non-LTS) ·
**Back to:** [the story](README.md)

**Everything marked [measured] in this file was executed on OpenJDK 25.0.4
(Homebrew, macOS) during this lab's build.**

---

## The one thing to remember

**Java 25 is the current LTS and the correct target for essentially any Java
work today.** Supported to 2030 (premier) / 2033 (extended) under Oracle's
roadmap, with equivalents from other vendors.

It has no single headline feature the way [21](java-21.md) had virtual threads.
Its value is that it is the release where a large number of long ladders
finish at once — scoped values, module imports, compact source files, flexible
constructor bodies, compact object headers, generational Shenandoah — plus it
inherits [Java 24](java-24.md)'s `synchronized` unpinning fix, which is what
makes virtual threads genuinely safe to adopt on an existing codebase.

## What you actually get

### Scoped values — **FINAL** — JEP 506

Five years, six ticks: incubator in [20](java-20.md), then previews in 21, 22,
23, 24, final here.

```java
private static final ScopedValue<Principal> USER = ScopedValue.newInstance();

ScopedValue.where(USER, principal).run(() -> handleRequest());
// anywhere beneath, with no parameter threading:
Principal p = USER.get();
```

Full treatment in the [scoped-values](../20-scoped-values/) lab. The reason it
matters *now*: with virtual threads, `ThreadLocal` becomes a liability — a
million virtual threads each carrying a `ThreadLocal` map is a million maps,
and `InheritableThreadLocal` copies them on every fork. A `ScopedValue` is
immutable, bound for a block, inherited by reference, and gone when the block
exits on any path.

Note that scoped values are final while **structured concurrency, which they
were designed alongside, is still a fifth preview** (JEP 505) here and a sixth
in JDK 26 (JEP 525). Two Loom features, same team, adjacent scope, seven years
apart in maturity — the difference is entirely how much design risk each
carried.

### Compact source files and instance main methods — **FINAL** — JEP 512

The end of a four-preview run that was renamed twice (JEP 445 "Unnamed Classes
and Instance Main Methods" → 463/477 "Implicitly Declared Classes" → 495
"Simple Source Files" → 512 "Compact Source Files").

A complete Java program is now:

```java
void main() {
    IO.println("Hello");
}
```

**[measured, JDK 25.0.4]** — that file compiles with `javac P.java`, no flags,
no warnings.

Three things happen: the class declaration is implicit, `main` need not be
`static` or `public` or take `String[]`, and `java.lang.IO` provides
`IO.println` / `IO.readln` without an import.

**Who this is for:** learners, scripts, and small tools. It is not intended to
change how you write a service, and a compact source file cannot be part of a
package or referenced by name from other code. Combined with
`java Hello.java` ([11](java-11.md)) and multi-file source programs
([22](java-22.md)), Java is now a viable scripting language for the first time.

### Module import declarations — **FINAL** — JEP 511

```java
import module java.base;
```

**[measured, JDK 25.0.4]** — compiles with no flags on 25.

Imports every package exported by the named module. Genuinely useful in
scripts and compact source files; use with care in a large codebase, where
explicit imports carry information and reduce ambiguity. If two imported
modules export the same simple name, you must disambiguate with a single-type
import.

### Flexible constructor bodies — **FINAL** — JEP 513

Statements before `super(...)` or `this(...)`:

```java
public Order(List<Line> lines) {
    if (lines.isEmpty()) throw new IllegalArgumentException("empty order");
    super(computeTotal(lines));
}
```

You still cannot *read* the object's own fields before the super call — the
restriction that prevented observing an uninitialised object is intact. What
was lifted is the blanket ban on any statement at all, which forced validation
into static helper methods.

### Compact object headers — **product feature** — JEP 519

Promoted from experimental ([24](java-24.md)) to a product feature. 64-bit
object headers instead of 96/128, reducing heap footprint on object-heavy
workloads.

**[measured, JDK 25.0.4] — it is still not the default:**

```
bool UseCompactObjectHeaders = false {product lp64_product} {default}
```

You must pass `-XX:+UseCompactObjectHeaders`. "Product feature" means supported
and no longer needing `-XX:+UnlockExperimentalVMOptions` — not enabled. Worth
benchmarking on a service with a large live set.

### Generational Shenandoah — **FINAL** — JEP 521

Out of experimental. Shenandoah now has generations, as ZGC has since
[21](java-21.md).

### AOT command-line ergonomics and method profiling — JEP 514, 515

Building on [24](java-24.md)'s JEP 483. JEP 514 simplifies cache creation
(`-XX:AOTCacheOutput` in one step rather than a two-command dance); JEP 515
records method profiles in the cache so the JIT starts from a warm profile
rather than cold.

**[measured, JDK 25.0.4, macOS, 8 runs each]:**

| Configuration | Mean startup | Classes from archive |
|---|---|---|
| Default (CDS only) | 0.1050 s | 956 of 1003 |
| AOT cache (12.6 MB) | 0.0862 s | 1039 of 1048 |

18% on a small program. Larger applications gain more — that is documented,
not measured here. The command that produced the cache:

```
$ java -XX:AOTCacheOutput=app.aot -cp . Boot
$ java -XX:AOTCache=app.aot -cp . Boot
```

### JFR: CPU-time profiling, cooperative sampling, method timing — JEP 509, 518, 520

Flight Recorder gets a real CPU-time profiler (Linux, experimental), safer
stack sampling that doesn't require a safepoint, and method timing and tracing.

The safepoint point is the important one: traditional Java profilers that
sample at safepoints are biased, because safepoints only occur at certain
bytecode positions — so hot loops without a safepoint poll are systematically
under-sampled. JEP 518's cooperative sampling reduces that bias. If you have
ever had a profiler confidently point at the wrong method, this is why.

### Key Derivation Function API — **FINAL** — JEP 510

A standard API for KDFs (HKDF, and Argon2 later). Completes the crypto set with
[21](java-21.md)'s KEM API and [24](java-24.md)'s post-quantum algorithms.

### Stable values — JEP 502 (**preview**)

Deferred immutability: a field initialised at most once, on first use, with the
JIT able to treat it as constant afterwards — the safe version of the
double-checked-locking idiom.

Note for the pipeline: this is **renamed "Lazy Constants" in JDK 26**
(JEP 526, second preview). A rename between previews is normal and is exactly
the kind of change the preview gate exists to permit.

### PEM encodings — JEP 470 (**preview**)

Standard API for reading and writing PEM (`-----BEGIN CERTIFICATE-----`) text.
Long overdue; everyone has hand-rolled this.

## Pipeline movement this release

| Feature | State here | Ends up |
|---|---|---|
| **Scoped values (JEP 506)** | **FINAL** (6 ticks) | done |
| **Compact source files (JEP 512)** | **FINAL** (5 ticks, 2 renames) | done |
| **Module imports (JEP 511)** | **FINAL** | done |
| **Flexible constructor bodies (JEP 513)** | **FINAL** (4 ticks) | done |
| **Compact object headers (JEP 519)** | **Product** (opt-in) | done |
| **Generational Shenandoah (JEP 521)** | **FINAL** | done |
| **KDF API (JEP 510)** | **FINAL** | done |
| Stable values (JEP 502) | 1st preview | Renamed "Lazy Constants", 2nd preview in 26 |
| PEM encodings (JEP 470) | 1st preview | 2nd preview in 26 |
| Structured concurrency (JEP 505) | **5th preview** | 6th preview in 26 |
| Primitive patterns (JEP 507) | 3rd preview | 4th preview in 26 |
| **Vector API (JEP 508)** | **10th incubator** | 11th in 26 |

## What broke

- **32-bit x86 port removed — JEP 503.** Deprecated in [24](java-24.md).
- **`--release 8` warns as obsolete.** **[measured, JDK 25.0.4]**:

  ```
  $ javac --release 8 T.java
  warning: [options] source value 8 is obsolete and will be removed in a future release
  warning: [options] target value 8 is obsolete and will be removed in a future release
  ```

  Supported releases on 25.0.4 are **8 through 25**; `--release 7` is already
  an error. [F10](README.md#f10----release-8-warns-and-older-is-already-gone-jdk-25).
  Note the asymmetry: **running** Java 8 bytecode on JDK 25 is fine and always
  will be; **compiling for** Java 8 is what expires.
- Everything inherited from [24](java-24.md): Security Manager disabled,
  `Unsafe` warnings, non-generational ZGC gone, JNI warnings.

## What is still coming (JDK 26, March 2026) **[documented]**

| JEP | Feature |
|---|---|
| 517 | **HTTP/3 for the HTTP Client API** |
| 504 | Remove the Applet API (deprecated since [17](java-17.md)) |
| 500 | Prepare to Make Final Mean Final — starts restricting reflective mutation of `final` fields |
| 516 | AOT object caching with any GC |
| 522 | G1: improve throughput by reducing synchronisation |
| 525 | Structured concurrency (**sixth** preview) |
| 526 | Lazy Constants (was Stable Values), 2nd preview |
| 529 | Vector API (**eleventh** incubator) |
| 530 | Primitive types in patterns (4th preview) |

JEP 500 is the one to watch: reflectively writing a `final` field —
which some serialisation and DI frameworks still do — starts down the removal
ladder. Same shape as JEP 451 and JEP 472 before it.

## Verdict for your day job

**This is the answer. Target Java 25.**

- Supported to 2030 premier / 2033 extended (Oracle; check your vendor).
- Everything from Amber is final: records, sealed types, pattern matching,
  text blocks, switch expressions.
- Virtual threads are final *and* the `synchronized` pinning caveat is gone
  ([24](java-24.md), JEP 491) — which is what makes them adoptable on a large
  existing codebase rather than only on new code.
- Scoped values are final, so the `ThreadLocal` replacement story is complete.
- Spring Boot 4 requires it.

**Still not safe to build on:** structured concurrency (fifth preview, sixth in
26, API already redesigned once), primitive patterns (third preview), the
Vector API (tenth incubator, waiting on Valhalla), stable values (renamed in
26). Use them in throwaway experiments; keep them out of anything you publish.

**Routes:**

| From | Route | Where the risk is |
|---|---|---|
| 8 | 8 → 17 → 21 or 25 | Almost all of it in 8→17: [F1](README.md#f1--split-packages-jdk-9-the-module-system), [F2](README.md#f2--the-java-ee-and-corba-modules-are-gone-jdk-11-jep-320), [F3](README.md#f3--inaccessibleobjectexception-strong-encapsulation-jdk-1617) |
| 11 | 11 → 17 → 25 | [F3](README.md#f3--inaccessibleobjectexception-strong-encapsulation-jdk-1617), and dependency upgrades it forces |
| 17 | 17 → 25 directly | Light. [F6](README.md#f6--utf-8-became-the-default-charset-jdk-18-jep-400) charset default, GC flags, `Unsafe` warnings |
| 21 | 21 → 25 directly | Very light |

And whichever route you take, the practice that matters most is the one from
the README's [playbook](README.md#the-playbook): put the next JDK in a
**non-blocking CI job now**, running your full behavioural test suite, and let
it accumulate small known fixes instead of one big unknown project.
