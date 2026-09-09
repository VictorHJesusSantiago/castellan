package io.castellan.ledger.api.web.dto;

import io.castellan.ledger.domain.EntryType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PostingDto(
        @NotNull UUID accountId,
        @NotNull EntryType entryType,
        @NotNull @Valid MoneyDto amount
) {
}
