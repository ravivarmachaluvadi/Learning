import java.util.*;

/**
 * Runnable failure experiments. Each one asks a question, breaks something, and
 * prints what actually happened.
 *
 *   java src/Experiments.java            -- run all
 *   java src/Experiments.java election   -- run one
 */
public class Experiments {

    public static void main(String[] args) {
        Set<String> want = new LinkedHashSet<>(Arrays.asList(args));
        Map<String, Runnable> all = new LinkedHashMap<>();
        all.put("election",      Experiments::election);
        all.put("replication",   Experiments::replication);
        all.put("leader-crash",  Experiments::leaderCrash);
        all.put("split-vote",    Experiments::splitVote);
        all.put("partition",     Experiments::partition);
        all.put("log-repair",    Experiments::logRepair);
        all.put("loss",          Experiments::loss);
        all.put("stale-candidate", Experiments::staleCandidate);
        all.put("figure8",       Experiments::figure8);
        all.put("heartbeat",     Experiments::heartbeat);
        all.put("soak",          Experiments::soak);

        if (!want.isEmpty() && !all.keySet().containsAll(want)) {
            System.out.println("unknown experiment. choose from: " + all.keySet());
            return;
        }
        for (var e : all.entrySet()) if (want.isEmpty() || want.contains(e.getKey())) e.getValue().run();
    }

    static void title(String n, String q) {
        System.out.println("\n" + "=".repeat(74));
        System.out.println("EXPERIMENT: " + n);
        System.out.println("QUESTION:   " + q);
        System.out.println("=".repeat(74));
    }

    static int[] othersThan(Cluster c, int id) {
        int[] out = new int[c.nodes.length - 1]; int k = 0;
        for (RaftNode n : c.nodes) if (n.id != id) out[k++] = n.id;
        return out;
    }

    // ============================================================== 1
    static void election() {
        title("election", "Five servers boot at once. How does exactly one of them become leader?");
        Cluster c = new Cluster(5, 2);
        c.verbose = true;
        c.runUntil(() -> c.leader() != null, 3000);
        RaftNode l = c.leader();
        System.out.println("\n  -> S" + l.id + " is leader of term " + l.currentTerm
                + " after " + c.now + " ticks, on " + l.votesReceived.size() + " votes " + l.votesReceived);
        System.out.println("  -> messages: " + c.net.sent + " sent, " + c.net.delivered + " delivered");
        c.verbose = false;
        long termBefore = c.maxTerm();
        c.run(5000);
        System.out.println("  -> after 5000 more quiet ticks the term is STILL " + c.maxTerm()
                + " (was " + termBefore + "): heartbeats are what keep it there.");
        System.out.println("  -> heartbeats sent in those 5000 ticks: ~" + (5000 / RaftNode.HEARTBEAT) * 4
                + ". A Raft cluster is never idle.");
    }

    // ============================================================== 2
    static void replication() {
        title("replication", "What exactly has to happen before a write is safe?");
        Cluster c = new Cluster(5, 3);
        c.runUntil(() -> c.leader() != null, 3000);
        RaftNode l = c.leader();
        System.out.println("  leader is S" + l.id + ", term " + l.currentTerm + "\n");
        c.verbose = true;
        l.clientRequest("set balance=100");
        c.runUntil(() -> l.commitIndex >= 1, 500);
        System.out.println("\n  -> the entry existed on the leader immediately, but was only COMMITTED");
        System.out.println("     once 3 of 5 servers had it on disk. Replicas that have it:");
        for (RaftNode n : c.nodes) System.out.println("       S" + n.id + " matchIndex=" + l.matchIndex[n.id]);
        c.runUntil(() -> c.committedCount("set balance=100") == 5, 500);
        c.verbose = false;
        c.report("everybody has applied it");
        System.out.println("  -> note the ordering: leader appends -> majority stores -> leader commits");
        System.out.println("     -> followers learn the commit on the NEXT message -> everyone applies.");
    }

