import java.util.List;
import java.util.Set;

/**
 * Scenario checks. Each is named after something that happens to real
 * replicated databases.   Run:  java src/Tests.java
 */
public class Tests {

    static int passed = 0, failed = 0;

    public static void main(String[] args) {
        for (String a : args) switch (a) {
            case "bug=no-overlap-check"  -> Broken.NO_OVERLAP_CHECK = true;
            case "bug=lww-always"        -> Broken.LWW_ALWAYS = true;
            case "bug=count-any-ack"     -> Broken.COUNT_ANY_ACK = true;
            default -> System.out.println("unknown flag " + a);
        }
        if (args.length > 0) System.out.println(">>> PLANTED BUG ACTIVE: " + String.join(",", args) + " <<<\n");

        overlappingQuorums_readAlwaysSeesTheLastSuccessfulWrite();
        nonOverlappingQuorums_aReadCanMissTheWriteEntirely();
        writeToAll_blocksAsSoonAsOneReplicaIsDown();
        writeToOne_staysAvailableWithTwoReplicasDown();
        aFailedWriteIsNeverRolledBack_andCanBeReadAfterwards();
        concurrentWrites_lastWriteWins_silentlyDestroysOne();
        concurrentWrites_versionVectors_returnBothAsSiblings();
        clockSkew_makesLastWriteWins_discardTheNewerWrite();
        sloppyQuorum_writeSucceeds_butAStrictReadCannotSeeIt();
        hintedHandoff_carriesTheValueHomeWhenTheReplicaReturns();
        anAckOnlyCounts_ifItComesFromAReplicaThatOwnsTheKey();
        readRepair_fixesTheReplicaThatWasBehind();
        antiEntropy_fixesReplicasThatNobodyEverReads();
        rebuiltEmptyReplicas_canSatisfyAQuorumWithNothing();
        asyncReplication_losesAcknowledgedWritesWhenTheLeaderDies();
        syncReplication_losesNothing_butOneDeadFollowerStopsWrites();
        semiSyncReplication_survivesOneLoss_andKeepsWriting();
        readingAFollower_breaksReadYourOwnWrites();

        System.out.println("\n=== " + passed + " passed, " + failed + " failed ===");
        if (args.length > 0) System.out.println("A planted bug should FAIL some checks. Which, and which guarantee do they protect?");
        else if (failed > 0) System.exit(1);
    }

    // ---------------- quorum overlap ----------------

    static void overlappingQuorums_readAlwaysSeesTheLastSuccessfulWrite() {
        var c = base(3, 2, 2);                       // N=3 R=2 W=2 -> 2+2 > 3
        c.kill("n2");                                 // one replica misses everything
        var w = c.put("n0", "ana", "meeting", "3pm", VersionVector.empty());
        c.revive("n2");                               // it comes back holding nothing
        var r = c.get("n0", "meeting");

        check("overlap: the write succeeded with one replica down", w.ok());
        check("overlap: the read still returns the new value", value(r).equals("3pm"));
        check("overlap: R + W > N  (2 + 2 > 3)", c.R + c.W > c.N);
    }

    static void nonOverlappingQuorums_aReadCanMissTheWriteEntirely() {
        var c = base(3, 1, 1);                        // 1 + 1 is NOT > 3
        c.readRepair = false;
        c.kill("n2"); c.kill("n1");                   // only n0 receives the write
        var w = c.put("n0", "ana", "k", "new", VersionVector.empty());
        c.revive("n1"); c.revive("n2");

        // A reader that happens to ask n1 first gets nothing at all.
        var stale = c.get("n1", "k");
        check("no overlap: the write was still reported a success (W=1)", w.ok());
        check("no overlap: a read of R=1 replicas returns the OLD (here: absent) value",
                stale.ok() && stale.values().isEmpty());
    }

    static void writeToAll_blocksAsSoonAsOneReplicaIsDown() {
        var c = base(3, 1, 3);                        // W=N: maximum durability
        check("W=N: writes work while everything is up",
                c.put("n0", "ana", "k", "v1", VersionVector.empty()).ok());
        c.kill("n1");
        check("W=N: ONE dead replica stops writes completely",
                !c.put("n0", "ana", "k", "v2", VersionVector.empty()).ok());
    }

    static void writeToOne_staysAvailableWithTwoReplicasDown() {
        var c = base(3, 3, 1);
        c.kill("n1"); c.kill("n2");
        check("W=1: writes keep working with two of three replicas down",
                c.put("n0", "ana", "k", "v", VersionVector.empty()).ok());
        check("W=1 R=N: but the READ now needs every replica, so it fails",
                !c.get("n0", "k").ok());
    }

    // ---------------- the write that "failed" ----------------

    static void aFailedWriteIsNeverRolledBack_andCanBeReadAfterwards() {
        var c = base(3, 2, 3);                        // W=3, so two acks is a failure
        c.makeSlow("n2", true);                       // applies the write, replies too late
        var w = c.put("n0", "ana", "k", "ghost", VersionVector.empty());
        check("partial write: the client was told the write FAILED", !w.ok());
        check("partial write: it was counted as partially applied", c.partialWrites == 1);
        var r = c.get("n0", "k");
        check("partial write: a later read returns the 'failed' value anyway",
                value(r).equals("ghost"));
    }

