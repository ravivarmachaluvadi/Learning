enum DriverState {
    /** App closed. Not in the index at all. */
    OFFLINE,
    /** Online, no passenger, claimable. The only state a match may start from. */
    AVAILABLE,
    /** Reserved for ONE rider, waiting for the driver to tap accept. */
    OFFERED,
    /** Carrying a passenger, or driving to one. Not claimable. */
    ON_TRIP
}