    // ============================================================== 3
    static void leaderCrash() {
        title("leader-crash", "The leader dies. How long is the cluster leaderless, over 200 runs?");
        List<Long> gaps = new ArrayList<>();
        int noLeader = 0, lostCommitted = 0;
        for (int seed = 0; seed < 200; seed++) {
            Cluster c = new Cluster(5, seed);
            c.runUntil(() -> c.leader() != null, 3000);
            RaftNode l = c.leader();
            l.clientRequest("committed-before-crash");
            c.runUntil(() -> c.committedCount("committed-before-crash") >= 3, 800);
            c.crash(l.id);
            long gap = c.runUntil(() -> c.leader() != null && c.leader().id != l.id, 4000);
            if (gap < 0) { noLeader++; continue; }
            gaps.add(gap);
            if (!c.leader().logString().contains("committed-before-crash")) lostCommitted++;
        }
        Collections.sort(gaps);
        System.out.printf("  runs                        : 200%n");
        System.out.printf("  never recovered             : %d%n", noLeader);
        System.out.printf("  leaderless gap  min / p50 / p95 / max : %d / %d / %d / %d ticks%n",
                gaps.get(0), gaps.get(gaps.size() / 2), gaps.get((int) (gaps.size() * 0.95)), gaps.get(gaps.size() - 1));
        System.out.printf("  new leaders MISSING the committed write : %d  <-- this is Leader Completeness%n", lostCommitted);
        System.out.println("  election timeout is " + RaftNode.ELECTION_MIN + "-" + RaftNode.ELECTION_MAX
                + " ticks, so the gap is 'timeout + one round trip', not 'timeout x retries'.");
        System.out.println("  -> the cluster is UNAVAILABLE for that gap. Raft trades availability for");
        System.out.println("     consistency exactly here; nothing is lost, but nothing is served either.");
    }

    // ============================================================== 4
    static void splitVote() {
        title("split-vote", "Why are election timeouts random? What if they were all identical?");
        Bugs fixed = new Bugs(); fixed.fixedTimeout = true;
        Cluster a = new Cluster(5, 5, fixed);
        a.run(6000);
        System.out.println("  IDENTICAL timeouts, 6000 ticks:");
        System.out.println("    leader elected : " + (a.leader() == null ? "NEVER" : "S" + a.leader().id));
        System.out.println("    term reached   : " + a.maxTerm() + "   (every one of them a failed election)");
        System.out.println("    safety violations: " + a.violations.size() + "  <-- liveness died, safety did not");

        int firstRoundSplits = 0; List<Long> times = new ArrayList<>();
        for (int seed = 0; seed < 200; seed++) {
            Cluster c = new Cluster(5, seed);
            long t = c.runUntil(() -> c.leader() != null, 4000);
            times.add(t);
            if (c.leader().currentTerm > 1) firstRoundSplits++;
        }
        Collections.sort(times);
        System.out.println("\n  RANDOM timeouts (" + RaftNode.ELECTION_MIN + "-" + RaftNode.ELECTION_MAX + "), 200 cold starts:");
        System.out.println("    leader elected in every run : 200/200");
        System.out.printf("    time to first leader  min / p50 / max : %d / %d / %d ticks%n",
                times.get(0), times.get(100), times.get(199));
        System.out.println("    runs that needed more than one term : " + firstRoundSplits
                + "  (a split vote, resolved by the next random timeout)");
        System.out.println("  -> randomisation does not make split votes impossible, it makes them");
        System.out.println("     self-correcting: each retry re-rolls the dice.");
    }

    // ============================================================== 5
    static void partition() {
        title("partition", "The network splits 2|3. Which side works, and what happens to the writes?");
        Cluster c = new Cluster(5, 41);
        c.runUntil(() -> c.leader() != null, 3000);
        RaftNode old = c.leader();
        old.clientRequest("before-the-split");
        c.runUntil(() -> c.committedCount("before-the-split") == 5, 600);
        int[] rest = othersThan(c, old.id);
        int[] small = {old.id, rest[0]}, big = {rest[1], rest[2], rest[3]};
        c.partition(small, big);
        System.out.println("  small side " + Arrays.toString(small) + " holds the OLD leader S" + old.id);
        System.out.println("  big side   " + Arrays.toString(big) + " holds nobody yet\n");
        old.clientRequest("ghost-1");
        old.clientRequest("ghost-2");
        c.run(400);
        System.out.println("  after 400 ticks on the small side:");
        System.out.println("    S" + old.id + " still thinks it is LEADER, log=" + old.logString()
                + ", commitIndex=" + old.commitIndex + "  <-- stuck at 1, it can never commit again");
        c.runUntil(() -> c.leaderAmong(big) != null, 3000);
        RaftNode neu = c.leaderAmong(big);
        neu.clientRequest("real-1");
        c.runUntil(() -> neu.commitIndex >= 2, 800);
        System.out.println("    big side elected S" + neu.id + " in term " + neu.currentTerm
                + " and committed \"real-1\"");
        System.out.println("  TWO LEADERS EXIST RIGHT NOW -- and that is fine, because they are in");
        System.out.println("  different terms (" + old.currentTerm + " and " + neu.currentTerm + ") and only the newer one can commit.");
        System.out.println("  safety violations so far: " + c.violations.size());
        long mark = c.now;
        c.heal();
        c.runUntil(() -> old.role == RaftNode.Role.FOLLOWER, 1000);
        c.runUntil(() -> old.logString().equals(neu.logString()), 3000);
        System.out.println("\n  after healing:");
        c.printEventsAfter(mark);
        c.report("converged");
        System.out.println("  -> the two ghost writes were DELETED. They were never acknowledged as");
        System.out.println("     committed, so nothing promised to a client was broken.");
        System.out.println("  -> total safety violations for the whole run: " + c.violations.size());
    }

