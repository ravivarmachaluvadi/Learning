# Java 9 — the release that broke the world, and then fixed how releases work

**GA:** September 2017 · **LTS:** no (superseded by 10 in six months) ·
**Class file major version:** 53 ·
**Oracle support:** ended March 2018 **[documented — Oracle roadmap]**

**Previous:** Java 8 (outside this lab) · **Next:** [Java 10](java-10.md) ·
**Back to:** [the story](README.md)

---

## The one thing to remember

**Java 9 is the reason everything after it looks the way it does.** It arrived
three and a half years late, carrying a change so large it broke a significant
fraction of the ecosystem, and the lesson learned from it — *never ship a
release this big again* — produced the six-month train, preview features, and
the LTS designation. If you only read one version file, the value of this one
is the story, not the feature list.

It also contains, quietly, several things you use daily without knowing they
arrived here: `List.of()`, `var`'s groundwork, the HTTP client, and G1 as the
default garbage collector.

## What you actually get

Ordered by how likely a backend engineer is to touch it.

### Collection factory methods — JEP 269

The single most-used thing in Java 9. Before:

```java
List<String> l = Collections.unmodifiableList(Arrays.asList("a", "b", "c"));
Map<String,Integer> m = new HashMap<>();
m.put("a", 1); m.put("b", 2);
m = Collections.unmodifiableMap(m);
```

After:

```java
List<String>       l = List.of("a", "b", "c");
Set<String>        s = Set.of("a", "b");
Map<String,Integer> m = Map.of("a", 1, "b", 2);
```

Three things to know, because all three surprise people:

1. The result is **immutable** — `add` throws `UnsupportedOperationException`.
   That is a feature, not a limitation, but it means `List.of(...)` is not a
   drop-in replacement for `new ArrayList<>(...)`.
2. **Nulls are rejected.** `List.of("a", null)` throws `NullPointerException`.
   `Arrays.asList("a", null)` does not. This bites when converting old code.
3. **Iteration order of `Set.of` and `Map.of` is unspecified and deliberately
   randomised per JVM run.** If a test asserts on the order of a `Set.of`, it
   will pass locally and fail in CI, eventually. This is intentional — it stops
   you depending on an order that was never promised.

`Map.of` takes at most 10 key-value pairs; beyond that use `Map.ofEntries(
Map.entry(k, v), ...)`.

### The module system — JEP 261, 200, 220, 260

The headline, the delay, and the pain. The full treatment is a lab of its own
(`/learn java-module-system`); here is what you need to make a decision.

A module declares what it needs and what it shares:

```java
// module-info.java
module com.acme.orders {
    requires java.net.http;              // I need this
    requires transitive com.acme.model;  // and anyone who needs me needs this too
    exports com.acme.orders.api;         // others may compile against this
    // everything else is invisible to the outside world
}
```

**The decision you actually have to make:** you almost certainly should not
write one of these. Classpath code lands in the **unnamed module**, which reads
everything and tolerates split packages, and this is fully supported forever —
not a deprecated compatibility mode. The overwhelming majority of production
Java applications, including Spring Boot ones, run on the classpath on JDK 25
with no `module-info.java` anywhere.

**Where modules genuinely earn their keep:** building a stripped-down runtime
image with `jlink` (smaller containers), and publishing a library where you
want a compiler-enforced public surface.

