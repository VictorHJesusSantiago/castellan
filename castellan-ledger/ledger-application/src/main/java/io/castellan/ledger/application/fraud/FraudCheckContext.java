package io.castellan.ledger.application.fraud;

import io.castellan.ledger.application.commands.PostTransactionCommand;
import io.castellan.ledger.domain.Account;

import java.util.List;
import java.util.Map;

/** Everything a {@link FraudRule} is allowed to look at. Deliberately a closed, explicit bundle
 * (not "here's a repository, go query whatever you want") — a rule that needs a new kind of
 * signal grows this record, which makes every rule's actual data dependency visible at a glance
 * rather than hidden behind arbitrary port access. */
public record FraudCheckContext(
        PostTransactionCommand command,
        Map<io.castellan.ledger.domain.AccountId, Account> involvedAccounts,
        Map<io.castellan.ledger.domain.AccountId, Integer> recentTransactionCounts
) {
}
