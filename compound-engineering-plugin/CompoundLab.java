import java.util.*;
import java.util.stream.*;

/**
 * CompoundLab — a runnable model of the ONE mechanism that makes the
 * compound-engineering plugin "compound": writing a finished piece of work down
 * as a small file with searchable metadata, and finding it again on the next,
 * unrelated task.
 *
 * Everything else in the plugin (33 skills, a pipeline, review personas) is
 * scaffolding around that return arrow. This lab models the arrow itself, so you
 * can measure it instead of believing it.
 *
 * Run:  java CompoundLab.java
 *
 * Modelled faithfully (traceable to the plugin's own files):
 *   - a learning = YAML frontmatter + markdown body, one problem per file
 *   - the frontmatter fields are the real schema
 *     (skills/ce-compound/references/schema.yaml)
 *   - retrieval greps frontmatter FIRST and reads bodies of the shortlist ONLY
 *     (skills/ce-plan/references/agents/learnings-researcher.md, "Search Strategy")
 *   - CONCEPTS.md is consulted before keyword extraction
 *
 * Simplified, and flagged wherever it matters:
 *   - relevance here is keyword overlap; a real run asks a language model. That
 *     makes these recall numbers a FLOOR, not a forecast. The failure modes are
 *     real; the exact percentages belong to this model, not to the plugin.
 *   - "tokens" = characters / 4, the usual rough English estimate.
 */
public class CompoundLab {

    public static void main(String[] args) {
        System.out.println(banner("COMPOUND ENGINEERING — the return arrow, measured"));
        Experiment1.run();
        Experiment2.run();
        Experiment3.run();
        Experiment4.run();
        System.out.println("\nAll four experiments complete. README.md stage 14 says what each one proves.");
    }

    static String banner(String s) {
        String bar = "=".repeat(Math.max(s.length(), 60));
        return "\n" + bar + "\n" + s + "\n" + bar;
    }

    static String section(String s) {
        return "\n--- " + s + " " + "-".repeat(Math.max(0, 56 - s.length()));
    }
}

// ---------------------------------------------------------------------------
// EXPERIMENT 1 — why the pre-filter exists.
// ---------------------------------------------------------------------------

final class Experiment1 {
    static void run() {
        System.out.println(CompoundLab.banner("EXPERIMENT 1 — the cost of remembering"));
        System.out.println("""
                Question: a repo has been compounding for a year. The next task starts.
                How much does it cost to consult everything that was learned?

                Read the 'context' columns: that is what lands in the agent's window
                before it has done any work at all.""");

        Corpus.Topic t = Corpus.TOPICS.get(0);                 // the N+1 topic
        Set<String> q = Text.queryTerms(t.canonicalQuery());

        System.out.printf("%n%-8s | %-28s | %-28s | %s%n",
                "corpus", "read-everything (context)", "grep-first (context)", "saving");
        System.out.println("-".repeat(92));

        for (int size : new int[]{12, 40, 96}) {
            List<Learning> corpus = Corpus.build(size);
            Retrieval.Result naive = Retrieval.readEverything(corpus, q);
            Retrieval.Result smart = Retrieval.grepFirst(corpus, q);
            double saving = 100.0 * (1 - (double) smart.charsIntoContext() / naive.charsIntoContext());
            System.out.printf("%-8d | %,10d tok (%3d files)    | %,10d tok (%3d bodies)   | %5.1f%%%n",
                    corpus.size(), naive.tokensIntoContext(), naive.bodiesRead(),
                    smart.tokensIntoContext(), smart.bodiesRead(), saving);
        }

        List<Learning> big = Corpus.build(96);
        Retrieval.Result smart = Retrieval.grepFirst(big, q);
        System.out.println(CompoundLab.section("what grep-first actually did"));
        System.out.printf("  scanned frontmatter of      : %d files   (zero context cost)%n", smart.filesGrepped());
        System.out.printf("  shortlisted                 : %d files%n", smart.frontmattersRead());
        System.out.printf("  read the body of            : %d files%n", smart.bodiesRead());
        System.out.printf("  found the preventing doc?   : %s%n",
                Retrieval.found(smart, Corpus.goldKey(t, "payments")) ? "YES" : "no");

        System.out.println("""

                Why it matters: read-everything grows with the corpus, so a repo that
                has learned MORE becomes MORE expensive to work in — the exact opposite
                of compounding. Grep-first is close to flat in corpus size, because the
                only part that grows is the part nothing reads.""");
    }
}

// ---------------------------------------------------------------------------
// EXPERIMENT 2 — the retrieval miss, and what fixes it.
// ---------------------------------------------------------------------------

