import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Experiment 2 — why search engines exist.
 *
 * Claims under test (README §10 trick 5, incident 2):
 *   1. LIKE '%word%' must scan every row, every query — cost grows with the table.
 *   2. An inverted index (word -> set of doc ids) answers the same question in
 *      microseconds, because it intersects two small sets instead of scanning.
 *   3. Exact-term lookup is NOT search: the typo/prefix 'locomotiv' needs analyzer
 *      machinery (here: a prefix range over sorted terms) — showing where a real
 *      engine's relevance pipeline attaches.
 *
 * Row store: H2, in-memory; the index is a plain HashMap. Run (from the lab directory):
 *   java --enable-native-access=ALL-UNNAMED -cp ../lib/duckdb_jdbc.jar:../lib/h2.jar experiments/Exp2InvertedIndex.java
 */
public class Exp2InvertedIndex {

    static final int N_DOCS = 200_000;
    // NOTE: 'locomotive' is deliberately NOT in the pool — only the planted doc
    // below contains it, so both query strategies should find exactly one book.
    static final String[] WORDS = (
            "the blue red green train station mystery murder garden midnight express "
          + "shadow river mountain letter secret doctor winter summer island bridge "
          + "clock library orient detective harbor lantern whisper").split(" ");
    static final int PLANTED = 123_456;

    static PreparedStatement likeStmt;
    static long sink;

    public static void main(String[] args) throws Exception {
        System.out.printf("Building %,d book titles...%n", N_DOCS);
        // Fixed seed, so every run of this program sees the same corpus. (Java's
        // PRNG is not Python's, so the random titles differ from the original
        // script's — nothing here depends on which words land where, only on the
        // one planted document below.)
        Random rnd = new Random(42);
        List<String> pool = new ArrayList<>(Arrays.asList(WORDS));
        String[] docs = new String[N_DOCS];
        for (int i = 0; i < N_DOCS; i++) {
            Collections.shuffle(pool, rnd);              // draw 6 DISTINCT words
            docs[i] = String.join(" ", pool.subList(0, 6));
        }
        docs[PLANTED] = "the blue locomotive";           // the book the user is looking for

        // Same two H2 result caches disabled as in experiment 1: with them on, the
        // repeated LIKE below would be answered from a memo, not from the table.
        Connection db = DriverManager.getConnection(
                "jdbc:h2:mem:exp2;QUERY_CACHE_SIZE=0;OPTIMIZE_REUSE_RESULTS=0", "sa", "");
        try (Statement s = db.createStatement()) {
            s.execute("CREATE TABLE books (id INT PRIMARY KEY, title VARCHAR)");
        }
        try (PreparedStatement ins = db.prepareStatement("INSERT INTO books VALUES (?, ?)")) {
            for (int i = 0; i < N_DOCS; i++) {
                ins.setInt(1, i);
                ins.setString(2, docs[i]);
                ins.addBatch();
                if (i % 20_000 == 0) ins.executeBatch();
            }
            ins.executeBatch();
        }

        // --- The database way: LIKE substring scan --------------------------------
        likeStmt = db.prepareStatement("SELECT id FROM books WHERE title LIKE ?");
        for (int i = 0; i < 2; i++) likeQuery("%blue%locomotive%");   // JIT warm-up
        double tLike = Double.MAX_VALUE;
        List<Integer> likeHits = List.of();
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            likeHits = likeQuery("%blue%locomotive%");
            tLike = Math.min(tLike, (System.nanoTime() - t0) / 1e9);
        }

        // --- The search-engine way: an inverted index -----------------------------
        // Build once at write time (this is the cost search engines pay on indexing):
        long t0 = System.nanoTime();
        Map<String, Set<Integer>> inverted = new HashMap<>();
        for (int docId = 0; docId < N_DOCS; docId++) {
            for (String term : docs[docId].split(" ")) {  // 'tokenize' — the simplest possible analyzer
                inverted.computeIfAbsent(term, k -> new HashSet<>()).add(docId);
            }
        }
        String[] sortedTerms = inverted.keySet().toArray(new String[0]);
        Arrays.sort(sortedTerms);                         // enables prefix matching below
        double tBuild = (System.nanoTime() - t0) / 1e9;

