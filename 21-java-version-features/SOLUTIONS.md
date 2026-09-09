# Solutions — Java 9 → 25

Attempt `EXERCISES.md` first. Rungs 7–8 have no single right answer; what
follows is a worked position with its reasoning exposed, so you can disagree
with the reasoning rather than the conclusion.

---

## Rung 1 — Explain

**1.1** Java used to release "when the big feature was done." Java 9's big
feature took three and a half years, and ninety other finished features sat
waiting for it. When it finally shipped, everyone had to absorb ninety-one
changes at once, which made upgrading a project rather than a chore — and
projects get scheduled against other projects, and lose. So teams skipped it,
which made the *next* jump bigger, which made skipping more likely. Java broke
that loop by shipping on a fixed date twice a year with whatever is ready, so
each step is small enough to absorb without planning it.

**1.2** Java 9 was not big because the team was unusually productive — it was
big because it was *three and a half years* of accumulated work released in one
go. Java 10 covered six months and shipped six months' worth. The rate of work
was roughly the same; what changed was the batch size. And smaller batches are
the point, because upgrade cost grows faster than linearly with batch size:
when four things change and your tests go red you can find the cause, and when
four hundred do, you cannot.

**1.3**
- **Preview** is a *language or VM* feature, gated by `--enable-preview` at
  both compile and run time, and enforced by a stamp written into the class
  file.
- **Incubator** is an *API*, shipped in a `jdk.incubator.*` module you must add
  with `--add-modules`, and enforced by the module system plus the package
  name — which changes when it graduates.
- **Experimental** is a *VM* feature (a collector, a JIT), gated by
  `-XX:+UnlockExperimentalVMOptions`, and enforced by nothing but that flag.

**1.4** LTS means some vendors have committed to keep publishing security fixes
for that release for years after it is superseded; non-LTS releases get about
six months. To the follow-up: **nothing is technically different about the
build.** OpenJDK ships every release the same way. LTS is a support commitment
made *about* a release, not a property *of* it — which is also why different
vendors publish different LTS dates, and why some support releases Oracle does
not.

---

## Rung 2 — Identify

**2.1 (at JDK 21)** Final: switch expressions, text blocks, records, pattern
matching for `instanceof`, sealed classes, **pattern matching for `switch`**,
**record patterns**, **virtual threads**, sequenced collections, the HTTP
client. Still impermanent: structured concurrency (preview 1), scoped values
(preview 1), string templates (preview 1), compact source files (preview 1),
the FFM API (preview 3), the Vector API (incubator 6).

**2.2 String templates.** Preview in 21, second preview in 22, then
**withdrawn** in 23 — removed from the JDK entirely, no replacement, no
successor JEP. It is the only row that reverses, and it is the event that makes
preview mean something.

**2.3 The Vector API.** Incubator 1 in JDK 16 through incubator 10 in JDK 25
(and 11 in JDK 26) — the state never changes. It is waiting on **Project
Valhalla's value types**, because the API is designed around value semantics
the language does not have yet.

**2.4** LTS releases are **8, 11, 17, 21, 25**, with 29 next. Gaps: 8→11 is
4.5 years (the old model), 11→17 is 3 years, 17→21 is **2** years, 21→25 is 2,
25→29 is 2. The change is at 17→21: the cadence moved from three years to two,
because a three-year jump was still big enough to make teams wait for the next
one — the ratchet restarting one level up.

**2.5** The **minor version** — bytes 5 and 6, immediately after the
`cafebabe` magic number. Normally `0000`; a preview class file has `ffff`,
i.e. 65535.

---

## Rung 3 — Predict

**3.1 No, it does not load.** A preview class file's major version must match
the running JVM *exactly*. Built on 25 (major 69), it runs on 25 and nowhere
else. The `--enable-preview` flag on JDK 26 does not help — it satisfies a
different rule.

