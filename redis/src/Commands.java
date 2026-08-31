import java.util.*;

/**
 * The command table.
 *
 * READ THIS FILE WITH ONE QUESTION IN MIND: why is each of these a SERVER command
 * instead of something the client does?
 *
 * A plain key-value cache can only offer GET and SET, so "add one to a counter"
 * becomes GET, add one in the client, SET -- three steps with a gap in the middle
 * that another client can slip into. That gap is the lost update from the
 * distributed-rate-limiting lab, and no amount of client cleverness closes it.
 *
 * Redis closes it by knowing the TYPE of the value. Because the server knows
 * "visits" holds a number, it can offer INCR: read, add, write, all inside one
 * command, on the one thread, with nothing able to interleave. The lock you would
 * otherwise need does not exist because the critical section is the command.
 */
final class Commands {

    // ---- planted bugs, for EXERCISES.md rung 6. All false in normal operation. ----
    // Each one is a real mistake somebody has shipped. Turn one on and find out which
    // test catches it and which guarantee it breaks.
    static boolean BUG_RELATIVE_EXPIRE      = false;   // send EXPIRE to replicas instead of PEXPIREAT
    static boolean BUG_INCR_IGNORES_EXPIRY  = false;   // INCR reads the table directly, skipping the deadline check
    static boolean BUG_NO_SET_REWRITE       = false;   // replicate SET verbatim, keeping NX and a relative deadline

    private final MiniRedis srv;
    private final Store s;
    private List<String> propagateOverride;

    Commands(MiniRedis srv) { this.srv = srv; this.s = srv.store; }

    private static final Set<String> KNOWN = new HashSet<>(Arrays.asList(
        "PING","ECHO","QUIT","COMMAND","CLIENT","HELLO","INFO","DBSIZE","FLUSHALL","CONFIG","DEBUG","SLOWLOG",
        "SET","GET","GETSET","GETDEL","SETNX","SETEX","MSET","MGET","APPEND","STRLEN","INCR","DECR","INCRBY","DECRBY","INCRBYFLOAT",
        "DEL","UNLINK","EXISTS","EXPIRE","PEXPIRE","PEXPIREAT","TTL","PTTL","PERSIST","TYPE","KEYS","SCAN","RANDOMKEY","RENAME",
        "LPUSH","RPUSH","LPOP","RPOP","LRANGE","LLEN","LREM",
        "HSET","HGET","HDEL","HGETALL","HLEN","HKEYS","HVALS","HEXISTS","HINCRBY",
        "SADD","SREM","SMEMBERS","SISMEMBER","SCARD","SPOP",
        "ZADD","ZSCORE","ZINCRBY","ZRANGE","ZREVRANGE","ZRANK","ZREVRANK","ZCARD","ZREM",
        "MULTI","EXEC","DISCARD","WATCH","UNWATCH",
        "SUBSCRIBE","UNSUBSCRIBE","PUBLISH",
        "SYNC","REPLICAOF","REPLCONF","WAIT","OBJECT","RATELIMIT","LASTSAVE"));

    boolean exists(String name) { return KNOWN.contains(name); }

    /** Commands may rewrite themselves for the AOF/replicas (see EXPIRE -> PEXPIREAT). */
    List<String> takePropagateOverride(List<String> original) {
        List<String> o = propagateOverride; propagateOverride = null;
        return o != null ? o : original;
    }

