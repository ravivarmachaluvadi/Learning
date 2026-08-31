import java.util.*;

/**
 * The corpus — a repo's docs/solutions/ directory after a year of compounding.
 *
 * Built as problem topics crossed with modules, so a "new" task can be given a
 * known-correct answer: the doc that would have prevented it.
 */
final class Corpus {

    /** A problem shape that shows up repeatedly in real backend work. */
    record Topic(
            String key,
            String titleTemplate,
            String category,
            String problemType,
            String component,
            List<String> tags,
            List<String> bodyTerms,
            /** How a NEW task that would hit this same trap is typically described. */
            String canonicalQuery,
            /** The same task, described by someone who does not know the house vocabulary. */
            String synonymQuery
    ) {}

    static final List<String> MODULES = List.of(
            "payments", "catalog", "auth", "jobs", "search", "notifications", "reporting", "checkout");

    static final List<Topic> TOPICS = List.of(
            new Topic("n-plus-one",
                    "N+1 query when listing %s with their line items",
                    "performance-issues", "performance_issue", "data_model",
                    List.of("n-plus-one", "jpa", "lazy-loading", "query-count"),
                    List.of("lazy", "fetch", "join", "hibernate", "repository", "batch"),
                    "listing orders is slow, looks like one query per row in the payments module",
                    "the order page takes forever to load, database seems to be doing lots of little lookups"),

            new Topic("outbox-dual-write",
                    "%s event lost when the broker publish happened outside the transaction",
                    "integration-issues", "integration_issue", "messaging",
                    List.of("outbox", "dual-write", "kafka", "at-least-once"),
                    List.of("transaction", "publish", "relay", "idempotent", "consumer", "commit"),
                    "we need to publish an event after saving in the payments module, dual write",
                    "after we store the record we also have to tell the other service about it"),

            new Topic("tz-truncation",
                    "Dates off by one day in %s reports because the JVM ran in UTC and the DB in IST",
                    "logic-errors", "logic_error", "reporting",
                    List.of("timezone", "date-boundary", "utc", "off-by-one"),
                    List.of("zoneid", "instant", "localdate", "truncate", "offset"),
                    "the reporting module shows dates off by one day, timezone problem",
                    "numbers land on the wrong day in the summary screen"),

            new Topic("flaky-test-clock",
                    "%s tests fail after midnight because the fixture pins a relative date",
                    "test-failures", "test_failure", "testing_framework",
                    List.of("flaky-test", "clock", "fixture", "test-isolation"),
                    List.of("clock", "fixed", "instant", "fixture", "deterministic"),
                    "tests in the jobs module are flaky, they fail overnight, something about the clock",
                    "our checks pass in the morning and break at night for no reason"),

            new Topic("connection-pool",
                    "%s background job exhausts the connection pool under retry storms",
                    "runtime-errors", "runtime_error", "background_job",
                    List.of("connection-pool", "hikari", "retry-storm", "exhaustion"),
                    List.of("pool", "timeout", "retry", "backoff", "leak", "hikari"),
                    "the jobs module exhausts the connection pool when retries pile up",
                    "the service stops responding when it gets busy retrying things"),

            new Topic("cache-invalidation",
                    "Stale price served from cache after a %s update, because invalidation ran before commit",
                    "database-issues", "database_issue", "database",
                    List.of("cache", "invalidation", "stale-read", "race"),
                    List.of("evict", "commit", "ttl", "stale", "race", "aftercommit"),
                    "cache in the catalog module serves a stale price after an update",
                    "customers see the old amount for a while after we change it"),

            new Topic("migration-lock",
                    "Adding a NOT NULL column locked the %s table in production",
                    "database-issues", "database_issue", "database",
                    List.of("migration", "lock", "ddl", "zero-downtime"),
                    List.of("alter", "lock", "backfill", "default", "expand", "contract"),
                    "we need a migration adding a column to a big table in the checkout module",
                    "we want to add a new field to a table that has a lot of rows"),

            new Topic("secret-in-env",
                    "%s credentials silently empty because the env var name differs per environment",
                    "build-errors", "build_error", "infrastructure",
                    List.of("env-var", "config", "secrets", "silent-failure"),
                    List.of("environment", "variable", "profile", "placeholder", "fail-fast"),
                    "auth module credentials come through empty in staging, env var config",
                    "the login keys are blank when we deploy but fine on my machine"),

            new Topic("idempotency-key",
                    "Duplicate %s charge when the client retried a timed-out request",
                    "logic-errors", "logic_error", "payments",
                    List.of("idempotency", "retry", "duplicate", "exactly-once"),
                    List.of("idempotency", "key", "retry", "duplicate", "dedupe"),
                    "payments module creates a duplicate charge when the client retries",
                    "people got billed twice when their connection dropped"),

            new Topic("index-missing",
                    "%s search times out because the filter column has no index",
                    "performance-issues", "performance_issue", "database",
                    List.of("index", "slow-query", "explain", "seq-scan"),
                    List.of("index", "explain", "scan", "cardinality", "analyze"),
                    "search module queries time out, probably a missing index",
                    "finding things is really slow when there is a lot of data"),

            new Topic("api-contract-drift",
                    "%s consumer broke when an optional field became required",
                    "integration-issues", "integration_issue", "api_layer",
                    List.of("api-contract", "versioning", "breaking-change", "schema"),
                    List.of("contract", "optional", "required", "version", "deprecate"),
                    "notifications module consumer broke on an api contract change",
                    "the other team changed their response and our side fell over"),

            new Topic("log-pii",
                    "%s request logs contained card data because the DTO had a default toString",
                    "security-issues", "security_issue", "observability",
                    List.of("pii", "logging", "tostring", "redaction"),
                    List.of("redact", "tostring", "mask", "logger", "sensitive"),
                    "checkout module logs are leaking pii through toString",
                    "personal details are showing up in our log files"));

    /** Build a corpus of roughly `size` learnings: topics crossed with modules. */
    static List<Learning> build(int size) {
        List<Learning> out = new ArrayList<>();
        outer:
        for (String module : MODULES) {
            for (Topic t : TOPICS) {
                if (out.size() >= size) break outer;
                out.add(one(t, module));
            }
        }
        return out;
    }

    static Learning one(Topic t, String module) {
        List<String> tags = new ArrayList<>(t.tags());
        tags.add(module);
        return new Learning(
                "docs/solutions/%s/%s-%s.md".formatted(t.category(), module, t.key()),
                t.titleTemplate().formatted(module),
                t.category(), module, t.problemType(), t.component(),
                tags, t.bodyTerms(),
                2600,                                        // a typical short learning doc
                List.of("src/main/java/" + module + "/Service.java",
                        "src/test/java/" + module + "/ServiceTest.java"),
                Set.of(t.key() + "@" + module));
    }

    /** The doc that WOULD have prevented this task's mistake, if it gets found. */
    static String goldKey(Topic t, String module) { return t.key() + "@" + module; }

    /** Each canonical query names the module it is about; pull it back out. */
    static String moduleMentioned(Topic t) {
        return MODULES.stream()
                .filter(m -> t.canonicalQuery().contains(m))
                .findFirst().orElse("payments");
    }

    private Corpus() {}
}
