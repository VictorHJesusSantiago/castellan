package io.castellan.ledger.application.commands;

import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.IdempotencyKey;
import io.castellan.ledger.domain.Money;
import io.castellan.ledger.domain.TenantId;

public record InitiateTransferCommand(
        TenantId tenantId,
        AccountId sourceAccountId,
        AccountId destinationAccountId,
        Money amount,
        String description,
        IdempotencyKey idempotencyKey
) {
}
