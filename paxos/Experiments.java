import java.util.List;
import java.util.TreeSet;

/**
 * Experiments.java — break Paxos on purpose and watch what it does.
 *
 *     java Experiments.java         # run all five
 *     java Experiments.java 4       # run just one
 *
 * Each experiment prints every message as it is delivered, so you can follow the
 * protocol one step at a time. The final line of each is the verdict.
 */
public class Experiments {

    static void banner(int num, String title, String question) {
        System.out.println();
        System.out.println("=".repeat(74));
        System.out.println(" EXPERIMENT " + num + " — " + title);
        System.out.println(" " + question);
        System.out.println("=".repeat(74));
    }

    static void verdict(Paxos.Cluster c, String expected) {
        System.out.println();
        System.out.println(c.table());
        System.out.println();
        var chosen = c.chosenValues();
        System.out.println(" chosen value(s): "
                + (chosen.isEmpty() ? "(nothing chosen)" : Paxos.reprSet(chosen)));
        System.out.println(" safety holds (at most one value ever chosen): "
                + Paxos.reprBool(c.safetyHolds()));
        System.out.println(" >> " + expected);
    }

    /** One complete, successful round: prepare -> promise -> accept -> accepted. */
    static void fullRound(Paxos.Cluster c, String pid, int n, String acceptors) {
        c.propose(pid, n);
        c.deliverMany(acceptors, "PREPARE", n);
        for (int i = 0; i < acceptors.length(); i++) {
            c.deliverTo(pid, "PROMISE", n);
        }
        c.deliverMany(acceptors, "ACCEPT", n);
        for (int i = 0; i < acceptors.length(); i++) {
            c.deliverTo(pid, "ACCEPTED", n);
        }
    }

    static Paxos.Cluster newCluster() {
        return newCluster(true);
    }

    static Paxos.Cluster newCluster(boolean durable) {
        Paxos.Cluster c = new Paxos.Cluster(5, 0, durable, true);
        c.addProposer("P1", "Zoo");
        c.addProposer("P2", "Museum");
        System.out.println(" 5 acceptors A-E, majority = " + c.quorum()
                + ".  P1 wants 'Zoo', P2 wants 'Museum'.");
        return c;
    }

    // -----------------------------------------------------------------------

    static void experiment1() {
        banner(1, "the happy path", "One proposer, nothing broken. What does a clean run look like?");
        Paxos.Cluster c = newCluster();
        System.out.println("\n-- P1 runs both phases with A, B, C --");
        fullRound(c, "P1", 1, "ABC");
        verdict(c, "Nobody had accepted anything, so P1 was free to use its own value.");
    }

    static void experiment2() {
        banner(2, "a chosen value can never be changed",
                "P1 gets 'Zoo' chosen. Can P2 then push 'Museum' through?");
        Paxos.Cluster c = newCluster();
        System.out.println("\n-- P1 gets 'Zoo' chosen by the majority {A,B,C} --");
        fullRound(c, "P1", 1, "ABC");
        System.out.println("\n  'Zoo' is now CHOSEN by " + Paxos.reprList(new TreeSet<>(c.chosenQuorum)) + ".");

        System.out.println("\n-- P2 tries with a DIFFERENT majority {C,D,E} and a higher number --");
        c.propose("P2", 2);
        c.deliverMany("CDE", "PREPARE", 2);
        for (int i = 0; i < 3; i++) {
            c.deliverTo("P2", "PROMISE", 2);
        }

        Paxos.Proposer p2 = c.proposers.get("P2");
        System.out.println("\n  C is in BOTH majorities: " + Paxos.reprList(new TreeSet<>(c.chosenQuorum)) + " and {C,D,E}.");
        System.out.println("  C reported it had already accepted " + Paxos.repr(p2.proposedValue)
                + " at n=" + p2.adoptedFrom + ".");
        System.out.println("  P2 wanted " + Paxos.repr(p2.ownValue) + " but is forced to propose "
                + Paxos.repr(p2.proposedValue) + ".");
        c.deliverMany("CDE", "ACCEPT", 2);
        for (int i = 0; i < 3; i++) {
            c.deliverTo("P2", "ACCEPTED", 2);
        }
        verdict(c, "Quorum overlap did the work: 'Museum' was never even proposed.");
    }

