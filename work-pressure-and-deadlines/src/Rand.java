/**
 * A tiny deterministic random-number generator (mulberry32).
 *
 * Why not java.util.Random? Because visualization.html runs the SAME model in
 * JavaScript, and mulberry32 is 8 lines of 32-bit integer arithmetic that
 * produces bit-identical results in both languages. A number you read in the
 * browser is the number this code prints.
 */
final class Rand {
    private int s;

    Rand(int seed) { this.s = seed; }

    /** Uniform in [0,1). Identical to the mulberry32 in visualization.html. */
    double next() {
        s = s + 0x6D2B79F5;
        int t = (s ^ (s >>> 15)) * (1 | s);
        t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
        return Integer.toUnsignedLong(t ^ (t >>> 14)) / 4294967296.0;
    }

    /** Standard normal, Box-Muller. */
    double gauss() {
        double u1 = Math.max(next(), 1e-12), u2 = next();
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }

    /**
     * Lognormal with the given MEDIAN. This is the whole estimation lesson in
     * one line: task durations are right-skewed, so the median (what people
     * picture when they estimate) is BELOW the mean (what the schedule pays).
     * mean = median * exp(sigma^2 / 2).
     */
    double lognormal(double median, double sigma) {
        return median * Math.exp(sigma * gauss());
    }

    double exp(double mean) { return -mean * Math.log(Math.max(next(), 1e-12)); }

    /** A seed derived from a world seed and a stream id, so streams stay independent. */
    static Rand stream(int worldSeed, int streamId) {
        return new Rand(worldSeed * 0x9E3779B9 + streamId * 0x85EBCA6B);
    }
}