        Set<Integer> invHits = termQuery(inverted, "blue", "locomotive");
        // The Python original averaged 1,000 passes. One intersection here costs a few
        // hundred NANOseconds, which is below the clock's useful resolution, so we
        // average a much larger batch and take the best batch of five.
        final int ITERATIONS = 100_000;
        for (int i = 0; i < ITERATIONS; i++) sink += termQuery(inverted, "blue", "locomotive").size();
        double tInv = Double.MAX_VALUE;
        for (int rep = 0; rep < 5; rep++) {
            long t1 = System.nanoTime();
            for (int i = 0; i < ITERATIONS; i++) sink += termQuery(inverted, "blue", "locomotive").size();
            tInv = Math.min(tInv, (System.nanoTime() - t1) / 1e9 / ITERATIONS);
        }

        System.out.printf("%n  LIKE '%%blue%%locomotive%%' (scans all %,d rows)  %9.3f ms%n", N_DOCS, tLike * 1000);
        System.out.printf("  inverted-index intersection of 2 posting sets       %9.6f ms%n", tInv * 1000);
        System.out.printf("  (index build cost, paid once at write time:         %9.1f ms)%n", tBuild * 1000);
        System.out.printf("  both found exactly the planted book: %s%n",
                new HashSet<>(likeHits).equals(invHits) && invHits.equals(Set.of(PLANTED)));
        System.out.printf("  speedup per query: %,.0fx%n", tLike / tInv);

        // --- Where 'search' becomes more than lookup ------------------------------
        System.out.println("\nUser now types the prefix/typo 'locomotiv':");
        System.out.printf("  LIKE '%%locomotiv%%' finds it (substring luck): %s%n",
                !likeQuery("%locomotiv%").isEmpty());
        System.out.printf("  exact inverted lookup finds it:               %s%n",
                !termQuery(inverted, "blue", "locomotiv").isEmpty());

        Set<Integer> hits = new HashSet<>(termQuery(inverted, "blue"));
        hits.retainAll(prefixExpand(inverted, sortedTerms, "locomotiv"));
        System.out.printf("  prefix expansion over sorted terms finds it:  %s (doc id %s)%n",
                hits.equals(Set.of(PLANTED)), new ArrayList<>(new TreeSet<>(hits)));

        System.out.println(
                "\nLesson: the speed comes from the inverted index; the QUALITY (prefixes,\n"
              + "typos, stemming, ranking) comes from machinery bolted onto it. A real engine\n"
              + "(Lucene/Elasticsearch) is this dict — persistent, compressed, scored, sharded.");
    }

    static List<Integer> likeQuery(String pattern) throws SQLException {
        likeStmt.setString(1, pattern);
        List<Integer> out = new ArrayList<>();
        try (ResultSet r = likeStmt.executeQuery()) {
            while (r.next()) out.add(r.getInt(1));
        }
        return out;
    }

    // Query = intersect the posting sets of each term. Cost ~ size of the smallest
    // set, INDEPENDENT of total corpus size — this is the inverted index's bet.
    static Set<Integer> termQuery(Map<String, Set<Integer>> inverted, String... terms) {
        if (terms.length == 0) return Set.of();
        List<Set<Integer>> sets = new ArrayList<>(terms.length);
        for (String t : terms) sets.add(inverted.getOrDefault(t, Set.of()));
        sets.sort(Comparator.comparingInt(Set::size));    // smallest set first: it bounds the work
        Set<Integer> out = new HashSet<>(sets.get(0));
        for (int i = 1; i < sets.size(); i++) out.retainAll(sets.get(i));
        return out;
    }

    // Fix: expand 'locomotiv' to every indexed term starting with it (prefix range
    // over the sorted term list) — a toy version of what analyzers/fuzzy matching do.
    static Set<Integer> prefixExpand(Map<String, Set<Integer>> inverted, String[] sortedTerms, String prefix) {
        int i = bisectLeft(sortedTerms, prefix);
        Set<Integer> out = new HashSet<>();
        while (i < sortedTerms.length && sortedTerms[i].startsWith(prefix)) {
            out.addAll(inverted.get(sortedTerms[i]));
            i++;
        }
        return out;
    }

    /** Python's bisect.bisect_left: index of the first term >= key. */
    static int bisectLeft(String[] a, String key) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid].compareTo(key) < 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