    static void experiment3() {
        banner(3, "accepted by SOME is not the same as chosen",
                "P1's ACCEPT reaches only 2 of 5. Is 'Zoo' safe from replacement?");
        Paxos.Cluster c = newCluster();
        System.out.println("\n-- P1 prepares with A,B,C, then its ACCEPT reaches only A and B --");
        c.propose("P1", 1);
        c.deliverMany("ABC", "PREPARE", 1);
        for (int i = 0; i < 3; i++) {
            c.deliverTo("P1", "PROMISE", 1);
        }
        c.deliverMany("AB", "ACCEPT", 1);
        for (char d : "CDE".toCharArray()) {
            c.dropTo(String.valueOf(d), "ACCEPT", 1);
        }
        System.out.println("\n  2 of 5 accepted 'Zoo'. Majority is " + c.quorum()
                + ", so NOTHING is chosen yet.");

        System.out.println("\n-- P2 now runs a full round with C, D, E --");
        c.propose("P2", 2);
        c.deliverMany("CDE", "PREPARE", 2);
        for (int i = 0; i < 3; i++) {
            c.deliverTo("P2", "PROMISE", 2);
        }
        System.out.println("\n  None of C,D,E had accepted anything, so P2 keeps its own value "
                + Paxos.repr(c.proposers.get("P2").proposedValue) + ".");
        c.deliverMany("CDE", "ACCEPT", 2);
        for (int i = 0; i < 3; i++) {
            c.deliverTo("P2", "ACCEPTED", 2);
        }
        verdict(c, "'Museum' wins, and that is CORRECT: 'Zoo' had never been chosen. "
                + "A had accepted it, but no majority had.");
    }

    static void experiment4() {
        banner(4, "duelling proposers (livelock)",
                "Two proposers keep gazumping each other. Does Paxos still terminate?");
        Paxos.Cluster c = newCluster();
        c.trace = false;
        System.out.println("\n-- eight rounds, P1 and P2 alternating, no crashes, no lost messages --");
        int n = 1;
        for (int rnd = 1; rnd <= 8; rnd++) {
            c.propose("P1", n);
            c.deliverMany("ABC", "PREPARE", n);
            for (int i = 0; i < 3; i++) {
                c.deliverTo("P1", "PROMISE", n);
            }

            c.propose("P2", n + 1);
            c.deliverMany("ABC", "PREPARE", n + 1);
            for (int i = 0; i < 3; i++) {
                c.deliverTo("P2", "PROMISE", n + 1);
            }

            c.deliverMany("ABC", "ACCEPT", n);
            var chosen = c.chosenValues();
            System.out.println("  round " + rnd + ": P1 prepared n=" + n
                    + ", P2 gazumped with n=" + (n + 1)
                    + ", P1's ACCEPT(n=" + n + ") refused -> chosen so far: "
                    + (chosen.isEmpty() ? "(nothing)" : Paxos.reprSet(chosen)));
            n += 2;
        }

        System.out.println("\n-- now P2 stands down (this is what electing a leader buys you) --");
        c.trace = true;
        fullRound(c, "P1", 101, "ABC");
        verdict(c, "Safety was never in danger; only progress was. A single "
                + "proposer finishes in one round.");
    }

    static void experiment5() {
        banner(5, "the amnesiac acceptor",
                "An acceptor keeps its promises in RAM instead of on disk. What breaks?");
        Paxos.Cluster c = newCluster(false);
        System.out.println("\n-- P1 gets 'Zoo' chosen by A, B, C --");
        fullRound(c, "P1", 1, "ABC");

        System.out.println("\n-- A, B and C reboot. Being non-durable, they forget everything --");
        c.crash("A", "B", "C");
        c.recover("A", "B", "C");
        System.out.println();
        System.out.println(c.table());

        System.out.println("\n-- P2 now runs a normal, entirely rule-abiding round with A, B, C --");
        fullRound(c, "P2", 2, "ABC");
        verdict(c, "TWO different values were chosen. Consensus is broken -- and no "
                + "node did anything 'wrong'. This is why acceptors must fsync "
                + "promised_n / accepted_n / accepted_value BEFORE replying.");
    }

    static final List<Runnable> EXPERIMENTS = List.of(
            Experiments::experiment1,
            Experiments::experiment2,
            Experiments::experiment3,
            Experiments::experiment4,
            Experiments::experiment5);

    public static void main(String[] args) {
        if (args.length > 0) {
            EXPERIMENTS.get(Integer.parseInt(args[0]) - 1).run();
        } else {
            for (Runnable fn : EXPERIMENTS) fn.run();
            System.out.println("\n" + "=".repeat(74));
            System.out.println(" Experiments 1-4: safety held in every one.");
            System.out.println(" Experiment 5: safety broke -- because an acceptor lied about its past.");
            System.out.println("=".repeat(74));
        }
    }
}
