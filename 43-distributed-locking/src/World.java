import java.util.*;

/**
 * A deterministic, tick-based simulation of distributed locking.
 *
 * One process (this JVM) plays every part: clients that can PAUSE (a GC stall,
 * a long syscall, a debugger) or CRASH, one or more lock servers with
 * switchable rules (TTL on/off, renewal, watch queues, planted bugs), a shared
 * RESOURCE that clients read-modify-write (the thing the lock is supposed to
 * protect), and write messages that take time to travel. No randomness
 * anywhere: same setup, same story, every run.
 *
 *   java src/World.java        -> narrated four-act story (no deps)
 *
 * The resource work is a read-modify-write increment, the same shape every
 * prior lab used, so "corruption" is measurable as a lost update:
 * finalValue < completed critical sections.
 */
public class World {

    public int tick = 0;
    public int msgDelay = 2;              // one-way travel time of a WRITE message
    public boolean verbose = false;
    public long msgCount = 0;             // every client<->server/resource exchange
    public int overlapTicks = 0;          // ticks where >=2 clients BELIEVE they hold the lock
    public final List<String> log = new ArrayList<>();

    public LockSrv[] servers;             // usually 1; Redlock experiments use 5
    public Res resource = new Res();
    public final List<Cli> clients = new ArrayList<>();

    // scheduled deliveries (write messages in flight), deterministic order
    private final List<Delivery> wire = new ArrayList<>();
    private long deliverySeq = 0;

    public World(int nServers) {
        servers = new LockSrv[nServers];
        for (int i = 0; i < nServers; i++) servers[i] = new LockSrv("S" + (i + 1));
    }

    void ev(String s) { log.add("t" + String.format("%03d", tick) + "  " + s); if (verbose) System.out.println(log.get(log.size() - 1)); }

    // ------------------------------------------------------------ lock server

    public static class LockSrv {
        final String name;
        public boolean ttlEnabled = false;
        public int ttl = 30;
        public boolean watchMode = false;      // notify next waiter instead of polling
        public boolean bugTokenRestart = false;   // planted: token counter kept in memory only
        public boolean bugNoIncrementOnExpiry = false; // planted: an expiry-grant reuses the old token

        public Integer holder = null;          // client id
        public long token = 0;                 // grant counter: THE fencing token source
        public int expiresAt = Integer.MAX_VALUE;
        public int clockOffset = 0;            // server-local clock = tick + offset (jump experiments)
        public final ArrayDeque<Integer> waitQueue = new ArrayDeque<>();

        LockSrv(String name) { this.name = name; }
        int now(int tick) { return tick + clockOffset; }

        boolean free(int tick) {
            return holder == null || (ttlEnabled && now(tick) >= expiresAt);
        }

        /** Returns the fencing token if granted, or -1 if denied.
         *  The token increases on EVERY grant — that monotonicity is the
         *  entire value the resource will later lean on. */
        long acquire(World w, int cliId) {
            w.msgCount++;
            if (free(w.tick)) {
                boolean tookOverExpired = holder != null;
                if (tookOverExpired) w.ev(name + ": lease of C" + (holder+1) + " (token " + token + ") has EXPIRED — granting to C" + (cliId+1));
                holder = cliId;
                if (!(bugNoIncrementOnExpiry && tookOverExpired))
                    token++;                    // monotonic, bumped on EVERY grant: never reused, never lowered
                // (the planted bug reuses the expired lease's token — "same lease, new tenant")
                expiresAt = ttlEnabled ? now(w.tick) + ttl : Integer.MAX_VALUE;
                return token;
            }
            return -1;
        }

        /** Renewal (heartbeat): extend the lease — conditionally, only for the
         *  client that still holds it. An unconditional renewal (a plain
         *  SET k me PX ttl) would be a lock theft; this is compare-and-extend. */
        boolean renew(World w, int cliId, long tok) {
            w.msgCount++;
            if (holder != null && holder == cliId && token == tok && now(w.tick) < expiresAt) {
                expiresAt = now(w.tick) + ttl;
                return true;
            }
            return false;
        }

        /** Truthful "do I still hold it?" — used by the defensive-check pattern. */
        boolean stillHolder(World w, int cliId, long tok) {
            w.msgCount++;
            return holder != null && holder == cliId && token == tok && now(w.tick) < expiresAt;
        }

        void release(World w, int cliId, long tok) {
            w.msgCount++;
            if (holder != null && holder == cliId && token == tok) {
                holder = null;
                expiresAt = Integer.MAX_VALUE;
            }
        }

        void crashRestart(World w) {
            holder = null; expiresAt = Integer.MAX_VALUE; waitQueue.clear();
            if (bugTokenRestart) { w.ev(name + " restarted — BUG: token counter was in memory, resets to 0"); token = 0; }
            else w.ev(name + " restarted — token counter was durable, continues from " + token);
        }
    }

