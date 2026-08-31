# Java 16 — records and pattern matching go final; the encapsulation door closes

**GA:** March 2021 · **LTS:** no · **Class file major version:** 60 ·
**Oracle support:** ended September 2021 **[documented — Oracle roadmap]**

**Previous:** [Java 15](java-15.md) · **Next:** [Java 17](java-17.md) ·
**Back to:** [the story](README.md)

---

## The one thing to remember

**Java 16 is where strong encapsulation stops being a warning and starts being
an error.** JEP 396 flips the default from `--illegal-access=permit` to
`deny` — five releases and three and a half years after
[Java 9](java-09.md) first warned about it. This is the change that made
8→17 migrations hard, and it lands *here*, not in 17. Java 17 only removes the
escape hatch.

It is also the release where records and pattern matching for `instanceof` go
final, so the modern Java data-modelling style becomes usable without flags.

## What you actually get

### Records — **final** — JEP 395

Two previews ([14](java-14.md), [15](java-15.md)), done. From here you can
write `record Point(int x, int y) { }` with no flag, and every JDK from 16
onward supports it.

### Pattern matching for `instanceof` — **final** — JEP 394

Also done. `if (o instanceof String s && s.length() > 5)` needs no flag.

Together these two mean **Java 16 is the earliest release where modern Java
"looks modern" without `--enable-preview`.** In practice nobody targets 16 —
but this is why [Java 17](java-17.md) felt like such a large jump from 11: most
of the visible change actually happened in 14, 15 and 16, and 17 is where it
became available on a supported LTS.

### Strong encapsulation by default — JEP 396

The default for `--illegal-access` moves from `permit` to `deny`.

**What this means concretely:** code that reflectively accesses JDK internals —
`setAccessible(true)` on a private field of a `java.*` class — now throws
`InaccessibleObjectException` instead of printing a warning and working.

**The escape hatch still exists here:** `--illegal-access=permit` restores the
old behaviour. [Java 17](java-17.md) removes the option entirely, leaving only
per-package `--add-opens`. So 16 is a warning shot with a way out; 17 is not.

Full treatment, with the five-release ladder and measured JDK 25 behaviour, in
[F3](README.md#f3--inaccessibleobjectexception-strong-encapsulation-jdk-1617).

### Unix-domain socket channels — JEP 380

`SocketChannel` and `ServerSocketChannel` can now use `AF_UNIX`. Relevant for
talking to the Docker daemon socket, to a local PostgreSQL over a socket file,
or to a sidecar — lower overhead and better access control than TCP on
loopback.

```java
var addr = UnixDomainSocketAddress.of("/var/run/docker.sock");
try (var ch = SocketChannel.open(addr)) { ... }
```

### `Stream.toList()`

Small, but you will use it constantly:

```java
list.stream().filter(...).collect(Collectors.toList())   // before
list.stream().filter(...).toList()                       // now
```

**One difference that matters:** `toList()` returns an **unmodifiable** list;
`Collectors.toList()` returns a mutable `ArrayList`. Swapping one for the other
mechanically will produce `UnsupportedOperationException` somewhere if anything
downstream mutates. `toList()` also permits nulls, unlike
`Collectors.toUnmodifiableList()`.

`Stream.mapMulti` also arrives — a one-to-many mapping that avoids allocating an
intermediate stream per element, for when `flatMap` shows up in a profile.

### `jpackage` — **final** — JEP 392

Native installers: `.msi`/`.exe` on Windows, `.dmg`/`.pkg` on macOS,
`.deb`/`.rpm` on Linux, each bundling a runtime image built with `jlink`.

### Vector API — JEP 338 (**first incubator**)

SIMD operations expressed in Java, compiled to hardware vector instructions
(AVX, NEON) when available.

**This is the entry in the README's dataset worth remembering**: it is still
incubating in [Java 25](java-25.md) as JEP 508 — its *tenth* incubation — and
in JDK 26 as JEP 529, its eleventh. Not because it is going badly, but because
it is waiting on Project Valhalla's value types. See the README's
[pathological case](README.md#the-pathological-case--the-vector-api-ten-incubations-and-counting).

### Elastic metaspace — JEP 387

Class metadata memory returns to the OS more promptly. Matters for
applications that load and unload many classes — app servers, and anything
using a lot of runtime code generation.

### Alpine Linux / musl port — JEP 386

Official support for musl-based Linux, which is what makes small Alpine
container images viable for Java.

### Windows/AArch64 port — JEP 388

### OpenJDK moved to Git and GitHub — JEP 357, 369

From Mercurial to Git, and to `github.com/openjdk/jdk`. Practical consequence:
you can read the actual implementation of any JDK class on GitHub in ten
seconds, and JDK bug reports link to real commits. Do use this — reading
`java.base` source is one of the fastest ways to settle an argument about
behaviour.

## Pipeline movement this release

| Feature | State here | Ends up |
|---|---|---|
| Records (JEP 395) | **FINAL** | done |
| Pattern matching for `instanceof` (JEP 394) | **FINAL** | done |
| `jpackage` (JEP 392) | **FINAL** | done |
| Sealed classes (JEP 397) | 2nd preview | Final in [17](java-17.md) |
| **Vector API (JEP 338)** | 1st incubator | Still incubating in [25](java-25.md) |
| Foreign Linker API (JEP 389) | 1st incubator | Merges into FFM in [17](java-17.md) |
| Foreign-Memory Access (JEP 393) | 3rd incubator | FFM final in [22](java-22.md) |

## What broke

- **Strong encapsulation by default — JEP 396.** The big one. See above.
- **`@Warning` on value-based classes — JEP 390.** Synchronising on a boxed
  primitive (`synchronized (someInteger)`) or on `Optional` now warns, because
  those are candidates to become value types under Valhalla and would lose
  identity. If your code does this, it is already a bug.

## Verdict for your day job

**Don't target it** — six months, ended September 2021.

But **read the encapsulation section**, because if your 11→17 migration is
painful, this release is where the pain actually originates. Understanding that
16 flipped the default and 17 removed the switch tells you exactly what to test
and in what order.
