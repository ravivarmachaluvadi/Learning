import java.util.*;

/** Tests for ImplExercise. Run: java src/ImplExerciseTest.java */
public class ImplExerciseTest {
    static int pass = 0, fail = 0;
    static void t(String name, boolean ok, String why) {
        if (ok) { pass++; System.out.println("  PASS  " + name); }
        else { fail++; System.out.println("  FAIL  " + name + "\n          " + why); }
    }

    public static void main(String[] a) {
        System.out.println("1. candidateLogIsUpToDate");
        t("equal_logs_are_up_to_date",
                ImplExercise.candidateLogIsUpToDate(3, 2, 3, 2), "identical logs must qualify");
        t("a_longer_log_in_the_same_term_is_up_to_date",
                ImplExercise.candidateLogIsUpToDate(5, 2, 3, 2), "index 5 beats index 3 at the same term");
        t("a_shorter_log_in_the_same_term_is_NOT_up_to_date",
                !ImplExercise.candidateLogIsUpToDate(2, 2, 3, 2), "index 2 must lose to index 3");
        t("a_later_term_wins_even_with_a_shorter_log",
                ImplExercise.candidateLogIsUpToDate(1, 5, 9, 4),
                "term 5 at index 1 beats term 4 at index 9 -- term is compared FIRST");
        t("an_earlier_term_loses_even_with_a_longer_log",
                !ImplExercise.candidateLogIsUpToDate(9, 4, 1, 5), "term 4 must lose to term 5");
        t("an_empty_candidate_log_loses_to_any_entry",
                !ImplExercise.candidateLogIsUpToDate(0, 0, 1, 1),
                "this is the check that stops a committed entry being erased");

        System.out.println("\n2. shouldGrantVote");
        t("grants_when_free_and_the_log_is_fine",
                ImplExercise.shouldGrantVote(4, -1, 4, 2, true), "no vote used yet");
        t("refuses_a_second_vote_to_a_DIFFERENT_candidate_in_the_same_term",
                !ImplExercise.shouldGrantVote(4, 1, 4, 2, true),
                "already voted for S1 in term 4 -- this is Election Safety");
        t("grants_again_to_the_SAME_candidate",
                ImplExercise.shouldGrantVote(4, 2, 4, 2, true), "a retry of a request already answered yes");
        t("refuses_a_candidate_with_a_stale_log",
                !ImplExercise.shouldGrantVote(4, -1, 4, 2, false), "log check failed");
        t("refuses_a_candidate_from_an_older_term",
                !ImplExercise.shouldGrantVote(5, -1, 4, 2, true), "term 4 request, we are on term 5");

        System.out.println("\n3. highestCommittableIndex");
        // 5 servers. Leader is on term 3. Its log: idx1 term1, idx2 term3.
        int[] logTerms = {0, 1, 3};
        t("commits_an_own_term_entry_once_a_majority_has_it",
                ImplExercise.highestCommittableIndex(new int[]{2, 2, 2, 0, 0}, logTerms, 3, 0) == 2,
                "3 of 5 have index 2 and it is a term-3 entry");
        t("does_not_commit_without_a_majority",
                ImplExercise.highestCommittableIndex(new int[]{2, 2, 0, 0, 0}, logTerms, 3, 0) == 0,
                "only 2 of 5");
        t("FIGURE_8_refuses_to_commit_an_OLD_term_entry_on_its_own",
                ImplExercise.highestCommittableIndex(new int[]{1, 1, 1, 1, 0}, new int[]{0, 1}, 3, 0) == 0,
                "4 of 5 have index 1, but it is a term-1 entry and the leader is on term 3");
        t("committing_a_new_entry_carries_the_old_one_with_it",
                ImplExercise.highestCommittableIndex(new int[]{2, 2, 2, 1, 0}, logTerms, 3, 0) == 2,
                "index 2 is term 3 and has a majority, so index 1 is committed for free underneath it");
        t("never_goes_backwards",
                ImplExercise.highestCommittableIndex(new int[]{0, 0, 0, 0, 0}, logTerms, 3, 2) == 2,
                "already committed to 2");

        System.out.println("\n" + "=".repeat(50));
        System.out.printf("  %d passed, %d failed%n", pass, fail);
        System.out.println("=".repeat(50));
        if (fail > 0) System.exit(1);
    }
}
