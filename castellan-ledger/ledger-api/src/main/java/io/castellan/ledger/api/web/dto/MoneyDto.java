package io.castellan.ledger.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Currency;

/**
 * Deliberately does not re-validate "amount must be positive" here -- {@link
 * io.castellan.ledger.domain.Posting}'s own compact constructor already enforces that invariant
 * for request postings (mapped to 400 by {@code GlobalExceptionHandler} on
 * {@code IllegalArgumentException}), and this same DTO also carries response balances, which are
 * legitimately zero or (in a currency the account has no history in) never populated at all --
 * baking in {@code @Positive} would make this type unusable for half of what it's used for.
 */
public record MoneyDto(
        long minorUnits,
        @NotBlank String currency
) {
    public static MoneyDto from(io.castellan.ledger.domain.Money money) {
        return new MoneyDto(money.minorUnits(), money.currency().getCurrencyCode());
    }

    public io.castellan.ledger.domain.Money toMoney() {
        return new io.castellan.ledger.domain.Money(minorUnits, Currency.getInstance(currency));
    }
}
