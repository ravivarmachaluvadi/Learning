import java.util.*;

/** Word handling. A real agent uses a language model here; this is plain overlap. */
final class Text {

    static final Set<String> STOP = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "we", "our", "us", "it", "its", "and", "or",
            "but", "to", "of", "in", "on", "at", "for", "with", "that", "this", "these", "those",
            "when", "after", "before", "if", "so", "as", "by", "from", "they", "them", "their",
            "there", "here", "have", "has", "had", "do", "does", "did", "be", "been", "being",
            "some", "lots", "really", "very", "just", "also", "not", "no", "my", "me", "you", "your",
            "get", "gets", "got", "seems", "looks", "like", "about", "one", "up", "out", "into");

    static List<String> words(String s) {
        return Arrays.stream(s.toLowerCase().split("[^a-z0-9+]+"))
                .filter(w -> w.length() > 1 && !STOP.contains(w))
                .toList();
    }

    /** The keywords extracted from a description of the work about to start. */
    static Set<String> queryTerms(String activity) {
        return new LinkedHashSet<>(words(activity));
    }

    private Text() {}
}
