/**
 * Everything in this lab that can be copied onto several machines implements
 * this one method.
 *
 * merge(other) must obey three laws, and every class here is tested against
 * them in Tests.java:
 *
 *   commutative   a.merge(b)          equals  b.merge(a)
 *   associative   a.merge(b).merge(c) equals  a.merge(b.merge(c))
 *   idempotent    a.merge(a)          equals  a
 *
 * Those three laws are the entire trick. Commutative kills message reordering,
 * associative kills batching/grouping differences, idempotent kills duplicate
 * delivery. A network that can reorder, regroup and duplicate is exactly the
 * network we have — so a merge with these three laws cannot be hurt by it.
 */
public interface Replicated<T extends Replicated<T>> {
    /** Pure: returns a NEW value, never mutates either side. */
    T merge(T other);
}
