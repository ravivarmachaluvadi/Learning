import java.nio.charset.StandardCharsets;

/**
 * The hash functions everything else in this lab depends on.
 *
 * WHY THIS FILE MATTERS MORE THAN IT LOOKS: the whole promise of consistent hashing
 * is that keys land EVENLY around a circle. That promise is not made by the ring --
 * it is made by the hash function. Give the ring a hash whose outputs clump, and the
 * ring faithfully preserves the clumping and the load is lopsided.
 *
 * So this file contains two hashes on purpose: one good, one bad. Experiment 6 runs
 * the identical ring on both.
 */
final class Hashing {

    /**
     * A good 64-bit hash: FNV-1a to mix the bytes in, then MurmurHash3's finalizer to
     * avalanche the result (one flipped input bit changes about half the output bits).
     *
     * Real systems pick differently and it matters for one reason beyond quality:
     * two client libraries must compute the SAME hash or they disagree about who owns
     * a key. memcached's ketama uses MD5; Cassandra uses Murmur3. See README F8.
     */
    static long hash64(String s) {
        long h = 0xcbf29ce484222325L;                 // FNV-1a 64-bit offset basis
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xffL);
            h *= 0x100000001b3L;                      // FNV prime
        }
        return fmix64(h);
    }

    /** MurmurHash3's 64-bit finalizer. This is the step that produces the avalanche. */
    static long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }

    /**
     * A DELIBERATELY BAD key hash: Java's String.hashCode, widened to 64 bits.
     *
     * The defect is not that it collides -- it is a RANGE MISMATCH, and that is the
     * version of this bug people actually ship. hashCode is 32-bit, so its outputs
     * live in roughly +/-2x10^9. The ring's coordinate space is 64-bit, about
     * 1.8x10^19 wide. So every key in the system lands inside about one hundred-
     * millionth of the circle, and whichever machine happens to own that sliver owns
     * essentially the entire dataset.
     *
     * The ring is not broken and the machine positions are perfectly spread. The keys
     * simply never reach most of the circle. Experiment 6 measures it.
     */
    static long badHash64(String s) {
        return s.hashCode();
    }

    /** Position on the ring, as a fraction of the way round. Used only for drawing. */
    static double angleOf(long h) {
        return (h >>> 11) / (double) (1L << 53);
    }

    private Hashing() {}
}