    // ------------------------------------------------------------ the resource

    /** The thing the lock protects: a value clients read-modify-write.
     *  With fencing ON it keeps the highest token it has ever accepted and
     *  refuses anything older — one comparison, and it is the only line in
     *  this whole file that provides a guarantee that survives pauses. */
    public static class Res {
        public boolean fencing = false;
        public boolean bugAcceptUnseen = false; // planted: dedup (reject repeats) instead of ordering
        public boolean bugWriteOnlyFencing = false; // planted: reads don't carry tokens — in-flight writes can race a new holder's read
        public long maxToken = -1;
        public final Set<Long> seenTokens = new HashSet<>();
        public int value = 0;
        public int accepted = 0, rejected = 0;

        /** A fenced READ: the reader presents its token, which raises the
         *  resource's floor — after this, any write still in flight from an
         *  OLDER lease is rejected on arrival. Without this, a new holder can
         *  read a value that a stale in-flight write is about to change, and
         *  write-only fencing accepts both writes in token order while the
         *  read has already raced. Fence every operation, not just writes. */
        void observeRead(World w, long tok) {
            if (fencing && !bugWriteOnlyFencing) maxToken = Math.max(maxToken, tok);
        }

        boolean write(World w, Cli c, int newValue, long tok) {
            w.msgCount++;
            if (fencing) {
                boolean stale = bugAcceptUnseen ? seenTokens.contains(tok) : tok < maxToken;
                if (stale) {
                    rejected++;
                    w.ev("RESOURCE: REJECTED write value=" + newValue + " from " + c.name +
                         " (token " + tok + " is older than " + maxToken + " already seen) — stale holder fenced off");
                    return false;
                }
                seenTokens.add(tok);
                maxToken = Math.max(maxToken, tok);
            }
            value = newValue;
            accepted++;
            w.ev("RESOURCE: accepted write value=" + newValue + " from " + c.name + (fencing ? " (token " + tok + ")" : ""));
            return true;
        }
    }

    record Delivery(int at, long seq, Cli from, int newValue, long token) {}

    // ------------------------------------------------------------ clients

    public static class Pause { int round; String phase; int len;   // phase: "mid-work" | "after-check"
        public Pause(int round, String phase, int len) { this.round = round; this.phase = phase; this.len = len; } }

    public static class Cli {
        public final int id; public final String name;
        public int rounds = 1;               // critical sections to complete
        public int workTicks = 6;            // time between read and write
        public boolean useRenewal = false;   // heartbeat every ttl/3
        public boolean defensiveCheck = false; // ask "am I still holder?" right before writing
        public int pollEvery = 2;
        public int writeDelay = -1;          // per-client override; -1 = world default
        public final List<Pause> pauses = new ArrayList<>();
        public int majority = 1;             // 1 = single server; 3 = Redlock over 5
        public boolean[] reachable;          // which servers this client can talk to

        // runtime
        String state = "idle";               // idle waiting working awaitAck done
        int workLeft = 0;
        int readValue = 0;
        public long tok = -1;                // token of current grant (max token for Redlock)
        final Set<Integer> grantedBy = new HashSet<>();
        int pausedUntil = -1;
        public boolean crashed = false;
        public int done = 0, redone = 0, denied = 0;
        int nextPollAt = 0;
        boolean checkedOk = false;           // defensive check passed, write not yet sent

        public Cli(int id, String name) { this.id = id; this.name = name; }
        boolean believesHolding() { return !crashed && tok >= 0 && (state.equals("working") || state.equals("awaitAck")); }
    }

    public Cli addClient(String name) {
        Cli c = new Cli(clients.size(), name);
        c.reachable = new boolean[servers.length];
        Arrays.fill(c.reachable, true);
        clients.add(c);
        return c;
    }

    // ------------------------------------------------------------ the tick