**What you inherit whether you opt in or not:** the JDK itself is modular, and
that is what produced [F1 and F3 in the README](README.md#11-failure-scenarios--what-actually-breaks-and-why)
— split packages and `InaccessibleObjectException`. You do not escape the
consequences by staying on the classpath; you only escape the authoring work.

### `jshell` — JEP 222

A REPL. `jshell` at a terminal, type an expression, get a result. Genuinely
useful for checking what an API does without creating a project — and for a
no-code lab like this one, it is the right way to poke at anything you read
here.

```
jshell> List.of(1,2,3).stream().map(i -> i*2).toList()
$1 ==> [2, 4, 6]
```

### The HTTP client — JEP 110 (incubator here; standard in 11)

An HTTP/2-capable client to replace `HttpURLConnection`. **In Java 9 it is an
incubator module** (`jdk.incubator.http`) — a textbook instance of the
incubator mechanism from the README, including the package rename on
graduation. See [Java 11](java-11.md) for the finished version, which is the
one to use.

### Private interface methods — part of JEP 213 ("Milling Project Coin")

Java 8 gave interfaces `default` methods. Java 9 lets those share private
helpers:

```java
interface Validator {
    default boolean isValidEmail(String s) { return matches(s, EMAIL); }
    default boolean isValidPhone(String s) { return matches(s, PHONE); }
    private boolean matches(String s, Pattern p) {   // new in 9
        return s != null && p.matcher(s).matches();
    }
}
```

JEP 213 also allows `try` with an effectively-final resource variable, `@SafeVarargs`
on private instance methods, and diamond with anonymous classes.

### Stream and Optional additions

Small but frequently reached for:

```java
Stream.of(1,2,3,4,5).takeWhile(i -> i < 3)   // [1, 2]
Stream.of(1,2,3,4,5).dropWhile(i -> i < 3)   // [3, 4, 5]
Stream.iterate(1, i -> i < 100, i -> i * 2)  // 3-arg form: has a termination test
Stream.ofNullable(maybeNull)                 // 0 or 1 elements

opt.ifPresentOrElse(this::use, this::fallback);
opt.or(() -> Optional.of(fallback));
opt.stream()                                 // flat-map Optionals cleanly
```

### `VarHandle` — JEP 193

The supported replacement for `sun.misc.Unsafe`'s field operations: typed,
checked, atomic access to fields and array elements, with explicit memory
ordering modes (`getVolatile`, `compareAndSet`, `getAcquire`, `setRelease`).

This matters for two reasons beyond its direct use. It is what made
[deprecating `Unsafe` possible](README.md#f4--sunmiscunsafe-is-on-its-way-out-jdk-23-24-and-beyond)
— you cannot remove a thing with no replacement. And its ordering modes are the
API-level expression of the happens-before rules from the
[java-memory-model](../java-memory-model/) lab; if that lab's `volatile`
discussion made sense, `VarHandle`'s access modes are the same ideas with
finer granularity.

### `ProcessHandle` — JEP 102

Finally, a way to ask about a process without shelling out:

```java
ProcessHandle.current().pid();
ProcessHandle.current().info().commandLine();
ProcessHandle.of(pid).ifPresent(ProcessHandle::destroy);
proc.onExit().thenAccept(p -> log("exited " + p.exitValue()));  // CompletableFuture
```

### Multi-release JARs — JEP 238

One jar with version-specific class files under `META-INF/versions/N/`. The JVM
picks the highest version it supports. This is how libraries offer a Java 9+
optimised implementation while still running on 8 — and if you have ever
wondered why a dependency's jar has that directory, this is it.

### G1 becomes the default garbage collector — JEP 248

Replacing Parallel GC. A change in default behaviour with no code change on
your side: G1 trades some throughput for much shorter and more predictable
pauses. Worth knowing if you have benchmarks that straddle Java 8 and 9.

### The rest, briefly

Unified JVM logging (`-Xlog`, JEP 158/271 — one consistent logging framework
for the whole VM, and the origin of every `-Xlog:gc`, `-Xlog:class+load`
command in this lab), compact strings (JEP 254 — `String` stores Latin-1 in a
`byte[]` instead of always `char[]`, typically a large heap saving, entirely
invisible), the stack-walking API (JEP 259), a platform logging SPI (JEP 264),
reserved stack areas (JEP 270), and `Indify` string concatenation (JEP 280 —
`+` on strings now compiles to an `invokedynamic` that the JVM can optimise,
rather than fixed `StringBuilder` calls).

## Pipeline movement this release

Java 9 predates the preview mechanism (JEP 12 arrives with the release train).
It has incubators only:

| Feature | State here | Ends up |
|---|---|---|
| HTTP Client (JEP 110) | Incubator (`jdk.incubator.http`) | Standard in [11](java-11.md) (JEP 321) |

## What broke

The list that made 8→9 famous. All of these are expanded in the README's
[failure scenarios](README.md#11-failure-scenarios--what-actually-breaks-and-why).

- **Deep reflection into JDK internals now warns** — the first rung of the
  five-release ladder that ends in `InaccessibleObjectException` at
  [Java 16](java-16.md)/[17](java-17.md). In Java 9 it is *permitted*, with a
  one-time warning per package, and `--illegal-access=permit` is the default.
- **`javax.xml.bind` and friends deprecated for removal** — the groundwork for
  their removal in [Java 11](java-11.md). `--add-modules java.xml.bind` is the
  temporary bridge here.
- **Split packages become fatal on the module path.**
- **The version string changed** — JEP 223. `1.8.0_281` became `9.0.4`. Every
  script that parsed `java -version` with a regex expecting `1.x` broke. If you
  have build scripts older than 2017, check them.
- **Underscore is no longer a legal identifier.** `int _ = 5;` was a warning in
  8, an error in 9. (It later comes back with a *different* meaning as the
  unnamed variable in [Java 22](java-22.md).)
- **Internal APIs encapsulated** — JEP 260. `sun.misc.BASE64Encoder` and
  similar simply vanish. Use `java.util.Base64` (which arrived in Java 8).
- **Removed:** the `hprof` agent (JEP 240), `jhat` (JEP 241), the GC
  combinations deprecated in 8 (JEP 214), launch-time JRE version selection
  (JEP 231), demos and samples (JEP 298).
- **Deprecated:** the Applet API (JEP 289 — finally removed in JDK 26),
  the CMS collector (JEP 291 — removed in [14](java-14.md)).

## Verdict for your day job

**Do not target Java 9.** It received six months of updates and ended in March
2018. Its value to you is entirely historical *plus* the APIs it introduced,
all of which you get in any later release.

Read this file to understand where `List.of`, `VarHandle` and the module
system's consequences came from, then go to [Java 11](java-11.md) or, better,
[Java 17](java-17.md) for the first target worth having.
