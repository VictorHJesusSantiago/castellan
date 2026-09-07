package io.castellan.rules;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Conflict resolution as actually implemented by {@link Agenda}: a {@link java.util.PriorityQueue}
 * ordered by salience descending, then by the activation's monotonic {@code sequence} (the order in
 * which each tuple completed its match) ascending as a FIFO tiebreak — see {@code Agenda}'s javadoc.
 * Notably NOT rule declaration order: the rules below are deployed high/low/mid on purpose to prove
 * declaration order is irrelevant. */
class AgendaConflictResolutionTest {

    private record Event(String kind) {
    }

    private Rule rule(String name, int salience, String kind, List<String> fired) {
        return Rule.name(name).salience(salience)
                .pattern(Event.class, e -> e.kind().equals(kind), "kind=" + kind)
                .then((Event e, RuleContext ctx) -> fired.add(name));
    }

    @Test
    void higherSalienceFiresFirstRegardlessOfInsertionOrder() {
        List<String> fired = new ArrayList<>();
        Rule highPriority = rule("high-priority", 10, "high", fired);
        Rule medium1 = rule("med-1", 0, "med1", fired);
        Rule medium2 = rule("med-2", 0, "med2", fired);

        RuleEngine engine = new RuleEngine(
                new RuleSetRegistry().deploy("agenda-rules", List.of(medium1, highPriority, medium2)));

        engine.insert(new Event("med2"));
        engine.insert(new Event("med1"));
        engine.insert(new Event("high"));

        assertThat(engine.fireAllRules()).isEqualTo(3);
        assertThat(fired)
                .as("salience wins outright; among salience ties, FIFO by activation sequence (med2 matched before med1)")
                .containsExactly("high-priority", "med-2", "med-1");
    }

    @Test
    void equalSalienceActivationsFireInTheOrderTheyCompletedTheirMatch() {
        List<String> fired = new ArrayList<>();
        Rule a = rule("rule-a", 5, "a", fired);
        Rule b = rule("rule-b", 5, "b", fired);
        Rule c = rule("rule-c", 5, "c", fired);

        RuleEngine engine = new RuleEngine(new RuleSetRegistry().deploy("agenda-rules", List.of(a, b, c)));

        engine.insert(new Event("c"));
        engine.insert(new Event("a"));
        engine.insert(new Event("b"));

        assertThat(engine.fireAllRules()).isEqualTo(3);
        assertThat(fired).containsExactly("rule-c", "rule-a", "rule-b");
    }

    @Test
    void fireOneRuleStepsThroughTheSameDocumentedOrder() {
        List<String> fired = new ArrayList<>();
        Rule highPriority = rule("high-priority", 10, "high", fired);
        Rule medium = rule("med", 0, "med", fired);

        RuleEngine engine = new RuleEngine(new RuleSetRegistry().deploy("agenda-rules", List.of(medium, highPriority)));

        engine.insert(new Event("med"));
        engine.insert(new Event("high"));
        assertThat(engine.pendingActivationCount()).isEqualTo(2);

        assertThat(engine.fireOneRule()).isTrue();
        assertThat(fired).containsExactly("high-priority");
        assertThat(engine.pendingActivationCount()).isEqualTo(1);

        assertThat(engine.fireOneRule()).isTrue();
        assertThat(fired).containsExactly("high-priority", "med");
        assertThat(engine.pendingActivationCount()).isZero();

        assertThat(engine.fireOneRule()).isFalse();
        assertThat(fired).hasSize(2);
    }
}