    byte[] run(MiniRedis.Conn c, String name, List<String> a) {
        switch (name) {

        // ---------- connection / admin ----------
        case "PING":    return a.size() > 1 ? Resp.bulk(a.get(1)) : Resp.simple("PONG");
        case "ECHO":    return Resp.bulk(arg(a,1));
        case "QUIT":    srv.flushOut(c); srv.closeConn(c); return null;
        case "COMMAND": return Resp.array(List.of());
        case "CLIENT":  return Resp.simple("OK");
        case "HELLO":   return Resp.error("NOPROTO this lab speaks RESP2 only");
        // FAITHFUL TO REAL REDIS, AND IT SURPRISES PEOPLE: DBSIZE reports how many keys
        // are RESIDENT, not how many are still alive. A key whose deadline passed is
        // counted here until something reaps it. This is why "my TTLs expired but DBSIZE
        // didn't move" is a support question rather than a bug.
        case "DBSIZE":  return Resp.integer(s.dict.size());
        case "FLUSHALL": s.flush(); s.dirty++; return Resp.simple("OK");
        case "LASTSAVE": return Resp.integer(System.currentTimeMillis() / 1000);

        case "INFO": {
            long used = s.usedMemory();
            String txt = "# Server\nsingle_threaded:" + (!srv.parallel)
                + "\nloop_iterations:" + srv.loopIterations
                + "\n# Clients\nconnected_clients:" + srv.conns.size()
                + "\nconnected_replicas:" + srv.replicas.size()
                + "\n# Memory\nused_memory:" + used
                + "\nmaxmemory:" + s.maxMemory
                + "\nmaxmemory_policy:" + s.maxMemoryPolicy
                + "\n# Stats\ntotal_commands_processed:" + s.commandsProcessed
                + "\nkeyspace_hits:" + s.hits
                + "\nkeyspace_misses:" + s.misses
                + "\nexpired_keys:" + s.expiredKeys
                + "\nevicted_keys:" + s.evictedKeys
                + "\n# Persistence\nappendfsync:" + srv.appendfsync
                + "\naof_written_bytes:" + srv.writtenLen
                + "\naof_fsynced_bytes:" + srv.fsyncedLen
                + "\n# Replication\nrole:" + (srv.isReplica ? "replica" : "master")
                + "\nmaster_repl_offset:" + srv.replOffset
                + "\n# Keyspace\ndb0:keys=" + s.dict.size() + ",expires=" + s.expires.size() + "\n";
            return Resp.bulk(txt);
        }

        case "CONFIG": {
            String sub = arg(a,1).toUpperCase(Locale.ROOT);
            if (sub.equals("SET")) {
                switch (arg(a,2).toLowerCase(Locale.ROOT)) {
                    case "maxmemory"         -> s.maxMemory = Long.parseLong(arg(a,3));
                    case "maxmemory-policy"  -> s.maxMemoryPolicy = arg(a,3);
                    case "maxmemory-samples" -> s.maxMemorySamples = Integer.parseInt(arg(a,3));
                    case "appendfsync"       -> srv.appendfsync = arg(a,3);
                    default -> { return Resp.error("ERR unknown parameter"); }
                }
                return Resp.simple("OK");
            }
            String k = arg(a,2);
            String v = switch (k) {
                case "maxmemory" -> String.valueOf(s.maxMemory);
                case "maxmemory-policy" -> s.maxMemoryPolicy;
                case "maxmemory-samples" -> String.valueOf(s.maxMemorySamples);
                case "appendfsync" -> srv.appendfsync;
                default -> "";
            };
            return Resp.array(List.of(k, v));
        }

        case "DEBUG": {
            String sub = arg(a,1).toUpperCase(Locale.ROOT);
            switch (sub) {
                // Blocks the one thread on purpose. This is the whole of Experiment 2.
                case "SLEEP":     try { Thread.sleep((long)(Double.parseDouble(arg(a,2)) * 1000)); } catch (InterruptedException ignored) {} return Resp.simple("OK");
                case "ADVANCE":   s.advanceClock(Long.parseLong(arg(a,2))); return Resp.simple("OK");
                case "REPLDELAY": srv.replDelayMs = Integer.parseInt(arg(a,2)); return Resp.simple("OK");
                case "EXPIRECYCLE": return Resp.integer(s.activeExpireCycle(16));
                // Real Redis has this exact command. Turning the background cycle off
                // leaves ONLY lazy expiry, which is how you see expired-but-resident keys.
                case "SET-ACTIVE-EXPIRE": srv.activeExpireEnabled = arg(a,2).equals("1"); return Resp.simple("OK");
                case "RESIDENT":  return Resp.integer(s.dict.size());       // same number DBSIZE reports
                case "LIVE":      return Resp.integer(s.liveKeys().size()); // the logical truth (and it reaps as it counts)
                default: return Resp.error("ERR unknown DEBUG subcommand");
            }
        }

        case "SLOWLOG": {
            if (arg(a,1).equalsIgnoreCase("RESET")) { srv.slowlog.clear(); return Resp.simple("OK"); }
            return Resp.array(new ArrayList<>(srv.slowlog));
        }

        case "OBJECT": {
            // OBJECT ENCODING is how you discover Redis silently swapped the physical
            // layout under your value once it outgrew the small-collection threshold.
            Store.Value v = s.lookup(arg(a,2));
            if (v == null) return Resp.error("ERR no such key");
            return Resp.bulk(encodingOf(v));
        }

        // ---------- strings and counters ----------
        case "SET": {
            String key = arg(a,1), val = arg(a,2);
            boolean nx = false, xx = false, keepTtl = false; Long pxMs = null;
            for (int i = 3; i < a.size(); i++) {
                String o = a.get(i).toUpperCase(Locale.ROOT);
                switch (o) {
                    case "NX" -> nx = true;
                    case "XX" -> xx = true;
                    case "KEEPTTL" -> keepTtl = true;
                    case "EX" -> pxMs = Long.parseLong(a.get(++i)) * 1000;
                    case "PX" -> pxMs = Long.parseLong(a.get(++i));
                    case "PXAT" -> pxMs = Long.parseLong(a.get(++i)) - s.now();   // absolute -> relative
                }
            }
            boolean present = s.existsNoTouch(key);
            // "SET key val NX PX 30000" -- the famous lock. Taking the lock and giving it
            // a deadline is ONE command, so a crash can never leave a lock with no
            // expiry. Doing it as SETNX-then-EXPIRE can, and used to, all the time.
            if (nx && present) return Resp.nil();
            if (xx && !present) return Resp.nil();
            s.put(key, new Store.Value(Store.Type.STRING, val, s.now()));
            Long deadline = null;
            if (pxMs != null) { deadline = s.now() + pxMs; s.setExpiryAt(key, deadline); }
            else if (!keepTtl) s.removeExpiry(key);
            s.bump(key);
            // PROPAGATE THE EFFECT, NOT THE REQUEST -- and SET is the sharpest example.
            // Two things must be stripped before this reaches a replica or the AOF:
            //   NX/XX  -- the condition was already decided HERE. Re-deciding it later,
            //             against a slightly different keyspace or a later clock, can
            //             reach the opposite answer.
            //   PX     -- a relative deadline replayed later means a later expiry.
            // Real Redis performs exactly this rewrite, to `SET key val PXAT <absolute>`.
            if (!BUG_NO_SET_REWRITE)
                propagateOverride = deadline == null
                        ? List.of("SET", key, val)
                        : List.of("SET", key, val, "PXAT", String.valueOf(deadline));
            return Resp.simple("OK");
        }
        case "SETNX": { if (s.existsNoTouch(arg(a,1))) return Resp.integer(0);
                        s.put(arg(a,1), new Store.Value(Store.Type.STRING, arg(a,2), s.now())); s.bump(arg(a,1)); return Resp.integer(1); }
        case "SETEX": { s.put(arg(a,1), new Store.Value(Store.Type.STRING, arg(a,3), s.now()));
                        s.setExpiryAt(arg(a,1), s.now() + Long.parseLong(arg(a,2)) * 1000); s.bump(arg(a,1)); return Resp.simple("OK"); }
        case "GET": { Store.Value v = s.lookup(arg(a,1)); if (v == null) return Resp.nil();
                      if (v.type != Store.Type.STRING) return wrongType(); return Resp.bulk(v.str()); }
        case "GETDEL": { Store.Value v = s.lookup(arg(a,1)); if (v == null) return Resp.nil();
                         s.delete(arg(a,1)); s.bump(arg(a,1)); return Resp.bulk(v.str()); }
        case "GETSET": { Store.Value v = s.lookup(arg(a,1));
                         s.put(arg(a,1), new Store.Value(Store.Type.STRING, arg(a,2), s.now())); s.bump(arg(a,1));
                         return v == null ? Resp.nil() : Resp.bulk(v.str()); }
        case "MSET": { for (int i = 1; i + 1 < a.size(); i += 2) { s.put(a.get(i), new Store.Value(Store.Type.STRING, a.get(i+1), s.now())); s.bump(a.get(i)); } return Resp.simple("OK"); }
        case "MGET": { List<String> out = new ArrayList<>();
                       for (int i = 1; i < a.size(); i++) { Store.Value v = s.lookup(a.get(i)); out.add(v == null ? null : v.str()); }
                       return Resp.array(out); }
        case "APPEND": { Store.Value v = s.lookup(arg(a,1)); String nv = (v == null ? "" : v.str()) + arg(a,2);
                         s.put(arg(a,1), new Store.Value(Store.Type.STRING, nv, s.now())); s.bump(arg(a,1)); return Resp.integer(nv.length()); }
        case "STRLEN": { Store.Value v = s.lookup(arg(a,1)); return Resp.integer(v == null ? 0 : v.str().length()); }

        case "INCR":   return incrBy(arg(a,1), 1);
        case "DECR":   return incrBy(arg(a,1), -1);
        case "INCRBY": return incrBy(arg(a,1), Long.parseLong(arg(a,2)));
        case "DECRBY": return incrBy(arg(a,1), -Long.parseLong(arg(a,2)));
        case "INCRBYFLOAT": {
            Store.Value v = s.lookup(arg(a,1));
            double cur = v == null ? 0 : Double.parseDouble(v.str());
            double nv = cur + Double.parseDouble(arg(a,2));
            s.put(arg(a,1), new Store.Value(Store.Type.STRING, trimDouble(nv), s.now())); s.bump(arg(a,1));
            return Resp.bulk(trimDouble(nv));
        }

        // ---------- keys, expiry ----------
        case "DEL": case "UNLINK": { int n = 0; for (int i = 1; i < a.size(); i++) if (s.delete(a.get(i))) { n++; s.bump(a.get(i)); } return Resp.integer(n); }
        case "EXISTS": { int n = 0; for (int i = 1; i < a.size(); i++) if (s.existsNoTouch(a.get(i))) n++; return Resp.integer(n); }
        case "RENAME": { Store.Value v = s.lookup(arg(a,1)); if (v == null) return Resp.error("ERR no such key");
                         s.delete(arg(a,1)); s.put(arg(a,2), v); s.bump(arg(a,1)); s.bump(arg(a,2)); return Resp.simple("OK"); }
        case "TYPE": { Store.Value v = s.lookup(arg(a,1)); return Resp.simple(v == null ? "none" : v.type.name().toLowerCase(Locale.ROOT)); }

        case "EXPIRE": case "PEXPIRE": {
            if (!s.existsNoTouch(arg(a,1))) return Resp.integer(0);
            long ms = Long.parseLong(arg(a,2)) * (name.equals("EXPIRE") ? 1000 : 1);
            long at = s.now() + ms;
            s.setExpiryAt(arg(a,1), at); s.bump(arg(a,1));
            if (BUG_RELATIVE_EXPIRE) return Resp.integer(1);   // <- planted bug 1: no rewrite
            // REWRITE FOR REPLICATION. A replica that receives "expire in 30s" a second
            // late would expire the key a second late, and the two nodes would disagree.
            // Sending the ABSOLUTE deadline makes replication deterministic. Real Redis
            // does exactly this rewrite.
            propagateOverride = List.of("PEXPIREAT", arg(a,1), String.valueOf(at));
            return Resp.integer(1);
        }
        case "PEXPIREAT": { if (!s.existsNoTouch(arg(a,1))) return Resp.integer(0);
                            s.setExpiryAt(arg(a,1), Long.parseLong(arg(a,2))); s.bump(arg(a,1)); return Resp.integer(1); }
        case "PERSIST": { if (s.expiryOf(arg(a,1)) == null) return Resp.integer(0); s.removeExpiry(arg(a,1)); s.bump(arg(a,1)); return Resp.integer(1); }
        case "TTL": case "PTTL": {
            if (!s.existsNoTouch(arg(a,1))) return Resp.integer(-2);      // -2 = no such key
            Long d = s.expiryOf(arg(a,1));
            if (d == null) return Resp.integer(-1);                        // -1 = key with no deadline
            long ms = d - s.now();
            return Resp.integer(name.equals("TTL") ? (ms + 999) / 1000 : ms);
        }

        case "KEYS": {
            // O(N) over the WHOLE keyspace, on the one thread. On a 10-million-key
            // instance this is the command that takes production down. Experiment 2.
            String pat = arg(a,1);
            List<String> out = new ArrayList<>();
            for (String k : s.liveKeys()) if (glob(pat, k)) out.add(k);
            return Resp.array(out);
        }
        case "RANDOMKEY": { List<String> ks = s.liveKeys(); return ks.isEmpty() ? Resp.nil() : Resp.bulk(ks.get(new Random().nextInt(ks.size()))); }
        case "SCAN": {
            // The safe alternative: a bounded slice per call, so the loop is never held
            // for long. The price is weaker promises -- see the doc's CORRECTNESS stage.
            int cursor = Integer.parseInt(arg(a,1));
            int count = 10;
            for (int i = 2; i + 1 < a.size(); i++) if (a.get(i).equalsIgnoreCase("COUNT")) count = Integer.parseInt(a.get(i+1));
            List<String> all = s.liveKeys();
            Collections.sort(all);
            List<String> page = new ArrayList<>();
            int i = cursor;
            for (; i < all.size() && page.size() < count; i++) page.add(all.get(i));
            int next = i >= all.size() ? 0 : i;
            return Resp.arrayRaw(List.of(Resp.bulk(String.valueOf(next)), Resp.array(page)));
        }

        // ---------- lists (queues) ----------
        case "LPUSH": case "RPUSH": {
            Store.Value v = s.lookup(arg(a,1));
            if (v == null) { v = new Store.Value(Store.Type.LIST, new ArrayDeque<String>(), s.now()); s.put(arg(a,1), v); }
            if (v.type != Store.Type.LIST) return wrongType();
            for (int i = 2; i < a.size(); i++) { if (name.equals("LPUSH")) v.list().addFirst(a.get(i)); else v.list().addLast(a.get(i)); }
            s.bump(arg(a,1));
            return Resp.integer(v.list().size());
        }
        case "LPOP": case "RPOP": {
            Store.Value v = s.lookup(arg(a,1));
            if (v == null) return Resp.nil();
            if (v.type != Store.Type.LIST) return wrongType();
            String x = name.equals("LPOP") ? v.list().pollFirst() : v.list().pollLast();
            if (x != null) { s.bump(arg(a,1)); if (v.list().isEmpty()) s.delete(arg(a,1)); }
            return x == null ? Resp.nil() : Resp.bulk(x);
        }
        case "LLEN": { Store.Value v = s.lookup(arg(a,1)); return Resp.integer(v == null ? 0 : v.list().size()); }
        case "LRANGE": {
            Store.Value v = s.lookup(arg(a,1));
            if (v == null) return Resp.array(List.of());
            List<String> l = new ArrayList<>(v.list());
            int st = idx(Integer.parseInt(arg(a,2)), l.size()), en = idx(Integer.parseInt(arg(a,3)), l.size());
            if (st > en || st >= l.size()) return Resp.array(List.of());
            return Resp.array(l.subList(Math.max(0, st), Math.min(l.size(), en + 1)));
        }
        case "LREM": { Store.Value v = s.lookup(arg(a,1)); if (v == null) return Resp.integer(0);
                       int before = v.list().size(); v.list().removeIf(x -> x.equals(arg(a,3)));
                       s.bump(arg(a,1)); return Resp.integer(before - v.list().size()); }

        // ---------- hashes ----------
        case "HSET": {
            Store.Value v = s.lookup(arg(a,1));
            if (v == null) { v = new Store.Value(Store.Type.HASH, new LinkedHashMap<String,String>(), s.now()); s.put(arg(a,1), v); }
            if (v.type != Store.Type.HASH) return wrongType();
            int added = 0;
            for (int i = 2; i + 1 < a.size(); i += 2) if (v.hash().put(a.get(i), a.get(i+1)) == null) added++;
            s.bump(arg(a,1));
            return Resp.integer(added);
        }
        case "HGET": { Store.Value v = s.lookup(arg(a,1)); return v == null ? Resp.nil() : Resp.bulk(v.hash().get(arg(a,2))); }
        case "HDEL": { Store.Value v = s.lookup(arg(a,1)); if (v == null) return Resp.integer(0);
                       int n = 0; for (int i = 2; i < a.size(); i++) if (v.hash().remove(a.get(i)) != null) n++;
                       if (n > 0) s.bump(arg(a,1)); return Resp.integer(n); }
        case "HGETALL": { Store.Value v = s.lookup(arg(a,1)); List<String> out = new ArrayList<>();
                          if (v != null) v.hash().forEach((k2,v2) -> { out.add(k2); out.add(v2); });
                          return Resp.array(out); }
        case "HLEN": { Store.Value v = s.lookup(arg(a,1)); return Resp.integer(v == null ? 0 : v.hash().size()); }
        case "HKEYS": { Store.Value v = s.lookup(arg(a,1)); return Resp.array(v == null ? List.of() : new ArrayList<>(v.hash().keySet())); }
        case "HVALS": { Store.Value v = s.lookup(arg(a,1)); return Resp.array(v == null ? List.of() : new ArrayList<>(v.hash().values())); }
        case "HEXISTS": { Store.Value v = s.lookup(arg(a,1)); return Resp.integer(v != null && v.hash().containsKey(arg(a,2)) ? 1 : 0); }
        case "HINCRBY": {
            Store.Value v = s.lookup(arg(a,1));
            if (v == null) { v = new Store.Value(Store.Type.HASH, new LinkedHashMap<String,String>(), s.now()); s.put(arg(a,1), v); }
            long nv = Long.parseLong(v.hash().getOrDefault(arg(a,2), "0")) + Long.parseLong(arg(a,3));
            v.hash().put(arg(a,2), String.valueOf(nv)); s.bump(arg(a,1));
            return Resp.integer(nv);
        }

        // ---------- sets ----------
        case "SADD": {
            Store.Value v = s.lookup(arg(a,1));
            if (v == null) { v = new Store.Value(Store.Type.SET, new LinkedHashSet<String>(), s.now()); s.put(arg(a,1), v); }
            if (v.type != Store.Type.SET) return wrongType();
            int n = 0; for (int i = 2; i < a.size(); i++) if (v.set().add(a.get(i))) n++;
            if (n > 0) s.bump(arg(a,1));
            return Resp.integer(n);
        }
        case "SREM": { Store.Value v = s.lookup(arg(a,1)); if (v == null) return Resp.integer(0);
                       int n = 0; for (int i = 2; i < a.size(); i++) if (v.set().remove(a.get(i))) n++;
                       if (n > 0) s.bump(arg(a,1)); return Resp.integer(n); }
        case "SMEMBERS": { Store.Value v = s.lookup(arg(a,1)); return Resp.array(v == null ? List.of() : new ArrayList<>(v.set())); }
        case "SISMEMBER": { Store.Value v = s.lookup(arg(a,1)); return Resp.integer(v != null && v.set().contains(arg(a,2)) ? 1 : 0); }
        case "SCARD": { Store.Value v = s.lookup(arg(a,1)); return Resp.integer(v == null ? 0 : v.set().size()); }
        case "SPOP": { Store.Value v = s.lookup(arg(a,1)); if (v == null || v.set().isEmpty()) return Resp.nil();
                       String x = v.set().iterator().next(); v.set().remove(x); s.bump(arg(a,1));
                       // Another propagation rewrite: SPOP is random, so replaying it on a
                       // replica would pick a DIFFERENT member. Send the concrete removal.
                       propagateOverride = List.of("SREM", arg(a,1), x);
                       return Resp.bulk(x); }

        // ---------- sorted sets (leaderboards) ----------
        case "ZADD": {
            Store.Value v = s.lookup(arg(a,1));
            if (v == null) { v = new Store.Value(Store.Type.ZSET, new LinkedHashMap<String,Double>(), s.now()); s.put(arg(a,1), v); }
            if (v.type != Store.Type.ZSET) return wrongType();
            int added = 0;
            for (int i = 2; i + 1 < a.size(); i += 2) if (v.zset().put(a.get(i+1), Double.parseDouble(a.get(i))) == null) added++;
            s.bump(arg(a,1));
            return Resp.integer(added);
        }
        case "ZSCORE": { Store.Value v = s.lookup(arg(a,1)); if (v == null) return Resp.nil();
                         Double d = v.zset().get(arg(a,2)); return d == null ? Resp.nil() : Resp.bulk(trimDouble(d)); }
        case "ZINCRBY": {
            Store.Value v = s.lookup(arg(a,1));
            if (v == null) { v = new Store.Value(Store.Type.ZSET, new LinkedHashMap<String,Double>(), s.now()); s.put(arg(a,1), v); }
            double nv = v.zset().getOrDefault(arg(a,3), 0.0) + Double.parseDouble(arg(a,2));
            v.zset().put(arg(a,3), nv); s.bump(arg(a,1));
            return Resp.bulk(trimDouble(nv));
        }
        case "ZCARD": { Store.Value v = s.lookup(arg(a,1)); return Resp.integer(v == null ? 0 : v.zset().size()); }
        case "ZREM": { Store.Value v = s.lookup(arg(a,1)); if (v == null) return Resp.integer(0);
                       int n = 0; for (int i = 2; i < a.size(); i++) if (v.zset().remove(a.get(i)) != null) n++;
                       if (n > 0) s.bump(arg(a,1)); return Resp.integer(n); }
        case "ZRANGE": case "ZREVRANGE": {
            Store.Value v = s.lookup(arg(a,1));
            if (v == null) return Resp.array(List.of());
            boolean rev = name.equals("ZREVRANGE") || a.stream().anyMatch(x -> x.equalsIgnoreCase("REV"));
            boolean withScores = a.stream().anyMatch(x -> x.equalsIgnoreCase("WITHSCORES"));
            List<Map.Entry<String,Double>> es = new ArrayList<>(v.zset().entrySet());
            es.sort(Map.Entry.comparingByValue());
            if (rev) Collections.reverse(es);
            int st = idx(Integer.parseInt(arg(a,2)), es.size()), en = idx(Integer.parseInt(arg(a,3)), es.size());
            List<String> out = new ArrayList<>();
            for (int i = Math.max(0, st); i <= Math.min(es.size() - 1, en); i++) {
                out.add(es.get(i).getKey());
                if (withScores) out.add(trimDouble(es.get(i).getValue()));
            }
            return Resp.array(out);
        }
        case "ZRANK": case "ZREVRANK": {
            Store.Value v = s.lookup(arg(a,1)); if (v == null) return Resp.nil();
            List<Map.Entry<String,Double>> es = new ArrayList<>(v.zset().entrySet());
            es.sort(Map.Entry.comparingByValue());
            if (name.equals("ZREVRANK")) Collections.reverse(es);
            for (int i = 0; i < es.size(); i++) if (es.get(i).getKey().equals(arg(a,2))) return Resp.integer(i);
            return Resp.nil();
        }

        // ---------- transactions ----------
        case "MULTI": c.inMulti = true; c.multiAborted = false; c.multiQueue.clear(); return Resp.simple("OK");
        case "DISCARD": c.inMulti = false; c.multiQueue.clear(); c.watched.clear(); return Resp.simple("OK");
        case "WATCH": {
            // Optimistic concurrency, exactly the expected-version idea from the
            // event-sourcing lab. WATCH does not lock anything -- it remembers a
            // version, and EXEC refuses if the version moved.
            for (int i = 1; i < a.size(); i++) c.watched.put(a.get(i), s.versionOf(a.get(i)));
            return Resp.simple("OK");
        }
        case "UNWATCH": c.watched.clear(); return Resp.simple("OK");
        case "EXEC": {
            if (!c.inMulti) return Resp.error("ERR EXEC without MULTI");
            c.inMulti = false;
            for (Map.Entry<String,Long> w : c.watched.entrySet())
                if (!Objects.equals(s.versionOf(w.getKey()), w.getValue())) {
                    c.watched.clear(); c.multiQueue.clear();
                    return Resp.nilArray();                     // somebody else touched it: abort
                }
            c.watched.clear();
            if (c.multiAborted) { c.multiQueue.clear(); return Resp.error("EXECABORT Transaction discarded"); }
            List<byte[]> out = new ArrayList<>();
            List<List<String>> q = new ArrayList<>(c.multiQueue);
            c.multiQueue.clear();
            // The batch runs with nothing interleaved -- but NOTE: there is no rollback.
            // If command 3 of 5 fails, commands 1, 2, 4 and 5 still happened.
            for (List<String> cmd : q) {
                long before = s.dirty;
                byte[] r;
                try { r = srv.commands.run(c, cmd.get(0).toUpperCase(Locale.ROOT), cmd); }
                catch (Throwable t) { r = Resp.error("ERR " + t.getMessage()); }
                if (s.dirty != before) { byte[] enc = Resp.command(takePropagateOverride(cmd)); srv.appendAof(enc); srv.propagate(enc); }
                out.add(r == null ? Resp.nil() : r);
            }
            return Resp.arrayRaw(out);
        }

        // ---------- pub/sub ----------
        case "SUBSCRIBE": {
            for (int i = 1; i < a.size(); i++) {
                c.subs.add(a.get(i));
                synchronized (srv.channels) { srv.channels.computeIfAbsent(a.get(i), x -> new HashSet<>()).add(c); }
                srv.queueOut(c, Resp.arrayRaw(List.of(Resp.bulk("subscribe"), Resp.bulk(a.get(i)), Resp.integer(c.subs.size()))));
            }
            return null;
        }
        case "UNSUBSCRIBE": { synchronized (srv.channels) { for (Set<MiniRedis.Conn> set : srv.channels.values()) set.remove(c); } c.subs.clear(); return Resp.simple("OK"); }
        case "PUBLISH": {
            // FIRE AND FORGET. Subscribers that are not connected right now never learn
            // this happened. There is no queue, no retry, no replay. Redis Streams
            // (XADD) exist because of this gap.
            Set<MiniRedis.Conn> subs;
            synchronized (srv.channels) { subs = new HashSet<>(srv.channels.getOrDefault(arg(a,1), Set.of())); }
            for (MiniRedis.Conn sub : subs) {
                srv.queueOut(sub, Resp.arrayRaw(List.of(Resp.bulk("message"), Resp.bulk(arg(a,1)), Resp.bulk(arg(a,2)))));
                srv.flushOut(sub);
            }
            return Resp.integer(subs.size());
        }

        // ---------- replication ----------
        case "SYNC": srv.doSync(c); return null;
        case "REPLCONF": { c.ackOffset = a.size() > 2 ? Long.parseLong(arg(a,2)) : 0; return Resp.simple("OK"); }
        case "REPLICAOF": {
            try { srv.replicaOf(arg(a,1), Integer.parseInt(arg(a,2))); } catch (Exception e) { return Resp.error("ERR " + e.getMessage()); }
            return Resp.simple("OK");
        }
        case "WAIT": {
            // SIMPLIFICATION (flagged): real WAIT blocks up to `timeout` waiting for acks.
            // Ours reports immediately. The lesson survives either way: WAIT tells you how
            // many replicas HAVE the write; it cannot un-acknowledge one that they don't.
            int caught = 0;
            for (MiniRedis.Conn r : srv.replicas) if (r.ackOffset >= srv.replOffset) caught++;
            return Resp.integer(caught);
        }

        // ---------- a compound atomic operation ----------
        case "RATELIMIT": {
            // SIMPLIFICATION (flagged): in real Redis this would be a Lua script or a
            // Redis Function -- there is no built-in RATELIMIT. It is native here because
            // embedding a Lua interpreter is out of scope. The POINT is identical and is
            // the whole reason "atomic Lua" appears in the rate-limiting lab: several
            // reads and writes execute as ONE indivisible unit on the server, so two
            // gateways can never both see "count = 99" and both admit a request.
            String key = arg(a,1);
            long limit = Long.parseLong(arg(a,2));
            long windowMs = Long.parseLong(arg(a,3)) * 1000;
            Store.Value v = s.lookup(key);
            long cur = v == null ? 0 : Long.parseLong(v.str());
            if (cur >= limit) {
                Long d = s.expiryOf(key);
                return Resp.arrayRaw(List.of(Resp.integer(0), Resp.integer(d == null ? 0 : Math.max(0, d - s.now()))));
            }
            s.put(key, new Store.Value(Store.Type.STRING, String.valueOf(cur + 1), s.now()));
            if (v == null) s.setExpiryAt(key, s.now() + windowMs);
            s.bump(key);
            return Resp.arrayRaw(List.of(Resp.integer(1), Resp.integer(limit - cur - 1)));
        }
        }
        return Resp.error("ERR unknown command '" + a.get(0) + "'");
    }

