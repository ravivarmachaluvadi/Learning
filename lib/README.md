# lib/ — shared jars for the Java labs

Most labs here are pure Java and need nothing on the classpath: `java Foo.java`
just runs. Three labs need a real embedded database, because *real* ACID
transactions are the lesson rather than a detail — simulating them with
in-memory objects would quietly delete the thing being taught.

| Jar | Stands in for | Used by |
|---|---|---|
| `h2.jar` | Python's `sqlite3` — a row-store with real transactions | `transactional-outbox`, `event-sourcing`, `cqrs`, and the row-store half of `database-landscape` |
| `duckdb_jdbc.jar` | DuckDB, unchanged — a columnar engine | the columnar half of `database-landscape` |

**Why H2 rather than sqlite-jdbc:** H2 is pure Java (one portable jar, no native
binaries) and it is the embedded database you already meet in Spring Boot tests,
so the connection code reads like something from work rather than lab-only
scaffolding.

## Running a lab that needs one

From inside the lab directory:

```bash
java -cp ../lib/h2.jar OutboxLab.java dual-write-crash
```

`database-landscape` needs both jars, plus a flag to silence a JDK 25 warning
about DuckDB's native access:

```bash
java --enable-native-access=ALL-UNNAMED \
     -cp ../lib/duckdb_jdbc.jar:../lib/h2.jar \
     experiments/Exp3RowVsColumn.java
```

Each lab's own README states its exact command; these are the shapes.

## Versions

Pinned in `H2-VERSION.txt` and `DUCKDB-VERSION.txt`. To refresh a jar, download
the same artifact from Maven Central at the version you want:

```bash
curl -sL -o lib/h2.jar \
  https://repo1.maven.org/maven2/com/h2database/h2/<version>/h2-<version>.jar
```

Note `duckdb_jdbc.jar` is large (~81 MB) because it bundles native binaries for
every platform. That is normal for DuckDB and is the price of the columnar
engine in `database-landscape`; nothing else here depends on it.