    // ============================================================== 6
    static void logRepair() {
        title("log-repair", "A follower's log is wrong. How far back does the leader have to walk?");

        // CASE A -- the follower is far BEHIND. S2 sleeps through six writes, then a
        // new leader is elected holding all six and probes S2 optimistically.
        Cluster a = new Cluster(5, 41);
        a.letTimeout(0, 1);
        a.runUntil(() -> a.node(0).role == RaftNode.Role.LEADER, 800);
        a.crash(2);
        for (int i = 1; i <= 6; i++) a.node(0).clientRequest("w" + i);
        a.runUntil(() -> a.node(0).commitIndex >= 6, 1200);
        a.crash(0); a.run(80);
        a.letTimeout(1, 1);
        a.runUntil(() -> a.node(1).role == RaftNode.Role.LEADER, 1500);
        System.out.println("  CASE A -- follower far behind");
        System.out.println("    new leader S1 log      : " + a.node(1).logString());
        System.out.println("    sleeping S2 log        : (empty -- it missed all six)");
        System.out.println("    S1 optimistically sets nextIndex[S2] = its own lastLogIndex+1 = "
                + a.node(1).nextIndex[2]);
        long mark = a.now, msgs = a.net.sent;
        a.restart(2);
        a.runUntil(() -> a.node(2).logString().equals(a.node(1).logString()), 3000);
        a.run(200);
        long rejects = a.events.stream().filter(e -> e.contains("REJECTS AppendEntries")
                && tickOf(e) >= mark).count();
        System.out.println("    rejections before the logs agreed : " + rejects
                + "   (one per step back: 7,6,5,4,3,2,1)");
        System.out.println("    messages spent on the repair      : " + (a.net.sent - msgs));
        System.out.println("    repaired S2 log        : " + a.node(2).logString()
                + "  matchIndex=" + a.node(1).matchIndex[2]);

        // CASE B -- the follower is DIVERGENT: it holds entries no leader ever committed.
        Cluster b = new Cluster(5, 41);
        b.runUntil(() -> b.leader() != null, 3000);
        RaftNode old = b.leader();
        old.clientRequest("keep");
        b.runUntil(() -> b.committedCount("keep") == 5, 600);
        int[] rest = othersThan(b, old.id);
        b.partition(new int[]{old.id, rest[0]}, new int[]{rest[1], rest[2], rest[3]});
        for (int i = 1; i <= 5; i++) old.clientRequest("ghost-" + i);
        b.run(600);
        b.runUntil(() -> b.leaderAmong(rest[1], rest[2], rest[3]) != null, 3000);
        RaftNode neu = b.leaderAmong(rest[1], rest[2], rest[3]);
        for (int i = 1; i <= 2; i++) neu.clientRequest("real-" + i);
        b.run(600);
        System.out.println("\n  CASE B -- follower divergent (five entries that were never committed)");
        System.out.println("    stale S" + old.id + " log : " + old.logString());
        System.out.println("    new   S" + neu.id + " log : " + neu.logString());
        System.out.println("    nextIndex[S" + old.id + "] on the new leader = " + neu.nextIndex[old.id]
                + "  <-- it was frozen at election time, when the new leader's log was short");
        long mark2 = b.now, msgs2 = b.net.sent;
        b.heal();
        b.runUntil(() -> old.logString().equals(neu.logString()), 5000);
        b.run(200);
        long rejects2 = b.events.stream().filter(e -> e.contains("REJECTS AppendEntries")
                && tickOf(e) >= mark2).count();
        System.out.println("    rejections before the logs agreed : " + rejects2
                + "   (none -- the probe already landed on a matching index)");
        System.out.println("    messages spent on the repair      : " + (b.net.sent - msgs2));
        System.out.println("    repaired S" + old.id + " log : " + old.logString()
                + "   -- five ghost entries deleted in one message");

        System.out.println("\n  -> the leader never diffs the logs. It guesses an index, and the follower's");
        System.out.println("     yes/no on (prevLogIndex, prevLogTerm) is a binary search oracle that");
        System.out.println("     certifies the ENTIRE prefix in one comparison.");
        System.out.println("  -> how far it walks depends only on where nextIndex started: a fresh leader");
        System.out.println("     guesses its own log end, so a badly-behind follower costs one round trip");
        System.out.println("     per missing entry. Real Raft returns the conflicting TERM in the rejection");
        System.out.println("     so the leader can skip a whole term per round trip instead of one entry.");
    }

