import java.util.ArrayList;
import java.util.List;

import static java.util.List.of;

/** Tests for ImplExercise.java — each test name states the rule it enforces. */
public class ImplExerciseTest {

    static final int QUORUM = 3;

    static ImplExercise.Promise promise(String acceptorId, int lastN, Object lastValue) {
        return new ImplExercise.Promise(acceptorId, lastN, lastValue);
    }

    static ImplExercise.AcceptorState state(int acceptedN, Object acceptedValue) {
        return new ImplExercise.AcceptorState(acceptedN, acceptedValue);
    }

    public static void main(String[] args) {
        System.out.println("Running ImplExerciseTest — 8 rules");
        System.out.println();

        // -- PickValueRule -------------------------------------------------
        test("test_clean_history_means_you_may_use_your_own_value",
                ImplExerciseTest::cleanHistoryMeansYouMayUseYourOwnValue);
        test("test_any_reported_value_must_be_adopted_over_your_own",
                ImplExerciseTest::anyReportedValueMustBeAdoptedOverYourOwn);
        test("test_the_highest_numbered_accepted_value_wins_not_the_most_common",
                ImplExerciseTest::theHighestNumberedAcceptedValueWinsNotTheMostCommon);

        // -- ChosenRule ----------------------------------------------------
        test("test_nothing_accepted_means_nothing_chosen",
                ImplExerciseTest::nothingAcceptedMeansNothingChosen);
        test("test_a_minority_writing_it_down_is_not_a_decision",
                ImplExerciseTest::aMinorityWritingItDownIsNotADecision);
        test("test_a_majority_on_the_same_proposal_number_is_a_decision",
                ImplExerciseTest::aMajorityOnTheSameProposalNumberIsADecision);
        test("test_same_value_under_different_numbers_does_not_count_as_one_quorum",
                ImplExerciseTest::sameValueUnderDifferentNumbersDoesNotCountAsOneQuorum);
        test("test_a_later_full_quorum_decides_even_with_older_leftovers",
                ImplExerciseTest::aLaterFullQuorumDecidesEvenWithOlderLeftovers);

        System.out.println();
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // -------------------------------------------------------- PickValueRule

    static void cleanHistoryMeansYouMayUseYourOwnValue() {
        var promises = of(promise("A", 0, null), promise("B", 0, null), promise("C", 0, null));
        assertEquals("Museum", ImplExercise.pickValueFromPromises(promises, "Museum"));
    }

    static void anyReportedValueMustBeAdoptedOverYourOwn() {
        var promises = of(promise("A", 0, null), promise("B", 1, "Zoo"), promise("C", 0, null));
        assertEquals("Zoo", ImplExercise.pickValueFromPromises(promises, "Museum"));
    }

    static void theHighestNumberedAcceptedValueWinsNotTheMostCommon() {
        // Two acceptors report an OLD value, one reports a NEWER one.
        // Majority-of-reports is the tempting wrong rule; highest n is the law.
        var promises = of(promise("A", 3, "Zoo"), promise("B", 3, "Zoo"), promise("C", 5, "Museum"));
        assertEquals("Museum", ImplExercise.pickValueFromPromises(promises, "Aquarium"));
    }

    // ----------------------------------------------------------- ChosenRule

    static void nothingAcceptedMeansNothingChosen() {
        List<ImplExercise.AcceptorState> states = new ArrayList<>();
        for (int i = 0; i < 5; i++) states.add(state(0, null));
        assertNull(ImplExercise.chosenValue(states, QUORUM));
    }

    static void aMinorityWritingItDownIsNotADecision() {
        var states = of(state(1, "Zoo"), state(1, "Zoo"), state(0, null), state(0, null), state(0, null));
        assertNull(ImplExercise.chosenValue(states, QUORUM));
    }

    static void aMajorityOnTheSameProposalNumberIsADecision() {
        var states = of(state(1, "Zoo"), state(1, "Zoo"), state(1, "Zoo"), state(0, null), state(0, null));
        assertEquals("Zoo", ImplExercise.chosenValue(states, QUORUM));
    }

    static void sameValueUnderDifferentNumbersDoesNotCountAsOneQuorum() {
        // Three acceptors say "Zoo" — but not under the same proposal number.
        // No single (n, value) pair has a majority, so nothing is chosen YET.
        // (A value re-proposed under a higher n forms its own, new quorum.)
        var states = of(state(1, "Zoo"), state(1, "Zoo"), state(2, "Zoo"), state(0, null), state(0, null));
        assertNull(ImplExercise.chosenValue(states, QUORUM));
    }

    static void aLaterFullQuorumDecidesEvenWithOlderLeftovers() {
        var states = of(state(1, "Zoo"), state(2, "Zoo"), state(2, "Zoo"), state(2, "Zoo"), state(0, null));
        assertEquals("Zoo", ImplExercise.chosenValue(states, QUORUM));
    }

    // -------------------------------------------------------------- harness

    static int passed = 0, failed = 0;

    interface Rule {
        void run() throws Exception;
    }

    static void test(String name, Rule body) {
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
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionFailed("expected " + expected + ", got " + actual);
        }
    }

    static void assertNull(Object actual) {
        if (actual != null) throw new AssertionFailed("expected null, got " + actual);
    }
}
