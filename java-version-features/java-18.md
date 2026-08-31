# Java 18 — UTF-8 everywhere, silently

**GA:** March 2022 · **LTS:** no · **Class file major version:** 62 ·
**Oracle support:** ended September 2022 **[documented — Oracle roadmap]**

**Previous:** [Java 17](java-17.md) · **Next:** [Java 19](java-19.md) ·
**Back to:** [the story](README.md)

---

## The one thing to remember

**Java 18 makes UTF-8 the default charset on every platform — and it does so
silently.** No exception, no warning, no compile error. Text that used to
decode one way now decodes another way, on platforms where the OS default
wasn't UTF-8.

That makes it the cleanest example in this lab of the failure class that
actually reaches customers. Every other hazard in the README throws something;
this one changes your output and says nothing. It is
[F6](README.md#f6--utf-8-became-the-default-charset-jdk-18-jep-400).

## What you actually get

### UTF-8 by default — JEP 400

Before Java 18, the *default charset* — used by `new FileReader(f)`,
`new String(bytes)`, `new PrintWriter(f)`, `Files.readAllLines(p)` and many
others when you don't pass one — came from the operating system locale.

That meant: UTF-8 on most Linux, `windows-1252` on a US/European Windows,
`Shift_JIS` on a Japanese Windows, `MS950` on a Traditional Chinese one. The
same program, the same file, different results.

From Java 18 the default is **UTF-8 everywhere**, regardless of locale.

**Why this is right:** it removes an entire class of "works on CI, fails on a
developer laptop" bugs, and it matches what every other modern platform does.

**Why it is dangerous anyway:** if a Windows service has been writing files
with the platform default for a decade, those files are in `windows-1252`. On
JDK 18+ they will be *read* as UTF-8. Bytes that were legal `windows-1252`
become replacement characters or mojibake. The application does not fail; it
produces wrong text.

**The bridge:** `-Dfile.encoding=COMPAT` restores the JDK 17 behaviour.
Treat it as temporary.

**The real fix, which was always the right advice:** never rely on the default
charset.

```java
new String(bytes)                                  // depends on the default
new String(bytes, StandardCharsets.UTF_8)          // says what it means

Files.readAllLines(p)                              // UTF-8 since Java 8 for this one
new FileReader(f)                                  // default charset
new FileReader(f, StandardCharsets.UTF_8)          // explicit, added in Java 11
```

Note the inconsistency that trips people up: `Files.readString`,
`Files.readAllLines` and `Files.newBufferedReader` specified UTF-8 from the
start. `FileReader`, `FileWriter`, `PrintWriter(File)`, `new String(byte[])`
and `String.getBytes()` use the default. That is why JEP 400 changes behaviour
for some of your code and not other parts.

`System.out` is a separate story: its encoding is `stdout.encoding`, which
follows the *console*, not `file.encoding` — so console output on Windows can
still differ from file output.

### Simple web server — JEP 408

```
$ jwebserver -p 8000 -d /path/to/files
```

A static file server, and a `com.sun.net.httpserver` API to embed one. For
testing and local development only — explicitly not a production server. Useful
for serving a directory of test fixtures, or for opening this lab's
`visualization.html` if your browser blocks local file access.

### Code snippets in Javadoc — JEP 413

`{@snippet}` — Javadoc code examples that are actually compiled and validated,
rather than comments that rot.

### Internet-address resolution SPI — JEP 418

A plug point for host name resolution, replacing the built-in resolver. The
motivation is virtual threads: the JDK's built-in resolver uses a native call
that would pin a virtual thread. This SPI is what lets a non-pinning resolver
be plugged in — another unglamorous prerequisite, like the socket rewrites in
[13](java-13.md) and [15](java-15.md).

### Core reflection reimplemented with method handles — JEP 416

An internal rewrite: `java.lang.reflect` now sits on `MethodHandle`
infrastructure instead of generating bytecode stubs. Roughly three thousand
lines of native code deleted. Performance characteristics of reflection shift
slightly — worth knowing if you have a reflection-heavy benchmark that
straddles this release.

## Pipeline movement this release

| Feature | State here | Ends up |
|---|---|---|
| Pattern matching for `switch` (JEP 420) | 2nd preview | Final in [21](java-21.md) |
| FFM (JEP 419) | 2nd incubator | Final in [22](java-22.md) |
| Vector API (JEP 417) | 3rd incubator | Still incubating in [25](java-25.md) |

## What broke

- **UTF-8 default — JEP 400.** Discussed above. Silent.
- **Finalization deprecated for removal — JEP 421.** `Object.finalize()` — a
  1995 mistake that has been discouraged since roughly 1997 — is now terminally
  deprecated. You can test life without it using `--finalization=disabled`.

  Finalizers are unpredictable (no guarantee they ever run), can resurrect
  objects, and slow down GC. The replacements are try-with-resources for
  deterministic cleanup and `java.lang.ref.Cleaner` for a safety net. If you
  find `finalize()` in a codebase, it is almost always both unnecessary and
  actively harmful.

## Verdict for your day job

**Don't target it.** Six months, ended September 2022.

But **JEP 400 will reach you** whether you target 18 or not — it is in every
release from 18 onward, so it lands on you at 21 or 25 just the same. If you
run on Windows, or read files written by a Windows process, or handle any
non-ASCII text at all, read the section above before your next upgrade and add
a test that asserts on decoded content.
