import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * EXERCISES.md rung 6 (Debug) — an LSM with ONE planted bug.
 *
 * Everything here is a copy of the real compact() from Lsm.java with a single
 * expression changed. The engine looks fine: writes work, reads work, files
 * merge, disk shrinks. Something is nonetheless deeply wrong.
 *
 * Your job (exercise 6.1): find it by running the test suite against this
 * class, name which test fails, which README invariant it violates, and what a
 * user of a real database would observe.
 *
 *     javac -d out src/*.java tests/*.java experiments/*.java   # once
 *     java -cp out ExpDebugBroken
 *
 * Do not read past the class if you want the hunt to be fair.
 */
public class BrokenLsm extends Lsm {

    public BrokenLsm(Path dirpath) throws IOException {
        super(dirpath);
    }

    public BrokenLsm(Path dirpath, int memtableLimit, int compactAt) throws IOException {
        super(dirpath, memtableLimit, compactAt);
    }

    @Override
    public void compact() throws IOException {
        if (tables.isEmpty()) {
            return;
        }
        List<List<Tagged>> sources = new ArrayList<>();
        for (SSTable t : tables) {
            sources.add(tagged(t.entries(), t.seq));
        }
        List<Tagged> merged = kWayMerge(sources);
        Path path = dir.resolve(String.format("sstable_%04d.txt", nextSeq));
        try (BufferedWriter f = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            int i = 0;
            while (i < merged.size()) {
                String k = merged.get(i).key();
                int j = i;
                while (j < merged.size() && merged.get(j).key().equals(k)) {
                    j++;
                }
                Tagged winner = merged.get(i);
                for (int x = i + 1; x < j; x++) {
                    if (merged.get(x).negPrio() > winner.negPrio()) {
                        winner = merged.get(x);
                    }
                }
                if (!TOMBSTONE.equals(winner.value())) {
                    f.write(k + "\t" + winner.value() + "\n");
                }
                i = j;
            }
        }
        bytesWritten += Files.size(path);
        List<SSTable> old = new ArrayList<>(tables);
        tables.clear();
        tables.add(new SSTable(path, nextSeq, bitsPerKey));
        nextSeq++;
        compactions++;
        for (SSTable t : old) {
            Files.delete(t.path);
        }
    }
}
