package io.castellan.ledger.application.fraud.rules;

import io.castellan.ledger.application.commands.PostingRequest;
import io.castellan.ledger.application.fraud.FraudCheckContext;
import io.castellan.ledger.application.fraud.FraudRule;
import io.castellan.ledger.application.fraud.FraudSignal;
import io.castellan.ledger.domain.AccountId;

/**
 * Blocks a transaction touching an account that has already posted {@code maxTransactions} or
 * more transactions within the caller-supplied recent window (see
 * {@code FraudCheckContext#recentTransactionCounts}, populated by
 * {@code PostTransactionHandler} from a {@code RecentActivityPort} query before this rule ever
 * runs — the rule itself never queries anything, keeping it a pure function of its input, which
 * is what makes it trivially unit-testable with a handful of fabricated counts).
 */
public final class VelocityRule implements FraudRule {

    private final int maxTransactions;

    public VelocityRule(int maxTransactions) {
        if (maxTransactions < 1) {
            throw new IllegalArgumentException("maxTransactions must be >= 1");
        }
        this.maxTransactions = maxTransactions;
    }

    @Override
    public String name() {
        return "velocity";
    }

    @Override
    public FraudSignal evaluate(FraudCheckContext context) {
        for (PostingRequest posting : context.command().postings()) {
            AccountId accountId = posting.accountId();
            int recentCount = context.recentTransactionCounts().getOrDefault(accountId, 0);
            if (recentCount >= maxTransactions) {
                return new FraudSignal.Block("account " + accountId + " has " + recentCount
                        + " recent transactions, at or above the limit of " + maxTransactions);
            }
        }
        return FraudSignal.ALLOW;
    }
}
