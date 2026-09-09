import java.util.*;

/**
 * Measured experiments. Run all:   java src/Experiments.java
 * Run one:  java src/Experiments.java pause
 *
 * Names: crash | pause | dilemma | check | herd | redlock | efficiency
 */
public class Experiments {

    public static void main(String[] args) {
        Set<String> want = args.length == 0 ? null : Set.of(args);
        if (want == null || want.contains("crash"))      crash();
        if (want == null || want.contains("pause"))      pause();
        if (want == null || want.contains("dilemma"))    dilemma();
        if (want == null || want.contains("check"))      check();
        if (want == null || want.contains("herd"))       herd();
        if (want == null || want.contains("redlock"))    redlock();
        if (want == null || want.contains("efficiency")) efficiency();
    }

    static void header(String t) { System.out.println(); System.out.println("=== " + t); }

    static World ttlWorld(int ttl) {
        World w = new World(1);
        w.servers[0].ttlEnabled = ttl > 0;
        w.servers[0].ttl = Math.max(ttl, 1);
        return w;
    }

    /**
     * EXPERIMENT 1 — a holder crashes at tick 2. How long until anyone else
     * gets to work? The TTL is exactly the price of crash recovery.
     */
    static void crash() {
        header("crash — holder dies at t2; when does the next client finish?");
        System.out.printf("%8s | %s%n", "TTL", "other client finishes at");
        for (int ttl : new int[]{0, 5, 10, 30, 100}) {
            World w = ttlWorld(ttl);
            World.Cli a = w.addClient("Pia");   a.rounds = 1;
            World.Cli b = w.addClient("Quinn"); b.rounds = 1; b.workTicks = 4;
            w.step(); w.step();
            a.crashed = true;
            w.run(1000);
            System.out.printf("%8s | %s%n", ttl == 0 ? "none" : String.valueOf(ttl),
                b.done == 1 ? "t" + w.tick : "NEVER (still stuck at t1000)");
        }
        System.out.println("(the lease frees itself one TTL after the silence began — no human, no reboot)");
    }

    static World pauseRun(int ttl, int pauseLen, boolean fencing, boolean renewal, int rounds) {
        World w = ttlWorld(ttl);
        w.resource.fencing = fencing;
        World.Cli a = w.addClient("Pia");
        a.rounds = rounds; a.workTicks = 4; a.useRenewal = renewal;
        for (int r = 0; r < rounds; r++) a.pauses.add(new World.Pause(r, "mid-work", pauseLen));
        World.Cli b = w.addClient("Quinn");
        b.rounds = rounds; b.workTicks = 4;
        w.run(rounds * (pauseLen + 60) + 200);
        return w;
    }

    /**
     * EXPERIMENT 2 — the pause sweep. TTL 30. One client pauses mid-critical-
     * section for P ticks, 8 rounds. Watch the cliff at P > TTL, watch
     * renewal NOT move it, and watch fencing flatten it.
     */
    static void pause() {
        header("pause — TTL 30, 8 rounds, one client pauses P ticks mid-work");
        System.out.printf("%6s | %18s | %18s | %s%n", "P", "plain lease", "+ renewal", "+ fencing at resource");
        for (int p : new int[]{0, 10, 25, 31, 45, 90}) {
            World plain = pauseRun(30, p, false, false, 8);
            World renew = pauseRun(30, p, false, true, 8);
            World fence = pauseRun(30, p, true, false, 8);
            System.out.printf("%6d | %6d lost updates | %6d lost updates | %d lost, %d stale writes rejected%n",
                p, plain.completed() - plain.resource.value,
                renew.completed() - renew.resource.value,
                fence.completed() - fence.resource.value, fence.resource.rejected);
        }
        System.out.println("(renewal extends an ALIVE holder's lease; a paused holder cannot renew —");
        System.out.println(" the cliff does not move. Only the resource's own token check removes it.)");
    }