final class Experiment2 {
    static void run() {
        System.out.println(CompoundLab.banner("EXPERIMENT 2 — a learning nobody can find"));
        System.out.println("""
                Question: the learning WAS written. Does the next task find it?

                Each row is the same past learning and the same new task, described two
                ways: once in the words the repo uses, once in the words someone reaches
                for before they know what the cause is.""");

        List<Learning> corpus = Corpus.build(96);
        int canonHits = 0, synHits = 0, groundedHits = 0;

        System.out.printf("%n%-18s | %-9s | %-9s | %s%n", "topic", "house", "outsider", "outsider+CONCEPTS.md");
        System.out.println("-".repeat(70));

        for (Corpus.Topic t : Corpus.TOPICS) {
            String gold = Corpus.goldKey(t, Corpus.moduleMentioned(t));

            boolean canon = Retrieval.found(
                    Retrieval.grepFirst(corpus, Text.queryTerms(t.canonicalQuery())), gold);
            boolean syn = Retrieval.found(
                    Retrieval.grepFirst(corpus, Text.queryTerms(t.synonymQuery())), gold);
            boolean grounded = Retrieval.found(
                    Retrieval.grepFirst(corpus, Concepts.ground(Text.queryTerms(t.synonymQuery()))), gold);

            canonHits += canon ? 1 : 0;
            synHits += syn ? 1 : 0;
            groundedHits += grounded ? 1 : 0;
            System.out.printf("%-18s | %-9s | %-9s | %s%n", t.key(),
                    canon ? "FOUND" : "miss", syn ? "FOUND" : "miss", grounded ? "FOUND" : "miss");
        }

        int n = Corpus.TOPICS.size();
        System.out.println("-".repeat(70));
        System.out.printf("recall             | %d/%d     | %d/%d      | %d/%d%n",
                canonHits, n, synHits, n, groundedHits, n);

        System.out.println("""

                Why it matters: writing the learning down is the cheap half. The doc is
                identical in all three columns — the only thing that changed is whether
                the words in the NEW task overlap the words in its frontmatter. That is
                why retrieval reads CONCEPTS.md first: it maps how a problem gets
                described onto how this repo names things.

                A learning nobody retrieves is indistinguishable from a learning nobody
                wrote — except that it cost time to write and now needs maintaining.

                Note the vocabulary bridge is not a cure: it recovers most of the loss,
                not all of it. Three topics stay unreachable because no alias in this
                model connects the symptom words to the house words.""");
    }
}

// ---------------------------------------------------------------------------
// EXPERIMENT 3 — why one learning per run.
// ---------------------------------------------------------------------------

final class Experiment3 {
    static void run() {
        System.out.println(CompoundLab.banner("EXPERIMENT 3 — the batched learning"));
        System.out.println("""
                Question: a long session solved five different problems. Write five small
                docs, or one doc titled "things we learned this week"?

                Same five learnings, same corpus, same queries. Only the packaging differs.""");

        List<Corpus.Topic> solved = Corpus.TOPICS.subList(0, 5);
        List<Learning> splitCorpus = new ArrayList<>(Corpus.build(96));
        Set<String> foldedPaths = solved.stream()
                .map(t -> Corpus.one(t, Corpus.moduleMentioned(t)).path())
                .collect(Collectors.toSet());
        List<Learning> folded = splitCorpus.stream().filter(l -> foldedPaths.contains(l.path())).toList();

        List<Learning> batchedCorpus = new ArrayList<>(splitCorpus);
        batchedCorpus.removeIf(l -> foldedPaths.contains(l.path()));
        batchedCorpus.add(batchOf(folded));

        System.out.printf("%n%-26s | %-9s | %-16s | %s%n",
                "shape", "found it?", "context read", "on-topic share");
        System.out.println("-".repeat(78));

        for (Corpus.Topic t : solved) {
            Set<String> q = Text.queryTerms(t.canonicalQuery());
            String gold = Corpus.goldKey(t, Corpus.moduleMentioned(t));

            Retrieval.Result s = Retrieval.grepFirst(splitCorpus, q);
            Retrieval.Result b = Retrieval.grepFirst(batchedCorpus, q);

            System.out.printf("%-26s | %-9s | %,8d tok     | %4.1f%%%n",
                    "5 docs / " + t.key(), Retrieval.found(s, gold) ? "yes" : "NO",
                    s.tokensIntoContext(),
                    100.0 * onTopicChars(s, gold) / Math.max(1, s.charsIntoContext()));
            System.out.printf("%-26s | %-9s | %,8d tok     | %4.1f%%%n",
                    "1 doc  / " + t.key(), Retrieval.found(b, gold) ? "yes" : "NO",
                    b.tokensIntoContext(),
                    100.0 * onTopicChars(b, gold) / Math.max(1, b.charsIntoContext()));
        }

        System.out.println("""

                Why it matters — two damages, and the first is worse than it looks.

                1. FOUR OF THE FIVE LEARNINGS BECOME UNREACHABLE. The schema gives a doc
                   exactly ONE module, ONE component, ONE problem_type. A doc covering
                   five problems must pick one set of labels, so it is labelled
                   correctly for one learning and wrongly for the other four — and the
                   pre-filter, which only ever sees labels, skips it. The knowledge is
                   in the repo and cannot be reached.

                2. WHEN IT IS FOUND, IT COSTS MORE AND SAYS LESS. Compare the two
                   outbox-dual-write rows above: context read rises by about half,
                   while the share of that context actually about the task falls by
                   a third. Same knowledge, worse packaging.

                This is why ce-compound refuses to batch: a session that produced five
                learnings gets five sequential runs, not one tidy weekly summary.""");
    }

