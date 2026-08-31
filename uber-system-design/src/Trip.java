final class Trip {
    final String id;
    final String riderId;
    final Loc pickup;
    final Loc dropoff;
    volatile String driverId;
    volatile TripState state = TripState.REQUESTED;
    /** Drivers already offered this trip and lost/declined -- never offer twice. */
    final java.util.Set<String> triedDrivers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** The driver currently holding this trip's offer. Used by the tests as an
     *  independent check: two trips must never name the same driver at once. */
    volatile String pendingOfferDriver;
    volatile double fareRupees;
    volatile double surgeMultiplier = 1.0;

    Trip(String id, String riderId, Loc pickup, Loc dropoff) {
        this.id = id; this.riderId = riderId; this.pickup = pickup; this.dropoff = dropoff;
    }

    @Override public String toString() {
        return id + "[" + state + (driverId == null ? "" : " driver=" + driverId) + "]";
    }
}
