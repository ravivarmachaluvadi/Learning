import java.util.*;

/**
 * CONCEPTS.md — the project's shared vocabulary file.
 *
 * The retrieval subagent reads it BEFORE extracting keywords, precisely so that a
 * task described in outsider words can still reach docs written in house words
 * (skills/ce-plan/references/agents/learnings-researcher.md, step 0).
 *
 * SIMPLIFICATION, flagged: the real file is prose definitions that a model reads
 * and reasons over. Here it is a flat synonym map, which is strictly weaker — so
 * Experiment 2's grounded column is a floor on what the real thing recovers, not
 * a ceiling.
 */
final class Concepts {

    static final Map<String, List<String>> ALIASES = Map.ofEntries(
            Map.entry("slow", List.of("performance", "timeout")),
            Map.entry("forever", List.of("performance", "slow")),
            Map.entry("load", List.of("query")),
            Map.entry("lookups", List.of("query", "n-plus-one")),
            Map.entry("little", List.of("n-plus-one")),
            Map.entry("page", List.of("listing", "catalog")),
            Map.entry("order", List.of("payments", "checkout")),
            Map.entry("store", List.of("transaction", "database")),
            Map.entry("record", List.of("data", "database")),
            Map.entry("tell", List.of("publish", "event")),
            Map.entry("service", List.of("messaging", "integration")),
            Map.entry("billed", List.of("payments", "charge")),
            Map.entry("twice", List.of("duplicate", "idempotency")),
            Map.entry("dropped", List.of("retry", "timeout")),
            Map.entry("connection", List.of("retry", "pool")),
            Map.entry("amount", List.of("price", "cache")),
            Map.entry("old", List.of("stale", "cache")),
            Map.entry("customers", List.of("catalog", "checkout")),
            Map.entry("screen", List.of("reporting", "ui")),
            Map.entry("summary", List.of("reporting")),
            Map.entry("day", List.of("timezone", "date")),
            Map.entry("wrong", List.of("logic_error")),
            Map.entry("night", List.of("clock", "flaky-test")),
            Map.entry("morning", List.of("clock")),
            Map.entry("checks", List.of("test", "test_failure")),
            Map.entry("break", List.of("failure")),
            Map.entry("busy", List.of("pool", "retry-storm")),
            Map.entry("responding", List.of("runtime_error")),
            Map.entry("rows", List.of("migration", "table")),
            Map.entry("field", List.of("column", "migration")),
            Map.entry("add", List.of("migration")),
            Map.entry("blank", List.of("env-var", "config")),
            Map.entry("keys", List.of("secrets", "credentials")),
            Map.entry("login", List.of("auth")),
            Map.entry("deploy", List.of("infrastructure", "config")),
            Map.entry("finding", List.of("search", "index")),
            Map.entry("data", List.of("index")),
            Map.entry("response", List.of("api-contract", "api_layer")),
            Map.entry("changed", List.of("breaking-change")),
            Map.entry("team", List.of("integration")),
            Map.entry("personal", List.of("pii")),
            Map.entry("details", List.of("pii")),
            Map.entry("log", List.of("logging", "observability")),
            Map.entry("files", List.of("logging")));

    /** Expand the words in a task description into the vocabulary the corpus uses. */
    static Set<String> ground(Set<String> raw) {
        Set<String> out = new LinkedHashSet<>(raw);
        for (String w : raw) out.addAll(ALIASES.getOrDefault(w, List.of()));
        return out;
    }

    private Concepts() {}
}
