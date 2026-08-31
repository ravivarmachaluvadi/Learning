# Exercises — Java 9 → 25

Eight rungs, easiest first. Everything here is pencil-and-paper or answerable
from the visualization — **there is nothing to code**, by design.

**The visualization:** `visualization.html` in this directory, or
<https://claude.ai/code/artifact/7b7a1370-fb28-4a44-bcfe-32b4e499a35d>

Attempt each before opening `SOLUTIONS.md`. Rungs 3–6 are the ones that
actually change how you read a release note; rungs 7–8 are the ones you would
be asked in a senior interview or a platform review.

---

## Rung 1 — Explain

**1.1** In your own words, and without using the words *preview*, *LTS* or
*JEP*: why did Java start releasing every six months? A colleague who last
touched Java 8 should understand your answer.

**1.2** Java 9 shipped 91 changes. Java 10, six months later, shipped 12. Your
colleague says "so Java 10 was a much smaller release — they slowed down."
Explain why that reading is wrong, in three sentences.

**1.3** Explain the difference between *preview*, *incubator* and
*experimental* to someone who thinks they are three words for the same thing.
One sentence each, and each sentence must name the mechanism that enforces it.

**1.4** What does LTS actually mean? Your answer must survive the follow-up
question: "so what is technically different about a JDK 25 build?"

---

## Rung 2 — Identify

Use the visualization.

**2.1** Open **tab 2 (The pipeline)** and step to JDK 21. Which features are
*final* as of that release, and which are still impermanent?

**2.2** In the same tab, find the one row whose colour goes *backwards* — from
a state to a worse state. Which feature is it, and what happened?

**2.3** Find the row that never changes state at all across eighteen releases.
Name it, and say what it is waiting for.

**2.4** Open **tab 1 (The timetable)**. Which releases are LTS? What is the gap
between each consecutive pair, and where does that gap change?

**2.5** Open **tab 3 (The class-file lock)** with the default settings. Which
two bytes of the class file carry the preview stamp, and what value do they
take when the stamp is present?

---

## Rung 3 — Predict

Commit to an answer before checking.

**3.1** A jar was compiled on JDK 25 with `--enable-preview`. It is run on
JDK 26 *with* `--enable-preview`. Does it load? Check in tab 3 (build on 25,
run on 26).

**3.2** A jar was compiled on JDK 21 with **no** flags. It is run on JDK 25.
Does it load? Why is your answer different from 3.1?

**3.3** Team A is on JDK 17 and moves to 25. Team B is on JDK 8 and moves to
17. Which team hits more hazards? Predict first, then check in tab 4.

**3.4** Scoped values entered the pipeline in JDK 20. Structured concurrency
entered in JDK **19** — a release *earlier*. Which one finalised first? Check
in tab 2, then explain the result.

**3.5** You add `--enable-preview` to your build to try something, then decide
against it and remove the preview code — but you leave the flag in the build
file. Does anything break? Check in tab 3 (set "My code uses" to *Only final
features* and "Compiled with" to the flag).

---

## Rung 4 — Modify

**4.1** Suppose preview features were marked only by a compiler flag, with
nothing written into the class file. Walk through what goes wrong, using a
concrete story: a developer, a jar, an artifact repository, and a JDK upgrade
six months later.

**4.2** Suppose Java had kept the LTS gap at three years instead of moving to
two. Using the JEP counts in tab 1, work out how many changes an 17→23 jump
would have swallowed, versus the real 17→21 and 21→25 jumps. What does the
difference do to a team's willingness to upgrade?

**4.3** Suppose the release train ran every **three** months instead of six.
Name one thing that gets better and two things that get worse. (Hint: one of
the two involves the number of releases a vendor must support, and the other
involves how long a feature takes to gather feedback.)

**4.4** Suppose JEP 400 (UTF-8 by default) had thrown an exception on any
ambiguous decode rather than changing silently. Would that have been better?
Argue both sides, then commit to an answer.

---

## Rung 5 — Trace

Work these by hand, then verify.

**5.1** Trace switch expressions from first appearance to final: which release,
which JEP, which state, at each step — and name the one thing that changed
between the first and second preview, and why.

**5.2** Trace the Foreign Function & Memory API from JDK 14 to JDK 22. There
are nine steps. Two of them are unusual: one is a *merge* of two separate
efforts, and one is a *graduation* that broke every early adopter's code. Name
both, with their releases.