**3.2 Yes, it loads.** This is Java's core forward-compatibility promise:
ordinary bytecode compiled for an older release runs on every newer JVM. Java 8
bytecode (major 52) still runs on JDK 25.

The answers differ because **preview class files are deliberately exempt from
the compatibility promise.** That exemption is not an oversight — it is the
mechanism that makes it impossible for an impermanent feature to quietly become
load-bearing infrastructure in somebody else's dependency tree.

**3.3 Team B (8→17), by a wide margin.** 8→17 crosses split packages, the
version-string change, the Java EE module removal, JavaFX and Web Start,
Nashorn, biased locking, and both halves of strong encapsulation. 17→25 crosses
the charset default, dynamic agent loading, sequenced collections' default
methods, the string-template withdrawal, `Unsafe` warnings, the Security
Manager, non-generational ZGC and the JNI warning — real work, but almost all
of it either mechanical or a flag change.

**3.4 Scoped values finalised first** — JDK 25 (JEP 506) — despite entering the
pipeline a release *later* than structured concurrency, which is still
previewing in JDK 26.

The explanation is the useful part: both are Project Loom, same team, adjacent
scope. Scoped values have a small API surface whose semantics were settled
early — bind a value, read it beneath, it is gone when the block exits. Structured
concurrency had to settle how a scope decides it is done, and that *joiner*
design took several attempts; the JDK 21 shape
(`StructuredTaskScope.ShutdownOnFailure`) is not the JDK 25 shape
(`StructuredTaskScope.open(joiner)`). **Time in the pipeline measures design
risk, not team velocity.**

**3.5 Yes, something breaks — and this is the trap worth internalising.** The
flag stamps the class file whether or not any preview feature was used. So a
build with a leftover `--enable-preview` produces artifacts with minor version
65535, which require the flag at runtime and refuse to load on any other JDK
version — for no benefit whatsoever. Check the tab: "Only final features" plus
the flag still yields `ffff`.

---

## Rung 4 — Modify

**4.1** Dev enables the flag on JDK 12 to use switch expressions, writes
`break value;`, builds `orders.jar`, publishes it to the internal repository.
Three other teams add it as a dependency. Six months later the platform team
upgrades production to JDK 13, where that construct's semantics changed. The
jar is unchanged, and Java's compatibility promise says an unchanged artifact
keeps working — so nobody suspects it. It either misbehaves subtly or throws
something that points at nothing.

The compile-time flag protected the *compiler*. It did nothing for the
*artifact*, and the artifact is the thing that travels — into repositories,
into containers, into other teams' dependency trees, long after everyone has
forgotten the flag was passed. Hence the class-file stamp: put the mark on the
thing that moves.

**4.2** A hypothetical 17→23 jump: 18(9) + 19(7) + 20(7) + 21(15) + 22(12) +
23(12) = **62 JEPs**. The real jumps: 17→21 = 9+7+7+15 = **38**, and 21→25 =
12+12+24+18 = **66**. So the two-year jumps are not uniformly smaller in JEP
count — 21→25 is larger than the hypothetical, because JDK 24 was unusually
big.

That is the honest answer and it is more interesting than the expected one.
**JEP count is a poor proxy for migration cost.** What actually matters is how
many *breaking* changes are in the window, and there the two-year cadence wins
clearly: 17→21 contains one significant hazard, while 11→17 contained the
entire strong-encapsulation transition. The argument for two years is about
where the breakage falls, not about counting features.

**4.3** Better: a feature reaches users sooner, and a design mistake is caught
sooner.

Worse, first: **the feedback window per preview shrinks.** Real feedback on a
language feature comes from people using it in anger, which takes months.
Three-month previews would mostly produce previews with no feedback in them,
which is the appearance of iteration without the substance.

Worse, second: **the support surface grows.** Every release is a branch a
vendor may have to patch, and every release is another class-file version every
bytecode library must handle. Doubling the rate doubles that cost for the
entire ecosystem, not just for the JDK team.

