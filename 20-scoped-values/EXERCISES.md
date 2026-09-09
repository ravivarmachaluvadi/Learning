# Exercises

**Predict** (reason before running), **modify** (change a demo, explain),
**debug** (find and fix the planted bug). Answers in
[`SOLUTIONS.md`](SOLUTIONS.md) — commit to answers in writing first.

---

## Predict

**P1 — the four prints.**
[`src/P1.java`](src/P1.java) binds `CTX` to `"A"`, then inside: rebinds to
`"B"` for one print, prints again, starts a bare virtual thread that prints
`CTX.orElse("none")`, and finally — outside everything — prints
`CTX.orElse("none")` once more. Write down all four outputs, then:

```bash
java src/P1.java
```

**P2 — same shape, ThreadLocal.**
Without running: rewrite P1's four prints in your head with a `ThreadLocal`
(`set("A")`, `set("B")` + restore, bare thread, after). Which of the four
outputs change? Which line of the ThreadLocal version is the one a refactor
will eventually delete, and what is the blast radius when it happens?

---

## Modify

**M1 — break the restoration (try to).**
In `src/Rebinding.java`, try to make the "audit write" leave `USER` as
`"system"` for the rest of the request — using only the public `ScopedValue`
API, no reflection. How far do you get? What does your failure prove about
where the value of a scoped value can be determined from?

**M2 — make the fork outlive the binding (try to).**
In `src/Inheritance.java`, the structured fork inherits `REQUEST_ID`. Try to
arrange for that forked child to still be running *after* the
`ScopedValue.where(...).run(...)` block has exited, so it would read a dead
binding. Which mechanism from the structured-concurrency lab stops you, and
at exactly which line?

**M3 — the mutable payload smuggle.**
Change `BasicFlow` to bind a `new ArrayList<>(List.of("a"))` and have the
deepest layer `add("smuggled")` to it. It works (the test
`the_binding_is_immutable_but_the_object_it_names_is_not` says so). Now
state precisely which guarantee ScopedValue kept and which desirable
property *you* violated — and the one-word fix.

---

## Debug

**D1 — the audit log that crashes in production.**
`java src/BrokenAudit.java` — the request queues an audit write on a
background executor; it crashes with
`NoSuchElementException: ScopedValue not bound`. Explain the exact timeline
(when does the binding die? when does the lambda run?), then fix it
**without** making the audit write synchronous and without introducing a
ThreadLocal. One line changes and one line is added. Why is this crash
*better* than what the ThreadLocal version of the same bug does?
