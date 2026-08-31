import java.util.List;

/**
 * "Where are the drivers near this point?" -- the one question the dispatch system
 * asks millions of times a day, and the one write path (update) it takes millions of
 * times a minute.
 *
 * Two implementations race in this lab:
 *   NaiveIndex - measure the distance to every driver in the city. Correct, O(N).
 *   CellIndex  - chop the map into squares; touch only the squares near the rider.
 * The entire performance argument of ride dispatch is that one swap.
 */
interface Index {
    /** Insert or move a driver. Called on EVERY location ping -- the hot write path. */
    void update(String driverId, Loc loc);
    void remove(String driverId);
    /** Ids whose last known position is within `radiusM` of `centre`. */
    List<String> near(Loc centre, double radiusM);
    String name();
}
