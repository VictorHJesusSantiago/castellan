package io.castellan.ledger.application.fraud.rules;

import io.castellan.ledger.application.commands.PostingRequest;
import io.castellan.ledger.application.fraud.FraudCheckContext;
import io.castellan.ledger.application.fraud.FraudRule;
import io.castellan.ledger.application.fraud.FraudSignal;
import io.castellan.ledger.domain.Account;
import io.castellan.ledger.domain.Money;

import java.time.Duration;
import java.time.Instant;

/** Flags a transaction whose amount meets {@code amountThreshold} when it touches an account
 * that was opened less than {@code minAccountAge} ago — a classic "new account, unusually large
 * first move" signal. Needs a reference clock ({@code now}) supplied per evaluation (not read
 * from the system clock internally) so this rule, like every other one in this package, stays a
 * pure function of its explicit inputs and is exactly reproducible in a test. */
public final class NewAccountLargeTransferRule implements FraudRule {

    private final Duration minAccountAge;
    private final Money amountThreshold;
    private final Instant now;

    public NewAccountLargeTransferRule(Duration minAccountAge, Money amountThreshold, Instant now) {
        this.minAccountAge = minAccountAge;
        this.amountThreshold = amountThreshold;
        this.now = now;
    }

    @Override
    public String name() {
        return "new-account-large-transfer";
    }

    @Override
    public FraudSignal evaluate(FraudCheckContext context) {
        for (PostingRequest posting : context.command().postings()) {
            if (!posting.amount().currency().equals(amountThreshold.currency())
                    || posting.amount().compareTo(amountThreshold) < 0) {
                continue;
            }
            Account account = context.involvedAccounts().get(posting.accountId());
            if (account == null || account.openedAt() == null) {
                continue;
            }
            Duration age = Duration.between(account.openedAt(), now);
            if (age.compareTo(minAccountAge) < 0) {
                return new FraudSignal.Flag("account " + account.id() + " is only " + age
                        + " old and involved in a transfer of " + posting.amount().minorUnits()
                        + " " + posting.amount().currency().getCurrencyCode());
            }
        }
        return FraudSignal.ALLOW;
    }
}
