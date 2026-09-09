import java.util.List;

/**
 * The one question this whole lab is about:
 *
 *      given a key, which machine should hold it?
 *
 * Every strategy below answers it differently, and putting them behind one interface
 * is what lets the experiments run the SAME workload through each and compare the
 * answers directly. That comparison is the lab.
 *
 * The critical property to keep in mind while reading the implementations: every
 * client must be able to answer this question ALONE, from the membership list, with
 * no lookup and no coordination. A strategy that needs to ask somebody has not solved
 * the problem -- it has moved it.
 */
interface Placement {

    /** Human-readable name, used in experiment output. */
    String name();

    /** Add a machine to the set. */
    void addNode(String node);

    /** Remove a machine from the set. */
    void removeNode(String node);

    /** Which single machine owns this key? */
    String owner(String key);

    /**
     * The first r DISTINCT machines that should hold copies of this key.
     * Returns fewer than r only if there are fewer than r machines.
     */
    List<String> owners(String key, int r);

    /** How many machines are currently in the set. */
    int size();

    /**
     * Every machine in the set, including ones currently holding no keys at all.
     * Load metrics MUST be computed against this, not against the machines that
     * happen to appear in a tally -- otherwise "one machine got everything" scores as
     * perfectly balanced, because there is only one machine in the tally.
     */
    List<String> nodes();
}