**4.4** *For throwing:* silent behaviour changes are the ones that reach
customers; an exception would have converted an invisible correctness bug into
a loud, findable one.

*Against:* there is no well-defined "ambiguous decode." Most byte sequences are
valid in both `windows-1252` and UTF-8 and simply *mean* different things —
there is nothing for the JVM to detect. Enforcing this would mean rejecting
input that is legal by every applicable specification, on the basis of a guess.

**Committed answer: against.** The change was correct and could not have been
made loud without breaking correct programs. The right mitigation is not in the
JDK — it is `-Dfile.encoding=COMPAT` as a bridge, and, permanently, never using
default-charset APIs. That advice predates JEP 400 by fifteen years, and the
people bitten by JEP 400 are exactly the people who had ignored it.

---

## Rung 5 — Trace

**5.1**

| Release | JEP | State |
|---|---|---|
| 12 | 325 | First preview — the first preview feature in Java's history |
| 13 | 354 | Second preview |
| 14 | 361 | **Final** |

What changed between the previews: producing a value from a multi-statement
branch used `break value;` in JDK 12. `break` already meant "leave the
enclosing loop or switch", so inside a loop inside a switch you could not tell
which was meant. The second preview replaced it with **`yield`**, a contextual
keyword — only a keyword inside a switch expression, so existing code with a
variable named `yield` still compiles.

**5.2**

| Release | JEP | State |
|---|---|---|
| 14 | 370 | Foreign-Memory Access — incubator 1 |
| 15 | 383 | incubator 2 |
| 16 | 389 | Foreign **Linker** API — a separate incubator |
| 16 | 393 | Foreign-Memory Access — incubator 3 |
| 17 | 412 | **the merge** → Foreign Function & Memory API, incubator |
| 18 | 419 | incubator |
| 19 | 424 | **the graduation** → preview |
| 20 | 434 | preview 2 |
| 21 | 442 | preview 3 |
| 22 | 454 | **Final** |

The **merge** is JDK 17: two separate incubating efforts — memory access and
native linking — became one API. The **graduation** is JDK 19: moving from
incubator to preview renamed the package from `jdk.incubator.foreign` to
`java.lang.foreign`, breaking every import in every early adopter's code. That
break is by design; it is how the incubator gate stops an unfinished API
becoming load-bearing.

**5.3** Subtract 44 from the major version.

| | Major | JDK | Runs on 25 with no flags? |
|---|---|---|---|
| (a) `00 34` | 52 | Java 8 | **Yes.** Ordinary class file, forward compatible. |
| (b) `00 41` | 65 | Java 21 | **Yes.** |
| (c) `ff ff 00 45` | 69, minor 65535 | Java 25 **preview** | **No.** Needs `--enable-preview`, and then only on JDK 25. |
| (d) `00 46` | 70 | Java 26 | **No.** `UnsupportedClassVersionError` — you cannot run *newer* bytecode on an older JVM. |

Note (c) and (d) fail for completely different reasons: (c) is a preview gate,
(d) is ordinary backward incompatibility.

**5.4**

| Release | `setAccessible(true)` on a private `String` field |
|---|---|
| 9 | **Succeeds**, printing a one-time warning per offending package. `--illegal-access=permit` is the default. |
| 10–15 | Same. Warning stays. Five releases of notice. |
| 16 | **Throws `InaccessibleObjectException`** — the default flipped to `deny` (JEP 396). But `--illegal-access=permit` still restores the old behaviour. |
| 17 | **Throws**, and `--illegal-access` is removed as an option entirely (JEP 403). Only per-package `--add-opens` remains. |

Three distinct behaviours: permitted-with-warning (9–15), denied-but-escapable
(16), denied-with-no-global-escape (17+).

---

## Rung 6 — Spot the flaw

