package io.castellan.rules;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Retraction must correctly tear down downstream beta-network state: a join's memory (and any
 * not-yet-fired activation built on top of it) must not survive the removal of a fact it depends
 * on — see {@code JoinNode#retract} / {@code TerminalNode#retract} javadoc on why this is a flat
 * filter rather than a cascade, and {@code RuleEngine#retract}'s ordering note. */
class RetractionTest {

    private record Account(String accountId) {
    }

    private record TransactionRequest(String accountId, int amount) {
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
    void retractingAFactAfterFiringRemovesItFromAlphaMemorySoFutureJoinsCannotUseIt() {
        List<String> matched = new ArrayList<>();
        RuleEngine engine = new RuleEngine(new RuleSetRegistry().deploy("join-rules", List.of(sameAccountRule(matched))));

        FactHandle accountHandle = engine.insert(new Account("acc-1"));
        engine.insert(new TransactionRequest("acc-1", 250));
        assertThat(engine.fireAllRules()).isEqualTo(1);
        assertThat(matched).containsExactly("acc-1/250");

        engine.retract(accountHandle);

        engine.insert(new TransactionRequest("acc-1", 999));

        assertThat(engine.pendingActivationCount()).isZero();
        assertThat(engine.fireAllRules()).isZero();
        assertThat(matched).containsExactly("acc-1/250");
    }

    @Test
    void retractingAFactBeforeItFiresRemovesTheNotYetFiredActivationFromTheAgenda() {
        List<String> matched = new ArrayList<>();
        RuleEngine engine = new RuleEngine(new RuleSetRegistry().deploy("join-rules", List.of(sameAccountRule(matched))));

        FactHandle accountHandle = engine.insert(new Account("acc-1"));
        engine.insert(new TransactionRequest("acc-1", 250));
        assertThat(engine.pendingActivationCount())
                .as("the join completed a match; an activation is pending but not yet fired")
                .isEqualTo(1);

        engine.retract(accountHandle);

        assertThat(engine.pendingActivationCount())
                .as("the downstream partial match depended on the retracted fact and must be gone")
                .isZero();
        assertThat(engine.fireAllRules()).isZero();
        assertThat(matched).isEmpty();
    }

    @Test
    void retractingAnUnknownHandleThrows() {
        RuleEngine engine = new RuleEngine(new RuleSetRegistry().deploy("join-rules", List.of(sameAccountRule(new ArrayList<>()))));
        FactHandle handle = engine.insert(new Account("acc-1"));
        engine.retract(handle);

        assertThatThrownBy(() -> engine.retract(handle))
                .isInstanceOf(NoSuchFactException.class);
    }
}
