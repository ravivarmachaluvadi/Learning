import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Experiment 3 — what bloom filters are worth (README §10.5, F-L4).
 *
 * Build an LSM with 8 SSTables, then issue 2,000 gets for keys that were NEVER
 * written — the worst kind of read, because absence can't stop early. Compare
 * disk probes with blooms off vs on, and measure the false-positive rate.
 *
 * Run:  javac -d out src/*.java tests/*.java experiments/*.java   # once
 *       java -cp out Exp3Bloom
 */
public class Exp3Bloom {

    static final int MISSING = 2_000;

    public static void main(String[] args) throws Exception {
        Path d = Files.createTempDirectory("lsm-exp3-");

        Lsm db = new Lsm(d, 250, 999, true, 10);
        for (int i = 0; i < 2_000; i++) db.put(String.format("present%05d", i), "v");
        int tables = db.tableCount();
        db.close();

        long probesOff = 0, probesOn = 0, skipsOn = 0;
        for (boolean useBloom : new boolean[]{false, true}) {
            Lsm eng = new Lsm(d, useBloom);
            eng.resetCounters();
            for (int i = 0; i < MISSING; i++) eng.get(String.format("missing%06d", i));
            if (useBloom) {
                probesOn = eng.fileProbes;
                skipsOn = eng.bloomSkips;
            } else {
                probesOff = eng.fileProbes;
            }
            eng.close();
        }

        double fpRate = (double) probesOn / (probesOn + skipsOn);

        System.out.printf("%d SSTables on disk; %,d gets for keys that DON'T exist%n%n", tables, MISSING);
        System.out.printf("  blooms OFF: %,7d disk probes  (%.1f per get — every table, every time)%n",
                probesOff, (double) probesOff / MISSING);
        System.out.printf("  blooms ON : %,7d disk probes  (%.2f per get)  "
                        + "+ %,d free 'definitely not here' skips%n",
                probesOn, (double) probesOn / MISSING, skipsOn);
        System.out.printf("  false-positive rate: %.2f%%  "
                + "(probes that found nothing; ~1%% is the 10-bits/key design point)%n", fpRate * 100);
        System.out.printf("  disk I/O eliminated: %.1f%%%n", (1 - (double) probesOn / probesOff) * 100);
        System.out.println(
                "\nLesson: a few KB of RAM per table turns the worst read pattern (absent\n"
                + "keys) from probe-every-file into nearly free. It can never wrongly skip —\n"
                + "only occasionally probe in vain. The cheapest win in storage (F-L4).");
        deleteTree(d);
    }

    static void deleteTree(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
