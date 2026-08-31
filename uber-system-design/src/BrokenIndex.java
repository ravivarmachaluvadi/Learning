import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FOR EXERCISE 6 (debug). This index has exactly one planted bug.
 *
 * It is the kind of bug that passes review, works in every manual test and
 * degrades slowly in production. Run `java src/Tests.java broken`, look at which
 * tests fail, and work out from the failures what is wrong and which guarantee
 * it breaks -- before reading SOLUTIONS.md.
 *
 * Design note so you are debugging the bug and not the design: unlike CellIndex,
 * this one stores each driver's position INSIDE the cell entry (a common and
 * perfectly reasonable choice -- it saves a second map lookup per candidate).
 */
final class BrokenIndex implements Index {

    private final double cellDegLat, cellDegLon, cellMetres;
    /** cell key -> (driver id -> the position it had when it was filed here). */
    private final Map<Long, Map<String, Loc>> cells = new ConcurrentHashMap<>();
    private final Map<String, Long> driverCell = new ConcurrentHashMap<>();

    BrokenIndex(double cellMetres) {
        this.cellMetres = cellMetres;
        this.cellDegLat = cellMetres / Loc.M_PER_DEG_LAT;
        this.cellDegLon = cellMetres / (Loc.M_PER_DEG_LAT * Math.cos(Math.toRadians(12.97)));
    }

    private int ixOf(Loc l) { return (int) Math.floor(l.lat() / cellDegLat); }
    private int iyOf(Loc l) { return (int) Math.floor(l.lon() / cellDegLon); }
    private static long pack(int ix, int iy) { return ((long) ix << 32) | (iy & 0xffffffffL); }

    public void update(String driverId, Loc loc) {
        long newKey = pack(ixOf(loc), iyOf(loc));
        cells.computeIfAbsent(newKey, k -> new ConcurrentHashMap<>()).put(driverId, loc);
        driverCell.put(driverId, newKey);
    }

    public void remove(String driverId) {
        Long k = driverCell.remove(driverId);
        if (k != null) {
            Map<String, Loc> s = cells.get(k);
            if (s != null) s.remove(driverId);
        }
    }

    public List<String> near(Loc centre, double radiusM) {
        int ix = ixOf(centre), iy = iyOf(centre);
        int ring = (int) Math.ceil(radiusM / cellMetres);
        List<String> out = new ArrayList<>();
        for (int dx = -ring; dx <= ring; dx++) for (int dy = -ring; dy <= ring; dy++) {
            Map<String, Loc> s = cells.get(pack(ix + dx, iy + dy));
            if (s == null) continue;
            for (Map.Entry<String, Loc> e : s.entrySet())
                if (centre.distanceTo(e.getValue()) <= radiusM && !out.contains(e.getKey()))
                    out.add(e.getKey());
        }
        return out;
    }

    public String name() { return "BrokenIndex(" + Math.round(cellMetres) + "m cells)"; }
}
