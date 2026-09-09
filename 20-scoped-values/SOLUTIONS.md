# Solutions

## P1 — B, A, none, none

1. `"B"` — the inner `where` shadows `"A"` for exactly that block.
2. `"A"` — shadow gone with the inner block; nothing to restore because
   nothing was overwritten.
3. `"none"` — a hand-started thread is outside the dynamic scope; bindings
   do not follow it (only structured forks inherit).
4. `"none"` — outside the block the binding no longer exists.

## P2 — outputs 3 and 4 change; the doomed line is the restore

With ThreadLocal: (1) `"B"`, (2) `"A"` *only if* the code faithfully did
`String old = TL.get(); TL.set("B"); try {...} finally { TL.set(old); }`,
(3) `null` (no inheritance for plain `ThreadLocal` — or, with
`InheritableThreadLocal`, `"A"`: a copy taken at thread creation), and
(4) **`"A"` — still bound!** The value survives the "request" until someone
calls `remove()`. The doomed line is the `finally { TL.set(old); }` /
`remove()` — delete it and every later reader of the thread sees stale
identity, silently: the leak from `./run.sh leak`.

## M1 — you can't, and that's the theorem

Everything you can legally do — another `where(...).run(...)` inside the
audit block — only affects code *called from* that block, and unwinds with
it. There is no operation whose effect survives its own block: no `set`, no
carrier you can "apply" without wrapping a block. Your failure proves the
central property: **the value at any point in the code is decided solely by
the nearest enclosing `where` in the source** — readable statically, like a
final parameter, never by a distant caller's leftover action.

## M2 — `scope.join()` (and the close guarantee behind it)

Every escape you try hits the same wall: the fork was created inside
`try (var scope = ...)`, and the structured-concurrency close guarantee —
"nothing outlives the scope block" — means the child is finished or
cancelled before the `try` exits, which happens *before* the
`where(...).run(...)` block exits (the scope is nested inside the binding).
Concretely you're stopped at `scope.join()` /the implicit `close()`. This
nesting-discipline is exactly *why* inheritance-by-reference is safe to
offer at all: JEP 506 leans on JEP 505's lifetime guarantee.

## M3 — the binding held; immutability of the payload was yours to keep

ScopedValue guaranteed: the *association* `ITEMS → that list object* never
changed; nobody rebound it. You violated: the payload's immutability —
deep code mutated shared state through the binding, reintroducing invisible
sideways data flow (the very disease of ThreadLocal-as-scratchpad). The
one-word fix: **`List.copyOf`** (bind an immutable snapshot — records and
unmodifiable views generally).

## D1 — capture at submit time, not at run time

Timeline: the binding for `req-7f3a` exists only while
`ScopedValue.where(...).run(handleRequest)` is on the main thread's stack.
`handleRequest` *queues* the lambda and returns; the block exits; the
binding is erased. ~150 ms later the audit thread runs the lambda and calls
`REQUEST_ID.get()` — on a different thread, after the scope died: doubly
unbound → `NoSuchElementException`.

Fix — read while the binding is alive, close over the *value*:

```java
static void handleRequest() {
    String id = REQUEST_ID.get();                       // ADDED: capture now
    System.out.println("handling " + id + "; queueing audit write...");
    auditPool.execute(() ->
        System.out.println("audit: writing entry for " + id));   // CHANGED: use the captured value
}
```

Still asynchronous, no ThreadLocal, and correct forever.

Why the loud crash is better: the ThreadLocal version of this bug doesn't
throw — `get()` on the audit thread quietly returns `null` (or a *previous
request's id* if the pool thread is contaminated), and you discover it weeks
later in corrupted audit rows. The `NoSuchElementException` points at the
exact line, the first time it runs.
