import java.util.*;

/**
 * One documented learning — i.e. one file under docs/solutions/<category>/<name>.md.
 *
 * INVARIANT (ce-compound, "One learning per run"): exactly one solved problem per
 * document. Experiment 3 in CompoundLab measures what breaks when this is violated.
 *
 * The field names are the real frontmatter schema from the plugin
 * (skills/ce-compound/references/schema.yaml). Note that module, component and
 * problem_type are each SINGLE-VALUED — that fact does a lot of work in Experiment 3.
 */
record Learning(
        String path,
        String title,
        String category,
        String module,
        String problemType,
        String component,
        List<String> tags,
        List<String> bodyTerms,   // the vocabulary that appears in the prose
        int bodyChars,
        List<String> citedPaths,  // file paths the doc points at — these can rot
        Set<String> topics        // which problem topic(s) this doc covers; >1 means it was batched
) {

    /** The frontmatter block, as text. This is the only part the pre-filter ever scans. */
    String frontmatter() {
        return """
               ---
               title: %s
               category: %s
               module: %s
               problem_type: %s
               component: %s
               tags: [%s]
               ---
               """.formatted(title, category, module, problemType, component, String.join(", ", tags));
    }

    int frontmatterChars() { return frontmatter().length(); }

    int totalChars() { return frontmatterChars() + bodyChars; }

    /** Every word a grep over the frontmatter could possibly match. */
    Set<String> frontmatterTerms() {
        Set<String> t = new LinkedHashSet<>();
        t.addAll(Text.words(title));
        t.addAll(Text.words(module));
        t.addAll(Text.words(component));
        t.addAll(Text.words(problemType));
        t.addAll(Text.words(category));
        tags.forEach(tag -> t.addAll(Text.words(tag)));
        return t;
    }
}
