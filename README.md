# Learning

Self-study labs on distributed systems, databases, the JVM, and networking.
71 topics, each a numbered folder with a README you read like a story, a
browser visualization you drive yourself, and exercises with solutions.

## Start here

| File | What it is |
|---|---|
| [`LEARNING-PATH.md`](LEARNING-PATH.md) | **The order.** Labs numbered 1 to 71 in eight stages, from one request on one wire up to whole systems. The single source of the numbering. |
| [`LEARNING-PATH.html`](LEARNING-PATH.html) | The same path as a visual page. Open it in a browser; the lab links are clickable. |
| [`KNOWLEDGE-MAP.md`](KNOWLEDGE-MAP.md) | What each lab covers and how they depend on each other (478 `Builds on` links). Newest first. |
| [`STYLE.md`](STYLE.md) | How a lab is written: the Feynman / Socratic voice contract every README follows. |

## How the folders are numbered

Every lab folder is `<number>-<slug>`: `01-networking-for-developers`,
`24-cap-theorem`, `39-paxos`. The number is the lab's position in
`LEARNING-PATH.md`. Sort the folder by name and you are looking at the
curriculum.

**Inserting a lab later:** a topic that belongs between 3 and 4 gets the number
3.5 and the folder `03.5-<slug>`. Between 3 and 3.5 it gets 3.25. Nothing
else moves. The `/learn` skill does this automatically when it creates a lab;
`python tools/renumber.py --check` confirms folders and links agree, and
`--renumber` collapses the decimals back to a clean 1..N whenever you want
that.

## Shape of a lab

Each folder holds a `README.md` (the lesson), a `visualization.html` (open it
in a browser), and `EXERCISES.md` with `SOLUTIONS.md`. Some older labs also
carry runnable Java, kept for reference; the lesson never depends on running
it. `java-memory-model` is the one lab that is still visualization and source
only.

## How a lab reads

A README opens with the story, not with a table of contents. Every section
asks its question before answering it, says the answer in one breath a
twelve-year-old could follow, then gives the engineer's version with the
numbers in tables. Symbols arrive after the words for them. The story's
characters stay on stage all the way into the failure analysis and the
production section. And nothing is ever cut to make a lab shorter: a lab that
reads badly gets more structure, not less content. The full contract is
[`STYLE.md`](STYLE.md).

Two labs were rewritten first to set the standard: `39-paxos` and
`01-networking-for-developers`. The rest are being re-delivered in batches;
see the backlog below.

## Tools

| Command | What it does |
|---|---|
| `python tools/renumber.py --check` | Every folder matches its `LEARNING-PATH.md` line; every relative link resolves |
| `python tools/renumber.py --apply` | Rename folders to match the path file; rewrite links |
| `python tools/renumber.py --renumber` | Collapse decimal numbers into 1..N, then apply |
| `python tools/audit.py .` | Delivery signals for every lab (front-matter, templates, notation, story continuity) |
| `python tools/preserve-check.py old.md new.md` | Prove a rewrite lost no number, link, table, diagram or glossary term |

## Running the Java labs (historical)

Most are single-file and need nothing on the classpath:

```bash
java Foo.java
```

A few need an embedded database. Those jars are not committed; see
[`lib/README.md`](lib/README.md) for the Maven Central download commands and
the pinned versions. Two labs generate throwaway keys and certificates on first
run (`ssh/ssh-real.sh`, and the `tls` lab's setup); those artifacts are not
committed either.

## Rewrite backlog

<!-- BACKLOG:START -->
<!-- BACKLOG:END -->
