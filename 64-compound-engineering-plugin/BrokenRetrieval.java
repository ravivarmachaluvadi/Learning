import java.util.*;

/**
 * BrokenRetrieval — for EXERCISES.md, rung 6 (Debug).
 *
 * This file contains ONE deliberate bug in the retrieval step. It is not a crash
 * and not a wrong answer: every learning that should be found is still found, and
 * the run prints a confident-looking result. Something else is destroyed.
 *
 * Run:  java BrokenRetrieval.java
 *
 * Two checks run below. One passes, one fails. Your job:
 *   1. Which check fails, and what does its number tell you?
 *   2. Which single line in grepFirstBroken causes it?
 *   3. Which invariant from CompoundLab.java does that line violate — and why
 *      does breaking it make the whole compounding loop pointless, even though
 *      the retrieved documents are still correct?
 *
 * Answers in SOLUTIONS.md.
 */
public class BrokenRetrieval {

    public static void main(String[] args) {
        List<Learning> corpus = Corpus.build(96);
        Set<String> query = Text.queryTerms(Corpus.TOPICS.get(0).canonicalQuery());
        String gold = Corpus.goldKey(Corpus.TOPICS.get(0), "payments");

        Retrieval.Result fixed = Retrieval.grepFirst(corpus, query);
        Retrieval.Result broken = grepFirstBroken(corpus, query);

        System.out.println("=".repeat(64));
        System.out.println("BrokenRetrieval — one bug, two checks");
        System.out.println("=".repeat(64));

        // CHECK 1 — correctness. Does retrieval still return the right document?
        boolean c1 = Retrieval.found(broken, gold);
        System.out.printf("%nCHECK 1  retrieval_returns_the_preventing_doc%n");
        System.out.printf("         reference : %s%n", Retrieval.found(fixed, gold) ? "found" : "missed");
        System.out.printf("         broken    : %s%n", c1 ? "found" : "missed");
        System.out.printf("         -> %s%n", c1 ? "PASS" : "FAIL");

        // CHECK 2 — the property the whole design exists to provide.
        // The cost of consulting past work must not grow with how much was learned.
        int small = grepFirstBroken(Corpus.build(12), query).tokensIntoContext();
        int large = broken.tokensIntoContext();
        double growth = (double) large / small;
        boolean c2 = growth < 1.5;
        System.out.printf("%nCHECK 2  context_cost_is_flat_in_corpus_size%n");
        System.out.printf("         12-doc corpus : %,d tokens%n", small);
        System.out.printf("         96-doc corpus : %,d tokens%n", large);
        System.out.printf("         growth        : %.1fx  (must stay under 1.5x)%n", growth);
        System.out.printf("         -> %s%n", c2 ? "PASS" : "FAIL");

        System.out.printf("%nreference implementation, same two corpus sizes: %,d -> %,d tokens (%.1fx)%n",
                Retrieval.grepFirst(Corpus.build(12), query).tokensIntoContext(),
                fixed.tokensIntoContext(),
                (double) fixed.tokensIntoContext()
                        / Retrieval.grepFirst(Corpus.build(12), query).tokensIntoContext());

        System.out.println("\nNow open grepFirstBroken below and find the line.");
    }

    /**
     * The retrieval step, with one line changed from CompoundLab's version.
     */
    static Retrieval.Result grepFirstBroken(List<Learning> corpus, Set<String> queryTerms) {
        List<Learning> candidates = corpus.stream()
                .filter(l -> !Collections.disjoint(l.frontmatterTerms(), queryTerms))
                .sorted(Comparator.comparingInt((Learning l) -> -Retrieval.score(l, queryTerms)))
                .limit(Retrieval.SHORTLIST_CAP)
                .toList();

        // Pull the shortlist in so it can be ranked properly before we choose.
        int chars = candidates.stream().mapToInt(Learning::totalChars).sum();

        List<Learning> ranked = candidates.stream()
                .sorted(Comparator.comparingInt((Learning l) -> -Retrieval.score(l, queryTerms)))
                .limit(Retrieval.TOP_K)
                .toList();

        return new Retrieval.Result(ranked, corpus.size(), candidates.size(), ranked.size(), chars);
    }
}