    // ---------- helpers ----------

    private byte[] incrBy(String key, long by) {
        Store.Value v = BUG_INCR_IGNORES_EXPIRY ? s.dict.get(key)   // <- planted bug 2: skips the deadline check
                                                : s.lookup(key);
        long cur;
        if (v == null) cur = 0;
        else if (v.type != Store.Type.STRING) return wrongType();
        else { try { cur = Long.parseLong(v.str()); } catch (NumberFormatException e) { return Resp.error("ERR value is not an integer or out of range"); } }
        long nv = cur + by;
        // THE LOAD-BEARING LINE OF THE WHOLE LAB.
        // Read, modify and write happen here with no possibility of interleaving,
        // because only one thread ever enters this method. That is Redis's atomicity:
        // not a lock, not a compare-and-swap loop -- an absence of concurrency.
        s.put(key, new Store.Value(Store.Type.STRING, String.valueOf(nv), s.now()));
        s.bump(key);
        return Resp.integer(nv);
    }

    /** How Redis reports the physical layout it chose for a value. */
    private String encodingOf(Store.Value v) {
        return switch (v.type) {
            case STRING -> { try { Long.parseLong(v.str()); yield "int"; }
                             catch (NumberFormatException e) { yield v.str().length() <= 44 ? "embstr" : "raw"; } }
            case LIST   -> v.list().size() <= 128 ? "listpack" : "quicklist";
            case HASH   -> v.hash().size() <= 128 ? "listpack" : "hashtable";
            case SET    -> v.set().stream().allMatch(Commands::isInt) && v.set().size() <= 512 ? "intset"
                           : v.set().size() <= 128 ? "listpack" : "hashtable";
            case ZSET   -> v.zset().size() <= 128 ? "listpack" : "skiplist";
        };
    }

