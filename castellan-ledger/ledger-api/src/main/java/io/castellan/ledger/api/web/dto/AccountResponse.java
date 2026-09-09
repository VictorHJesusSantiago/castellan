package io.castellan.ledger.api.web.dto;

import io.castellan.ledger.domain.Account;
import io.castellan.ledger.domain.Money;

public record AccountResponse(
        String accountId,
        String tenantId,
        String ownerName,
        String status,
        String currency,
        String openedAt,
        MoneyDto balance
) {
    public static AccountResponse of(Account account, Money balance) {
        return new AccountResponse(
                account.id().toString(),
                account.tenantId().toString(),
                account.ownerName(),
                account.status().name(),
                account.currency().getCurrencyCode(),
                account.openedAt().toString(),
                MoneyDto.from(balance));
    }

    public static AccountResponse ofNoBalance(Account account) {
        return new AccountResponse(
                account.id().toString(),
                account.tenantId().toString(),
                account.ownerName(),
                account.status().name(),
                account.currency().getCurrencyCode(),
                account.openedAt().toString(),
                null);
    }
}
