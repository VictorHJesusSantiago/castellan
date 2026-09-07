package io.castellan.rules;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code RuleEngine.fireOneRule()}: fires exactly one pending activation per call, for
 * stepping through firing order one rule at a time (its own javadoc: "Exists mainly for
 * tests/inspection that want to step through firing order one rule at a time"). */
class FireOneRuleTest {

    private record Event(String kind) {
    }

    @Test
    void firesExactlyOneMatchPerCall() {
        List<String> fired = new ArrayList<>();
        Rule ruleA = Rule.name("rule-a")
                .pattern(Event.class, e -> e.kind().equals("a"), "kind=a")
                .then((Event e, RuleContext ctx) -> fired.add("a"));
        Rule ruleB = Rule.name("rule-b")
                .pattern(Event.class, e -> e.kind().equals("b"), "kind=b")
                .then((Event e, RuleContext ctx) -> fired.add("b"));

        RuleEngine engine = new RuleEngine(new RuleSetRegistry().deploy("step-rules", List.of(ruleA, ruleB)));
        engine.insert(new Event("a"));
        engine.insert(new Event("b"));
        assertThat(engine.pendingActivationCount()).isEqualTo(2);

        boolean firstCall = engine.fireOneRule();
        assertThat(firstCall).isTrue();
        assertThat(fired).hasSize(1);
        assertThat(engine.pendingActivationCount()).isEqualTo(1);

        boolean secondCall = engine.fireOneRule();
        assertThat(secondCall).isTrue();
        assertThat(fired).hasSize(2);
        assertThat(engine.pendingActivationCount()).isZero();

        boolean thirdCall = engine.fireOneRule();
        assertThat(thirdCall)
                .as("agenda is empty, nothing left to fire")
                .isFalse();
        assertThat(fired).hasSize(2);
    }

    @Test
    void fireOneRuleReturnsFalseOnAnEmptyAgenda() {
        RuleEngine engine = new RuleEngine(new RuleSetRegistry().deploy("step-rules", List.of(
                Rule.name("rule-a").pattern(Event.class, e -> true, "any").then((e, ctx) -> { }))));

        assertThat(engine.fireOneRule()).isFalse();
        assertThat(engine.firedRuleNames()).isEmpty();
    }

    @Test
    void fireAllRulesFiresEveryPendingActivationIncludingThoseProducedDuringTheCall() {
        List<String> alerts = new ArrayList<>();
        Rule flagHighValue = Rule.name("flag-high-value")
                .pattern(Transaction.class, t -> t.amount() > 1000, "amount>1000")
                .then((Transaction t, RuleContext ctx) -> ctx.insert(new Flagged(t.id())));
        Rule alertOnFlagged = Rule.name("alert-on-flagged")
                .pattern(Flagged.class, f -> true, "any flagged")
                .then((Flagged f, RuleContext ctx) -> alerts.add(f.id()));

        RuleEngine engine = new RuleEngine(
                new RuleSetRegistry().deploy("cascade-rules", List.of(flagHighValue, alertOnFlagged)));
        engine.insert(new Transaction("t1", 5000));

        int fired = engine.fireAllRules();

        assertThat(fired)
                .as("flag-high-value fires, its action inserts a Flagged fact, which makes alert-on-flagged match within the same call")
                .isEqualTo(2);
        assertThat(alerts).containsExactly("t1");
        assertThat(engine.firedRuleNames()).containsExactly("flag-high-value", "alert-on-flagged");
    }

    private record Transaction(String id, int amount) {
    }

    private record Flagged(String id) {
    }
}
