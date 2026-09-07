package io.castellan.rules;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link RuleSetRegistry}'s versioning contract, as actually implemented: deploying under an
 * existing name always appends a new, higher version (never overwrites), every earlier version
 * stays resolvable forever, and a session pinned to a specific version keeps evaluating against
 * exactly that {@link Rule} list even after newer versions are deployed under the same name. */
class RuleSetVersioningTest {

    private record Transaction(int amountCents) {
    }

    @Test
    void deployingUnderTheSameNameAlwaysMintsAHigherVersionAndNeverOverwrites() {
        RuleSetRegistry registry = new RuleSetRegistry();
        Rule ruleV1 = Rule.name("threshold-rule")
                .pattern(Transaction.class, t -> t.amountCents() > 100, "amountCents > 100")
                .then((t, ctx) -> { });
        Rule ruleV2 = Rule.name("threshold-rule")
                .pattern(Transaction.class, t -> t.amountCents() > 200, "amountCents > 200")
                .then((t, ctx) -> { });

        RuleSet v1 = registry.deploy("fraud-rules", List.of(ruleV1));
        RuleSet v2 = registry.deploy("fraud-rules", List.of(ruleV2));

        assertThat(v1.version()).isEqualTo(1);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(registry.version("fraud-rules", 1)).contains(v1);
        assertThat(registry.version("fraud-rules", 2)).contains(v2);
        assertThat(registry.version("fraud-rules", 3)).isEmpty();
        assertThat(registry.latest("fraud-rules")).contains(v2);
    }

    @Test
    void latestAndVersionAreEmptyForAnUndeployedName() {
        RuleSetRegistry registry = new RuleSetRegistry();

        assertThat(registry.latest("never-deployed")).isEmpty();
        assertThat(registry.version("never-deployed", 1)).isEmpty();
    }

    @Test
    void newSessionWithoutAVersionUsesTheLatestDeployment() {
        RuleSetRegistry registry = new RuleSetRegistry();
        registry.deploy("fraud-rules", List.of(
                Rule.name("r").pattern(Transaction.class, t -> true, "any").then((t, ctx) -> { })));
        registry.deploy("fraud-rules", List.of(
                Rule.name("r").pattern(Transaction.class, t -> true, "any").then((t, ctx) -> { })));

        RuleEngine session = registry.newSession("fraud-rules");

        assertThat(session.ruleSetVersion()).isEqualTo(2);
    }

    @Test
    void aSessionPinnedToASpecificVersionKeepsEvaluatingAgainstThatVersionForever() {
        RuleSetRegistry registry = new RuleSetRegistry();
        List<String> firedV1 = new ArrayList<>();
        Rule ruleV1 = Rule.name("threshold-rule")
                .pattern(Transaction.class, t -> t.amountCents() > 100, "amountCents > 100")
                .then((t, ctx) -> firedV1.add("v1-fired"));
        registry.deploy("fraud-rules", List.of(ruleV1));

        RuleEngine pinnedToV1 = registry.newSession("fraud-rules", 1);

        List<String> firedV2 = new ArrayList<>();
        Rule ruleV2 = Rule.name("threshold-rule")
                .pattern(Transaction.class, t -> t.amountCents() > 200, "amountCents > 200")
                .then((t, ctx) -> firedV2.add("v2-fired"));
        registry.deploy("fraud-rules", List.of(ruleV2));

        pinnedToV1.insert(new Transaction(150));
        assertThat(pinnedToV1.fireAllRules())
                .as("the pinned session must still be evaluating v1's rule list, unaffected by the v2 deployment")
                .isEqualTo(1);
        assertThat(firedV1).containsExactly("v1-fired");
        assertThat(firedV2).isEmpty();
        assertThat(pinnedToV1.ruleSetVersion()).isEqualTo(1);

        RuleEngine latestSession = registry.newSession("fraud-rules");
        latestSession.insert(new Transaction(150));
        assertThat(latestSession.fireAllRules())
                .as("v2's threshold (>200) rejects the same fact that satisfied v1's")
                .isZero();
    }

    @Test
    void newSessionThrowsForAnUndeployedName() {
        RuleSetRegistry registry = new RuleSetRegistry();

        assertThatThrownBy(() -> registry.newSession("missing"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.newSession("missing", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
