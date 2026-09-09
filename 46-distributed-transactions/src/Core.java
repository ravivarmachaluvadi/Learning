// Core.java — the machinery every design in this lab shares:
// shards (multi-version data + lock tables), timestamp sources, a
// deterministic scheduler, and the planted-bug switches.
//
// A "shard" here is one independent database holding some of the keys.
// It commits its own data atomically (that part is a solved, local problem —
// see the isolation-levels lab). Everything interesting in THIS lab happens
// BETWEEN shards.
import java.util.*;

public class Core {

  // ---------- planted-bug switches (EXERCISES rung 6; `java src/Tests.java broken`) ----------
  public static class Bugs {
    public boolean readerIgnoresLocks = false;       // snapshot reader skips the lock check
    public boolean commitSecondariesFirst = false;   // percolator commits secondaries before the primary
    public boolean resolveWithoutPrimaryCheck = false;// reader rolls a stale lock back without asking the primary
    public boolean earlyReadLockRelease = false;     // 2PL reader releases each read lock right after reading
  }

  // ---------- the timestamp oracle: one counter, handed out in order ----------
  public static class Oracle {
    private long ts = 1;          // ts 1 is reserved for the initial balances
    public long calls = 0;
    public long next() { calls++; return ++ts; }
  }

  // ---------- a percolator-style lock: "key is claimed by the txn that started at startTs;
  // ---------- its fate is decided at primaryKey" ----------
  public static class PercoLock {
    public final long startTs; public final String txnId; public final String primaryKey;
    public final long createdTick;
    public PercoLock(long startTs, String txnId, String primaryKey, long createdTick) {
      this.startTs = startTs; this.txnId = txnId; this.primaryKey = primaryKey; this.createdTick = createdTick;
    }
  }

  // ---------- one shard ----------
  public static class Shard {
    public final String name;
    // committed truth: key -> (commitTs -> value). Multi-version, like MiniDb in isolation-levels.
    public final Map<String, TreeMap<Long, Long>> versions = new HashMap<>();

    // 2PC-family state
    public final Map<String, String> writeLock = new HashMap<>();        // key -> txnId
    public final Map<String, Set<String>> readLocks = new HashMap<>();   // key -> reader txnIds
    public final Map<String, Long> staged2pc = new HashMap<>();          // key -> value to install at commit

    // percolator-family state
    public final Map<String, PercoLock> locks = new HashMap<>();         // key -> in-flight claim
    public final Map<String, Long> stagedPerco = new HashMap<>();        // key -> staged value for that claim
    public final Map<Long, Long> commitRecord = new HashMap<>();         // startTs -> commitTs. Written ONLY by
                                                                         // the primary commit. THE commit point.
    public long localSeq = 1;  // per-shard commit counter for designs with no global time

    public Shard(String name) { this.name = name; }

    public long latest(String key) {
      TreeMap<Long, Long> v = versions.get(key);
      return (v == null || v.isEmpty()) ? 0 : v.lastEntry().getValue();
    }
    public long latestCommitTs(String key) {
      TreeMap<Long, Long> v = versions.get(key);
      return (v == null || v.isEmpty()) ? 0 : v.lastKey();
    }
    public long floorValue(String key, long ts) {
      TreeMap<Long, Long> v = versions.get(key);
      if (v == null) return 0;
      Map.Entry<Long, Long> e = v.floorEntry(ts);
      return e == null ? 0 : e.getValue();
    }
    public void put(String key, long ts, long value) {
      versions.computeIfAbsent(key, k -> new TreeMap<>()).put(ts, value);
    }
    public boolean readLockedByOthers(String key, String self) {
      Set<String> s = readLocks.get(key);
      if (s == null || s.isEmpty()) return false;
      return !(s.size() == 1 && s.contains(self));
    }
    public void addReadLock(String key, String txn)    { readLocks.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(txn); }
    public void removeReadLock(String key, String txn) { Set<String> s = readLocks.get(key); if (s != null) s.remove(txn); }
  }

  // ---------- the cluster: shards + key placement + shared services ----------
  public static class Cluster {
    public final List<Shard> shards = new ArrayList<>();
    public final Map<String, Shard> owner = new LinkedHashMap<>();  // key -> its shard
    public final Oracle oracle = new Oracle();
    public final Bugs bugs = new Bugs();

    public Cluster(int nShards) { for (int i = 0; i < nShards; i++) shards.add(new Shard("S" + i)); }
    public void place(String key, int shardIdx, long initialBalance) {
      owner.put(key, shards.get(shardIdx));
      shards.get(shardIdx).put(key, 1, initialBalance);   // initial balances committed at ts 1
    }
    public Shard of(String key) { return owner.get(key); }
    public long totalCommitted() {
      long sum = 0;
      for (Map.Entry<String, Shard> e : owner.entrySet()) sum += e.getValue().latest(e.getKey());
      return sum;
    }
  }

  // ---------- deterministic scheduler: one random live driver steps per tick ----------
  public interface Driver {
    void step(long tick);
    boolean done();       // finished, aborted, gave up, or killed — will not be stepped again
    String id();
  }

  public static class Sched {
    public final Random rnd; public long tick = 0;
    public final List<Driver> live = new ArrayList<>();
    private final TreeMap<Long, List<Driver>> pending = new TreeMap<>();
    public Sched(long seed) { rnd = new Random(seed); }
    public void spawn(Driver d) { live.add(d); }
    public void spawnAt(long t, Driver d) { pending.computeIfAbsent(t, k -> new ArrayList<>()).add(d); }
    public void run(long maxTicks) {
      while (tick < maxTicks) {
        while (!pending.isEmpty() && pending.firstKey() <= tick) live.addAll(pending.pollFirstEntry().getValue());
        List<Driver> ready = new ArrayList<>();
        for (Driver d : live) if (!d.done()) ready.add(d);
        if (ready.isEmpty()) {
          if (pending.isEmpty()) break;
          tick = pending.firstKey(); continue;
        }
        ready.get(rnd.nextInt(ready.size())).step(tick);
        tick++;
      }
    }
  }
}
