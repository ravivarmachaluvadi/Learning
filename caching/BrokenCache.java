import java.util.*;

/**
 * EXERCISE 6.1 — this LRU cache has exactly ONE planted bug.
 *
 *   java BrokenCache.java
 *
 * A check will fail. Before you fix it, answer:
 *   - which invariant from README stage 12 does it violate?
 *   - what would the production symptom be? (What does a customer see?
 *     What does the on-call engineer see on a dashboard?)
 *   - would a hit-ratio dashboard catch it?
 *
 * The answer is in SOLUTIONS.md, rung 6. Fix it and all four checks pass.
 */
public class BrokenCache {

    static class BuggyLruCache {
        static class Node { int key, value; Node prev, next; Node(int k, int v) { key = k; value = v; } }
        final int capacity;
        final Map<Integer, Node> map = new HashMap<>();
        final Node head = new Node(-1, -1), tail = new Node(-1, -1);

        BuggyLruCache(int capacity) {
            this.capacity = capacity;
            head.next = tail;
            tail.prev = head;
        }

        private void unlink(Node n) { n.prev.next = n.next; n.next.prev = n.prev; }
        private void linkAtHead(Node n) { n.next = head.next; n.prev = head; head.next.prev = n; head.next = n; }

        Integer get(int key) {
            Node n = map.get(key);
            if (n == null) return null;
            unlink(n);
            linkAtHead(n);
            return n.value;
        }

        void put(int key, int value) {
            Node n = map.get(key);
            if (n != null) { n.value = value; unlink(n); linkAtHead(n); return; }
            if (map.size() == capacity) {
                Node victim = tail.prev;
                unlink(victim);
                // ── the planted bug lives on the next line ──
                // (the node is removed from the list, but is it removed from everywhere?)
            }
            n = new Node(key, value);
            map.put(key, n);
            linkAtHead(n);
        }

        int listSize() {   // walks the linked list, independent of the map
            int count = 0;
            for (Node cur = head.next; cur != tail; cur = cur.next) count++;
            return count;
        }

        int mapSize() { return map.size(); }
    }

    static int failed = 0;
    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name + (ok ? "" : "   → " + detail));
        if (!ok) failed++;
    }

    public static void main(String[] args) {
        System.out.println("═".repeat(72));
        System.out.println("BrokenCache — one planted bug. Diagnose before you fix.\n");

        BuggyLruCache c = new BuggyLruCache(3);
        for (int i = 1; i <= 100; i++) c.put(i, i * 10);   // 100 inserts into a 3-entry cache

        int listSize = c.listSize(), mapSize = c.mapSize();
        Integer oldKey = c.get(1);   // read once: calling get() twice would mutate the state we're asserting on

        check("capacity_invariant_holds_for_list", listSize <= 3,
              "linked list holds " + listSize + " nodes, capacity is 3");
        check("capacity_invariant_holds_for_map", mapSize <= 3,
              "map holds " + mapSize + " entries, capacity is 3 — memory grows without bound");
        check("evicted_key_is_really_gone", oldKey == null,
              "key 1 should have been evicted long ago, but get(1) returned " + oldKey);
        check("most_recent_keys_are_present", c.get(100) != null && c.get(99) != null,
              "recent keys should still be cached");

        System.out.println();
        if (failed == 0) {
            System.out.println("All checks pass — the bug is fixed. Now re-read your diagnosis:");
            System.out.println("did the symptom you predicted match the checks that failed?");
        } else {
            System.out.println(failed + " check(s) failed. Two clues, in this order:");
            System.out.println("  1. The eviction branch removes the victim from ONE of the two data");
            System.out.println("     structures that together make up this cache. Which one is missed?");
            System.out.println("  2. Then ask why the damage is far worse than 'one stale node': look at");
            System.out.println("     the CONDITION that guards the eviction branch, and work out how many");
            System.out.println("     times it can ever be true once that structure starts growing.");
        }
        System.out.println("═".repeat(72));
    }
}
