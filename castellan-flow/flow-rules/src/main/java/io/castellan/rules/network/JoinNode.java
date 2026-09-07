package io.castellan.rules.network;

import io.castellan.rules.FactHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * Beta network join node: combines a LEFT input (either the raw facts of a rule's first pattern,
 * for the first join in a chain, or the tuples produced by the previous {@code JoinNode}, for
 * every join after that) with a RIGHT input (the next pattern's alpha memory), testing a
 * cross-fact predicate — this is the genuine multi-fact join Rete exists for, e.g. "this
 * {@code Account}'s id equals this {@code TransactionRequest}'s accountId".
 *
 * <h2>Incremental maintenance — the actual point of Rete</h2>
 *
 * A join node never recomputes its memory from scratch. There are exactly two events it reacts
 * to, and each only combines the ONE new item against the OTHER side's already-known memory:
 * <ul>
 *   <li>{@link #leftActivateFromRoot}/a propagated tuple from the previous join — a new LEFT
 *       partial match arrives; it is tested against every fact already sitting in the RIGHT
 *       alpha memory.</li>
 *   <li>{@link #rightActivate} — a new fact arrives at the RIGHT alpha node; it is tested against
 *       every partial match already sitting in the LEFT memory.</li>
 * </ul>
 * Whichever side a fact/tuple arrives on, only the other side's existing memory is scanned — the
 * join is never re-derived from the full cross product of both memories. This is what makes
 * insertion order irrelevant to the final set of matches (see {@code ReteIncrementalMaintenanceTest}):
 * inserting the Account first then the TransactionRequest walks the {@link #rightActivate} path;
 * the reverse order walks {@link #leftActivateFromRoot}; both produce the identical resulting
 * tuple, because both are testing the same join predicate against the same eventual pair of
 * facts — they just discover the pair via different trigger events.
 *
 * <h2>Retraction</h2>
 *
 * {@link #retract} removes every tuple in this node's memory that depends on the retracted
 * handle. Because {@link Tuple#handles()} carries the cumulative handle set (not just the most
 * recently joined fact), this is a direct filter — no walking back up to find which upstream
 * partial matches to invalidate first. {@link io.castellan.rules.RuleEngine#retract} calls this
 * on every join node in the network (in any order — the filter is self-contained per node), so a
 * fact's removal correctly evaporates every downstream partial match and activation that used it,
 * without recomputing any surviving join from scratch.
 */
public final class JoinNode {

    private final AlphaNode leftAlpha;
    private final JoinNode leftJoin;
    private final AlphaNode rightAlpha;
    private final BiPredicate<Tuple, Object> test;
    private final String description;

    private final List<Tuple> memory = new ArrayList<>();
    private final List<JoinNode> childJoins = new ArrayList<>();
    private final List<TerminalNode> childTerminals = new ArrayList<>();

    public JoinNode(AlphaNode leftAlpha, JoinNode leftJoin, AlphaNode rightAlpha,
                     BiPredicate<Tuple, Object> test, String description) {
        if ((leftAlpha == null) == (leftJoin == null)) {
            throw new IllegalArgumentException("exactly one of leftAlpha/leftJoin must be set");
        }
        this.leftAlpha = leftAlpha;
        this.leftJoin = leftJoin;
        this.rightAlpha = rightAlpha;
        this.test = test;
        this.description = description;
    }

    public void addChildJoin(JoinNode child) {
        childJoins.add(child);
    }

    public void addChildTerminal(TerminalNode terminal) {
        childTerminals.add(terminal);
    }

    AlphaNode rightAlpha() {
        return rightAlpha;
    }

    BiPredicate<Tuple, Object> test() {
        return test;
    }

    /** Pattern-0 alpha node reports a new fact directly (no tuple exists yet — this join IS the
     * first join in the chain). */
    void leftActivateFromRoot(Object fact, FactHandle handle) {
        Tuple seed = Tuple.of(fact, handle);
        for (AlphaEntry right : rightAlpha.memory()) {
            if (test.test(seed, right.fact())) {
                addAndPropagate(seed.extend(right.fact(), right.handle()));
            }
        }
    }

    /** The right-hand alpha node reports a newly matched fact: join it against every tuple
     * already known on the left (works uniformly whether "the left" is this node's own upstream
     * join's memory, or — for the very first join — the raw facts of pattern 0, wrapped as
     * singleton tuples on the fly). */
    void rightActivate(Object fact, FactHandle handle) {
        for (Tuple left : currentLeftEntries()) {
            if (test.test(left, fact)) {
                addAndPropagate(left.extend(fact, handle));
            }
        }
    }

    private List<Tuple> currentLeftEntries() {
        if (leftJoin != null) {
            return leftJoin.memory;
        }
        List<Tuple> wrapped = new ArrayList<>(leftAlpha.memory().size());
        for (AlphaEntry entry : leftAlpha.memory()) {
            wrapped.add(Tuple.of(entry.fact(), entry.handle()));
        }
        return wrapped;
    }

    /** Records a newly produced tuple in this node's own beta memory, then propagates it one
     * level further down the join chain (against the child join's right alpha memory) and/or into
     * any terminal nodes fed directly by this join. */
    private void addAndPropagate(Tuple tuple) {
        memory.add(tuple);
        for (JoinNode child : childJoins) {
            for (AlphaEntry right : child.rightAlpha().memory()) {
                if (child.test().test(tuple, right.fact())) {
                    child.addAndPropagate(tuple.extend(right.fact(), right.handle()));
                }
            }
        }
        for (TerminalNode terminal : childTerminals) {
            terminal.activate(tuple);
        }
    }

    public void retract(FactHandle handle) {
        memory.removeIf(tuple -> tuple.dependsOn(handle));
    }

    public List<Tuple> memory() {
        return memory;
    }

    @Override
    public String toString() {
        return "JoinNode[" + description + "]";
    }
}
