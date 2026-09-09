import java.util.*;

/**
 * The whole point of the lab in one table: run every anomaly at every isolation
 * level against a real database, and print what ACTUALLY happens.
 *
 *   java -cp ../lib/h2.jar src/Matrix.java              # in-memory H2
 *   java -cp ../lib/h2.jar:. -Ddb.url=jdbc:postgresql://... src/Matrix.java
 *
 * Verdicts:
 *   ALLOWED    the anomaly happened; both transactions committed happily
 *   safe       the anomaly did not happen, and nobody had to wait
 *   safe(wait) prevented by making one transaction WAIT for the other
 *   safe(err)  prevented by REFUSING one transaction — your code must retry
 */
public class Matrix {

    public static void main(String[] args) throws Exception {
        System.out.println("\ndatabase : " + Db.product());
        System.out.println("url      : " + Db.url + "\n");

        List<Scenario> scenarios = Anomalies.all();
        Map<String, Map<Integer, String>> table = new LinkedHashMap<>();
        Map<String, Map<Integer, Runner.Result>> detail = new LinkedHashMap<>();

        for (Scenario sc : scenarios) {
            table.put(sc.key(), new LinkedHashMap<>());
            detail.put(sc.key(), new LinkedHashMap<>());
            for (int lvl : Db.LEVELS) {
                Runner.Result r;
                try { r = new Runner(lvl).play(sc); }
                catch (Exception e) { table.get(sc.key()).put(lvl, "n/a"); continue; }
                table.get(sc.key()).put(lvl, Runner.verdict(sc.happened(r), r));
                detail.get(sc.key()).put(lvl, r);
            }
        }

        int w = scenarios.stream().mapToInt(s -> s.title().length()).max().orElse(20) + 2;
        System.out.printf("%-" + w + "s", "anomaly");
        for (int lvl : Db.LEVELS) System.out.printf("%-13s", Db.shortName(lvl));
        System.out.println();
        System.out.println("-".repeat(w + 13 * Db.LEVELS.length));
        for (Scenario sc : scenarios) {
            System.out.printf("%-" + w + "s", sc.title());
            for (int lvl : Db.LEVELS) System.out.printf("%-13s", table.get(sc.key()).get(lvl));
            System.out.println();
        }
        System.out.println("""

            RU = read uncommitted   RC = read committed   RR = repeatable read
            SNAP = snapshot (H2 only)   SER = serializable

            ALLOWED    the bad thing happened and nobody complained
            safe       prevented, with no waiting and no error
            safe(wait) prevented by making one transaction wait for the other
            safe(err)  prevented by refusing one transaction — YOUR CODE MUST RETRY
            """);

        if (args.length > 0) {
            String want = args[0];
            for (Scenario sc : scenarios) {
                if (!sc.key().equals(want) && !want.equals("all")) continue;
                System.out.println("\n" + "=".repeat(74));
                System.out.println(sc.title().toUpperCase());
                System.out.println("=".repeat(74));
                System.out.println("  what is happening : " + sc.story());
                System.out.println("  going wrong means : " + sc.badOutcome() + "\n");
                for (int lvl : Db.LEVELS) {
                    Runner.Result r = detail.get(sc.key()).get(lvl);
                    if (r == null) continue;
                    System.out.println("  --- " + Db.name(lvl) + " ---");
                    for (Runner.Outcome o : r.outcomes) System.out.println("      " + o.render());
                    System.out.println("      final state: " + r.finalState
                        + "   ==> " + (sc.happened(r) ? "ANOMALY HAPPENED" : "prevented"));
                    System.out.println();
                }
            }
        } else {
            System.out.println("  Add an anomaly key for the step-by-step log, e.g.:");
            System.out.print("    ");
            System.out.println(String.join("  ", scenarios.stream().map(Scenario::key).toList()));
            System.out.println("  or `all` for every one.\n");
        }
    }

}
