# Java 10 — `var`, and proof that the train departs on time

**GA:** March 2018 · **LTS:** no · **Class file major version:** 54 ·
**Oracle support:** ended September 2018 **[documented — Oracle roadmap]**

**Previous:** [Java 9](java-09.md) · **Next:** [Java 11](java-11.md) ·
**Back to:** [the story](README.md)

---

## The one thing to remember

**Java 10 exists to prove the train runs.** Six months after Java 9's
three-and-a-half-year saga, a release shipped on the day it said it would, with
a modest, complete, useful feature set. Nothing about Java 10 is dramatic, and
that is the entire point — it is the first release under the new model and it
was boring, on time, and small.

It also formalised the schedule itself: **JEP 322, "Time-Based Release
Versioning"**, is *in* Java 10. The release train is not just a policy, it is a
JEP with a number.

## What you actually get

### Local-variable type inference: `var` — JEP 286

The one feature everybody knows.

```java
var orders = new ArrayList<Order>();                    // ArrayList<Order>
var entry  = map.entrySet().iterator().next();          // Map.Entry<K,V>
for (var e : map.entrySet()) { ... }
try (var in = Files.newInputStream(p)) { ... }
```

**What `var` is:** the compiler infers the declared type from the initialiser.
The variable still has one fixed static type — `var` is not `Object`, and Java
does not become dynamically typed. The bytecode is identical to writing the
type out.

**Where it is allowed:** local variables with an initialiser, the index of an
enhanced `for`, and try-with-resources. Java 11 adds lambda parameters.

**Where it is not allowed, and why each restriction exists:**

| Not allowed | Reason |
|---|---|
| Fields | A field is part of a class's API surface; inference there would make an API depend on an implementation expression. |
| Method parameters and return types | Same reason, more so. |
| `var x;` with no initialiser | Nothing to infer from. |
| `var x = null;` | The only inferrable type would be the null type, which is useless. |
| `var x = () -> {...}` | A lambda has no type of its own — it needs a target type to become one. |
| `var x = new int[]{1,2};` is fine, but `var x = {1,2};` is not | Array initialiser shorthand needs a declared target type. |

**When to use it — the rule that actually holds up:** use `var` when the
initialiser already states the type plainly, and avoid it when it doesn't.

```java
var customer = new Customer();               // good: type is right there
var list = new ArrayList<Order>();           // good
var result = process(input);                 // bad: what is result?
var x = service.find(id).orElseThrow();      // bad: reader has to go look
```

There is a subtle trap worth knowing: `var` infers the **most specific** type,
including types you cannot name.

```java
var x = new Object() { int count = 0; };     // anonymous class type
x.count++;                                    // this compiles! (it would not with Object x)
```

That is legal and occasionally useful, but if it appears in a code review, the
reviewer is right to ask about it.

### `Optional.orElseThrow()` — no argument

```java
opt.get()             // works, but the name says nothing about the failure
opt.orElseThrow()     // identical behaviour, honest name
```

`get()` is not deprecated but `orElseThrow()` is the preferred spelling,
because `get()` reads like a safe accessor and throws
`NoSuchElementException`.

### Unmodifiable collection copies

```java
List.copyOf(someList)                          // immutable snapshot
Collectors.toUnmodifiableList()                // and toUnmodifiableSet/Map
```

`List.copyOf` is a genuine copy — later changes to the source are not visible —
which is what distinguishes it from `Collections.unmodifiableList`, an
*unmodifiable view* that still reflects changes to the underlying list. That
distinction causes real bugs; `copyOf` is usually what you meant.

### Application Class-Data Sharing — JEP 310

Extends the class-data sharing archive to *application* classes, not just JDK
ones. This is the direct ancestor of the AOT cache work in
[24](java-24.md)/[25](java-25.md), and the ancestor of the default CDS archive
in [12](java-12.md) that you have been silently benefiting from ever since.

### Container awareness (JDK 10, backported to 8u191)

The JVM now respects cgroup limits: `Runtime.availableProcessors()` and the
default heap sizing reflect the *container's* CPU and memory limits rather than
the host machine's.

This is easy to skip past and it caused an enormous amount of production pain
before it existed. A JVM in a 1-CPU container on a 64-core host would size its
GC thread pools, fork-join common pool and JIT compiler threads for 64 cores,
then get throttled into the ground by the CFS quota. If you have ever seen a
containerised Java service with mysterious latency spikes on an old JDK, this
is very often the cause.

### Parallel full GC for G1 — JEP 307

G1's full-GC fallback path was single-threaded, so on the rare occasions it
happened, the pause was enormous. Now parallel. Pure win, no action needed.

### Time-based release versioning — JEP 322

The version scheme itself: `$FEATURE.$INTERIM.$UPDATE.$PATCH`. `25.0.4` means
feature release 25, interim 0, update 4. Interim is always 0 in practice.

## Pipeline movement this release

Nothing entered or left the preview pipeline — JEP 12's preview mechanism is
not yet in use. The first preview feature is switch expressions in
[Java 12](java-12.md).

## What broke

Very little, which was the point.

- **`javah` removed** — JEP 313. Use `javac -h` instead. Affects JNI builds.
- The version-string change from [Java 9](java-09.md) continues to catch
  scripts.

## Verdict for your day job

**Don't target it** — six months of support, ended September 2018.

**But `var` is yours forever**, and it is the feature from this era you will use
most often. Everything else here is available in every later release.

The historical point worth keeping: Java 10 is the release that made the new
model credible. After 9, nobody believed the six-month promise. After 10 landed
on its date, people started to.
