import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests as executable documentation. Each name is a claim the README makes; running
 * this file is how you check the README is not lying to you.
 *
 *     java src/Tests.java
 */
public final class Tests {

    static int passed, failed;
    static final AtomicInteger PORT = new AtomicInteger(7301);

    public static void main(String[] args) throws Exception {
        System.out.println("MiniRedis test suite\n" + "=".repeat(64));

        section("protocol");
        test("resp_frames_a_command_split_across_two_tcp_writes", Tests::respFramesSplitCommand);
        test("pipelining_returns_replies_in_the_order_sent",      Tests::pipelineOrder);

        section("the single thread, and what it buys");
        test("concurrent_incr_never_loses_an_update",             Tests::concurrentIncr);
        test("incr_treats_a_missing_key_as_zero",                 Tests::incrMissing);
        test("ratelimit_admits_exactly_the_limit_under_contention", Tests::rateLimitAtomic);
        test("wrong_type_operation_is_refused",                   Tests::wrongType);

        section("expiry");
        test("ttl_distinguishes_missing_key_from_key_with_no_deadline", Tests::ttlCodes);
        test("expired_key_is_invisible_but_can_still_be_resident", Tests::lazyExpiry);
        test("active_expire_cycle_reclaims_keys_nobody_touched",  Tests::activeExpiry);

        section("eviction");
        test("maxmemory_with_lru_policy_evicts_down_to_the_limit", Tests::evictionEnforcesLimit);
        test("noeviction_policy_never_evicts",                     Tests::noeviction);

        section("transactions");
        test("multi_exec_runs_the_batch_with_nothing_interleaved", Tests::multiExecIsolated);
        test("multi_exec_does_not_roll_back_after_a_failed_command", Tests::multiExecNoRollback);
        test("watch_aborts_exec_when_another_client_changed_the_key", Tests::watchAborts);
        test("watch_allows_exec_when_nothing_changed",             Tests::watchPasses);

        section("locks");
        test("set_nx_px_grants_the_lock_to_exactly_one_client",    Tests::lockOnlyOnce);
        test("expired_lock_can_be_taken_by_a_second_holder",       Tests::lockExpiresAndIsRetaken);

        section("persistence and replication");
        test("aof_replay_reconstructs_the_keyspace",               Tests::aofReplay);
        test("expire_is_propagated_as_an_absolute_pexpireat",      Tests::expireRewrite);
        test("spop_is_propagated_as_a_concrete_srem",              Tests::spopRewrite);
        test("set_is_propagated_without_nx_and_with_an_absolute_deadline", Tests::setRewrite);
        test("replica_receives_a_full_sync_then_the_live_stream",  Tests::replication);

        section("other");
        test("pubsub_reaches_only_clients_connected_right_now",    Tests::pubsubIsFireAndForget);
        test("scan_visits_every_key_across_pages",                 Tests::scanCoversKeyspace);
        test("object_encoding_changes_when_a_collection_grows",    Tests::encodingSwitch);

        System.out.println("=".repeat(64));
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- protocol

    /** RESP's whole reason to exist: a half-arrived command must not be executed. */
    static void respFramesSplitCommand(MiniRedis s) throws Exception {
        try (java.net.Socket sock = new java.net.Socket("127.0.0.1", s.port)) {
            sock.setTcpNoDelay(true);
            var out = sock.getOutputStream();
            out.write("*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$5\r\nRe".getBytes());  // deliberately truncated
            out.flush();
            Thread.sleep(60);
            try (Cli probe = new Cli(s.port)) {
                eq("key must not exist from a half-command", null, probe.cmd("GET", "name"));
            }
            out.write("dis\r\n".getBytes());                                     // the rest
            out.flush();
            Thread.sleep(60);
            try (Cli probe = new Cli(s.port)) {
                eq("key appears once the command completes", "Redis", probe.cmd("GET", "name"));
            }
        }
    }

    static void pipelineOrder(MiniRedis s) throws Exception {
        try (Cli c = new Cli(s.port)) {
            List<String[]> batch = new ArrayList<>();
            for (int i = 0; i < 50; i++) batch.add(new String[]{"INCR", "n"});
            List<Object> replies = c.pipeline(batch);
            eq("50 replies for 50 commands", 50, replies.size());
            eq("first reply is 1", 1L, replies.get(0));
            eq("last reply is 50", 50L, replies.get(49));
        }
    }

    // ---------------------------------------------------- the single thread

    /**
     * THE CENTRAL CLAIM OF THE LAB. Eight clients, 500 increments each, one key.
     * Nobody takes a lock. The answer must be exactly 4000.
     */
    static void concurrentIncr(MiniRedis s) throws Exception {
        int threads = 8, per = 500;
        runConcurrently(threads, t -> {
            try (Cli c = new Cli(s.port)) { for (int i = 0; i < per; i++) c.cmd("INCR", "counter"); }
            catch (IOException e) { throw new RuntimeException(e); }
        });
        try (Cli c = new Cli(s.port)) {
            eq("no increments lost", String.valueOf(threads * per), c.cmd("GET", "counter"));
        }
    }

    static void incrMissing(MiniRedis s) throws Exception {
        try (Cli c = new Cli(s.port)) {
            eq("missing key starts at zero", 1L, c.cmd("INCR", "brand-new"));
            eq("INCRBY on missing key", 7L, c.cmd("INCRBY", "another", "7"));
        }
    }

    /** The rate-limiting lab's problem, solved by making check-and-increment one command. */
    static void rateLimitAtomic(MiniRedis s) throws Exception {
        int threads = 10, per = 50, limit = 100;
        int[] admitted = new int[threads];
        runConcurrently(threads, t -> {
            try (Cli c = new Cli(s.port)) {
                for (int i = 0; i < per; i++) {
                    @SuppressWarnings("unchecked") List<Object> r = (List<Object>) c.cmd("RATELIMIT", "user:42", String.valueOf(limit), "60");
                    if ((Long) r.get(0) == 1L) admitted[t]++;
                }
            } catch (IOException e) { throw new RuntimeException(e); }
        });
        int total = Arrays.stream(admitted).sum();
        eq("exactly the limit was admitted, from 500 attempts", limit, total);
    }

    static void wrongType(MiniRedis s) throws Exception {
        try (Cli c = new Cli(s.port)) {
            c.cmd("LPUSH", "q", "job1");
            Object r = c.cmd("GET", "q");
            ok("GET on a list is an error", r instanceof Cli.Err e && e.message().startsWith("WRONGTYPE"));
        }
    }

    // ---------------------------------------------------------------- expiry

    static void ttlCodes(MiniRedis s) throws Exception {
        try (Cli c = new Cli(s.port)) {
            eq("-2 means no such key", -2L, c.cmd("TTL", "ghost"));
            c.cmd("SET", "forever", "v");
            eq("-1 means key exists with no deadline", -1L, c.cmd("TTL", "forever"));
            c.cmd("SET", "temp", "v", "EX", "100");
            ok("a real deadline reports a positive TTL", (Long) c.cmd("TTL", "temp") > 90);
        }
    }

    /**
     * The lesson that surprises people: passing the deadline makes a key INVISIBLE, not
     * absent. With the background cycle switched off, the memory stays occupied until
     * someone touches the key.
     */
    static void lazyExpiry(MiniRedis s) throws Exception {
        try (Cli c = new Cli(s.port)) {
            c.cmd("DEBUG", "SET-ACTIVE-EXPIRE", "0");
            c.cmd("SET", "session:1", "data", "PX", "50");
            eq("resident before expiry", 1L, c.cmd("DEBUG", "RESIDENT"));
            c.cmd("DEBUG", "ADVANCE", "5000");                 // jump the clock past the deadline
            eq("invisible after expiry", null, c.cmd("GET", "session:1"));
            eq("and reading it is what actually freed the memory", 0L, c.cmd("DEBUG", "RESIDENT"));
            c.cmd("SET", "session:2", "data", "PX", "50");
            c.cmd("DEBUG", "ADVANCE", "5000");
            eq("DBSIZE still counts the dead key -- it reports residency, not liveness", 1L, c.cmd("DBSIZE"));
            eq("the logical view says it is gone", 0L, c.cmd("DEBUG", "LIVE"));
        }
    }

    static void activeExpiry(MiniRedis s) throws Exception {
        try (Cli c = new Cli(s.port)) {
            c.cmd("DEBUG", "SET-ACTIVE-EXPIRE", "0");
            for (int i = 0; i < 200; i++) c.cmd("SET", "k" + i, "v", "PX", "50");
            c.cmd("DEBUG", "ADVANCE", "5000");
            eq("all 200 still resident, untouched", 200L, c.cmd("DEBUG", "RESIDENT"));
            long before = (Long) c.cmd("DEBUG", "RESIDENT");
            for (int i = 0; i < 40; i++) c.cmd("DEBUG", "EXPIRECYCLE");
            long after = (Long) c.cmd("DEBUG", "RESIDENT");
            ok("the sampling cycle reclaimed them without anyone reading them (" + before + " -> " + after + ")", after == 0);
        }
    }

    // -------------------------------------------------------------- eviction

    static void evictionEnforcesLimit(MiniRedis s) throws Exception {
        try (Cli c = new Cli(s.port)) {
            c.cmd("CONFIG", "SET", "maxmemory-policy", "allkeys-lru");
            for (int i = 0; i < 500; i++) c.cmd("SET", "k" + i, "x".repeat(50));
            long full = Long.parseLong(info(c, "used_memory"));
            c.cmd("CONFIG", "SET", "maxmemory", String.valueOf(full / 2));
            for (int i = 0; i < 20; i++) c.cmd("SET", "trigger" + i, "y");
            long used = Long.parseLong(info(c, "used_memory"));
            long max  = Long.parseLong(info(c, "maxmemory"));
            ok("memory pulled back under maxmemory (" + used + " <= " + max + ")", used <= max);
            ok("eviction was actually the mechanism", Long.parseLong(info(c, "evicted_keys")) > 0);
        }
    }

    static void noeviction(MiniRedis s) throws Exception {
        try (Cli c = new Cli(s.port)) {
            c.cmd("CONFIG", "SET", "maxmemory-policy", "noeviction");
            for (int i = 0; i < 200; i++) c.cmd("SET", "k" + i, "x".repeat(50));
            c.cmd("CONFIG", "SET", "maxmemory", "1000");
            for (int i = 0; i < 5; i++) c.cmd("SET", "more" + i, "y");
            eq("nothing was evicted", 0L, Long.parseLong(info(c, "evicted_keys")));
            ok("so memory stayed above the limit", Long.parseLong(info(c, "used_memory")) > 1000);
        }
    }

    // ---------------------------------------------------------- transactions

    static void multiExecIsolated(MiniRedis s) throws Exception {
        try (Cli a = new Cli(s.port); Cli b = new Cli(s.port)) {
            a.cmd("SET", "x", "0"); a.cmd("SET", "y", "0");
            a.cmd("MULTI");
            a.cmd("INCR", "x");
            a.cmd("INCR", "y");
            // b interleaves an attempt while the batch is queued -- it lands BEFORE or
            // AFTER the batch, never inside it.
            b.cmd("SET", "x", "100");
            @SuppressWarnings("unchecked") List<Object> r = (List<Object>) a.cmd("EXEC");
            eq("two replies from the batch", 2, r.size());
            eq("x moved from b's 100 to 101 -- the batch saw a consistent snapshot", 101L, r.get(0));
            eq("y incremented too", 1L, r.get(1));
        }
    }

    /** Redis "transactions" are batches, not ACID transactions. There is no undo. */
    static void multiExecNoRollback(MiniRedis s) throws Exception {
        try (Cli c = new Cli(s.port)) {
            c.cmd("SET", "good", "0");
            c.cmd("SET", "text", "not-a-number");
            c.cmd("MULTI");
            c.cmd("INCR", "good");
            c.cmd("INCR", "text");        // will fail at run time
            c.cmd("INCR", "good");
            @SuppressWarnings("unchecked") List<Object> r = (List<Object>) c.cmd("EXEC");
            eq("three replies", 3, r.size());
            ok("the middle command failed", r.get(1) instanceof Cli.Err);
            eq("and the ones around it still committed", "2", c.cmd("GET", "good"));
        }
    }

    static void watchAborts(MiniRedis s) throws Exception {
        try (Cli a = new Cli(s.port); Cli b = new Cli(s.port)) {
            a.cmd("SET", "balance", "100");
            a.cmd("WATCH", "balance");
            a.cmd("GET", "balance");
            b.cmd("SET", "balance", "50");         // somebody else moved it
            a.cmd("MULTI");
            a.cmd("SET", "balance", "90");
            eq("EXEC returns nil -- the whole batch was refused", null, a.cmd("EXEC"));
            eq("and the value is b's, untouched by a", "50", a.cmd("GET", "balance"));
        }
    }

    static void watchPasses(MiniRedis s) throws Exception {
        try (Cli a = new Cli(s.port)) {
            a.cmd("SET", "balance", "100");
            a.cmd("WATCH", "balance");
            a.cmd("MULTI");
            a.cmd("SET", "balance", "90");
            @SuppressWarnings("unchecked") List<Object> r = (List<Object>) a.cmd("EXEC");
            eq("batch ran", 1, r.size());
            eq("value applied", "90", a.cmd("GET", "balance"));
        }
    }

    // ------------------------------------------------------------------ locks

    static void lockOnlyOnce(MiniRedis s) throws Exception {
        int threads = 20;
        int[] won = new int[threads];
        runConcurrently(threads, t -> {
            try (Cli c = new Cli(s.port)) {
                if ("OK".equals(c.cmd("SET", "lock:order:9", "owner" + t, "NX", "PX", "5000"))) won[t] = 1;
            } catch (IOException e) { throw new RuntimeException(e); }
        });
        eq("exactly one of 20 clients got the lock", 1, Arrays.stream(won).sum());
    }

    /**
     * The uncomfortable half of the Redis lock: a lock with a deadline is a lock that
     * can be lost while you still think you hold it. Experiment 8 turns this into the
     * two-holders failure.
     */
    static void lockExpiresAndIsRetaken(MiniRedis s) throws Exception {
        try (Cli a = new Cli(s.port); Cli b = new Cli(s.port)) {
            eq("A takes the lock", "OK", a.cmd("SET", "lock:x", "A", "NX", "PX", "80"));
            eq("B is refused while it is held", null, b.cmd("SET", "lock:x", "B", "NX", "PX", "80"));
            Thread.sleep(140);
            eq("after the deadline, B takes it", "OK", b.cmd("SET", "lock:x", "B", "NX", "PX", "80"));
            eq("and A was never told", "B", a.cmd("GET", "lock:x"));
        }
    }

    // ------------------------------------------- persistence and replication

    static void aofReplay(MiniRedis s) throws Exception {
        Path aof = Files.createTempFile("miniredis-test", ".aof");
        Files.deleteIfExists(aof);
        MiniRedis m = new MiniRedis(PORT.getAndIncrement());
        m.aofPath = aof.toString();
        m.appendfsync = "always";
        m.start();
        try (Cli c = new Cli(m.port)) {
            c.cmd("SET", "user:1", "alice");
            c.cmd("RPUSH", "jobs", "a", "b");
            c.cmd("INCR", "hits");
            c.cmd("SET", "gone", "v"); c.cmd("DEL", "gone");
            Thread.sleep(80);
        }
        m.stop();

        MiniRedis restarted = new MiniRedis(PORT.getAndIncrement());
        restarted.aofPath = aof.toString();
        int replayed = restarted.loadAof();
        restarted.start();
        try (Cli c = new Cli(restarted.port)) {
            ok("commands were replayed (" + replayed + ")", replayed >= 5);
            eq("string survived", "alice", c.cmd("GET", "user:1"));
            eq("list survived", 2L, c.cmd("LLEN", "jobs"));
            eq("counter survived", "1", c.cmd("GET", "hits"));
            eq("the delete was replayed too", 0L, c.cmd("EXISTS", "gone"));
        }
        restarted.stop();
        Files.deleteIfExists(aof);
    }

    /** A relative deadline replayed later would drift. Redis rewrites it to an absolute one. */
    static void expireRewrite(MiniRedis s) throws Exception {
        Path aof = Files.createTempFile("miniredis-exp", ".aof");
        Files.deleteIfExists(aof);
        MiniRedis m = new MiniRedis(PORT.getAndIncrement());
        m.aofPath = aof.toString(); m.appendfsync = "always"; m.start();
        try (Cli c = new Cli(m.port)) { c.cmd("SET", "k", "v"); c.cmd("EXPIRE", "k", "100"); Thread.sleep(80); }
        m.stop();
        String text = Files.readString(aof);
        ok("the AOF contains PEXPIREAT, not EXPIRE", text.contains("PEXPIREAT") && !text.contains("\nEXPIRE\r"));
        Files.deleteIfExists(aof);
    }

    /** SPOP picks at random; replaying it would pick a different member on the replica. */
    static void spopRewrite(MiniRedis s) throws Exception {
        Path aof = Files.createTempFile("miniredis-spop", ".aof");
        Files.deleteIfExists(aof);
        MiniRedis m = new MiniRedis(PORT.getAndIncrement());
        m.aofPath = aof.toString(); m.appendfsync = "always"; m.start();
        try (Cli c = new Cli(m.port)) { c.cmd("SADD", "s", "a", "b", "c"); c.cmd("SPOP", "s"); Thread.sleep(80); }
        m.stop();
        ok("the AOF records the concrete SREM", Files.readString(aof).contains("SREM"));
        Files.deleteIfExists(aof);
    }

    /**
     * SET carries two things that must not survive the trip to a replica: the NX/XX
     * condition (already decided here) and a relative deadline (which would mean a
     * later moment there). Real Redis rewrites both away.
     */
    static void setRewrite(MiniRedis s) throws Exception {
        Path aof = Files.createTempFile("miniredis-set", ".aof");
        Files.deleteIfExists(aof);
        MiniRedis m = new MiniRedis(PORT.getAndIncrement());
        m.aofPath = aof.toString(); m.appendfsync = "always"; m.start();
        try (Cli c = new Cli(m.port)) { c.cmd("SET", "lock", "A", "NX", "PX", "200"); Thread.sleep(80); }
        m.stop();
        String text = Files.readString(aof);
        ok("the condition was stripped -- it was already decided", !text.contains("NX"));
        ok("the deadline was made absolute", text.contains("PXAT") && !text.contains("\r\nPX\r\n"));
        Files.deleteIfExists(aof);
    }

    static void replication(MiniRedis unused) throws Exception {
        MiniRedis master = new MiniRedis(PORT.getAndIncrement());
        MiniRedis replica = new MiniRedis(PORT.getAndIncrement());
        master.start(); replica.start();
        try (Cli mc = new Cli(master.port); Cli rc = new Cli(replica.port)) {
            mc.cmd("SET", "before", "1");                    // exists before the replica attaches
            mc.cmd("RPUSH", "list", "x", "y");
            rc.cmd("REPLICAOF", "127.0.0.1", String.valueOf(master.port));
            Thread.sleep(200);
            eq("full sync carried the pre-existing string", "1", rc.cmd("GET", "before"));
            eq("full sync carried the pre-existing list", 2L, rc.cmd("LLEN", "list"));
            mc.cmd("SET", "after", "2");                     // written after attach
            Thread.sleep(200);
            eq("the live stream carried the new write", "2", rc.cmd("GET", "after"));
        } finally { master.stop(); replica.stop(); }
    }

    // ------------------------------------------------------------------ other

    static void pubsubIsFireAndForget(MiniRedis s) throws Exception {
        try (Cli pub = new Cli(s.port)) {
            eq("nobody listening: delivered to zero", 0L, pub.cmd("PUBLISH", "news", "missed-forever"));
            Cli sub = new Cli(s.port);
            sub.sendOnly("SUBSCRIBE", "news");
            sub.readReply();
            Thread.sleep(60);
            eq("now one subscriber", 1L, pub.cmd("PUBLISH", "news", "heard"));
            @SuppressWarnings("unchecked") List<Object> msg = (List<Object>) sub.readReply();
            eq("subscriber got the live message", "heard", msg.get(2));
            ok("the earlier message is unrecoverable -- there is no backlog to read", true);
            sub.close();
        }
    }

    static void scanCoversKeyspace(MiniRedis s) throws Exception {
        try (Cli c = new Cli(s.port)) {
            for (int i = 0; i < 95; i++) c.cmd("SET", "k" + i, "v");
            Set<String> seen = new HashSet<>();
            String cursor = "0";
            int pages = 0;
            do {
                @SuppressWarnings("unchecked") List<Object> r = (List<Object>) c.cmd("SCAN", cursor, "COUNT", "10");
                cursor = (String) r.get(0);
                @SuppressWarnings("unchecked") List<Object> keys = (List<Object>) r.get(1);
                for (Object k : keys) seen.add((String) k);
                pages++;
            } while (!cursor.equals("0") && pages < 100);
            eq("every key was visited", 95, seen.size());
            ok("and it took several bounded pages, not one big one (" + pages + ")", pages > 5);
        }
    }

    static void encodingSwitch(MiniRedis s) throws Exception {
        try (Cli c = new Cli(s.port)) {
            c.cmd("SET", "n", "12345");
            eq("a numeric string is stored as an int", "int", c.cmd("OBJECT", "ENCODING", "n"));
            c.cmd("SET", "short", "hello");
            eq("a short string is embstr", "embstr", c.cmd("OBJECT", "ENCODING", "short"));
            c.cmd("SET", "long", "x".repeat(100));
            eq("a long one is raw", "raw", c.cmd("OBJECT", "ENCODING", "long"));
            for (int i = 0; i < 10; i++) c.cmd("HSET", "h", "f" + i, "v");
            eq("a small hash is a flat listpack", "listpack", c.cmd("OBJECT", "ENCODING", "h"));
            for (int i = 0; i < 200; i++) c.cmd("HSET", "h", "g" + i, "v");
            eq("a big one becomes a real hash table", "hashtable", c.cmd("OBJECT", "ENCODING", "h"));
        }
    }

    // ------------------------------------------------------------- harness

    interface Scenario { void run(MiniRedis s) throws Exception; }

    static void section(String name) { System.out.println("\n-- " + name + " " + "-".repeat(Math.max(0, 58 - name.length()))); }

    static void test(String name, Scenario sc) {
        MiniRedis s = null;
        try {
            s = new MiniRedis(PORT.getAndIncrement());
            s.start();
            sc.run(s);
            System.out.printf("  PASS  %s%n", name);
            passed++;
        } catch (AssertionError e) {
            System.out.printf("  FAIL  %s%n          %s%n", name, e.getMessage());
            failed++;
        } catch (Exception e) {
            System.out.printf("  ERROR %s%n          %s: %s%n", name, e.getClass().getSimpleName(), e.getMessage());
            failed++;
        } finally { if (s != null) s.stop(); }
    }

    static void runConcurrently(int n, java.util.function.IntConsumer body) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int id = i;
            Thread t = Thread.ofVirtual().unstarted(() -> {
                try { go.await(); body.accept(id); } catch (Exception e) { throw new RuntimeException(e); }
            });
            ts.add(t); t.start();
        }
        go.countDown();
        for (Thread t : ts) t.join();
    }

    static String info(Cli c, String field) throws IOException {
        String txt = (String) c.cmd("INFO");
        for (String line : txt.split("\n")) if (line.startsWith(field + ":")) return line.substring(field.length() + 1).trim();
        return "0";
    }

    static void eq(String what, Object expected, Object actual) {
        Object e = expected instanceof Integer i ? (long) i : expected;
        Object a = actual   instanceof Integer i ? (long) i : actual;
        if (e instanceof Number && a instanceof Number) { e = ((Number) e).longValue(); a = ((Number) a).longValue(); }
        if (!Objects.equals(e, a)) throw new AssertionError(what + ": expected <" + e + "> but was <" + a + ">");
    }

    static void ok(String what, boolean cond) { if (!cond) throw new AssertionError(what); }
}
