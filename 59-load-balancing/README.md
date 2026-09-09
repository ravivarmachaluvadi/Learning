# Load Balancing — you are not spreading load, you are choosing a queue

> **Tell a friend:** Omar stands at the door of Asha's shop and tells each
> arriving customer which of the eight counters to join. He scans nothing, he
> opens no new counter, and he makes nobody work faster. All he can change is
> who waits behind whom. So when Meera lands behind a fourteen-deep queue while
> the counter beside her stands empty, that is the whole of what went wrong.
> The hard part is that Omar cannot really see the queues, and a picture of
> them a quarter of a second old makes confident pointing worse than shutting
> his eyes.

The trainer is [`visualization.html`](visualization.html) — **Eight Lanes**:
race two rules on identical traffic, then break a counter. Where every number
came from, and how this lab sits beside your others, is in
[section 17](#17-about-this-lab).

---

# BEGINNER MODEL — stages 1 to 6

## 1. Why is every counter healthy while customers give up?

> **In one breath:** Eight servers, all busy, none overloaded, every one handed
> exactly the same number of requests. And one customer in a hundred still
> misses the deadline. Nothing is broken. The lines are simply uneven.

It is 14:00. Asha runs a checkout service on **eight identical servers**. Each
one finishes about **50 requests a second**. Eight of them is 400 a second, and
320 are arriving. That is **80% of what she can do**, a load level most teams
would call comfortable.

Her phone goes off. The mobile app is timing out for a noticeable slice of
customers.

She opens the dashboard. Here is what makes the incident memorable a year later.

| What Asha reads | The number | What it seems to say |
|---|---:|---|
| CPU, on all eight servers | between 74% and 86% | busy, nothing near a limit |
| Error rate | zero | nothing is throwing |
| Requests a second, per server | 40, on every one of the eight | perfectly even sharing |
| Mean response time | 140 ms | well inside the 500 ms SLO |
| **99th percentile response** | **561 ms** | **past the 500 ms SLO** |

Nothing is broken. Every server is healthy. Every server is getting exactly its
fair share. And about one request in a hundred is still missing its deadline.

There is no faulty machine to reboot. There is no bad deploy to roll back.

**Before the numbers pile up, here is what each one means.** Every table below
uses the same small set of measures, so learn them once.

| Column you will see | What the number is | Read it as |
|---|---|---|
| mean | average time from arrival to finished | the fleet's ordinary day |
| p50 | the median request's time | half do better, half do worse |
| p95, p99 | the unluckiest 1 in 20, 1 in 100 | who complains, and who leaves |
| worst queue | deepest single queue seen in the run | how bad the pile-up got |
| share sent to A | fraction of all traffic routed to one server | fair is 12.5% with eight |

Now the part that should feel wrong. Take those same eight servers. Take the
same 320 requests a second. Change **nothing** about the code or the hardware.
Have all eight feed from a **single shared queue**: one line, eight counters,
the way a bank does it rather than the way a supermarket does it.

| Same fleet, same traffic, ρ = 0.8 | mean | p99 | what actually changed |
|---|---:|---:|---|
| Eight separate queues, request sent to a random server | 139.9 ms | 561.5 ms | nothing yet — this is today |
| **One shared queue feeding all eight** | **82.1 ms** | **371.5 ms** | only where the line forms |

The 99th percentile goes from a breached SLO to a comfortable one. No new
server. No faster code.

Push the same fleet a little harder, to 90%, and the gap gets starker still.
Sending each request to a server chosen at random gives a mean of **244 ms** and
a 99th percentile of **995 ms**. Feeding the identical eight from one shared
line gives **91.5 ms** and **384 ms**. Nothing broke, nothing was tuned, and no
code got faster. That is **2.7× the latency, purely from where the line forms**,
and it is the entire budget a load-balancing algorithm is competing for.

There is a second fact that makes the subject hard, and stage 10 is where you
meet it. The obvious way to spend that budget is to send each request to
whichever server currently has the least work. That is the best rule in this
lab, *when its information is perfect*.

Now spoil the information. Give that rule a view of server load which is
**250 ms out of date**, and it becomes **2.9× worse than choosing at random**.
Point it at a fleet where one backend has started failing instantly instead of
working, and it sends that backend **66%** of all traffic. Its more
sophisticated cousin, which balances on observed response time, sends it
**100%**.

That is what load balancing is for. Not "spreading load" — the load was already
perfectly spread, 40 requests a second to every server, and the fleet still
missed its SLO. The job is subtler than spreading, and stage 4 is where the
difference becomes visible.

**One warning about the p99 numbers, before they mislead you.** Even at 30%
load the 99th percentile in this lab sits near 368 ms, against a mean service
time of 80 ms. That is not queueing at all. That is the work itself.

| Where the 370 ms floor comes from | Value |
|---|---:|
| mean time to do one request's work | 80 ms |
| the shape of that time | exponential, so a few requests are far longer |
| the unluckiest 1 in 100, from work alone | 80 × ln(100) ≈ 368 ms |

Treat about **370 ms** as the floor of every p99 column in this lab. Everything
above the floor is queueing. Queueing is the only thing a load balancer can do
anything about at all.

---

## 2. What does Meera see when she walks in?

> **In one breath:** Eight counters, three of them empty, one of them fourteen
> people deep. Meera has to pick a line the moment she walks in, and whoever
> picks for her decides her whole afternoon.

Give the eight servers names: **A, B, C, D, E, F, G, H**. Each has four worker
threads and takes about 80 ms per request. So each finishes 50 a second.

Now the shop. Asha's shop has eight counters with the same names. Behind each
counter, four assistants work in parallel. In front of each counter, one queue.

**Omar** stands at the door. When a customer walks in, Omar points at a counter.
That is his entire job. He never scans an item and he never opens a counter.

A customer named **Meera** arrives at 14:00:00.000. Something must decide, in
microseconds and with no time to think, which of A to H she goes to. In the
service, that something is a **load balancer**: a box, or a library, or a kernel
routing rule, that sits in front of the eight and forwards each arriving request
to one of them. Omar is that box.

Here is the state of the fleet at the exact instant Meera arrives. These are
real queue depths lifted from a run at ρ = 0.9.

```
   A       B       C       D       E       F       G       H
 ██████  ░       ███     ██████████████  ░       ██      ░       ████
   6       0       3            14        0       2       0        4
```

Thirty-five people are waiting across the eight queues. Three counters are
**idle**. One counter has **fourteen** people in front of it. At 80 ms each and
four assistants, that is about 280 ms of waiting before the next arrival is even
looked at.

This is the whole subject in one picture. Send Meera to D and she waits 280 ms
before anyone touches her work. Send her to B and she waits nothing.

> **🔮 Predict.** *Omar shuts his eyes and points at a counter at random. What
> is Meera's chance of landing in one of the three empty queues, and what is her
> chance of landing behind the fourteen?*
> <details><summary>Reveal</summary>
> Three empty queues out of eight is a **37.5%** chance of a zero wait, and a
> **12.5%** chance of the 280 ms wall. If you said "she will probably be fine
> because the average queue is only 4.4 deep", you have made the exact mistake
> the whole lab is about. Meera does not experience the average. She experiences
> one queue, chosen for her, and the spread across the eight is what decides her
> afternoon.
> </details>

Nothing about the fleet is unhealthy. The *average* queue is 4.4 deep, which is
fine. The problem is the **spread**.

**Why is D so far ahead of B?** Nobody did anything wrong. Requests arrive at
random moments. They take random amounts of time. Over a few seconds, some
counters get unlucky twice in a row and others get a gap. Randomness clumps.
That is the entire explanation.

> **The analogy, and it is only an entry point.** Asha's shop is a supermarket.
> Eight checkouts, and you have to pick a line the moment you walk up, with a
> trolley you cannot re-park. Everyone knows the feeling of picking the line
> that stalls. The bank across the road solved this decades ago: one line, and
> the next free teller takes the front of it. The bank's arrangement is strictly
> better and everybody knows it. **The rest of this lab is about why a web
> service cannot simply have the bank's arrangement, and how close it can get.**

**How far this lab goes.** Stop at any checkpoint and you still have something
whole.

| Layer | Stages | After it you can… |
|---|---|---|
| Beginner | 1–6 | say why a perfectly even fleet still breaks its SLO |
| Intermediate | 7–9 | name every part, and watch the failure happen on screen |
| Engineering | 10–12 | derive all four rules, and say when each one turns harmful |
| Production | 13–17 | configure a real balancer in the order that actually matters |

---

## 3. Why not just take turns?

> **In one breath:** Omar keeps a counter in his head and sends customer 1 to A,
> customer 2 to B, and so on round the eight. It costs nothing, it is exactly
> fair, and it recovers about half of what random pointing wastes.

The first idea any engineer has, and it is a genuinely good one: **round robin.**
Keep a counter. Send request 1 to A, request 2 to B, and so on to H. Request 9
goes back to A. Perfectly even. Impossible to argue with.

Its virtues are real and they are not small.

- It needs **no information at all** about the servers. No health data, no load
  data, no round trip to ask anybody anything. One integer.
- It is **exactly fair in counts**. Over any window, every server gets one
  request in eight, to within one.
- It is trivially cheap. It is what almost every load balancer ships with as the
  default. NGINX's default is round robin. HAProxy's `roundrobin` is its
  most-used algorithm.

And it works. Here are the figures against random dispatch at ρ = 0.9, where
360 requests a second arrive at a fleet that can finish 400.

| ρ = 0.9, 8 backends | mean | p99 | worst queue seen |
|---|---:|---:|---:|
| random | 244.0 ms | 994.9 ms | 80 |
| **round robin** | **156.8 ms** | **605.7 ms** | **42** |
| one shared queue (the unreachable ideal) | 91.5 ms | 384.1 ms | — |

Round robin removes about half the penalty that random pointing pays. That is a
real improvement, bought for the price of one integer.

So why does the story not end here?

> ### 🔮 Predict — checkpoint 1
>
> Suppose you removed *all* the randomness from the work itself. Every single
> request takes **exactly** 80 ms, with no variation whatsoever. Arrivals are
> still unpredictable, as they always are, but the work is perfectly uniform.
>
> Under those conditions, how close does round robin get to the shared-queue
> ideal? Within 50%? Within 10%? Commit to a number before reading on.

---

## 4. Where does taking turns lose?

> **In one breath:** Round robin is not an algorithm with a quality. It is a
> bet that every customer's trolley is the same size. It pays out exactly as far
> as that bet is true, and in a real service the bet is usually false.

Here is the answer to checkpoint 1, at ρ = 0.8, varying only how much request
*sizes* vary.

The measure of that variation has a name you already own from
`queueing-theory`. It is the squared coefficient of variation of service time,
written **c²ₛ**. In words: how unequal the trolleys are. Zero means every
customer has an identical basket. One is the ordinary case. Sixteen means a
small tail of customers is doing a month's shopping.

| c²ₛ | what it means in the shop | random p99 | **round robin p99** | ideal p99 |
|---:|---|---:|---:|---:|
| 0 | every basket identical, 80 ms exactly | 277.7 ms | **99.7 ms** | 99.7 ms |
| 1 | ordinary variation (exponential) | 561.5 ms | 429.5 ms | 371.5 ms |
| 4 | some trolleys much bigger | 1,324.9 ms | 1,231.2 ms | 855.8 ms |
| 16 | a heavy tail — 3% of trolleys cost 16× | 3,847.2 ms | **3,863.0 ms** | 1,460.0 ms |

Read the two ends of the round-robin column, because that is the finding.

**At c²ₛ = 0, round robin ties the ideal exactly.** 99.7 ms against 99.7 ms. It
is not an approximation of the shared queue. Under identical work it *is* the
shared queue. Sending every eighth customer to A, when every basket costs the
same, is precisely the same thing as A taking the front of one shared line.

**At c²ₛ = 16, round robin is 3,863 ms and pointing at random is 3,847 ms.**
Round robin has become slightly worse than not thinking at all. Both are 2.6×
the ideal.

So round robin is a **bet**: *all requests cost about the same*. It pays out in
exact proportion to how true that bet is. In a real HTTP service it is usually
not very true. `GET /health` and `POST /checkout` do not cost the same. A cache
hit and a cache miss do not cost the same. A customer with 3 items and a
customer with 300 do not cost the same.

### Why does random dispatch still lose when every basket is identical?

Look again at the c²ₛ = 0 row. Random dispatch is **277.7 ms** where the ideal
is **99.7 ms**. Every request costs exactly 80 ms. There is no variation in the
work whatsoever. And random dispatch is still 2.8× off the ideal.

Where does that come from? **Arrivals clump even when work does not.** In any
short window, some counters get three customers and others get none, purely by
chance. Engineers call this the balls-in-bins effect. Round robin fixes exactly
this, which is why it ties the ideal in that row.

But it tells you something important about what the enemy really is.

> There are **two independent sources of imbalance**. One is randomness in
> *where requests go*. The other is randomness in *how big they are*. A dispatch
> rule has to defeat both. Round robin defeats the first completely and the
> second not at all. Random defeats neither.

### Which failure is the one that pages Asha?

Both failures above cost you percentiles. This one costs you the service.

Take the eight-server fleet at ρ = 0.8. Now make **server A five times slower**
than the rest. In the shop, that is Arjun on counter A, working through treacle:
a bad disk, a noisy neighbour on the hypervisor, a GC pathology, a config that
did not roll back cleanly.

Arjun has not stopped. He still greets every customer. He would still pass any
health check that only asks "are you there?". He is simply five times slower.
Engineers call this a **gray failure**: degraded, not dead.

Omar, taking turns and knowing nothing, sends counter A exactly 12.5% of the
customers, as always.

> **🔮 Predict.** *Arjun can now finish 10 requests a second. Omar keeps sending
> him 40. The other seven counters are fine and the fleet as a whole is only at
> 80%. What does the fleet's p99 become?*
> <details><summary>Reveal</summary>
> **331 seconds.** Not milliseconds. If you guessed "roughly five times worse",
> you assumed the queue settles somewhere. It does not settle. Arjun's queue
> grows by 30 requests every second and never stops growing, so the answer is
> not a latency at all — it is a statement about how long the run lasted.
> </details>

| ρ = 0.8, server A is 5× slow | share sent to A | fleet mean | fleet p99 |
|---|---:|---:|---:|
| round robin | 12.5% | 8,629 ms | **330,967 ms** |
| random | 12.5% | 8,692 ms | 332,577 ms |
| least connections | 3.0% | 102.7 ms | 571.0 ms |
| power of two choices | 3.1% | 131.5 ms | 978.2 ms |

A p99 of **331 seconds**. Do not read that as a measurement of anything, because
it is not one. Arjun at 5× service time can finish 10 requests a second. Round
robin sends him 40. His queue grows by 30 requests every second, forever.

Run it longer and the number simply gets bigger.

| simulated horizon | round robin, fleet p99 | what the number is telling you |
|---:|---:|---|
| 5 minutes | 172,782 ms | the length of the run |
| 10 minutes | 330,000 ms | double the run, double the number |
| 20 minutes | 652,294 ms | still doubling; nothing has settled |

It doubles when the window doubles. That is the signature of an unbounded queue.
It is `queueing-theory`'s ρ ≥ 1 cliff. And it was not reached because the fleet
was overloaded, because the fleet is sitting at 80%. It was reached because
**the dispatcher insisted on giving a sick counter a healthy counter's share of
the work.**

And this is 12.5% of Asha's customers. Not 12.5% slower. Twelve and a half
percent *never answered*. Seven perfectly good counters with spare capacity are
standing right next to the queue that is eating her traffic.

---

## 5. What are we actually asking for, in plain words?

> **In one breath:** Nobody should have to wait behind a long queue while a
> short queue exists. That is the entire requirement, and everything else in
> this lab is the difficulty of achieving it.

Three failures, one underlying demand. Before any vocabulary, here it is in
plain language.

> **No request should be made to wait behind a long queue while a short queue
> exists.**

Notice what that does *not* say.

- It does not say "give every counter an equal number of customers". That is
  what round robin guarantees, and it was not enough. It is exactly what Asha's
  dashboard showed during the stage-1 outage while the SLO was breached.
- It does not say "give every counter an equal amount of work". That is closer,
  but it is still a statement about counters. Meera does not care whether the
  shop is balanced. She cares whether *her* line is behind a wall.
- It says nothing about efficiency, throughput or utilisation. **Omar cannot
  change how much work gets done.** All eight counters are finishing customers
  as fast as they can either way. He can only change *who waits*.

Stated as an engineering property, the requirement is about **variance, not
mean**. The mean queue length is fixed by arithmetic: total work in, total
capacity available. `queueing-theory`'s Little's Law says so, and it is an
identity rather than something an algorithm can argue with.

The only thing a dispatch rule controls is how that unavoidable total is
**distributed across the eight queues**. The closer to equal it keeps them, the
closer the fleet gets to behaving like one big server.

Which gives the sharp version.

> **A load balancer's job is to make N separate queues behave as much like one
> shared queue as it can, using information it does not really have.**

The second half of that sentence is where the next ten stages live.

---

## 6. What if Omar simply looks at the queues?

> **In one breath:** Stop guessing and look. Send each customer to whichever
> counter has the fewest people waiting. It is not one heuristic among many. It
> is the requirement, written out as an instruction.

If the problem is that some queues are long while others are short, the fix
writes itself. **Before pointing, look at the queues, and send the customer to
the shortest one.**

That is it. That is the central idea of load balancing, and the rest of the
subject is consequences.

It has an attractive property that round robin lacks. Round robin had to
*assume* that all trolleys are the same size. Looking at the queue assumes
nothing. A counter that got three enormous trolleys has a long queue. A counter
that got three small baskets has a short queue. Omar sees the difference without
ever being told what a trolley costs.

**A measured signal beats an assumed one.**

Here is what happens at ρ = 0.9 when every request goes to the server with the
fewest outstanding requests.

| ρ = 0.9 | mean | p99 | worst queue |
|---|---:|---:|---:|
| random | 244.0 ms | 994.9 ms | 80 |
| round robin | 156.8 ms | 605.7 ms | 42 |
| **fewest outstanding requests** | **93.9 ms** | **390.8 ms** | **9** |
| one shared queue (the ideal) | 91.5 ms | 384.1 ms | — |

**93.9 ms against an unreachable ideal of 91.5 ms.** It captures 98% of the
available gap.

It handles Arjun's slow counter automatically. His share drops to 3.0% instead
of 12.5%, because a slow counter's queue stays long and so it stops being
picked. It handles heavy-tailed trolleys too: at c²ₛ = 16 it lands on 1,472 ms
against an ideal of 1,460 ms, while round robin was at 3,863 ms.

It looks finished.

> **Checkpoint — what you can explain now.** You can say why a fleet where every
> server has identical CPU and identical request counts can still miss its SLO.
> You can say why round robin is a bet on uniform request sizes, and what it
> costs when that bet is wrong. And you can say why "send it to the shortest
> queue" is not one heuristic among several, but the direct expression of the
> requirement.
>
> **What the next layer adds.** Everything above assumed Omar can see the true
> state of all eight queues, instantly and for free. He cannot. Stage 10 removes
> that assumption one piece at a time, and the rule that is essentially optimal
> above becomes, under entirely ordinary conditions, **worse than random**.

> **Say it back.** Without using the words *balance*, *spread* or *algorithm*,
> tell a friend what Omar is for. Your answer has to survive the objection
> *"but taking turns already gives every counter the same number of customers,
> and the shop still failed."*
> <details><summary>A good answer sounds like</summary>
> "Omar cannot make anyone faster and he cannot open a counter. All he does is
> stop you from standing behind fourteen people when the counter next to you is
> empty. Giving every counter the same *number* of customers does not do that,
> because customers are not the same size. One counter can be handed eight small
> baskets while the next is handed eight full trolleys. Both got eight. Only one
> of them is in trouble."
> </details>

---

# INTERMEDIATE MODEL — stages 7 to 9

## 7. What do engineers call all of this?

> **In one breath:** Six words. Two of them are pairs that get confused so often
> that the confusion is worth more attention than the definitions.

You have now met every idea in this lab at least once. Here are the names, with
the distinctions that actually carry weight.

### What exactly is a backend, and where is its real boundary?

In the shop, a backend is one counter: A, B, C and so on to H. Omar can send a
customer to it, and after that the customer is in that counter's line and cannot
move. Other names for the same thing are *upstream*, *real server*, *origin*,
*target*, *endpoint* and *pod*.

Said precisely, a backend is **an independently queueing unit of capacity**.
That phrase is the load-bearing one. What makes something a backend is not that
it is a machine. It is that it has **its own queue**, which the balancer cannot
see into and cannot reach across.

Why does the distinction matter? Because it tells you where the real boundary
sits, and that is often not where the org chart says. Eight pods on one node
sharing a CPU are eight backends to the balancer and roughly one queue in
reality. One pod with 200 threads is one backend with a large internal shared
queue. If two "backends" share a bottleneck, balancing between them buys you
nothing. That is the most common reason a balancer change produces no
improvement at all.

Vocabulary you already have from elsewhere: `consistent-hashing` called these
*nodes*, `microservices` called them *instances*, Envoy calls the set of them a
*cluster* and each one a *host*, and NGINX calls the set an *upstream*.

### What is a dispatch rule, stated exactly?

In the shop, it is whatever Omar does in his head before he points. Products
call it the algorithm, the balancing policy, or `lb_policy`.

Said precisely, it is a function. It takes three things and returns one backend.

| What goes in | In the shop | Why it matters |
|---|---|---|
| the dispatcher's view of backend state | what Omar thinks he can see | this is where all the difficulty lives |
| some randomness | Omar's coin | it is what stops every doorman agreeing |
| the request itself | who the customer is | it is what makes stickiness possible |

Those three inputs are the three axes on which every algorithm in this lab
differs. The first one, **the view**, is where all the difficulty is.

### Utilisation, ρ (rho) — whose ρ is it?

You have this from `queueing-theory`. It is arrival rate divided by service
capacity. Here it always means the **fleet's** ρ unless a sentence says
otherwise. 320 requests a second against a fleet that can do 400 is ρ = 0.8.

The individual backends' ρ can be wildly different from the fleet's. In every
interesting scenario in this lab, it *is*. Arjun's counter in stage 4 was past
ρ = 1 while the fleet sat at 0.8.

### What is "the view", and how old is it (τ)?

The view is what Omar believes about each counter. Its age is how long ago he
last looked. Engineers write that age as **τ** (tau), in milliseconds.

There are three genuinely different situations here. Mixing them up is the
single most common source of wrong reasoning about load balancers.

| The view | What the dispatcher knows | Where you actually find it |
|---|---|---|
| **exact** | the true, current state of every backend | only with one dispatcher that is the sole source of traffic |
| **stale (τ)** | a snapshot of the truth, refreshed every τ ms | any design that gossips or scrapes load statistics |
| **local** | only the requests *this dispatcher* has outstanding | every real multi-instance load balancer, by default |

The third is the normal case and it is the one people forget. Run 20 NGINX
instances with `least_conn` and **none of them knows the fleet's least-loaded
backend**. Each knows only the least-loaded of its own one-twentieth share.
Stage 10 measures what that costs.

### Which two pairs of words get confused?

**Load balancing against sharding, also called partitioning.** Both send a
request to one of N machines. The difference is whether the choice is **free**.

- *Balancing*: any backend can serve this request, so the dispatcher may choose
  on the basis of load. Choice is free, so use it.
- *Sharding*: only one machine has the data, or the lock, or the session. The
  destination is determined by the key. Choice is not free, and load is whatever
  the key distribution makes it.

This is exactly the boundary `consistent-hashing` was working on, seen from the
other side. That lab was about making the sharding function well-behaved when
the machine set changes. **Turn on sticky sessions and you convert a balancing
problem into a sharding problem**, inheriting every hot-key problem that lab
described. F5 in stage 11 puts numbers on it.

**Load balancing against load shedding.** From `load-shedding`, sharpened.

- *Balancing* decides **which** queue a request joins. It assumes the request is
  going to be served and only asks where. It cannot help you at ρ ≥ 1, because
  then every queue is growing and there is no good one.
- *Shedding* decides **whether** a request joins any queue at all. It is the
  only tool that works when the fleet genuinely cannot do the work.

The clean statement: **balancing buys you the last 15% of your fleet's usable
capacity, and shedding is what happens after that.** They are complements. They
are usually the same box, which is why they get conflated. Stage 13 shows what
goes wrong when they are configured without reference to each other.

### Which two terms are deliberately held back until stage 10?

The first is *herd*, also called a stampede or a thundering herd. The second is
*power of two choices*. Both are introduced at the exact moment the problem that
names them appears, because a definition given earlier would not mean anything
yet.

---

## 8. What picture should you carry in your head?

> **In one breath:** A supermarket that is trying to behave like a bank, using
> only what it can see from the door. That one sentence predicts almost every
> result in the rest of this lab.

Two mental models, and they are the same machine seen twice. The first is
Asha's shop, which you can picture. The second is the engineer's, which you can
compute with. This stage builds both and then maps each part of one onto the
other.

### The shop and the bank: why is one line better than eight?

Asha's shop has eight checkouts. You must pick a line on arrival and you cannot
switch. Some customers have three items and some have three hundred, and you
cannot tell which from behind.

The bank across the road has one line and eight tellers. When a teller frees up,
the person at the front walks over. Nobody ever waits behind a 300-item trolley
while a teller stands idle.

**The bank's arrangement is strictly better and it is not close.** The gap
between the two, on identical hardware and identical traffic, is stage 1's
table: 2.7× the mean and 2.6× the p99 at ρ = 0.9.

So why does every web service use the supermarket?

Because the bank's single line requires the *tellers* to pull work when they are
free. A backend server cannot pull. The network only goes one way. The request
has to be sent somewhere before anyone knows who will be free next.

**A load balancer is a supermarket trying to behave like a bank, using only what
it can see from the door.**

### The engineering model: what state does Omar actually have?

Signposting the diagram before it appears. Arrivals come in at the top. Omar is
the box in the middle, holding a belief about each counter. The eight queues sit
underneath, with their four workers each.

```
                    ┌─────────────────────────────────────┐
   arrivals  ───►   │  DISPATCHER                          │
   λ req/s          │    view v[] : belief about each      │
                    │              backend, age τ          │
                    │    rule f(v, random, request) → i    │
                    └───────┬──────┬──────┬────────────────┘
                            │      │      │
                   ┌────────▼┐  ┌──▼─────┐│  ┌─────────┐
                   │ queue A │  │queue B ││  │ queue H │   … 8 of them
                   │ ██████  │  │        ││  │ ████    │
                   ├─────────┤  ├────────┤│  ├─────────┤
                   │ w w w w │  │w w w w ││  │ w w w w │   4 workers each
                   └─────────┘  └────────┘└──└─────────┘
                    μ = 50/s      μ = 50/s     μ = 50/s
```

The state that matters is small. Four things, and each is a thing in the shop
before it is a symbol.

| The thing in the shop | In words | Written |
|---|---|---|
| how many people are actually at counter i | requests waiting there, plus those being served — the truth | `q[i]` |
| what doorman `lb` thinks is at counter i | the belief the rule is allowed to use | `v[lb][i]` |
| how long ago the doorman last looked | how far the belief runs behind the truth; zero only in textbooks | `τ` |
| how fast counter i really is | its service rate, not the same for all counters and not constant in time | `μ[i]` |

The rule itself is one line of code. Every algorithm in this lab differs only in
that line.

```
random          →  i = randInt(n)
round robin     →  i = counter++ % n
least conn      →  i = argmin over all n of v[i]
power of two    →  a = randInt(n); b = randInt(n); i = v[a] < v[b] ? a : b
```

And here is the frame that makes the rest of the lab predictable.

> **Every dispatch rule is a bet about the world, and its performance is exactly
> how right that bet is.**
>
> - *random* bets nothing, and gets nothing.
> - *round robin* bets **all requests cost the same**.
> - *least connections* bets **my view of the queues is true**.
> - *power of two* bets **almost nothing, and only about two backends at a time**.
>
> The order of that list is not increasing quality. It is increasing *exposure*.
> Stage 10 collects on each bet in turn.

### Which part of the shop is which part of the model?

Read this table left to right when you want the story, and right to left when
you want to know where a number came from.

| In Asha's shop | In the engineering model | Where the figure is |
|---|---|---|
| eight checkouts | eight backends, each with its own queue | the standing 8 × 4-worker fleet |
| Omar picking a line at the door | the dispatch rule `f` | stages 3, 6 and 10 |
| Omar glancing down the aisles | the view `v` | stage 10, v3 |
| the glance being out of date | staleness τ | stage 10, the herd |
| trolley size you cannot see | service-time variability c²ₛ | stage 4's table |
| one till with a broken scanner | a slow backend, small `μ[i]` | stage 4's 331-second p99 |
| the bank's single line | shared queue, `queueing-theory`'s M/M/k | the "ideal" column everywhere |

### Where does the shop story lie to you?

Four places, and all four are load-bearing.

**1. You can see all eight lines. Omar usually cannot.** This is the biggest lie
in the story, and it is the whole subject of stage 10. A customer gets a free,
instant, perfectly accurate view of every queue. A dispatcher gets a view that
is late, or partial, or both. The whole difficulty of load balancing is that
acting confidently on a bad view is worse than acting humbly on none.

**2. There is only one of you. There are usually many dispatchers.** In the
shop, when you pick the shortest line you are the only person deciding at that
instant. Now imagine two hundred customers walking in together and all picking
"the shortest line". They would all pick the *same* line. That is the herd. It
does not exist in the story, and it dominates the engineering.

**3. A checkout does not get slower when its queue is long. A server does.**
Deeper queues mean more memory held, more context switching, worse cache
locality, longer GC pauses and more connection state. Real backends have a `μ`
that *falls* under load. That turns "slightly overloaded" into "collapsing"
faster than any model here shows. Both `queueing-theory` and `load-shedding`
have figures for this. **Every number in this lab is therefore optimistic.**

**4. A checkout is either open or closed. A server has a third state.** It can
be *answering instantly and uselessly*, returning 500s in one millisecond. To
every load-aware rule in this lab, that looks exactly like the fastest,
emptiest, healthiest counter in the shop. Stage 11's F1 puts a number on the
consequence, and it is the most surprising number in the lab.

---

## 9. What should you open, and what should you look for?

> **In one breath:** Open the page and race two rules against each other on the
> identical traffic. When the two panels diverge, the only thing that could have
> caused it is the decision.

Open **`visualization.html`**. It runs the same engine as this document. The
live panels show a rolling window of the last few thousand requests, and the
three *sweep* buttons re-run the numbers behind the tables above.

It is built around one deliberate choice: **two dispatch rules race on the
identical arrival stream.** Same requests, same sizes, same arrival times. The
only difference is the rule.

Four tabs, in the order this document introduces them.

**1. The eight lanes.** Watch Asha's queues form in real time. Pick a rule, pick
a load level, watch the bars. At 50% load every rule looks identical and the
bars stay short. At 90% the random and round-robin lanes develop tall spikes
that persist for seconds while other lanes sit empty. The number to watch is not
the average bar height. It is **the gap between the tallest and the shortest.**

**2. Race two rules.** The same traffic, two rules, side by side, with a running
p99 for each. Run round robin against least-connections with the "request sizes
vary" slider at its lowest setting and they tie. Push the slider up and watch
round robin come apart while least-connections barely moves. You are watching a
bet lose.

**3. The stale view.** The herd, made visible. There are two sets of bars: what
is **true**, and what Omar **believes**. With the staleness slider at 0 they are
the same picture. Push it to 250 ms and watch him pour every arrival into
whichever lane was empty at the last refresh, long after it stopped being empty.
The lanes take turns being crushed. This is the one animation in the lab that
explains something words struggle with.

**4. Break a server.** Failure injection. Make one counter slow, or make it fail
instantly, and watch the share of traffic each rule sends it. The direction
flips between those two cases. Under "slow", the load-aware rules protect you
and round robin destroys you. Under "fails instantly", the load-aware rules feed
the corpse and round robin is the safest thing on the page.

Each tab has a **predict before you run** panel. Use it. The stage-11 results in
particular are far more memorable if you have committed to a wrong number first.

> **Say it back.** Explain to a friend, without the words *stale* or *view*, why
> a doorman who looks at the queues once every quarter-second can do more damage
> than a doorman who never looks at all.
> <details><summary>A good answer sounds like</summary>
> "He looks, sees that counter E is empty, and then sends everybody to E for the
> next quarter of a second — because as far as he knows, E is still empty. By
> the time he looks again, E has ninety people and everyone else has none. So he
> sends everybody to the next empty one. He is not spreading customers out. He
> is marching them from counter to counter in a block."
> </details>

---

# ENGINEERING MODEL — stages 10 to 12

## 10. How would you build Omar, one repair at a time?

> **In one breath:** Four versions of the doorman. Each one exists because
> something broke in the version before it. By the end, the best rule on the
> page is the one that looks at the least.

We now build the dispatcher for real. Each version is the answer to something
that broke in the previous one.

**The standing setup for everything below**, unless a line says otherwise:
eight backends, four workers each, 80 ms mean service time, exponentially
distributed. Each backend finishes 50 requests a second and the fleet finishes
400. So `ρ = 0.9` means 360 requests a second arriving.

---

### v1 — what if Omar points at random?

```
i = randInt(8)
```

Omar shuts his eyes and points. No state, no coordination, no configuration, and
no failure modes of its own. It is correct with any number of doormen and any
fleet size.

It is also what you get free from DNS round robin, from ECMP hashing in a
router, and from any hash of the client's address. So it is far more widely
deployed than anyone chooses to deploy it.

| ρ = 0.9, one measure per row | random | one shared line (ideal) |
|---|---:|---:|
| mean — the ordinary request | 244.0 ms | 91.5 ms |
| p50 — the median request | 184.5 ms | 68.5 ms |
| p95 — the unluckiest 1 in 20 | 672.2 ms | 254.7 ms |
| p99 — the unluckiest 1 in 100 | 994.9 ms | 384.1 ms |
| worst queue — deepest pile-up seen | 80 | — |

**2.7× the mean and 2.6× the p99 of the ideal.** Where does all that go?

Entirely into imbalance. At any instant some counters have queues of 15 while
others are idle, and the idle ones are capacity Asha already paid for.

**What breaks v1:** nothing dramatic. It just wastes about 60% of the difference
between "eight servers" and "one server eight times bigger". It is the baseline,
not a failure.

---

### v2 — what if Omar takes turns?

```
i = counter++ % 8
```

One integer of state. It removes randomness from *placement*, so every counter
gets exactly one customer in eight.

| ρ = 0.9 | mean | p99 | worst queue |
|---|---:|---:|---:|
| random | 244.0 | 994.9 | 80 |
| **round robin** | **156.8** | **605.7** | **42** |
| ideal | 91.5 | 384.1 | — |

It recovers roughly 40% of the gap. And from stage 4, when every request costs
exactly the same it recovers **all** of it: 99.7 ms against an ideal of 99.7 ms.

**What breaks v2.** Three things, in increasing order of severity.

*(a) Request sizes vary, and that is the normal case.* From stage 4, at
c²ₛ = 16 round robin lands on 3,863 ms and random on 3,847 ms. They are
indistinguishable. Round robin balances *counts*, and counts are only a proxy
for work when work is uniform.

*(b) Backends are not identical.* Fleets are heterogeneous more often than
anyone admits. Mixed instance types after a capacity top-up. One node on
degraded hardware. One node in a different availability zone with different
network latency. One node that has just started and has not JIT-compiled
anything yet. Round robin sends every one of them exactly one eighth of the
traffic. Stage 4's 5×-slow counter produced an unbounded queue on a fleet
running at 80%.

*(c) The deploy case, which is (b) but happens to everyone, every week.* Take
the eight-backend fleet at ρ = 0.8 and restart one backend. For its first two
minutes it runs 5× slow: a cold JVM with an interpreted hot path, empty caches,
a connection pool still filling. In the shop, Arjun has just started his shift
and has not found anything yet.

Over that two-minute window:

| ρ = 0.8, backend A cold for 2 minutes | share sent to A | p99 | p99.9 | worst |
|---|---:|---:|---:|---:|
| **round robin** | **12.5%** | **76,293 ms** | 88,633 ms | 89,968 ms |
| power of two choices | 3.1% | 1,002.6 ms | 1,913.4 ms | 3,244.1 ms |
| least connections | 3.0% | **580.3 ms** | 1,524.9 ms | 2,989.1 ms |

**A 76-second p99 caused by a routine restart.** The dispatcher insisted on
giving a machine that could do 10 requests a second exactly 40 of them. The
load-aware rules noticed within a few hundred milliseconds and backed off to 3%.

This is not an exotic failure. It is Tuesday.

---

### v3 — what if Omar looks at the queues?

```
i = argmin over all backends of v[i]      // v[i] = outstanding requests
```

In words: of everything Omar believes about the eight counters, pick the
smallest, and send the customer there. `argmin` is just "the position of the
smallest one".

Every product that ships this calls it **least connections**: `least_conn` in
NGINX, `leastconn` in HAProxy, `LEAST_REQUEST` in Envoy. Envoy's name is the
honest one, and stage 14 explains why.

Assume for now that the view is perfect. One doorman, instant and exact
knowledge of all eight queues.

| ρ = 0.9, exact view | mean | p99 | worst queue |
|---|---:|---:|---:|
| random | 244.0 | 994.9 | 80 |
| round robin | 156.8 | 605.7 | 42 |
| **least connections** | **93.9** | **390.8** | **9** |
| ideal | 91.5 | 384.1 | — |

**It captures 98% of the gap.** And unlike round robin it assumes nothing about
request sizes. So it survives the c²ₛ table, at 1,472 ms where the ideal is
1,460 and round robin is 3,863. It survives the slow counter, at a 3.0% share.
It survives the cold restart, at a 580 ms p99.

Why does counting *outstanding* requests work so well when counting *dispatched*
requests worked so badly?

Because outstanding is a **feedback** signal. A request leaves the count only
when the backend finishes it. So a backend that is slow for any reason at all
accumulates outstanding requests and stops being chosen. Big requests, a slow
disk, a cold cache, a neighbour stealing CPU: all of them show up the same way.

**Omar never needs to know why.** That is the whole reason to prefer measurement
over assumption, and it is a principle far bigger than load balancing.

> ### 🔮 Predict — checkpoint 2
>
> Least connections is within 3% of a theoretical optimum you cannot otherwise
> reach. Before reading on: what is the **smallest, most ordinary change** to
> the situation that would make it perform *worse than choosing at random*?
>
> Not a bug, not a broken server, not a pathological workload. Something present
> in essentially every production deployment.

---

### What breaks v3? Any delay at all, or any second doorman

The perfect view in that table required **one dispatcher** with **instant**
knowledge. Remove either assumption and it falls apart.

#### Break 1 — what if the view is τ milliseconds old?

Real dispatchers do not have a live feed of every backend's queue. They have
statistics, and statistics arrive on a schedule: Envoy's load-report interval, a
Kubernetes metrics scrape, a gossip round, a health-check response carrying a
load header.

Call the age of the view **τ**. In the shop, τ is how long ago Omar last walked
down the aisle and looked.

Everything else identical, ρ = 0.9, sweeping τ:

| view age τ | least connections: mean | p99 | worst queue |
|---:|---:|---:|---:|
| 0 ms (perfect) | 93.9 | 390.8 | 9 |
| 10 ms | 106.2 | 409.9 | 15 |
| 50 ms | 196.2 | 614.6 | 36 |
| **100 ms** | **330.7** | 942.2 | 56 |
| 250 ms | 708.4 | 1,915.7 | 117 |
| 500 ms | 1,274.9 | 3,497.2 | 205 |
| 1,000 ms | 2,335.7 | 6,606.9 | 382 |
| *(random dispatch, for comparison)* | *244.0* | *994.9* | *80* |

Read the bolded row against the italic one, and be precise about which statistic
crosses over when.

**At τ = 100 ms least connections is already worse than random on the mean**,
at 330.7 against 244.0, though its p99 is still marginally better. **By
τ = 250 ms it is worse on both.** That is 708.4 against 244.0 on the mean, which
is **2.9× worse than a dispatcher that knows nothing at all and admits it**, and
1,915.7 against 994.9 on the p99. At τ = 1,000 ms it is 9.6× worse than random,
and 25× worse than itself with a perfect view.

**The mechanism, concretely.** At 14:00:00.000 the snapshot is taken. Counter E
happens to be empty. For the next 250 ms, `argmin` returns E for **every single
arrival**, and there are 90 of them, because the view still says E is empty.

E's real queue goes from 0 to 90 while the other seven drain to nothing. At
14:00:00.250 the new snapshot arrives. E is now catastrophically the worst, and
the entire stream swings onto whichever counter is now emptiest. And then it
does the same thing again.

The fleet oscillates. Every counter takes a turn being crushed. **The average
utilisation is still exactly 90%**, so Asha's dashboard shows nothing, while the
p99 is five times what it should be.

This is a **herd**, also called a stampede or a thundering herd: many
independent deciders, given the same information, making the same choice at the
same moment, and thereby destroying the thing they were choosing.

> **The general law, worth carrying out of this lab.** Stale load information is
> not merely less useful than fresh. It is **actively harmful, in a way that
> gets worse the more decisively you act on it.** A rule that commits fully to
> the best-looking option converts a small delay into a large oscillation. This
> is a positive feedback loop, and it is the same shape as `load-shedding`'s F3
> and the retry storms in `distributed-rate-limiting`.

#### Break 2 — what if there are three doormen and none sees the shop?

Even with **zero** delay, a real deployment has several load balancers, and each
one knows only about **its own** outstanding requests.

Asha's shop has three doors. Omar is on one, Dev is on the second and Ben is on
the third. Each of them keeps a perfect count of the customers *he* sent, and
none of them can see the other two doors at all.

Twenty NGINX pods running `least_conn` are twenty separate dispatchers, each
perfectly informed about its own one-twentieth of the traffic and blind to the
other 95%.

Same fleet, ρ = 0.9, each dispatcher tracking only its own requests:

| dispatchers | least connections mean / p99 | power of two mean / p99 | round robin mean / p99 |
|---:|---:|---:|---:|
| 1 | **93.9** / 390.8 | 108.6 / 413.9 | 156.8 / 605.7 |
| 2 | 97.3 / 394.4 | 116.7 / 427.7 | 163.1 / 638.5 |
| 4 | 104.6 / 406.4 | 125.3 / 441.9 | 159.6 / 604.5 |
| 8 | 126.6 / 439.6 | 132.6 / 459.7 | 166.6 / 622.6 |
| 16 | 178.1 / 535.0 | **147.0** / 494.4 | 169.6 / 644.0 |
| 32 | 292.4 / 774.8 | **159.5** / 531.2 | 173.7 / 639.8 |

Least connections degrades from 93.9 ms to 292.4 ms. That is a **3.1× loss
purely from horizontal scaling of the load balancer tier**, with the backends
untouched. And it crosses over: **at 16 dispatchers, power of two is better, and
at 32 it is 1.8× better.**

Notice also that round robin barely moves, from 156.8 to 173.7. A rule that uses
no shared information cannot be damaged by not having it. That is not an
argument for round robin, which is 1.8× worse than the alternatives throughout.
It is the reason round robin remains the sane default for people who do not know
how many dispatchers they will end up with.

> ### 🔮 Predict — checkpoint 3
>
> You need the quality of least-connections without its fragility. Two obvious
> repairs come to mind: **refresh the view faster**, or **share one view between
> all dispatchers**.
>
> Before reading on: what does each of them actually cost, and what is the fleet
> size at which each stops being possible?

---

### v4 — what if Omar looks at only two counters?

The two obvious repairs both fail, and for the same reason.

*Refresh faster* means every doorman asks every counter more often. The message
count is the number of dispatchers times the number of backends times the
frequency, written `dispatchers × backends × frequency`, and it grows with the
square of your fleet. Driving τ to 1 ms across 20 dispatchers and 200 backends
is 4 million probes a second. Worse, the probes themselves are requests, so they
consume the capacity you are trying to measure.

*Share one view* means a coordination service sitting in the path of every
dispatch decision. `distributed-rate-limiting` measured exactly this trade and
the answer is the same: **per-request coordination costs a round trip per
request.** You have added a network hop and a single point of failure, in order
to make a routing choice whose entire value is a few hundred milliseconds of
tail latency.

The actual repair is neither. It is one of the most surprising results in
distributed systems.

```
a = randInt(8)
b = randInt(8)
i = v[a] < v[b] ? a : b
```

**Pick two counters at random. Send the customer to whichever of those two has
the shorter queue. Ignore the other six entirely.**

That is the **power of two choices** (Azar, Broder, Karlin & Upfal 1994;
Mitzenmacher's 2001 survey *The Power of Two Choices in Randomized Load
Balancing* is the readable reference).

At ρ = 0.9 with a perfect view:

| ρ = 0.9 | mean | p99 | worst queue |
|---|---:|---:|---:|
| random (`d = 1`) | 244.0 | 994.9 | 80 |
| **power of two (`d = 2`)** | **108.6** | **413.9** | **13** |
| least connections (`d = 8`) | 93.9 | 390.8 | 9 |
| ideal | 91.5 | 384.1 | — |

**Looking at two counters out of eight captures 90% of what looking at all eight
captures.** And the returns after the second one are almost nothing. Here `d` is
just how many counters Omar glances at before choosing.

| choices sampled `d` | mean | p99 | worst queue |
|---:|---:|---:|---:|
| 1 (random) | 244.0 | 994.9 | 80 |
| **2** | **108.6** | **413.9** | **13** |
| 3 | 100.2 | 399.9 | 12 |
| 4 | 96.2 | 392.0 | 10 |
| 8 (all of them) | 93.9 | 390.8 | 9 |

The step from one choice to two is worth 135 ms. The step from two to *all
eight* is worth 15 ms. **The first extra glance does 90% of the work that
perfect information does.**

#### Why does looking at two work so well?

Here is the argument in one paragraph you can reconstruct later.

With one random choice, a counter's queue length is decided by luck alone, and
luck has a long tail. The worst of `n` independent random draws grows like
`log n / log log n`. With two choices you only land on a long queue if **both**
your samples were long, which squares a small probability. That squaring
compounds at every step, and the worst queue length collapses from
`log n / log log n` to `log log n / log d`.

That last quantity is **doubly** logarithmic. It is essentially a constant for
any fleet size you will ever operate. Going from `d = 2` to `d = 3` only changes
the denominator from `log 2` to `log 3`, which is exactly why the table above
flattens instantly.

You can watch the theory in the numbers. Fleet size varying, ρ = 0.9, one worker
per backend so that queue depths are directly comparable:

| backends | random: worst queue | **power of two: worst queue** | least conn: worst queue |
|---:|---:|---:|---:|
| 4 | 58.5 | 18.5 | 14.8 |
| 8 | 58.0 | 11.0 | 7.8 |
| 32 | 82.5 | 7.5 | 3.0 |
| 128 | 85.5 | **6.8** | 1.0 |

**Random's worst queue grows as the fleet grows. Power of two's shrinks.** By
128 backends, random is 12.6× worse.

This is the theoretical result made visible, and it is why the technique is
everywhere. NGINX ships it as `random two least_conn`, and HAProxy as
`balance random`, whose `random(<draws>)` defaults to two draws. Envoy's default
`lb_policy` is `ROUND_ROBIN`, and its `LEAST_REQUEST` is implemented as **power
of two choices with a configurable `choice_count`, not as a full scan**. The
documentation says so, and people who assume otherwise mispredict its behaviour
under staleness.

#### What matters more than the performance?

Power of two is not chosen because it is 89% as good as least connections. It is
chosen because **it does not herd**, and that is a difference in kind rather
than degree.

The same staleness sweep, both rules, ρ = 0.9:

| view age τ | least connections mean | **power of two mean** | ratio |
|---:|---:|---:|---:|
| 0 ms | 93.9 | 108.6 | 0.86× |
| 50 ms | 196.2 | **118.8** | 1.65× |
| 100 ms | 330.7 | **130.4** | 2.54× |
| 250 ms | 708.4 | **159.9** | 4.43× |
| 500 ms | 1,274.9 | **207.8** | 6.14× |
| 1,000 ms | 2,335.7 | **295.5** | 7.90× |

At τ = 0, least connections wins by 14%. At τ = 500 ms, power of two wins by 6×.
**The crossover is at about 30 milliseconds of staleness.** That is less than
one metrics scrape, less than one gossip round, less than one health-check
interval. In most systems it is less than a single network round trip.

The reason is structural, not statistical. A herd requires that all doormen,
looking at the same stale picture, *reach the same conclusion*. Taking the
smallest of all eight does exactly that: there is one minimum and everybody
finds it.

Power of two draws a **different random pair for every single request**. So Omar
and Dev, deciding a microsecond apart, are comparing different counters and
cannot agree on a victim. **The randomness is not a compromise forced by cost.
It is the mechanism that breaks the feedback loop.**

> This deserves to be the sentence you remember from this lab:
>
> **Using less information, less decisively, produced a system that survived bad
> information. The randomness was the feature.**

#### What breaks v4?

Two things, and both are stage 11.

First, it does **not** fix the failure magnet. It only dilutes it, to 23.1%
instead of least connections' 66.1%, where a fair share would be 12.5%. Choosing
a broken counter less often is not the same as not choosing it.

Second, it says nothing about backends of different sizes, sessions that must
stay put, backends that have just started, or backends that are about to be shut
down. Those are v5.

---

### v5 — what four things does a real balancer add?

Every one of these exists because of something in stage 11. None of them is a
dispatch rule. They are all changes to *which counters are candidates at all*
and *how much each one counts*. In the shop, these are Asha's job rather than
Omar's, and that separation is the point.

**Weights, for fleets that are not uniform.** Give backend `i` a weight, written
`w[i]`, and compare each counter's queue divided by its weight instead of its
raw queue. In symbols, compare `v[i]/w[i]` rather than `v[i]`. A machine with
twice the cores then gets twice the traffic.

This is the same idea as `consistent-hashing`'s weight-as-position-count, and it
has the same trap. Weights are static configuration, and static configuration
describes the fleet you had when you wrote it. A weight of 2 on a node that is
currently degraded is worse than no weight at all. **Prefer a rule that measures
over a weight that assumes**, which is stage 6's principle applied to
configuration.

**Health checking and outlier ejection, for backends that are broken.** This is
a separate loop from dispatch. Probe each backend on a schedule and remove
failing ones from the candidate set entirely. Asha walks the floor and closes a
counter; Omar then chooses only among the counters still open.

**This is the only mechanism in the entire lab that addresses the failure
magnet**, and stage 11's F1 shows that no dispatch rule can substitute for it.

*Outlier ejection* is Envoy's term for the passive version, and HAProxy and
NGINX have equivalents. Instead of probing, it watches the real traffic and
ejects a backend whose error rate or latency is an outlier against its peers. It
catches things a synthetic probe never will, because it tests the actual code
path with actual data.

**Slow start, for backends that have just arrived.** Ramp a newly-healthy
backend from zero to its full weight over some seconds, rather than handing it a
full share immediately. Arjun has just clocked on, so send him two customers,
not forty. v2's 76-second p99 on a routine restart is what this exists to
prevent.

NGINX Plus and HAProxy both call it `slow-start`. Envoy calls it
`slow_start_config`. Kubernetes has no equivalent, which is why `readinessProbe`
tuning is such a recurring source of deploy-time latency spikes.

**Connection draining, for backends that are leaving.** Stop sending *new*
requests, but let the in-flight ones finish before the process exits. Close the
lane, serve everyone already standing in it, then turn off the light.

The absence of this turns every deploy into a burst of 502s. It is the reason a
graceful shutdown handler and a `preStop` hook are not optional.

> **Checkpoint — what you can explain now.** You can derive all four dispatch
> rules from the failures that force them. You can say precisely why least
> connections degrades to worse-than-random under staleness, and why power of
> two does not. And you can state the crossover conditions, at about 30 ms of
> staleness or about 16 dispatchers, rather than repeating a preference.
>
> **What the next layer adds.** Stage 11 is where the direction reverses. Every
> result so far says load-aware beats load-blind. Two of the eight failures
> below say the opposite, for reasons that are not a special case.

---

## 11. What actually breaks in the real shop?

> **In one breath:** Eight failures, ordered so the most surprising comes first.
> The first two point in opposite directions, and that contradiction is the
> whole design lesson of this stage.

---

### F1 — why does the sickest counter attract the most customers?

Counter A loses its database connection pool. Arjun has not gone home and he has
not fallen asleep. He now greets each customer, says "sorry, cannot help you",
and waves them away in about a millisecond.

He is the fastest counter in the shop. Every queue in front of him empties
instantly. To every rule that looks at queue length or at response time, counter
A is now the emptiest, fastest, healthiest counter Asha owns.

> ### 🔮 Predict — checkpoint 4
>
> Eight backends, ρ = 0.8, one of them returning errors in 1 ms instead of
> working for 80 ms. Under **least connections**, what share of the fleet's
> traffic does the broken backend receive? Fair share is 12.5%.
>
> Then answer the same question for an EWMA-of-response-time rule, the "smart"
> one that products advertise as adaptive. Commit to both numbers.

At ρ = 0.8, with backend A erroring in 1 ms:

| dispatch rule | share of all traffic sent to the broken backend |
|---|---:|
| round robin | 12.5% |
| random | 12.5% |
| power of two choices | **23.1%** |
| least connections | **66.1%** |
| least response time (EWMA latency) | **100.0%** |

**The better the load signal, the more completely the fleet commits to the
corpse.** Least response time sends it literally everything. A backend answering
in 1 ms will always beat a backend answering in 80 ms, and it keeps winning for
exactly as long as it stays broken.

The mechanism is the same for all of them. Every load-aware rule in this lab
optimises a proxy for *capacity available*: either outstanding requests or
observed latency. Both proxies have the same blind spot. **They measure how fast
the backend gets rid of requests, not whether it did anything useful with
them.** Failing is the fastest possible way to get rid of a request. A rule that
seeks speed will seek failure, because failure is fast.

One guarantee survives. Round robin and random still give it exactly 12.5%,
because they are not optimising anything. In this one scenario, and only this
one, the dumbest rules are the safest.

And the fix is not a dispatch rule at all. It is health checking and outlier
ejection, which are a *separate mechanism* precisely because they answer a
question dispatch cannot. Not "which counter is fastest" but "which counters are
allowed to be candidates at all". Asha closes the counter; Omar then chooses
among what is left.

This is why every production balancer has two layers. It is also why a load
balancer with a beautiful algorithm and a badly configured health check is worse
than one running plain round robin.

In production this shows up as a partial outage that is far larger than the
broken component. One pod out of eight loses its database credentials after a
secret rotation. The error rate should be 12.5%. It is 66%, or 100%, and the
graph looks like a total outage caused by a single unhealthy pod. Anyone
reasoning "only one of eight pods is bad, so at most one in eight requests can
fail" will spend the first twenty minutes of the incident looking in the wrong
place.

One configuration trap deserves naming. If your health check is `GET /health`
and it returns 200 from a handler that touches nothing, it will pass on exactly
the backend that is failing every real request. Health checks that do not
exercise the dependency they certify are the mechanism by which F1 survives the
thing designed to prevent it. Envoy's outlier ejection, which watches real
traffic rather than a synthetic probe, exists because of this.

---

### F2 — why does the same signal that caused F1 save you here?

Now Arjun does not fail. He gets slow. Five times slow, for any of the ordinary
reasons: a degraded EBS volume, a noisy neighbour, a memory leak pushing the
process into constant GC, a cache that got evicted.

At ρ = 0.8:

| dispatch rule | share sent to A | fleet mean | fleet p99 |
|---|---:|---:|---:|
| round robin | 12.5% | 8,629 ms | 330,967 ms → unbounded |
| random | 12.5% | 8,692 ms | 332,577 ms → unbounded |
| power of two choices | 3.1% | 131.5 ms | 978.2 ms |
| least connections | 3.0% | 102.7 ms | 571.0 ms |

**This is F1 exactly backwards.** The load-blind rules, the heroes of F1, send a
machine that can do 10 requests a second exactly 40 of them. Its queue grows
without bound on a fleet running at 80% of capacity. The load-aware rules notice
within milliseconds and back off to a quarter of a fair share.

Why do the two failures point in opposite directions? Both rules answer the
question "how fast does this backend clear requests?". That signal correctly
identifies a *slow* backend, and catastrophically misidentifies a *broken* one,
because broken is fast. The signal is not wrong. It is answering a different
question from the one you need.

That gives the design principle of the whole stage.

> **Balancing and health are two different jobs and must be two different
> mechanisms.** Dispatch answers *"which of my healthy backends is least
> busy?"* Health checking answers *"which of my backends are healthy?"* Trying
> to make one rule do both gives you either F1 or F2, and which one you get
> depends on whether today's failure happens to be fast or slow.

On a dashboard this appears as the incident where the fleet is at 80% CPU, one
node is at 100%, and total fleet throughput has collapsed. The queue on that
node is absorbing an eighth of all traffic and returning none of it. The
give-away is a per-backend latency graph where one line is three orders of
magnitude above the others, next to a per-backend request-rate graph where all
eight lines are identical. **Identical request rates during an incident are a
symptom, not reassurance.**

---

### F3 — the herd: what does acting confidently on stale information do?

The mechanics were stage 10's Break 1. The operational shape is what matters
here.

You get a herd from any design where dispatchers learn backend load on a
schedule rather than continuously. A metrics scrape. A gossip round. A load
header on health-check responses. An Envoy load report. You also get it,
equivalently, from any design where several dispatchers share a view that
updates periodically.

From stage 10, at τ = 250 ms: mean latency 708 ms, against 244 for random
dispatch and 94 for the same rule with a live view. Worst queue depth 117
against 9.

Watch per-backend request rate at **one-second resolution or finer**. At
one-minute resolution the herd is completely invisible, because every backend's
*average* rate is exactly fair. They take turns. What you are looking for is
eight lines that are individually spiky and collectively flat, with the spikes
anti-correlated.

Correctness survives untouched. Every request still reaches a healthy backend
and gets a right answer. Throughput is unaffected. **Only the tail degrades**,
which is exactly why this survives so long undetected. Dashboards built on means
and one-minute buckets cannot see it at all.

In production it looks like p99 latency several times p50 with no identifiable
cause. It looks like a fleet where every backend periodically shows a CPU spike,
but never the same one twice. And most characteristically, it looks like an
improvement that arrives when somebody changes the algorithm to something
*simpler*. If switching from least-connections to round robin made your tail
latency better, you had a herd.

---

### F4 — what does losing one counter cost, exactly?

A node dies, or is cordoned for a kernel patch, or fails a health check. The
remaining seven absorb its traffic. Everyone knows this. Almost nobody does the
arithmetic.

> ### 🔮 Predict — checkpoint 5
>
> Eight backends. The fleet is running at **87.5%** of capacity: high, but you
> have seen higher, and every backend is healthy. One node is lost.
>
> Seven-eighths of the capacity remains, so the fleet goes to 100% of capacity
> rather than 87.5%. How bad is that? Commit to a p99 before reading.

With power-of-two dispatch throughout:

| fleet ρ with 8 nodes | 8 nodes: mean / p99 | ρ after losing one | 7 nodes: mean / p99 |
|---:|---:|---:|---:|
| 0.60 | 81.5 / 372.4 | 0.69 | 83.8 / 374.9 |
| 0.70 | 84.0 / 373.9 | 0.80 | 91.1 / 385.5 |
| 0.80 | 90.4 / 384.3 | 0.91 | 115.7 / 427.3 |
| **0.875** | **101.1 / 402.0** | **1.00** | **983.9 / 2,082.5** |
| 0.90 | 108.7 / 414.2 | 1.03 | 9,015.9 / 16,649.7 |

At ρ = 0.875 the fleet looks entirely healthy, with a p99 of 402 ms against a
floor of about 370. Losing one node takes it to exactly ρ = 1.0. The p99 goes to
2,082 ms and is still climbing when the run ends. At ρ = 0.90 it is 16.6
seconds.

Nothing about load balancing failed here. This is `queueing-theory`'s cliff,
reached by subtraction. The fleet simply no longer has the capacity. The rule is
arithmetic, and in words it is this: to survive losing one counter out of eight,
run below seven eighths.

> **To survive losing one of N backends, the fleet must run below `(N−1)/N`.**
> Eight nodes: below 87.5%. Four nodes: below 75%. **Three nodes: below 66%.**
> And because latency is set by *spare* capacity, "below" needs real margin
> rather than a rounding error. At ρ = 0.80 the loss is survivable, moving 384
> to 427 ms. At 0.875 it is not.

The trap is worst for small fleets. A three-pod deployment must run below 66% to
tolerate a single pod restart. Most three-pod deployments are scaled on a CPU
target of 70–80%, which means **the deployment cannot survive its own rolling
update**. Since the update takes pods down deliberately, the failure is
self-inflicted and it repeats on every release.

What does load balancing contribute here? Only this: it decides how quickly the
survivors absorb the load, and whether they absorb it evenly. Compare
`consistent-hashing`'s finding, where one position per node meant a dead node's
*entire* share landed on one arbitrary neighbour, at +483%. That is the same
failure in a sharded system, and much worse, because there the load cannot be
spread at all.

In production this appears as a cascade. One node dies. The survivors slow down.
Slower survivors start failing health checks or timing out, so they get removed,
and the remaining fleet is smaller still. `load-shedding`'s F3 is this loop at
the request level, and it explains why removing the original cause does not end
the outage.

---

### F5 — why is session affinity not load balancing?

Somebody needs a user to keep hitting the same backend. An in-memory session, a
local cache, a WebSocket, a stateful upload. So they turn on sticky sessions:
`ip_hash` in NGINX, a session cookie, `balance source` in HAProxy, `RING_HASH`
in Envoy.

In the shop, that is Asha announcing that every customer must always use the
counter matching the first letter of their surname. Omar no longer chooses. The
surname chooses.

What this actually did was convert a balancing problem into a **sharding**
problem. And a key distribution is whatever your users make it, which is never
uniform.

> **🔮 Predict.** *Requests are hashed to the eight counters by tenant, and
> there are 4,096 distinct tenants. That is 512 tenants per counter. Is that
> enough keys to balance?*
> <details><summary>Reveal</summary>
> No, and it is not close. With realistic tenant sizes the busiest backend still
> takes **20.5%** of the traffic against a fair 12.5%, and its queue grows
> without bound. If you said "4,096 keys over 8 buckets must average out", you
> assumed the keys were the same size. Make those same 4,096 tenants equal in
> size and the busiest backend drops to 12.6% and the p99 to 553.8 ms. The
> number of keys was never the variable. The skew was.
> </details>

Requests hashed to 8 backends by tenant, ρ = 0.8, where tenant sizes follow Zipf
— the ordinary shape of real tenant traffic, in which the largest tenant is many
times the median:

| distinct tenants | busiest backend's share (fair = 12.5%) | median p99 |
|---:|---:|---:|
| 64 | 27.4% | 246,482 ms → unbounded |
| 256 | 24.1% | 201,952 ms → unbounded |
| 4,096 | 20.5% | 133,957 ms → unbounded |
| 4,096, **all tenants equal size** | 12.6% | 553.8 ms |

Read the last two rows together, because that is the finding. **Four thousand
tenants is not enough keys to balance, and four thousand *equal* tenants is
plenty.** The number of keys is almost irrelevant. The *skew* is everything.

With realistic skew the busiest backend gets 20.5% of a 320-per-second stream.
That is 65 requests a second onto a machine that can do 50, so its queue never
stops growing while three other backends idle.

`consistent-hashing` established the reason and it has not changed. **Every
hashing scheme agrees that one key has one owner, and that agreement is the
feature.** You cannot spread a key without giving up the property you enabled
stickiness to get.

The stickiness itself works perfectly, by the way. What you lost is the ability
to balance, and you lost it in proportion to your traffic skew.

In production this appears as one pod permanently hotter than the others, which
no amount of restarting fixes, and which moves to a different pod when the fleet
size changes. If your remedy for a hot pod is "restart it", and the heat
reappears somewhere else, you have a key-distribution problem rather than a pod
problem.

What to do instead: move the state out. A shared session store, or a shared
cache, so that any backend can serve any request and the choice becomes free
again.

Where that is genuinely impossible, use **bounded-load consistent hashing** —
the technique `consistent-hashing` flagged as "a load balancer's tool, never a
database's". It gives you affinity with a cap. Hash to a preferred backend, but
if that backend is already above `(1+ε)` times the average load, fall through to
the next one on the ring. HAProxy ships this as `hash-balance-factor`. You keep
stickiness for most requests and give it up exactly where it would have hurt.

---

### F6 — what if Omar is placing connections, not requests?

This one is nobody's fault and it catches almost everybody.

An **L4** load balancer decides **when a TCP connection is opened**, by hashing
the five-tuple of addresses and ports. Once placed, that connection stays where
it is for its whole life. A network load balancer, ECMP in a router, `iptables`
in kube-proxy and an AWS NLB all work this way.

That was fine when one connection carried one request. But HTTP/2, gRPC and any
connection-pooling client multiplex **thousands** of requests over a **single**
long-lived connection. The balancer made eight decisions at process start and
then never balanced anything again.

In the shop: Omar is not directing customers. He is directing coaches. Each
coach parks at one counter and unloads passengers there all day.

> ### 🔮 Predict — checkpoint 6
>
> Eight backends, each able to do 50 requests a second. Eight client services,
> each holding **one** long-lived gRPC connection, placed by a hashing L4
> balancer. Fleet load is 320 requests a second, so ρ = 0.8 and comfortable.
>
> What share of the traffic does the busiest backend get? Fair share is 12.5%,
> and **62.5% is its capacity**. Commit to a number.

Connections placed by hash over 8 backends, ρ = 0.8:

| long-lived connections | busiest backend's share | worst seed | load on it | median p99 |
|---:|---:|---:|---:|---:|
| **8** | **42.2%** | 62.5% | 200 req/s | 337,729 ms → unbounded |
| 16 | 28.1% | 43.7% | 140 req/s | 221,443 ms → unbounded |
| 32 | 24.2% | 31.2% | 100 req/s | 211,545 ms → unbounded |
| 64 | 18.7% | 21.8% | 70 req/s | 127,897 ms → unbounded |
| 256 | 15.8% | 17.8% | 57 req/s | 2,150 ms |
| 1,024 | 14.0% | 14.7% | 47 req/s | 899.9 ms |
| *(balancing every request instead)* | *12.6%* | — | *40 req/s* | *569.0 ms* |

With eight connections over eight backends, the busiest backend gets **42%** of
all traffic. That is 3.4× its fair share, and in the worst run it got 62.5%,
which is 200 requests a second onto a 50-request-a-second machine.

**You need on the order of a thousand connections before hash placement is
safe.** Even 256 connections leaves the busiest backend at 57 req/s against a
capacity of 50.

The reason is balls-in-bins again. Placing `k` connections at random into `n`
bins leaves an imbalance that shrinks only as one over the square root of `k`,
written `1/√k`. Eight balls into eight bins is spectacularly uneven, with an
expected maximum around 3, and every request on a connection inherits its
connection's placement. **You are not balancing 320 requests a second. You are
balancing 8 objects, once, at startup, and living with the result for the
process's lifetime.**

The balancer is doing exactly what it says on the tin. Connections are spread.
Requests are not.

In production this is the single most common "why is one pod at 100% CPU when
the others are at 40%" question in any gRPC or HTTP/2 system. The give-away is
that the hot pod stays hot for hours, and changes identity only when something
reconnects, because reconnection is the only thing that can rebalance.

Four fixes, in order of how much they change:

1. **Balance at L7, per request.** Envoy, NGINX, HAProxy in HTTP mode, or a
   service mesh sidecar all terminate HTTP/2 and dispatch each *request*
   independently. This makes the problem vanish, which is the last row of the
   table. It is the reason service meshes exist for gRPC-heavy systems, and it
   is worth the extra hop.
2. **Client-side load balancing.** Let the client hold a connection to *every*
   backend and choose per request. gRPC's `round_robin` policy, Finagle and
   Spring Cloud LoadBalancer all do this. The connection count becomes
   `clients × backends`, which balances beautifully and scales badly. Five
   hundred clients and 200 backends is 100,000 connections, and every client
   must watch every backend.
3. **Subsetting**, which is what large deployments actually do. Each client
   connects to a random *subset* of, say, 20 backends and balances across those.
   You get most of the balancing quality with a bounded connection count.
   Google's *Site Reliability Engineering* book describes deterministic
   subsetting. Twitter and Finagle's *deterministic aperture* is the same idea,
   with the subsets chosen to tile evenly rather than randomly. This is the
   standard answer at scale and the name is worth knowing.
4. **Force reconnection periodically**, with `max_connection_age` in gRPC
   servers or `max_requests_per_connection` in Envoy. This does not fix the
   imbalance. It reshuffles it often enough that no single pod stays hot. It is
   a mitigation rather than a solution, and it is frequently the right one.

---

### F7 — how does a health check lie in both directions?

Health checking is the mechanism F1 and F2 both depend on, and it has two
failure modes that point opposite ways.

*Too lenient.* `GET /health` returns `200 OK` from a handler that allocates a
string. It will pass on a backend whose database pool is exhausted, whose disk
is full, and whose every real request is failing. That is the exact backend F1
needs removed. **A health check that does not exercise the dependency it
certifies is decorative.**

*Too strict.* A check with a 100 ms timeout, against a backend whose p99 is 400
ms under load, will fail intermittently. The backend gets ejected. Its load
moves to the others. They get slower, and their checks start failing too. This
is F4's cascade with the health checker as the amplifier, and it is worse than
the original problem, because the machines being removed were working.

Watch the count of backends currently in the pool, as a time series. If it
oscillates, you have this. A flat line at N is the only acceptable shape, and
most teams never graph it.

Neither failure loses data or corrupts anything. Both convert a small problem
into a fleet-wide one.

Four design rules come out of it.

- Check something real, but check it **cheaply**. A check that queries the
  database on every probe from every balancer becomes its own load problem. A
  common shape is a handler that reports a cached health status, refreshed every
  few seconds by the application itself.
- Use **asymmetric thresholds**. Require several consecutive failures to eject,
  which is HAProxy's `fall` and is typically 3, and fewer successes to restore,
  which is `rise` and is typically 2. Slow to condemn, quick to forgive.
- **Never eject below a floor.** Envoy's `outlier_detection` has
  `max_ejection_percent`, and it exists precisely because a checker that removes
  everything has converted a latency problem into a total outage. If the whole
  fleet looks unhealthy, the checker is what is broken.
- **Separate liveness from readiness.** Liveness answers "should this process be
  killed". Readiness answers "should this process get traffic". Wiring a
  dependency check into liveness means a database blip restarts your entire
  fleet.

---

### F8 — how does a retry multiply, and how does Omar help it?

A backend gets slow. Clients time out and retry. Omar faithfully dispatches
every retry, including to the backend that is already struggling, or worse,
spreading the amplified load across a fleet that was already at 80%.

`distributed-rate-limiting` and `load-shedding` both put numbers on this loop,
and those numbers are theirs rather than this lab's. The relevant point here is
what a **load balancer** contributes to it, which is two things.

*It hides the amplification.* Offered load has tripled. Every backend's request
rate has tripled evenly. Nothing on a per-backend graph distinguishes "three
times the customers" from "the same customers asking three times".

*It can turn a partial failure into a total one.* Retries are dispatched
independently of the original. So a request that failed on backend A retries
onto backends B and C, spreading the load of a failing region across the healthy
one. This is why **retrying at the load balancer is more dangerous than retrying
at the client**. The balancer's retry has no knowledge of how many other retries
are in flight, and its position in the path means every client's retries are
pooled.

Three things to do, in the order that matters.

- **Budget retries, do not count them.** Envoy's retry budget caps retries at a
  percentage of active requests, defaulting to 20%, rather than "3 attempts per
  request". A per-request count multiplies fleet load by 3 in exactly the
  situation where it must not. A budget cannot exceed its percentage no matter
  how many requests fail.
- **Never retry into an overload.** A 503 from a shedder means "there is no
  capacity", and retrying it immediately is the definition of a retry storm.
  `load-shedding` showed `Retry-After` becoming the synchroniser of the storm
  when everyone honours the same value. Jitter is not optional.
- **Retry on a different backend, and only once.** This is the one thing the
  balancer is uniquely well placed to do, and it is genuinely valuable. A single
  immediate retry on a *different* backend converts a single-backend blip into a
  small latency bump. Its cousin is **hedging**: send a second copy after the
  p95 and take whichever answers first. That is the same idea used proactively,
  and it is a lab of its own.

---

### All eight at once — which rules help, and which make it worse?

One table for the whole stage. Read the last two columns together, because they
are what makes F1 and F2 a genuine dilemma rather than a pair of tips.

| Failure | What it looks like | Load-blind rules | Load-aware rules |
|---|---|---|---|
| F1 fast-failing backend | error rate far above one eighth | safe, still 12.5% | harmful: 23.1% to 100% |
| F2 gray, 5× slow backend | one node at 100%, throughput gone | harmful: unbounded queue | safe, back off to 3% |
| F3 herd on a stale view | p99 many times p50, spiky per-backend rates | unaffected | harmful, worse the more decisive |
| F4 losing one of N | cascade after a node dies | equally exposed | equally exposed |
| F5 sticky sessions | one pod permanently hot | not applicable — the key chose | not applicable — the key chose |
| F6 L4 with long-lived connections | hot pod for hours, moves on reconnect | not applicable — placed at connect | not applicable — placed at connect |
| F7 health check lying | pool size oscillating, or never shrinking | it is the checker, not the rule | it is the checker, not the rule |
| F8 retry amplification | every backend's rate tripled evenly | hides it | hides it |

---

## 12. What are you actually guaranteed?

> **In one breath:** Very little, and most of the things people assume are on
> the list are not. The one guarantee everybody quotes, exactly equal request
> counts, is the one stage 4 spent its time attacking.

Load balancing has few guarantees. Being precise about which ones exist matters,
because most of the intuitive ones do not.

### What is guaranteed?

**Every request reaches exactly one backend.** The dispatcher is a function, so
it returns one answer. That stops being true once retries are involved, which is
F8.

**No request is lost by the choice itself.** In the model of this lab, Omar
never drops a customer. He only decides where they wait. Real balancers *do*
drop, through connection limits and queue limits, but that is shedding wearing a
balancer's clothes and should be reasoned about as shedding.

**Round robin gives exactly equal request counts.** To within one, over any
window, per dispatcher. This is a real, hard guarantee. Stage 4 is the argument
that it is not the guarantee you wanted.

**Sticky hashing gives stable placement.** The same key reaches the same backend
for as long as the backend set does not change, and `consistent-hashing` is the
lesson on what "does not change" costs.

### What is *not* guaranteed but is routinely assumed?

**Equal load.** Nothing in this lab guarantees equal *work* per backend. Every
load-aware rule is a statistical improvement rather than a bound. Power of two's
worst queue at 128 backends was 6.8, against a mean near 1.

**Any bound on the worst backend.** The one exception is bounded-load consistent
hashing, which is the only technique here that enforces a cap. That is precisely
what "bounded load" means, and why it has that name.

**That the least-loaded backend is chosen.** Only true with `d = n` *and* an
exact view. Both fail in production, and stage 10 is the measurement of what
each one costs.

**That balancing helps at all.**

> **🔮 Predict.** *Asha's fleet is at 50% utilisation. She switches from random
> dispatch to the best rule in this lab. How much does her p99 improve?*
> <details><summary>Reveal</summary>
> By about 4%, which is nothing. At ρ = 0.5 every algorithm on this page is
> within 5% of every other one. If you guessed "a lot, because random was 2.7×
> worse", you were remembering the ρ = 0.9 row. The whole value of a dispatch
> rule appears only when the fleet is already close to full.
> </details>

Here is the load sweep: the same eight backends, the same fleet, varying only ρ.
The first table is the three rules that do not need a fleet-wide view.

| fleet ρ | random p99 | round robin p99 | power of two p99 |
|---:|---:|---:|---:|
| 0.30 | 372.2 | 368.1 | 369.9 |
| 0.50 | 383.4 | 371.4 | 370.1 |
| 0.70 | 441.6 | 387.9 | 374.0 |
| 0.80 | 561.5 | 429.5 | 384.4 |
| 0.90 | 994.9 | 605.7 | 413.9 |
| 0.95 | 1,834.3 | 1,134.4 | 475.8 |

And the same runs again, showing the best rule, the unreachable ideal, and how
much the whole choice was worth at that load.

| fleet ρ | least conn p99 | ideal p99 | random ÷ ideal — what the choice is worth |
|---:|---:|---:|---:|
| 0.30 | 370.4 | 368.2 | **1.0×** |
| 0.50 | 367.2 | 369.2 | **1.0×** |
| 0.70 | 369.6 | 369.4 | 1.2× |
| 0.80 | 372.6 | 371.5 | 1.5× |
| 0.90 | 390.8 | 384.1 | 2.6× |
| 0.95 | 435.2 | 424.5 | **4.3×** |

> **At 50% utilisation, every algorithm on this page is within 4% of the
> theoretical optimum, and within 5% of each other.** The choice is worth
> nothing. At 95% it is worth 4.3×.
>
> **The algorithm only matters when you are already in trouble.** That is both a
> reason not to agonise over it on a comfortable fleet, and a reason to have the
> right one configured before the day you need it. Because the day you need it
> is the day you cannot safely change it.

**That it can save you above ρ = 1.** It cannot, and this is absolute. Above
capacity every queue grows without bound and there is no good one to choose.
From F4: eight nodes at ρ = 0.875 losing one gives ρ = 1.0 and a p99 of 2,082 ms
with the *best* rule in the lab. `load-shedding` is the only tool for that
region, and this is the sharp boundary between the two labs.

### Which assumptions is every number here standing on?

1. **Backends are independent.** If two share a bottleneck, whether the same
   node, the same database or the same NAT gateway, balancing between them buys
   nothing. This is the most common reason a real change produces no improvement
   you can detect.
2. **Any backend can serve any request.** The moment that is false you are
   sharding, which is F5.
3. **Service rate does not fall as the queue grows.** False in reality, in the
   direction that makes everything worse.
4. **The dispatcher itself is not the bottleneck.** True for these rules, which
   are all O(1) or O(d). But a rule that scans 200 backends per request at
   50,000 requests a second is 10 million comparisons a second in the request
   path. That cost is one of the reasons Envoy implements `LEAST_REQUEST` as
   power of two rather than a full scan.

### What does each rule spend, and what does it buy?

Every dispatch rule spends **information** to buy **tail latency**, and it pays
for the information in **coordination**.

| rule | information used | coordination cost | tail benefit | fails when |
|---|---|---|---|---|
| random | none | none | none | never — it has no assumptions to violate |
| round robin | one local counter | none | ~40% of the gap | request sizes vary; backends differ |
| power of two | 2 backends' state | O(1) per request | ~90% of the gap | the backend is broken rather than busy |
| least connections | all backends' state | O(n) per request, and a fresh view | ~98% of the gap | τ > ~30 ms, or > ~16 dispatchers, or a fast-failing backend |

**Reading down the "fails when" column is the lab.** The rules that use more
information do not merely stop helping when the information is bad. They
actively cause harm, and the harm grows with how decisively the rule acts. That
is the single transferable idea here, and it is not really about load balancing
at all.

> **Say it back.** Explain to a friend why the rule that looks at all eight
> counters is the best one in the lab and also the most dangerous one in the
> lab. Use no numbers.
> <details><summary>A good answer sounds like</summary>
> "Looking at everything and picking the very best works perfectly when you can
> actually see. The moment your picture is a little out of date, or there is
> more than one of you looking at the same picture, everybody picks the same
> 'best' one and buries it. And if a counter is broken in the particular way
> that makes it look empty, looking harder just makes you send more people to
> it. Glancing at two counters instead of eight is worse when you can see and
> much better when you cannot."
> </details>

---

# PRODUCTION MODEL — stages 13 to 17

## 13. What do you actually operate?

> **In one breath:** Four things matter more than the algorithm, and the
> algorithm is fifth. If you change one thing after reading this lab, change one
> of the four.

### What did the lesson leave out on purpose?

- **Everything below the request.** TCP handshakes, TLS termination, connection
  reuse, keep-alive, HTTP/2 flow control, NAT against direct server return. All
  of it matters for throughput, and none of it changes the dispatch decision.
- **Service discovery.** This lab assumed Omar already knows which counters
  exist. Producing that set, through DNS, EDS, Consul or the Kubernetes
  Endpoints API, is a distributed-systems problem in its own right.
  `gossip-protocols` is the lab that covers how such membership propagates.
- **Autoscaling.** Adding capacity is the other answer to "the fleet is at 90%",
  and it has a multi-minute time constant. `load-shedding` explains why that
  time constant means it cannot be your only answer.
- **Global traffic management.** Everything here is one pool in one place. DNS
  weighting, anycast and latency-based routing across regions are a different
  problem with different failure modes.
- **The balancer as a component that can itself fail.** A hardware appliance is
  a single point of failure. A fleet of software balancers is not, but then you
  have stage 10's multi-dispatcher problem by construction.

### Which layer decides what, and what can it see?

| layer | decides at | can see | balances | typical products |
|---|---|---|---|---|
| **DNS** | name resolution | nothing | *clients*, at TTL granularity | Route 53, any GSLB |
| **L4** | TCP connect | the 5-tuple | *connections* | AWS NLB, ECMP, kube-proxy/IPVS |
| **L7** | every request | headers, path, method | *requests* | Envoy, NGINX, HAProxy, ALB |
| **client-side** | every request | its own latencies | *requests*, with no extra hop | gRPC, Finagle, Ribbon |

The important column is "balances". **Only the bottom two rows balance
requests**, and F6 is the measurement of why that distinction is the difference
between a working fleet and one hot pod.

DNS is the coarsest of all. It balances *resolvers*, its unit of change is a
TTL, and clients cache past it. Treat DNS round robin as a way to spread clients
across regions, never as load balancing.

### Where do these algorithms actually live?

Concrete, so you can find them.

- **NGINX**: `round-robin` as the default, plus `least_conn`, `ip_hash`,
  `hash … consistent` and `random two least_conn`. That last one is power of two
  choices, and the `two` is not a coincidence.
- **HAProxy**: `roundrobin`, `leastconn`, `source`, `random`, and
  `hash-balance-factor` for bounded-load consistent hashing. Its `random` is
  power-of-two by default, via `random(2)`.
- **Envoy**: `ROUND_ROBIN`, `LEAST_REQUEST`, `RING_HASH`, `MAGLEV` and `RANDOM`.
  **`LEAST_REQUEST` is implemented as power of two choices** with a configurable
  `choice_count`, not as a full scan. The documentation says so, and people who
  assume otherwise mispredict its behaviour under staleness. Envoy is also where
  `outlier_detection`, `slow_start_config` and retry budgets live.
- **AWS**: ALB is L7 and does round robin or least-outstanding-requests. NLB is
  L4 and hashes the 5-tuple, so F6 applies to it in full.
- **Kubernetes `Service`**: `kube-proxy` in iptables mode picks a backend **at
  random**, per *connection*. It is v1 of stage 10, at L4. Every
  gRPC-in-Kubernetes hot-pod story starts here.
- **gRPC client-side**: `pick_first`, which is the default and means one
  connection with no balancing at all, and `round_robin`. That default catches
  people constantly.
- **Maglev** (Google, NSDI 2016) and **Ring Hash** are consistent-hashing
  variants, for when you need affinity plus minimal disruption on a membership
  change. `consistent-hashing` is the lab for the mechanism.

### What should Asha configure first?

In this order, because the order is worth more than the contents.

1. **Health checks that exercise a real dependency, with asymmetric thresholds
   and a `max_ejection_percent` floor.** F1 and F7. Everything else is
   optimisation. This is the difference between a 12.5% outage and a 100% one.
2. **L7 or client-side balancing for any HTTP/2 or gRPC traffic.** F6. If you
   change one thing after reading this lab and you run gRPC, change this.
3. **Fleet headroom below `(N−1)/N`.** F4. This is a capacity decision rather
   than a balancer setting, and it decides whether a node loss is an event or an
   outage.
4. **Slow start on any fleet where restarts are routine**, which is all of them.
   v2's 76-second p99.
5. **Then** pick a dispatch rule. Power of two if you have more than a couple of
   balancer instances, or any staleness in your view. Least-connections if you
   have exactly one dispatcher with live state. Round robin if your requests are
   genuinely uniform in cost. All three are within 1.7× of each other, and the
   four items above are worth two orders of magnitude more.

### What happens when Omar and the doorman who turns people away disagree?

Balancing and shedding are usually the same process, and they interact in a way
worth stating explicitly.

A shedder measures *this backend's* queue delay and rejects when it exceeds a
target. A balancer moves work *between* backends.

If the balancer is doing its job, every backend's queue delay is similar, so the
shedder sees a single coherent signal. If the balancer is *not* doing its job,
because of a herd, a hot shard or a hash imbalance, then one backend sheds
heavily while seven sit idle. Your error rate is then caused by imbalance rather
than by insufficient capacity.

> **The diagnostic that separates them, and it is worth memorising:** if you are
> shedding while the fleet has spare capacity, **you have a balancing problem,
> not a capacity problem.** Adding servers will not help, because the new ones
> will be as idle as the current idle ones.

Concretely: graph shed rate *per backend*, never fleet-wide. A fleet-wide shed
rate of 5% can be 5% everywhere, which is genuine overload, so add capacity or
shed harder. Or it can be 40% on one backend and 0% on seven, which is an
imbalance, so fix the routing. Those two have the same number on the dashboard
and completely different remedies.

### What belongs on the dashboard?

Everything in this lab is invisible on a standard dashboard, which is the main
reason it stays broken for so long.

| graph | what it catches | why the usual graph misses it |
|---|---|---|
| **per-backend request rate**, 1-second resolution | F3 (herd), F6 (connection imbalance) | a 1-minute average of a herd is perfectly flat |
| **per-backend p99**, one line per backend | F2 (gray failure) | a fleet-wide p99 buries one bad node in seven good ones |
| **max ÷ mean backend queue depth** (or in-flight) | everything | there is no standard metric for "spread", and spread is the subject |
| **healthy backend count** | F7 (flapping) | almost nobody graphs it, and it should be a flat line |
| **per-backend shed rate** | balancing against capacity | the fleet-wide number cannot distinguish them |

If you add one thing, add **max ÷ mean in-flight requests across backends**. It
is 1.0 for a perfect balancer. It was about 3.4 in F6's eight-connection case.
And it is the only number here that goes bad *before* latency does.

---

## 14. Which comfortable beliefs are wrong?

> **In one breath:** Ten sentences that sound obviously true, each followed by
> the number in this lab that kills it.

#### Does a load balancer spread load evenly?

It spreads *requests*. Stage 1 is a fleet with perfectly even request counts,
perfectly even CPU, and a breached SLO. Round robin's guarantee is exactly this
and it is not enough.

What is true instead: a balancer tries to equalise *queues*. Equal request
counts only equalise queues when all requests cost the same, meaning c²ₛ = 0.
Stage 4 shows that is where round robin ties the theoretical optimum, and that
c²ₛ = 16 is where it becomes indistinguishable from random.

#### Is least connections the best algorithm?

With one dispatcher and a live view, yes: 93.9 ms against an ideal of 91.5. With
a 250 ms-old view it is 708 ms, which is 2.9× **worse than random**. With 32
dispatchers it is 1.8× worse than power of two.

Least connections is the best algorithm *when its bet holds*. Its bet is "my
view of every queue is current", and most production deployments violate that by
construction.

#### Does more information about backend load produce better routing?

F1 is the counterexample and the numbers are not subtle. A backend failing in
1 ms receives 12.5% under round robin, 23.1% under power of two, 66.1% under
least connections, and **100%** under least-response-time.

More information produces better routing *when the information means what you
think it means*. All these signals measure how fast a backend disposes of
requests, and failing is the fastest disposal there is. Distinguishing broken
from fast is a different mechanism, which is health checking, not a better
signal.

#### Does adding a load balancer add capacity?

Omar performs no work. Every "ideal" column in this lab is a fleet doing the
same total work as every other column.

What a balancer does is recover capacity you already own and were wasting on
idle backends while other backends had queues. That recovery is worth 0% at
ρ = 0.5 and 4.3× at ρ = 0.95, and it is worth exactly nothing above ρ = 1.

#### Do health checks tell you which servers are healthy?

They tell you which servers pass *your specific check*. A `/health` endpoint
that touches no dependency passes on exactly the backend F1 needs removed.

A health check is a hypothesis about what "broken" means, and it is only as good
as the hypothesis. This is why outlier ejection exists, judging on real traffic
rather than a synthetic probe.

#### Are sticky sessions a small compromise?

F5 says otherwise. With realistically skewed tenants, 4,096 distinct session
keys still put 20.5% of traffic on one of eight backends. That is 65 requests a
second onto a 50-per-second machine, and its queue grows without bound while
others idle.

Enabling stickiness converts balancing into sharding, and it inherits every
property of sharding, including that hot keys are unfixable by definition. The
number of keys is nearly irrelevant. The skew is everything.

#### We are only at 80%, so is losing a node fine?

At ρ = 0.875, losing one of eight takes you to exactly ρ = 1.0 and a p99 of
2,082 ms that is still climbing when the run stops.

`(N−1)/N` is the ceiling, and because latency is governed by *spare* capacity
you need real distance below it. For a three-pod deployment that ceiling is 66%,
which is below most autoscaling targets. Many small deployments therefore cannot
survive their own rolling update.

#### Does our L4 load balancer distribute traffic across the fleet?

It distributes *connections*, once, at connect time. With eight long-lived gRPC
connections over eight backends, the busiest backend took **42.2%** of traffic,
and 62.5% in the worst run.

If requests-per-connection is large, an L4 balancer is balancing a handful of
objects at startup and nothing thereafter. Balance at L7, or in the client, or
force reconnection.

#### Is power of two choices a cheap approximation of least connections?

That is how it is usually introduced, and it gets the point backwards. It is
**better** than least connections in most real deployments: 6× better at
τ = 500 ms, and 1.8× better at 32 dispatchers.

The randomness is not a cost-saving compromise. It is the mechanism. Drawing a
fresh random pair per request means independent dispatchers cannot converge on
the same victim, so there is no herd to have. Using less information, less
decisively, is what makes it robust.

#### So is this why we should switch to `<algorithm>`?

At ρ = 0.5 every algorithm in this lab is within 5% of every other one, and
within 4% of a theoretical optimum it cannot beat.

The ordered list in stage 13 is the real priority: health checks, L7 for HTTP/2,
headroom, slow start, and only then the algorithm. The four items above the
algorithm are worth two orders of magnitude more than the algorithm is.

> **Say it back.** Asha is about to file a ticket titled "switch the balancer to
> least-connections to fix our p99". In two sentences, tell her what to check
> first and why the ticket might be worth nothing.
> <details><summary>A good answer sounds like</summary>
> "Check the fleet's utilisation and how many balancer instances you run. Below
> about 70% the rule changes nothing you can detect, and above a handful of
> instances least-connections is the *worse* of the two candidates. So the
> ticket is either zero benefit or a regression, and the health check and the
> gRPC connection story are worth far more either way."
> </details>

---

## 15. Where does this sit among your other labs?

> **In one breath:** This lab turns `queueing-theory`'s pooling result into a
> design constraint, gives `distributed-rate-limiting`'s staleness dial a
> crossover point, and gives `consistent-hashing`'s hot key a second home.

**Prerequisites you already had.** `queueing-theory` gave you the ρ = 1 cliff,
`W = 1/(μ−λ)`, Little's Law, Kingman, c²ₛ and the M/M/k pooling result this lab
is built on. `load-shedding` gave you goodput, admission control, the standing
four-worker 80 ms service, and metastable failure. `consistent-hashing` gave you
hot keys, monotonicity, bounded loads and ring hashing.
`distributed-rate-limiting` gave you the staleness budget, fail-open against
fail-closed, and retry storms. `microservices` gave you timeouts, breakers and
bulkheads.

**What this lab adds to them.** The pooling result becomes a design constraint.
"Stale shared state" acquires a crossover point at τ ≈ 30 ms. And the hot-key
lesson gains a second home in session affinity.

**Commonly confused with — one line each.**

| that | this |
|---|---|
| **sharding / partitioning** | the destination is fixed by the key; balancing exists only when the choice is free |
| **load shedding** | decides *whether* a request is admitted; balancing decides *which queue* it joins, and cannot help above ρ = 1 |
| **rate limiting** | enforces a promise per identity; balancing makes no promise to anyone |
| **autoscaling** | changes the number of backends over minutes; balancing changes their utilisation in microseconds |
| **failover** | picks a *different* pool when one is dead; balancing picks within a live pool |
| **reverse proxy** | the deployment shape, a hop in front of your fleet; balancing is one of the things that hop does |
| **service mesh** | where client-side balancing is implemented; not itself an algorithm |
| **circuit breaker** | protects the *caller* from a failing callee; health checking protects *the fleet* from one bad member |

**Where to go next**, in the order that will pay off most given what you now
have.

- `/learn tail-latency-and-hedging` — the other half of the deadline story that
  both this lab and `load-shedding` deferred. Hedged requests, tied requests,
  Dean and Barroso's *The Tail at Scale*, and what it costs to ask twice. It is
  the natural sequel to F8.
- `/learn backpressure` — the mechanism this lab and `load-shedding` both keep
  pointing at. Reactive streams, TCP windows and Kafka consumer lag as one idea
  in three places.
- `/learn service-discovery` — this lab assumed the backend set was known. How
  it is actually produced, how it propagates, and, crucially, how stale it is.
- `/learn observability-slos` — five labs now assume percentiles, error budgets
  and the difference between a mean and a tail. None of them teaches it, and
  stage 13's dashboard section is the shape of the gap.
- `/learn metastable-failures` — F4's cascade and `load-shedding`'s F3 as a
  subject in its own right. The 2021 HotOS paper *Metastable Failures in
  Distributed Systems* is the reference.

---

## 16. Glossary — what does each word mean exactly?

> **In one breath:** Every term below happened to Asha, Omar or Arjun somewhere
> above, and each entry says where. Terms are in order of first appearance.

**Backend** (upstream, real server, origin, target, pod, host) — one
independently queueing unit of capacity behind the balancer. One of Asha's eight
counters, from stage 2. The definition that matters is *has its own queue*,
rather than *is a machine*: eight pods sharing one CPU are one queue wearing
eight names.

**Dispatcher / load balancer** — the component that chooses a backend per
request. Omar at the door. Performs no work of its own.

**Dispatch rule** (algorithm, `lb_policy`, balancing policy) — the function from
the view, some randomness and the request, to a backend. All of stage 10.

**Shared queue / pooling** — the arrangement where all backends draw from one
line. The bank across the road from Asha's shop. Unreachable in a network
service, and the "ideal" column in every table here. `queueing-theory`'s M/M/k.

**ρ (rho), utilisation** — arrival rate divided by capacity. In this lab it is
the *fleet's* unless stated. The single most important number: below 0.7 the
algorithm is irrelevant, and above 0.9 it is worth several times.

**c²ₛ** — squared coefficient of variation of service time. How unequal your
requests are, or how unequal the trolleys are. 0 means identical, 1 is
exponential, 16 is a heavy tail. The number round robin is betting on, from
stage 4.

**Round robin** — take turns. Exactly equal request counts, zero information
required, and a bet that all requests cost the same. Ties the theoretical
optimum at c²ₛ = 0 and is indistinguishable from random at c²ₛ = 16.

**Least connections / least outstanding requests** — send to the backend with
the fewest requests in flight. This is stage 6's idea and stage 10's v3.
Essentially optimal with a live view, capturing 98% of the available gap, and
worse than random with a 100 ms-old one.

**The view (v), and staleness (τ)** — what Omar believes about backend state,
and how old that belief is. Three regimes, from stage 7: exact (one dispatcher
with live state), stale (a snapshot refreshed every τ), and local (each
dispatcher sees only its own requests, which is the normal case).

**Herd / stampede** — many independent deciders, given the same information,
choosing the same target simultaneously and destroying it. What taking the
minimum over a stale view produces, from stage 10's Break 1. Invisible on a
one-minute dashboard.

**Power of two choices (`d = 2`)** — sample two backends at random and use the
shorter queue. Stage 10's v4. Captures 89% of what perfect information captures,
and is 6× better than least connections at τ = 500 ms, because a fresh random
pair per request leaves nothing for dispatchers to agree on. Azar et al. 1994;
Mitzenmacher 2001.

**Failure magnet** — a backend that fails fast looks like the fastest backend,
so every load-aware rule sends it more traffic the sicker it gets. Arjun waving
customers away in a millisecond, in F1. The figures: 12.5% under round robin,
66.1% under least connections, 100% under least-response-time.

**Gray failure** — a backend that is degraded rather than down. Arjun working
through treacle, in stage 4 and F2. Passes naive health checks, ruins load-blind
dispatch, and is handled automatically by load-aware dispatch. The exact mirror
image of the failure magnet.

**Health check / outlier ejection** — the separate mechanism that decides which
backends are *candidates*, as opposed to which candidate is *least busy*. Asha
walking the floor, in v5 and F7. The only defence against the failure magnet.
Outlier ejection is the passive form, judging on real traffic rather than a
synthetic probe.

**Slow start** — ramping a newly-healthy backend up to full weight over seconds
instead of instantly. Arjun's first two minutes on shift, in v5. Prevents the
76-second p99 that a routine restart produces under round robin.

**Connection draining** — letting in-flight requests finish on a backend that is
being removed. Closing the lane but serving everyone already in it, from v5. The
absence of it is why deploys produce 502s.

**Session affinity / sticky sessions** — pinning a key to a backend. F5.
Converts balancing into sharding and inherits the hot-key problem: with
realistic tenant skew, 4,096 keys still gave one of eight backends 20.5% of
traffic.

**Bounded-load consistent hashing** — affinity with a cap. Hash to the preferred
backend unless it is already above `(1+ε)×` average, then fall through.
HAProxy's `hash-balance-factor`, from F5. The only technique in this lab that
*bounds* the worst backend rather than improving it statistically.

**L4 vs L7** — L4 decides once per TCP connection by hashing the 5-tuple, and L7
decides per HTTP request. F6. With HTTP/2 or gRPC, one connection carries
thousands of requests, so L4 balances a handful of objects at startup and
nothing after that.

**Subsetting / deterministic aperture** — each client connects to a random or
deterministically-assigned subset of backends rather than all of them. F6's
third fix. The standard answer to client-side balancing's `clients × backends`
connection explosion. Google SRE book; Twitter and Finagle.

**Retry budget** — capping retries as a percentage of active requests, where
Envoy's default is 20%, rather than as a count per request. F8. A per-request
count multiplies fleet load exactly when it must not.

**N+1 / `(N−1)/N`** — the utilisation ceiling for surviving the loss of one
backend, from F4. Eight nodes: 87.5%. Three nodes: 66%. Stay below it with real
margin, because latency is governed by spare capacity.

**Max ÷ mean in-flight** — the spread metric nobody graphs and everybody needs,
from stage 13. It is 1.0 for a perfect balancer, was 3.4 in F6's eight-connection
case, and it degrades before latency does.

---

## 17. About this lab

> **In one breath:** Where did every number above come from, what is in the
> folder, and which of your other labs sent you here? All the bookkeeping lives
> down here so the story upstairs stays a story.

### What is in this lab folder?

| File | What it is |
|---|---|
| `README.md` | this document — 16 stages plus this appendix, four depth layers, 6 numbered prediction checkpoints |
| `visualization.html` | **Eight Lanes** — race two balancers against each other on the identical traffic, then break a server |
| `EXERCISES.md` | eight rungs, pencil-and-paper plus the visualization |
| `SOLUTIONS.md` | worked answers |

### Which of your labs sent you here?

**Nine** of your labs named this one as the thing they were missing:
`load-shedding`, `consistent-hashing`, `distributed-rate-limiting`, `caching`,
`networking-for-developers`, `software-architecture-patterns`,
`amazon-web-services`, `uber-system-design` and `whatsapp-system-design`. That
is more than any other open topic in your map, and it is why `LEARNING-PATH.md`
has had it at the top of the queue.

They arrived from four different directions, and it is worth knowing which of
your existing results this lab stands on.

- **`queueing-theory`** gave you the two facts this entire lab is made of.
  First, `W = 1/(μ−λ)`: waiting is set by *spare* capacity, not capacity, and it
  has a vertical asymptote. Second, and much more directly, it found **four
  servers sharing one line at ρ = 0.8 → 60 ms of queueing**. A single server at
  the same 80 ms service time and the same 0.8 utilisation waits **320 ms**.
- That lab presented pooling as a curiosity in passing. **This lab is that one
  result turned into a subject.** "Eight servers behind a load balancer" is not
  one shared line. It is eight separate lines, and the whole job of the balancer
  is to fake the shared one.
- **`load-shedding`** is the layer *below* this one and shares its standing
  example. There, one service with four workers at 80 ms each, 50 requests a
  second, and clients giving up after a second, was pushed past capacity and
  kept alive by a doorman. **In this lab that exact service is one of eight
  backends.** Shedding decides *whether* a request gets in. Balancing decides
  *which door* it walks up to. Its closing section named `/learn load-balancing`
  as the highest-value next step, and stage 13 here closes the loop by showing
  what happens when a balancer and a shedder disagree.
- **`consistent-hashing`** answered "which machine owns this key", and warned
  that a **hot key** is unsolvable by any hashing scheme, because every scheme
  agrees one key has one owner. Stage 11's F5 is that warning with a load
  balancer's numbers on it: hashing sessions to backends looks like balancing
  and is not. Its mention of **bounded loads** as "a load balancer's tool, never
  a database's" gets explained here.
- **`distributed-rate-limiting`** listed four siblings needed to survive:
  timeout, breaker, bulkhead and shedding. It also put load balancing on its
  "next" list twice. Its central lesson, that *the only real dial is how stale
  each enforcement point's view of shared state may be*, turns out to be **the
  exact same dial** here. Stage 10 puts a cost on it in this setting.
- **`caching`**, **`networking-for-developers`**,
  **`software-architecture-patterns`**, **`amazon-web-services`**,
  **`uber-system-design`** and **`whatsapp-system-design`** all assume a load
  balancer exists, and none say what it does. `io-multiplexing` is the one that
  explains what a single backend is doing *inside* the box while its queue
  builds.

**New here**, and in this order. Why N queues are not one queue, and what that
costs. The four dispatch rules, and the specific condition each one is a bet on.
The power-of-two-choices result, and why it beats better information. The
failure magnet. And the discovery that a *sicker* backend attracts *more*
traffic under every load-aware rule.

**Not here, deliberately:** the packet-level mechanics of how a balancer
forwards bytes, meaning NAT, direct server return and connection tracking, get
one production section rather than a treatment. This lab is about the
*decision*, not the plumbing. Autoscaling is out, and `load-shedding` explains
why it is a different lever with a different time constant. Global traffic
management across regions gets a production paragraph and is a lab of its own.

### How were the numbers produced?

Every figure above comes from one discrete-event simulator. It has an arrival
stream, and a set of backends each with its own workers and its own FIFO queue.
It has a dispatcher with a configurable and deliberately imperfect view of
backend state. It has pluggable failure injection. It was run headlessly on
JavaScriptCore.

Unless a table says otherwise, every figure is the **mean of six seeds** over
**ten minutes of simulated time**, discarding the first minute.

**The identical engine is embedded in `visualization.html`.** Given the same run
length and seed count it reproduces every figure in this document to the digit.
That was checked, not assumed. The page's *sweep buttons* deliberately run
shorter, at five simulated minutes and three seeds, so that a press returns in
about twenty seconds rather than a minute. Those results land within roughly 1%
of the tables here, except very close to saturation, where the honest caveat
below applies.

### What was the engine checked against?

Before any teaching number was taken from it, the engine was compared with the
cases where queueing theory already knows the exact answer. That includes the
specific figures your own `queueing-theory` lab established.

| What was checked | Known answer | Simulator | |
|---|---|---|---|
| M/M/1, ρ = 0.5, S = 80 ms | 160 ms in system | 160.7 ms | ✅ |
| M/M/1, ρ = 0.8 | 400 ms | 398.6 ms | ✅ |
| M/M/1, ρ = 0.9 (20 seeds, 60 min) | 800 ms | 803.5 ms | ✅ |
| M/D/1, ρ = 0.5 (identical service times) | 120 ms | 119.7 ms | ✅ |
| M/D/1, ρ = 0.8 (20 seeds, 30 min) | 240 ms | 235.0 ms | ✅ (−2%) |
| D/D/1 (perfectly regular everything) | 80 ms, no waiting | 80.0 ms | ✅ |
| **M/M/4, one shared line, ρ = 0.8** | 140 ms (`queueing-theory`'s 60 ms wait + 80 ms work) | 139.8 ms | ✅ |
| Kingman's approximation, c²ₛ = 4, ρ = 0.8 | ≈ 880 ms | 868.2 ms | ✅ |

**Two honest caveats.**

*One.* Near saturation a ten-minute run genuinely scatters. That is a property
of nearly-full queues rather than a defect in the engine, and it is why every
figure is a multi-seed mean. Where a backend is pushed **past** capacity its
queue never stops growing, so any "mean latency" for it is a statement about the
length of the run rather than about the system. Stage 10 shows this explicitly
by running the same scenario over three different horizons.

*Two.* This is a model of dispatch and queueing. It does **not** model TCP,
connection setup, TLS handshakes, kernel scheduling, or the fact that a real
server gets slower rather than merely busier as its queue grows. Those all make
the real numbers worse than these, never better. Where a claim is **reasoning**,
or comes from a **published source** rather than from this simulator, the text
says so.
