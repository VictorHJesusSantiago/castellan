package io.castellan.ledger.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void addsWithinTheSameCurrency() {
        Money a = Money.of(500, "USD");
        Money b = Money.of(250, "USD");

        assertThat(a.add(b)).isEqualTo(Money.of(750, "USD"));
    }

    @Test
    void subtractsWithinTheSameCurrency() {
        Money a = Money.of(500, "USD");
        Money b = Money.of(250, "USD");

        assertThat(a.subtract(b)).isEqualTo(Money.of(250, "USD"));
    }

    @Test
    void refusesToAddDifferentCurrencies() {
        Money usd = Money.of(500, "USD");
        Money eur = Money.of(500, "EUR");

        assertThatThrownBy(() -> usd.add(eur)).isInstanceOf(Money.CurrencyMismatchException.class);
    }

    @Test
    void refusesToCompareDifferentCurrencies() {
        Money usd = Money.of(500, "USD");
        Money eur = Money.of(500, "EUR");

        assertThatThrownBy(() -> usd.compareTo(eur)).isInstanceOf(Money.CurrencyMismatchException.class);
    }

    @Test
    void negateFlipsSign() {
        assertThat(Money.of(500, "USD").negate()).isEqualTo(Money.of(-500, "USD"));
    }

    @Test
    void zeroIsNeitherPositiveNorNegative() {
        Money zero = Money.zero(java.util.Currency.getInstance("USD"));

        assertThat(zero.isZero()).isTrue();
        assertThat(zero.isPositive()).isFalse();
        assertThat(zero.isNegative()).isFalse();
    }

    @Test
    void additionOverflowThrowsRatherThanWrapping() {
        Money nearMax = Money.of(Long.MAX_VALUE, "USD");
        Money one = Money.of(1, "USD");

        assertThatThrownBy(() -> nearMax.add(one)).isInstanceOf(ArithmeticException.class);
    }
}