**6.1** The sequence: the library is built on JDK 21 with `--enable-preview` and
published internally. Three teams consume it. The platform team upgrades one
service to JDK 22 — a routine, low-risk bump. That service now fails at class
load with `UnsupportedClassVersionError`, and the failure is in a *dependency*,
so the team debugging it has no reason to suspect a flag in someone else's
build. Meanwhile no team can upgrade independently: the library has silently
coupled all of them to one exact JDK version.

And the harder failure: if the previewed feature is *withdrawn* (as string
templates were), the library cannot be rebuilt at all without a rewrite.

The violated guarantee is **binary forward compatibility** — deliberately
waived for preview class files. "We control all our JDKs" is not the relevant
claim; the relevant claim would be "no service will ever upgrade
independently," which nobody can promise.

**6.2 The UTF-8 charset default (JEP 400, JDK 18).** It compiles — no API
changed. It boots — nothing throws. The health endpoint returns 200 — it does
not read any affected file. And text read with a default-charset API now
decodes differently, so customer names with non-ASCII characters come out
corrupted, and it surfaces two weeks later in a report.

The mechanism is that the change is *semantic*, not structural: no signature
changed, no exception is thrown, only the bytes-to-characters mapping moved.
The only thing that catches it is a test asserting on **output content**.
Biased locking's removal (JDK 15) has the same shape.

**6.3** The reasoning treats time-in-pipeline as a proxy for team performance.
It is not: the Vector API's own JEP states it will remain in incubation until
Project Valhalla delivers value types, because the API is built on value
semantics the language does not yet have. Finalising now would freeze a design
against a language about to change underneath it — the exact mistake that
produced `java.util.Date`.

**What would actually support the conclusion:** evidence about *Valhalla's*
progress, since that is the blocker. **What would refute it:** Valhalla
delivering. The evidence to gather is one project over, not in the incubation
count.

For contrast: string templates left the pipeline after **two** previews, by
being withdrawn. That is what a design failure looks like, and it looks nothing
like ten patient incubations.

**6.4** **First:** "supported until 2030" is Oracle's *extended* support for
Java 8, which is a paid tier — the free public updates ended in 2019, and
whether you have it depends entirely on your vendor and contract. Many teams
saying this are not actually covered by the date they are citing.

**Second, and larger:** support dates say nothing about the **cost of the
eventual jump**, which grows every year you wait. That is the ratchet from the
README. A team that waits until 2030 will be doing an 8→33 migration under time
pressure with nobody left who remembers why the code does what it does. The
question is not "are we supported" but "what will this cost when we can no
longer avoid it, and is it cheaper now."

**6.5** What it bought: the service starts. What it cost:

It opens `java.lang` reflectively **for the entire application**, permanently
and invisibly — the setting is in an environment variable, not in code, so it
does not appear in any review, and nobody will find it when they wonder why
their JDK 25 upgrade behaves oddly. It removes the pressure that would have
caused the actual fix (upgrading the library that needs it), so the debt
compounds silently. And because it is global, a *second* library that starts
needing deep reflection will now work by accident, and nobody will ever know.

The better outcome: find the specific offender with
`jdeps --jdk-internals`, upgrade it if possible, and if not, add the narrowest
possible `--add-opens` **in the launch configuration where a reviewer will see
it**, with a ticket attached.

---

## Rung 7 — Handle failure

*Worked positions, not the only answers.*

**7.1 The 40-service plan.**

*Route:* 11 → 17 → 25, in two waves. Do not attempt 11→25 directly: essentially
all the risk is concentrated in 11→17 (strong encapsulation), and isolating it
means that when something breaks you know which transition caused it.

*CI first, before any migration work:* add a **non-blocking** job to all 40
repositories that builds and runs the full test suite on JDK 17. Non-blocking
is not a detail — a job that can break the team's day gets deleted. Let it run
for two to three weeks and collect the failures. This converts the migration
from an unknown into a list.

