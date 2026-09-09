# Java 24 — the release that fixed virtual threads' worst caveat

**GA:** March 2025 · **LTS:** no · **Class file major version:** 68 ·
**Oracle support:** ended September 2025 **[documented — Oracle roadmap]**

**Previous:** [Java 23](java-23.md) · **Next:** [Java 25](java-25.md) ·
**Back to:** [the story](README.md)

---

## The one thing to remember

**JEP 491 removes `synchronized` pinning.** On JDK 21, a virtual thread that
blocks inside a `synchronized` block stays stuck to its carrier OS thread,
which can starve the scheduler and silently cap your concurrency — and since
almost all existing Java library code uses `synchronized`, this was the single
biggest obstacle to adopting virtual threads on a real codebase.

From Java 24 it is gone. `synchronized` no longer pins.

This is a **direct correction to the [java-virtual-threads](../18-java-virtual-threads/)
lab**, which documents the JDK 21 behaviour and advises replacing
`synchronized` with `ReentrantLock`. That advice was right for 21 and is
unnecessary from 24.

Java 24 is also the largest JEP count in this lab — 24 of them — and it is
where several long ladders reach the end.

## What you actually get

### `synchronized` no longer pins virtual threads — JEP 491

The mechanism, briefly: a virtual thread blocking on a monitor now releases its
carrier, and the JVM tracks the monitor ownership across the unmount. Before,
the monitor's implementation held state on the OS thread stack, which made
unmounting impossible.

**Why this mattered so much in practice:** you can audit *your* code for
`synchronized`. You cannot audit every dependency, and JDBC drivers, connection
pools, logging frameworks and caches are full of it. On 21, adopting virtual
threads meant a real risk that some library deep in the stack would pin a
carrier under load — and the symptom is a throughput ceiling with no obvious
cause. JEP 491 removes that whole class of risk.

**What still pins:** native frames (JNI) and a few internal cases. Far narrower.

### Stream gatherers — **FINAL** — JEP 485

Two previews ([22](java-22.md), [23](java-23.md)), done.

```java
Stream.of(1,2,3,4,5).gather(Gatherers.windowFixed(2)).toList()   // [[1,2],[3,4],[5]]
```
**[measured, JDK 25.0.4]**

`Gatherers.mapConcurrent(limit, fn)` is the one to know: bounded-concurrency
mapping over virtual threads, inside a stream.

### Class-File API — **FINAL** — JEP 484

Two previews, done. `java.lang.classfile` — parse, generate and transform class
files with a JDK-supplied API that is always in sync with the format.

The practical significance is indirect but large: it is the supported
replacement for ASM, and once bytecode libraries move to it, "the new JDK broke
Mockito/JaCoCo/my agent" becomes a much smaller problem. That failure mode is
a direct cost of the six-month cadence, and this is the platform paying it back.

### Ahead-of-Time class loading and linking — JEP 483

Project Leyden's first delivery. A *training run* records which classes your
application loads and how they link; subsequent runs read that cache instead of
redoing the work.

```
$ java -XX:AOTCacheOutput=app.aot -cp . MyApp     # training run
$ java -XX:AOTCache=app.aot     -cp . MyApp       # fast start
```

**[measured, JDK 25.0.4, macOS, 8 runs each]** on a small program: **0.1050 s →
0.0862 s**, an 18% startup improvement, with a 12.6 MB cache. Small in absolute
terms because the baseline already benefits from the default CDS archive
([Java 12](java-12.md)) — of 1003 classes loaded, 956 already came from a
shared archive before AOT was involved. Larger applications have much more to
gain; that is a documented claim, not one this lab measured.

[Java 25](java-25.md) adds command-line ergonomics (JEP 514) and method
profiling (JEP 515) on top.

### Post-quantum cryptography — JEP 496, 497

**ML-KEM** (key encapsulation, JEP 496) and **ML-DSA** (digital signatures,
JEP 497) — the NIST-standardised lattice-based algorithms, in the standard
providers.

Why now: "harvest now, decrypt later." Traffic captured today can be decrypted
by a future quantum computer, so long-lived confidential data needs
quantum-resistant key exchange *before* such a machine exists, not after. Built
on the KEM API from [Java 21](java-21.md) (JEP 452).