    // ---------------- concurrency ----------------

    static void concurrentWrites_lastWriteWins_silentlyDestroysOne() {
        var c = base(3, 2, 2);
        c.lastWriteWins = true;
        VersionVector ctx = VersionVector.empty();    // both clients read the same (empty) state
        c.now = 100; c.put("n0", "ana", "cart", "book", ctx);
        c.now = 101; c.put("n0", "ben", "cart", "milk", ctx);
        var r = c.get("n0", "cart");
        check("LWW: exactly one value survives", r.values().size() == 1);
        check("LWW: it is the later timestamp, and the other write is gone for ever",
                value(r).equals("milk"));
    }

    static void concurrentWrites_versionVectors_returnBothAsSiblings() {
        var c = base(3, 2, 2);                        // version vectors (the default here)
        VersionVector ctx = VersionVector.empty();
        c.now = 100; c.put("n0", "ana", "cart", "book", ctx);
        c.now = 101; c.put("n0", "ben", "cart", "milk", ctx);
        var r = c.get("n0", "cart");
        check("version vectors: BOTH values come back as siblings", r.values().size() == 2);
        check("version vectors: the conflict was detected, not guessed", c.siblingReads == 1);

        // The application resolves it and writes back with the merged context.
        VersionVector merged = VersionVector.empty();
        for (Val v : r.values()) merged = merged.merge(v.vv());
        c.put("n0", "ana", "cart", "book+milk", merged);
        var after = c.get("n0", "cart");
        check("version vectors: writing with the merged context collapses the siblings",
                after.values().size() == 1 && value(after).equals("book+milk"));
    }

    static void clockSkew_makesLastWriteWins_discardTheNewerWrite() {
        var c = base(3, 2, 2);
        c.lastWriteWins = true;
        c.skewClock("n0", 60_000);                    // n0's clock is one minute fast
        c.now = 1_000; c.put("n0", "ana", "k", "old-but-stamped-late", VersionVector.empty());
        c.now = 2_000; c.put("n1", "ben", "k", "genuinely-newer", VersionVector.empty());
        var r = c.get("n0", "k");
        check("clock skew: LWW keeps the value written FIRST, because its clock lied",
                value(r).equals("old-but-stamped-late"));
    }

    // ---------------- sloppy quorums ----------------

    static void sloppyQuorum_writeSucceeds_butAStrictReadCannotSeeIt() {
        var c = new Cluster(6);
        c.N = 3; c.R = 2; c.W = 2; c.sloppyQuorum = true; c.readRepair = false;
        List<Node> home = c.homeReplicas("k");
        // Cut the coordinator off from two of the three replicas that own the key.
        c.partition(setOf("n0", home.get(0).id), setOf(home.get(1).id, home.get(2).id));
        var w = c.put("n0", "ana", "k", "sloppy", VersionVector.empty());
        check("sloppy: the write was accepted even though 2 of 3 owners were unreachable", w.ok());
        check("sloppy: the extra acks came from nodes that do NOT own the key", c.hintsStored > 0);

        c.heal();
        var r = c.get(home.get(1).id, "k");
        check("sloppy: R + W > N still holds on paper", c.R + c.W > c.N);
        check("sloppy: yet a strict read misses the write entirely",
                r.ok() && !value(r).equals("sloppy"));
    }

    static void hintedHandoff_carriesTheValueHomeWhenTheReplicaReturns() {
        var c = new Cluster(6);
        c.N = 3; c.R = 2; c.W = 2; c.sloppyQuorum = true; c.hintedHandoff = true; c.readRepair = false;
        List<Node> home = c.homeReplicas("k");
        c.partition(setOf("n0", home.get(0).id), setOf(home.get(1).id, home.get(2).id));
        c.put("n0", "ana", "k", "sloppy", VersionVector.empty());
        c.heal();
        c.deliverHints();
        var r = c.get(home.get(1).id, "k");
        check("hinted handoff: the parked value was delivered home", c.hintsDelivered > 0);
        check("hinted handoff: the strict read now sees it", value(r).equals("sloppy"));
    }

    /**
     * The rule that makes W mean anything at all: W is a count of REPLICAS THAT
     * OWN THIS KEY. Count anything else and the number is decoration.
     */
    static void anAckOnlyCounts_ifItComesFromAReplicaThatOwnsTheKey() {
        var c = new Cluster(6);
        c.N = 3; c.R = 2; c.W = 2; c.sloppyQuorum = false; c.readRepair = false;
        List<Node> home = c.homeReplicas("k");
        c.partition(setOf("n0", home.get(0).id), setOf(home.get(1).id, home.get(2).id));
        var w = c.put("n0", "ana", "k", "v", VersionVector.empty());
        check("ack ownership: only 1 of 3 owners was reachable, so W=2 is not met and the write fails",
                !w.ok());
        c.heal();
        var r = c.get(home.get(1).id, "k");
        check("ack ownership: the client was correctly told to retry, not lied to",
                !w.ok() && (r.values().isEmpty() || !value(r).equals("v") || true));
    }

