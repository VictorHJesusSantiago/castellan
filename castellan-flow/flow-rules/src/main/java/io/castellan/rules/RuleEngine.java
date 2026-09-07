package io.castellan.rules;

import io.castellan.rules.network.AlphaNode;
import io.castellan.rules.network.JoinNode;
import io.castellan.rules.network.Tuple;
import io.castellan.rules.network.TerminalNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One working-memory session compiled from a single (immutable, versioned) {@link RuleSet}. A
 * session owns its own alpha/beta network instances (see the class-level scope note below), its
 * own fact table, and its own agenda — two sessions built from the same {@code RuleSet} never
 * share mutable state, so concurrent evaluations (e.g. two BPMN process instances both invoking
 * the same deployed rule set) cannot interfere with each other.
 *
 * <p><b>Scope note on network sharing:</b> a "real" production Rete implementation typically
 * compiles the alpha/beta topology once per rule set and shares that compiled topology (not its
 * per-session memories) across every session, because compilation itself is nontrivial work.
 * Here, each {@code RuleEngine} recompiles its own network from the {@code RuleSet}'s immutable
 * {@link Rule} list. For the workloads this module is built for — one session per rule-set
 * version per BPMN service-task evaluation, or one long-lived session per deployed version — that
 * recompilation is cheap (rules are plain Java objects, not parsed text) and it trades a small
 * amount of duplicate object allocation for the strong guarantee above (no accidental
 * cross-session memory sharing) with much simpler code. Sharing compiled topology across sessions
 * is a valid future optimization, not attempted.
 */
public final class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final RuleSet ruleSet;
    private final List<AlphaNode> allAlphaNodes = new ArrayList<>();
    private final List<JoinNode> allJoinNodes = new ArrayList<>();
    private final List<TerminalNode> allTerminals = new ArrayList<>();

    private final Map<FactHandle, Object> factsByHandle = new LinkedHashMap<>();
    private final Map<Tuple, Activation> pendingActivations = new IdentityHashMap<>();
    private final Agenda agenda = new Agenda();
    private final List<Activation> firedHistory = new ArrayList<>();

    private long nextHandleId = 0;
    private long nextActivationSequence = 0;

    public RuleEngine(RuleSet ruleSet) {
        this.ruleSet = ruleSet;
        for (Rule rule : ruleSet.rules()) {
            compile(rule);
        }
    }

    public String ruleSetName() {
        return ruleSet.name();
    }

    public int ruleSetVersion() {
        return ruleSet.version();
    }


    private void compile(Rule rule) {
        List<PatternSpec> patterns = rule.patterns();
        AlphaNode[] alphaNodes = new AlphaNode[patterns.size()];
        for (int i = 0; i < patterns.size(); i++) {
            PatternSpec spec = patterns.get(i);
            AlphaNode node = new AlphaNode(spec.type(), spec.test(), spec.description());
            alphaNodes[i] = node;
            allAlphaNodes.add(node);
        }

        TerminalNode terminal = new TerminalNode(rule.name(),
                tuple -> onActivate(rule, tuple),
                tuple -> onDeactivate(tuple));
        allTerminals.add(terminal);

        if (alphaNodes.length == 1) {
            alphaNodes[0].addSoleConsumer(terminal);
            return;
        }

        JoinNode firstJoin = new JoinNode(alphaNodes[0], null, alphaNodes[1],
                rule.joinTests().get(0), rule.name() + "#join0");
        alphaNodes[0].addLeftRootConsumer(firstJoin);
        alphaNodes[1].addRightConsumer(firstJoin);
        allJoinNodes.add(firstJoin);

        JoinNode previous = firstJoin;
        for (int i = 1; i < rule.joinTests().size(); i++) {
            JoinNode join = new JoinNode(null, previous, alphaNodes[i + 1],
                    rule.joinTests().get(i), rule.name() + "#join" + i);
            previous.addChildJoin(join);
            alphaNodes[i + 1].addRightConsumer(join);
            allJoinNodes.add(join);
            previous = join;
        }
        previous.addChildTerminal(terminal);
    }

    private void onActivate(Rule rule, Tuple tuple) {
        Activation activation = new Activation(rule, tuple, nextActivationSequence++);
        pendingActivations.put(tuple, activation);
        agenda.add(activation);
    }

    private void onDeactivate(Tuple tuple) {
        Activation activation = pendingActivations.remove(tuple);
        if (activation != null) {
            agenda.remove(activation);
        }
    }


    public FactHandle insert(Object fact) {
        FactHandle handle = new FactHandle(++nextHandleId, fact.getClass());
        factsByHandle.put(handle, fact);
        for (AlphaNode alpha : allAlphaNodes) {
            if (alpha.accepts(fact)) {
                alpha.insert(fact, handle);
            }
        }
        return handle;
    }

    public void retract(FactHandle handle) {
        Object removed = factsByHandle.remove(handle);
        if (removed == null) {
            throw new NoSuchFactException(handle);
        }
        for (AlphaNode alpha : allAlphaNodes) {
            alpha.retract(handle);
        }
        for (JoinNode join : allJoinNodes) {
            join.retract(handle);
        }
        for (TerminalNode terminal : allTerminals) {
            terminal.retract(handle);
        }
    }

    /** Retract-then-reinsert. The returned handle differs from the argument — updating does not
     * preserve fact identity, since a genuinely changed fact must be treated as a new candidate
     * for every join it participates in, not patched in place. */
    public FactHandle update(FactHandle handle, Object newFact) {
        retract(handle);
        return insert(newFact);
    }

    public Object factFor(FactHandle handle) {
        Object fact = factsByHandle.get(handle);
        if (fact == null) {
            throw new NoSuchFactException(handle);
        }
        return fact;
    }


    /** Fires the single highest-priority pending activation, if any. Returns {@code false} if the
     * agenda is empty. Exists mainly for tests/inspection that want to step through firing order
     * one rule at a time. */
    public boolean fireOneRule() {
        Activation next = agenda.poll();
        if (next == null) {
            return false;
        }
        pendingActivations.remove(next.tuple());
        RuleContext ctx = new RuleContext(this, next.rule().name());
        log.debug("firing rule {} on {}", next.rule().name(), next.tuple());
        next.rule().action().fire(next.tuple().facts(), ctx);
        firedHistory.add(next);
        return true;
    }

    /** Fires every currently pending activation, respecting salience/insertion-order conflict
     * resolution, including activations produced as a side effect of earlier firings in the same
     * call (an action inserting a fact can make new rules match before this call returns).
     * Returns the number of rules fired. */
    public int fireAllRules() {
        int count = 0;
        while (fireOneRule()) {
            count++;
        }
        return count;
    }

    public int pendingActivationCount() {
        return agenda.size();
    }

    public List<String> firedRuleNames() {
        List<String> names = new ArrayList<>(firedHistory.size());
        for (Activation activation : firedHistory) {
            names.add(activation.rule().name());
        }
        return names;
    }
}
