import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The grid: chop the city into fixed squares ("cells"), keep a set of driver ids
 * per square.
 *
 *   a location ping  -> touches at most two cells (leave one, join one), O(1)
 *   a "who's near?"  -> touches only the cells that overlap the search circle
 *
 * Cells are square in METRES, not in degrees. A degree of longitude is only
 * cos(latitude) as wide as a degree of latitude, so a grid built in raw degrees
 * makes rectangles that get thinner towards the poles -- and then a "ring of
 * neighbours" is not a ring. We pin the longitude size to one reference latitude
 * for the city.
 *   SIMPLIFICATION: that trick works for one city. A global system cannot pin a
 *   latitude, which is exactly why real ones use S2 or H3 cells instead of a
 *   flat grid (see the PRODUCTION stage of the README).
 *
 * `searchNeighbours = false` reproduces mechanism v1 from the doc: look only in
 * the rider's own cell. It is faster, it demos beautifully, and it is wrong at
 * every cell edge -- Experiments.java measures exactly how wrong.
 */
final class CellIndex implements Index {

    private final double cellDegLat, cellDegLon;
    private final double cellMetres;
    private final boolean searchNeighbours;

    /** cell key -> driver ids in that cell. This map IS the index. */
    private final Map<Long, Set<String>> cells = new ConcurrentHashMap<>();
    /** driver id -> the cell it is currently filed under, so a move knows what to leave. */
    private final Map<String, Long> driverCell = new ConcurrentHashMap<>();
    private final Map<String, Loc> driverLoc = new ConcurrentHashMap<>();

    /** Instrumentation the experiments read. */
    volatile long lastCandidatesExamined;
    volatile long lastCellsVisited;

    CellIndex(double cellMetres, boolean searchNeighbours) { this(cellMetres, searchNeighbours, 12.97); }

    CellIndex(double cellMetres, boolean searchNeighbours, double refLat) {
        this.cellMetres = cellMetres;
        this.cellDegLat = cellMetres / Loc.M_PER_DEG_LAT;
        this.cellDegLon = cellMetres / (Loc.M_PER_DEG_LAT * Math.cos(Math.toRadians(refLat)));
        this.searchNeighbours = searchNeighbours;
    }

    private int ixOf(Loc l) { return (int) Math.floor(l.lat() / cellDegLat); }
    private int iyOf(Loc l) { return (int) Math.floor(l.lon() / cellDegLon); }
    private static long pack(int ix, int iy) { return ((long) ix << 32) | (iy & 0xffffffffL); }

    public void update(String driverId, Loc loc) {
        long newKey = pack(ixOf(loc), iyOf(loc));
        Long oldKey = driverCell.get(driverId);
        driverLoc.put(driverId, loc);
        // THE CHEAP WRITE: a driver who moved 20 m inside the same cell changes
        // nothing in the index at all. Most pings are exactly this one branch.
        if (oldKey != null && oldKey == newKey) return;
        if (oldKey != null) {
            Set<String> s = cells.get(oldKey);
            if (s != null) s.remove(driverId);
        }
        cells.computeIfAbsent(newKey, k -> ConcurrentHashMap.newKeySet()).add(driverId);
        driverCell.put(driverId, newKey);
    }

    public void remove(String driverId) {
        Long k = driverCell.remove(driverId);
        driverLoc.remove(driverId);
        if (k != null) {
            Set<String> s = cells.get(k);
            if (s != null) s.remove(driverId);
        }
    }

    /**
     * Cover the whole circle: every cell that could hold a driver within radiusM.
     * Complete, and its cost is the AREA in cells -- 3 km at 500 m cells is 169
     * cells whether the city has five drivers or five thousand.
     */
    public List<String> near(Loc centre, double radiusM) {
        int maxRing = searchNeighbours ? (int) Math.ceil(radiusM / cellMetres) : 0;
        List<String> out = new ArrayList<>();
        long examined = 0, visited = 0;
        for (int k = 0; k <= maxRing; k++) {
            long[] c = collectRing(centre, k, radiusM, out);
            visited += c[0]; examined += c[1];
        }
        lastCellsVisited = visited;
        lastCandidatesExamined = examined;
        return out;
    }

    /**
     * Ring-by-ring expansion: start in the rider's own cell, widen one ring at a
     * time, and STOP as soon as we hold enough candidates.
     *
     * The trade this makes is real and worth saying out loud: stopping early can
     * miss a driver just outside the last ring who would have had a better ETA.
     * We buy a bounded, supply-dependent search cost with a small amount of match
     * quality. Dense downtown -> stops at ring 0 or 1. Empty outskirts -> keeps
     * widening until maxRadiusM.
     */
    List<String> nearProgressive(Loc centre, double maxRadiusM, int minCandidates) {
        int maxRing = (int) Math.ceil(maxRadiusM / cellMetres);
        List<String> out = new ArrayList<>();
        long examined = 0, visited = 0;
        for (int k = 0; k <= maxRing; k++) {
            long[] c = collectRing(centre, k, maxRadiusM, out);
            visited += c[0]; examined += c[1];
            if (out.size() >= minCandidates) break;
        }
        lastCellsVisited = visited;
        lastCandidatesExamined = examined;
        return out;
    }

    /** The k-th square shell of cells around the centre. k=0 is the centre cell itself. */
    private long[] collectRing(Loc centre, int k, double radiusM, List<String> out) {
        int ix = ixOf(centre), iy = iyOf(centre);
        long visited = 0, examined = 0;
        for (int dx = -k; dx <= k; dx++) {
            for (int dy = -k; dy <= k; dy++) {
                if (Math.max(Math.abs(dx), Math.abs(dy)) != k) continue;   // shell only
                Set<String> s = cells.get(pack(ix + dx, iy + dy));
                visited++;
                if (s == null) continue;
                for (String id : s) {
                    Loc l = driverLoc.get(id);
                    examined++;
                    // Cells narrow the search; the exact distance check is still needed,
                    // because a cell is a square and the query is a circle.
                    if (l != null && centre.distanceTo(l) <= radiusM) out.add(id);
                }
            }
        }
        return new long[]{visited, examined};
    }

    int cellCount() { return (int) cells.values().stream().filter(s -> !s.isEmpty()).count(); }

    int sizeOfCellAt(Loc l) {
        Set<String> s = cells.get(pack(ixOf(l), iyOf(l)));
        return s == null ? 0 : s.size();
    }

    public String name() {
        return "CellIndex(" + Math.round(cellMetres) + "m cells, "
                + (searchNeighbours ? "ring search" : "OWN CELL ONLY") + ")";
    }
}