    /** Fold five learnings into one file: union of tags, sum of bodies, one vague label set. */
    static Learning batchOf(List<Learning> parts) {
        Set<String> tags = new LinkedHashSet<>();
        Set<String> body = new LinkedHashSet<>();
        Set<String> topics = new LinkedHashSet<>();
        int chars = 0;
        for (Learning l : parts) {
            tags.addAll(l.tags());
            body.addAll(l.bodyTerms());
            topics.addAll(l.topics());
            chars += l.bodyChars();
        }
        return new Learning(
                "docs/solutions/best-practices/lessons-this-week.md",
                "Assorted fixes and lessons from this week",
                "best-practices", "payments", "best_practice", "service_layer",
                new ArrayList<>(tags), new ArrayList<>(body), chars,
                List.of("src/main/java/payments/Service.java"), topics);
    }

    /** Of everything read into context, how much came from a doc about THIS problem. */
    static int onTopicChars(Retrieval.Result r, String gold) {
        return r.returned().stream()
                .filter(l -> l.topics().contains(gold))
                .mapToInt(l -> l.bodyChars() / l.topics().size() + l.frontmatterChars())
                .sum();
    }
}

// ---------------------------------------------------------------------------
// EXPERIMENT 4 — the learning that is now a lie.
// ---------------------------------------------------------------------------

final class Experiment4 {
    static void run() {
        System.out.println(CompoundLab.banner("EXPERIMENT 4 — knowledge rots"));
        System.out.println("""
                Question: six months pass. The team renames two modules and deletes one.
                The learnings still sit there, still get retrieved, still sound confident.

                'rotten' here = the doc points at a file path that no longer exists.""");

        List<Learning> corpus = Corpus.build(96);

        // The refactor: payments -> billing, jobs -> workers, reporting deleted.
        Set<String> livingPaths = corpus.stream()
                .flatMap(l -> l.citedPaths().stream())
                .filter(p -> !p.contains("/payments/") && !p.contains("/jobs/") && !p.contains("/reporting/"))
                .collect(Collectors.toSet());

        List<Learning> stale = corpus.stream()
                .filter(l -> l.citedPaths().stream().anyMatch(p -> !livingPaths.contains(p)))
                .toList();

        System.out.printf("%n  corpus size                 : %d learnings%n", corpus.size());
        System.out.printf("  docs citing a dead path     : %d  (%.0f%% of the corpus)%n",
                stale.size(), 100.0 * stale.size() / corpus.size());

        int surfaced = 0, checked = 0;
        for (Corpus.Topic t : Corpus.TOPICS) {
            Retrieval.Result r = Retrieval.grepFirst(corpus, Text.queryTerms(t.canonicalQuery()));
            for (Learning l : r.returned()) {
                checked++;
                if (stale.contains(l)) surfaced++;
            }
        }
        System.out.printf("  docs returned to the agent  : %d%n", checked);
        System.out.printf("  of those, silently rotten   : %d  (%.0f%%)%n",
                surfaced, 100.0 * surfaced / Math.max(1, checked));

        System.out.println(CompoundLab.section("what a mechanical claims check catches"));
        System.out.printf("""
                  Every path a learning cites is checkable against the tree. That is
                  exactly what ce-compound's validate-doc-claims.py does at write time,
                  and what ce-compound-refresh re-runs across the corpus later.

                  A dead path is a QUESTION, not a verdict — a doc may legitimately cite
                  a file that the very fix it documents deleted. The check narrows a
                  %d-doc corpus to %d docs somebody has to adjudicate.
                %n""".formatted(corpus.size(), stale.size()));

        System.out.println("""
                Why it matters: when a cache goes stale it serves a wrong answer once and
                the source of truth corrects it. When a learning goes stale it is trusted
                BECAUSE it was written down, and there is no source of truth behind it to
                do the correcting. So staleness has to be hunted, on a schedule. The loop
                creates that maintenance job, and somebody has to pay for it.""");
    }
}
