import java.util.*;

/**
 * Retrieval — the return arrow, the one mechanism that makes the loop compound.
 *
 * Two strategies over the same corpus and the same query. The difference between
 * them is the entire engineering content of the plugin's memory design.
 */
final class Retrieval {

    static final int SHORTLIST_CAP = 25;   // learnings-researcher: ">25 candidates -> narrow the pattern"
    static final int TOP_K = 5;            // how many bodies the agent actually reads

    record Result(
            List<Learning> returned,
            int filesGrepped,
            int frontmattersRead,
            int bodiesRead,
            int charsIntoContext
    ) {
        int tokensIntoContext() { return charsIntoContext / 4; }
    }

    /**
     * STRATEGY A — naive. "Read the notes before you start."
     * Every document is pulled into the agent's context, then judged. Correct,
     * and it stops being affordable almost immediately.
     */
    static Result readEverything(List<Learning> corpus, Set<String> queryTerms) {
        int chars = corpus.stream().mapToInt(Learning::totalChars).sum();
        List<Learning> ranked = corpus.stream()
                .sorted(Comparator.comparingInt((Learning l) -> -score(l, queryTerms)))
                .limit(TOP_K)
                .toList();
        return new Result(ranked, 0, corpus.size(), corpus.size(), chars);
    }

    /**
     * STRATEGY B — grep-first, which is what the plugin actually does.
     *
     * INVARIANT (learnings-researcher, Step 3): the pre-filter scans file CONTENTS
     * without reading them into context. Only the shortlist is read. This is the
     * whole reason a corpus can grow to hundreds of docs and still cost the next
     * task almost nothing. BrokenRetrieval.java violates exactly this line.
     */
    static Result grepFirst(List<Learning> corpus, Set<String> queryTerms) {
        // Step 3: pre-filter on frontmatter fields only. Costs zero context.
        //
        // The real skill says: if the pre-filter returns more than 25 candidates,
        // re-run it with more specific patterns. Narrowing is modelled here as
        // keeping the 25 strongest frontmatter matches — NOT the first 25 in
        // directory order, which would make the cap depend on filenames.
        List<Learning> candidates = corpus.stream()
                .filter(l -> !Collections.disjoint(l.frontmatterTerms(), queryTerms))
                .sorted(Comparator.comparingInt((Learning l) -> -score(l, queryTerms)))
                .limit(SHORTLIST_CAP)
                .toList();

        // Step 4: read the frontmatter of the shortlist — small, structured, cheap.
        int chars = candidates.stream().mapToInt(Learning::frontmatterChars).sum();

        // Step 5: read the bodies of only the best few.
        List<Learning> ranked = candidates.stream()
                .sorted(Comparator.comparingInt((Learning l) -> -score(l, queryTerms)))
                .limit(TOP_K)
                .toList();
        chars += ranked.stream().mapToInt(Learning::bodyChars).sum();

        return new Result(ranked, corpus.size(), candidates.size(), ranked.size(), chars);
    }

    /**
     * Relevance score, weighted so curated frontmatter counts more than prose.
     *
     * SIMPLIFICATION, flagged: a real run asks a language model. Keyword overlap
     * is strictly worse, which makes every recall number here a floor.
     */
    static int score(Learning l, Set<String> q) {
        int s = 0;
        for (String w : Text.words(l.title()))      if (q.contains(w)) s += 3;
        for (String tag : l.tags())                 if (q.contains(tag) || q.containsAll(Text.words(tag))) s += 4;
        if (q.contains(l.module()))                 s += 4;
        for (String w : Text.words(l.component()))  if (q.contains(w)) s += 2;
        for (String w : l.bodyTerms())              if (q.contains(w)) s += 1;
        return s;
    }

    /** Did the retrieval surface the doc that would have prevented this mistake? */
    static boolean found(Result r, String goldKey) {
        return r.returned().stream().anyMatch(l -> l.topics().contains(goldKey));
    }

    private Retrieval() {}
}
