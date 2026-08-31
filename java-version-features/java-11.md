# Java 11 — the first modern LTS, and the last one to still be common

**GA:** September 2018 · **LTS:** **yes** · **Class file major version:** 55 ·
**Oracle Premier Support:** ended September 2023 · **Extended:** January 2032
**[documented — Oracle roadmap]**

**Previous:** [Java 10](java-10.md) · **Next:** [Java 12](java-12.md) ·
**Back to:** [the story](README.md)

---

## The one thing to remember

**Java 11 is where the world went after Java 8, and where a lot of it stopped.**
It is the first LTS under the new release train, and for years it was *the*
answer to "which Java should we run." If you inherit a legacy service today, it
is on 8 or on 11.

Its feature set is modest. Its importance is that it is a **support target**,
and — critically — it is the release where the Java EE modules were removed,
which is the concrete reason many 8→11 migrations took weeks.

## What you actually get

### `HttpClient` becomes standard — JEP 321

Incubated in [Java 9](java-09.md) as `jdk.incubator.http`, now
`java.net.http`. **The package rename is the incubator mechanism working
exactly as designed** — every early adopter's imports broke on graduation, on
purpose.

```java
HttpClient client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.example.com/orders"))
        .header("Accept", "application/json")
        .timeout(Duration.ofSeconds(10))
        .GET()
        .build();

// synchronous
HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());

// asynchronous
CompletableFuture<HttpResponse<String>> f =
        client.sendAsync(req, HttpResponse.BodyHandlers.ofString());
```

What it gives you over `HttpURLConnection`: HTTP/2 with multiplexing,
WebSocket, a fluent builder, real timeouts, and an async API returning
`CompletableFuture`. What it does not give you: connection-pool tuning
comparable to Apache HttpClient or OkHttp, or the ergonomics of a declarative
client. For a Spring application `RestClient`/`WebClient` still usually wins;
`HttpClient` is excellent for libraries and small services that don't want a
dependency.

Note for later: HTTP/3 arrives for this client in JDK 26 (JEP 517).

### Single-file source-code programs — JEP 330

```
$ java Hello.java
```

No `javac`, no class file on disk. This is what makes Java viable for
scripting, and it is why every snippet in a modern Java tutorial can be run
directly. [Java 22](java-22.md) extends it to multiple files.

### `String` methods

```java
"  hi  ".strip()          // Unicode-aware trim (trim() only handles ASCII <= U+0020)
"  hi  ".stripLeading()
"  hi  ".stripTrailing()
"".isBlank()              // true; also true for "   " and "\t"
"a\nb\nc".lines()         // Stream<String>, handles \n, \r\n and \r
"ab".repeat(3)            // "ababab"
```

`strip()` vs `trim()` is the one to internalise: `trim()` removes characters
with code point ≤ U+0020, which means it does *not* remove a non-breaking space
(U+00A0) — a very common source of "this string looks empty but isn't" bugs
with data pasted from spreadsheets or web pages. `strip()` uses
`Character.isWhitespace` and handles it.

### `var` in lambda parameters — JEP 323

```java
(var a, var b) -> a + b
(@NonNull var a, @NonNull var b) -> a + b   // the actual reason: annotations
```

The only real motivation is being able to annotate an inferred lambda
parameter. You either need this or you don't.

### Files convenience methods

```java
Files.readString(path);              // whole file to String, UTF-8 by default
Files.writeString(path, content);
```

Note "UTF-8 by default" here is a property of *these* methods, which specified
UTF-8 from the start — unlike the platform default charset, which only becomes
UTF-8 in [Java 18](java-18.md).

### Collection and predicate additions

```java
list.toArray(String[]::new);         // cleaner than new String[0]
Predicate.not(String::isBlank);      // instead of s -> !s.isBlank()
```

### ZGC arrives (experimental) — JEP 333

