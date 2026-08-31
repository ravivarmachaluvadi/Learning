import java.util.*;

/**
 * Operational Transformation, for a document that is just a plain string.
 *
 * There are only two kinds of change a person can make here: insert one
 * character at a position, or delete the character at a position. Real editors
 * work on ranges and rich text; one character at a time is enough to show the
 * whole idea, and every rule below has a direct counterpart in the real thing.
 *
 * The load-bearing method is transform(). Everything else is bookkeeping.
 */
public final class Ot {

    public enum Kind { INSERT, DELETE, NOOP }

    /** One change. Immutable: transforming an op produces a NEW op. */
    public static final class Op {
        public final Kind kind;
        public final int pos;
        public final char ch;    // only meaningful for INSERT
        public final String site; // who typed it; used ONLY as a deterministic tie-break

        private Op(Kind kind, int pos, char ch, String site) {
            this.kind = kind; this.pos = pos; this.ch = ch; this.site = site;
        }
        public static Op insert(int pos, char ch, String site) { return new Op(Kind.INSERT, pos, ch, site); }
        public static Op delete(int pos, String site)          { return new Op(Kind.DELETE, pos, '\0', site); }
        public static Op noop(String site)                     { return new Op(Kind.NOOP, 0, '\0', site); }

        public Op withPos(int newPos) { return new Op(kind, newPos, ch, site); }

        @Override public String toString() {
            return switch (kind) {
                case INSERT -> site + ":insert('" + ch + "' at " + pos + ")";
                case DELETE -> site + ":delete(at " + pos + ")";
                case NOOP   -> site + ":nothing";
            };
        }
    }

    /** Run one change against a document. Throws if the position is impossible. */
    public static String apply(String doc, Op op) {
        switch (op.kind) {
            case NOOP: return doc;
            case INSERT:
                if (op.pos < 0 || op.pos > doc.length())
                    throw new IllegalStateException("insert at " + op.pos + " but doc is only " + doc.length() + " long: \"" + doc + "\"");
                return doc.substring(0, op.pos) + op.ch + doc.substring(op.pos);
            default:
                if (op.pos < 0 || op.pos >= doc.length())
                    throw new IllegalStateException("delete at " + op.pos + " but doc is only " + doc.length() + " long: \"" + doc + "\"");
                return doc.substring(0, op.pos) + doc.substring(op.pos + 1);
        }
    }

    /**
     * Same as apply(), but clamps an impossible position instead of throwing.
     * Only the no-transform ("raw") mode uses this: without transformation the
     * positions genuinely go out of range, and a real naive editor would either
     * crash or silently corrupt the text. We clamp so the experiment can finish
     * and show you the divergence rather than a stack trace.
     */
    public static String applyLenient(String doc, Op op) {
        switch (op.kind) {
            case NOOP: return doc;
            case INSERT: {
                int p = Math.max(0, Math.min(op.pos, doc.length()));
                return doc.substring(0, p) + op.ch + doc.substring(p);
            }
            default: {
                if (doc.isEmpty()) return doc;
                int p = Math.max(0, Math.min(op.pos, doc.length() - 1));
                return doc.substring(0, p) + doc.substring(p + 1);
            }
        }
    }

    /**
     * THE RULE THAT MAKES COLLABORATIVE EDITING WORK.
     *
     * transform(a, b) answers one question: "a was written against a document
     * that did NOT contain b. What would a have to be, to have the same effect
     * on a document that DOES contain b?"
     *
     * Every branch below is one sentence of common sense about positions.
     */
    /** Counts how much work transformation actually costs. Reset it before measuring. */
    public static long transformCount = 0;

    public static Op transform(Op a, Op b) {
        transformCount++;
        if (a.kind == Kind.NOOP || b.kind == Kind.NOOP) return a;

        if (a.kind == Kind.INSERT && b.kind == Kind.INSERT) {
            // b inserted a character before mine -> everything after it shifted right.
            if (b.pos < a.pos) return a.withPos(a.pos + 1);
            if (b.pos > a.pos) return a;
            // Exact same spot. Somebody has to go first, and BOTH sides must pick
            // the same somebody or the two copies end up with the letters in
            // opposite orders. Site id is an arbitrary but identical tie-break.
            // This is the invariant: concurrent inserts at one position are
            // ordered by site id, everywhere, forever.
            return a.site.compareTo(b.site) < 0 ? a : a.withPos(a.pos + 1);
        }

        if (a.kind == Kind.INSERT && b.kind == Kind.DELETE) {
            // b removed a character before my insertion point -> shift left.
            return b.pos < a.pos ? a.withPos(a.pos - 1) : a;
        }

        if (a.kind == Kind.DELETE && b.kind == Kind.INSERT) {
            // b added a character at or before the one I want to delete -> shift right.
            return b.pos <= a.pos ? a.withPos(a.pos + 1) : a;
        }

        // Both deletes.
        if (b.pos < a.pos) return a.withPos(a.pos - 1);
        if (b.pos > a.pos) return a;
        // We both deleted the SAME character. It is already gone; deleting again
        // would eat an innocent neighbour. My op becomes nothing at all.
        return Op.noop(a.site);
    }

    private Ot() {}
}
