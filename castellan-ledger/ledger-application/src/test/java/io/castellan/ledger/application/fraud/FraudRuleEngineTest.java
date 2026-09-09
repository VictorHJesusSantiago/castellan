package io.castellan.ledger.application.fraud;

import io.castellan.ledger.application.commands.PostTransactionCommand;
import io.castellan.ledger.application.commands.PostingRequest;
import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.EntryType;
import io.castellan.ledger.domain.IdempotencyKey;
import io.castellan.ledger.domain.Money;
import io.castellan.ledger.domain.TenantId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FraudRuleEngineTest {

    private final TenantId tenant = new TenantId(UUID.randomUUID());

    private FraudCheckContext context() {
        PostTransactionCommand command = new PostTransactionCommand(
                tenant,
                List.of(new PostingRequest(AccountId.newId(), EntryType.DEBIT, Money.of(100, "USD"))),
                "x", null, new IdempotencyKey("k"));
        return new FraudCheckContext(command, Map.of(), Map.of());
    }

    @Test
    void allowsWhenNoRuleObjects() {
        FraudRuleEngine engine = new FraudRuleEngine(List.of());

        FraudVerdict verdict = engine.evaluate(context());

        assertThat(verdict.blocked()).isFalse();
        assertThat(verdict.flags()).isEmpty();
    }

    @Test
    void collectsFlagsFromMultipleRulesWithoutStopping() {
        FraudRuleEngine engine = new FraudRuleEngine(List.of(
                fixedRule("rule-a", new FraudSignal.Flag("a")),
                fixedRule("rule-b", new FraudSignal.Flag("b"))
        ));

        FraudVerdict verdict = engine.evaluate(context());

        assertThat(verdict.blocked()).isFalse();
        assertThat(verdict.flags()).hasSize(2);
        assertThat(verdict.flags()).extracting(FraudVerdict.RaisedFlag::ruleName).containsExactly("rule-a", "rule-b");
    }

    @Test
    void blockShortCircuitsRemainingRules() {
        AtomicInteger thirdRuleCalls = new AtomicInteger();
        FraudRuleEngine engine = new FraudRuleEngine(List.of(
                fixedRule("rule-a", new FraudSignal.Flag("a")),
                fixedRule("rule-b", new FraudSignal.Block("stop here")),
                new FraudRule() {
                    public String name() { return "rule-c"; }
                    public FraudSignal evaluate(FraudCheckContext ctx) { thirdRuleCalls.incrementAndGet(); return FraudSignal.ALLOW; }
                }
        ));

        FraudVerdict verdict = engine.evaluate(context());

        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.flags()).hasSize(2);
        assertThat(thirdRuleCalls).hasValue(0);
    }

    private static FraudRule fixedRule(String name, FraudSignal signal) {
        return new FraudRule() {
            public String name() { return name; }
            public FraudSignal evaluate(FraudCheckContext context) { return signal; }
        };
    }
}
