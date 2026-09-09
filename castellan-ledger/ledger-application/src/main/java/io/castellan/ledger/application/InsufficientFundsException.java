package io.castellan.ledger.application;

import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.Money;

/** Thrown by {@link PostTransactionHandler} when a transaction's net debit against an account
 * would take its balance below zero. This is a flat "no overdraft, ever" policy, not a
 * configurable-per-account overdraft limit — a real product would likely want the latter; it's a
 * genuine, stated scope cut here (see {@code docs/ROADMAP.md}), layered cleanly on top of
 * {@code BalancePort} if it were added later. */
public final class InsufficientFundsException extends RuntimeException {

    private final AccountId accountId;
    private final Money currentBalance;
    private final Money attemptedDebit;

    public InsufficientFundsException(AccountId accountId, Money currentBalance, Money attemptedDebit) {
        super("account " + accountId + " has balance " + currentBalance.minorUnits()
                + " but transaction would debit " + attemptedDebit.minorUnits());
        this.accountId = accountId;
        this.currentBalance = currentBalance;
        this.attemptedDebit = attemptedDebit;
    }

    public AccountId accountId() {
        return accountId;
    }

    public Money currentBalance() {
        return currentBalance;
    }

    public Money attemptedDebit() {
        return attemptedDebit;
    }
}