    /**
     * EXPERIMENT 3 — pick a TTL. Go on. Crash recovery wants it short;
     * pause safety wants it longer than your longest pause — which is a
     * number nobody has.
     */
    static void dilemma() {
        header("dilemma — same system, crashes AND 25-tick pauses; sweep the TTL");
        System.out.printf("%6s | %28s | %s%n", "TTL", "recovery after a crash", "lost updates (8 rounds, P=25)");
        for (int ttl : new int[]{5, 10, 20, 40, 80}) {
            World w = ttlWorld(ttl);
            World.Cli a = w.addClient("Pia");   a.rounds = 1;
            World.Cli b = w.addClient("Quinn"); b.rounds = 1; b.workTicks = 4;
            w.step(); w.step();
            a.crashed = true;
            w.run(1000);
            int recovery = w.tick;
            World p = pauseRun(ttl, 25, false, false, 8);
            System.out.printf("%6d | %22s | %d%n", ttl,
                "done at t" + recovery, p.completed() - p.resource.value);
        }
        System.out.println("(every row is a compromise: fast recovery = corruption under pauses,");
        System.out.println(" pause-safe = slow recovery — and 'my longest pause' is unknowable anyway.)");
    }

    /**
     * EXPERIMENT 4 — the defensive check ("am I still the holder?" right
     * before writing). It fixes the case you first think of and dies in the
     * gap between the check and the write.
     */
    static void check() {
        header("check — ask the server 'am I still holder?' before writing (TTL 10, pause 20)");
        World none = pauseWorldPhase("mid-work", false);
        World good = pauseWorldPhase("mid-work", true);
        World gap  = pauseWorldPhase("after-check", true);
        System.out.println("no check, pause mid-work        : " + (none.completed() - none.resource.value) + " lost update(s)");
        System.out.println("check, pause mid-work (wakes, checks, sees the truth): " +
            (good.completed() - good.resource.value) + " lost — the check saved it, cost 1 redo");
        System.out.println("check, pause lands AFTER the check, BEFORE the write : " +
            (gap.completed() - gap.resource.value) + " lost update(s) — check-then-act has a gap");
        System.out.println("(any liveness question answered by the server is stale the moment the answer");
        System.out.println(" arrives. The only check that cannot be stale is done AT the resource, atomically.)");
    }

    static World pauseWorldPhase(String phase, boolean defensive) {
        World w = ttlWorld(10);
        World.Cli a = w.addClient("Pia");
        a.rounds = 1; a.workTicks = 4; a.defensiveCheck = defensive;
        a.pauses.add(new World.Pause(0, phase, 20));
        World.Cli b = w.addClient("Quinn"); b.rounds = 1; b.workTicks = 4;
        w.run(400);
        return w;
    }

    /**
     * EXPERIMENT 5 — N clients want the same lock; the holder works 12 ticks.
     * Polling every tick vs a watch queue (server notifies the next waiter).
     */
    static void herd() {
        header("herd — N waiters, 12-tick holds: polling vs watch queue");
        System.out.printf("%4s | %14s | %14s%n", "N", "poll messages", "watch messages");
        for (int n : new int[]{3, 8, 20}) {
            long pollMsgs, watchMsgs; int pollEnd, watchEnd;
            {
                World w = new World(1);
                for (int i = 0; i < n; i++) { World.Cli c = w.addClient("C" + i); c.rounds = 1; c.workTicks = 12; c.pollEvery = 1; }
                w.run(3000);
                pollMsgs = w.msgCount; pollEnd = w.tick;
            }
            World w = new World(1);
            w.servers[0].watchMode = true;
            for (int i = 0; i < n; i++) { World.Cli c = w.addClient("C" + i); c.rounds = 1; c.workTicks = 12; }
            w.run(3000);
            watchMsgs = w.msgCount; watchEnd = w.tick;
            System.out.printf("%4d | %8d (t%d) | %8d (t%d)%n", n, pollMsgs, pollEnd, watchMsgs, watchEnd);
        }
        System.out.println("(polling scales as waiters x wait time; the watch queue is one notification per");
        System.out.println(" handover — and hands the lock over FIFO, which polling doesn't promise.)");
    }