### Compact object headers (experimental) — JEP 450

Shrinks the Java object header from 96/128 bits to 64 bits, reducing heap usage
and improving cache locality — typically a few percent of heap on
object-heavy workloads. Experimental here (`-XX:+UnlockExperimentalVMOptions
-XX:+UseCompactObjectHeaders`); a product feature in [25](java-25.md).

**[measured, JDK 25.0.4]** — still `false` by default in 25:
`bool UseCompactObjectHeaders = false {product lp64_product} {default}`. It is
supported, but you must opt in.

This is downstream of biased locking's removal in
[Java 15](java-15.md) — biased locking's state lived in the object header, and
its complexity was what blocked this work.

### Generational Shenandoah (experimental) — JEP 404

Shenandoah's counterpart to generational ZGC. Final in [25](java-25.md).

### Linking runtime images without JMODs — JEP 493

`jlink` can now build a custom runtime image from an installed JDK without
needing the JMOD files, cutting the JDK's own on-disk size by roughly 25%.
Relevant if you build slim container images with `jlink`.

## Pipeline movement this release

| Feature | State here | Ends up |
|---|---|---|
| **`synchronized` unpinning (JEP 491)** | **FINAL** | done |
| **Stream gatherers (JEP 485)** | **FINAL** | done |
| **Class-File API (JEP 484)** | **FINAL** | done |
| AOT class loading (JEP 483) | **FINAL** | Extended in [25](java-25.md) |
| ML-KEM / ML-DSA (JEP 496, 497) | **FINAL** | done |
| Compact object headers (JEP 450) | Experimental | Product feature in [25](java-25.md) |
| Generational Shenandoah (JEP 404) | Experimental | Final in [25](java-25.md) |
| Scoped values (JEP 487) | 4th preview | Final in [25](java-25.md) |
| Structured concurrency (JEP 499) | 4th preview | Still preview in 26 |
| Module imports (JEP 494) | 2nd preview | Final in [25](java-25.md) |
| Flexible constructor bodies (JEP 492) | 3rd preview | Final in [25](java-25.md) |
| Primitive patterns (JEP 488) | 2nd preview | 4th preview in 26 |
| Simple source files (JEP 495) | 4th preview | Final in [25](java-25.md) |
| Vector API (JEP 489) | 9th incubator | 10th in [25](java-25.md) |

## What broke

Java 24 has the heaviest removal list in this lab.

- **Security Manager permanently disabled — JEP 486.** Deprecated for removal
  in [17](java-17.md), disabled here. `System.setSecurityManager` throws.
  [F9](README.md#f9--the-security-manager-is-permanently-disabled-jdk-24-jep-486).
  The replacement is process-level isolation, not another in-JVM sandbox.
- **`sun.misc.Unsafe` memory-access methods now warn at runtime — JEP 498.**
  **[measured, JDK 25.0.4]**:

  ```
  WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
  WARNING: sun.misc.Unsafe::objectFieldOffset has been called by W (file:...)
  WARNING: Please consider reporting this to the maintainers of class W
  WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release
  ```

  The code still runs. The warning names the calling class — read it; it is
  usually a dependency. [F4](README.md#f4--sunmiscunsafe-is-on-its-way-out-jdk-23-24-and-beyond).
- **Non-generational ZGC removed — JEP 490.** `-XX:-ZGenerational` no longer
  works. [F12](README.md#f12--non-generational-zgc-removed-jdk-24-jep-490).
- **Prepare to restrict JNI — JEP 472.** JNI use now warns unless you pass
  `--enable-native-access`. Same shape as JEP 451's agent warning in
  [21](java-21.md): a JEP whose entire content is a warning, filed to start a
  ladder.
- **Windows 32-bit x86 port removed — JEP 479.** 32-bit x86 deprecated for
  removal generally (JEP 501); removed in [25](java-25.md) (JEP 503).

## Verdict for your day job

**Don't target it** — six months, ended September 2025.

But **read the JEP 491 section**, because it changes the advice in the
virtual-threads lab and it changes the risk calculation for adopting virtual
threads on a large existing codebase. Everything else here you get in
[Java 25](java-25.md), which is where you should actually go.