*Order within a service:* build tool → build plugins → test and bytecode
libraries (Mockito/ByteBuddy, JaCoCo, Lombok) → application dependencies →
application code. Most "the new JDK is broken" reports are a five-year-old
plugin.

*Before writing the plan, run `jdeps --jdk-internals` on every service.* It
names each JDK-internal API used *and its supported replacement*, and it sees
through dependencies. It will usually change the plan.

*Readiness criterion — and this is where most plans are too weak:* not "it
compiles and boots." A service is ready when its **full behavioural test suite**
passes on the target JDK, including tests that assert on output *content*. That
is what catches JEP 400 and the biased-locking class of change.

*Sequencing across services:* pick two or three low-traffic services with good
test coverage first, absorb the surprises, write them down, then fan out. Group
the remainder by shared framework version — services that share a Spring
version will hit the same problems, and the second one costs a tenth of the
first.

*The abandoned library:* four options in order — (1) check for a community
fork, which usually exists; (2) check whether the JDK now provides what you
used it for (`VarHandle`, FFM, the Class-File API, `java.util.Base64` and the
HTTP client have all absorbed abandoned libraries); (3) vendor the source into
your codebase and fix it yourself — realistic, and the honest cost is
maintaining it forever; (4) contain it: isolate the dependency behind an
interface in a service that stays on 17 while everything else moves, and
schedule its replacement. Do not add a global `--add-opens` and call it done.

*Second quarter:* repeat for 17→25, which is much lighter.

**7.2 Preview policy.**

- **Who:** anyone, in a spike or a prototype. That freedom is the point of
  preview features existing.
- **What kind of artifact:** applications you own end-to-end, **never** a
  library, and never anything published to a shared repository. The rule is
  mechanical: if another team consumes the artifact, no preview features.
- **What must be true of the build:** the JDK version is pinned in the build
  file (toolchain, not "whatever is installed"), CI fails if the JDK drifts,
  and the `--enable-preview` flag is declared in exactly one place with a
  comment naming the JEP and the feature.
- **Enforce it, don't trust it:** add a CI check that greps published artifacts
  for class files with minor version 65535. This is cheap and it is the only
  control that actually works, because the flag will otherwise be copied into a
  build file by someone who does not know what it does (see 3.5).
- **On withdrawal:** the owning team rewrites within one release cycle. The
  policy should say this in advance, so that when it happens it is a scheduled
  task rather than an argument about whether it was a good idea.

**7.3 Diagnosing the wrong-characters report.**

*Order matters — cheapest and most likely first.*

1. **Suspect the charset default before anything else.** You moved across JDK
   18, and this symptom is JEP 400's signature: wrong characters, no exception.
2. **Confirm cheaply:** run the old and new JDK against the same input file and
   diff the decoded output. If they differ, you are done diagnosing.
3. **Locate the read.** Grep for default-charset APIs: `new FileReader(`,
   `new String(` with one argument, `new PrintWriter(File`, `getBytes()` with
   no argument, `new InputStreamReader(` with one argument.
4. **Bridge, then fix.** `-Dfile.encoding=COMPAT` restores JDK 17 behaviour and
   stops the bleeding. The fix is an explicit `StandardCharsets.UTF_8` at every
   call site.
5. **Check the data.** If files were *written* by the old service, they are in
   the old encoding and need converting — the code fix alone leaves you reading
   old files wrongly.

*The test to add:* a round-trip test with genuinely non-ASCII content — not
`"test"`, but something with accents, a non-breaking space, and an emoji — that
asserts on the exact decoded string. Put it in the shared test module so every
service gets it. **The general principle: your upgrade safety net is tests that
assert on output content, because those are the only ones that catch a silent
semantic change.**

**7.4 Responding to JEP 500 (final means final).**

*Find out if you are affected, first — most of this is in dependencies, not
your code.* Grep your own source for `setAccessible` followed by a write to a
`final` field, but expect little. The real exposure is frameworks: some
serialisation libraries reconstruct objects by writing final fields directly,
and some DI and test frameworks do the same for injection and mocking.

