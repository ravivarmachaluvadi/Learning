# Java 22 — the FFM API lands after eight years

**GA:** March 2024 · **LTS:** no · **Class file major version:** 66 ·
**Oracle support:** ended September 2024 **[documented — Oracle roadmap]**

**Previous:** [Java 21](java-21.md) · **Next:** [Java 23](java-23.md) ·
**Back to:** [the story](README.md)

---

## The one thing to remember

**Java 22 finalises the Foreign Function & Memory API — the end of the longest
ladder in this lab.** Eight years, two separate incubating APIs that merged,
six incubations and three previews. It is the supported replacement for JNI and
for `sun.misc.Unsafe`'s off-heap memory access, and its arrival is the reason
[Java 23](java-23.md) could start deprecating `Unsafe` at all.

You cannot remove a thing until a replacement exists. FFM is that replacement.

## What you actually get

### Foreign Function & Memory API — **FINAL** — JEP 454

Call native code and access off-heap memory, safely, without JNI.

```java
// Off-heap memory with a bounded lifetime
try (Arena arena = Arena.ofConfined()) {
    MemorySegment seg = arena.allocate(1024);
    seg.set(ValueLayout.JAVA_INT, 0, 42);
    int v = seg.get(ValueLayout.JAVA_INT, 0);
}   // memory is freed here, deterministically

// Calling a C function: strlen(const char*)
Linker linker = Linker.nativeLinker();
MethodHandle strlen = linker.downcallHandle(
        linker.defaultLookup().find("strlen").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

try (Arena arena = Arena.ofConfined()) {
    MemorySegment s = arena.allocateFrom("hello");
    long len = (long) strlen.invoke(s);      // 5
}
```

**What it improves on JNI:** no C glue code, no separate build step, no
`System.loadLibrary` dance. The `jextract` tool generates Java bindings from a C
header.

**What it improves on `Unsafe`:** bounds checking, and — the important part —
**lifetime checking**. An `Arena` owns its segments; touching a segment after
its arena closes throws `IllegalStateException` instead of corrupting memory or
crashing the VM. `Arena.ofConfined()` also enforces single-thread access.

That safety is the whole argument. `Unsafe.putInt` with a wrong offset silently
corrupts your heap; `MemorySegment.set` with a wrong offset throws.

**Where this shows up for you even if you never write FFM code:** the libraries
you depend on will move to it, which is what makes their `Unsafe` warnings
([F4](README.md#f4--sunmiscunsafe-is-on-its-way-out-jdk-23-24-and-beyond))
go away. Netty, Lucene, Arrow and the JDBC drivers for native protocols are the
obvious candidates.

**One operational note:** native access from a module requires
`--enable-native-access=<module>` (or `ALL-UNNAMED`), and JDK 24's JEP 472
starts restricting JNI the same way. Expect this flag to become required rather
than advisory.

### Unnamed variables and patterns — **FINAL** — JEP 456

`_` for "I must declare something here but I don't use it":

```java
try { ... } catch (Exception _) { log("failed"); }

for (Order _ : orders) count++;

if (o instanceof Point(int x, _)) { useX(x); }     // don't care about y

map.forEach((_, v) -> total += v);
```

Small, but it removes a genuine category of noise, and in nested record
patterns it is the difference between readable and not. Note that `_` was
*removed* as a legal identifier in [Java 9](java-09.md) precisely so it could
be reused for this — a thirteen-year setup.

### Stream gatherers — JEP 461 (**preview**)

Custom intermediate stream operations. `Stream` has always had a pluggable
*terminal* operation (`Collector`) but a fixed set of intermediate ones —
`map`, `filter`, `flatMap`. Gatherers make the intermediate stage pluggable
too.

```java
Stream.of(1,2,3,4,5).gather(Gatherers.windowFixed(2)).toList()
// [[1, 2], [3, 4], [5]]
```
**[measured, JDK 25.0.4 — gatherers are final from [24](java-24.md)]**

Built-ins: `windowFixed`, `windowSliding`, `fold`, `scan`, `mapConcurrent`.
`mapConcurrent` is the interesting one — it runs the mapper on virtual threads
with a concurrency limit, so `stream.gather(Gatherers.mapConcurrent(10, this::fetch))`
gives you bounded parallel I/O in a stream without a `ForkJoinPool`.

Final in [Java 24](java-24.md).

### Class-File API — JEP 457 (**preview**)

A standard API for reading and writing class files, replacing the need for ASM.

The motivation is squarely a release-train problem: the class file format
changes **every six months**, and every library that manipulates bytecode (ASM,
ByteBuddy, and therefore Mockito, Spring, Hibernate, JaCoCo) has to be updated
before it can run on a new JDK. That is why "the new JDK broke my build" is so
often a *test library* problem, and it is the mechanism behind that. A
JDK-supplied API that is always in sync removes the lag.

Final in [Java 24](java-24.md).

### Statements before `super(...)` — JEP 447 (**preview**)

Lets a constructor run validation before delegating:

```java
public Order(List<Line> lines) {
    if (lines.isEmpty()) throw new IllegalArgumentException("empty order");
    super(computeTotal(lines));      // now legal before this JEP it was not
}
```

Previously you had to hide the check in a static helper inside the `super`
call. Renamed "Flexible Constructor Bodies" from [23](java-23.md); final in
[25](java-25.md).

### Multi-file source programs — JEP 458

[Java 11](java-11.md)'s `java Hello.java` extended to programs spanning
several files, compiled in memory. Java is now genuinely usable for scripts of
non-trivial size.

### Region pinning for G1 — JEP 423

G1 can now pin individual regions during a JNI critical section instead of
disabling GC entirely. Removes a long-standing latency cliff for
JNI-heavy applications.

## Pipeline movement this release

| Feature | State here | Ends up |
|---|---|---|
| **FFM API (JEP 454)** | **FINAL** (8 years) | done |
| Unnamed variables & patterns (JEP 456) | **FINAL** | done |
| Stream gatherers (JEP 461) | 1st preview | Final in [24](java-24.md) |
| Class-File API (JEP 457) | 1st preview | Final in [24](java-24.md) |
| Statements before `super` (JEP 447) | 1st preview | Final in [25](java-25.md) |
| **String templates (JEP 459)** | 2nd preview | **WITHDRAWN in [23](java-23.md)** |
| Scoped values (JEP 464) | 2nd preview | Final in [25](java-25.md) |
| Structured concurrency (JEP 462) | 2nd preview | Still preview in 26 |
| Implicit classes (JEP 463) | 2nd preview | Final in [25](java-25.md) |
| Vector API (JEP 460) | 7th incubator | Still incubating in [25](java-25.md) |

## What broke

Nothing significant.

## Verdict for your day job

**Don't target it.** Six months, ended September 2024.

Read the FFM section, though — it is the concrete answer to "what replaces
`Unsafe`", and if a dependency of yours is emitting `Unsafe` warnings on JDK
24+, FFM is what its maintainers are migrating to. Also read the Class-File API
section, because it explains why upgrading the JDK so often breaks your *test*
tooling before it breaks your code.