    private static boolean isInt(String x) { try { Long.parseLong(x); return true; } catch (NumberFormatException e) { return false; } }

    /** Commands a replica must receive to reconstruct one key. Used by SYNC. */
    List<List<String>> dumpValue(String k, Store.Value v) {
        List<List<String>> out = new ArrayList<>();
        switch (v.type) {
            case STRING -> out.add(List.of("SET", k, v.str()));
            case LIST   -> { List<String> c = new ArrayList<>(List.of("RPUSH", k)); c.addAll(v.list()); if (c.size() > 2) out.add(c); }
            case SET    -> { List<String> c = new ArrayList<>(List.of("SADD", k)); c.addAll(v.set()); if (c.size() > 2) out.add(c); }
            case HASH   -> { List<String> c = new ArrayList<>(List.of("HSET", k)); v.hash().forEach((a,b) -> { c.add(a); c.add(b); }); if (c.size() > 2) out.add(c); }
            case ZSET   -> { List<String> c = new ArrayList<>(List.of("ZADD", k)); v.zset().forEach((m,sc) -> { c.add(trimDouble(sc)); c.add(m); }); if (c.size() > 2) out.add(c); }
        }
        return out;
    }

    private static byte[] wrongType() { return Resp.error("WRONGTYPE Operation against a key holding the wrong kind of value"); }
    private static String arg(List<String> a, int i) { return i < a.size() ? a.get(i) : ""; }
    private static int idx(int i, int size) { return i < 0 ? size + i : i; }
    private static String trimDouble(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d); }

    private static boolean glob(String pat, String s) {
        return s.matches(pat.replace(".", "\\.").replace("*", ".*").replace("?", "."));
    }
}
