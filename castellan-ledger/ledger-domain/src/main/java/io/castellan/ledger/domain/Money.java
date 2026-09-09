package io.castellan.ledger.domain;

import java.util.Currency;

/**
 * An exact monetary amount: a signed count of the currency's smallest unit (cents for USD/EUR,
 * the whole yen for JPY, ...) plus the {@link Currency} itself — never a {@code double}/
 * {@code float}, which cannot represent most decimal amounts exactly and would make the
 * double-entry balance check ({@link DoubleEntryTransaction}) a game of accumulated rounding
 * error instead of an exact invariant.
 *
 * <p>Every arithmetic operation refuses to mix currencies — {@link #add}/{@link #subtract}
 * throw rather than silently doing USD + EUR arithmetic, which is never meaningful without an
 * explicit, rate-carrying conversion this type deliberately does not attempt (currency
 * conversion is a pricing/FX concern, not a ledger-arithmetic one).
 */
public record Money(long minorUnits, Currency currency) implements Comparable<Money> {

    public Money {
        if (currency == null) {
            throw new IllegalArgumentException("currency must not be null");
        }
    }

    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    public static Money of(long minorUnits, String currencyCode) {
        return new Money(minorUnits, Currency.getInstance(currencyCode));
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    public boolean isNegative() {
        return minorUnits < 0;
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    /** Thrown by any operation that would mix two different currencies' amounts together. */
    public static final class CurrencyMismatchException extends RuntimeException {
        public CurrencyMismatchException(Currency expected, Currency actual) {
            super("expected currency " + expected.getCurrencyCode() + " but got " + actual.getCurrencyCode());
        }
    }
}
