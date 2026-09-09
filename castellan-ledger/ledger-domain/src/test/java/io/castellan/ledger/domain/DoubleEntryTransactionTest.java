package io.castellan.ledger.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DoubleEntryTransactionTest {

    private final AccountId source = AccountId.newId();
    private final AccountId dest = AccountId.newId();
    private final TenantId tenant = new TenantId(java.util.UUID.randomUUID());

    @Test
    void acceptsABalancedTwoPostingTransaction() {
        DoubleEntryTransaction tx = DoubleEntryTransaction.of(
                TransactionId.newId(), tenant,
                List.of(
                        new Posting(source, EntryType.DEBIT, Money.of(1000, "USD")),
                        new Posting(dest, EntryType.CREDIT, Money.of(1000, "USD"))
                ),
                "transfer", Instant.now(), Map.of());

        assertThat(tx.postings()).hasSize(2);
        assertThat(tx.affectedAccounts()).containsExactly(source, dest);
    }

    @Test
    void rejectsAnUnbalancedTransaction() {
        assertThatThrownBy(() -> DoubleEntryTransaction.of(
                TransactionId.newId(), tenant,
                List.of(
                        new Posting(source, EntryType.DEBIT, Money.of(1000, "USD")),
                        new Posting(dest, EntryType.CREDIT, Money.of(999, "USD"))
                ),
                "bad", Instant.now(), Map.of()))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void rejectsFewerThanTwoPostings() {
        assertThatThrownBy(() -> DoubleEntryTransaction.of(
                TransactionId.newId(), tenant,
                List.of(new Posting(source, EntryType.DEBIT, Money.of(1000, "USD"))),
                "bad", Instant.now(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void balancesEachCurrencyIndependentlyInAMultiCurrencyTransaction() {
        AccountId usdAccount = AccountId.newId();
        AccountId eurAccount = AccountId.newId();
        AccountId usdAccount2 = AccountId.newId();
        AccountId eurAccount2 = AccountId.newId();

        DoubleEntryTransaction tx = DoubleEntryTransaction.of(
                TransactionId.newId(), tenant,
                List.of(
                        new Posting(usdAccount, EntryType.DEBIT, Money.of(500, "USD")),
                        new Posting(usdAccount2, EntryType.CREDIT, Money.of(500, "USD")),
                        new Posting(eurAccount, EntryType.DEBIT, Money.of(300, "EUR")),
                        new Posting(eurAccount2, EntryType.CREDIT, Money.of(300, "EUR"))
                ),
                "fx-neutral batch", Instant.now(), Map.of());

        assertThat(tx.postings()).hasSize(4);
    }

    @Test
    void rejectsWhenOnlyOneCurrencyInAMultiCurrencyBatchIsUnbalanced() {
        AccountId usdAccount = AccountId.newId();
        AccountId eurAccount = AccountId.newId();
        AccountId usdAccount2 = AccountId.newId();
        AccountId eurAccount2 = AccountId.newId();

        assertThatThrownBy(() -> DoubleEntryTransaction.of(
                TransactionId.newId(), tenant,
                List.of(
                        new Posting(usdAccount, EntryType.DEBIT, Money.of(500, "USD")),
                        new Posting(usdAccount2, EntryType.CREDIT, Money.of(500, "USD")),
                        new Posting(eurAccount, EntryType.DEBIT, Money.of(300, "EUR")),
                        new Posting(eurAccount2, EntryType.CREDIT, Money.of(299, "EUR"))
                ),
                "bad batch", Instant.now(), Map.of()))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void postingAmountMustBePositive() {
        assertThatThrownBy(() -> new Posting(source, EntryType.DEBIT, Money.zero(java.util.Currency.getInstance("USD"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
