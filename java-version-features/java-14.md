# Java 14 — records arrive, and NullPointerException finally tells you which thing was null

**GA:** March 2020 · **LTS:** no · **Class file major version:** 58 ·
**Oracle support:** ended September 2020 **[documented — Oracle roadmap]**

**Previous:** [Java 13](java-13.md) · **Next:** [Java 15](java-15.md) ·
**Back to:** [the story](README.md)

---

## The one thing to remember

**Java 14 is where Project Amber's programme becomes visible.** Records
(preview), pattern matching for `instanceof` (preview) and finalised switch
expressions all land here, and they are not three unrelated features — they are
three parts of one design that completes in [Java 21](java-21.md).

It also ships **helpful NullPointerExceptions**, which is, per line of code
saved in debugging, one of the highest-value changes in this entire lab.

## What you actually get

### Helpful NullPointerExceptions — JEP 358

Before:

```
Exception in thread "main" java.lang.NullPointerException
    at com.acme.Orders.process(Orders.java:42)
```

Line 42 is `return order.getCustomer().getAddress().getCity().toUpperCase();`.
Four things could be null. Good luck.

After:

```
Exception in thread "main" java.lang.NullPointerException:
Cannot invoke "String.toUpperCase()" because the return value of
"com.acme.Address.getCity()" is null
    at com.acme.Orders.process(Orders.java:42)
```

The JVM reconstructs which dereference failed by analysing the bytecode at the
throw site. **In Java 14 it is off by default** (`-XX:+ShowCodeDetailsInExceptionMessages`);
**it became the default in [Java 15](java-15.md).**

One caution worth knowing: the message can include variable and field names
from your code, so an NPE message reaching a user-facing error page can leak
internal structure. That is an argument for not putting raw exception messages
in HTTP responses, which was true anyway.

### Records — JEP 359 (**first preview**)

A record is a transparent carrier for immutable data.

```java
public record OrderLine(String sku, int quantity, BigDecimal unitPrice) { }
```

That single line generates: a canonical constructor, `sku()`, `quantity()`,
`unitPrice()` accessors, `equals`, `hashCode`, and `toString`. All fields are
`private final`; the class is `final`; it cannot extend anything.

**What records are for, precisely:** a record's contract is that it is exactly
its components — nothing hidden, nothing derived, no identity beyond its
values. That is a stronger statement than "a class with less boilerplate", and
it is why the compiler is allowed to generate `equals` for you.

**What they are not:** a replacement for JavaBeans in every context. A record
has no no-arg constructor and no setters, which historically confused
frameworks. Modern Jackson, Spring and Hibernate handle records well now (as
DTOs and projections; as JPA `@Entity` types they remain a poor fit, because
JPA requires mutability and a no-arg constructor).

Validation goes in a **compact constructor**:

```java
public record OrderLine(String sku, int quantity, BigDecimal unitPrice) {
    public OrderLine {                        // no parameter list, no assignments
        Objects.requireNonNull(sku);
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be > 0");
        sku = sku.strip();                    // you may normalise; assignment to
                                              // the field happens after this block
    }
}
```

A record can also have static factories, extra instance methods, and can
implement interfaces — it just cannot extend a class or add instance fields.

Records become genuinely powerful in [Java 21](java-21.md), where **record
patterns** let you destructure them in a `switch`. Keep that endpoint in mind;
records are step one of three.

### Pattern matching for `instanceof` — JEP 305 (**first preview**)

```java
// before
if (obj instanceof String) {
    String s = (String) obj;      // the cast you just proved was safe
    if (s.length() > 5) { ... }
}

// after
if (obj instanceof String s && s.length() > 5) { ... }
```

The binding variable `s` is in scope exactly where the compiler can prove the
test passed — including in the rest of the `&&`, and in the `else` branch of a
negated test:

```java
if (!(obj instanceof String s)) return;
// s is in scope here, because the method returned if it wasn't a String
use(s);
```

That flow-sensitive scoping is the part people find surprising and it is
deliberate — it makes the early-return idiom work.

### Switch expressions — **final** — JEP 361

Two previews ([12](java-12.md), [13](java-13.md)), one syntax change, done. Use
them freely from Java 14 on; no flag.

### Text blocks, second preview — JEP 368

Adds two escapes: `\s` (a space that survives incidental-whitespace stripping)
and a trailing `\` (join to the next line without a newline).

### JFR event streaming — JEP 349

Consume Flight Recorder events *as they happen*, in-process or from another
process, rather than dumping a file and opening it later:

```java
try (var rs = new RecordingStream()) {
    rs.enable("jdk.CPULoad").withPeriod(Duration.ofSeconds(1));
    rs.onEvent("jdk.CPULoad", e -> gauge.set(e.getFloat("machineTotal")));
    rs.startAsync();
}
```

This is how you get JFR data into Micrometer/Prometheus without a file
round-trip.

### Packaging tool (incubator) — JEP 343

`jpackage` — native installers (`.msi`, `.dmg`, `.deb`). Final in
[Java 16](java-16.md). Partly a response to Java Web Start's removal in
[11](java-11.md).

### Foreign-Memory Access API (incubator) — JEP 370

The first step of the eight-year road to the FFM API. See the README's
[very long case](README.md#the-very-long-case--the-foreign-function--memory-api-eight-years)
for the full ladder.

## Pipeline movement this release

| Feature | State here | Ends up |
|---|---|---|
| Switch expressions (JEP 361) | **FINAL** | done |
| **Records (JEP 359)** | 1st preview | Final in [16](java-16.md) |
| **Pattern matching for `instanceof` (JEP 305)** | 1st preview | Final in [16](java-16.md) |
| Text blocks (JEP 368) | 2nd preview | Final in [15](java-15.md) |
| **Foreign-Memory Access (JEP 370)** | 1st incubator | FFM final in [22](java-22.md) |
| Packaging tool (JEP 343) | Incubator | Final in [16](java-16.md) |

## What broke

- **CMS garbage collector removed — JEP 363.** Deprecated in
  [Java 9](java-09.md), gone here. `-XX:+UseConcMarkSweepGC` now fails to
  start. If you inherit JVM flags from a pre-2017 runbook, this is the flag
  that bites. Move to G1 (the default) or ZGC.
- **Pack200 tools and API removed — JEP 367.** Deprecated in
  [11](java-11.md).
- **`ParallelScavenge + SerialOld` GC combination deprecated — JEP 366.**
- **Solaris and SPARC ports deprecated — JEP 362.** Removed in
  [15](java-15.md).

## Verdict for your day job

**Don't target it.** Six months, ended September 2020.

But this is the release to read if you want to understand where modern Java's
*shape* comes from. Records and pattern matching start here; helpful NPEs start
here. Everything in this file is available and better in
[17](java-17.md), [21](java-21.md) and [25](java-25.md).