    public void step() {
        tick++;
        // 1. deliver write messages whose time has come (in send order)
        wire.sort(Comparator.comparingInt((Delivery d) -> d.at).thenComparingLong(d -> d.seq));
        Iterator<Delivery> it = wire.iterator();
        List<Delivery> arrived = new ArrayList<>();
        while (it.hasNext()) { Delivery d = it.next(); if (d.at <= tick) { arrived.add(d); it.remove(); } }
        for (Delivery d : arrived) {
            boolean ok = resource.write(this, d.from, d.newValue, d.token);
            Cli c = d.from;
            if (c.crashed) continue;
            if (c.state.equals("awaitAck")) {           // result reaches the client
                if (ok) { finishRound(c); }
                else {                                   // fenced off: the work must be REDONE
                    c.redone++;
                    releaseAll(c);
                    c.state = "idle"; c.tok = -1;
                    ev(c.name + " learns its write was rejected — its lease was stale. It must re-acquire and redo the work.");
                }
            }
        }
        // 2. each client acts, in id order (determinism)
        for (Cli c : clients) {
            if (c.crashed || c.done >= c.rounds) continue;
            if (tick < c.pausedUntil) continue;         // paused: no work, no renewals, nothing
            switch (c.state) {
                case "idle" -> tryAcquire(c);
                case "waiting" -> { if (tick >= c.nextPollAt) tryAcquire(c); }
                case "working" -> workTick(c);
                case "awaitAck" -> { /* write in flight; nothing to do */ }
            }
        }
        // 3. watch mode: hand freed/expired locks to the next waiter directly
        for (LockSrv s : servers) {
            if (s.watchMode && s.free(tick) && !s.waitQueue.isEmpty()) {
                int next = s.waitQueue.pollFirst();
                Cli c = clients.get(next);
                if (!c.crashed && (c.state.equals("waiting"))) {
                    long t = s.acquire(this, c.id);     // the notification message
                    if (t >= 0) { startWork(c, t, s); ev(s.name + " notifies " + c.name + " — the lock is now theirs (token " + t + "). Nobody else was woken."); }
                }
            }
        }
        // 4. metrics
        int believers = 0;
        for (Cli c : clients) if (c.believesHolding()) believers++;
        if (believers >= 2) overlapTicks++;
    }

    private void tryAcquire(Cli c) {
        int grants = 0; long maxTok = -1;
        for (int i = 0; i < servers.length; i++) {
            if (!c.reachable[i]) continue;
            LockSrv s = servers[i];
            if (s.watchMode && !s.free(tick)) {          // subscribe instead of polling
                if (!s.waitQueue.contains(c.id)) { s.waitQueue.add(c.id); msgCount++; ev(c.name + " is waiting; " + s.name + " will notify it when the lock frees (watch)."); }
                c.state = "waiting"; c.nextPollAt = Integer.MAX_VALUE;
                return;
            }
            long t = s.acquire(this, c.id);
            if (t >= 0) { grants++; maxTok = Math.max(maxTok, t); c.grantedBy.add(i); }
        }
        if (grants >= c.majority) {
            startWork(c, maxTok, null);
        } else {
            for (int i : c.grantedBy) servers[i].release(this, c.id, servers[i].token); // give back partial grants
            c.grantedBy.clear();
            c.denied++;
            c.state = "waiting";
            c.nextPollAt = tick + c.pollEvery;
        }
    }

    private void startWork(Cli c, long tok, LockSrv via) {
        c.tok = tok;
        c.state = "working";
        c.workLeft = c.workTicks;
        c.readValue = resource.value; msgCount++;        // the READ half of read-modify-write
        resource.observeRead(this, tok);                 // the read carries the token too
        c.checkedOk = false;
        ev(c.name + " acquired the lock (token " + tok + "), read value=" + c.readValue + ", starts " + c.workTicks + " ticks of work.");
        applyPause(c, "at-start");
    }

    private void workTick(Cli c) {
        // renewal heartbeat while working
        if (c.useRenewal) {
            LockSrv s = servers[0];
            if (s.ttlEnabled && (tick % Math.max(1, s.ttl / 3)) == 0) {
                boolean ok = s.renew(this, c.id, c.tok);
                if (!ok) ev(c.name + " tried to renew and was refused — its lease is gone.");
            }
        }
        // scripted mid-work pause
        applyPause(c, "mid-work");
        if (tick < c.pausedUntil) return;
        c.workLeft--;
        if (c.workLeft > 0) return;

        // work finished -> optionally check, then send the write
        if (c.defensiveCheck && !c.checkedOk) {
            boolean still = true;
            for (int i : c.grantedBy.isEmpty() ? Set.of(0) : c.grantedBy) still &= servers[i].stillHolder(this, c.id, c.tok);
            if (!still) {
                ev(c.name + " checks first: \"am I still the holder?\" — NO. It stops, re-acquires, redoes the work. (The check saved it this time.)");
                c.redone++;
                c.state = "idle"; c.tok = -1; c.grantedBy.clear();
                return;
            }
            c.checkedOk = true;
            ev(c.name + " checks first: \"am I still the holder?\" — yes. It proceeds to write.");
            applyPause(c, "after-check");                 // the fatal gap: pause BETWEEN check and send
            if (tick < c.pausedUntil) return;
        }
        sendWrite(c);
    }