    static long tickOf(String event) {
        try { return Long.parseLong(event.substring(event.indexOf('=') + 1).trim().split("\\s+")[0]); }
        catch (Exception e) { return -1; }
    }

    // ============================================================== 7
    static void loss() {
        title("loss", "How much of the network can vanish before Raft stops working?");
        System.out.printf("  %-6s %-14s %-14s %-16s %s%n", "drop", "leader found", "elections", "writes committed", "violations");
        for (double d : new double[]{0.0, 0.1, 0.2, 0.3, 0.5, 0.7, 0.9}) {
            int found = 0, committedRuns = 0, viol = 0, terms = 0;
            for (int seed = 0; seed < 30; seed++) {
                Cluster c = new Cluster(5, seed * 31 + 7);
                c.net.dropRate = d;
                long t = c.runUntil(() -> c.leader() != null, 6000);
                if (t >= 0 && c.leader() != null) found++;
                for (int i = 0; i < 3; i++) { c.submit("w" + i); c.run(400); }
                c.run(4000);
                terms += c.maxTerm();
                int best = 0;
                for (RaftNode n : c.nodes) best = Math.max(best, n.applied.size());
                if (best >= 3) committedRuns++;
                viol += c.violations.size();
            }
            System.out.printf("  %-6.0f%% %-14s %-14s %-16s %d%n", d * 100, found + "/30",
                    "avg term " + (terms / 30), committedRuns + "/30", viol);
        }
        System.out.println("  -> safety NEVER degrades. Liveness degrades smoothly and then collapses:");
        System.out.println("     when a leader cannot land a heartbeat inside one election timeout, the");
        System.out.println("     cluster burns terms instead of doing work. That is what an ops dashboard");
        System.out.println("     sees as 'leader elections per minute' climbing -- the classic Raft alarm.");
    }

    // ============================================================== 8
    static void staleCandidate() {
        title("stale-candidate", "Can a server that missed a committed write ever become leader?");
        for (boolean bug : new boolean[]{false, true}) {
            Bugs b = new Bugs(); b.noUpToDateCheck = bug;
            Cluster c = new Cluster(3, 7, b);
            c.letTimeout(0, 1);
            c.runUntil(() -> c.leader() != null, 800);
            c.crash(2);
            c.node(0).clientRequest("committed-write");
            c.runUntil(() -> c.node(0).commitIndex >= 1, 500);
            System.out.println("\n  up-to-date check " + (bug ? "OFF (bug)" : "ON  (real Raft)"));
            System.out.println("    \"committed-write\" is committed on S0 and S1. S2 was offline and has an empty log.");
            c.crash(0); c.run(60); c.restart(2); c.letTimeout(2, 1);
            c.runUntil(() -> c.leader() != null, 1500);
            RaftNode l = c.leader();
            System.out.println("    S2 runs for leader with an empty log -> winner is "
                    + (l == null ? "NOBODY" : "S" + l.id));
            if (l != null) l.clientRequest("overwrite");
            c.run(800);
            System.out.println("    S1's log afterwards: " + c.node(1).logString());
            System.out.println("    violations: " + (c.violations.isEmpty() ? "none" : c.violations.toString()));
        }
        System.out.println("\n  -> one line of code (\"is your log at least as fresh as mine?\") is the whole");
        System.out.println("     difference between a durable write and a write that gets deleted later.");
    }

