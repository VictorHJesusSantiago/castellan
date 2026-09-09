package io.castellan.ledger.application.fraud.rules;

import io.castellan.ledger.application.commands.PostTransactionCommand;
import io.castellan.ledger.application.commands.PostingRequest;
import io.castellan.ledger.application.fraud.FraudCheckContext;
import io.castellan.ledger.application.fraud.FraudSignal;
import io.castellan.ledger.domain.Account;
import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.EntryType;
import io.castellan.ledger.domain.IdempotencyKey;
import io.castellan.ledger.domain.Money;
import io.castellan.ledger.domain.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FraudRulesTest {

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final Currency usd = Currency.getInstance("USD");
    private final AccountId account = AccountId.newId();

    private FraudCheckContext contextWithPosting(long minorUnits, Map<AccountId, Account> accounts, Map<AccountId, Integer> recentCounts) {
        PostTransactionCommand command = new PostTransactionCommand(
                tenant,
                List.of(new PostingRequest(account, EntryType.DEBIT, new Money(minorUnits, usd))),
                "x", null, new IdempotencyKey("k"));
        return new FraudCheckContext(command, accounts, recentCounts);
    }

    @Test
    void largeAmountRuleFlagsAtOrAboveThreshold() {
        LargeAmountRule rule = new LargeAmountRule(new Money(1_000, usd));

        assertThat(rule.evaluate(contextWithPosting(999, Map.of(), Map.of()))).isInstanceOf(FraudSignal.Allow.class);
        assertThat(rule.evaluate(contextWithPosting(1_000, Map.of(), Map.of()))).isInstanceOf(FraudSignal.Flag.class);
        assertThat(rule.evaluate(contextWithPosting(5_000, Map.of(), Map.of()))).isInstanceOf(FraudSignal.Flag.class);
    }

    @Test
    void largeAmountRuleIgnoresDifferentCurrencies() {
        LargeAmountRule rule = new LargeAmountRule(new Money(100, Currency.getInstance("EUR")));

        assertThat(rule.evaluate(contextWithPosting(1_000_000, Map.of(), Map.of()))).isInstanceOf(FraudSignal.Allow.class);
    }

    @Test
    void velocityRuleBlocksAtOrAboveTheLimit() {
        VelocityRule rule = new VelocityRule(5);

        assertThat(rule.evaluate(contextWithPosting(100, Map.of(), Map.of(account, 4)))).isInstanceOf(FraudSignal.Allow.class);
        assertThat(rule.evaluate(contextWithPosting(100, Map.of(), Map.of(account, 5)))).isInstanceOf(FraudSignal.Block.class);
    }

    @Test
    void newAccountLargeTransferRuleFlagsOnlyRecentlyOpenedAccountsAboveThreshold() {
        Instant now = Instant.parse("2026-01-10T00:00:00Z");
        Instant openedRecently = now.minus(Duration.ofHours(1));
        Instant openedLongAgo = now.minus(Duration.ofDays(400));

        Account newAccount = Account.replay(List.of(Account.open(account, tenant, usd, "New Guy", openedRecently)));
        NewAccountLargeTransferRule rule = new NewAccountLargeTransferRule(Duration.ofDays(30), new Money(500, usd), now);

        assertThat(rule.evaluate(contextWithPosting(1_000, Map.of(account, newAccount), Map.of())))
                .isInstanceOf(FraudSignal.Flag.class);

        Account oldAccount = Account.replay(List.of(Account.open(account, tenant, usd, "Old Timer", openedLongAgo)));
        assertThat(rule.evaluate(contextWithPosting(1_000, Map.of(account, oldAccount), Map.of())))
                .isInstanceOf(FraudSignal.Allow.class);
    }

    @Test
    void newAccountLargeTransferRuleAllowsSmallAmountsRegardlessOfAge() {
        Instant now = Instant.parse("2026-01-10T00:00:00Z");
        Account newAccount = Account.replay(List.of(Account.open(account, tenant, usd, "New Guy", now.minus(Duration.ofMinutes(5)))));
        NewAccountLargeTransferRule rule = new NewAccountLargeTransferRule(Duration.ofDays(30), new Money(500, usd), now);

        assertThat(rule.evaluate(contextWithPosting(10, Map.of(account, newAccount), Map.of())))
                .isInstanceOf(FraudSignal.Allow.class);
    }
}
