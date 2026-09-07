package io.castellan.rules.network;

import io.castellan.rules.FactHandle;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One rule's terminus in the network: every tuple that reaches here is a complete match for that
 * rule's full pattern list. {@link #activate} fires {@code onActivate} (which
 * {@link io.castellan.rules.RuleEngine} wires to push a new {@code Activation} onto the agenda);
 * {@link #retract} fires {@code onDeactivate} for any currently-active tuple that depended on the
 * retracted handle, which {@code RuleEngine} wires to pull the matching (not-yet-fired) activation
 * back off the agenda — a rule that no longer matches should not fire just because it was on the
 * agenda before the fact that made it match disappeared.
 *
 * <p>{@code activeTuples} is a {@link LinkedHashSet} keyed on {@link Tuple} identity (no
 * {@code equals}/{@code hashCode} override on {@code Tuple} is intentional — see its javadoc: each
 * logical combination of facts is produced by exactly one code path in {@link JoinNode}, so
 * reference identity is already a correct and cheaper uniqueness key).
 */
public final class TerminalNode {

    private final String ruleName;
    private final Set<Tuple> activeTuples = new LinkedHashSet<>();
    private final Consumer<Tuple> onActivate;
    private final Consumer<Tuple> onDeactivate;

    public TerminalNode(String ruleName, Consumer<Tuple> onActivate, Consumer<Tuple> onDeactivate) {
        this.ruleName = ruleName;
        this.onActivate = onActivate;
        this.onDeactivate = onDeactivate;
    }

    void activate(Tuple tuple) {
        activeTuples.add(tuple);
        onActivate.accept(tuple);
    }

    public void retract(FactHandle handle) {
        activeTuples.removeIf(tuple -> {
            boolean depends = tuple.dependsOn(handle);
            if (depends) {
                onDeactivate.accept(tuple);
            }
            return depends;
        });
    }

    public String ruleName() {
        return ruleName;
    }

    public Set<Tuple> activeTuples() {
        return activeTuples;
    }
}