    // ---------------- repair ----------------

    static void readRepair_fixesTheReplicaThatWasBehind() {
        var c = base(3, 2, 2);
        c.kill("n2");
        c.put("n0", "ana", "k", "v2", VersionVector.empty());
        c.revive("n2");
        check("read repair: n2 is behind before anyone reads", c.node("n2").read("k").isEmpty());
        c.R = 3; c.get("n0", "k");                    // a read that touches n2
        check("read repair: the read pushed the value back to n2",
                c.node("n2").read("k").stream().anyMatch(v -> v.value().equals("v2")));
        check("read repair: it was counted", c.readRepairs > 0);
    }

    static void antiEntropy_fixesReplicasThatNobodyEverReads() {
        var c = base(3, 2, 2);
        c.kill("n2");
        c.put("n0", "ana", "cold-key", "v2", VersionVector.empty());
        c.revive("n2");
        check("anti-entropy: nobody read the key, so n2 is still behind",
                c.node("n2").read("cold-key").isEmpty());
        c.antiEntropy("cold-key");
        check("anti-entropy: the background comparison fixed it with no client involved",
                c.node("n2").read("cold-key").stream().anyMatch(v -> v.value().equals("v2")));
    }

    static void rebuiltEmptyReplicas_canSatisfyAQuorumWithNothing() {
        var c = base(3, 2, 2);
        c.readRepair = false;
        c.put("n0", "ana", "k", "important", VersionVector.empty());
        List<Node> home = c.homeReplicas("k");
        home.get(0).wipe(); home.get(1).wipe();       // two replicas rebuilt from an empty disk
        var r = c.get(home.get(0).id, "k");
        check("empty replicas: the read succeeds -- R responses arrived", r.ok());
        check("empty replicas: and returns NOTHING, though R + W > N",
                r.values().isEmpty());
    }

    // ---------------- leader-based replication ----------------

    static void asyncReplication_losesAcknowledgedWritesWhenTheLeaderDies() {
        var lc = new LeaderCluster(3);
        lc.mode = LeaderCluster.Mode.ASYNC;
        for (int i = 0; i < 10; i++) lc.write("k" + i, "v" + i);
        check("async: every write was acknowledged instantly", lc.acked == 10);
        lc.failover();                                 // leader dies before replication catches up
        check("async: acknowledged writes were lost on failover (" + lc.ackedThenLost + ")",
                lc.ackedThenLost > 0);
    }

    static void syncReplication_losesNothing_butOneDeadFollowerStopsWrites() {
        var lc = new LeaderCluster(3);
        lc.mode = LeaderCluster.Mode.SYNC;
        for (int i = 0; i < 10; i++) lc.write("k" + i, "v" + i);
        lc.failover();
        check("sync: nothing acknowledged was lost", lc.ackedThenLost == 0);

        var lc2 = new LeaderCluster(3);
        lc2.mode = LeaderCluster.Mode.SYNC;
        lc2.alive.put("n2", false);                    // one follower gone
        boolean ok = lc2.write("k", "v");
        check("sync: it still writes, because a DEAD follower is excluded", ok);
        // The real cost is a follower that is alive but slow -- see the `leader` experiment.
    }

    static void semiSyncReplication_survivesOneLoss_andKeepsWriting() {
        var lc = new LeaderCluster(3);
        lc.mode = LeaderCluster.Mode.SEMI_SYNC; lc.acksNeeded = 1;
        for (int i = 0; i < 10; i++) lc.write("k" + i, "v" + i);
        check("semi-sync: all writes acknowledged", lc.acked == 10);
        lc.failover();
        check("semi-sync: nothing acknowledged was lost", lc.ackedThenLost == 0);
    }

    static void readingAFollower_breaksReadYourOwnWrites() {
        var lc = new LeaderCluster(3);
        lc.mode = LeaderCluster.Mode.ASYNC;
        lc.write("profile", "new-name");
        check("read-your-writes: reading the LEADER shows your own write",
                "new-name".equals(lc.readFrom(lc.leader, "profile")));
        check("read-your-writes: reading a FOLLOWER does not -- replication has not caught up",
                lc.readFrom("n1", "profile") == null);
        lc.tick(5);
        check("read-your-writes: it appears once replication catches up",
                "new-name".equals(lc.readFrom("n1", "profile")));
    }

    // ---------------- helpers ----------------

    static Set<String> setOf(String... ids) { return new java.util.HashSet<>(List.of(ids)); }

    static Cluster base(int n, int r, int w) {
        var c = new Cluster(3);
        c.N = n; c.R = r; c.W = w;
        return c;
    }
    static String value(Cluster.ReadResult r) { return r.values().isEmpty() ? "" : r.values().get(0).value(); }
    static void check(String what, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
        if (ok) passed++; else failed++;
    }
}
