package io.castellan.ledger.domain;

import java.util.Currency;

/** The one exception a real double-entry ledger can never allow past its front door: postings
 * whose debits and credits don't sum to zero, per currency. Thrown by
 * {@link DoubleEntryTransaction}'s factory — there is no way to construct an unbalanced
 * transaction instance at all, not even transiently. */
public final class UnbalancedTransactionException extends RuntimeException {

    public UnbalancedTransactionException(Currency currency, long debitTotal, long creditTotal) {
        super("unbalanced transaction in " + currency.getCurrencyCode()
                + ": debits=" + debitTotal + " credits=" + creditTotal);
    }
}