**5.3** You are handed a class file starting with these bytes. For each, say
which JDK compiled it and whether it will run on JDK 25 with no flags:

```
(a) ca fe ba be 00 00 00 34
(b) ca fe ba be 00 00 00 41
(c) ca fe ba be ff ff 00 45
(d) ca fe ba be 00 00 00 46
```

**5.4** Trace the strong-encapsulation ladder from JDK 9 to JDK 17. At each
release, say what happened when code called `setAccessible(true)` on a private
field of `java.lang.String`. There are three distinct behaviours across five
releases.

---

## Rung 6 — Spot the flaw

Each of these is a real position someone has argued. Find the error.

**6.1** *"We use a preview feature in our shared internal library. It's fine —
every team in the company is on the same JDK, and we control all of them."*

Construct the concrete sequence of events that breaks this. Name the guarantee
being violated.

**6.2** *"Our upgrade plan is: compile on the new JDK, boot the service, smoke
test the health endpoint. If all three pass, we ship."*

Name a hazard from tab 4 that passes all three checks and still reaches
customers. Explain the mechanism.

**6.3** *"The Vector API has been incubating for ten releases. That team is
clearly not delivering — we should not plan around it ever landing."*

The conclusion may or may not be right, but the reasoning is wrong. Say why,
and say what evidence would actually support or refute the conclusion.

**6.4** *"We're on Java 8 and we're fine — Oracle supports it until December
2030, so there's no urgency."*

Two things are wrong here. Find both. (One is about what "support" covers; one
is about a cost that has nothing to do with support dates.)

**6.5** *"We set `--add-opens java.base/java.lang=ALL-UNNAMED` in
`JAVA_TOOL_OPTIONS` on all our containers. That solved the JDK 17 encapsulation
problem."*

Explain why this is a worse outcome than it appears, and what it has actually
bought and cost.

---

## Rung 7 — Handle failure

Design work, on paper.

**7.1** Your team runs 40 microservices on JDK 11. You have been asked to get
them all to 25 within two quarters. Design the plan. It must specify: the
route, what runs in CI and when, the order in which things are upgraded, how
you decide a service is ready, and what you do with the service that turns out
to depend on a library whose maintainer disappeared in 2019.

**7.2** Design a policy that lets your organisation use preview features
without ever being burned by one. It must answer: who may use them, in what
kind of artifact, what must be true of the build, and what happens on the day a
previewed feature is withdrawn.

**7.3** Your service is on JDK 21 and a silent behaviour change is suspected
after a move to 25 — a report has wrong characters in customer names. Design
the diagnosis: what you check, in what order, and what single test you add
afterwards so this class of problem cannot recur.

**7.4** JEP 500 ("Prepare to Make Final Mean Final", JDK 26) will begin
restricting reflective writes to `final` fields. Some serialisation and
dependency-injection frameworks do this. Design your organisation's response
*now*, before it becomes an error — including how you find out whether you are
affected at all.

---

## Rung 8 — Design

Open-ended. The answer is a reasoned position, not a single output.

**8.1** You are the platform lead for a company with 200 Java services across
30 teams, currently spread across JDK 8, 11, 17 and 21. Design the JDK version
policy. Cover: which versions are permitted, who decides, how new versions are
introduced, how old ones are retired, what is automated, what you monitor, and
how you stop the ratchet from restarting. Then name the two ways your policy
most likely fails in practice.

**8.2** You maintain a widely-used open-source Java library. Design your
version support policy: what is your minimum JDK, when do you raise it, how do
you use multi-release jars (if at all), and how do you handle the fact that
your users are on everything from 8 to 26. Justify each choice against a
specific cost.

**8.3** Java's preview mechanism costs real money: every previewed feature is
implemented, shipped, maintained, and sometimes thrown away entirely. Argue
whether it is worth it. Your argument must engage with the strongest case
*against* — that string templates represent years of wasted engineering — and
must reference at least two specific features from this lab.

**8.4** Design a preview/deprecation mechanism for a platform you actually work
on — a shared internal library, a service API, a database schema. What would
"preview" mean there? What plays the role of the class-file version stamp,
i.e. what physically prevents an impermanent thing from becoming load-bearing?
If nothing can play that role, what do you do instead?
