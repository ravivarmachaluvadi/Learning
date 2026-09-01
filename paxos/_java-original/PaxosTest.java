import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * PaxosTest.java — the tests ARE the documentation.
 *
 * Each test name is a scenario from README.md. Run them:
 *
 *     java PaxosTest.java
 *
 * Every test that involves more than one proposer ends by asserting the one
 * invariant that matters: at most one value is ever chosen.
 */
public class PaxosTest {

    static Paxos.Cluster twoProposerCluster() {
        return twoProposerCluster(0, true);
    }

    static Paxos.Cluster twoProposerCluster(int seed, boolean durable) {
        Paxos.Cluster c = new Paxos.Cluster(5, seed, durable, false);
        c.addProposer("P1", "Zoo");
        c.addProposer("P2", "Museum");
        return c;
    }

    /** Drive one full, successful Paxos round for `pid` using `acceptors`. */
    static void getChosenBy(Paxos.Cluster c, String pid, int n, String acceptors) {
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

    public static void main(String[] args) {
        System.out.println("Running PaxosTest — 17 scenarios from README.md");
        System.out.println();

        // -- QuorumMath ----------------------------------------------------
        test("test_quorum_is_a_strict_majority", PaxosTest::quorumIsAStrictMajority);
        test("test_any_two_quorums_always_overlap", PaxosTest::anyTwoQuorumsAlwaysOverlap);

        // -- HappyPath -----------------------------------------------------
        test("test_single_proposer_with_no_failures_chooses_its_own_value",
                PaxosTest::singleProposerWithNoFailuresChoosesItsOwnValue);
        test("test_a_minority_of_crashed_acceptors_does_not_stop_consensus",
                PaxosTest::aMinorityOfCrashedAcceptorsDoesNotStopConsensus);

        // -- TheAdoptRule --------------------------------------------------
        test("test_acceptor_rejects_a_proposal_below_its_promise",
                PaxosTest::acceptorRejectsAProposalBelowItsPromise);
        test("test_a_later_proposer_must_abandon_its_own_value_and_carry_the_chosen_one",
                PaxosTest::aLaterProposerMustAbandonItsOwnValueAndCarryTheChosenOne);
        test("test_a_value_accepted_by_only_a_minority_may_still_be_replaced",
                PaxosTest::aValueAcceptedByOnlyAMinorityMayStillBeReplaced);
        test("test_proposer_adopts_the_highest_numbered_accepted_value_not_the_first_it_hears",
                PaxosTest::proposerAdoptsTheHighestNumberedAcceptedValueNotTheFirstItHears);

        // -- Failures ------------------------------------------------------
        test("test_losing_a_majority_stalls_progress_without_breaking_safety",
                PaxosTest::losingAMajorityStallsProgressWithoutBreakingSafety);
        test("test_duplicate_messages_change_nothing", PaxosTest::duplicateMessagesChangeNothing);
        test("test_a_stale_accept_arriving_late_is_refused", PaxosTest::aStaleAcceptArrivingLateIsRefused);

        // -- Liveness ------------------------------------------------------
        test("test_two_proposers_can_duel_forever_without_ever_choosing_a_value",
                PaxosTest::twoProposersCanDuelForeverWithoutEverChoosingAValue);
        test("test_the_duel_ends_the_moment_only_one_proposer_keeps_trying",
                PaxosTest::theDuelEndsTheMomentOnlyOneProposerKeepsTrying);

        // -- Durability ----------------------------------------------------
        test("test_an_acceptor_that_forgets_after_a_crash_breaks_consensus",
                PaxosTest::anAcceptorThatForgetsAfterACrashBreaksConsensus);
        test("test_the_same_crash_is_harmless_when_acceptors_persist_their_state",
                PaxosTest::theSameCrashIsHarmlessWhenAcceptorsPersistTheirState);

        // -- Chaos ---------------------------------------------------------
        test("test_safety_survives_hundreds_of_random_schedules",
                PaxosTest::safetySurvivesHundredsOfRandomSchedules);
        test("test_chaos_can_still_reach_a_decision_once_the_noise_stops",
                PaxosTest::chaosCanStillReachADecisionOnceTheNoiseStops);

        System.out.println();
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------ QuorumMath

    static void quorumIsAStrictMajority() {
        assertEquals(2, Paxos.quorumSize(3));
        assertEquals(3, Paxos.quorumSize(5));
        assertEquals(3, Paxos.quorumSize(4));   // even sizes buy nothing
    }

    /** The single fact the whole protocol rests on. */
    static void anyTwoQuorumsAlwaysOverlap() {
        String nodes = "ABCDE";
        int q = Paxos.quorumSize(nodes.length());
        for (Set<String> left : combinations(nodes, q)) {
            for (Set<String> right : combinations(nodes, q)) {
                Set<String> shared = new LinkedHashSet<>(left);
                shared.retainAll(right);
                assertTrue(!shared.isEmpty(), left + " and " + right + " share nobody");
            }
        }
    }

    // ------------------------------------------------------------- HappyPath

    static void singleProposerWithNoFailuresChoosesItsOwnValue() {
        Paxos.Cluster c = twoProposerCluster();
        getChosenBy(c, "P1", 1, "ABC");
        assertEquals(Set.of("Zoo"), c.chosenValues());
        assertNull(c.proposers.get("P1").adoptedFrom,
                "nobody had accepted anything, so P1 kept its own value");
        assertEquals("done", c.proposers.get("P1").phase);
    }

    static void aMinorityOfCrashedAcceptorsDoesNotStopConsensus() {
        Paxos.Cluster c = twoProposerCluster();
        c.crash("D", "E");                      // 2 of 5 down, 3 still up
        getChosenBy(c, "P1", 1, "ABC");
        assertEquals(Set.of("Zoo"), c.chosenValues());
    }

    // ----------------------------------------------------------- TheAdoptRule
    // Phase 1 exists so that a new proposer can discover what came before.

    static void acceptorRejectsAProposalBelowItsPromise() {
        Paxos.Cluster c = twoProposerCluster();
        c.propose("P2", 9);
        c.deliverTo("A", "PREPARE", 9);         // A now promises 9
        c.propose("P1", 4);
        c.deliverTo("A", "PREPARE", 4);         // 4 < 9 -> refused
        List<Paxos.Message> replies = new ArrayList<>();
        for (Paxos.Message m : c.queue) {
            if (m.dst().equals("P1") && m.kind().equals("NACK")) replies.add(m);
        }
        assertEquals(1, replies.size());
        assertEquals(9, replies.get(0).lastN());
    }

    /**
     * The safety property, demonstrated end to end.
     *
     * P1 gets 'Zoo' chosen by {A,B,C}. P2 then runs a whole round with
     * {C,D,E} and *wanted* 'Museum' -- but C is in both majorities and
     * remembers, so P2 is forced to propose 'Zoo' instead.
     */
    static void aLaterProposerMustAbandonItsOwnValueAndCarryTheChosenOne() {
        Paxos.Cluster c = twoProposerCluster();
        getChosenBy(c, "P1", 1, "ABC");
        assertEquals(Set.of("Zoo"), c.chosenValues());

        c.propose("P2", 2);
        c.deliverMany("CDE", "PREPARE", 2);
        for (int i = 0; i < 3; i++) {
            c.deliverTo("P2", "PROMISE", 2);
        }

        Paxos.Proposer p2 = c.proposers.get("P2");
        assertEquals("Museum", p2.ownValue);
        assertEquals("Zoo", p2.proposedValue, "P2 had to adopt the value it learned from acceptor C");
        assertEquals(1, p2.adoptedFrom);

        c.run();
        assertEquals(Set.of("Zoo"), c.chosenValues());
        assertTrue(c.safetyHolds(), "safety must hold");
    }

    /**
     * The mirror image: 'accepted by some' is NOT 'chosen'.
     *
     * P1's ACCEPT reaches only A and B (2 of 5). Nothing is chosen, so P2 is
     * free to get a different value chosen -- and that is correct behaviour,
     * not a bug.
     */
    static void aValueAcceptedByOnlyAMinorityMayStillBeReplaced() {
        Paxos.Cluster c = twoProposerCluster();
        c.propose("P1", 1);
        c.deliverMany("ABC", "PREPARE", 1);
        for (int i = 0; i < 3; i++) {
            c.deliverTo("P1", "PROMISE", 1);
        }
        c.deliverMany("AB", "ACCEPT", 1);       // only 2 accept
        c.dropTo("C", "ACCEPT", 1);
        c.dropTo("D", "ACCEPT", 1);
        c.dropTo("E", "ACCEPT", 1);
        assertEquals(Set.of(), c.chosenValues(), "2 of 5 is not a majority");

        c.propose("P2", 2);
        c.deliverMany("CDE", "PREPARE", 2);
        for (int i = 0; i < 3; i++) {
            c.deliverTo("P2", "PROMISE", 2);
        }
        assertEquals("Museum", c.proposers.get("P2").proposedValue,
                "none of C,D,E had accepted anything, so P2 keeps its own value");
        c.deliverMany("CDE", "ACCEPT", 2);
        assertEquals(Set.of("Museum"), c.chosenValues());
        assertTrue(c.safetyHolds(), "safety must hold");
    }

    static void proposerAdoptsTheHighestNumberedAcceptedValueNotTheFirstItHears() {
        Paxos.Cluster c = new Paxos.Cluster(5);
        c.addProposer("P1", "Zoo");
        c.addProposer("P2", "Museum");
        c.addProposer("P3", "Beach");

        // A accepts an old proposal (n=1, Zoo); B accepts a newer one (n=5, Museum).
        c.propose("P1", 1);
        c.deliverMany("ABC", "PREPARE", 1);
        for (int i = 0; i < 3; i++) {
            c.deliverTo("P1", "PROMISE", 1);
        }
        c.deliverTo("A", "ACCEPT", 1);

        c.propose("P2", 5);
        c.deliverMany("BCD", "PREPARE", 5);
        for (int i = 0; i < 3; i++) {
            c.deliverTo("P2", "PROMISE", 5);
        }
        c.deliverTo("B", "ACCEPT", 5);

        // P3 asks A and B (plus E) and must take the n=5 value, not the n=1 one.
        c.propose("P3", 7);
        c.deliverMany("ABE", "PREPARE", 7);
        for (int i = 0; i < 3; i++) {
            c.deliverTo("P3", "PROMISE", 7);
        }
        assertEquals("Museum", c.proposers.get("P3").proposedValue);
        assertEquals(5, c.proposers.get("P3").adoptedFrom);
    }

    // --------------------------------------------------------------- Failures

    static void losingAMajorityStallsProgressWithoutBreakingSafety() {
        Paxos.Cluster c = twoProposerCluster();
        c.crash("C", "D", "E");                 // 3 of 5 down: no quorum remains
        c.propose("P1", 1);
        c.run();
        assertEquals(Set.of(), c.chosenValues(), "no majority, so nothing can be chosen");
        assertTrue(c.safetyHolds(), "stalling is allowed; deciding wrongly is not");
    }

    static void duplicateMessagesChangeNothing() {
        Paxos.Cluster c = twoProposerCluster();
        c.propose("P1", 1);
        c.deliverMany("ABC", "PREPARE", 1);
        for (int i = 0; i < 3; i++) {
            c.deliverTo("P1", "PROMISE", 1);
        }
        c.deliverMany("ABC", "ACCEPT", 1);
        List<Paxos.Cluster.Decision> first = List.copyOf(c.chosen);

        for (int dup = 0; dup < 3; dup++) {     // replay the same ACCEPTs
            c.proposers.get("P1").receive(Paxos.Message.accepted("A", "P1", 1, "Zoo"));
        }
        c.run();
        assertEquals(Set.of("Zoo"), c.chosenValues());
        assertEquals(first.size(), c.chosen.size(), "a value is announced chosen exactly once");
    }

    static void aStaleAcceptArrivingLateIsRefused() {
        Paxos.Cluster c = twoProposerCluster();
        c.propose("P1", 1);
        c.deliverMany("ABC", "PREPARE", 1);
        for (int i = 0; i < 3; i++) {
            c.deliverTo("P1", "PROMISE", 1);    // ACCEPT(1) now in flight
        }

        c.propose("P2", 2);                     // a newer round overtakes it
        c.deliverMany("ABC", "PREPARE", 2);

        c.deliverMany("ABC", "ACCEPT", 1);      // the old ACCEPT finally lands
        assertEquals(Set.of(), c.chosenValues());
        for (char a : "ABC".toCharArray()) {
            assertNull(c.acceptors.get(String.valueOf(a)).acceptedValue,
                    a + " must refuse an ACCEPT below its promise");
        }
    }

    // --------------------------------------------------------------- Liveness

    /** Paxos is always safe. It is not always live -- and this is why. */
    static void twoProposersCanDuelForeverWithoutEverChoosingAValue() {
        Paxos.Cluster c = twoProposerCluster();
        int n = 1;
        for (int r = 0; r < 8; r++) {           // eight full rounds, no progress
            c.propose("P1", n);
            c.deliverMany("ABC", "PREPARE", n);
            for (int i = 0; i < 3; i++) {
                c.deliverTo("P1", "PROMISE", n);
            }

            c.propose("P2", n + 1);             // P2 gazumps P1's promise
            c.deliverMany("ABC", "PREPARE", n + 1);
            for (int i = 0; i < 3; i++) {
                c.deliverTo("P2", "PROMISE", n + 1);
            }

            c.deliverMany("ABC", "ACCEPT", n);  // P1's accept is now too old
            n += 2;
        }

        assertEquals(Set.of(), c.chosenValues(), "nothing was ever chosen");
        assertTrue(c.safetyHolds(), "but nothing wrong was chosen either");
    }

    /** The production fix: elect a leader and let everyone else stand down. */
    static void theDuelEndsTheMomentOnlyOneProposerKeepsTrying() {
        Paxos.Cluster c = twoProposerCluster();
        int n = 1;
        for (int r = 0; r < 4; r++) {           // stage the same stalemate
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
            n += 2;
        }
        assertEquals(Set.of(), c.chosenValues(), "the duel chose nothing");

        // P2 stands down. P1 alone needs exactly one clean round.
        getChosenBy(c, "P1", 101, "ABC");
        assertEquals(Set.of("Zoo"), c.chosenValues());
    }

    // ------------------------------------------------------------- Durability

    /**
     * The #1 way real implementations get this wrong.
     *
     * An acceptor MUST write promised_n / accepted_n / accepted_value to
     * stable storage BEFORE replying. Keep them in RAM only, and a reboot
     * turns the acceptor into a liar -- it re-promises as if it had never
     * voted, and two different values get chosen.
     *
     * This test asserts the FAILURE, to pin the bug down.
     */
    static void anAcceptorThatForgetsAfterACrashBreaksConsensus() {
        Paxos.Cluster c = twoProposerCluster(0, false);
        getChosenBy(c, "P1", 1, "ABC");
        assertEquals(Set.of("Zoo"), c.chosenValues());

        c.crash("A", "B", "C");                 // amnesia
        c.recover("A", "B", "C");
        for (char a : "ABC".toCharArray()) {
            assertNull(c.acceptors.get(String.valueOf(a)).acceptedValue, "state was lost");
        }

        getChosenBy(c, "P2", 2, "ABC");
        assertEquals(Set.of("Zoo", "Museum"), c.chosenValues());
        assertFalse(c.safetyHolds(), "two different values chosen: consensus is broken");
    }

    static void theSameCrashIsHarmlessWhenAcceptorsPersistTheirState() {
        Paxos.Cluster c = twoProposerCluster(0, true);
        getChosenBy(c, "P1", 1, "ABC");
        c.crash("A", "B", "C");
        c.recover("A", "B", "C");
        getChosenBy(c, "P2", 2, "ABC");
        assertEquals(Set.of("Zoo"), c.chosenValues());
        assertTrue(c.safetyHolds(), "safety must hold");
    }

    // ------------------------------------------------------------------ Chaos

    /**
     * Try hard to break the invariant instead of hoping it holds.
     *
     * Random message loss, duplication, crashes and recoveries, two
     * proposers fighting, 300 different schedules. At most one value may
     * ever be chosen in any of them.
     */
    static void safetySurvivesHundredsOfRandomSchedules() {
        for (int seed = 0; seed < 300; seed++) {
            Random rng = new Random(seed);
            Paxos.Cluster c = twoProposerCluster(seed, true);
            c.dropRate = 0.25;
            c.dupRate = 0.15;

            for (int rnd = 0; rnd < 6; rnd++) {
                for (String pid : List.of("P1", "P2")) {
                    if (rng.nextDouble() < 0.7) c.retry(pid);
                }
                String victim = String.valueOf("ABCDE".charAt(rng.nextInt(5)));
                if (rng.nextDouble() < 0.3) c.crash(victim);
                c.run(200);
                c.recover(victim);
                c.run(200);
            }

            assertTrue(c.safetyHolds(),
                    "seed " + seed + ": chose more than one value -> " + Paxos.reprSet(c.chosenValues()));
        }
    }

    /** Safety is unconditional; liveness needs the network to behave. */
    static void chaosCanStillReachADecisionOnceTheNoiseStops() {
        int decided = 0;
        for (int seed = 0; seed < 50; seed++) {
            Paxos.Cluster c = twoProposerCluster(seed, true);
            c.dropRate = 0.25;
            for (int i = 0; i < 3; i++) {
                c.retry("P1");
                c.run(200);
            }
            c.dropRate = 0.0;                   // the storm passes
            for (int attempt = 0; attempt < 5; attempt++) {
                c.retry("P1");
                c.run(200);
                if (!c.chosenValues().isEmpty()) break;
            }
            if (!c.chosenValues().isEmpty()) decided += 1;
            assertTrue(c.safetyHolds(), "safety must hold");
        }
        assertEquals(50, decided, "a lone proposer on a quiet network always finishes");
    }

    // ---------------------------------------------------------------- harness

    static int passed = 0, failed = 0;

    interface Scenario {
        void run() throws Exception;
    }

    static void test(String name, Scenario body) {
        try {
            body.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (AssertionFailed e) {
            failed++;
            System.out.println("  FAIL  " + name + "  (" + e.getMessage() + ")");
        } catch (Exception e) {
            failed++;
            System.out.println("  FAIL  " + name + "  (" + e + ")");
        }
    }

    static final class AssertionFailed extends RuntimeException {
        AssertionFailed(String message) {
            super(message);
        }
    }

    static void assertEquals(Object expected, Object actual) {
        assertEquals(expected, actual, null);
    }

    static void assertEquals(Object expected, Object actual, String because) {
        if (!java.util.Objects.equals(expected, actual)) {
            fail("expected " + expected + ", got " + actual, because);
        }
    }

    static void assertNull(Object actual, String because) {
        if (actual != null) fail("expected None, got " + actual, because);
    }

    static void assertTrue(boolean condition, String because) {
        if (!condition) fail("expected true", because);
    }

    static void assertFalse(boolean condition, String because) {
        if (condition) fail("expected false", because);
    }

    static void fail(String detail, String because) {
        throw new AssertionFailed(because == null ? detail : detail + "; " + because);
    }

    /** All size-k subsets of the given node names, in lexicographic order. */
    static List<Set<String>> combinations(String nodes, int k) {
        List<Set<String>> out = new ArrayList<>();
        combine(nodes, k, 0, new ArrayList<>(), out);
        return out;
    }

    private static void combine(String nodes, int k, int start, List<String> acc, List<Set<String>> out) {
        if (acc.size() == k) {
            out.add(new LinkedHashSet<>(acc));
            return;
        }
        for (int i = start; i < nodes.length(); i++) {
            acc.add(String.valueOf(nodes.charAt(i)));
            combine(nodes, k, i + 1, acc, out);
            acc.remove(acc.size() - 1);
        }
    }
}
