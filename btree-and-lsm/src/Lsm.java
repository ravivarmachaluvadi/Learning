import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * A teaching LSM-tree engine (README §10.3–10.7) — with REAL files.
 *
 * Point it at a directory and `ls` it while you work: you'll see `wal.log`
 * grow, `sstable_N.txt` files appear at each flush, and compaction replace
 * them. Toy thresholds (flush at 6 keys, compact at 4 tables) so everything
 * happens early; real engines use ~64 MB memtables — the logic is identical.
 *
 * I/O counters: `bytesWritten` (write amp numerator), `fileProbes` and
 * `bloomSkips` (read amp), `diskBytes()` vs live data (space amp).
 */
public class Lsm {

    /**
     * §10.4: immutable files can't be edited, so a delete is a WRITE of this
     * marker; newest-wins makes older values invisible until compaction
     * physically drops them.
     */
    public static final String TOMBSTONE = "<TOMBSTONE>";

    /** One (key, value) pair as it lives in a table or the memtable. */
    public record Entry(String key, String value) {}

    /**
     * A (key, value) pair carrying its source's priority NEGATED, so that
     * sorting by (key, negPrio) puts same-key entries newest-first.
     */
    public record Tagged(String key, int negPrio, String value) {}

    /**
     * §10.5: answers 'definitely not here' or 'maybe here'. It can never
     * wrongly skip a file (no false negatives) — only occasionally probe in
     * vain (~1% false positives at ~10 bits/key).
     */
    public static final class BloomFilter {
        final int m;
        final int nHashes;
        final byte[] bits;

        BloomFilter(int nKeys, int bitsPerKey) {
            this(nKeys, bitsPerKey, 3);
        }

        BloomFilter(int nKeys, int bitsPerKey, int nHashes) {
            this.m = Math.max(8, nKeys * bitsPerKey);
            this.nHashes = nHashes;
            this.bits = new byte[(m + 7) / 8];
        }

        /** nHashes independent bit positions for one key, salted so they differ. */
        private int position(int salt, String key) {
            byte[] digest = sha256((salt + ":" + key).getBytes(StandardCharsets.UTF_8));
            long h = 0;
            for (int i = 0; i < 8; i++) {          // first 8 bytes, big-endian, unsigned
                h = (h << 8) | (digest[i] & 0xFFL);
            }
            return (int) Long.remainderUnsigned(h, m);
        }

        public void add(String key) {
            for (int salt = 0; salt < nHashes; salt++) {
                int p = position(salt, key);
                bits[p / 8] |= (byte) (1 << (p % 8));
            }
        }

        public boolean maybeContains(String key) {
            for (int salt = 0; salt < nHashes; salt++) {
                int p = position(salt, key);
                if ((bits[p / 8] & (1 << (p % 8))) == 0) {
                    return false;   // one clear bit is proof: DEFINITELY not here
                }
            }
            return true;            // every bit set: maybe here — go probe the file
        }

        private static byte[] sha256(byte[] input) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(input);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * One immutable sorted file + its two in-RAM accelerators
     * (bloom filter, sparse index). Never modified after creation.
     */
    public static final class SSTable {
        public final Path path;
        public final int seq;                                       // higher seq = newer = wins (§10.3)
        final List<String> sparseKeys = new ArrayList<>();           // every Nth key ...
        final List<Long> sparseOffsets = new ArrayList<>();          // ... and its byte offset: the sparse index
        public final int count;
        public final BloomFilter bloom;

        SSTable(Path path, int seq, int bitsPerKey) throws IOException {
            this(path, seq, 4, bitsPerKey);
        }

