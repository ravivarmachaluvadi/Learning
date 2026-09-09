import java.util.*;

/**
 * Nine measurements. Every number the README quotes comes from here.
 *
 * Run all:  java src/Experiments.java
 * Run one:  java src/Experiments.java 4
 *
 * Gossip is random, so each result is averaged over several seeds and the spread
 * is reported where it matters. Re-runs move the last digit, never the lesson.
 */
public class Experiments {

    static void title(int n, String s) {
        System.out.println("\n" + "=".repeat(78));
        System.out.printf("  EXPERIMENT %d - %s%n", n, s);
        System.out.println("=".repeat(78));
    }

    public static void main(String[] args) {
        Set<Integer> only = new HashSet<>();
        for (String a : args) only.add(Integer.parseInt(a));
        java.util.function.IntPredicate run = n -> only.isEmpty() || only.contains(n);

        if (run.test(1)) costPerNode();
        if (run.test(2)) convergenceVsSize();
        if (run.test(3)) fanoutSweep();
        if (run.test(4)) pushPullPushpull();
        if (run.test(5)) rumourStopRule();
        if (run.test(6)) messageLoss();
        if (run.test(7)) detectionTradeoff();
        if (run.test(8)) brokenLink();
        if (run.test(9)) partitionWindow();
        if (run.test(10)) detectorMeltdown();
        System.out.println();
    }

    // ------------------------------------------------------------ helpers

    /** Rounds until every running node holds `version`. -1 if it never did. */
    static int roundsToConverge(int n, int fanout, Cluster.Mode mode, double drop, long seed, int cap) {
        Cluster c = new Cluster(n, seed, false);       // one fact travelling, nothing else
        c.fanout = fanout; c.mode = mode; c.dropRate = drop;
        c.suspectAfter = 10_000;                       // detection off: this is about spread
        c.publishConfig("n1", "v", 2);
        for (int r = 0; r < cap; r++) {
            c.runRound();
            if (c.nodesWith(2) == n) return r + 1;
        }
        return -1;
    }

    static double mean(List<? extends Number> xs) {
        double s = 0; for (Number x : xs) s += x.doubleValue(); return s / xs.size();
    }

    // ------------------------------------------------------------------ 1
    /** What each node pays, and what the cluster pays, to tell everyone one thing. */
    static void costPerNode() {
        title(1, "cost: telling everybody, versus telling two people");
        System.out.printf("%n  %6s | %-34s | %-34s%n", "nodes", "every node tells every node",
                "gossip, fanout 3, push-pull");
        System.out.printf("  %6s | %-34s | %-34s%n", "", "msgs/node/round   total/round",
                "msgs/node/round   rounds   total");
        System.out.println("  " + "-".repeat(78));
        for (int n : new int[]{10, 100, 500, 1000}) {
            List<Integer> rounds = new ArrayList<>();
            long total = 0;
            for (long seed = 1; seed <= 5; seed++) {
                Cluster c = new Cluster(n, seed);
                c.fanout = 3; c.mode = Cluster.Mode.PUSH_PULL; c.suspectAfter = 10_000;
                c.publishConfig("n1", "v", 2);
                int r = 0;
                while (c.nodesWith(2) < n && r < 100) { c.runRound(); r++; }
                rounds.add(r); total += c.messagesSent;
            }
            System.out.printf("  %6d | %11d %16d | %11d %8.1f %8.0f%n",
                    n, n - 1, (long) n * (n - 1), 3 * 2, mean(rounds), total / 5.0);
        }
        System.out.println("""
            
            Read the two "msgs/node/round" columns. On the left it grows with the
            cluster: at 1,000 nodes every node sends 999 messages every round, for
            ever, whether or not anything changed. On the right it is 6, at every
            size. That is the trade gossip makes: nobody ever has a complete
            picture at an instant, and nobody ever pays for one either.""");
    }