    /**
     * EXPERIMENT 6 — five independent lock servers, majority (3/5) to hold:
     * the Redlock shape. Availability up; and then the two failures that made
     * the argument famous.
     */
    static void redlock() {
        header("redlock — 5 independent servers, majority=3");
        {   // availability: 2 servers unreachable, still locks
            World w = new World(5);
            for (World.LockSrv s : w.servers) { s.ttlEnabled = true; s.ttl = 20; }
            World.Cli a = w.addClient("Pia"); a.rounds = 1; a.workTicks = 4; a.majority = 3;
            a.reachable = new boolean[]{true, true, true, false, false};
            w.run(100);
            System.out.println("2 of 5 servers unreachable: client completed " + a.done + "/1 — no single point of failure. Good.");
        }
        {   // client pause: majority does not survive it (and there is no token to check)
            World w = new World(5);
            for (World.LockSrv s : w.servers) { s.ttlEnabled = true; s.ttl = 10; }
            World.Cli a = w.addClient("Pia"); a.rounds = 1; a.workTicks = 4; a.majority = 3;
            a.pauses.add(new World.Pause(0, "mid-work", 25));
            World.Cli b = w.addClient("Quinn"); b.rounds = 1; b.workTicks = 4; b.majority = 3;
            w.run(400);
            System.out.println("client GC pause 25 > TTL 10: lost updates = " + (w.completed() - w.resource.value) +
                ", overlap ticks = " + w.overlapTicks + " — five servers agreed; the pause didn't ask them.");
        }
        {   // clock jump on one server: two overlapping majorities
            World w = new World(5);
            for (World.LockSrv s : w.servers) { s.ttlEnabled = true; s.ttl = 30; }
            World.Cli a = w.addClient("Pia"); a.rounds = 1; a.workTicks = 20; a.majority = 3;
            a.reachable = new boolean[]{true, true, true, false, false};
            World.Cli b = w.addClient("Quinn"); b.rounds = 1; b.workTicks = 4; b.majority = 3;
            b.reachable = new boolean[]{true, false, false, true, true};
            w.run(3);
            w.servers[0].clockOffset = 100;   // S1's wall clock jumps (NTP step, VM migration)
            w.run(400);
            System.out.println("S1 clock jumps: Pia holds {S1,S2,S3}, Quinn assembles {S1,S4,S5} -> overlap ticks = " +
                w.overlapTicks + ", lost updates = " + (w.completed() - w.resource.value) +
                " — safety was resting on a wall clock.");
        }
        System.out.println("(contrast: single server + fencing under the same pause: 0 lost — run `pause`.");
        System.out.println(" Redlock's leases carry no token the resource could check; that is the critique.)");
    }

    /**
     * EXPERIMENT 7 — the SAME overlap events, read under two different jobs.
     * If the lock guards an invariant, each overlap is corruption. If it only
     * de-duplicates idempotent work, each overlap is a shrug.
     */
    static void efficiency() {
        header("efficiency vs correctness — same faults, different jobs (TTL 10, pause 20, 6 rounds)");
        World w = pauseRun(10, 20, false, false, 6);
        int overlaps = w.completed() - w.resource.value;
        System.out.println("overlap events measured: " + overlaps + " (each = two clients ran the critical section together)");
        System.out.println();
        System.out.println("job A: 'apply the interest calculation exactly once' (an INVARIANT)");
        System.out.println("   -> " + overlaps + " lost updates = money wrong. The lock FAILED at its job;");
        System.out.println("      it needed fencing at the resource (or a conditional write).");
        System.out.println();
        System.out.println("job B: 'resize uploaded images; doing one twice is wasted CPU' (an OPTIMIZATION)");
        System.out.println("   -> " + overlaps + " images resized twice, final state correct. The lock did its job:");
        System.out.println("      it made duplicates RARE (" + overlaps + " in " + w.completed() + " sections), not impossible.");
        System.out.println();
        System.out.println("(the same lock, the same faults, opposite verdicts — 'is this lock for efficiency");
        System.out.println(" or for correctness?' is the first question, and if the answer is correctness,");
        System.out.println(" the real guarantee must live at the resource. For job B, the work must be");
        System.out.println(" idempotent anyway — at which point the lock is just saving you duplicate cost.)");
    }
}
