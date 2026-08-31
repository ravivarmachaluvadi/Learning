import java.util.*;

/**
 * One write, plus everything the system knows about where it sits in history.
 *
 *   deps      — the writes this one causally depends on. THE field this lab is
 *               about: if a replica shows you this write, it must already be
 *               showing you every write in here, or you are looking at an
 *               effect whose cause is invisible.
 *   nearest   — the same information with the redundant entries removed
 *               (COPS's optimisation), kept only so we can measure the saving.
 *   lamport   — a single counter, higher than every counter its author had
 *               seen. Cheap, and it loses something; §10 measures what.
 *   vv        — one counter per replica. The compact way to say the same thing
 *               `deps` says, and the reason production systems ship this.
 */
public record Write(Dot id, String key, String value,
                    Set<Dot> deps, Set<Dot> nearest,
                    long lamport, long[] vv) {

    /** Rendered the way a log line would show it. */
    @Override public String toString() {
        return id + " " + key + "=" + value + " deps" + new TreeSet<>(deps);
    }

    public String shortLabel() { return key + "=" + value; }

    /** Bytes we would put on the wire for the ordering metadata, roughly. */
    public int metadataCells(Rule rule) {
        return switch (rule.ordering()) {
            case NONE -> 0;
            case LAMPORT -> 1;
            case VV -> vv.length;
            case DEPS -> rule.nearestOnly() ? nearest.size() : deps.size();
        };
    }
}
