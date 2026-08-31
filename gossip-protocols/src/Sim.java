import java.util.*;

/**
 * One narrated afternoon in an 8-node cluster.
 *
 * Run:  java src/Sim.java
 *
 * Nothing here is random-free -- gossip IS random -- but the seed is fixed, so
 * you and I see exactly the same afternoon.
 */
public class Sim {

    static void scene(String title) {
        System.out.println("\n" + "=".repeat(76));
        System.out.println("  " + title);
        System.out.println("=".repeat(76));
    }

    /** One line per round: who holds the news yet. */
    static void showSpread(Cluster c, long version) {
        StringBuilder sb = new StringBuilder("    ");
        for (Node n : c.nodes) sb.append(n.configVersion >= version ? "#" : ".").append(' ');
        System.out.printf("%sround %-2d  %d/%d know it%n", sb, c.round, c.nodesWith(version), c.nodes.size());
    }

    static void showBeliefs(Cluster c, String about) {
        System.out.printf("    beliefs about %s:  ALIVE %d   SUSPECT %d   DEAD %d   (of %d running)%n",
                about, c.believing(about, Status.ALIVE), c.believing(about, Status.SUSPECT),
                c.believing(about, Status.DEAD), c.runningCount());
    }

    public static void main(String[] args) {
        Cluster c = new Cluster(8, 42);
        c.fanout = 1;
        c.mode = Cluster.Mode.PUSH;
        c.verbose = true;

        scene("SCENE 1 - one node learns something. Nobody else does yet.");
        System.out.println("""
            
            n1 has just been told the cluster's write-leader is now n7. Seven other
            nodes are still working from the old answer. Nobody is in charge of
            telling them: n1 will pass it to ONE random node per round, and so will
            everyone who hears it.
            
            '#' = knows,  '.' = does not.""");
        c.publishConfig("n1", "leader=n7", 2);
        showSpread(c, 2);
        while (c.nodesWith(2) < 8 && c.round < 20) { c.runRound(); showSpread(c, 2); }
        System.out.printf("""
            
            Eight nodes, fanout 1, %d rounds. The important thing is not the exact
            counts (they are random) but the SHAPE: the group that knows grows by a
            FACTOR each round, not by a fixed number, because every node that heard
            it is now also telling somebody. That is why 8 nodes take a handful of
            rounds and 1,000 nodes take about 11 -- not 125x more.%n""", c.round);

        scene("SCENE 2 - what it cost");
        System.out.printf("""
                gossip, %d rounds, fanout 1     : %d messages in total
                every node telling every node   : %d messages per round, %d in total
            
            The gap is small at 8 nodes and it is the whole story at 800: gossip
            costs each node a fixed amount per round no matter how big the cluster
            is, while telling everybody costs each node one message per other node.
            %n""", c.round, c.messagesSent, 8 * 7, 8 * 7 * c.round);

        scene("SCENE 3 - n5 crashes. Nobody is told; everybody notices.");
        c.log.clear();
        c.fanout = 2;
        c.mode = Cluster.Mode.PUSH_PULL;
        c.kill("n5");
        System.out.println("""
            
            n5's process is gone. It sends nothing and answers nothing. No node has
            been notified -- each one simply stops seeing n5's heartbeat move, and
            after 3 quiet rounds starts to doubt it.""");
        for (int i = 0; i < 9; i++) {
            c.runRound();
            if (i % 2 == 1 || i == 8) { System.out.printf("%n  after round %d:%n", c.round); showBeliefs(c, "n5"); }
        }
        System.out.println("\n    what the nodes said:");
        c.log.stream().filter(s -> s.contains("n5")).limit(8).forEach(s -> System.out.println("      " + s));

        scene("SCENE 4 - n3 was only busy, and says so");
        c.log.clear();
        System.out.println("""
            
            Now the dangerous case. n3 is alive and healthy, but a 6-round garbage
            collection pause stops it answering. Its peers cannot tell the difference
            between "paused" and "crashed" -- nobody can -- so they suspect it.""");
        c.kill("n3");                                   // stands in for a long pause
        for (int i = 0; i < 6; i++) c.runRound();
        showBeliefs(c, "n3");
        System.out.println("\n    n3 comes back:");
        c.revive("n3");
        for (int i = 0; i < 5; i++) c.runRound();
        showBeliefs(c, "n3");
        c.log.stream().filter(s -> s.contains("REFUTED")).limit(3).forEach(s -> System.out.println("      " + s));
        System.out.printf("""
            
            n3's incarnation number is now %d. That number is the only way a node
            can overrule the whole cluster about itself -- and it works because
            everyone's merge rule says "a higher incarnation always wins".%n""",
                c.byId.get("n3").incarnation);

        scene("SCENE 5 - the cluster splits in two");
        c.log.clear();
        System.out.println("""
            
            A switch fails: n1-n4 can talk to each other, n6-n8 can talk to each
            other, and nothing crosses. A new fact is published on the left side.""");
        c.partition(List.of("n6", "n7", "n8"));
        c.publishConfig("n1", "leader=n2", 3);
        for (int i = 0; i < 6; i++) c.runRound();
        System.out.printf("    during the split : %d of %d nodes have v3%n", c.nodesWith(3), c.runningCount());
        System.out.printf("    membership disagreement across the cluster: %.0f%%%n",
                100 * c.disagreementFraction());
        System.out.println("\n    the switch is repaired:");
        c.heal();
        for (int i = 0; i < 6; i++) c.runRound();
        System.out.printf("    after healing    : %d of %d nodes have v3%n", c.nodesWith(3), c.runningCount());
        System.out.println("""
            
            Nothing special happened at the moment of healing. No re-sync, no
            catch-up protocol, no leader deciding what the truth is. The first
            message across the mended link carried a higher version number, and
            the merge rule did the rest.""");

        scene("SCENE 6 - half the messages are lost");
        c.log.clear();
        c.dropRate = 0.5;
        c.publishConfig("n8", "leader=n8", 4);
        long msgBefore = c.messagesSent, dropBefore = c.messagesDropped, roundBefore = c.round;
        while (c.nodesWith(4) < c.runningCount() && c.round - roundBefore < 40) c.runRound();
        System.out.printf("""
            
                50%% packet loss, fanout 2, push-pull
                rounds to reach every running node : %d
                messages attempted                 : %d   (%d of them lost)
            
            Losing half the messages did not break anything and did not need a
            single retry. There is no retry logic in this lab. Redundancy IS the
            retry: every round, every node tries again with different partners.%n""",
                c.round - roundBefore, c.messagesSent - msgBefore, c.messagesDropped - dropBefore);

        System.out.println("\n" + "-".repeat(76));
        System.out.println("  Next:  java src/Experiments.java   -- measure all of this properly");
    }
}