        SSTable(Path path, int seq, int sparseEvery, int bitsPerKey) throws IOException {
            this.path = path;
            this.seq = seq;
            List<String> keys = new ArrayList<>();
            long offset = 0;
            try (BufferedReader f = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                int i = 0;
                while ((line = f.readLine()) != null) {
                    String key = line.substring(0, line.indexOf('\t'));
                    keys.add(key);
                    if (i % sparseEvery == 0) {
                        sparseKeys.add(key);
                        sparseOffsets.add(offset);
                    }
                    offset += line.getBytes(StandardCharsets.UTF_8).length + 1;   // +1 for the '\n'
                    i++;
                }
            }
            this.count = keys.size();
            this.bloom = new BloomFilter(count, bitsPerKey);
            for (String k : keys) {
                bloom.add(k);
            }
        }

        /** One seek (via the sparse index) + a short forward scan. */
        public String get(String key) throws IOException {
            int i = BTree.bisectRight(sparseKeys, key) - 1;
            if (i < 0) {
                return null;
            }
            try (RandomAccessFile f = new RandomAccessFile(path.toFile(), "r")) {
                f.seek(sparseOffsets.get(i));       // the sparse index buys us this ONE seek
                String raw;
                while ((raw = f.readLine()) != null) {
                    String line = decode(raw);
                    int tab = line.indexOf('\t');
                    String k = line.substring(0, tab);
                    if (k.equals(key)) {
                        return line.substring(tab + 1);
                    }
                    if (k.compareTo(key) > 0) {     // sorted file: passed it — not here
                        return null;
                    }
                }
            }
            return null;
        }

