package io.castellan.rules;

/** The cross-fact join test for a rule's third pattern, given the already-matched pair
 * {@code (a, b)} and the candidate fact {@code c}. Compiled into the
 * {@link io.castellan.rules.network.JoinNode} that joins the (pattern0, pattern1) tuple with
 * pattern 2. */
@FunctionalInterface
public interface Join3<A, B, C> {
    boolean test(A a, B b, C c);
}
