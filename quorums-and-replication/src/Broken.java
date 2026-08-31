/**
 * EXERCISES rung 6. Each flag disables exactly one rule from the CORRECTNESS
 * stage of the README, so you can watch which checks are load-bearing.
 *
 *   java src/Tests.java bug=no-overlap-check
 *   java src/Tests.java bug=lww-always
 *   java src/Tests.java bug=count-any-ack
 */
class Broken {
    /** Answer a read from the FIRST replica that replies, ignoring R. */
    static boolean NO_OVERLAP_CHECK = false;
    /** Always resolve conflicts by timestamp, even when version vectors say "concurrent". */
    static boolean LWW_ALWAYS = false;
    /** Count an ack from any node at all, including ones that do not own the key. */
    static boolean COUNT_ANY_ACK = false;
}