    // ------------------------------------------------------------------ 2
    /** How does the time to reach everyone grow with the cluster? */
    static void convergenceVsSize() {
        title(2, "convergence: rounds to reach every node as the cluster grows");
        System.out.printf("%n  %6s | %8s | %10s | %s%n", "nodes", "rounds", "log4(n)", "spread over 10 runs");
        System.out.println("  " + "-".repeat(62));
        for (int n : new int[]{10, 50, 100, 500, 1000, 5000}) {
            List<Integer> rs = new ArrayList<>();
            for (long seed = 1; seed <= 10; seed++)
                rs.add(roundsToConverge(n, 3, Cluster.Mode.PUSH_PULL, 0, seed, 200));
            int min = Collections.min(rs), max = Collections.max(rs);
            System.out.printf("  %6d | %8.1f | %10.1f | %d - %d%n",
                    n, mean(rs), Math.log(n) / Math.log(4), min, max);
        }
        System.out.println("""
            
            The cluster gets 200x bigger and the time roughly doubles. That is the
            signature of logarithmic growth, and it comes from one fact: each round
            multiplies the number of informed nodes instead of adding to it. The
            practical reading: if it takes 6 rounds today, growing 10x costs you
            about 3 more rounds -- not 10x more.""");
    }

    // ------------------------------------------------------------------ 3
    /** Does telling more people per round help? How much, and at what price? */
    static void fanoutSweep() {
        title(3, "fanout: how many peers should each node contact per round?");
        int n = 500;
        System.out.printf("%n  %7s | %8s | %14s | %s%n", "fanout", "rounds", "total messages", "messages per round");
        System.out.println("  " + "-".repeat(62));
        for (int f : new int[]{1, 2, 3, 4, 6, 10}) {
            List<Integer> rs = new ArrayList<>(); long msgs = 0;
            for (long seed = 1; seed <= 10; seed++) {
                Cluster c = new Cluster(n, seed, false);
                c.fanout = f; c.mode = Cluster.Mode.PUSH_PULL; c.suspectAfter = 10_000;
                c.publishConfig("n1", "v", 2);
                int r = 0;
                while (c.nodesWith(2) < n && r < 200) { c.runRound(); r++; }
                rs.add(r); msgs += c.messagesSent;
            }
            System.out.printf("  %7d | %8.1f | %14.0f | %d%n", f, mean(rs), msgs / 10.0, f * 2 * n);
        }
        System.out.println("""
            
            Going from 1 to 3 buys a lot of rounds. Going from 6 to 10 buys almost
            nothing and costs 67% more traffic every round, for ever. The reason is
            in experiment 2: rounds fall like log(n)/log(fanout+1), so each extra
            peer helps less than the one before -- while its cost is linear. Real
            systems sit at the knee: Cassandra gossips to ~3 peers per second.""");
    }

    // ------------------------------------------------------------------ 4
    /** Three ways to hold a conversation, and the tail nobody expects. */
    static void pushPullPushpull() {
        title(4, "push, pull, or both: who finishes the last mile?");
        int n = 500;
        System.out.printf("%n  %-10s | %10s | %12s | %s%n", "mode", "rounds", "to reach 90%", "messages");
        System.out.println("  " + "-".repeat(60));
        for (Cluster.Mode m : Cluster.Mode.values()) {
            List<Integer> full = new ArrayList<>(), ninety = new ArrayList<>(); long msgs = 0;
            for (long seed = 1; seed <= 10; seed++) {
                Cluster c = new Cluster(n, seed, false);
                c.fanout = 1; c.mode = m; c.suspectAfter = 10_000;
                c.publishConfig("n1", "v", 2);
                int r = 0, r90 = -1;
                while (c.nodesWith(2) < n && r < 200) {
                    c.runRound(); r++;
                    if (r90 < 0 && c.nodesWith(2) >= n * 0.9) r90 = r;
                }
                full.add(r); ninety.add(r90); msgs += c.messagesSent;
            }
            System.out.printf("  %-10s | %10.1f | %12.1f | %,.0f%n",
                    m, mean(full), mean(ninety), msgs / 10.0);
        }
        System.out.println("""
            
            Compare the two round columns for PUSH. Reaching 90% is quick; reaching
            the last few nodes takes almost as long again, because a push only lands
            usefully if it hits one of the shrinking set of nodes that has not heard
            yet -- and late in the spread, almost every push is wasted.
            
            PULL has the opposite shape: an uninformed node asks, and late in the
            spread almost everyone it could ask already knows. That is why PULL
            finishes the tail fast and starts slowly, and why doing both is the
            standard choice.""");
    }

