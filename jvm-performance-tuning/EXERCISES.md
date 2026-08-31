# JVM Performance Tuning — Exercises

Eight rungs, easiest first. Everything here is pencil-and-paper or done in
**[The Bottleneck Machine](https://claude.ai/code/artifact/a12d63c9-7eaf-4596-9171-a92a41b28044)**
(`visualization.html` beside this file). Nothing asks you to write code.

Answers are in `SOLUTIONS.md`. Attempt first — especially rungs 3 and 6, where
being wrong is the whole point. Rungs 7 and 8 have worked discussions rather
than single right answers.

Where an exercise says **[viz]**, do it on the page. Where it says **[paper]**,
you should be able to do it without opening anything.

---

## Rung 1 — Explain

**1.1** [paper] In your own words, in no more than four sentences and using no
notation: what is performance tuning, and why is "add some JVM flags" usually
the wrong first move?

**1.2** [paper] Explain to a colleague who has never thought about it why the
**average** response time is a bad number to alert on, and what you would alert
on instead. Use the sandwich shop or an example of your own — no formulas.

**1.3** [paper] There is one idea in this lab that makes everything else
follow. State it in one sentence, then say what it implies about a proposal to
"add more memory and see if it helps".

**1.4** [paper] Why can a CPU profiler be running perfectly, reporting perfectly
accurate numbers, and still be describing less than 5% of your latency problem?

---

## Rung 2 — Identify

**2.1** [viz] Open the page on its default settings and let it run for a few
model seconds. Which of the seven buckets is widest, and how many milliseconds
is it? What is the median and the p99?

**2.2** [viz] Press **Slowest 5%**. Which bucket is widest now? Write down the
one-sentence difference between what makes a typical request take its time and
what makes a slow request take its time on these settings.

**2.3** [viz] On the resource sheet, find the row that tells you whether the
thread pool is the constraint. There are two numbers on it — what does each
one mean, and which combination of the two would tell you the pool is too
small?

**2.4** [viz] Click any coloured square in the thread grid. Read the sentence
underneath. Which phase is that thread in, and how much of its request's life
so far has been spent off-CPU?

**2.5** [viz] Set the collector to **Serial** and *Long-lived data kept in the
heap* to 1,200 MB. Where on the page do you find (a) how often a collection
happens, (b) how long the worst one was, and (c) what share of the clock has
been spent frozen?

---

## Rung 3 — Predict

For each, write down your prediction **and your reasoning** before you check.

**3.1** [viz] Load scenario 1 (*The heap upgrade that changed nothing*), but do
not press an answer yet. The p99 is about 1,327 ms and the CPUs are 11% busy.
Predict what happens to p99 if instead of the heap you **double the number of
handler threads**. Then set it and look.

**3.2** [viz] Defaults, but set *Waiting on the database* to 0 ms and *Work per
request* to 40 ms. Predict the new bottleneck bucket and roughly the new p99,
then check.

**3.3** [paper, then viz] A service handles 200 requests a second. Each request
occupies a handler thread for 43 ms. Using Little's Law, how many threads must
be busy on average? Now predict what happens to throughput at pool sizes of 8,
10, 16 and 24 — and in particular, predict whether 24 beats 16. Check on the
page.

**3.4** [viz] Defaults. Predict what happens to p99 if you raise *Garbage made
per request* from 128 KB to 1 MB (an 8× increase) while changing nothing else.
Then predict what happens if you *also* raise the heap 8×. Check both.

**3.5** [paper] A program has a serial fraction of 10%. Someone proposes moving
it from a 16-core machine to a 128-core machine. Before reading anything else:
what is the largest speed-up that could possibly result, and what does that
imply about the proposal?

---

## Rung 4 — Modify

**4.1** [viz] Find a set of knob positions where **"code not yet compiled"** is
the widest bucket. What did you have to do, and why does it not happen on the
default settings?

**4.2** [viz] Starting from the defaults, make **"waiting for a lock"** the
widest bucket **without** touching the arrival rate, the pool size, or the core
count. Which single knob did it, and what does that tell you about which
resource a lock actually is?

**4.3** [viz] Set cores to 16 and the container limit to 2. Now try to recover
p99 to under 300 ms by changing anything *except* the container limit. Report
what you managed and what you had to give up. What does this tell you about the
class of problem that tuning can and cannot solve?

**4.4** [viz] Set *Cross-talk between threads* to 0.00%. Now repeat the pool
sweep from stage 4 of the README (8 → 128 threads on 8 cores, no database call,
20 ms of work, 450 requests/second offered). What survives of the retrograde
effect, and what disappears? Which of the two mechanisms in the model does the
remainder come from — and is it safety or liveness that is at risk?

**4.5** [viz, tab 2] Set the serial share to 0% and the cross-talk to 0.000%.
What shape is the curve, and what does that tell you about what each of the two
terms is actually responsible for? Now raise only the cross-talk. Now only the
serial share. Describe the difference in one sentence each.

---

## Rung 5 — Trace

**5.1** [paper] A JVM has just started. A request arrives whose handler needs
**24 ms** of CPU when fully compiled, plus a **40 ms** database call. Using the
model's rules (interpreted = 5.0×, C1 after 300 calls = 1.9×, C2 after 3,000
calls = 1.0×), work out by hand:

- the elapsed time for request #1;
- the elapsed time for request #500;
- the elapsed time for request #5,000;
- for request #1, the split between the *running your code* and *code not yet
  compiled* buckets. Show that your buckets sum to the elapsed time.

**5.2** [paper] A service allocates **256 KB** per request and handles **300**
requests a second, on a **1 GB** heap. The model's allocation area is 30% of
the heap, and every 8th collection is an old-generation one. Work out:

- the allocation rate in MB/s;
- how often a young collection happens;
- how often an old-generation collection happens;
- and then: if you halve the allocation per request, what happens to each of
  those three numbers?

**5.3** [paper] Using the model's Serial collector rule — old-generation pause
= `14.0 + 0.42 × retained MB`, plus time-to-safepoint — compute the pause for a
service retaining 800 MB with a 2 ms time-to-safepoint. Then compute it for
ZGC (`0.6 + 0.004 × retained MB`). Now compute both again with a
time-to-safepoint of 150 ms, and say in one sentence what that does to the
argument for choosing ZGC.

**5.4** [paper] Amdahl: a program is 92% parallelisable. Compute the speed-up at
1, 4, 16, 64 and 1,024 threads, and the ceiling. Then add the USL's coherence
term at `κ = 0.0005` and compute the peak thread count and the speed-up there.
How many threads is the *last* one that still improves things?

---

## Rung 6 — Spot the flaw

Each of these is a described design or a described piece of reasoning with a
real mistake in it. Find it, construct a concrete case where it produces a wrong
answer or a wrong decision, and name what has been violated.

**6.1** A team adds this to their service: a `Timer` around the body of every
controller method, exported as a histogram, with an alert on p99 > 500 ms. They
say: "we now have full visibility of our latency." Construct a scenario in which
this alert stays green for an hour while every user experiences four-second
waits, and say precisely which time is being excluded.

**6.2** A performance report says: *"We profiled the service under load. 61% of
CPU samples were in `PriceCalculator.compute`. We optimised it and cut its cost
in half. Expected improvement: ~30% end-to-end."* State the assumption hidden in
that last sentence, and describe a service where the actual improvement would be
under 2%.

**6.3** A benchmark harness runs `for (int i = 0; i < 10_000_000; i++)
service.hash(input);` and reports 0.4 ns per call. The team concludes the method
is free and stops optimising. Name three separate mechanisms, any one of which
could produce that number without the method being fast, and say what each would
require to rule out.

**6.4** A runbook says: *"If p99 is high, check the GC log. If pauses are under
10 ms, garbage collection is not the problem, so move on."* Construct a concrete
case where the GC log shows only sub-10 ms pauses and the collector-related
machinery is nonetheless responsible for 180 ms freezes. Which log should the
runbook have named?