*Then:* run on a JDK 26 early-access build in the non-blocking CI job you
already have from 7.1 — this is exactly what that job is for, and it is why it
should exist permanently rather than being deleted after each migration. JEP
500 is a *preparation* JEP, so it warns before it forbids; the warning names
the caller, which gives you the inventory for free.

*Then:* upgrade the named frameworks, most of which will have moved to
`VarHandle`, `MethodHandles.privateLookupIn` or the Class-File API. For your
own code, records and constructor injection remove the need entirely.

*The pattern to notice:* JEP 451 (agents), JEP 472 (JNI) and JEP 500 (final)
are the same move — a release whose entire content is a warning, filed to start
a ladder. When you see one, that is your notice, and acting on it during the
warning phase costs a fraction of acting on it during the removal phase.

---

## Rung 8 — Design

*Worked positions.*

**8.1 Version policy for 200 services.**

*Permitted versions:* exactly two at any time — the **current LTS** and the
**previous LTS**. Two, not one, because a hard cutover across 30 teams is not
achievable; three or more and you are maintaining a museum.

*Who decides:* a platform team owns the list and the dates. Individual teams do
not get to choose their JDK, for the same reason they do not choose their TLS
version — the cost of divergence is borne by everyone.

*Introducing a version:* the moment a new LTS reaches GA, it goes into every
repository's CI as a **non-blocking** job. Not when migration starts — at GA.
This is the single highest-leverage item in the whole policy, because it turns
migration into a stream of small known fixes instead of a project.

*Retiring one:* announce the date at the same time you introduce the new one —
two years of notice, matching the LTS cadence. Then actually enforce it, or the
policy is decoration.

*Automated:* the CI job; a build-time check that the toolchain version matches
the declared one; a scan for preview-stamped class files in published artifacts
(see 7.2); a dependency bot. Automate the *detection*, not the migration —
attempts to automate the fix produce changes nobody understands.

*Monitored:* one dashboard, one number per service — which JDK it runs in
production. Distribution, not average. The number that matters is *how many
services are on the oldest permitted version*, because that is your exposure
when it is retired.

*Stopping the ratchet:* the ratchet is "upgrading is a project, projects get
scheduled, this one loses." You break it by making the upgrade **not a
project** — continuous CI, a fixed cadence nobody re-litigates, and a default
that moving is what happens unless someone objects.

*The two most likely failures.* **First: the non-blocking CI job gets ignored
and then deleted.** It is red for months, everyone stops looking, someone
cleans it up. Mitigation: give one named person responsibility for its
*aggregate* status, and review it monthly — the review is what keeps it alive,
not the job. **Second: two or three services get a permanent exemption**, for
good reasons at the time, and the exemption never expires. Mitigation: every
exemption gets an expiry date and a named owner at the moment it is granted,
and an exemption without a date is refused.

**8.2 Library version policy.**

*Minimum JDK:* the **previous LTS**. On this timeline that means 21 — supported
until at least 2028, and it gives you records, sealed types, pattern matching
and virtual threads. Going lower costs you real expressiveness for users who,
in practice, are not going to adopt a new version of your library anyway.
Requiring the *current* LTS excludes too many.

*When to raise it:* on a major version of your library, on the LTS cadence, and
announced one minor version in advance. Never in a patch release — a minimum
JDK bump is a breaking change even though it does not look like one, and
shipping it in a patch is how you end up in someone's incident review.

*Multi-release jars:* only for a genuine optimisation, never for API surface.
The legitimate use is "there is a much faster path available on JDK N+" — for
example, using the Vector API or FFM when present and falling back otherwise.
The illegitimate use is offering different APIs per version, which doubles your
test matrix and confuses every user. Cost: your build and test complexity
roughly doubles for the versions you cover, so cover as few as possible.