    // ------------------------------------------------------------------ 5
    /** "Stop repeating old news" sounds obviously right. It loses nodes. */
    static void rumourStopRule() {
        title(5, "the stop rule: what happens to the nodes nobody told");
        int n = 1000, rounds = 40;
        System.out.printf("%n  %-38s | %14s | %s%n", "policy", "nodes reached",
                "messages that carried the news");
        System.out.println("  " + "-".repeat(78));
        for (int stop : new int[]{1, 2, 3, 5}) {
            double reached = 0, msgs = 0; int runs = 20;
            for (long seed = 1; seed <= runs; seed++) {
                Cluster c = new Cluster(n, seed, false);
                c.fanout = 1; c.mode = Cluster.Mode.PUSH; c.suspectAfter = 10_000;
                c.rumorMongering = true; c.stopAfterRedundant = stop;
                c.publishConfig("n1", "v", 2);
                for (int r = 0; r < rounds; r++) c.runRound();
                reached += c.nodesWith(2); msgs += c.configMessagesSent;
            }
            System.out.printf("  %-38s | %9.1f/%d | %,.0f%n",
                    "stop after hearing it back " + stop + "x", reached / runs, n, msgs / runs);
        }
        double reached = 0, msgs = 0;
        for (long seed = 1; seed <= 20; seed++) {
            Cluster c = new Cluster(n, seed, false);
            c.fanout = 1; c.mode = Cluster.Mode.PUSH; c.suspectAfter = 10_000;
            c.publishConfig("n1", "v", 2);
            for (int r = 0; r < rounds; r++) c.runRound();
            reached += c.nodesWith(2); msgs += c.configMessagesSent;
        }
        System.out.printf("  %-38s | %9.1f/%d | %,.0f%n", "never stop (anti-entropy)", reached / 20, n, msgs / 20);
        System.out.println("""
            
            Look at the two columns together. The stop rule is a real optimisation:
            it stops the cluster repeating a month-old fact for ever, and the saving
            grows with every round that passes. And on its own it is not safe --
            with an eager stop rule, a few nodes are never chosen before everyone
            around them gives up, and a stopped rumour cannot restart, because
            nobody knows it is missing.
            
            Production systems therefore run BOTH layers: fast rumour spreading for
            new facts, and a slower always-on exchange of full state underneath it
            that eventually repairs whatever the rumours missed.""");
    }

    // ------------------------------------------------------------------ 6
    /** No acknowledgements, no retries, no sequence numbers. Does it still work? */
    static void messageLoss() {
        title(6, "message loss: the protocol with no retries");
        int n = 500;
        System.out.printf("%n  %10s | %10s | %s%n", "loss", "rounds", "vs no loss");
        System.out.println("  " + "-".repeat(44));
        double base = 0;
        for (double drop : new double[]{0, 0.2, 0.4, 0.6, 0.8, 0.95}) {
            List<Integer> rs = new ArrayList<>();
            for (long seed = 1; seed <= 10; seed++)
                rs.add(roundsToConverge(n, 3, Cluster.Mode.PUSH_PULL, drop, seed, 400));
            double m = mean(rs);
            if (drop == 0) base = m;
            System.out.printf("  %9.0f%% | %10.1f | %.2fx%n", drop * 100, m, m / base);
        }
        System.out.println("""
            
            Losing 80% of every message costs about four times the rounds, and even
            at 95% loss it still finishes -- 18x slower, but it finishes. Nothing
            was retried, nothing was acknowledged, nothing detected the loss. The
            redundancy is not a safety net bolted on the side -- it IS the protocol,
            and that is why gossip is normally run over UDP and why it degrades
            smoothly on a bad network instead of collapsing.""");
    }