A concurrent, low-latency collector targeting sub-10ms pauses at multi-terabyte
heaps. **Experimental in 11** — behind `-XX:+UnlockExperimentalVMOptions
-XX:+UseZGC`, and not for production here. It becomes a production feature in
[Java 15](java-15.md).

### Epsilon: a no-op garbage collector — JEP 318

A collector that allocates and never reclaims. When the heap fills, the JVM
exits. Useful for two narrow things: benchmarking allocation cost without GC
noise, and very short-lived batch jobs that will exit before they fill the
heap. Not a production collector for services.

### Flight Recorder is open source — JEP 328

JFR — the JVM's built-in, always-on-capable profiler — was a commercial Oracle
feature. From Java 11 it is in OpenJDK.

This is genuinely one of the most valuable things in this release and it is
routinely overlooked. JFR gives you allocation profiles, lock contention, GC
detail, I/O and exception rates at roughly 1% overhead, recordable in
production:

```
java -XX:StartFlightRecording=duration=60s,filename=app.jfr ...
```

Open the file in JDK Mission Control. If you have a latency problem and you are
guessing, this is what to reach for. It keeps improving through the releases —
event streaming in [14](java-14.md), CPU-time profiling and method timing in
[25](java-25.md).

### TLS 1.3 — JEP 332

Faster handshakes, better security. Mostly transparent, but it can break
brittle middleboxes and old test fixtures that pinned a cipher suite.

## Pipeline movement this release

| Feature | State here | Ends up |
|---|---|---|
| HTTP Client (JEP 321) | **Standard** — graduated from incubator | done |
| ZGC (JEP 333) | Experimental | Production in [15](java-15.md); generational in [21](java-21.md) |

## What broke — and this is the release where it hurts

**This is the migration everyone remembers, and F2 in the README is the reason.**

- **Java EE and CORBA modules removed — JEP 320.** `javax.xml.bind` (JAXB),
  `javax.xml.ws` (JAX-WS), `javax.annotation`, `javax.activation`,
  `javax.transaction`, `java.corba`. Deprecated in 9, gone in 11. In 9 and 10
  you could bridge with `--add-modules java.xml.bind`; in 11 that module does
  not exist, so you must add real dependencies:

  ```xml
  <dependency>
    <groupId>jakarta.xml.bind</groupId><artifactId>jakarta.xml.bind-api</artifactId>
  </dependency>
  <dependency>
    <groupId>org.glassfish.jaxb</groupId><artifactId>jaxb-runtime</artifactId>
  </dependency>
  ```

  Beware the second migration hiding inside this one: current versions use the
  `jakarta.*` package prefix, not `javax.*`. That rename is a **Jakarta EE
  trademark consequence, not a JDK change** — the same rename Spring Boot 3
  required — but it lands on your desk at the same time and the two get
  confused constantly.

- **JavaFX removed from the JDK.** Now a separate project (OpenJFX / Gluon).
- **Java Web Start and the browser plugin removed.** No replacement; the
  successor for desktop distribution is `jpackage` ([Java 16](java-16.md)).
- **Nashorn deprecated** — JEP 335. Removed in [15](java-15.md).
- **Pack200 deprecated** — JEP 336. Removed in [14](java-14.md).

## Verdict for your day job

**A defensible place to be, and a bad place to stay.**

Oracle Premier Support ended in September 2023; extended support runs to 2032,
and other vendors have their own dates. So you are not unsupported — but you
are five releases and seven years behind, and you are missing records, sealed
classes, pattern matching, text blocks and virtual threads.

**If you are on 11 today, the target is [25](java-25.md), and the route is
through [17](java-17.md).** Do the 11→17 hop first as its own piece of work,
because that hop is where strong encapsulation lands
([F3](README.md#f3--inaccessibleobjectexception-strong-encapsulation-jdk-1617))
and it is the genuinely risky part. 17→21→25 afterwards is comparatively
gentle.
