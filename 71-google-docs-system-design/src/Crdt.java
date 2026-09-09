import java.util.*;

/**
 * The other answer: a sequence CRDT (this one is RGA — Replicated Growable Array).
 *
 * Transformation fixes positions AFTER the fact. A CRDT removes the problem
 * instead: every character gets a permanent identity when it is typed, and an
 * edit refers to that identity rather than to a position. Identities never
 * shift, so there is nothing to rewrite — and therefore nothing that needs a
 * central server to order it first.
 *
 * Run:  java src/Crdt.java
 */
public final class Crdt {

    /** A character's permanent name: a Lamport clock plus who typed it. Never reused. */
    record Id(int clock, String site) implements Comparable<Id> {
        public int compareTo(Id o) { return clock != o.clock ? Integer.compare(clock, o.clock) : site.compareTo(o.site); }
        @Override public String toString() { return site.isEmpty() ? "the start of the document" : "'" + clock + "@" + site + "'"; }
    }

    sealed interface Op permits Ins, Del {}
    /** "put `ch`, named `id`, immediately after the character named `after`". */
    record Ins(Id id, Id after, char ch) implements Op {}
    /** "the character named `id` is gone". */
    record Del(Id id) implements Op {}

    static final Id HEAD = new Id(0, "");   // the invisible start-of-document marker

    static final class Cell {
        final Id id; final char ch; boolean deleted;
        Cell(Id id, char ch) { this.id = id; this.ch = ch; }
    }

    /** One person's copy. There is no server anywhere in this class. */
    static final class Replica {
        final String site;
        final List<Cell> cells = new ArrayList<>();      // includes tombstones
        final Set<Id> known = new HashSet<>();
        final List<Ins> waitingForParent = new ArrayList<>();
        /** Deletes that arrived before the character they delete. Without this, a
         *  delete overtaking its own insert is silently lost and the replicas
         *  drift apart — the fuzz test in anyOrderConverges() found exactly that. */
        final Set<Id> deletedBeforeSeen = new HashSet<>();
        int clock = 0;

        Replica(String site) { this.site = site; cells.add(new Cell(HEAD, '\0')); known.add(HEAD); }

        String text() {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < cells.size(); i++) if (!cells.get(i).deleted) sb.append(cells.get(i).ch);
            return sb.toString();
        }

        int tombstones() { int n = 0; for (Cell c : cells) if (c.deleted) n++; return n; }

        /** Position in the VISIBLE text -> the id of the character it must follow. */
        Id idBeforeVisible(int visiblePos) {
            int seen = 0;
            for (int i = 1; i < cells.size(); i++) {
                if (cells.get(i).deleted) continue;
                if (seen == visiblePos) return cells.get(i - 1 >= 0 ? i - 1 : 0).id;
                seen++;
            }
            return cells.get(cells.size() - 1).id;
        }

        Op localInsert(int visiblePos, char ch) {
            Id after = anchorFor(visiblePos);
            Ins op = new Ins(new Id(++clock, site), after, ch);
            apply(op);
            return op;
        }

        Id anchorFor(int visiblePos) {
            if (visiblePos == 0) return HEAD;
            int seen = 0;
            for (int i = 1; i < cells.size(); i++) {
                if (cells.get(i).deleted) continue;
                seen++;
                if (seen == visiblePos) return cells.get(i).id;
            }
            return cells.get(cells.size() - 1).id;
        }

        Op localDelete(int visiblePos) {
            int seen = 0;
            for (int i = 1; i < cells.size(); i++) {
                if (cells.get(i).deleted) continue;
                if (seen == visiblePos) { Del op = new Del(cells.get(i).id); apply(op); return op; }
                seen++;
            }
            throw new IllegalArgumentException("no visible character at " + visiblePos);
        }

        /**
         * Applying somebody else's op. Two properties matter and both are visible here:
         *  - COMMUTATIVE: the result does not depend on the order ops arrive in.
         *  - IDEMPOTENT: applying the same op twice changes nothing, so a duplicate
         *    delivery is harmless — no idempotency key needed.
         */
        void apply(Op op) {
            if (op instanceof Del d) {
                for (Cell c : cells) if (c.id.equals(d.id)) { c.deleted = true; return; }
                deletedBeforeSeen.add(d.id());   // remember it; the character has not arrived yet
                return;
            }
            Ins ins = (Ins) op;
            if (known.contains(ins.id())) return;            // duplicate: ignore
            if (!known.contains(ins.after())) { waitingForParent.add(ins); return; } // wait for causality

            clock = Math.max(clock, ins.id().clock());
            int i = indexOf(ins.after()) + 1;
            // Concurrent inserts anchored at the same character are ordered by id —
            // the same rule, evaluated identically on every replica, forever.
            while (i < cells.size() && cells.get(i).id.compareTo(ins.id()) > 0) i++;
            Cell cell = new Cell(ins.id(), ins.ch());
            if (deletedBeforeSeen.remove(ins.id())) cell.deleted = true; // it was already deleted elsewhere
            cells.add(i, cell);
            known.add(ins.id());

            // Anything that was waiting for this character can now be placed.
            List<Ins> retry = new ArrayList<>(waitingForParent);
            waitingForParent.clear();
            for (Ins w : retry) apply(w);
        }

