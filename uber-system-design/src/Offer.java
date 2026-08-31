/** A reservation with a deadline. If the driver does not accept in time, it dies. */
record Offer(String offerId, String tripId, String driverId, long expiresAtMs) {}