    // ------------------------------------------------------------------ 7
    /** The dial nobody gets to avoid: fast detection versus false alarms. */
    static void detectionTradeoff() {
        title(7, "failure detection: how long do you wait before believing the worst?");
        System.out.printf("%n  %12s | %-26s | %s%n", "suspectAfter",
                "rounds to spot a real crash", "false alarms on a 4-round pause");
        System.out.println("  " + "-".repeat(76));
        for (int s : new int[]{1, 2, 3, 5, 8}) {
            // (a) a genuine crash: how long until every survivor suspects it?
            List<Integer> detect = new ArrayList<>();
            for (long seed = 1; seed <= 10; seed++) {
                Cluster c = new Cluster(50, seed);
                c.fanout = 3; c.suspectAfter = s; c.deadAfter = 3;
                for (int r = 0; r < 5; r++) c.runRound();
                c.kill("n7");
                int r = 0;
                while (c.believing("n7", Status.ALIVE) > 0 && r < 100) { c.runRound(); r++; }
                detect.add(r);
            }
            // (b) a node that is merely slow for 4 rounds: who wrongly suspects it?
            List<Double> falses = new ArrayList<>();
            for (long seed = 1; seed <= 10; seed++) {
                Cluster c = new Cluster(50, seed);
                c.fanout = 3; c.suspectAfter = s; c.deadAfter = 100;
                for (int r = 0; r < 5; r++) c.runRound();
                c.kill("n7");                                   // stands in for a GC pause
                for (int r = 0; r < 4; r++) c.runRound();
                c.revive("n7");
                falses.add(100.0 * c.believing("n7", Status.SUSPECT) / c.runningCount());
            }
            System.out.printf("  %12d | %-26.1f | %.0f%% of nodes%n", s, mean(detect), mean(falses));
        }
        System.out.println("""
            
            There is no setting that is good at both columns, because no node can
            tell "crashed" from "busy" -- both look like silence. Every timeout you
            pick is a bet on how long a healthy node is allowed to go quiet.
            
            This is why production detectors do not use a fixed threshold at all:
            Cassandra's phi-accrual detector learns each peer's normal reply
            interval and outputs a suspicion LEVEL, so a node with a jittery link
            gets more rope than one that is usually instant.""");
    }

    // ------------------------------------------------------------------ 8
    /** One broken link, two detection philosophies, two different answers. */
    static void brokenLink() {
        title(8, "a broken link between two nodes: who gets blamed?");
        System.out.printf("%n  %-46s | %s%n", "detection model", "nodes that declare n5 dead");
        System.out.println("  " + "-".repeat(78));
        record Setup(String name, boolean direct, int probes) {}
        for (Setup s : List.of(
                new Setup("heartbeats via anyone (Cassandra-style)", false, 0),
                new Setup("only direct contact counts (SWIM-style)", true, 0),
                new Setup("direct contact + 3 indirect probes (SWIM)", true, 3))) {
            List<Double> wrong = new ArrayList<>();
            for (long seed = 1; seed <= 10; seed++) {
                Cluster c = new Cluster(20, seed);
                c.fanout = 3; c.suspectAfter = 3; c.deadAfter = 3;
                c.directEvidenceOnly = s.direct(); c.indirectProbes = s.probes();
                for (int r = 0; r < 5; r++) c.runRound();
                c.blockOneWay("n1", "n5");           // the cable between n1 and n5,
                c.blockOneWay("n5", "n1");           // and only that cable, is cut
                for (int r = 0; r < 25; r++) c.runRound();
                wrong.add((double) (c.believing("n5", Status.DEAD) + c.believing("n5", Status.SUSPECT)));
            }
            System.out.printf("  %-46s | %.1f of 20%n", s.name(), mean(wrong));
        }
        System.out.println("""
            
            n5 is perfectly healthy and 18 of its 19 peers can talk to it. Only the
            path to n1 is broken.
            
            Row 1 never notices, because n5's rising heartbeat reaches n1 through
            everybody else -- convenient, and it means n1 keeps routing work to a
            node it cannot actually reach.
            Row 2 notices and overreacts: n1 declares n5 dead on its own, and that
            verdict then spreads to nodes for which it is simply false.
            Row 3 is the fix: before blaming n5, n1 asks other nodes to try it. They
            can, so n1 keeps quiet. The lesson is that "is n5 up?" and "can I reach
            n5?" are different questions, and only the second one is answerable.""");
    }

