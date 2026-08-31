# Learning

Self-study labs on distributed systems, databases, the JVM, and networking —
65 topics, each a self-contained folder with a README, a browser
visualization, and exercises.

## Start here

| File | What it is |
|---|---|
| [`LEARNING-PATH.md`](LEARNING-PATH.md) | The order to read the labs in — seven stages, from one request on one wire up to consensus and system design |
| [`LEARNING-PATH.html`](LEARNING-PATH.html) | The same path as a visual page. Open it in a browser; the lab links are clickable |
| [`KNOWLEDGE-MAP.md`](KNOWLEDGE-MAP.md) | What each lab covers and how they depend on each other (387 `Builds on` links) |

## Shape of a lab

Each topic folder holds a `README.md` (the explanation), a
`visualization.html` (open it in a browser), and `EXERCISES.md` with
`SOLUTIONS.md`. Some of the older labs also carry runnable Java.

## Running the Java labs

Most are single-file and need nothing on the classpath:

```bash
java Foo.java
```

A few need an embedded database. Those jars are not committed — see
[`lib/README.md`](lib/README.md) for the Maven Central download commands and the
pinned versions.

Two labs generate their own throwaway keys and certificates on first run
(`ssh/ssh-real.sh`, and the `tls` lab's setup). Those artifacts are not
committed either.
