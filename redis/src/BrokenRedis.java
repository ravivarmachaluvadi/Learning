import java.nio.file.*;
import java.util.*;

/**
 * Three planted bugs, each one a mistake somebody has genuinely shipped.
 *
 *     java src/BrokenRedis.java        show all three
 *     java src/BrokenRedis.java 2      show just bug 2
 *
 * For each: work out WHICH TEST in src/Tests.java catches it and WHICH GUARANTEE
 * from README §12 it violates, before you read the output. Answers in SOLUTIONS.md.
 */
public final class BrokenRedis {

    static int port = 7901;

    public static void main(String[] args) throws Exception {
        Set<Integer> only = new HashSet<>();
        for (String a : args) only.add(Integer.parseInt(a));
        if (only.isEmpty() || only.contains(1)) bug1();
        if (only.isEmpty() || only.contains(2)) bug2();
        if (only.isEmpty() || only.contains(3)) bug3();
        System.out.println("\nWhich test in src/Tests.java fails for each? Which invariant in README §12?");
    }

    static void header(int n, String what, String looks) {
        System.out.println("\n" + "=".repeat(72));
        System.out.println("BUG " + n + " -- " + what);
        System.out.println("what a reviewer would think: \"" + looks + "\"");
        System.out.println("=".repeat(72));
    }

    /** The command is replicated exactly as the client sent it, with no rewrite. */
    static void bug1() throws Exception {
        header(1, "EXPIRE is sent to the replica exactly as the client typed it",
                  "it's the same command, why would you rewrite it?");
        Commands.BUG_RELATIVE_EXPIRE = true;
        try {
            MiniRedis master = new MiniRedis(port++), replica = new MiniRedis(port++);
            master.start(); replica.start();
            try (Cli mc = new Cli(master.port); Cli rc = new Cli(replica.port)) {
                rc.cmd("REPLICAOF", "127.0.0.1", String.valueOf(master.port));
                Thread.sleep(200);
                mc.cmd("DEBUG", "REPLDELAY", "900");   // 900 ms of ordinary replication lag
                mc.cmd("SET", "session", "alice");
                mc.cmd("EXPIRE", "session", "10");
                Thread.sleep(1400);                     // long enough for the replica to receive it
                long mTtl = (Long) mc.cmd("PTTL", "session");
                long rTtl = (Long) rc.cmd("PTTL", "session");
                System.out.printf("%n  deadline on the master  : %,d ms away%n", mTtl);
                System.out.printf("  deadline on the replica : %,d ms away%n", rTtl);
                System.out.printf("  they disagree by        : %,d ms%n", Math.abs(mTtl - rTtl));
                System.out.println("""
                
                  The replica applied the command LATER than the master did, and the
                  command said "ten seconds from now" -- so its "now" was different.
                  Every replication hop adds more drift, and after a failover the key
                  expires at the wrong moment. Redis avoids this by rewriting the
                  command to an absolute deadline before it leaves the server.""".indent(0));
            }
            master.stop(); replica.stop();
        } finally { Commands.BUG_RELATIVE_EXPIRE = false; }
    }

    /** INCR reads the hash table directly instead of going through the expiry check. */
    static void bug2() throws Exception {
        header(2, "INCR reads the table directly instead of through the expiry check",
                  "lookup() just does a map get with extra steps -- inline it, it's faster");
        Commands.BUG_INCR_IGNORES_EXPIRY = true;
        try {
            MiniRedis s = new MiniRedis(port++);
            s.start();
            try (Cli c = new Cli(s.port)) {
                c.cmd("DEBUG", "SET-ACTIVE-EXPIRE", "0");
                c.cmd("SET", "ratelimit:user:9", "99", "PX", "1000");
                c.cmd("DEBUG", "ADVANCE", "60000");            // the window ended a minute ago
                System.out.println("\n  the counter's window ended 60 seconds ago.");
                System.out.println("  GET says  : " + c.cmd("GET", "ratelimit:user:9") + "   (correct -- the window is over)");
                System.out.println("  INCR says : " + c.cmd("INCR", "ratelimit:user:9") + "   (it resurrected the dead counter)");
                System.out.println("""
                
                  Two commands disagree about whether the same key exists. A rate
                  limiter built on this never resets: the count carries over from a
                  window that ended long ago, and the user is throttled forever with
                  no way to recover. Note how quiet it is -- GET behaves perfectly, so
                  every debugging session starts by looking in the wrong place.""".indent(0));
            }
            s.stop();
        } finally { Commands.BUG_INCR_IGNORES_EXPIRY = false; }
    }

    /** SET is written to the log exactly as the client typed it, NX and all. */
    static void bug3() throws Exception {
        header(3, "SET is logged exactly as typed, keeping NX and a relative deadline",
                  "why would you rewrite a command into a different command?");
        Commands.BUG_NO_SET_REWRITE = true;
        Path aof = Files.createTempFile("brokenredis", ".aof");
        Files.deleteIfExists(aof);
        try {
            MiniRedis s = new MiniRedis(port++);
            s.aofPath = aof.toString(); s.appendfsync = "always";
            s.start();
            try (Cli a = new Cli(s.port); Cli b = new Cli(s.port)) {
                System.out.println("\n  a short-lived lock changes hands, exactly as it would in production:");
                System.out.println("    worker A takes it for 200 ms  -> " + a.cmd("SET", "lock", "A", "NX", "PX", "200"));
                Thread.sleep(350);
                System.out.println("    (A's 200 ms elapse, so the lock is free again)");
                System.out.println("    worker B takes it for 30 s    -> " + b.cmd("SET", "lock", "B", "NX", "PX", "30000"));
                System.out.println("    the live server says the lock is held by: " + b.cmd("GET", "lock"));
                Thread.sleep(120);
            }
            s.stop();
            System.out.println("\n  now the machine restarts and replays its log:");
            MiniRedis rec = new MiniRedis(port++);
            rec.aofPath = aof.toString(); rec.loadAof();
            Store.Value v = rec.store.dict.get("lock");
            System.out.println("    the recovered server says the lock is held by: " + (v == null ? "nobody" : v.str()));
            System.out.println("""

              A and B disagree, and neither machine can tell.

              Replaying `SET lock A NX PX 200` re-set A's lock with a FRESH 200 ms --
              the deadline was relative, so replaying it later moved it later. Then
              `SET lock B NX PX 30000` was re-evaluated against that revived lock and
              the NX condition refused it, reversing a decision the live server had
              already made and already told B about.

              Both mistakes have the same root: the log recorded what B ASKED FOR
              instead of what actually HAPPENED. Redis rewrites this to
              `SET lock B PXAT <absolute>` -- no condition left to re-decide, and a
              deadline that means the same instant whenever it is replayed.""".indent(0));
        } finally { Commands.BUG_NO_SET_REWRITE = false; Files.deleteIfExists(aof); }
    }
}