    private void sendWrite(Cli c) {
        int d = c.writeDelay >= 0 ? c.writeDelay : msgDelay;
        wire.add(new Delivery(tick + d, deliverySeq++, c, c.readValue + 1, c.tok));
        c.state = "awaitAck";
        ev(c.name + " sends write value=" + (c.readValue + 1) + " (token " + c.tok + ") — it will reach the resource in " + d + " ticks.");
    }

    private void finishRound(Cli c) {
        releaseAll(c);
        c.done++;
        c.state = "idle"; c.tok = -1;
        ev(c.name + " releases the lock. Critical sections completed: " + c.done + "/" + c.rounds + ".");
    }

    private void releaseAll(Cli c) {
        if (c.grantedBy.isEmpty()) { servers[0].release(this, c.id, c.tok); }
        else for (int i : c.grantedBy) servers[i].release(this, c.id, servers[i].token);
        c.grantedBy.clear();
    }

    private void applyPause(Cli c, String phase) {
        for (Iterator<Pause> it = c.pauses.iterator(); it.hasNext(); ) {
            Pause p = it.next();
            if (p.phase.equals(phase) && p.round == c.done) {
                it.remove();
                c.pausedUntil = tick + p.len;
                ev(c.name + " STOPS RESPONDING for " + p.len + " ticks (" + phase + ") — a GC pause, a swap storm, a debugger. It does not know this is happening.");
                return;
            }
        }
    }

    public void run(int maxTicks) {
        while (tick < maxTicks) {
            step();
            boolean allDone = clients.stream().allMatch(c -> c.crashed || c.done >= c.rounds);
            if (allDone && wire.isEmpty()) break;
        }
    }

    public int completed() { return clients.stream().mapToInt(c -> c.done).sum(); }

    // ------------------------------------------------------------ narrated demo

    public static void main(String[] args) {
        System.out.println("=== Act 1 — a lock with no expiry, and a holder that dies");
        World w1 = new World(1);
        w1.verbose = true;
        Cli a = w1.addClient("Pia");  a.rounds = 1; a.workTicks = 4;
        Cli b = w1.addClient("Quinn"); b.rounds = 1; b.workTicks = 4;
        a.crashed = false;
        w1.step();                                    // Pia acquires
        w1.clients.get(0).crashed = true;
        w1.ev("Pia CRASHES holding the lock. She will never release it.");
        w1.run(40);
        System.out.println("-> after 40 ticks: Quinn completed " + b.done + " of 1. The system is stuck forever.\n");

        System.out.println("=== Act 2 — add a TTL: the lease frees itself");
        World w2 = new World(1);
        w2.verbose = true;
        w2.servers[0].ttlEnabled = true; w2.servers[0].ttl = 10;
        Cli a2 = w2.addClient("Pia");  a2.rounds = 1; a2.workTicks = 4;
        Cli b2 = w2.addClient("Quinn"); b2.rounds = 1; b2.workTicks = 4;
        w2.step();
        w2.clients.get(0).crashed = true;
        w2.ev("Pia CRASHES holding the lock — but this lease expires at t011.");
        w2.run(60);
        System.out.println("-> Quinn completed " + b2.done + " of 1, roughly one TTL after the crash.\n");

        System.out.println("=== Act 3 — the same TTL, and a pause instead of a crash");
        World w3 = new World(1);
        w3.verbose = true;
        w3.servers[0].ttlEnabled = true; w3.servers[0].ttl = 10;
        Cli a3 = w3.addClient("Pia");  a3.rounds = 1; a3.workTicks = 4;
        a3.pauses.add(new Pause(0, "mid-work", 20));  // pause 2x the TTL
        Cli b3 = w3.addClient("Quinn"); b3.rounds = 1; b3.workTicks = 4;
        w3.run(80);
        System.out.println("-> completed sections: " + w3.completed() + ", resource value: " + w3.resource.value +
            (w3.resource.value < w3.completed() ? "  <- A LOST UPDATE. Two believed holders, ticks: " + w3.overlapTicks : ""));
        System.out.println();

        System.out.println("=== Act 4 — same story, resource checks the fencing token");
        World w4 = new World(1);
        w4.verbose = true;
        w4.servers[0].ttlEnabled = true; w4.servers[0].ttl = 10;
        w4.resource.fencing = true;
        Cli a4 = w4.addClient("Pia");  a4.rounds = 1; a4.workTicks = 4;
        a4.pauses.add(new Pause(0, "mid-work", 20));
        Cli b4 = w4.addClient("Quinn"); b4.rounds = 1; b4.workTicks = 4;
        w4.run(120);
        System.out.println("-> completed sections: " + w4.completed() + ", resource value: " + w4.resource.value +
            ", stale writes rejected: " + w4.resource.rejected + " — nothing was lost; the LATE writer paid with a redo.");
    }
}
