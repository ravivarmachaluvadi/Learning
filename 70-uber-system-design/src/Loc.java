/**
 * A point on the earth, in degrees, plus the two bits of maths every other file
 * needs: how far apart two points are, and how wide a metre is in longitude.
 */
/** A point on the earth, in degrees. */
record Loc(double lat, double lon) {

    /** Metres per degree of latitude. Constant everywhere; longitude is not. */
    static final double M_PER_DEG_LAT = 111_320.0;

    /**
     * Straight-line ("as the crow flies") distance in metres, using the
     * equirectangular approximation.
     *
     * SIMPLIFICATION, stated up front: this is not road distance and it is not
     * an ETA. Over a few kilometres inside one city the error against the exact
     * haversine formula is centimetres, so it is fine for choosing candidates --
     * but a driver 300 m away across a river is not 300 m away by road. The doc
     * (stage 10, mechanism v2) is where that bites and gets fixed.
     */
    double distanceTo(Loc o) {
        double meanLatRad = Math.toRadians((lat + o.lat) / 2);
        double dy = (lat - o.lat) * M_PER_DEG_LAT;
        double dx = (lon - o.lon) * M_PER_DEG_LAT * Math.cos(meanLatRad);
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Degrees of longitude that span `metres` at this latitude. */
    double lonDegreesFor(double metres) {
        return metres / (M_PER_DEG_LAT * Math.cos(Math.toRadians(lat)));
    }

    Loc moved(double dLatM, double dLonM) {
        return new Loc(lat + dLatM / M_PER_DEG_LAT, lon + lonDegreesFor(dLonM));
    }

    @Override public String toString() { return String.format("(%.5f,%.5f)", lat, lon); }
}
