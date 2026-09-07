package io.castellan.rules.network;

import io.castellan.rules.FactHandle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An ordered, immutable partial match: one fact per rule pattern position, built up one join at a
 * time as facts flow through the beta network (pattern 0's fact, then pattern 0+1's pair, then
 * pattern 0+1+2's triple, ...).
 *
 * <p>{@link #handles()} is the <em>cumulative</em> set of every fact handle that contributed to
 * this tuple, not just the one most recently joined in. That single design choice is what lets
 * retraction be a direct membership check ("does this tuple depend on the retracted handle?") at
 * every node in the network, rather than a parent-pointer cascade that has to walk back up from
 * the node the fact directly entered at. It costs one extra reference per tuple; it buys a
 * retraction algorithm that is a flat filter instead of a recursive tree walk — see
 * {@link JoinNode#retract} and {@link TerminalNode#retract} for where that pays off.
 */
public final class Tuple {

    private final List<Object> facts;
    private final List<FactHandle> handles;

    private Tuple(List<Object> facts, List<FactHandle> handles) {
        this.facts = facts;
        this.handles = handles;
    }

    public static Tuple of(Object fact, FactHandle handle) {
        return new Tuple(List.of(fact), List.of(handle));
    }

    public Tuple extend(Object fact, FactHandle handle) {
        List<Object> newFacts = new ArrayList<>(facts);
        newFacts.add(fact);
        List<FactHandle> newHandles = new ArrayList<>(handles);
        newHandles.add(handle);
        return new Tuple(Collections.unmodifiableList(newFacts), Collections.unmodifiableList(newHandles));
    }

    public List<Object> facts() {
        return facts;
    }

    public List<FactHandle> handles() {
        return handles;
    }

    public boolean dependsOn(FactHandle handle) {
        return handles.contains(handle);
    }

    public int size() {
        return facts.size();
    }

    @Override
    public String toString() {
        return "Tuple" + facts;
    }
}
