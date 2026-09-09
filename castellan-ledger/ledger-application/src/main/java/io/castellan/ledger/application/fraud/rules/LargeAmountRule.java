package io.castellan.ledger.application.fraud.rules;

import io.castellan.ledger.application.commands.PostingRequest;
import io.castellan.ledger.application.fraud.FraudCheckContext;
import io.castellan.ledger.application.fraud.FraudRule;
import io.castellan.ledger.application.fraud.FraudSignal;
import io.castellan.ledger.domain.Money;

/** Flags (does not block — a large transfer is unusual, not automatically fraudulent) any
 * posting at or above {@code threshold} in the threshold's own currency; postings in other
 * currencies are not evaluated by this instance — configure one instance per currency that needs
 * its own threshold. */
public final class LargeAmountRule implements FraudRule {

    private final Money threshold;

    public LargeAmountRule(Money threshold) {
        this.threshold = threshold;
    }

    @Override
    public String name() {
        return "large-amount";
    }

    @Override
    public FraudSignal evaluate(FraudCheckContext context) {
        for (PostingRequest posting : context.command().postings()) {
            if (!posting.amount().currency().equals(threshold.currency())) {
                continue;
            }
            if (posting.amount().compareTo(threshold) >= 0) {
                return new FraudSignal.Flag("posting of " + posting.amount().minorUnits() + " "
                        + posting.amount().currency().getCurrencyCode() + " meets or exceeds threshold of "
                        + threshold.minorUnits());
            }
        }
        return FraudSignal.ALLOW;
    }
}