**6.5** A team sets `-XX:MaxGCPauseMillis=5` and `-Xmx` equal to their
container's memory limit, and reports: *"pause target met, and we're using all
the memory we paid for."* Describe two distinct failures this configuration is
now set up for, and say which one produces no stack trace at all.

**6.6** A capacity model says: *"Each request takes 43 ms. We need 200 requests
per second. 200 × 0.043 = 8.6, so 9 threads."* Little's Law is applied
correctly. Explain why the service will nonetheless fail to reach 200/s, and
state the general rule for turning Little's Law into a pool size.

---

## Rung 7 — Handle failure

Extend a design on paper so that it survives something it currently does not.

**7.1** Your service has a bounded queue and starts refusing requests under
overload — which is correct. But clients retry on failure, twice, with no delay.
Describe what happens to offered load, and design the smallest change on the
*server* side that keeps the system recoverable. Then describe the smallest
change on the *client* side. Which of the two would you do first, and why?
(Your `queueing-theory` lab measured the failure this prevents.)

**7.2** Deployments cause a 60-second window of elevated p99 on every new
instance, occasionally bad enough that the instance saturates and never
recovers. Design the deployment process so that (a) the instance is never given
more traffic than it can currently handle, (b) the rollout does not take an
hour, and (c) a genuinely broken build is still detected quickly. What signal
do you use to decide an instance is ready?

**7.3** Your p99 is dominated by collector pauses that happen a few times an
hour and freeze everything for 400 ms. You cannot reduce allocation in the time
available. Design a change that makes those 400 ms invisible to *most* users
without making the pauses shorter. What are you trading, and what breaks if the
pause rate doubles?

**7.4** A downstream service you call has a p99 of 2 seconds and a p50 of 20 ms.
Your own SLA is 300 ms at p99. You cannot change the downstream service.
Enumerate the options available to you, in order of how much they cost to build,
and say what each one gives up. (One of them makes the situation worse and it
is worth naming.)

---

## Rung 8 — Design

Full production design prompts. Write a page each; there is no single right
answer, but there are answers that ignore something important.

**8.1 — The observability you would actually build.** You have inherited a
payments service: Spring Boot, JDK 21, running in Kubernetes, 40 pods, 3,000
requests/second, p99 SLA of 400 ms, and no useful telemetry beyond CPU and
memory graphs. You have two weeks.

Design what you would put in place. Be specific about: which metrics (and at
which point in the request's life each one is measured), which logs are on
permanently and which are on-demand, what triggers a profile to be captured,
what the alerts fire on, and what a person paged at 3am reads first, second and
third. For each item, say **which of the seven buckets it would let you rule in
or out** — anything that rules nothing out does not go on the list.

**8.2 — The capacity and pool design.** Same service. Each request does 12 ms of
CPU work and makes three sequential downstream calls averaging 30 ms each. You
have 4 CPUs per pod.

Work out: the thread pool size, the queue capacity, the timeout, and the number
of pods — showing the arithmetic and stating which formula gives you each one.
Then answer the harder questions: what happens to every one of those numbers if
the downstream latency triples? Which of them re-derives itself and which are
now wrong? And would you build this on platform threads or virtual threads —
what specifically changes in your answer, and what specifically does not?

**8.3 — The tuning proposal that should be rejected.** A colleague proposes:
*"Switch everything to ZGC, raise all heaps to 8 GB, set `MaxGCPauseMillis=5`,
and triple every thread pool. It worked on the ingestion service."*

Write the reply you would actually send. For each of the four changes, say what
it would do, under what circumstances it would be right, and what evidence you
would want before agreeing. Then propose what to do instead, in order, and say
what you expect each step to move and by roughly how much. (Aim this at a
colleague, not at an audience — the goal is a decision, not a lecture.)

**8.4 — Three datacentres.** The same payments service now runs in three
regions, 50 ms apart, with a database that replicates across them. Which parts
of your answers to 8.1 and 8.2 survive unchanged, which must change, and what
new bucket would you need to add to the seven in order to diagnose this system?
Name the labs you already have that cover the new failure modes.
