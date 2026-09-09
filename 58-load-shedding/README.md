# Load Shedding — choosing who to disappoint, before the choice is made for you

> **Tell a friend:** Dana can finish 15 passport applications an hour, and this
> morning 60 people walk in, each with an 11:00 train to catch. She works flat
> out all morning and about one person catches their train, because she spends
> the rest of it filling in forms for people who already left. Put Ben on the
> door with a clipboard, turning people away once the waiting room is full, and
> the same Dana at the same speed sends home roughly five hundred times as many
> passports. Your service is Dana's desk: offered 60 requests a second when it
> can finish 50, it serves 0.7% of them, not 83%, while its throughput graph
> reads a flat and healthy 50/s. One line of admission control turns that 0.7%
> into 83.6%, and this lab is about which line, and where it goes.

Overload the office yourself in [`visualization.html`](visualization.html), also
published as [The Overload Desk](https://claude.ai/code/artifact/175a445a-1314-4caa-b2f8-71cd11134949).
What this lab is made of, and where every number came from, is stage 17.

---

# BEGINNER MODEL — stages 1 to 6

## 1. Why does an outage start when nothing has broken?

> **In one breath:** More work arrived than the service could finish. No
> component failed, no alert fired, and out of every thousand customers, one got
> an answer.

It is 09:58 and Meera is on call. Her orders service is healthy. It has four
worker threads. Each request takes about 80 ms, so the service finishes about
**50 requests per second**. Traffic is 35/s. Median response 82 ms. No errors.

At 10:00 a promotion goes live and traffic goes to 150/s.

Nothing crashes. No process dies, no disk fills, no dependency returns an error,
no deploy went out. Every worker thread stays busy. The throughput graph, the
one on the wall that the alert is wired to, goes to **50 requests per second and
stays there, flat and level, for forty minutes**.

And during those forty minutes, out of every thousand customers who press the
button, **one** gets an answer.

Before the whole incident, stop on the least dramatic row of it. Traffic has not
tripled yet. It has gone up by a fifth.

> **🔮 Predict:** *The service can finish 50 requests a second. Offer it 60 a
> second — one fifth more work than it can do, sustained. What share of those
> customers get an answer: about 83%, about half, or something else?*
> <details><summary>Reveal</summary>
>
> **0.7%.**
>
> If you said 83%, you did the arithmetic everyone does, and the arithmetic is
> right about the *work*: 50 of every 60 requests can be finished, so 10 are
> lost. It is wrong about *which* 50, because the ten that cannot be finished do
> not politely leave. They join the queue, wait, and push everybody behind them
> past the point where the answer is still wanted. The service finishes 50
> requests a second all morning. Almost none of them belong to a customer who is
> still listening.
>
> That is a 20% overload producing a 99.3% failure. Stage 6 puts a doorman in
> front of the same service at the same 60/s, and the number becomes **83.6%**.
> </details>

Here is the whole incident in one table. Read the third column against the
second.

| offered load | what the throughput graph shows | customers who got an answer | what the row means |
|---|---|---|---|
| 35/s (normal) | 34.9/s | **100.0%** | spare capacity; everyone served |
| 45/s | 44.9/s | 99.3% | 90% of capacity, still comfortable |
| 50/s (exactly capacity) | 50.0/s | **unstable — see below** | not an operating point at all |
| 55/s | 50.1/s | **1.2%** | 10% too much work, almost total loss |
| 60/s | 50.0/s | **0.7%** | a fifth too much work, not a fifth of the loss |
| 150/s | 50.1/s | **0.1%** | the promotion; one in a thousand |

Three things in that table are worth stopping on.

**The throughput column is useless.** It reads 50/s during total success and
50/s during total failure. It is the single most-watched number in most
production dashboards, and here it cannot distinguish a healthy service from a
dead one.

**The collapse is not proportional to the overload.** Going 20% over capacity
does not cost you 20% of your customers. It costs you 99.3% of them.

**There is no bug to find.** The service is doing exactly what it was built to
do, as fast as it can, on every single request. That is the problem.

And one row of that table refuses to hold still. Offer the service exactly what
it can do, 50 a second against a capacity of 50 a second, and the same
configuration gives a different answer every time you run it.

| exactly at capacity: twenty runs of one configuration | figure | what it means |
|---|---|---|
| best run of the twenty | **70.6%** | a good day, decided by luck |
| worst run of the twenty | **1.1%** | the same settings, the same load |
| mean over ten minutes | 28.6% | the average is not a promise |
| mean over one hour | **11.7%** | the longer it runs, the worse it gets |

That spread is not noise in the measurement. It *is* the answer. Your
`queueing-theory` lab named the ratio of arriving work to work you can finish —
busy-ness, written ρ. At ρ = 1, arrivals and departures balance exactly, so the
queue is a random walk with nothing pulling it back down. A random walk has no
steady state to settle into. Whether you are having a good day is decided by
luck, and the longer the day lasts the worse it gets. Every other figure in this
lab is a stable mean over five runs. This one cannot be, and that is the point:
**"we are running at exactly 100% of capacity" is not an operating point. It is
a coin toss that gets worse with time.**

What goes wrong if you do not know this? You spend the incident looking for a
component that failed, and there isn't one. You add capacity, which helps, and
you conclude the answer was capacity. So it happens again next quarter, at a
different service, at a different scale. Meanwhile the thing that would have
turned that 0.1% into 33% was a change you could have shipped in an afternoon,
and it has been available the entire time.

## 2. What happens at Dana's desk when everyone has a train to catch?

> **In one breath:** One clerk, sixty people, one train. She works the whole
> morning without a break and almost nobody gets a passport.

Forget servers for a moment. You met **Dana** in your `queueing-theory` lab, at
the only desk in a passport office. Today she is on the long form, and we are
going to break her morning differently.

Each application takes Dana exactly **4 minutes**. She is fast, accurate, and
never stops. That is her capacity: **15 people an hour**. It is a fact about
Dana, and no decision anyone makes today will change it.

This morning, because of a deadline in the news, **60 people** arrive in the
first hour. Four times what Dana can do. **Priya** is one of them, and she is
somewhere in the middle of the crowd.

There is one more fact, and it is the one that matters most. **Every person here
has a train to catch at 11:00.** If Priya is still in the queue at 11:00, she
leaves. She walks out with nothing — no passport, no partial passport, nothing.
Her hour in your building bought her exactly zero.

That deadline is not a detail. It is the entire subject. A queue without a
deadline is a waiting room. A queue with one is a **countdown on every person in
it**, and the countdown runs whether or not Dana is working hard.

> **🔮 Predict:** *Dana works flat out for the full hour with no breaks and no
> mistakes. She physically processes 15 applications. Of the 60 people who
> walked in, how many walk out with a passport?*
> <details><summary>Reveal</summary>
>
> **About one.**
>
> Not fifteen. Dana *processes* fifteen applications, but she processes them in
> the order people arrived. By the time she reaches the fifth person that person
> has been waiting 16 minutes. By the fifteenth they have been waiting nearly an
> hour, and they left for the train some time ago. Dana spends most of her
> morning filling in forms for people who are no longer in the building.
>
> The service at 3× capacity does the same thing: **0.1% success, 100%
> utilisation, and 100% of its working time spent on requests whose client had
> already given up.**
>
> Hold on to the shape of that: *she did all the work, and almost none of it
> counted.* Stage 4 walks through exactly how the count gets to one.
> </details>

**Where this lab takes you.** Stop at any checkpoint and you still have
something whole.

| Layer | Stages | After it you can… |
|---|---|---|
| Beginner | 1–6 | say why a fifth too much work serves 0.7%, and what a doorman does about it |
| Intermediate | 7–10 | build the doorman, choose what it looks at, and defend the number you picked |
| Engineering | 11–12 | name eight ways it goes wrong, and say what you are actually guaranteed |
| Production | 13–16 | ship it in the right order, and put the right things on the dashboard |

## 3. What is wrong with letting everyone in?

> **In one breath:** The office manager's plan is four decent-sounding rules.
> Your web framework already ships all four as defaults, and together they are
> the outage.

Before we fix anything, let us be fair to the design that fails. It is the
design almost every system ships with — not through carelessness, but because it
is the *decent* thing to do.

**Arjun** manages the office. You met him in `queueing-theory` writing capacity
on a whiteboard. Here is his plan for the morning:

1. Let everyone in. Turning people away when the desk is *right there* and Dana
   is *willing to work* feels like a failure of service.
2. Serve them in the order they arrived. That is fair, and everyone understands
   it.
3. Keep the queue in the corridor if the waiting room fills. Space is cheap.
4. Ask Dana to hurry.

Every one of those four is defensible on its own. Three of them are things you
would say out loud in a meeting without anyone objecting.

Here is what Arjun's plan looks like in a service, and you have almost certainly
shipped this configuration:

```
// A thread pool with an unbounded queue: "let everyone in"
new ThreadPoolExecutor(4, 4, 0L, MILLISECONDS, new LinkedBlockingQueue<>());

// Spring Boot's default task queue capacity
spring.task.execution.pool.queue-capacity = 2147483647
```

*(Illustrative, not runnable. `LinkedBlockingQueue` with no capacity argument is
unbounded, and that Spring default is `Integer.MAX_VALUE`. Both are step 3 of
Arjun's plan, shipped as a default.)*

Nobody chose this. It is what you get by not choosing.

## 4. Where does the morning's work actually go?

> **In one breath:** Dana does every minute of work she is capable of. After
> 10:20, not one of those minutes belongs to somebody still in the building.

Let us watch the morning minute by minute. Dana opens at 10:00. Sixty people
arrive spread over the hour — call it one per minute. Everyone leaves at 11:00.
Read the third column: it is how long that person has been in the building when
Dana finally calls them.

| clock | who Dana is serving | how long they have waited | what happens |
|---|---|---|---|
| 10:00 | person 1 | 0 min | ✅ served, catches the train |
| 10:04 | person 2 | 3 min | ✅ served |
| 10:08 | person 3 | 6 min | ✅ served |
| 10:20 | person 6 | 15 min | ✅ served |
| 10:40 | person 11 | 30 min | ✅ served |
| 10:56 | person 15 | 42 min | ✅ served — the last one who makes it |
| 11:00 | — | — | **the other 45 people walk out** |

So far so good. Dana served 15 and 45 left. That is bad, but it is the honest
arithmetic of 60 people and a 15-person capacity. **Nothing is surprising yet.**

Now change one thing to match reality, and the floor falls out.

Real queues are not one-per-minute. People arrive in clumps. And crucially, **in
a real service the deadline belongs to the client, not to the server.** Priya's
phone gives up after one second and shows her an error. *The server never finds
out.* Her request is still sitting in the queue. Its work still gets done. Its
response is still written, to a connection nobody is listening on.

So replace "people leave the building" with what actually happens: **Priya
leaves for her train, and Dana still fills in her form.** Dana has no way to
know the chair is empty.

Now walk the morning again. By 10:20 the queue is 20 deep and growing by 45
people an hour. Anyone joining at 10:20 will not reach the desk until well after
11:00. So from 10:20 onward, **every single person who joins the queue is
guaranteed to have gone before Dana reaches them.** Dana keeps working at
exactly 15 applications an hour, all morning. After 10:20 not one of those
applications belongs to a person who is still in the building.

The service does the same thing, and it is worth seeing the exact numbers. This
is one run at 3× capacity with nobody on the door.

| what you would watch | the run at 3× capacity | what the number means |
|---|---|---|
| offered | 150 requests/s | what arrived at the door |
| **throughput** | **50.1/s** — full capacity, all day | requests finished per second |
| **goodput** | **0.1/s** | finished *in time to matter* |
| worker time spent on requests whose client had gone | **100%** | Dana's whole morning |
| utilisation | 100% | nobody was idle for a moment |
| queue depth after 10 minutes | **60,047** | the corridor, in your heap |
| median latency **of the requests that succeeded** | 532 ms | a perfectly healthy-looking number |

Read the last row twice. During a total outage, the latency of the requests that
*did* succeed looks fine. The only ones that succeed are the lucky few that
arrived when the queue happened to be short. Your latency dashboard is computed
over survivors. **A metric computed only over survivors cannot detect that
everyone else died.**

And here is the part that makes this an emergency rather than a curiosity: **that
queue of 60,047 is a linked list in your heap.** It grows at 100 entries per
second, each holding a request object, its headers, its body, its trace context.
The outage that started as "slow" ends as an `OutOfMemoryError`, a restart, and a
cold cache. At which point the still-overloaded traffic hits an empty cache and
the whole thing happens again, faster.

> **This is the moment the lesson turns.** Everything up to here is a
> restatement of what `queueing-theory` proved. What follows is the response.

## 5. What are we actually asking for, in plain words?

> **In one breath:** At four times the work she can do, Dana was always going to
> disappoint most of the room. The question is which people, and how long they
> stand there before they find out.

Look at what actually went wrong. It was not that requests were lost. At 150/s
against a capacity of 50/s, **100 requests per second were always going to be
lost.** That is arithmetic, not a design flaw. No configuration, no rewrite, no
language change makes 4 workers finish 150 requests a second.

What went wrong is *which* ones were lost, and *when* they found out.

Which: the system lost almost all of them, including the ones it had capacity to
serve. Every request was allowed to consume a share of a resource that none of
them could get enough of.

When: Priya found out after a full second of waiting. She had occupied memory
and a connection the whole time, and in many designs her phone immediately tried
again.

So the requirement is this:

> **When more work arrives than we can finish, decide *at the door* who is not
> getting served, and tell them *immediately*. Then spend everything we have
> left on the ones we said yes to, so that the yeses actually complete in
> time.**

Three separate demands hide in that sentence, and the rest of the lesson is
three mechanisms answering them.

**At the door.** The decision must happen *before* the request consumes a
worker, a connection, or a megabyte. A rejection that costs as much as the work
is not a rejection.

**Immediately.** Priya must learn quickly. A client that is still waiting is
still holding resources on *both* sides. And a fast "no" is something she can
act on (fall back, degrade, try elsewhere), while a slow "no" is not.

**The yeses complete.** Admitting fewer requests is only worth doing if the
admitted ones then succeed. Which means the number you admit must be tied to
what you can actually finish, and that number changes.

Now we can name the metric the throughput graph was missing. **Throughput** is
how much work you finish per second. **Goodput** is how much work you finish per
second *that somebody still wanted*. In stage 4's table they were 50.1/s and
0.1/s. Throughput is a fact about your CPU. **Goodput is a fact about your
business**, and it is the only one of the two that can tell a healthy service
from a dead one.

## 6. What does putting one doorman on the door do to the numbers?

> **In one breath:** Ben stands at the door and turns people away once the
> waiting room is full. Dana works no faster. Roughly five hundred times as many
> people catch their train.

Here is the whole idea, and it is genuinely this small:

> **Before a request is allowed to join the queue, something looks at how backed
> up we already are and says yes or no. A no is returned instantly and costs
> almost nothing.**

That is load shedding. The thing making the decision is called an **admission
controller**. In the office, it is **Ben**, with a clipboard, standing at the
door.

The counterintuitive part, the reason this is a lesson and not a footnote, is
what Ben does to the numbers.

Same office. Same Dana. Same 4-minute applications. Same 60 people at the door.
Now Ben turns people away whenever the waiting room already holds more than a
handful.

| 3× overload, capacity 50/s, best achievable = 33.3% | goodput | success rate | queue depth |
|---|---|---|---|
| no admission control | 0.1/s | **0.1%** | 60,047 |
| queue capped at 20 | 50.0/s | **33.3%** | 20 |

**Turning away two-thirds of the arrivals increased the number of people served
by roughly five hundred times.**

And look at the right-hand column of that table. 33.3% is not an improvement on
the way to something better. 50 requests per second is what the service can
finish, 150 are arriving, so **33.3% is the mathematical ceiling**. A
twenty-slot queue reaches it exactly. There is nothing left on the table.

That is the first counterintuitive result. Here is the second, which is the one
that changes how you feel about shipping this. Read the two right-hand columns
against each other at every level of traffic, including the ordinary ones.

| offered load | no shedding | queue capped at 20 | what the row settles |
|---|---|---|---|
| 35/s (normal) | 100.0% | **100.0%** | the doorman costs nothing when it is quiet |
| 45/s | 99.3% | 98.9% | at 90% of capacity, four tenths of a percent |
| **60/s (a fifth too much)** | **0.7%** | **83.6%** | stage 1's headline, answered |
| 150/s (3×) | 0.1% | **33.3%** | the ceiling, reached exactly |

The 60/s row is the one to remember. The arithmetic ceiling there is 83.3%,
because 50 of every 60 requests can be finished. The capped queue lands on
83.6%, which is that ceiling plus the ordinary scatter between runs. The
unprotected service, same hardware, same traffic, gives 0.7%.

*(50/s is left out of that table on purpose — see stage 1: at exactly capacity
the unshielded number is not a number. The capped column is stable there at
around 96%, which is itself the point: **admission control gives ρ = 1 a defined
behaviour it does not otherwise have.**)*

**Ben costs nothing when the office is not busy.** He is not a big red button
you press during an incident. He stands there every day, waves everyone through
every day, and the one morning in two years when he is needed, he is already at
his post and already calibrated. A shedder that only gets switched on during
incidents is a shedder that has never been tested, configured by people under
pressure, on the worst day of their quarter.

> **Say it back.** Without using the words *shed*, *goodput* or *admission*,
> explain to Arjun why turning away two-thirds of the people at the door means
> *more* people go home with a passport.
> <details><summary>A good answer sounds like</summary>
>
> "Dana can do fifteen an hour and sixty are coming, so forty-five of them are
> going home empty no matter what we do. The only thing we choose is when they
> find out. If we let them all queue, they all wait. The ones at the front have
> already left for the train by the time Dana calls them, so she fills in forms
> for empty chairs and almost nobody gets served. If we tell forty-five of them
> at the door, they lose two seconds instead of an hour. And everyone still in
> the room is close enough to the front that Dana's work reaches a real person."
> </details>

> **Checkpoint.** You can now explain why load shedding exists, why it
> *increases* the number of requests served, and why goodput is the metric that
> shows it. What you cannot yet do is build one — because "how backed up are we"
> turns out to be a genuinely hard question with at least four defensible
> answers, three of which break in production. That is the next layer.

---

# INTERMEDIATE MODEL — stages 7 to 10

## 7. Which five words are being used as if they were one?

> **In one breath:** Five different mechanisms all end with somebody not being
> served. They are not interchangeable, and the difference is the whole design.

Your `distributed-rate-limiting` lab put *"rate limit vs quota vs throttling vs
load shedding"* in a single glossary line. That line was hiding the most
important distinction in this subject, so let us pull it apart. The question
that separates them is: **whose interest is it protecting, and what does it
know?**

**Rate limiting** protects *you* from a *particular client*. Ben checks a list:
has this travel agent already brought in 1,000 people this month? That is a
question about identity and a promise, answered the same way whether the office
is empty or on fire. It is a **contract**, and its threshold is a business
decision. It cannot save you from overload, because overload usually arrives as
a million clients each behaving perfectly.

**Quota** is the same thing over a longer window and usually with money
attached.

**Throttling** is the general verb for slowing a client down — usually rate
limiting's enforcement half.

**Load shedding** protects *the service* from *all clients at once*. Ben looks
over his shoulder at the waiting room and asks: can Dana finish what she already
has? That question is about your own internal state, its answer changes second
by second, and it has nothing to do with who is asking. Priya is a perfectly
well-behaved client and Priya gets shed. That feels unfair, and it is. It is
also the only thing that works, because the alternative is that everyone gets
nothing.

> Rate limiting asks **"are you allowed?"**  Load shedding asks **"can I?"**
>
> A system can have both. It needs both. They fire at different times, for
> different reasons, and a rate limiter tuned tight enough to prevent overload
> would have to be so tight that it rejects your legitimate traffic every day.

**Backpressure** is the fourth, and it is shedding's opposite number. It means
**refusing to accept work and letting the pressure propagate backwards** to
whoever is producing it. TCP's receive window, a bounded queue that blocks its
producer, Kafka consumer lag. It is the better answer *when it is available*,
because nothing is thrown away; the producer simply slows down. It is available
when the producer is something that can wait. It is *not* available when the
producer is forty thousand phones that will each retry in two seconds.
**Shedding is what you do when there is nowhere to push back to.**

**Admission control** is the neutral, precise name for the machinery: the
component that decides yes or no at the entrance. Load shedding is what it is
called when it says no because you are overloaded.

Here are the five side by side. The last column is where you have already met
the thing.

| mechanism | protects | the question it asks | fires when | first seen |
|---|---|---|---|---|
| rate limiting | you, from one client | are you allowed? | a contract is exceeded | `distributed-rate-limiting` |
| quota | the same, over a longer window | have you paid for this much? | a billing period fills | `distributed-rate-limiting` |
| throttling | the enforcement half of both | how do I slow you down? | the limiter says no | `distributed-rate-limiting` |
| load shedding | the service, from everyone | can I finish what I have? | your own queue backs up | stage 6, Ben at the door |
| backpressure | the whole chain, losing nothing | can the producer wait? | the producer *can* be slowed | `queueing-theory` |

Four more terms you now need, each introduced because you have already felt the
thing it names.

**Goodput** — work completed *in time to be useful*, as opposed to throughput,
which is work completed. Stage 4's 50.1/s versus 0.1/s.

**Offered load** — what arrives, as opposed to what you serve. In stage 4 the
offered load was 150/s while throughput was 50/s. The difference is not visible
anywhere on a server-side graph unless you deliberately count rejections.

**Overload** — the state where offered load exceeds the rate at which you can
finish work. Note it is defined against your *completion* rate, not your CPU. A
service can be 100% overloaded at 3% CPU if its workers are blocked waiting on a
slow database. This matters enormously in stage 11.

**Deadline** — how long an answer is still worth having. Usually the client's
timeout. Priya's 11:00 train. Almost always known to the client and unknown to
the server, which is the single largest missing piece of information in this
entire subject, and the reason stage 13 spends time on deadline propagation.

## 8. Which two pictures of the doorman should you carry?

> **In one breath:** One picture is Ben glancing at the waiting room. The other
> is a thermostat. They are the same machine, and the second one tells you what
> to change.

### The simple picture: what does Ben actually look at?

One door, one waiting room, one desk. Ben does not know anything about who is
arriving, why, or how important they are. He knows one thing: **how many people
are already inside waiting.** If it is more than some number, the next person is
told "not today" before they sit down, and they are told it in two seconds
rather than fifty minutes.

That picture is enough to get every result in stage 6 right, and you should keep
it.

### The engineer's picture: why is Ben a control loop?

Ben is a **feedback controller**, the same shape as the thermostat on your wall.
Naming its four parts is what lets you reason about every design in the rest of
this lab.

```
        ┌─────────────── measured signal ───────────────┐
        │      (queue depth, queue delay, in-flight)    │
        ▼                                               │
   ┌─────────┐                                    ┌──────────┐
   │ compare │  ── error ──▶  ┌────────────┐      │ THE      │
   │ against │                │ actuator:  │─────▶│ SERVICE  │
   │ SET     │                │ admit or   │      │ (queue + │
   │ POINT   │                │ reject     │      │ workers) │
   └─────────┘                └────────────┘      └──────────┘
```

The **signal** is what you measure to decide whether you are overloaded. The
**set point** is the value of that signal you are trying to hold. The
**actuator** is the only thing this loop can do: admit, or reject. The **plant**
is the service itself, whose behaviour changes what you measure next.

Every load-shedding design in production is a choice of **signal** and **set
point**. That is genuinely all that varies. Stage 10 is four different choices of
signal, in the order that the failures of each one force the next.

### How do the two pictures map onto each other?

| Ben, at the door | control loop | in a service |
|---|---|---|
| glances at the waiting room | reads the signal | `queue.size()`, or a latency measurement |
| "more than 20 in there" | set point | `maxQueue = 20`, `target = 50 ms` |
| says "not today" | actuator | returns HTTP 503 |
| the waiting room and Dana | the plant | your queue and your worker pool |
| people leaving for their train | *unmeasured* disturbance | client timeouts the server never sees |

### Where does the doorman story lie to you?

An analogy that is never corrected becomes the learner's actual model, so here
is where this one stops being true. Each break is a real failure mode later in
the lab.

**A rejected person goes home. A rejected client comes straight back.** This is
the big one. Ben's job gets easier as he turns people away. Your shedder's job
can get *harder*, because a 503 arrives at a client library configured to retry,
and a retry is a new arrival. Stage 11 puts a number on it: a correct shedder at
150 offered requests/s was processing **394 admission decisions per second**.
Its own rejections had become 62% of its input.

**Ben's "no" is free. Yours is not.** Saying no to an HTTP request still costs a
TCP accept, a TLS handshake, header parsing, a log line and a response write.
Small — but multiplied by an overload. Stage 11 shows a service that became
*completely unable to serve anyone* purely from the cost of saying no, at an
offered load it could otherwise have shed comfortably.

**Ben can see the whole waiting room. Your service cannot see the deadline.** He
knows about the 11:00 train. Your server does not know Priya's timeout unless
someone sends it. The most valuable single upgrade in this whole lab is deadline
propagation, in stage 13. It is the act of telling the server what Ben could
simply see.

**Dana's speed never changes. Yours changes constantly.** A passport takes 4
minutes today and 4 minutes next year. Your 80 ms request becomes a 400 ms
request the moment a downstream database gets slower. When it does, your
capacity silently drops from 50/s to 10/s, and every fixed threshold you
configured is now wrong by a factor of five. Stage 10's v2 exists entirely
because of this, and it is where most real shedding configurations fail.

## 9. Where can you break the office yourself?

> **In one breath:** Open the page and watch two counters that normally move
> together come apart. That gap is the whole subject.

Open `visualization.html` now, before reading the mechanism. It runs the same
engine that produced every number in this document.

**The one thing to look at:** the page shows **two counters side by side** —
*throughput* (work finished) and *goodput* (work finished in time). Watch them
during the very first scenario. They start equal. The moment you push offered
load past capacity, one of them stays flat and the other falls to the floor. If
you take nothing else from the page, take the sight of those two numbers coming
apart, because that gap is invisible on every dashboard you currently own.

The page has five tabs, matched to the stages below. Dana is the worker pool in
all five; Ben appears in tab 2 onwards.

1. **The collapse** — a service with no protection, a slider for offered load.
   Find the load at which goodput falls off the cliff. It is closer to normal
   traffic than you expect.
2. **The four shedders** — the same overload with each signal from stage 10.
   Switch between them live and watch the limit move.
3. **The queue is a second lever** — order and drop rules with no admission
   control at all. This one contains the most surprising result in the lab.
4. **Failure injection** — retry storms, expensive rejections, a dependency that
   slows down.
5. **Priority** — three classes of traffic under one limit.

Each tab asks you to predict before it runs. Take the predictions seriously; the
ones people get wrong are tabs 3 and 4.

## 10. What should the doorman actually look at?

> **In one breath:** Ben needs one number to decide on. Four different numbers
> are defensible, each one is broken by a specific morning, and the break is
> what produces the next.

We now build the shedder properly. Each version is a real design that real
systems ship. Each one is broken by a specific scenario, and every scenario is
the same office with one thing changed.

Throughout: **4 workers × 80 ms = capacity 50 requests/s. Client timeout 1
second. Offered load 150/s unless stated.** At 150/s offered against 50/s
capacity, **33.3% is the best any design can possibly do.**

---

### v1 — what if Ben just counts the room?

The simplest signal there is: how many requests are waiting? Ben glances at the
chairs, counts heads, and stops letting people in above some number.

```
if (queue.size() >= MAX_QUEUE) return HTTP_503;
queue.add(request);
```

This is one line, it is available in every framework, and it is genuinely
excellent. The only question is what `MAX_QUEUE` should be.

Your `queueing-theory` lab derived a rule for exactly this. A request arriving
behind `q` others, where there are `c` workers each taking `S` seconds, waits
`q·S/c`. In words: everybody ahead of you, times how long each takes, shared
between the desks. For it to finish inside a deadline `D`:

```
q_max = c · (D/S − 1) = 4 × (1000/80 − 1) = 46
```

> **🔮 Predict:** *We have a formula that says 46. Sweep the cap from 400 down to
> 1 at 3× overload, where 33.3% is the ceiling. Which cap wins, and how sharp is
> the peak?*
> <details><summary>Reveal</summary>
>
> | cap | success rate (ceiling = 33.3%) | median latency of the served |
> |---|---|---|
> | 400 | **0.1%** | 532 ms |
> | 200 | **0.1%** | 532 ms |
> | 100 | **0.1%** | 532 ms |
> | 50 | 12.0% | 918 ms |
> | **46 — the formula's answer** | **18.7%** | 889 ms |
> | 30 | 32.8% | 653 ms |
> | 20 | **33.3%** | 455 ms |
> | 10 | 33.4% | 253 ms |
> | 5 | 33.5% | 151 ms |
> | 2 | 33.1% | 90 ms |
> | 1 | 32.3% | 71 ms |
>
> Three findings, and all three matter more than the winning number.
>
> **A cap of 100 is worth exactly nothing.** Same 0.1% as no cap at all. This is
> the most common way shedding is shipped and then found not to work. Somebody
> bounds the queue at a number that *sounds* small next to 60,047. They have
> bounded it at eight times too large. "We have a bounded queue, so we're
> protected" is false. There is a specific number and 100 is not it.
>
> **The formula is a ceiling, not a setting.** 46 is the cap at which the
> *average* admitted request just barely makes its deadline — which means about
> half of them don't. The sweep says 18.7%, a bit over half of optimal. Any
> deadline-derived cap needs dividing by two or three before use.
>
> **There is no sharp peak — there is a plateau.** Anything from 30 down to 2 is
> within a whisker of optimal. You do not have to tune this well. You have to
> tune it *small*. Which is `queueing-theory`'s "shed hard or don't bother",
> arriving from the other direction.
> </details>

Since anything from 2 to 30 wins under overload, choose from the other end: what
does the cap cost you on an ordinary day, when Dana has time to spare?

| cap | success at 35/s (ρ=0.70) | success at 45/s (ρ=0.90) | success at 150/s |
|---|---|---|---|
| 100 | 100.0% | 99.3% | 0.1% |
| 30 | 100.0% | 99.5% | 32.8% |
| **20** | **100.0%** | **98.9%** | **33.3%** |
| 12 | 99.8% | 97.2% | 33.4% |
| 8 | 99.2% | 95.0% | 33.3% |
| 3 | 95.3% | 87.9% | 33.2% |
| 1 | 88.7% | 80.3% | 32.3% |

Now the trade-off is visible and it is a real one. Too large and Ben does
nothing when you need him. Too small and he turns away healthy traffic during
ordinary bursts: a cap of 3 throws away 4.7% of a perfectly normal day. **The
usable window here is roughly 8 to 30**, and 20 sits in the middle of it. That
window is about 4× wide, which is the honest answer to "how carefully do I have
to tune this": not very, but you cannot be off by ten.

**A better way to say the same setting.** Cap the queue and you have capped a
count of people. What you actually care about is *time*. Little's Law, which you
already have, converts between them. To keep the total time inside the service
under a deadline `D` when you can finish `μ` per second, hold the number of
requests inside the service under `μ · D`. In words: how many you finish per
second, times how long the client will wait. Here that is 50/s × 1 s = **50 in
flight**.

Counting in-flight work, queued *plus* being worked on, is strictly better than
counting queued work alone. It is the quantity Little's Law actually talks
about, and it does not change meaning when you add workers. It is what the
production libraries in stage 13 all use. Same behaviour, same plateau:

| in-flight limit | 35/s | 45/s | 150/s |
|---|---|---|---|
| 50 (Little's Law, undivided) | 100.0% | 99.7% | 18.7% |
| 30 | 100.0% | 99.4% | 33.2% |
| **12** | **99.2%** | **95.0%** | **33.3%**, p99 540 ms |
| 6 | 92.8% | 85.0% | 33.1% |

v1 is a real, shippable, dramatically effective design. Ship it today if you
have nothing. Then read the next section, because it has one specific weakness
and it is the weakness that bites in production.

---

### What breaks v1? The number 80 was not a constant

Every threshold above was derived from `S = 80 ms`. That number is not a
property of your code. It is a property of your code *and everything it calls*.

So leave the traffic exactly where it is, at a comfortable 60/s, and let a
downstream database get slower. Replica lag, a bad query plan, a noisy
neighbour, a GC pause. Service time goes from **80 ms to 400 ms**. Nothing else
changes; the same requests arrive at the same rate.

In the office: the passport system starts taking five times as long to load each
record. Dana is not slower. Her *desk* is slower, and it happens between one
person and the next.

Your capacity just fell from 50/s to **10/s**. You are now at 6× overload and
nobody sent you any extra traffic.

> **🔮 Predict:** *Your queue cap of 20 was excellent five minutes ago. The
> ceiling is now 16.7%. What does the cap of 20 deliver?*
> <details><summary>Reveal</summary>
>
> | design | success rate (ceiling = 16.7%) | wasted worker time |
> |---|---|---|
> | nothing at all | 0.0% | 100% |
> | **queue cap 20 — perfect yesterday** | **0.1%** | **100%** |
> | in-flight limit 12 | 7.1% | 82% |
> | in-flight limit 3 — hand-tuned for 400 ms | 11.2% | 27% |
>
> **The shedder that was recovering 500× yesterday is now recovering nothing.**
> A queue of 20 at 400 ms per request is 20 × 400/4 = 2,000 ms of waiting, which
> is twice the client's deadline. So every single admitted request is stale
> before it starts, exactly as if there were no shedder at all.
>
> Note what did *not* happen: nothing alerted. The cap is still enforced, the
> rejection metric still shows 83% shed, the config is unchanged and looks
> correct in code review. The setting did not break. **The world moved out from
> under it.**
> </details>

This is the single most important failure in the lab, because it is not a bug
you can find by reading the config. Any threshold expressed in *units of work*,
such as queue entries, in-flight requests or connections, silently encodes an
assumption about how long a unit of work takes. **When service time changes,
every one of those thresholds is wrong by the same factor.**

The fix is to stop counting the proxy and measure the thing you actually care
about.

---

### v2 — what if Ben measures time instead of heads?

You never cared how many people were in the chairs. You cared whether somebody
joining now would still be there when their turn came. So measure **queue
delay**: how long are requests currently waiting between being accepted and
being picked up by a worker?

Ben stops counting heads. He starts timing how long the last person sat before
Dana called them.

That signal is self-correcting by construction. It does not need to know `S`,
the time one application takes. If requests get slower, the delay Ben measures
grows, and he sheds harder, with nobody redeploying anything.

There are two ways to build it, and the difference is instructive.

**v2a predicts the wait.** Keep a running estimate of service time and multiply:
`predictedWait = queueLength × Ŝ / workers`. In words: heads in the room, times
your best guess of how long each takes, shared between the desks. Reject if that
exceeds a target. This is v1 with the constant replaced by a measurement.

**v2b measures the wait you already caused.** Don't predict. Look at how long
the request you *just* dequeued actually sat there. This is the approach
**Facebook published in "Fail at Scale"** (ACM Queue, 2015), adapting the CoDel
algorithm from network queueing. Their rule, paraphrased:

> If the queue has been continuously non-empty for a whole interval (say 100 ms),
> it is a **standing queue**: a backlog, not a burst. The tolerated waiting time
> then collapses from the interval down to a small target (say 5 ms). Anything
> that waited longer than the current tolerance is dropped when it reaches the
> front.

The elegance is in the two-mode behaviour. A queue that keeps draining to empty
is absorbing a burst, and is left alone. A queue that never drains is a backlog,
and gets cut aggressively. It tells "busy" from "underwater" using a signal that
costs one timestamp per request.

Now the test that v1 failed: service time changing from 80 ms to 400 ms under
constant traffic. The right-hand column is the one that matters — it asks
whether anybody had to tell the design that the world had changed.

| design | success rate (ceiling = 16.7%) | told about the change? |
|---|---|---|
| nothing | 0.0% | — |
| queue cap 20 (tuned at 80 ms) | 0.1% | no |
| in-flight limit 12 (tuned at 80 ms) | 7.1% | no |
| in-flight limit 3 (**hand-tuned for 400 ms**) | 11.2% | **yes — we told it** |
| **v2a — predicted wait, target 300 ms** | **13.1%** | no |
| **v2b — adaptive LIFO + CoDel(50, 200)** | **15.4%** | no |

**The self-tuning rules beat the hand-tuned static one.** Read that again. The
setting we computed by hand, *knowing in advance* that service time would become
400 ms, was worse than a rule that found its own way there. And v2b reached 92%
of the theoretical ceiling with nobody touching anything.

**A static queue cap gives 0.1% and a queue-delay rule gives 15.4% — a 154×
difference, from exactly the same hardware, at exactly the same load.**

---

### What breaks v2? The target is not portable, and it is not free

CoDel has two numbers, and they are not universal constants. That is worth
saying out loud, because Facebook's specific values get copied into services
nothing like Facebook's.

| CoDel(target, interval) | healthy 35/s (ceiling 100%) | mild 60/s (ceiling 83.3%) | spike 150/s (ceiling 33.3%) | queue left at 3× |
|---|---|---|---|---|
| **(5 ms, 100 ms) — Facebook's published values** | **89.9%** | 70.0% | 32.4% | 15 |
| (20, 100) | 91.1% | 71.1% | 32.9% | 30 |
| **(50, 200)** | **95.3%** | 75.5% | 33.1% | 58 |
| (100, 500) | 98.4% | 78.4% | 33.3% | 453 |
| (300, 1000) | **99.5%** | 81.1% | 33.4% | **60,047** |

Two things to take from that table.

**Facebook's numbers cost 10% of your healthy traffic.** Not because they are
wrong. They are right for Facebook, whose unit of work is on the order of a
millisecond. A 5 ms tolerance on 80 ms work means a *single request already in
service* pushes you over target. Scale the target to your own service time or
the shedder becomes the outage.

**The last row is the trap in the other direction.** CoDel(300, 1000) is
essentially free when healthy, at 99.5%, and leaves 60,047 requests in the queue
under overload, which is where we started. **Every load-shedding parameter is a
dial between "sheds when it shouldn't" and "doesn't shed when it must", and
there is no setting that is zero on both.** Anyone who tells you their shedder is
free has only measured one end.

---

### v3 — can the service find its own limit?

If a good limit is discoverable from measurements, why configure one at all? Let
a controller search for it, continuously, the way TCP searches for the right
window size. Arjun stops giving Ben a number, and Ben starts finding his own.

**AIMD** (additive increase, multiplicative decrease) is the shape you already
know from TCP congestion control: creep the limit up while things are going
well, cut it hard the moment they are not.

```
on a fast success:   limit += 1/limit      // gentle, additive
on a congestion hit: limit *= 0.85         // sharp, multiplicative
```

Does it work? Here is the limit it found on its own, through a service-time
change it was never told about. The right-hand columns are the limit Ben settled
on and the goodput it bought.

| time | what is happening | limit it chose | goodput |
|---|---|---|---|
| t=100 s | S = 80 ms, capacity 50/s | 17.7 | 53/s |
| t=290 s | still 80 ms | 19.1 | 56/s |
| **t=300 s** | **S jumps to 400 ms, capacity → 10/s** | | |
| t=330 s | 30 seconds later | **3.2** | 5/s |
| t=590 s | steady in the slow regime | 2.6 | 4/s |
| **t=600 s** | **S returns to 80 ms** | | |
| t=630 s | 30 seconds later | **11.5** | 45/s |
| t=890 s | steady again | 10.6 | 42/s |

**Nobody told it anything.** Capacity dropped by 5× and the limit dropped by 6×,
within half a minute, and came back when the dependency recovered. That is what
a control loop buys you over a constant.

---

### What breaks v3? An absolute threshold is a disguised constant

The AIMD above needs to know what "a congestion hit" means, and the obvious
implementation is a latency threshold: *slower than 400 ms means congested.*

Which is a constant. With units of time. Chosen when `S` was 80 ms.

When `S` becomes 400 ms, **every completion is slower than 400 ms**. So the
controller receives a congestion signal on literally every request and grinds
its limit down to the floor. It delivered 10.1% against a 16.7% ceiling while
running its workers at only **66% utilisation**. It was shedding requests it had
the capacity to serve, because it could not tell "I have a backlog" apart from
"this work is just slower now".

The fix production libraries use is to make the signal **relative**: compare the
service's recent latency to *its own* longer-run latency. A ratio has no units
and no built-in assumption about how fast the work is. This is the **gradient**
family in Netflix's `concurrency-limits` library:

```
gradient   = clamp(longWindowLatency / shortWindowLatency, 0.5, 1.0)
newLimit   = limit × gradient + sqrt(limit)     // sqrt = the standing queue we allow
limit      = smooth(limit, newLimit)
```

**An honest report on building this.** Three versions of that ratio were tried
while writing this lab, and the first two were wrong in ways worth knowing
about.

The first compares the *minimum* latency to the *mean* latency. That looks
natural and is broken. With realistically variable service times the minimum is
near zero, so the ratio is pinned at its 0.5 floor and the limit sticks at 4
forever. Bad at both ends, as the table below shows.

The second compares a short window to a long window. That fixes the healthy case
and then **runs away under sustained overload**, because the long window is an
average of the same bad samples and slowly accepts the overload as normal. The
gradient converges to 1, the limit grows without bound, and goodput reaches
zero.

The third adds a hard multiplicative decrease on an actual drop — a completion
whose client had already given up. That fixes the runaway. Netflix's library
does exactly this, and it was not an embellishment.

| version of the ratio | healthy 35/s (ceiling 100%) | 3× overload (ceiling 33.3%) | what went wrong |
|---|---|---|---|
| minimum vs mean latency | 82.3% | 30.3% | ratio pinned at its floor, limit stuck at 4 |
| short window vs long window | 99.9% | goodput **0** | limit ran away to **346** under sustained load |
| **plus a decrease on a real drop** | **96.9%** | **31.9%** | behaves everywhere; Netflix ships this |

The corrected version also beats absolute-threshold AIMD on the
changed-service-time test: 14.5% against 10.1%. But it did not beat a
well-chosen static limit at steady load. And under *permanently* sustained
overload it stays pinned at its minimum, because the drop signal never stops
arriving, and no AIMD-shaped controller can distinguish "I should let more in"
from "I am still drowning".

**The honest conclusion, which the marketing for these libraries does not
volunteer.** Adaptive concurrency control is genuinely better when your service
time moves, and genuinely harder to get right than a queue-delay rule. Its
failure modes are subtle enough that all three of the versions above look
correct in code review. If you are choosing one thing to ship, ship v2b and
measure queue delay.
It gets most of the benefit for a fraction of the risk, which is why CoDel-shaped
designs are what Facebook and Envoy converged on.

---

### v4 — Ben is fair to everyone. Is fair what you want?

Every design so far treats all requests identically. Under 3× overload they all
reach the ceiling of 33.3%, which means **one in three checkouts, and one in
three recommendation-carousel loads.**

That is a terrible allocation. Those two requests are not worth the same amount,
and your business would trade every carousel in the building for one more
completed checkout.

So Arjun gives Ben a guest list. Google's SRE book calls this **criticality**,
and ships four levels (`CRITICAL_PLUS`, `CRITICAL`, `SHEDDABLE_PLUS`,
`SHEDDABLE`). The mechanism is that each level may use a different fraction of
the limit.

| traffic | share of requests | fraction of the limit it may use |
|---|---|---|
| checkout | 20% | 100% |
| browse | 30% | 60% |
| recommendations | 50% | 25% |

> **🔮 Predict:** *Under 3× overload the overall ceiling is still 33.3% — priority
> cannot create capacity. So what does checkout get?*
> <details><summary>Reveal</summary>
>
> | offered | overall | checkout | browse | recommendations |
> |---|---|---|---|---|
> | 60/s (1.2×) | 63.3% | **100%** | 97% | 28% |
> | 150/s (3×) | 33.0% | **97%** | 43% | 1% |
> | 300/s (6×) | 16.6% | **74%** | 6% | 0% |
>
> At 3× overload, checkout goes from 33% served to **97% served**. The overall
> number barely moved, 33.0% against 33.3%, because priority does not create
> capacity. It **spends the same capacity on different requests.**
>
> This is the point at which load shedding stops being an availability
> mechanism and becomes a product decision. The numbers in that `allow` column
> are a statement about what your business is for, and they should not be chosen
> by whoever is on call.
> </details>

---

### What breaks v4? A fraction of a constant is still a constant

Look at the same priority scheme at normal, healthy load, on a day when nothing
is wrong at all:

| offered | overall | checkout | browse | recommendations |
|---|---|---|---|---|
| 35/s (ρ = 0.70, nothing is wrong) | **79.0%** | 100% | 100% | **58%** |

We just threw away 42% of the recommendation traffic on a quiet Tuesday. There
was capacity for all of it. The workers were 69% busy.

The cause: `allow = 0.25` of a static limit of 12 is a hard limit of 3, enforced
at all times, including when nothing is wrong. The class fraction inherited the
static limit's flaw.

The fix is to apply the fractions to an **adaptive** limit, so that when the
service is healthy the limit is large and a quarter of it is still plenty:

| limit type | healthy 35/s | 3× overload: checkout survival |
|---|---|---|
| static 12, with class fractions | **79.0%** | 97% |
| **adaptive, with the same class fractions** | **95.3%** | 96% |

Same protection when it matters, 16 points of healthy traffic recovered.
**Priority must ride on a limit that moves. Bolted onto a constant it becomes a
permanent tax.**

---

### v5 — what are the other two levers?

Everything so far controls **who gets in**. Two other decisions are made after
that. They are independent of admission control, and one of them produces the
most surprising number in this lab.

**Lever 2 is what order Dana calls people in.** First-come-first-served is fair
and it is the default everywhere. Under overload it is also the *worst possible*
order. The person at the front of a backed-up queue is by definition the one who
has been waiting longest, which makes them the one most likely to have left for
the train already. Engineers call that order FIFO, and under overload FIFO
serves the corpses first. **LIFO** means calling the newest arrival, so it always
picks the freshest person in the building.

**Lever 3 is who Dana gives up on.** If Priya has been waiting 990 ms and her
deadline is 1,000 ms, starting 80 ms of work on her form is pure waste. Check
before you start.

> **🔮 Predict:** *At 3× overload with **no admission control at all**, so the
> queue is unbounded and nothing is rejected: what does simply switching FIFO to
> LIFO do to the success rate? Baseline is 0.1%, ceiling is 33.3%.*
> <details><summary>Reveal</summary>
>
> | queue discipline (no admission control anywhere) | success rate | wasted worker time | median latency | queue after 10 min |
> |---|---|---|---|---|
> | FIFO | 0.1% | 100% | 532 ms | 60,047 |
> | FIFO + drop expired, no slack | 3.8% | 99% | 993 ms | 154 |
> | FIFO + drop expired, 80 ms slack | 22.5% | 69% | 943 ms | 143 |
> | FIFO + drop expired, 250 ms slack | 32.1% | 16% | 793 ms | 116 |
> | **LIFO** | **33.4%** | **0%** | **66 ms** | **60,047** |
> | adaptive LIFO + CoDel(5, 100) | 32.4% | 0% | 65 ms | 15 |
>
> **Changing one word, the order you take work off the queue, took a service
> from 0.1% to the theoretical maximum, with no admission control, no rejection,
> and no configuration.** It also cut median latency by 8×, because the request
> being served is always a fresh one.
>
> But look at the last column, and this is why LIFO is not the answer on its own:
> **the queue is still 60,047.** LIFO fixed goodput and did nothing about memory.
> The service will still die, just with excellent latency on the way.
>
> And the drop-expired rows contain a detail worth more than it looks. Dropping
> requests whose deadline has *already* passed recovers almost nothing, 3.8%,
> because in a saturated queue the head is always *just* under the deadline and
> then spends 80 ms in service and misses anyway. **Drop against `deadline −
> expected service time`, not against the deadline.** That one subtraction is
> worth 22.5% instead of 3.8%; a more generous 250 ms of slack is worth 32.1%.
> </details>

The design Facebook published pairs these two levers. **Adaptive LIFO** is FIFO
while the queue is short, because FIFO is fairer and the order barely matters
when nobody is waiting, and LIFO once the queue is deep. **CoDel** then drops
whatever has waited too long. That combination gets 32.4% *and* keeps the queue
at 15.

The three levers are independent, and you want all three:

| lever | in the office | what it controls | what it protects | what it cannot do |
|---|---|---|---|---|
| **admission control** | Ben at the door | who gets in | memory, and the client's time | nothing, if the limit is too high |
| **queue order** | who Dana calls next | who is served next | goodput and latency | bound the queue |
| **drop rule** | the empty chair | who is given up on | wasted work | stop the queue growing |

> **Say it back.** Meera says: "Our queue is bounded at 100, so we are
> protected." Without using the words *goodput*, *deadline* or *ceiling*, tell
> her what her cap actually does at three times capacity.
> <details><summary>A good answer sounds like</summary>
>
> "A hundred chairs is still fifty minutes of waiting, and our customers give up
> after one second. So the room fills with people who will all be gone before
> Dana reaches them, and she spends her whole day on empty chairs — exactly the
> same as having no cap at all. The number that helps is around twenty, because
> twenty people is a wait short enough that the person at the back is still
> there when they are called. Being bounded is not the point. Being bounded
> small enough that the last person in the room can still be served in time is
> the point."
> </details>

> **Checkpoint.** You can now build a shedder, choose its signal, defend the
> number you chose, and say what happens when service time changes underneath
> it. What remains is everything that goes wrong *around* it — and every one of
> the next eight failures has taken down a real production system while the
> shedder itself was working perfectly.

---

# ENGINEERING MODEL — stages 11 to 12

## 11. What are the eight ways this goes wrong around a working shedder?

> **In one breath:** Ben is doing his job perfectly in every one of the next
> eight stories. The service still falls over in all eight.

### F1 — is a rejection really free?

Every design so far assumed a 503 costs nothing. It does not. A TCP accept, a
TLS handshake, header parsing, a routing decision, a metric increment, an access
log line, a response write. Call it 2 ms of CPU, a deliberately modest number
for a JVM service behind TLS.

In the office: every person Ben turns away still needs greeting, checking and a
note in the book. It takes him a few seconds. He is one man.

Four workers give you 4 milliseconds of work per millisecond of wall clock. If a
rejection costs 2 ms, then **2,000 rejections per second consumes the entire
machine.** Not 2,000 requests. 2,000 *refusals*.

Here is the service with an in-flight limit of 12, doing its job correctly the
whole way down the table. The last column is what is left for Dana.

| offered load | goodput | rejections/s | share of CPU spent saying "no" |
|---|---|---|---|
| 150/s | 47.4/s | 100 | 4% |
| 600/s | 35.8/s | 627 | 28% |
| 1,000/s | 25.5/s | 1,083 | 49% |
| 1,500/s | 8.1/s | 1,653 | 74% |
| **1,800/s** | **0.1/s** | 1,995 | **90%** |
| 2,400/s | **0.0/s** | 2,223 | **100%** |

The predicted collapse point is `workers ÷ rejectCost` = 4 ÷ 0.002 = **2,000
rejections/s**, and that matches the observed plateau exactly. Past it the service is
perfectly protected and completely useless. It spends every cycle explaining
that it is too busy to do anything.

Halving the cost of a rejection roughly doubles the load you can survive:

| cost per rejection | goodput at 1,200/s | goodput at 2,400/s | goodput at 4,800/s |
|---|---|---|---|
| 0 ms (free) | 50.0/s | 50.2/s | 50.0/s |
| 0.5 ms | 42.7/s | 35.2/s | 19.7/s |
| 2 ms | 19.8/s | 0.0/s | 0.0/s |
| 5 ms | 0.0/s | 0.0/s | 0.0/s |

What to do about it. Shed as early in the stack as the decision can be made
correctly — at the load balancer or gateway, where a rejection is cheaper than
in your application. Drop before TLS termination if the attack shape warrants
it. Never log a shed request at INFO with the full request body. And treat "cost
per rejection" as a number you have actually put a stopwatch on, because your
capacity to shed is a real capacity with a real ceiling. **A shedder is a
service, and it can be overloaded.**

---

### F2 — what happens when your own 503s become your traffic?

Return a 503 to a client library configured with three attempts, and you have
not removed a request. You have scheduled two more.

**Dev** wrote the phone app. His retry policy is three attempts with a 200 ms
pause, which is the default in most client libraries and looks entirely sensible
in review. Here is what it does to Ben's door at 150/s offered, with a queue cap
of 20.

| client retry policy | admission decisions/s | share shed | goodput |
|---|---|---|---|
| 1 attempt | **150** | 67% | 50.0/s |
| 3 attempts, 200 ms backoff | **394** | 87% | 50.0/s |
| 3 attempts, 1 s backoff | 393 | 87% | 50.0/s |
| 3 attempts, 1 s backoff **+ jitter** | 395 | 87% | 50.0/s |
| 3 attempts, **but never retry a 503** | **150** | 67% | 50.0/s |

Two results here, and the second one contradicts advice you have probably given.

**The offered load nearly tripled and goodput did not move.** All that extra
work is pure overhead. Combined with F1, that is the mechanism: 394 decisions/s
at 2 ms each is 0.8 of your 4 workers, gone, to no purpose.

**Backoff and jitter made no difference at all.** 393 against 394 against 395.
This is worth being precise about, because "add exponential backoff with jitter"
is the standard advice, and it is not wrong. It is aimed at a different problem.
Backoff and jitter fix **synchronisation**: they stop a thundering herd of
clients retrying in lockstep at the same instant. Under *sustained* overload
there is no herd to de-synchronise. Every client will exhaust its attempts
eventually, and spreading them over a second rather than 200 ms changes when
they arrive, not how many. **Jitter is a fix for correlation, not for volume.**

What actually reduced the volume was the last row: **not retrying the rejection
at all.** Which makes sense once stated plainly. A 503 from a load shedder is
the one error in your system that carries a guarantee: *the work was definitely
not done, and the service is definitely already at its limit.* Retrying it is
the one case where you know in advance the retry cannot help.

What to do about it. Distinguish 503-from-overload (`Retry-After` present, back
off *substantially* or fail immediately) from 502/504, which is worth one retry.
Use a **retry budget** rather than per-request attempt counts, because a budget
is a global constraint and an attempt count is not. Google's SRE book suggests
capping retries at ~10% of a client's request volume. And your
`distributed-rate-limiting` lab's warning applies with full force here: **a
dishonest `Retry-After` makes your own header the thing that synchronises the
next wave.** If you say 1 second, mean it, and add jitter *on the server side*
so that every client you rejected in the same millisecond does not come back in
the same millisecond.

---

### F3 — why doesn't the outage end when the cause does?

This is the failure that makes shedding non-optional, and your `queueing-theory`
lab measured its first half.

A healthy service at 35/s. Workers 69% busy, comfortable. At t = 120 s, in **one
instant**, 3,000 extra requests arrive at once. A cache expired, a mobile app
woke up, a partner batch fired. It is a single burst. The traffic rate never
changes again.

> **🔮 Predict:** *One burst, over in an instant, into a service with a fifth of
> its capacity spare. Thirteen minutes later, is the queue empty?*
> <details><summary>Reveal</summary>
>
> It depends on one thing, and it is not the burst. It is whether Dev's clients
> retry.
>
> | design | queue at t=180 | t=300 | t=600 | t=890 |
> |---|---|---|---|---|
> | no shedding, clients retry 3× | 12,265 | 19,072 | 35,785 | **52,206 and rising** |
> | no shedding, **clients do not retry** | 2,014 | 266 | 1 | **1 — fully absorbed** |
> | **queue cap 20** | 1 | 0 | 1 | **1** |
>
> If you said "of course it is empty, the burst was one instant and we have
> spare capacity", you assumed the arrivals were still independent of the
> service's own behaviour. They are not. Every timeout produces a retry, and
> every retry is a new arrival, so the queue feeds itself.
> </details>

Read the first two rows against each other. **The same burst, into the same
service, at the same load. The only difference is whether clients retry.**
Without retries the system swallows it in about four minutes. With retries the
burst becomes permanent: thirteen minutes later the queue is 52,206 and growing,
and the event that caused it ended twelve and a half minutes ago.

That is a **metastable failure**. The system has two stable states, and the burst
knocked it from the good one into the bad one, where it now sustains itself. The
trigger is gone. The outage is not. This is why "we removed the cause and it's
still down" is such a common and disorienting incident report. It is also why
turning traffic off and back on works when nothing else does. Turning it off is
the only way anyone had of draining the queue.

The third row is the point. **With admission control the burst never becomes an
event at all.** The queue never exceeds 1. Load shedding is not merely damage
limitation here. It is what makes the outage *end*.

The recovery numbers say the same thing from the other side. Here is a 3× spike
lasting two minutes, after which traffic returns to normal:

| design | back to full goodput after the spike ended | peak queue |
|---|---|---|
| no shedding | **never** (still degraded 6 minutes later) | 11,599 |
| queue cap 20 | **+5 s** | 20 |
| in-flight limit 12 | +10 s | 8 |
| adaptive LIFO + CoDel | **+5 s** | 221 |

**Without shedding, the length of your outage has nothing to do with the length
of the event that caused it.**

---

### F4 — why is CPU the wrong signal, when it is the one everybody reaches for?

The instinct is to shed on CPU: "if CPU > 90%, start rejecting." It is available
on every host and needs no code.

Be careful here, because the most authoritative source in the field disagrees
with the simple version of this advice. Google's SRE book, chapter 21, states
that their per-task overload protection uses **CPU rate** as its primary
utilisation signal, sometimes combined with memory. CPU rate there means current
CPU consumption divided by the CPU reserved for the task. So "never shed on CPU"
would be wrong.

The reconciliation is in that phrase *reserved for the task*. Google's signal is
CPU **against a quota**, on RPC servers whose work is CPU-bound and whose thread
pools are tightly controlled. In that setting CPU rate *is* a direct measure of
how close you are to your capacity to finish work. The failure below is what
happens when you copy the signal into a setting where that is not true.

**Where CPU cannot see the overload.** A service whose workers are blocked
waiting on a slow database is *completely* overloaded, at near-zero CPU. Every
worker occupied, queue growing, nothing completing. This is exactly the
v1-breaks scenario in stage 10, where service time went 80 ms → 400 ms: **worker
occupancy was 100% while the extra 320 ms per request was pure waiting.** Dana
is sitting at her desk staring at a spinner, and she is as unavailable as if she
were flat out. A CPU-triggered shedder sits silently through the entire
incident. Your `microservices` lab already showed you this shape from the
caller's side: a slow dependency is far more dangerous than a dead one, and
every dashboard stays green. *(That last claim is reasoning rather than a run;
stage 17 says exactly which part was observed.)*

Most services you will write are IO-bound. A request that calls a database and
two other services spends the overwhelming majority of its latency waiting. So
this is the common case, not the exotic one.

**Two further problems, which apply even to CPU-bound work.** CPU is a **lagging
indicator**: by the time a one-minute load average crosses a threshold, the
queue has been growing for a minute. Queue delay responds in milliseconds,
because it *is* the thing you care about. And CPU is **not monotonic** in what
you want. GC, a noisy neighbour on the same host, or a co-located sidecar all
move CPU without moving your capacity to serve.

What to use instead, in order of preference: **queue delay** first, then
**in-flight count** against a limit, then **queue depth**. Queue delay is the
time between accepting a request and starting work on it, and it is the single
most valuable number to add to a service that has none. Reach for CPU when your
work is genuinely CPU-bound and measured against a reservation, as Google's is.
Otherwise treat it as a last-resort backstop with a high threshold, and know
that it will not fire during the incident you are most likely to have.

---

### F5 — where should Ben stand?

You have several places to put the doorman, and the choice is not free. **Omar**
is on the street door of the building; Ben is at the door of Dana's room.

**At the edge** (load balancer, gateway) a rejection is cheapest. F1's ceiling is
highest here, and you never spend backend capacity on a request you refuse. But
Omar does not know *which* room is in trouble, or how expensive a particular
application is. If one of your six services is sick, an edge shedding 30% of
everything degrades the five that are fine and still lets through more than the
sick one can take.

**At the service** the signal is accurate, because it is your own queue. But by
the time the request arrives you have already spent the edge's accept, the TLS
handshake, the routing decision, and a network hop. And a service that sheds
cannot protect the *dependency* it is hammering.

**In the client, per dependency**, is the third option and the one people
forget. This is your `microservices` lab's quartet: a bulkhead caps how much of
*your* worker pool one dependency may occupy, and a circuit breaker stops calling
a dependency that is failing. Those protect *you*. They do nothing for the
dependency. A concurrency limit on the *outbound* side is the piece that
protects what you are calling.

The rule that resolves it: *shed as early as the decision can be made correctly,
and no earlier.* Concretely, that usually means all three, doing different jobs.
A coarse global limit at the edge, sized to protect the edge. A per-service
adaptive limit inside each service, which is where the accurate signal lives.
And outbound limits per dependency. What you must avoid is two layers
independently shedding the same traffic against uncoordinated thresholds, which
multiplies your shed rate and is very hard to see on a dashboard.

---

### F6 — what if the shedder eats the recovery path?

A load shedder that treats all requests identically will, under overload, shed
the very requests that would have ended the incident.

It sheds **health checks**. So your load balancer concludes the instance is dead
and removes it, moving its traffic to the remaining instances, which are now
more overloaded, and shed harder, and are also removed. That is a cascading
failure that the mitigation caused.

It sheds **the configuration or control-plane call** that would have let you
turn a feature off. It sheds **the deploy or rollback endpoint**.

And it sheds **the cache-fill request**, so the cache stays cold, so more
traffic reaches the origin, so the overload deepens. The one request that would
have reduced future load is the one you rejected.

Every one of those is a request whose *value is negative to shed*. This is why
Google's criticality scheme has a level above "critical": some traffic must never
be shed even when everything else is.

What to do about it: health checks and control-plane endpoints go on a separate
port, or a separate thread pool, or are explicitly exempt from admission control.
That is a **bulkhead**, from your `microservices` lab, applied to the paths that
are cheap and load-bearing. And your health check must report *"am I able to
serve?"* rather than *"am I alive?"*. A service shedding 90% of its traffic is
alive and should say so, or the load balancer will make it worse.

---

### F7 — is a shedder that ignores identity actually fair?

Load shedding is blind to identity, which was the point. It also means that when
one tenant sends 80% of your traffic, they get 80% of your admissions, including
during the overload *they* caused. Every other customer is degraded so that the
one who broke it can continue at four-fifths strength.

This is the exact seam where load shedding needs rate limiting, and where your
`distributed-rate-limiting` lab's material becomes load-bearing rather than
adjacent.

Rate limiting enforces *"you may have 1,000/s"* — a promise, per identity,
independent of your health. It is what stops one tenant from being 80% of the
offered load in the first place. Load shedding enforces *"I can only do 50/s
right now"* — a capability, across everyone, independent of identity.

Neither substitutes for the other. Rate limits that are loose enough to be
generous will not prevent overload. A shedder tight enough to protect you will
not protect *tenant B from tenant A*. Ship both. If you can only ship one thing
this quarter, ship the shedder, because it is the one that prevents total loss,
and know that you have not solved fairness.

There is a middle option, for when you need fairness under overload without a
full quota system. Use **per-tenant queues with weighted round-robin**, or key
the shed decision on `(tenant, current share)` so that the heaviest sender is
shed first.

---

### F8 — what did the dashboard hide, and what did nobody test?

Two failures that arrive together, because they are two halves of one habit.

**The dashboard.** Stage 4 showed a total outage in which throughput read 50.1/s
and the median latency of served requests read 532 ms. Both entirely normal
numbers. Add a correct shedder and throughput is *still* 50.1/s. Here they are
side by side, and the only column that moves is the last one:

| | throughput | median latency (of served) | **success rate** |
|---|---|---|---|
| no shedding at 3× — a total outage | 50.1/s | 532 ms | **0.1%** |
| in-flight limit 12 at 3× — optimal | 50.0/s | 214 ms | **33.3%** |

**Throughput cannot tell these apart. Latency-of-served makes the outage look
*better*, because during a shed the requests you serve are exactly the ones you
had capacity for.** A shed 503 is fast, so it improves your average latency. A
p99 computed over served requests improves during an outage. Every latency
metric you own is computed over survivors.

The three numbers that would have caught it: **goodput** (or success rate over
*offered*, which requires counting rejections), **queue delay**, and **shed rate
by priority class**. If you add one, add queue delay.

**The test.** A shedder that has never been exercised is a config file, not a
mechanism. Here is what turns up the first time somebody actually runs one past
capacity. Read the right-hand column: it is why none of these were caught
earlier.

| what the load test found | why code review missed it |
|---|---|
| the threshold was in the wrong units | the number looks plausible either way |
| the rejection path allocated more than the success path: F1 at 8 ms per reject rather than 0.5 | nobody puts a stopwatch on the error path |
| the 503 was logged at INFO with the full request body, so the logging pipeline fell over first | one log line looks harmless |
| the health check was shed (F6) | the exemption list lives in a different file |
| Dev's client library retried the 503 immediately with no backoff (F2) | the retry policy lives in a different repository |

None of those are visible in code review. All are visible in one load test that
goes past capacity, which is the load test almost nobody runs. Load tests are
usually written to find the *maximum sustainable* load and then stop, which is
precisely the boundary at which all of this behaviour begins.

---

## 12. What are you actually guaranteed?

> **In one breath:** Four promises, five things people assume are promises and
> are not, and five assumptions that each turn into one of stage 11's failures
> when they stop being true.

**What load shedding guarantees**, given the assumptions below:

1. **Bounded work in progress.** The queue cannot exceed the limit, so memory is
   bounded and the `OutOfMemoryError` ending of stage 4 cannot happen.
2. **Bounded latency for admitted work.** If the limit is derived from a deadline
   via Little's Law, admitted requests complete inside it. That is the whole
   content of `inflight ≤ μ · D`.
3. **The system stays in a recoverable state.** F3's numbers: with shedding, full
   service returns 5 seconds after the cause ends; without it, never. Shedding is
   what makes the outage's length a function of the event's length.
4. **A fast, unambiguous answer to the client.** A 503 in 1 ms is information
   Priya can act on. A timeout at 1,000 ms is not. It does not even tell her
   whether the work happened.

**What it does not guarantee**, and these are the ones that get promised anyway:

1. **It does not create capacity.** Every table in this lab is bounded by
   `capacity ÷ offered`. Shedding gets you *to* that ceiling. Only more workers,
   faster work, or less work raise it. A shedder is not a substitute for capacity
   planning; it is what keeps you honest about the plan you have.
2. **It does not make the shed requests succeed.** They failed. Fast, cheaply, and
   legibly — but they failed, and Priya did not get her passport.
3. **It does not choose the least valuable requests** unless you told it what
   valuable means (v4). By default it sheds by arrival time, which correlates with
   nothing.
4. **It does not protect your dependencies.** Admitting 50/s means calling your
   database 50 times a second. If the database is what is overloaded, an inbound
   limit does not help it. You need an outbound one.
5. **It is not fair** (F7).

Every one of those guarantees rests on an assumption, and each assumption is a
failure mode when it is violated. Read the middle column as "the morning where
this stops being true".

| assumption | violated by | consequence |
|---|---|---|
| rejection is much cheaper than service | expensive 503 path | F1 — you can only reject |
| clients respect the rejection | retry-on-503 | F2 — your output becomes your input |
| the signal responds faster than the overload grows | CPU averages, 1-min metrics | F4 — you shed after it matters |
| service time is roughly stable, *or* the signal is time-based | a slow dependency | v1 breaks — 154× worse |
| some traffic is exempt | shedding health checks | F6 — the mitigation cascades |

**Safety and liveness, stated properly.** The safety property is *"work in
progress never exceeds the limit"*. It holds at every instant, under every load,
and is trivially checkable. The liveness property is *"every admitted request
eventually completes"*. Note that LIFO **violates** it: under permanent
overload, the person at the bottom of the stack is never called. That is a real
cost, not a rounding error, and it is why LIFO is an overload *mode* rather than
a default.

The two ways of failing two-thirds of your customers are not equivalent, even
when the success rate is identical:

| at 3× overload | success | how the other 67% failed |
|---|---|---|
| LIFO, no admission control | 33.4% | waited the full second, then got nothing |
| queue cap 20, FIFO | 33.3% | told "no" in about a millisecond |

**Identical goodput. Completely different product.** The second one lets Priya
fall back to a cached page, try another region, or see an honest error
immediately. The first burns a second of her life and a second of the client's
connection before delivering the same nothing. **Failing fast is a feature you
are shipping to your callers, not just a saving for yourself.**

> **Say it back.** Two services both fail 67% of their customers. One says "no"
> in a millisecond; the other says nothing for a full second. Without using the
> words *safety*, *liveness* or *bounded*, explain why the first is the better
> product.
> <details><summary>A good answer sounds like</summary>
>
> "Both of them serve the same people, so the difference is not who wins. It is
> what everyone else gets to do next. A fast no is an answer: the app can show a
> cached page, try the other region, or say sorry while the customer is still
> looking at the screen. A slow nothing is not an answer, and it costs the
> customer a second of their life and us a second of a connection we could have
> used. And nobody can even tell whether their order went through."
> </details>

---

# PRODUCTION MODEL — stages 13 to 16

## 13. What do you actually operate, and in what order do you build it?

> **In one breath:** The office has one door and one desk. Your system has six
> queues in a row, dozens of instances, and a client whose deadline you cannot
> see. Here is what the real implementations do about that.

### What did the lesson deliberately leave out?

| simplification here | production reality |
|---|---|
| one service, one queue | five or six queues in series — kernel accept backlog, connection pool, thread pool, per-dependency pool, database. You have configured about one of them |
| requests all cost the same | request cost varies 100×; a shedder counting *requests* under-protects against expensive ones |
| the client's deadline is a fixed 1 s | deadlines vary per call and should be *propagated*, see below |
| one instance | dozens, each with its own view, behind a load balancer with its own opinion |
| rejection is instant | 503 must traverse the same stack it came in on |
| offered load is a smooth rate | it is bursty, correlated, and sometimes adversarial |

### What do the four production designs actually do?

These are the real implementations, with what is known about each one.

**Facebook builds Ben out of a stopwatch: adaptive LIFO plus CoDel.** Published
in *"Fail at Scale"* (ACM Queue, 2015). Requests are served FIFO while the queue
is short and **LIFO once a queue starts to form**. CoDel drops requests that have
waited beyond a tolerance which collapses when the queue stops draining. The
published constants are **M = 5 ms (target) and N = 100 ms (interval)**,
described as working well across a wide set of use cases. Implementations exist
in **HHVM** and in Facebook's **Wangle** library, used by Thrift. This is the
design stage 10's v2b models, and stage 10's table shows why the 5 ms constant
needs scaling to your own service time.

**Envoy builds Ben out of a ratio: the adaptive concurrency filter.** It is a
gradient controller, and its formula is very close to what stage 10 built:

```
gradient  = (minRTT + B) / sampleRTT        B = minRTT × buffer_pct
limit_new = gradient × limit_old + headroom  headroom = sqrt(limit), not configurable
```

Documented defaults: sample latency taken at the **90th percentile**, sampling
window **100 ms**, minRTT recalculated every **60 seconds** with **10% jitter**,
using **50 requests**.

The detail worth stealing is how Envoy solves a problem stage 10 hit head-on.
This lab found that a *minimum*-latency baseline is unreliable, because under
sustained load you never observe an uncontended request. Envoy's answer is to
**periodically pin the concurrency limit down to `min_concurrency` in order to
force an uncontended measurement**. It deliberately creates the idle conditions
needed to measure minRTT, then goes back to normal. In office terms: Ben empties
the waiting room for a few seconds so he can time how long one application takes
when Dana is not rushed. The documentation is candid that this causes a
noticeable increase in 503s during those windows, and recommends enabling
retries because of it. That is a real, honest trade: the controller pays a small
periodic outage to keep its baseline true.

Envoy also ships a separate **admission control filter**, which sheds based on
observed success rate rather than latency.

**Netflix ships Ben as a library: `concurrency-limits`.** A Java library of
pluggable limit algorithms, gradient and AIMD variants, with interceptors for
gRPC and servlets. This is where stage 10's v3 comes from, including the
necessity of a multiplicative decrease on an actual drop alongside the
gradient's gentle adjustment. This lab found that last part the hard way.

**Google gives Ben a guest list and teaches the callers to stay home:
criticality plus client-side adaptive throttling.** Chapter 21 of the SRE book.
Two pieces.

*Criticality* is carried in the RPC and comes in exactly four levels:
**`CRITICAL_PLUS`, `CRITICAL`, `SHEDDABLE_PLUS`, `SHEDDABLE`**. It propagates
through the call tree, so a request marked sheddable at the edge is sheddable
everywhere it goes. That is what stage 10's v4 models, and the propagation is the
part that is easy to leave out and expensive to omit.

*Client-side adaptive throttling* is the piece that fixes F2 properly, and it is
the most under-used idea in this whole subject: **the client throttles itself.**
Dev's app stops calling before Ben has to say no. Each client tracks `requests`
(attempted at the application layer) and `accepts` (accepted by the backend)
over a recent window. Once `requests` exceeds `K × accepts` it starts rejecting
its own outbound calls locally, with probability

```
max(0, (requests − K × accepts) / (requests + 1))
```

The book recommends **K = 2** as the default, and notes that reducing K to 1.1
means only one request is rejected by the backend for every ten it accepts.
Tighter protection for the server, at the cost of the client giving up sooner.

Why this matters: it moves the cost of rejection **off the overloaded server
entirely**, which is the only real answer to F1. A server that is shedding has a
ceiling on how fast it can shed. A client that has stopped calling costs it
nothing at all.

### Deadline propagation — why is it the highest-value thing to add?

Stage 8's third analogy break was that your server cannot see the client's
deadline. Ben can see the clock on the wall and knows about the 11:00 train.
Your service does not. Fix that, and several things become possible at once.

The mechanism: the caller sends the time remaining with the request. gRPC does
this natively as the `grpc-timeout` header, and every service in the chain
subtracts the time it has already spent before calling the next one.

What it buys you:

- **Drop-on-dequeue becomes correct rather than a guess.** Stage 10 showed that
  dropping against `deadline − expected service time` is worth 22.5% against
  3.8% for dropping against the deadline, and 32.1% with a healthy slack. With a
  propagated deadline you know the real number instead of estimating it.
- **You stop doing work nobody is waiting for**, across the whole call tree at
  once, not just at the entry point.
- **Timeouts stop being independently guessed** at every hop. The classic bug
  this removes: service A times out at 1 s and calls B, which has its own 3 s
  timeout and keeps working for two seconds after A gave up.

### What do you configure, and in what order?

1. **Bound every queue you own.** Start with the accidental ones — an unbounded
   `LinkedBlockingQueue`, `spring.task.execution.pool.queue-capacity` at
   `Integer.MAX_VALUE`, a connection-pool acquire timeout of 30 s (which, as your
   `queueing-theory` lab put it, is an unbounded queue with a 30× multiplier).
   Stage 10's plateau says the exact number barely matters as long as it is
   small; being off by 10× does.
2. **Add queue delay as a metric** before you add any controller. You cannot tune
   what you cannot see, and this one number would have caught every failure in
   stage 11.
3. **Ship a static in-flight limit** derived from Little's Law and then divided by
   two or three. Stage 10 put the undivided value at 18.7% against a 33.3%
   ceiling.
4. **Make the rejection cheap and make it honest.** Put a stopwatch on your 503
   path. Return `Retry-After` and mean it. Do not log shed requests at INFO.
5. **Stop retrying rejections.** Retry budget, not attempt counts; never retry a
   shedder's 503.
6. **Then** consider criticality classes, remembering they must ride on an
   adaptive limit or they become a permanent tax on your low-priority traffic:
   79.0% against 95.3% at healthy load.
7. **Then** consider an adaptive controller, knowing what stage 10 found about how
   easy they are to get subtly wrong.

### What goes on the dashboard, and what do you alert on?

| metric | why | 
|---|---|
| **goodput / success rate over *offered*** | the only number that distinguished a total outage from optimal operation in stage 11's F8 |
| **queue delay (p50 and p99)** | the fastest, most direct overload signal you can have |
| **shed rate, split by criticality class** | "we shed 40%" is meaningless; "we shed 40% and it was all `SHEDDABLE`" is a healthy system working exactly as designed |
| **the limit itself, if adaptive** | a controller pinned at its minimum is a silent outage (stage 10's second broken gradient) |
| in-flight count vs limit | how much headroom is left |
| rejection cost × rejection rate | your distance from F1's ceiling |

**Alert on shed rate of `CRITICAL` traffic, not on shed rate.** A system shedding
its lowest class is a system working. A system shedding its highest class has run
out of the capacity it promised, and that is the page.

And the operational rule that everything else depends on: run a load test that
goes *past* capacity, on purpose, regularly. Every failure in stage 11 is
invisible until you do. Most load tests are written to find the maximum
sustainable load and then stop, which is exactly the boundary at which all of
this behaviour begins.

> **Say it back.** You have one afternoon and a service with nothing. Tell Arjun
> the three things you will do, in order, and what each one buys, without using
> the words *adaptive*, *criticality* or *control loop*.
> <details><summary>A good answer sounds like</summary>
>
> "First I put a lid on every waiting room we own, and I make it small: about
> twenty, not a hundred. A queue nobody can reach the front of in time is the
> same as no lid at all. Second I start recording how long each request
> sat before a worker picked it up, because that one number tells us we are
> drowning while every other graph still looks fine. Third I make our 'sorry,
> not now' cheap and honest, and I stop our own apps from immediately asking
> again, because our refusals were becoming most of our traffic. After that
> afternoon we can survive a spike. Everything cleverer comes later."
> </details>

## 14. Which comfortable sentences are wrong?

> **In one breath:** Eight sentences you will hear in an incident review. Each
> one sounds like caution, and each one is contradicted by a number you now
> have.

> *"Load shedding means we lose requests, so it's a last resort."*

Backwards. Under overload the requests are lost either way. Stage 6 put it at
0.1% served without it and 33.3% with it, at the same offered load on the same
hardware. Shedding does not cause the loss. It *converts* undirected loss into
directed loss, and directed loss is 500× better. The requests you shed were
going to fail; the ones you saved were not.

> *"It's the same as rate limiting."*

Rate limiting asks *are you allowed?* — a question about identity and a
contract, answered identically whether you are idle or on fire. Shedding asks
*can I?* — a question about your own state right now, answered identically for
every client. A rate limiter set tight enough to prevent overload rejects your
legitimate traffic every day. A shedder does not protect tenant B from tenant A.
You need both, they fire at different times, and neither is a configuration of
the other.

> *"We have a bounded queue, so we're protected."*

Stage 10 swept caps of 400, 200 and 100, and all three delivered the same
**0.1%** as no cap at all. The bound must be small enough that a request joining
at the back can still finish in time, which is a number in the range 8–30 for
that service, not 100. *Bounded* and *correctly bounded* are different claims,
and only one of them protects you.

> *"We'll set the limit once and it'll be right."*

It was right for the service time you had when you chose it. Stage 10's central
result: the same cap of 20 that recovered 500× at 80 ms per request recovered
**nothing at all** when a dependency pushed service time to 400 ms. That is 0.1%
against a 16.7% ceiling, with no config change, no alert, and no traffic
increase. Any limit in units of work silently encodes an assumption about how
long work takes.

> *"Throughput looks fine, so we're fine."*

This is Meera's sentence from stage 1. Throughput read 50.1/s during a total
outage and 50.0/s during optimal operation. Median latency read *better* during
the outage than under load, because it is computed over survivors. Both of your
default dashboards are blind to the thing that is happening.

> *"Returning a 503 is free."*

Stage 11's F1: at 2 ms per rejection, a four-worker service becomes completely
unable to serve anyone at around 1,800 requests/s offered, purely from the cost
of refusing them. Your capacity to shed is a finite capacity. It has a computable
ceiling, `workers ÷ cost per rejection`, and past it the shedder is the outage.

> *"Retries make the system more reliable."*

They make an individual request more likely to succeed *when failures are
independent*. Under overload failures are not independent. They are caused by
load, and retries are load. A service at 45/s against a capacity of 50/s is
**comfortably under capacity**. It still dropped to a **24.2% success rate with
a 38,663-deep queue**, purely because its clients retried three times. And a
single 3,000-request burst into a healthy system was absorbed in four minutes
without retries, and had grown to a 52,206-deep queue thirteen minutes later
with them.

> *"Exponential backoff with jitter fixes retry storms."*

It fixes *synchronisation*, which is a real and different problem. Under
sustained overload, 200 ms backoff, 1 s backoff, and 1 s backoff with jitter
produced 394, 393 and 395 attempts per second. Indistinguishable. The thing that
worked was not retrying the rejection at all, which took the door back to 150/s.
A shedder's 503 is the one error that guarantees both that the work did not
happen and that retrying cannot help.

> *"Autoscaling means I don't need this."*

Autoscaling responds in minutes. Stage 11's F3 showed a system going from
healthy to unrecoverable in about sixty seconds. Shedding is what covers the
gap. And worse, an autoscaler reading the wrong signal will scale *down* a
shedding service, because its CPU looks fine and its latency looks great.

Here are the eight in one place, with the single figure that settles each.

| the comfortable sentence | the figure that settles it | where |
|---|---|---|
| shedding is a last resort | 0.1% without it, 33.3% with it | stage 6 |
| it is the same as rate limiting | one asks *are you allowed*, one asks *can I* | stage 7 |
| a bounded queue is protection | caps of 400, 200 and 100 all give 0.1% | stage 10, v1 |
| set the limit once | cap 20 goes from 500× better to 0.1% | stage 10, v1 breaks |
| throughput looks fine | 50.1/s during the outage, 50.0/s when optimal | stage 11, F8 |
| a 503 is free | dead at 1,800/s offered, from refusals alone | stage 11, F1 |
| retries add reliability | 24.2% at 45/s, queue 38,663 | stage 11, F2 |
| backoff and jitter fix it | 394, 393, 395 attempts per second | stage 11, F2 |

## 15. What else does this connect to, and what should you learn next?

> **In one breath:** Four labs you already own were leaning on this one without
> saying so, and six neighbouring ideas are one line each away from being
> confused with it.

**Prerequisites you already had.** `queueing-theory` gave you the cliff at ρ = 1,
Little's Law, goodput versus throughput, and `maxQueue ≈ deadline/S`.
`microservices` gave you timeouts, bulkheads, breakers, and the slow-dependency
failure. `distributed-rate-limiting` gave you the four siblings, `429` and
`Retry-After`, and fail-open versus fail-closed. `idempotency` becomes mandatory
the moment a client retries a request that may already have been processed.

**Commonly confused with — one line each.** Every row is something Ben is *not*
doing.

| that | this |
|---|---|
| **rate limiting** | enforces a *promise* per identity; shedding enforces a *capability* across everyone |
| **backpressure** | slows the producer down and throws nothing away; shedding is what you do when the producer cannot be slowed |
| **circuit breaker** | protects the *caller* from a failing callee; shedding protects the *callee* from its callers |
| **bulkhead** | reserves a share of a resource so one dependency cannot take it all; shedding decides whether to take work at all |
| **autoscaling** | raises the ceiling in minutes; shedding keeps you alive in the seconds before it arrives |
| **timeout** | how long *one* caller waits; shedding decides who waits at all |

**Where to go next**, in the order that will pay off most given what you now
have:

- `/learn load-balancing` — the layer above the shedder, and the most-requested
  gap in your map (8 labs). Health checks, least-connections vs power-of-two, and
  the fact that a load balancer's job is choosing *which* queue.
- `/learn tail-latency-and-hedging` — the other half of the deadline story:
  hedged requests, and what it costs to ask twice.
- `/learn observability-slos` — goodput and shed rate belong in an error budget;
  four of your labs assume this and none teach it.
- `/learn backpressure` — the mechanism this lab kept deferring to. Reactive
  streams, TCP windows, and Kafka consumer lag as the same idea in three places.
- `/learn metastable-failures` — F3 as a subject in its own right; the 2021
  HotOS paper "Metastable Failures in Distributed Systems" is the reference.

## 16. What does each word mean?

Grouped by what the word is about. Each entry names the stage where you met the
thing, so a definition you have forgotten is always two clicks from the story
that earned it.

### What are the words for the thing you are trying to see?

**Throughput** — work completed per second. A fact about your machine. Read 50/s
during both total success and total failure in stage 1.

**Goodput** — work completed per second *that was still wanted when it finished*.
A fact about your business, and the only metric in this lab that could tell a
healthy service from a dead one.

**Offered load** — requests arriving per second, as opposed to requests served.
Invisible on server-side graphs unless you count rejections.

**Capacity (μ)** — completions per second the system can sustain. `workers ÷
service time`. Not a constant: it fell 5× in stage 10 when a dependency slowed.

**Overload** — offered load exceeding capacity. Defined against completion rate,
not CPU.

**Shed rate** — fraction of arrivals rejected. Meaningless without a criticality
breakdown.

**Wasted work** — worker time spent on requests whose client had already given up.
100% of it in stage 4, which is Dana's whole morning.

**Queue depth** — number of requests waiting. A count, and therefore carrying a
hidden assumption about service time.

**Queue delay (sojourn time)** — how long a request waited between being accepted
and being started. The best single signal, because it is denominated in the units
the deadline is denominated in.

### What are the words for the machinery at the door?

**Admission control** — the component deciding, at the entrance, whether a request
joins the queue. *Load shedding* is what it is called when it says no because you
are overloaded. Ben, in stage 6.

**Signal / set point / actuator** — the three parts of the control loop that every
shedder is. Designs differ almost entirely in the choice of signal. Stage 8.

**In-flight / concurrency limit** — a cap on requests inside the service, queued
plus being worked on. Little's Law's natural variable: `inflight ≤ μ × deadline`.

**Little's Law** — `L = λW`. Here it converts a deadline into a concurrency limit.
An identity, not a model.

**AIMD** — additive increase, multiplicative decrease. TCP's shape, applied to a
concurrency limit. Stage 10's v3.

**Gradient controller** — an adaptive limit driven by the *ratio* of recent
latency to the service's own baseline latency, so that it carries no assumption
about how fast the work is. Envoy: `limit = gradient × limit + sqrt(limit)`.

**minRTT** — the uncontended latency baseline a gradient controller compares
against. Envoy re-measures it every 60 s by deliberately pinning concurrency to
its minimum.

**Criticality** — a priority class carried in the request and propagated through
the call tree. Google's four levels: `CRITICAL_PLUS`, `CRITICAL`,
`SHEDDABLE_PLUS`, `SHEDDABLE`. Arjun's guest list, in stage 10's v4.

**Client-side adaptive throttling** — the client rejecting its own calls once
`requests > K × accepts`, with probability `max(0, (requests − K×accepts) /
(requests + 1))`. Google's default `K = 2`. Moves the cost of rejection off the
overloaded server.

### What are the words for what happens after the door?

**Deadline** — how long an answer is still worth having. Usually the client's
timeout; usually known to the client and not the server. Priya's 11:00 train.

**CoDel (Controlled Delay)** — a queue rule that tolerates delay while the queue
keeps draining and cuts hard once it stops. Facebook's published constants:
target 5 ms, interval 100 ms.

**Standing queue** — a queue that never drains to empty. The distinction CoDel is
built on: a burst is fine, a backlog is not.

**Adaptive LIFO** — FIFO while the queue is short, LIFO once it forms. Facebook's
pairing with CoDel; on its own it took goodput from 0.1% to 33.4% in stage 10.

**Drop-on-dequeue** — discarding a request when it reaches the front if it can no
longer finish in time. Must be evaluated against `deadline − expected service
time`, which is worth 22.5% versus 3.8% for the naive version.

**Deadline propagation** — sending the remaining time budget with the request, so
every hop can decide whether the work is still worth doing. gRPC's
`grpc-timeout`. The thing that would let your server see what Ben can see.

### What are the words for what goes wrong, and for the neighbours?

**Metastable failure** — a system with two stable states, knocked into the bad one
by a transient, which then sustains itself after the trigger is gone. Stage 11's
F3: a burst absorbed in four minutes without retries, permanent with them.

**Retry budget** — a global cap on retries as a fraction of a client's traffic,
as opposed to a per-request attempt count. The only retry control in this lab
that reduced offered load.

**Backpressure** — refusing work and letting the pressure travel back to the
producer, discarding nothing. Shedding is the answer when there is no producer to
push back on.

**Bulkhead** — a reserved share of a resource, so one dependency or one class
cannot consume all of it. What keeps health checks from being shed.

## 17. About this lab — where did the numbers come from?

> **In one breath:** One document, one simulator, one exercise ladder, and a set
> of numbers checked against the cases where mathematics already knows the exact
> answer.

### What is this lab made of?

| File | What it is |
|---|---|
| `README.md` | this document — 16 stages, four depth layers, 6 prediction checkpoints |
| `visualization.html` | **The Overload Desk** — a service you can overload, then fix, one lever at a time · <https://claude.ai/code/artifact/175a445a-1314-4caa-b2f8-71cd11134949> |
| `EXERCISES.md` | eight rungs, pencil-and-paper plus the visualization |
| `SOLUTIONS.md` | worked answers |

### Where does this sit in your other labs?

Five of your labs named this topic as the thing they were missing. Each one hit
the same wall from a different direction.

- **`queueing-theory`** proved the cliff exists. It measured that at ρ ≥ 1 a
  queue grows without bound: 1,619 waiting after 30 minutes at just 5% over,
  and goodput of 64 while throughput was 7,711. It ended with the rule *"shed
  hard or don't bother"* without ever showing you a shedder. **This lab is that
  missing half.** Everything here is downstream of one result you already own:
  `W = 1/(μ−λ)` has a vertical asymptote, and no amount of engineering moves it.
  It is also where you met Dana, Arjun, Priya and Meera.
- **`distributed-rate-limiting`** listed shedding as one of the "four siblings"
  (timeout, breaker, bulkhead, shedding) "needed to survive". Its glossary then
  put *rate limit vs quota vs throttling vs load shedding* in a single line
  without separating them. Stage 7 separates them properly, and the difference
  turns out to be the whole design.
- **`microservices`** derived timeouts, retries, bulkheads and breakers from
  Little's Law, and left shedding in the glossary. Stage 13 puts it back where
  it belongs: shedding is the *only* one of the five that protects the service
  being crushed, rather than the caller watching it die.
- **`work-pressure-and-deadlines`** found the human version, "yes, and here is
  what comes out", and priced it. Same mechanism, different queue.
- **`caching`** and **`idempotency`** both assume a system that is still
  answering. This is what keeps it answering.

**New here.** Goodput as the only honest metric. Admission control as a
continuously-running control loop. The three independent levers: who gets in,
what order you serve, who you give up on. And the discovery that a static
setting which is perfect today is worth nothing tomorrow.

**Not here.** Autoscaling, because adding capacity is a different lever with a
multi-minute time constant, and shedding is what covers the minutes before it
arrives. Load balancing algorithms, since `/learn load-balancing` is still open.
And the queueing mathematics itself, which you already have.

### How were the numbers produced?

Every measured figure in this document comes from a discrete-event simulator: an
arrival stream, a pool of workers, a queue, clients with deadlines and retry
policies, and a pluggable admission controller. **The identical engine is
embedded in `visualization.html`**, so any number here can be reproduced by
pressing buttons on that page. It was run headlessly on JavaScriptCore.

Unless a table says otherwise, every figure is the **mean of five seeds** over
**ten minutes of simulated time**.

Before any teaching number was taken from it, the engine was checked against the
cases where mathematics already knows the exact answer — including the specific
figures your own `queueing-theory` lab established.

| What was checked | Known answer | Simulator | |
|---|---|---|---|
| Little's Law, `L = λW`, at ρ = 0.8 and 0.9 | identity — must hold | agreed to 0.4% and 0.2% | ✅ |
| M/M/1 mean time in system at ρ = 0.5 / 0.8 | 160 / 400 ms | 165 / 389 ms | ✅ |
| M/M/1 at ρ = 0.8 and 0.9, **mean of 8 seeds** | 400 / 800 ms | 398.8 / 805.4 ms | ✅ |
| M/D/1 at ρ = 0.8 (identical service times) | 240 ms | 240.2 ms | ✅ |
| D/D/1 (perfectly regular everything) | 80 ms, no waiting at all | 80.0 ms | ✅ |
| Four servers sharing one line, ρ = 0.8 | 60 ms queueing (`queueing-theory`'s figure) | 59.8 ms | ✅ |
| Kingman's approximation, `c²ₛ = 4`, ρ = 0.8 | ≈ 800 ms queueing | 813.7 ms | ✅ |

**One honest caveat.** At ρ = 0.95 a single ten-minute run scatters between
1,450 and 1,670 ms against a theoretical 1,600 ms. That is not a defect in the
engine. It is the genuine variance of a nearly-saturated queue, and it is why
every figure in this document is a multi-seed mean.

### What is asserted rather than run?

Where a claim is **reasoning** rather than a measurement, the text says so in
place. There is one such claim, and it is stage 11's F4.

The simulator models worker occupancy, not processor cycles. The 100% occupancy
during the slow-dependency scenario was measured; the statement that CPU would
read low during it is an inference from what those workers are doing, namely
waiting on a database. Everywhere else, a claim that a number was observed means
the simulator produced it on the run described beside it.

The Java and Spring lines in stage 3 are illustrative shapes rather than
verified runs, as that stage says. `LinkedBlockingQueue` with no capacity
argument is unbounded by its own javadoc, and `Integer.MAX_VALUE` is Spring
Boot's documented default queue capacity.

---

*Lab built 2026-08-30. Every measured figure produced by the discrete-event
engine embedded in `visualization.html`, validated against Little's Law, M/M/1,
M/D/1, D/D/1, M/M/4 and Kingman before use. Production claims are sourced to the
Google SRE book chapter 21, Facebook's "Fail at Scale" (ACM Queue, 2015), the
Envoy adaptive concurrency filter documentation, and Netflix's
`concurrency-limits`. Claims that are reasoning rather than measurement are
marked as such in place.*