    // ----------------------------------------------------------------- 10
    /**
     * The failure that surprises everyone: the spreading survives a bad network,
     * and the JUDGING does not.
     */
    static void detectorMeltdown() {
        title(10, "when the network is bad enough, the cluster declares itself dead");
        int n = 100, rounds = 40;
        System.out.printf("%n  %8s | %-18s | %-22s | %s%n", "loss",
                "processes running", "beliefs saying DEAD", "nodes that got the news");
        System.out.println("  " + "-".repeat(80));
        for (double loss : new double[]{0, 0.2, 0.4, 0.6, 0.8, 0.9}) {
            double dead = 0, reached = 0; int runs = 5;
            for (long seed = 1; seed <= runs; seed++) {
                Cluster c = new Cluster(n, seed);
                c.fanout = 3; c.dropRate = loss; c.suspectAfter = 3; c.deadAfter = 3;
                c.publishConfig("n1", "v", 2);
                for (int r = 0; r < rounds; r++) c.runRound();
                dead += c.deadBeliefFraction() * 100;
                reached += c.nodesWith(2);
            }
            System.out.printf("  %7.0f%% | %-18s | %19.0f%% | %.0f of %d%n",
                    loss * 100, n + " of " + n, dead / runs, reached / runs, n);
        }
        System.out.println("""
            
            Not one process crashed in any of those rows. At 80-90% loss the nodes
            write each other off wholesale, stop gossiping to anyone they have
            written off, and the cluster goes quiet -- a total outage produced
            entirely by its own failure detector.
            
            This is the honest limit of the mechanism. Gossip is extremely robust
            at SPREADING; the JUDGING bolted on top is the fragile part, because
            silence is the only symptom it has and a bad network manufactures
            silence for free. Every production mitigation -- adaptive timeouts,
            suspicion instead of death, refutation, asking for a second opinion --
            exists to buy back some of this table.""");
    }

    // ------------------------------------------------------------------ 9
    /** The window the consistent-hashing lab could not explain. */
    static void partitionWindow() {
        title(9, "the disagreement window: how long is the cluster of two minds?");
        int n = 100;
        System.out.printf("%n  %-30s | %s%n", "event", "rounds until every node agrees again");
        System.out.println("  " + "-".repeat(72));

        List<Integer> joinW = new ArrayList<>(), killW = new ArrayList<>(), healW = new ArrayList<>();
        List<Double> peak = new ArrayList<>();
        for (long seed = 1; seed <= 10; seed++) {
            // (a) a node dies -- how long until everyone's membership list matches?
            Cluster c = new Cluster(n, seed);
            c.fanout = 3; c.suspectAfter = 3; c.deadAfter = 3;
            for (int r = 0; r < 5; r++) c.runRound();
            c.kill("n42");
            int r = 0; double mx = 0;
            while (r < 100) {
                c.runRound(); r++;
                mx = Math.max(mx, c.disagreementFraction());
                if (c.disagreementFraction() == 0 && c.believing("n42", Status.DEAD) == c.runningCount()) break;
            }
            killW.add(r); peak.add(mx * 100);

            // (b) a fact published on one side of a partition, then healed
            Cluster d = new Cluster(n, seed + 100);
            d.fanout = 3; d.suspectAfter = 10_000;
            List<String> right = new ArrayList<>();
            for (int i = n / 2 + 1; i <= n; i++) right.add("n" + i);
            d.partition(right);
            d.publishConfig("n1", "v", 2);
            for (int i = 0; i < 15; i++) d.runRound();
            long split = d.nodesWith(2);
            d.heal();
            int rr = 0;
            while (d.nodesWith(2) < n && rr < 100) { d.runRound(); rr++; }
            healW.add(rr);
            if (seed == 1) System.out.printf("  %-30s | %d of %d nodes had it while split%n",
                    "(during the partition)", split, n);
        }
        System.out.printf("  %-30s | %.1f rounds (peak disagreement %.0f%% of nodes)%n",
                "a node crashes", mean(killW), mean(peak));
        System.out.printf("  %-30s | %.1f rounds after the link is repaired%n",
                "a partition heals", mean(healW));
        System.out.println("""
            
            This is the window the consistent-hashing lab had to assume away. While
            it is open, different nodes route the same key to different owners, and
            no amount of correctness in the hashing helps -- the disagreement is
            about the membership list, not the hash. Gossip does not remove that
            window; it bounds it, at a few rounds, and makes it self-healing.""");
    }
}
