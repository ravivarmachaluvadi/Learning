# Java 13 — `yield`, and text blocks arrive

**GA:** September 2019 · **LTS:** no · **Class file major version:** 57 ·
**Oracle support:** ended March 2020 **[documented — Oracle roadmap]**

**Previous:** [Java 12](java-12.md) · **Next:** [Java 14](java-14.md) ·
**Back to:** [the story](README.md)

---

## The one thing to remember

**Java 13 is the release where the preview mechanism visibly did its job.** Six
months after switch expressions shipped with `break value;`, the second preview
replaced it with `yield`. The wrong syntax existed for exactly one release,
behind a flag, and then it was gone.

It also introduces text blocks, which — once they go final in
[Java 15](java-15.md) — become one of the most-used features of modern Java for
anyone who writes SQL, JSON or HTML in Java source.

## What you actually get

### Switch expressions, second preview — JEP 354

One change from [Java 12](java-12.md), and it is the whole point:

```java
// Java 12 preview                      // Java 13 preview onward, and final
default -> {                            default -> {
    int len = day.toString().length();      int len = day.toString().length();
    break len;      // overloaded            yield len;     // unambiguous
}                                       }
```

`yield` is a **contextual keyword**: it is only a keyword inside a switch
expression block, so any existing code with a variable or method named `yield`
still compiles. This is how Java adds keywords now without breaking the world —
the same technique is used for `record`, `sealed`, `permits` and `when`.

### Text blocks — JEP 355 (**first preview**)

Multi-line string literals. Before, writing a SQL query in Java looked like
this:

```java
String sql = "SELECT o.id, o.total, c.name\n" +
             "FROM orders o\n" +
             "JOIN customers c ON c.id = o.customer_id\n" +
             "WHERE o.status = 'PENDING'\n" +
             "ORDER BY o.created_at DESC";
```

Now:

```java
String sql = """
        SELECT o.id, o.total, c.name
        FROM orders o
        JOIN customers c ON c.id = o.customer_id
        WHERE o.status = 'PENDING'
        ORDER BY o.created_at DESC""";
```

The rule that matters and that nobody explains clearly: **incidental leading
whitespace is stripped based on the least-indented line, and the closing
delimiter's position counts as a line for that calculation.** So where you put
the closing `"""` controls the indentation of the result:

```java
String a = """
        hello
        """;        // closing """ at column 8, same as content -> "hello\n"

String b = """
        hello
    """;            // closing """ at column 4, less than content -> "    hello\n"
```

Two more details worth knowing before you use them:

- A text block ending with a newline before `"""` **includes** that trailing
  newline. Ending the content on the same line as the closing delimiter (as in
  the SQL example above) omits it. This is the most common surprise.
- Escapes still work: `\n`, `\"`, `\\`. Java 14's second preview adds `\s`
  (preserve a trailing space) and `\` at end-of-line (join lines without a
  newline).

**Do not use text blocks to build SQL by concatenating user input.** They make
string-building comfortable, which makes SQL injection easier to write. Use
parameter binding exactly as before.

### Dynamic CDS archives — JEP 350

Class-data-sharing archives can now be generated automatically when the
application exits, instead of requiring a separate training step. Another rung
on the ladder that ends at the AOT cache in [24](java-24.md)/[25](java-25.md).

### ZGC uncommits unused memory — JEP 351

ZGC now returns unused heap memory to the OS. Same container-friendliness
argument as G1's JEP 346 in [Java 12](java-12.md): an idle JVM that never
shrinks is an OOM-kill waiting to happen.

### Reimplemented legacy Socket API — JEP 353

`java.net.Socket` and `ServerSocket` were rewritten on top of modern
infrastructure. Invisible to you at the time — but this rewrite (plus JEP 373's
`DatagramSocket` rewrite in [15](java-15.md)) is **what made virtual threads
possible**. The old implementation used native-code paths that could not be
made to park a virtual thread; the new one can.

This is worth pausing on as a general pattern: **most big Java features are
preceded, by several years, by unglamorous rewrites of old code that nobody
announces.** Virtual threads in [21](java-21.md) look like a sudden arrival.
They are the visible end of work that started here.

## Pipeline movement this release

| Feature | State here | Ends up |
|---|---|---|
| Switch expressions (JEP 354) | **2nd preview** — `break value` → `yield` | Final in [14](java-14.md) |
| **Text blocks (JEP 355)** | **1st preview** | Final in [15](java-15.md) |

## What broke

Nothing significant.

## Verdict for your day job

**Don't target it.** Six months, ended March 2020.

Its lasting contributions are `yield` and text blocks, both of which you get in
any later release. Read the switch-expressions section as the concrete proof
that preview features are not bureaucracy.
