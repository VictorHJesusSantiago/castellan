package io.castellan.rules;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A single-pattern rule: the simplest possible network (one {@code AlphaNode} feeding a
 * {@code TerminalNode} directly, no beta network at all — see {@code RuleEngine#compile}). */
class SingleConditionRuleTest {

    private record Transaction(String id, int amountCents) {
    }

    private Rule highValueRule(List<String> fired) {
        return Rule.name("high-value-transaction")
                .pattern(Transaction.class, t -> t.amountCents() > 100_000, "amountCents > 100000")
                .then((Transaction t, RuleContext ctx) -> fired.add(t.id()));
    }

    @Test
    void firesWhenAMatchingFactIsInserted() {
        List<String> fired = new ArrayList<>();
        RuleEngine engine = new RuleEngine(new RuleSetRegistry().deploy("txn-rules", List.of(highValueRule(fired))));

        engine.insert(new Transaction("t1", 150_000));

        assertThat(engine.pendingActivationCount()).isEqualTo(1);
        assertThat(engine.fireAllRules()).isEqualTo(1);
        assertThat(fired).containsExactly("t1");
        assertThat(engine.firedRuleNames()).containsExactly("high-value-transaction");
    }

    @Test
    void doesNotFireWhenFactFailsThePredicate() {
        List<String> fired = new ArrayList<>();
        RuleEngine engine = new RuleEngine(new RuleSetRegistry().deploy("txn-rules", List.of(highValueRule(fired))));

        engine.insert(new Transaction("t2", 500));

        assertThat(engine.pendingActivationCount()).isZero();
        assertThat(engine.fireAllRules()).isZero();
        assertThat(fired).isEmpty();
    }

    @Test
    void doesNotFireForAFactOfADifferentType() {
        List<String> fired = new ArrayList<>();
        RuleEngine engine = new RuleEngine(new RuleSetRegistry().deploy("txn-rules", List.of(highValueRule(fired))));

        engine.insert("not a transaction at all");

        assertThat(engine.pendingActivationCount()).isZero();
        assertThat(engine.fireAllRules()).isZero();
        assertThat(fired).isEmpty();
    }
}