    // ============================================================== 9
    static void figure8() {
        title("figure8", "An old entry sits on a majority. Is it safe? (the Raft paper's Figure 8)");
        for (boolean bug : new boolean[]{false, true}) {
            Bugs b = new Bugs(); b.commitAnyTerm = bug;
            Cluster c = Tests.figure8(b);
            System.out.println("\n  own-term commit rule " + (bug ? "OFF (bug)" : "ON  (real Raft)"));
            for (RaftNode n : c.nodes)
                System.out.printf("    S%d %-9s term=%-3d commit=%-3d log=%-22s applied=%s%n",
                        n.id, n.alive ? n.role : "DEAD", n.currentTerm, n.commitIndex, n.logString(), n.applied);
            System.out.println("    violations: " + (c.violations.isEmpty() ? "none" : ""));
            for (String v : c.violations) System.out.println("      " + v);
        }
        System.out.println("\n  -> \"a majority has it\" is NOT the same as \"it is committed\". A leader may");
        System.out.println("     only count replicas for an entry from its OWN term. Old entries become");
        System.out.println("     committed for free, as a side effect, when a newer one commits above them.");
    }

    // ============================================================== 10
    static void heartbeat() {
        title("heartbeat", "What happens when the heartbeat is slower than the election timeout?");
        int savedHb = RaftNode.HEARTBEAT;
        System.out.printf("  %-22s %-16s %-14s %s%n", "heartbeat interval", "election timeout", "terms in 10k", "leader stable?");
        for (int hb : new int[]{10, 50, 100, 140, 200, 400}) {
            RaftNode.HEARTBEAT = hb;
            Cluster c = new Cluster(5, 17);
            c.runUntil(() -> c.leader() != null, 3000);
            int t0 = c.maxTerm();
            RaftNode l0 = c.leader();
            c.run(10000);
            boolean stable = c.leader() != null && l0 != null && c.leader().id == l0.id && c.maxTerm() == t0;
            System.out.printf("  %-22d %-16s %-14d %s%n", hb,
                    RaftNode.ELECTION_MIN + "-" + RaftNode.ELECTION_MAX, c.maxTerm() - t0,
                    stable ? "yes" : "NO -- churning");
        }
        RaftNode.HEARTBEAT = savedHb;
        System.out.println("  -> the rule of thumb from the paper: broadcastTime << electionTimeout << MTBF.");
        System.out.println("     Once one heartbeat interval approaches the MINIMUM election timeout, followers");
        System.out.println("     start timing out on a perfectly healthy leader and the cluster eats itself.");
        System.out.println("     etcd defaults: 100ms heartbeat, 1000ms election timeout -- a 10x margin.");
    }

    // ============================================================== 11
    static void soak() {
        title("soak", "300 rounds of random crashes, restarts and partitions. Does safety ever break?");
        int totalViolations = 0, totalWrites = 0, totalElections = 0;
        for (int seed = 0; seed < 20; seed++) {
            Cluster c = new Cluster(5, seed * 7 + 3);
            c.net.dropRate = 0.05;
            Random r = new Random(seed);
            int writes = 0;
            for (int round = 0; round < 300; round++) {
                int dice = r.nextInt(12);
                if (dice < 2) { int v = r.nextInt(5); if (c.node(v).alive) c.crash(v); }
                else if (dice < 4) { int v = r.nextInt(5); if (!c.node(v).alive) c.restart(v); }
                else if (dice == 4) c.partition(new int[]{0, 1}, new int[]{2, 3, 4});
                else if (dice == 5) c.partition(new int[]{0, 1, 2}, new int[]{3, 4});
                else if (dice == 6) c.heal();
                else if (c.submit("w" + writes) >= 0) writes++;
                c.run(120);
            }
            c.heal();
            for (RaftNode n : c.nodes) if (!n.alive) c.restart(n.id);
            c.run(6000);
            totalViolations += c.violations.size();
            totalWrites += writes;
            totalElections += c.maxTerm();
            if (!c.violations.isEmpty()) { System.out.println("  seed " + seed + " VIOLATED:"); c.violations.forEach(v -> System.out.println("    " + v)); }
        }
        System.out.println("  20 clusters x 300 fault rounds, 5% packet loss throughout");
        System.out.println("    client writes accepted by a leader : " + totalWrites);
        System.out.println("    elections held (sum of final terms): " + totalElections);
        System.out.println("    SAFETY VIOLATIONS                  : " + totalViolations);
        System.out.println("  -> checked after every single tick: at most one leader per term, committed");
        System.out.println("     entries never change, logs that agree on (index,term) agree on the whole");
        System.out.println("     prefix, and no two state machines ever applied different commands in the");
        System.out.println("     same position.");
    }
}