        public List<Entry> entries() throws IOException {
            List<Entry> out = new ArrayList<>();
            try (BufferedReader f = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                while ((line = f.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    out.add(new Entry(line.substring(0, tab), line.substring(tab + 1)));
                }
            }
            return out;
        }

        /** RandomAccessFile.readLine() gives one char per byte; re-read those bytes as UTF-8. */
        private static String decode(String raw) {
            return new String(raw.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        }
    }

    // ---------- engine state ----------

    public final Path dir;
    public final int memtableLimit;
    public final int compactAt;
    public final boolean useBloom;
    public final int bitsPerKey;
    public TreeMap<String, String> mem = new TreeMap<>();   // the memtable: sorted as papers land
    public final List<SSTable> tables = new ArrayList<>();  // oldest → newest
    public int nextSeq = 0;
    // counters
    public long bytesWritten = 0;
    public long userBytes = 0;
    public long fileProbes = 0;
    public long bloomSkips = 0;
    public long recordsScanned = 0;
    public long flushes = 0;
    public long compactions = 0;

    private final Path walPath;
    private BufferedWriter wal;

    public Lsm(Path dirpath) throws IOException {
        this(dirpath, 6, 4, true, 10);
    }

    public Lsm(Path dirpath, boolean useBloom) throws IOException {
        this(dirpath, 6, 4, useBloom, 10);
    }

    public Lsm(Path dirpath, int memtableLimit, int compactAt) throws IOException {
        this(dirpath, memtableLimit, compactAt, true, 10);
    }

    public Lsm(Path dirpath, int memtableLimit, int compactAt, boolean useBloom, int bitsPerKey)
            throws IOException {
        this.dir = dirpath;
        Files.createDirectories(dirpath);
        this.memtableLimit = memtableLimit;
        this.compactAt = compactAt;
        this.useBloom = useBloom;
        this.bitsPerKey = bitsPerKey;
        // RECOVERY (§10.7): load surviving SSTables, then replay the WAL —
        // this is what "restart after a crash" means. Acknowledge nothing
        // the log can't replay.
        List<String> names = new ArrayList<>();
        try (Stream<Path> listing = Files.list(dirpath)) {
            listing.forEach(p -> names.add(p.getFileName().toString()));
        }
        names.sort(Comparator.naturalOrder());
        for (String name : names) {
            if (name.startsWith("sstable_")) {
                int seq = Integer.parseInt(name.split("_")[1].split("\\.")[0]);
                tables.add(new SSTable(dirpath.resolve(name), seq, bitsPerKey));
                nextSeq = Math.max(nextSeq, seq + 1);
            }
        }
        tables.sort(Comparator.comparingInt(t -> t.seq));
        this.walPath = dirpath.resolve("wal.log");
        if (Files.exists(walPath)) {
            try (BufferedReader f = Files.newBufferedReader(walPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = f.readLine()) != null) {
                    String[] parts = line.split("\t", 3);
                    mem.put(parts[1], parts[0].equals("P") ? parts[2] : TOMBSTONE);
                }
            }
        }
        this.wal = Files.newBufferedWriter(walPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // ---------- write path ----------

    public void put(String key, String value) throws IOException {
        log("P", key, value);
        mem.put(key, value);            // newest-wins: the memtable IS newest
        userBytes += key.length() + value.length();
        if (mem.size() >= memtableLimit) {
            flush();
        }
    }

    /**
     * §10.4: a delete is a write. The old value (possibly in an
     * immutable file) stays on disk until compaction.
     */
    public void delete(String key) throws IOException {
        log("D", key, TOMBSTONE);
        mem.put(key, TOMBSTONE);
        if (mem.size() >= memtableLimit) {
            flush();
        }
    }

    private void log(String op, String key, String value) throws IOException {
        // INVARIANT (WAL-before-ack): the write hits the append-only log
        // BEFORE we acknowledge; a crash loses only what the log can replay.
        String line = op + "\t" + key + "\t" + value + "\n";
        wal.write(line);
        wal.flush();
        bytesWritten += line.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * §10.3: the frozen memtable becomes an immutable SSTable in ONE
     * sequential write — this is how random-order puts become sequential
     * disk I/O.
     */
    private void flush() throws IOException {
        Path path = dir.resolve(String.format("sstable_%04d.txt", nextSeq));
        try (BufferedWriter f = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : mem.entrySet()) {   // TreeMap: already in key order
                f.write(e.getKey() + "\t" + e.getValue() + "\n");
            }
        }
        bytesWritten += Files.size(path);
        tables.add(new SSTable(path, nextSeq, bitsPerKey));
        nextSeq++;
        flushes++;
        mem = new TreeMap<>();
        // the flushed data is durable in the SSTable; its WAL entries are
        // no longer needed — truncate so the log stays short (§10.7)
        wal.close();
        wal = Files.newBufferedWriter(walPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        if (tables.size() >= compactAt) {
            compact();
        }
    }

    // ---------- read path ----------

    public String get(String key) throws IOException {
        // INVARIANT (newest wins): memtable first, then tables newest→oldest;
        // the FIRST hit is the answer. This order DEFINES the current value.
        if (mem.containsKey(key)) {
            String v = mem.get(key);
            return TOMBSTONE.equals(v) ? null : v;
        }
        for (int i = tables.size() - 1; i >= 0; i--) {
            SSTable t = tables.get(i);
            if (useBloom && !t.bloom.maybeContains(key)) {
                bloomSkips++;       // 'definitely not here' — no disk touch
                continue;
            }
            fileProbes++;
            String v = t.get(key);
            if (v != null) {
                return TOMBSTONE.equals(v) ? null : v;
            }
        }
        return null;
    }

    /**
     * Merge every source, newest-wins per key. Counts every physical
     * record examined — including tombstones and shadowed versions — which
     * is exactly why delete-heavy ranges get slow (F-L2).
     */
    public List<Entry> rangeScan(String lo, String hi) throws IOException {
        List<List<Tagged>> sources = new ArrayList<>();
        sources.add(tagged(memEntries(), nextSeq + 1, lo, hi));
        for (SSTable t : tables) {
            sources.add(tagged(t.entries(), t.seq + 1, lo, hi));
        }
        List<Tagged> merged = kWayMerge(sources);
        List<Entry> out = new ArrayList<>();
        int i = 0;
        while (i < merged.size()) {
            String k = merged.get(i).key();
            int j = i;
            while (j < merged.size() && merged.get(j).key().equals(k)) {
                j++;
            }
            Tagged newest = merged.get(i);
            recordsScanned += j - i;
            if (!TOMBSTONE.equals(newest.value())) {
                out.add(new Entry(k, newest.value()));
            }
            i = j;
        }
        return out;
    }

    /** The memtable as a sorted entry list — one more source for the merge. */
    public List<Entry> memEntries() {
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, String> e : mem.entrySet()) {
            out.add(new Entry(e.getKey(), e.getValue()));
        }
        return out;
    }

    /**
     * Tag (key, value) pairs as (key, -prio, value) so the k-way merge sorts
     * same-key groups newest-first. `prio` is bound EAGERLY, once per source,
     * before any merging starts: a version that resolved each source's
     * priority lazily during the merge silently merged with the wrong
     * priorities — the same class of bug as rung 6's.
     */
    static List<Tagged> tagged(List<Entry> entries, int prio, String lo, String hi) {
        List<Tagged> out = new ArrayList<>();
        for (Entry e : entries) {
            if ((lo == null || e.key().compareTo(lo) >= 0) && (hi == null || e.key().compareTo(hi) <= 0)) {
                out.add(new Tagged(e.key(), -prio, e.value()));
            }
        }
        return out;
    }

    static List<Tagged> tagged(List<Entry> entries, int prio) {
        return tagged(entries, prio, null, null);
    }

    /** One source being merged: its remaining entries plus the one at its head. */
    private static final class Cursor {
        final Iterator<Tagged> it;
        Tagged head;

        Cursor(Iterator<Tagged> it) {
            this.it = it;
            this.head = it.hasNext() ? it.next() : null;
        }

        void advance() {
            head = it.hasNext() ? it.next() : null;
        }
    }

    /**
     * The k-way merge itself: every input is sorted, so repeatedly taking the
     * smallest head produces one sorted stream. Ordering by (key, negPrio)
     * means same-key entries emerge NEWEST FIRST — which is what lets
     * compaction and rangeScan just take the head of each group.
     */
    static List<Tagged> kWayMerge(List<List<Tagged>> sources) {
        PriorityQueue<Cursor> heap = new PriorityQueue<>(
                Comparator.<Cursor, String>comparing(c -> c.head.key())
                        .thenComparingInt(c -> c.head.negPrio()));
        for (List<Tagged> s : sources) {
            Cursor c = new Cursor(s.iterator());
            if (c.head != null) {
                heap.add(c);
            }
        }
        List<Tagged> out = new ArrayList<>();
        while (!heap.isEmpty()) {
            Cursor c = heap.poll();
            out.add(c.head);
            c.advance();
            if (c.head != null) {
                heap.add(c);
            }
        }
        return out;
    }

    // ---------- maintenance ----------

    /**
     * §10.6 (size-tiered, one tier): k-way merge of ALL tables into one,
     * dropping shadowed values and tombstones.
     * INVARIANT (compaction is logically invisible): every get() answers
     * the same before and after — verified by
     * compaction_preserves_every_answer_and_reclaims_space.
     * Note the F-L3 shape: the output is fully written BEFORE inputs are
     * deleted, so disk usage transiently spikes.
     */
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
                Tagged newest = merged.get(i);              // smallest -seq = newest table
                if (!TOMBSTONE.equals(newest.value())) {    // tombstones die here — and only
                    f.write(k + "\t" + newest.value() + "\n");   // here (simplified: no
                }                                                // gc_grace in the toy)
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

    // ---------- introspection ----------

    public long diskBytes() throws IOException {
        long total = 0;
        for (SSTable t : tables) {
            total += Files.size(t.path);
        }
        return total;
    }

    public int tableCount() {
        return tables.size();
    }

    /** Force the log to disk without closing the engine — used to stage a "crash". */
    public void flushWal() throws IOException {
        wal.flush();
    }

    public void close() throws IOException {
        wal.close();
    }

    public void resetCounters() {
        fileProbes = 0;
        bloomSkips = 0;
        recordsScanned = 0;
    }
}