        int indexOf(Id id) {
            for (int i = 0; i < cells.size(); i++) if (cells.get(i).id.equals(id)) return i;
            return 0;
        }
    }

    // ------------------------------------------------------------------ demos

    public static void main(String[] args) {
        sameStoryNoServer();
        anyOrderConverges();
        theBill();
    }

    static void sameStoryNoServer() {
        banner("The HAT story again — this time with no server at all");
        Replica alice = new Replica("Alice"), bob = new Replica("Bob");
        for (char ch : "HAT".toCharArray()) bob.apply(alice.localInsert(alice.text().length(), ch));

        Op a = alice.localInsert(0, 'C');   // Alice: C at the front
        Op b = bob.localInsert(3, 'S');     // Bob:   S at the end
        System.out.println("   Alice types C -> \"" + alice.text() + "\"");
        System.out.println("   Bob types S   -> \"" + bob.text() + "\"");
        System.out.println("   Alice's op says: put 'C' after " + ((Ins) a).after() + "  (a character, not a position)");
        System.out.println("   Bob's op says:   put 'S' after " + ((Ins) b).after());
        bob.apply(a); alice.apply(b);
        System.out.println("   after swapping ops: Alice=\"" + alice.text() + "\"  Bob=\"" + bob.text() + "\"");
        System.out.println("   Nothing was rewritten. 'after the T' means the same thing on both machines.\n");
    }

    static void anyOrderConverges() {
        banner("500 random delivery orders, 3 people, no server, duplicates included");
        int diverged = 0;
        for (int seed = 0; seed < 500; seed++) {
            Random rnd = new Random(seed);
            String[] sites = {"Alice", "Bob", "Carol"};
            Map<String, Replica> reps = new LinkedHashMap<>();
            for (String s : sites) reps.put(s, new Replica(s));
            List<Object[]> pending = new ArrayList<>(); // {targetSite, op}

            for (int step = 0; step < 40; step++) {
                String site = sites[rnd.nextInt(3)];
                Replica r = reps.get(site);
                Op op;
                if (rnd.nextInt(100) < 25 && !r.text().isEmpty()) op = r.localDelete(rnd.nextInt(r.text().length()));
                else op = r.localInsert(rnd.nextInt(r.text().length() + 1), (char) ('a' + rnd.nextInt(4)));
                for (String other : sites) if (!other.equals(site)) {
                    pending.add(new Object[]{other, op});
                    if (rnd.nextInt(100) < 20) pending.add(new Object[]{other, op}); // duplicate delivery
                }
                for (int k = rnd.nextInt(4); k > 0 && !pending.isEmpty(); k--) {
                    Object[] m = pending.remove(rnd.nextInt(pending.size()));
                    reps.get((String) m[0]).apply((Op) m[1]);
                }
            }
            while (!pending.isEmpty()) {
                Object[] m = pending.remove(rnd.nextInt(pending.size()));
                reps.get((String) m[0]).apply((Op) m[1]);
            }
            String first = reps.get("Alice").text();
            for (Replica r : reps.values()) if (!r.text().equals(first)) { diverged++; break; }
        }
        System.out.println("   diverged in " + diverged + " of 500 runs");
        System.out.println("   No server ordered anything. Ops arrived shuffled and sometimes twice.\n");
    }

    static void theBill() {
        banner("What it costs");
        Replica r = new Replica("Alice");
        for (int i = 0; i < 1000; i++) r.localInsert(r.text().length(), 'x');
        for (int i = 0; i < 900; i++) r.localDelete(0);
        System.out.println("   visible characters : " + r.text().length());
        System.out.println("   cells still stored : " + (r.cells.size() - 1) + "  (of which " + r.tombstones() + " are tombstones)");
        System.out.println("   Every character carries a clock, a site name and a parent id — call it 30+ bytes");
        System.out.println("   to store one byte of text, and deleted characters are still there.");
        System.out.println("   A CRDT trades memory and garbage collection for not needing a central server.\n");
    }

    static void banner(String s) {
        System.out.println("\n===========================================================================");
        System.out.println(" " + s);
        System.out.println("===========================================================================");
    }
}
