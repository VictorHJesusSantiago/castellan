package io.castellan.ledger.application.commands;

import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.EntryType;
import io.castellan.ledger.domain.Money;

public record PostingRequest(AccountId accountId, EntryType entryType, Money amount) {
}
