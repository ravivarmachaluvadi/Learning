import java.util.*;

/**
 * EXERCISE (rung 5): implement the two halves of a safe lease-based lock —
 * the SERVER's grant rule and the RESOURCE's fencing rule.
 *
 *   java src/YourLock.java
 *
 * The two methods below currently contain naive versions. Replace their
 * bodies until all 6 checks pass. SOLUTIONS.md has the answers.
 */
public class YourLock {

    /** The lock server's state, reduced to what the grant rule needs. */
    static class Srv {
        Integer holder = null;   // client id, or null
        long token = 0;          // last granted token
        int expiresAt = Integer.MAX_VALUE;
        int ttl = 30;
    }

    /** The resource's state, reduced to what the fencing rule needs. */
    static class ResState {
        long maxToken = -1;      // highest token this resource has ever seen
    }

    /**
     * TODO #1 — the grant rule.
     * Must: grant when the lock is free OR the current lease has expired
     * (now >= expiresAt); on every grant, hand out a token STRICTLY larger
     * than any previous one and start a fresh TTL. Return the token, or -1
     * if the lock is legitimately held.
     * (Currently: grants only when free — an expired lease blocks forever —
     * and reuses the same token for every grant.)
     */
    static long yourAcquire(Srv s, int now, int cliId) {
        if (s.holder == null) {
            s.holder = cliId;
            s.expiresAt = now + s.ttl;
            return 1;                    // every grant gets "token 1"
        }
        return -1;
    }

    /**
     * TODO #2 — the fencing rule, applied to EVERY operation on the resource.
     * `isWrite` false means a read: it must never be lost data's fault —
     * a read RAISES the floor so older in-flight writes get rejected later.
     * Must: reject any operation whose token is lower than the highest seen;
     * otherwise accept and remember the token. Return true = accepted.
     * (Currently: accepts everything.)
     */
    static boolean yourFenceCheck(ResState r, long token, boolean isWrite) {
        return true;
    }

    // ------------------------------------------------------------------ checks

    static int passed = 0, failed = 0;
    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  PASS  " + name); }
        else    { failed++; System.out.println("  FAIL  " + name + "  [" + detail + "]"); }
    }

    public static void main(String[] args) {
        // 1: first acquire is granted
        Srv s = new Srv();
        long t1 = yourAcquire(s, 0, 0);
        check("1_first_acquire_granted", t1 > 0, "token=" + t1);

        // 2: a live lease is exclusive
        long t2 = yourAcquire(s, 10, 1);
        check("2_live_lease_is_exclusive", t2 == -1, "second acquire returned " + t2);

        // 3: an expired lease is granted to the next asker...
        long t3 = yourAcquire(s, 31, 1);
        check("3_expired_lease_is_regranted", t3 > 0, "token=" + t3);

        // 4: ...with a STRICTLY larger token (this is what fencing will lean on)
        check("4_token_strictly_increases", t3 > t1, "t1=" + t1 + " t3=" + t3);

        // 5: the resource rejects a stale write after seeing a newer token
        ResState r = new ResState();
        boolean w1 = yourFenceCheck(r, t3, true);   // new holder writes first
        boolean w2 = yourFenceCheck(r, t1, true);   // old holder's late write
        check("5_stale_write_rejected", w1 && !w2, "newer=" + w1 + " older=" + w2);

        // 6: a READ raises the floor — the in-flight-write race from the lab
        ResState r2 = new ResState();
        boolean read = yourFenceCheck(r2, 7, false); // new holder READS with token 7
        boolean late = yourFenceCheck(r2, 6, true);  // old holder's write lands after
        check("6_read_raises_the_floor", read && !late, "read=" + read + " lateWrite=" + late);

        System.out.println();
        System.out.println(passed + "/6 passed" + (failed == 0 ? " — done!" : " — keep going"));
    }
}
