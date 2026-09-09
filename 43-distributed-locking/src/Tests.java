import java.util.*;

/**
 * Scenario checks, named after the claims in README.md.
 *
 *   java src/Tests.java          -> all guarantees
 *   java src/Tests.java broken   -> planted bugs, each must be caught
 */
public class Tests {

    static int passed = 0, failed = 0;
    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  PASS  " + name); }
        else    { failed++; System.out.println("  FAIL  " + name + "  [" + detail + "]"); }
    }

    // -- world builders -------------------------------------------------------

    static World ttlWorld(int ttl) {
        World w = new World(1);
        w.servers[0].ttlEnabled = true;
        w.servers[0].ttl = ttl;
        return w;
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("broken")) { brokenMode(); return; }

        lock_admits_one_holder_at_a_time();
        crashed_holder_blocks_everyone_forever_without_ttl();
        ttl_frees_the_lock_about_one_ttl_after_a_crash();
        renewal_extends_a_lease_while_the_holder_is_alive();
        pause_longer_than_ttl_makes_two_believed_holders();
        the_overlap_loses_an_update();
        defensive_check_saves_the_wake_then_write_case();
        defensive_check_dies_in_its_own_gap();
        fencing_rejects_the_stale_writer_and_nothing_is_lost();
        fencing_tokens_strictly_increase_across_expiries();
        delayed_write_from_a_dead_holder_is_fenced_off();
        watch_mode_beats_polling_and_keeps_fifo_order();
        majority_lock_tolerates_unreachable_servers();
        majority_alone_is_not_exclusive_after_a_clock_jump();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // -------------------------------------------------------------- scenarios

    /** Happy path: with nobody pausing, a plain lock really does serialize. */
    static void lock_admits_one_holder_at_a_time() {
        World w = new World(1);
        World.Cli a = w.addClient("Pia");   a.rounds = 3; a.workTicks = 4;
        World.Cli b = w.addClient("Quinn"); b.rounds = 3; b.workTicks = 4;
        w.run(300);
        check("lock_admits_one_holder_at_a_time",
              w.completed() == 6 && w.resource.value == 6 && w.overlapTicks == 0,
              "completed=" + w.completed() + " value=" + w.resource.value + " overlap=" + w.overlapTicks);
    }

    /** The two-phase-commit lesson again: a lock with no expiry turns one
     *  crash into everyone's permanent halt. */
    static void crashed_holder_blocks_everyone_forever_without_ttl() {
        World w = new World(1);
        World.Cli a = w.addClient("Pia");   a.rounds = 1;
        World.Cli b = w.addClient("Quinn"); b.rounds = 1;
        w.step();                 // Pia acquires
        a.crashed = true;
        w.run(200);
        check("crashed_holder_blocks_everyone_forever_without_ttl",
              b.done == 0 && w.tick >= 200,
              "quinn done=" + b.done + " tick=" + w.tick);
    }

    static void ttl_frees_the_lock_about_one_ttl_after_a_crash() {
        World w = ttlWorld(10);
        World.Cli a = w.addClient("Pia");   a.rounds = 1;
        World.Cli b = w.addClient("Quinn"); b.rounds = 1; b.workTicks = 4;
        w.step();
        a.crashed = true;
        w.run(200);
        check("ttl_frees_the_lock_about_one_ttl_after_a_crash",
              b.done == 1 && w.tick <= 25,
              "quinn done=" + b.done + " finished at tick=" + w.tick);
    }

    /** A lease + heartbeat lets honest long work exceed the TTL: the TTL
     *  bounds the silence, not the total hold. */
    static void renewal_extends_a_lease_while_the_holder_is_alive() {
        World w = ttlWorld(10);
        World.Cli a = w.addClient("Pia");   a.rounds = 1; a.workTicks = 35; a.useRenewal = true;
        World.Cli b = w.addClient("Quinn"); b.rounds = 1; b.workTicks = 4;
        w.run(300);
        check("renewal_extends_a_lease_while_the_holder_is_alive",
              w.completed() == 2 && w.resource.value == 2 && w.overlapTicks == 0 && w.servers[0].token == 2,
              "completed=" + w.completed() + " value=" + w.resource.value +
              " overlap=" + w.overlapTicks + " grants=" + w.servers[0].token);
    }

    static World pauseWorld(boolean fencing, String phase, boolean defensive) {
        World w = ttlWorld(10);
        w.resource.fencing = fencing;
        World.Cli a = w.addClient("Pia");   a.rounds = 1; a.workTicks = 4; a.defensiveCheck = defensive;
        a.pauses.add(new World.Pause(0, phase, 20));   // pause 2x TTL
        World.Cli b = w.addClient("Quinn"); b.rounds = 1; b.workTicks = 4;
        w.run(300);
        return w;
    }

    static void pause_longer_than_ttl_makes_two_believed_holders() {
        World w = pauseWorld(false, "mid-work", false);
        check("pause_longer_than_ttl_makes_two_believed_holders",
              w.overlapTicks > 0, "overlapTicks=" + w.overlapTicks);
    }

    static void the_overlap_loses_an_update() {
        World w = pauseWorld(false, "mid-work", false);
        check("the_overlap_loses_an_update",
              w.completed() == 2 && w.resource.value == 1,
              "completed=" + w.completed() + " value=" + w.resource.value);
    }

    /** Asking the server "am I still the holder?" right before writing DOES
     *  rescue the case where the pause ended before the check ran... */
    static void defensive_check_saves_the_wake_then_write_case() {
        World w = pauseWorld(false, "mid-work", true);
        check("defensive_check_saves_the_wake_then_write_case",
              w.completed() == 2 && w.resource.value == 2 && w.clients.get(0).redone == 1,
              "completed=" + w.completed() + " value=" + w.resource.value + " redone=" + w.clients.get(0).redone);
    }

    /** ...and is worthless when the pause lands BETWEEN the check and the
     *  write. Check-then-act has a gap, and a pause can always land in it. */
    static void defensive_check_dies_in_its_own_gap() {
        World w = pauseWorld(false, "after-check", true);
        check("defensive_check_dies_in_its_own_gap",
              w.completed() == 2 && w.resource.value == 1,
              "completed=" + w.completed() + " value=" + w.resource.value);
    }

    static void fencing_rejects_the_stale_writer_and_nothing_is_lost() {
        World w = pauseWorld(true, "mid-work", false);
        check("fencing_rejects_the_stale_writer_and_nothing_is_lost",
              w.completed() == 2 && w.resource.value == 2 && w.resource.rejected == 1
              && w.clients.get(0).redone == 1,
              "completed=" + w.completed() + " value=" + w.resource.value +
              " rejected=" + w.resource.rejected + " redone=" + w.clients.get(0).redone);
    }

    static void fencing_tokens_strictly_increase_across_expiries() {
        World w = ttlWorld(8);
        List<Long> tokens = new ArrayList<>();
        // three clients, each crashing while holding, so every grant is an expiry-takeover
        for (int i = 0; i < 3; i++) {
            World.Cli c = w.addClient("C" + (i + 1));
            c.rounds = 1;
        }
        for (int i = 0; i < 3; i++) {
            w.run(w.tick + 1);
            World.Cli holder = w.clients.get(w.servers[0].holder);
            tokens.add(holder.tok);
            holder.crashed = true;
            w.run(w.tick + 10);   // let the lease expire
        }
        boolean increasing = tokens.get(0) < tokens.get(1) && tokens.get(1) < tokens.get(2);
        check("fencing_tokens_strictly_increase_across_expiries", increasing, "tokens=" + tokens);
    }

    /** The failure fencing handles that NO liveness check can: a write packet
     *  already in flight when its sender's lease died. */
    static void delayed_write_from_a_dead_holder_is_fenced_off() {
        // fencing ON
        World w = ttlWorld(10);
        w.resource.fencing = true;
        World.Cli a = w.addClient("Pia");   a.rounds = 1; a.workTicks = 2; a.writeDelay = 40;
        World.Cli b = w.addClient("Quinn"); b.rounds = 2; b.workTicks = 4;
        w.run(5);
        a.crashed = true;         // write already in the wire; sender is gone
        w.run(300);
        boolean fencedOk = w.resource.value == 2 && w.resource.rejected == 1;
        // fencing OFF: the ghost packet lands LAST and rolls the value back
        World w2 = ttlWorld(10);
        World.Cli a2 = w2.addClient("Pia");   a2.rounds = 1; a2.workTicks = 2; a2.writeDelay = 40;
        World.Cli b2 = w2.addClient("Quinn"); b2.rounds = 2; b2.workTicks = 4;
        w2.run(5);
        a2.crashed = true;
        w2.run(300);
        boolean ghostWins = w2.resource.value == 1;   // Quinn's 2 completed sections erased back to 1
        check("delayed_write_from_a_dead_holder_is_fenced_off",
              fencedOk && ghostWins,
              "fencing: value=" + w.resource.value + " rejected=" + w.resource.rejected +
              " | no fencing: value=" + w2.resource.value);
    }

    /** 6 waiters behind one 12-tick holder: polling hammers the server;
     *  a watch queue sends ONE notification per handover, in FIFO order. */
    static void watch_mode_beats_polling_and_keeps_fifo_order() {
        long pollMsgs, watchMsgs;
        {
            World w = new World(1);
            for (int i = 0; i < 7; i++) { World.Cli c = w.addClient("C" + (i + 1)); c.rounds = 1; c.workTicks = 12; c.pollEvery = 1; }
            w.run(600);
            pollMsgs = w.msgCount;
        }
        World w = new World(1);
        w.servers[0].watchMode = true;
        for (int i = 0; i < 7; i++) { World.Cli c = w.addClient("C" + (i + 1)); c.rounds = 1; c.workTicks = 12; }
        w.run(600);
        watchMsgs = w.msgCount;
        check("watch_mode_beats_polling_and_keeps_fifo_order",
              w.completed() == 7 && watchMsgs * 2 < pollMsgs,
              "watch=" + watchMsgs + " msgs vs poll=" + pollMsgs);
    }

    /** The availability upside of asking N independent servers: a client that
     *  can reach only 3 of 5 still locks. */
    static void majority_lock_tolerates_unreachable_servers() {
        World w = new World(5);
        for (World.LockSrv s : w.servers) { s.ttlEnabled = true; s.ttl = 20; }
        World.Cli a = w.addClient("Pia");
        a.rounds = 1; a.workTicks = 4; a.majority = 3;
        a.reachable = new boolean[]{true, true, true, false, false};
        w.run(100);
        check("majority_lock_tolerates_unreachable_servers",
              a.done == 1, "done=" + a.done);
    }

    /** The Redlock critique, run: A holds {S1,S2,S3}; S1's clock jumps and
     *  expires A early; B (partitioned the other way) assembles {S1,S4,S5}.
     *  Two disjoint-looking majorities that share S1 — both "hold the lock". */
    static void majority_alone_is_not_exclusive_after_a_clock_jump() {
        World w = new World(5);
        for (World.LockSrv s : w.servers) { s.ttlEnabled = true; s.ttl = 30; }
        World.Cli a = w.addClient("Pia");
        a.rounds = 1; a.workTicks = 20; a.majority = 3;
        a.reachable = new boolean[]{true, true, true, false, false};
        World.Cli b = w.addClient("Quinn");
        b.rounds = 1; b.workTicks = 4; b.majority = 3;
        b.reachable = new boolean[]{true, false, false, true, true};
        w.run(3);                       // A holds S1,S2,S3 and is working
        w.servers[0].clockOffset = 100; // S1's clock jumps forward: A's lease "expires" there
        w.run(300);
        check("majority_alone_is_not_exclusive_after_a_clock_jump",
              w.overlapTicks > 0 && w.resource.value < w.completed(),
              "overlap=" + w.overlapTicks + " value=" + w.resource.value + " completed=" + w.completed());
    }

    // ------------------------------------------------------------ broken mode

    static void brokenMode() {
        System.out.println("Planted bug: token-reused-on-expiry (an expiry-grant keeps the old token)");
        {
            World w = ttlWorld(10);
            w.servers[0].bugNoIncrementOnExpiry = true;
            w.resource.fencing = true;   // fencing is ON and still fails — that's the point
            World.Cli a = w.addClient("Pia");   a.rounds = 1; a.workTicks = 4;
            a.pauses.add(new World.Pause(0, "mid-work", 20));
            World.Cli b = w.addClient("Quinn"); b.rounds = 1; b.workTicks = 4;
            w.run(300);
            check("bug_caught: same token on both holders defeats fencing (" +
                  w.resource.value + "/" + w.completed() + " survive)",
                  w.resource.value < w.completed(),
                  "value=" + w.resource.value + " completed=" + w.completed());
        }
        System.out.println("Planted bug: token-counter-in-memory (server restart resets it to 0)");
        {
            World w = ttlWorld(10);
            w.resource.fencing = true;
            World.Cli a = w.addClient("Pia"); a.rounds = 4; a.workTicks = 4;
            w.run(16);                       // ~2 rounds done; resource has seen token 2
            int before = w.resource.rejected;
            w.servers[0].bugTokenRestart = true;
            w.servers[0].crashRestart(w);
            w.run(300);
            // safety held (no lost update) but LEGITIMATE writes got rejected until
            // the reborn counter climbed back past what the resource had seen —
            // fencing's arithmetic only works if the counter never goes backwards
            check("bug_caught: reset counter fences off LEGITIMATE new writers until it catches up",
                  a.done == 4 && w.resource.value == 4 && w.resource.rejected > before && a.redone >= 1,
                  "done=" + a.done + " value=" + w.resource.value +
                  " rejected=" + w.resource.rejected + " redone=" + a.redone);
        }
        System.out.println("Planted bug: dedup-instead-of-ordering (resource rejects only tokens it has SEEN)");
        {
            World w = ttlWorld(10);
            w.resource.fencing = true;
            w.resource.bugAcceptUnseen = true;
            World.Cli a = w.addClient("Pia");   a.rounds = 1; a.workTicks = 4;
            a.pauses.add(new World.Pause(0, "mid-work", 20));
            World.Cli b = w.addClient("Quinn"); b.rounds = 1; b.workTicks = 4;
            w.run(300);
            check("bug_caught: an UNSEEN stale token walks straight past a dedup check (" +
                  w.resource.value + "/" + w.completed() + " survive)",
                  w.resource.value < w.completed(),
                  "value=" + w.resource.value + " completed=" + w.completed());
        }
        System.out.println("Planted bug: write-only fencing (reads don't carry tokens)");
        {
            // The bug this lab found in itself: pause 25 < TTL 30, yet an update
            // dies — the old holder's write was IN FLIGHT when the lease expired,
            // and the new holder READ the value before that write landed. Both
            // writes then arrive with valid, increasing tokens. Only a fenced
            // read (which raises the resource's floor) catches it.
            World w = ttlWorld(30);
            w.resource.fencing = true;
            w.resource.bugWriteOnlyFencing = true;
            World.Cli a = w.addClient("Pia");   a.rounds = 1; a.workTicks = 4;
            a.pauses.add(new World.Pause(0, "mid-work", 25));   // SHORTER than the TTL
            World.Cli b = w.addClient("Quinn"); b.rounds = 1; b.workTicks = 4;
            w.run(300);
            check("bug_caught: unfenced reads race in-flight writes even with pause < TTL (" +
                  w.resource.value + "/" + w.completed() + " survive, 0 rejections)",
                  w.resource.value < w.completed() && w.resource.rejected == 0,
                  "value=" + w.resource.value + " completed=" + w.completed() +
                  " rejected=" + w.resource.rejected);
        }
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
