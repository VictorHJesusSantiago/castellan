package io.castellan.rules;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Multi-condition, multi-fact-type rules: a genuine beta-network join. The point under test is
 * that {@link io.castellan.rules.network.JoinNode} does incremental maintenance correctly — the
 * final match is identical regardless of which side of the join a fact arrives on first (see
 * {@code JoinNode}'s javadoc on {@code leftActivateFromRoot} vs {@code rightActivate}). */
class JoinRuleTest {

    private record Account(String accountId) {
    }

    private record TransactionRequest(String accountId, int amount) {
    }

    private record RiskScore(String accountId, int score) {
    }

    private Rule sameAccountRule(List<String> matched) {
        return Rule.name("same-account")
                .pattern(Account.class, a -> true, "any account")
                .join(TransactionRequest.class,
                        (Account a, TransactionRequest t) -> a.accountId().equals(t.accountId()),
                        "same account id")
                .then((Account a, TransactionRequest t, RuleContext ctx) ->
                        matched.add(a.accountId() + "/" + t.amount()));
    }

    @Test
    void firesOnlyAfterBothFactTypesArePresent_accountFirstThenTransaction() {
        List<String> matched = new ArrayList<>();
        RuleEngine engine = new RuleEngine(new RuleSetRegistry().deploy("join-rules", List.of(sameAccountRule(matched))));

        engine.insert(new Account("acc-1"));
        assertThat(engine.pendingActivationCount())
                .as("only one side of the join is present, no tuple can form yet")
                .isZero();

        engine.insert(new TransactionRequest("acc-1", 250));
        assertThat(engine.pendingActivationCount()).isEqualTo(1);

        assertThat(engine.fireAllRules()).isEqualTo(1);
        assertThat(matched).containsExactly("acc-1/250");
    }

    @Test
    void firesOnlyAfterBothFactTypesArePresent_transactionFirstThenAccount() {
        List<String> matched = new ArrayList<>();
        RuleEngine engine = new RuleEngine(new RuleSetRegistry().deploy("join-rules", List.of(sameAccountRule(matched))));

        engine.insert(new TransactionRequest("acc-1", 250));
        assertThat(engine.pendingActivationCount())
                .as("only one side of the join is present, no tuple can form yet")
                .isZero();

        engine.insert(new Account("acc-1"));
        assertThat(engine.pendingActivationCount()).isEqualTo(1);

        assertThat(engine.fireAllRules()).isEqualTo(1);
        assertThat(matched)
                .as("same match regardless of which side of the join arrived first")
                .containsExactly("acc-1/250");
    }

    @Test
    void doesNotFireWhenTheJoinKeysDoNotMatch() {
        List<String> matched = new ArrayList<>();
        RuleEngine engine = new RuleEngine(new RuleSetRegistry().deploy("join-rules", List.of(sameAccountRule(matched))));

        engine.insert(new Account("acc-1"));
        engine.insert(new TransactionRequest("acc-2", 250));

        assertThat(engine.pendingActivationCount()).isZero();
        assertThat(engine.fireAllRules()).isZero();
        assertThat(matched).isEmpty();
    }

    @Test
    void threePatternRuleJoinsAcrossAllThreeFactTypes() {
        List<String> matched = new ArrayList<>();
        Rule rule = Rule.name("high-risk-same-account")
                .pattern(Account.class, a -> true, "any account")
                .join(TransactionRequest.class,
                        (Account a, TransactionRequest t) -> a.accountId().equals(t.accountId()),
                        "same account id")
                .join(RiskScore.class,
                        (Account a, TransactionRequest t, RiskScore r) ->
                                a.accountId().equals(r.accountId()) && r.score() > 80,
                        "same account, high risk score")
                .then((Account a, TransactionRequest t, RiskScore r, RuleContext ctx) ->
                        matched.add(a.accountId() + "/" + t.amount() + "/" + r.score()));

        RuleEngine engine = new RuleEngine(new RuleSetRegistry().deploy("triple-join", List.of(rule)));

        engine.insert(new RiskScore("acc-1", 95));
        engine.insert(new TransactionRequest("acc-1", 999));
        assertThat(engine.pendingActivationCount()).isZero();

        engine.insert(new Account("acc-1"));
        assertThat(engine.pendingActivationCount()).isEqualTo(1);
        assertThat(engine.fireAllRules()).isEqualTo(1);
        assertThat(matched).containsExactly("acc-1/999/95");
    }

    @Test
    void threePatternRuleDoesNotFireWhenTheThirdConditionFails() {
        List<String> matched = new ArrayList<>();
        Rule rule = Rule.name("high-risk-same-account")
                .pattern(Account.class, a -> true, "any account")
                .join(TransactionRequest.class,
                        (Account a, TransactionRequest t) -> a.accountId().equals(t.accountId()),
                        "same account id")
                .join(RiskScore.class,
                        (Account a, TransactionRequest t, RiskScore r) ->
                                a.accountId().equals(r.accountId()) && r.score() > 80,
                        "same account, high risk score")
                .then((Account a, TransactionRequest t, RiskScore r, RuleContext ctx) ->
                        matched.add(a.accountId()));

        RuleEngine engine = new RuleEngine(new RuleSetRegistry().deploy("triple-join", List.of(rule)));

        engine.insert(new Account("acc-1"));
        engine.insert(new TransactionRequest("acc-1", 999));
        engine.insert(new RiskScore("acc-1", 10));

        assertThat(engine.pendingActivationCount()).isZero();
        assertThat(engine.fireAllRules()).isZero();
        assertThat(matched).isEmpty();
    }
}
