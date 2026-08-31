# Java 21 — virtual threads and pattern matching go final

**GA:** September 2023 · **LTS:** **yes** · **Class file major version:** 65 ·
**Oracle Premier Support:** September 2028 · **Extended:** September 2031
**[documented — Oracle roadmap]**

**Previous:** [Java 20](java-20.md) · **Next:** [Java 22](java-22.md) ·
**Back to:** [the story](README.md)

---

## The one thing to remember

**Java 21 is the biggest LTS since 8, and for two independent reasons.**

**Virtual threads (JEP 444)** change how you write a concurrent server: a
blocking, thread-per-request style now scales to hundreds of thousands of
concurrent requests, which means the reactive/callback style stops being the
only option for high concurrency.

**Pattern matching for `switch` (JEP 441)** completes Project Amber's
three-part design. Records ([16](java-16.md)) + sealed types
([17](java-17.md)) + patterns (21) together make Java's data modelling
genuinely different from Java 8's.

It is also the release where the LTS cadence changed: 17→21 is **two** years,
not three.

## What you actually get

### Virtual threads — **FINAL** — JEP 444

Three previews ([19](java-19.md), [20](java-20.md)), done.

```java
try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
    for (var task : tasks) exec.submit(() -> handle(task));
}
```

The [java-virtual-threads](../java-virtual-threads/) lab has the mechanism.
Three things this lab adds:

1. **In Spring Boot 3.2+, one property enables it** for the web server and
   `@Async`: `spring.threads.virtual.enabled=true`. Combined with a JDK 21+
   runtime, that is the whole adoption cost for a typical MVC service.
2. **Do not pool virtual threads.** A pool exists to reuse an expensive
   resource; a virtual thread is not expensive. Pooling them reintroduces the
   ceiling you removed. Use `newVirtualThreadPerTaskExecutor`.
3. **The `synchronized` pinning caveat expired.** On 21, a `synchronized` block
   around a blocking call pins the virtual thread to its carrier and can starve
   the scheduler; the advice was to use `ReentrantLock`. **JEP 491 in
   [Java 24](java-24.md) removed that limitation.** If you are on 21 the advice
   still applies; on 24+ it does not.

### Pattern matching for `switch` — **FINAL** — JEP 441

Four previews ([17](java-17.md), [18](java-18.md), [19](java-19.md),
[20](java-20.md)) — the longest preview run to reach final in this lab. Done.

```java
String describe(PaymentResult r) {
    return switch (r) {
        case Approved(String code)          -> "authorised: " + code;
        case Declined(String reason)        -> "declined: " + reason;
        case PendingReview(Duration in)     -> "pending, expect " + in;
    };                                        // exhaustive: no default needed
}
```

Four capabilities, each worth naming:

**Type patterns** — `case Approved a ->` replaces an `if/else instanceof` chain.

**Record deconstruction** (JEP 440, also final here) — `case Approved(String
code) ->` binds the component directly, and nests arbitrarily deep.

**Guards with `when`** — a refinement on a matched pattern:

```java
case Declined d when d.reason().equals("INSUFFICIENT_FUNDS") -> retryLater();
case Declined d                                              -> giveUp();
```

**Exhaustiveness over sealed types** — no `default` needed, and adding a new
permitted subtype breaks compilation of every switch that doesn't handle it.
This is the payoff described in [Java 15](java-15.md), now finally usable.

Two rules that catch people:

- **`null` no longer throws by default.** A pattern `switch` with no `case
  null` throws `NullPointerException` on null, same as before — but you can now
  write `case null ->` to handle it, or `case null, default ->` to fold it into
  the default. This makes null handling explicit rather than accidental.
- **Case order matters.** A more general pattern before a more specific one is
  a compile error (dominance checking), so you cannot accidentally shadow a
  case.

### Sequenced collections — JEP 431

Java's collections had no common way to ask for "the first element". `List` had
`get(0)`, `Deque` had `getFirst()`, `SortedSet` had `first()`, and
`LinkedHashSet` had *nothing* — you iterated.

Now there is `SequencedCollection`, `SequencedSet`, `SequencedMap`:

```java
list.getFirst();  list.getLast();
list.addFirst(x); list.addLast(x);
list.reversed();                      // a reversed *view*, not a copy
linkedHashMap.firstEntry();  linkedHashMap.pollFirstEntry();
```

**[measured, JDK 25.0.4]** — `new ArrayList<>(List.of(1,2,3))` gives
`getFirst()` → `1`, `getLast()` → `3`, `reversed()` → `[3, 2, 1]`.

