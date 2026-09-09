import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** The version everybody writes first. Kept because it is the correctness oracle. */
final class NaiveIndex implements Index {
    private final Map<String, Loc> all = new ConcurrentHashMap<>();

    public void update(String driverId, Loc loc) { all.put(driverId, loc); }
    public void remove(String driverId) { all.remove(driverId); }

    public List<String> near(Loc centre, double radiusM) {
        List<String> out = new ArrayList<>();
        // THE PROBLEM, in one loop: every driver in the city is touched to answer
        // a question about 500 metres of it.
        for (Map.Entry<String, Loc> e : all.entrySet()) {
            if (centre.distanceTo(e.getValue()) <= radiusM) out.add(e.getKey());
        }
        return out;
    }
    public String name() { return "NaiveIndex(full scan)"; }
}
