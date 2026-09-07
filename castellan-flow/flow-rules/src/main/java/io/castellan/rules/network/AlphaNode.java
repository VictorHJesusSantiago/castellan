package io.castellan.rules.network;

import io.castellan.rules.FactHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Alpha network node: the single-fact discrimination test for one rule pattern ("fact is a
 * {@code TransactionRequest} and {@code amount > 1000}"). A fact inserted into working memory is
 * tested against each compiled {@code AlphaNode} exactly once; it only ever reaches this node's
 * memory — and only ever propagates into the beta network below it — if it actually satisfies the
 * type check and predicate. That is the alpha half of Rete's efficiency claim: a fact that does
 * not match a pattern never causes any join work for that pattern, rather than every rule
 * re-testing every fact from scratch.
 *
 * <p>Scope note: this engine compiles one {@code AlphaNode} chain per rule pattern (no cross-rule
 * alpha node sharing keyed by structural predicate equality). Two rules that happen to share an
 * identical first condition still get two independent nodes and two independent memories. Real
 * Drools-style engines share alpha nodes across rules to avoid duplicate memory and duplicate
 * predicate evaluation when many rules test the same condition; that sharing is a valid
 * optimization we did not implement. What is fully real here is the per-rule discrimination
 * (facts not matching a pattern never reach that pattern's join) and — the actually
 * load-bearing property under test — incremental beta memory maintenance (see {@link JoinNode}).
 */
public final class AlphaNode {

    private final Class<?> factType;
    private final Predicate<Object> test;
    private final String description;

    private final List<AlphaEntry> memory = new ArrayList<>();
    private final List<JoinNode> rightConsumers = new ArrayList<>();
    private final List<JoinNode> leftRootConsumers = new ArrayList<>();
    private final List<TerminalNode> soleConsumers = new ArrayList<>();

    public AlphaNode(Class<?> factType, Predicate<Object> test, String description) {
        this.factType = factType;
        this.test = test;
        this.description = description;
    }

    public boolean accepts(Object fact) {
        return factType.isInstance(fact) && test.test(fact);
    }

    /** Registers a join node that uses this alpha node as its RIGHT input (i.e. this node's
     * pattern is being joined against an already-accumulated tuple from the left). */
    public void addRightConsumer(JoinNode join) {
        rightConsumers.add(join);
    }

    /** Registers a join node that uses this alpha node as the very first (pattern 0) LEFT input —
     * only applicable to the alpha node for a rule's first pattern. */
    public void addLeftRootConsumer(JoinNode join) {
        leftRootConsumers.add(join);
    }

    /** Registers a terminal node fed directly by this alpha node — only applicable to
     * single-pattern rules, which need no beta network at all. */
    public void addSoleConsumer(TerminalNode terminal) {
        soleConsumers.add(terminal);
    }

    /** Propagates a newly matched fact into this node's memory and onward into the beta network.
     * Every downstream consumer only has to consider the ONE new fact against its OTHER side's
     * existing memory — never a full recomputation of the join. */
    public void insert(Object fact, FactHandle handle) {
        memory.add(new AlphaEntry(fact, handle));
        for (JoinNode join : leftRootConsumers) {
            join.leftActivateFromRoot(fact, handle);
        }
        for (JoinNode join : rightConsumers) {
            join.rightActivate(fact, handle);
        }
        for (TerminalNode terminal : soleConsumers) {
            terminal.activate(Tuple.of(fact, handle));
        }
    }

    public void retract(FactHandle handle) {
        memory.removeIf(entry -> entry.handle().equals(handle));
    }

    public List<AlphaEntry> memory() {
        return memory;
    }

    public String description() {
        return description;
    }

    @Override
    public String toString() {
        return "AlphaNode[" + factType.getSimpleName() + ": " + description + "]";
    }
}
