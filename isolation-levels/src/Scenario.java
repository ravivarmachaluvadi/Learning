import java.util.*;
import java.util.function.*;

/**
 * One anomaly, described the way a bug report would describe it: what two people
 * were doing, what should have happened, and the single question whose answer
 * tells you whether the database let the bad thing through.
 */
record Scenario(
    String key,
    String title,
    String story,               // one plain sentence: what the two clients are doing
    String badOutcome,          // one plain sentence: what going wrong looks like
    String[] ddl,               // schema + seed, re-run before every scenario
    List<Step> steps,
    String finalQuery,          // one value, read after both transactions are done
    Predicate<Runner.Result> occurred
) {
    /**
     * An anomaly is a property of a history in which everything COMMITTED. If the
     * database refused one of the transactions, it did not corrupt anything — it
     * declined, and said so. That is the guarantee working, not failing.
     */
    boolean happened(Runner.Result r) { return r.bothCommitted() && occurred.test(r); }
}