*Handling a user base spread from 8 to 26:* be explicit, in the README, in a
table, with dates. If you must serve Java 8, do it with a **separate,
frozen, maintenance-only branch**, not with compatibility contortions in the
main line. And compile with `--release`, never `-source`/`-target`, so the
compiler catches an accidental use of a newer API instead of your user
discovering it as a `NoSuchMethodError`.

*Never use preview features.* Non-negotiable for a library — see 6.1.

**8.3 Is the preview mechanism worth it?**

**Yes**, and the strongest case against is worth stating properly first.

*The case against:* string templates consumed years of design, two full
implementations, two release cycles of documentation and tooling support, and
was then deleted. That is real engineering that produced nothing shippable.
Multiply by every feature that has previewed more than twice, and the mechanism
looks expensive.

*Why it is still worth it.* Compare against the actual alternative, which is
not "get it right first time" — it is **shipping it permanently**. Java is
still carrying `Object.finalize()`, Java serialization and `java.util.Date`,
each of which has cost the ecosystem more than string templates cost, every
year, for thirty years. `finalize()` alone has produced two decades of resource
leaks and GC pathologies, and it can never be removed.

*Two concrete features that make the case:*

**Switch expressions.** Shipped in JDK 12 preview with `break value;`, which
overloaded `break` ambiguously. Feedback arrived, and the JDK 13 preview
replaced it with `yield`. Cost: one release, behind a flag, affecting only
opt-in users. Under the old model that syntax would be permanent, and every
Java programmer would be explaining it to newcomers forever.

**String templates.** The apparently-damning example is actually the proof.
The mechanism's value is precisely that withdrawal is *possible*, and a
mechanism whose escape hatch is never used is not known to work. The cost of
those years is the insurance premium; the payout is that Java did not
permanently acquire a syntax its own designers concluded was too complex.

*The honest qualification:* the mechanism is not free for users either, and the
lab's F5 is the evidence — anyone who used `STR."..."` had working code become
uncompilable. The design's answer is that this is *disclosed and enforced up
front* rather than discovered later, which is the most you can ask of an
impermanence mechanism.

**8.4 Designing this for your own platform.**

Take a shared internal library as the example.

*What "preview" means there:* an API you may call, that will not be removed
without notice **only if** you are in the list of registered callers, and that
carries no compatibility promise across minor versions. Concretely: a
`@Preview` annotation, a separate package (`com.acme.lib.preview.*`), and its
own section of the changelog.

*What plays the role of the class-file stamp — the enforcement question, which
is the whole design problem.* The stamp works because it is **mechanical,
attached to the artifact, and checked automatically by something the developer
does not control.** Candidates in a normal organisation:

- A **build-time check** that fails the build if a non-exempt module imports
  the preview package. This is the closest analogue and it is genuinely
  effective, because it runs on every build without anyone remembering.
- A **linter or ArchUnit rule** in the shared build configuration.
- For a service API: a **required header or API version parameter** on preview
  endpoints, so a caller cannot use one by accident, and the gateway can
  enumerate who is calling.
- For a database schema: a **naming convention plus a migration check** —
  `_preview` suffixed tables that the schema linter refuses to reference from
  stable code.

*If nothing can play that role* — and often nothing can, because you cannot
modify the consumer's build — then **do not call it preview.** An impermanent
thing with no enforcement is just an undocumented promise, and it will become
load-bearing within one quarter; the failure mode is precisely the one in 6.1.
The honest alternatives are: (a) don't ship it until it is final, or (b) ship
it final and accept that you now maintain it forever, and price that in.

*The general lesson, which is the point of the exercise:* **impermanence needs
a mechanism, not a label.** Java put the mark on the artifact rather than on
the developer's intention, because the artifact is the thing that outlives the
intention. Any impermanence scheme you design should be judged by the same
test: what physically stops this from becoming something people depend on?
