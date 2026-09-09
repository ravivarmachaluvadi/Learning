/**
 * What one node currently believes about another.
 *
 * The order matters and is not alphabetical: within the same incarnation number,
 * a worse-sounding belief wins. If I think you are fine and somebody tells me you
 * are suspect, I take the suspicion -- because the only thing that may overturn
 * it is YOU, saying so with a fresher incarnation number. That asymmetry is what
 * makes bad news travel and stay travelling until it is refuted at the source.
 */
enum Status {
    ALIVE,      // heard from recently, directly or via somebody else
    SUSPECT,    // has not been heard from; not yet declared dead
    DEAD        // declared gone; will not be gossiped to any more
}