`reversed()` returning a *view* is the detail to remember: it is O(1), and
mutations through it write through to the original.

One compatibility note: `List` gained `getFirst`/`addFirst`/etc. as default
methods, which can clash with an existing method of the same name in your own
`List` implementation. Rare, but it is a real source of 17→21 compile errors.

### Generational ZGC — JEP 439

ZGC gains generations (young/old), so short-lived objects are collected far
more cheaply. Opt-in here with `-XX:+UseZGC -XX:+ZGenerational`; the default in
[23](java-23.md); the only mode from [24](java-24.md).

### Key Encapsulation Mechanism API — JEP 452

A standard API for KEMs. The groundwork for the post-quantum algorithms that
arrive in [Java 24](java-24.md) (ML-KEM, ML-DSA).

### String templates — JEP 430 (**preview**) — the one that was withdrawn

```java
String s = STR."Order \{id} total \{total}";
```

Previewed here, second preview in [22](java-22.md), then **removed from the
JDK entirely** during [23](java-23.md) development, with no replacement.

**This is the load-bearing event of the whole lab.** See the README's
[withdrawal section](README.md#the-case-that-justifies-the-whole-design--string-templates-withdrawn).
Preview is only meaningful if withdrawal actually happens, and it did. Anyone
who used `STR."..."` on 21 or 22 found that code uncompilable on 23 — exactly
what the preview contract said might happen.

### Unnamed classes and instance main methods — JEP 445 (**preview**)

The beginner-friendly entry point, aimed at making a first Java program not
require `public static void main(String[] args)` inside a class. Renamed twice
on the way — final as "Compact Source Files and Instance Main Methods" in
[Java 25](java-25.md).

### Scoped values — JEP 446 (**preview**), structured concurrency — JEP 453 (**preview**)

Both graduate from incubator to preview here, moving into
`java.util.concurrent`. See [scoped-values](../scoped-values/) and
[structured-concurrency](../structured-concurrency/).

## Pipeline movement this release

| Feature | State here | Ends up |
|---|---|---|
| **Virtual threads (JEP 444)** | **FINAL** | done |
| **Pattern matching for `switch` (JEP 441)** | **FINAL** (5 releases) | done |
| **Record patterns (JEP 440)** | **FINAL** | done |
| Sequenced collections (JEP 431) | **FINAL** (no preview needed) | done |
| Generational ZGC (JEP 439) | Opt-in | Default in [23](java-23.md), only mode in [24](java-24.md) |
| **String templates (JEP 430)** | 1st preview | **WITHDRAWN in [23](java-23.md)** |
| Scoped values (JEP 446) | 1st preview | Final in [25](java-25.md) |
| Structured concurrency (JEP 453) | 1st preview | Still preview in 26 |
| Unnamed classes (JEP 445) | 1st preview | Final in [25](java-25.md) |
| Unnamed patterns/variables (JEP 443) | 1st preview | Final in [22](java-22.md) |
| FFM (JEP 442) | 3rd preview | Final in [22](java-22.md) |
| Vector API (JEP 448) | 6th incubator | Still incubating in [25](java-25.md) |

## What broke

- **Dynamic agent loading now warns — JEP 451.** Attaching an agent to a
  *running* JVM — profilers, APM agents, some mocking setups — prints a
  warning, and a future release will require `-XX:+EnableDynamicAgentLoading`.
  This is [F11](README.md#f11--dynamic-agent-loading-now-warns-jdk-21-jep-451).
  Agents passed at startup with `-javaagent:` are unaffected.
- **Windows 32-bit x86 port deprecated for removal — JEP 449.** Removed in
  [24](java-24.md).
- **Sequenced collections default methods** can clash with your own `List`
  implementations, as noted above.
- Compared with 11→17, this is a very light list. **17→21 is a gentle jump.**

## Verdict for your day job

**An excellent place to be, and the minimum you should accept for new work.**

Supported to September 2028 (Oracle premier). Spring Boot 3.2+ has first-class
virtual thread support. Everything modern about Java is final here except
scoped values, structured concurrency and the FFM API.

**If you are on 17:** 21 is a small, low-risk jump with a very large payoff.
Do it.

**If you are choosing between 21 and [25](java-25.md) for a new service:**
choose 25. It is supported two years longer, and it fixes the
`synchronized` pinning caveat that complicates virtual thread adoption on 21.
The 21→25 delta is small.
