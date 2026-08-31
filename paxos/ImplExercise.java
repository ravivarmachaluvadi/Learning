import java.util.List;

/**
 * Exercise rung 5 — IMPLEMENT the two decisions at the heart of Paxos.
 *
 * Fill in the two methods below, then run:
 *
 *     java ImplExerciseTest.java
 *
 * Both are pure static functions — no I/O, no mutable state. Between them they
 * contain the entire intellectual content of the protocol: what a proposer must
 * propose, and what "decided" actually means. Everything else in Paxos.java is
 * plumbing.
 *
 * (Reference answers in SOLUTIONS.md — attempt first; the tests tell you
 * exactly which rule you got wrong.)
 */
public class ImplExercise {

    /**
     * One phase-1 reply, as a proposer collected it.
     *
     * @param acceptorId        who replied
     * @param lastAcceptedN     that acceptor's highest accepted proposal number (0 if none)
     * @param lastAcceptedValue the value that went with it (null if it never accepted anything)
     */
    public record Promise(String acceptorId, int lastAcceptedN, Object lastAcceptedValue) {
    }

    /**
     * One acceptor's durable state, as a learner would read it.
     *
     * @param acceptedN     highest proposal number accepted (0 if none)
     * @param acceptedValue the value that went with it (null if nothing accepted)
     */
    public record AcceptorState(int acceptedN, Object acceptedValue) {
    }

    /**
     * The phase-2 value rule.
     *
     * `promises` is the list of phase-1 replies a proposer collected from a
     * majority. Each promise carries:
     *
     *     (acceptorId, lastAcceptedN, lastAcceptedValue)
     *
     * where lastAcceptedValue is null (and lastAcceptedN is 0) if that
     * acceptor has never accepted anything.
     *
     * Return the value this proposer MUST send in its Accept messages.
     */
    public static Object pickValueFromPromises(List<Promise> promises, Object myValue) {
        throw new UnsupportedOperationException("your code here");
    }

    /**
     * The decision rule.
     *
     * `acceptorStates` has one entry per acceptor:
     *
     *     (acceptedN, acceptedValue)        // (0, null) if nothing accepted
     *
     * Return the value that has been CHOSEN, or null if no value has been.
     * Be careful about exactly what has to match for a decision to exist.
     */
    public static Object chosenValue(List<AcceptorState> acceptorStates, int quorum) {
        throw new UnsupportedOperationException("your code here");
    }
}
