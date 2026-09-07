package io.castellan.ledger.api.fraud;

import io.castellan.ledger.application.fraud.FraudCheckContext;
import io.castellan.ledger.application.fraud.FraudRule;
import io.castellan.ledger.application.fraud.FraudSignal;
import io.castellan.ledger.application.fraud.rules.NewAccountLargeTransferRule;
import io.castellan.ledger.domain.Money;

import java.time.Clock;
import java.time.Duration;

/**
 * Adapts {@link NewAccountLargeTransferRule} -- which is deliberately a pure function of an
 * explicit {@code now} passed to its constructor, not something that reads the system clock
 * itself, precisely so it stays trivially unit-testable -- to {@code ledger-api}'s long-lived,
 * singleton {@code FraudRuleEngine} bean. A {@code FraudRuleEngine} is built once at startup and
 * reused for the app's whole lifetime, so a single baked-in {@code now} captured at construction
 * time would go stale the moment the process had been running for more than {@code minAccountAge}
 * (every account would eventually look "new" relative to a frozen, ever-more-wrong instant, or
 * worse, look negative-age for anything opened after startup). This class is the fix: it holds
 * only the two real configuration values and a {@link Clock}, and builds a fresh
 * {@link NewAccountLargeTransferRule} -- reading the clock -- on every single evaluation.
 */
public final class LiveNewAccountLargeTransferRule implements FraudRule {

    private final Duration minAccountAge;
    private final Money amountThreshold;
    private final Clock clock;

    public LiveNewAccountLargeTransferRule(Duration minAccountAge, Money amountThreshold, Clock clock) {
        this.minAccountAge = minAccountAge;
        this.amountThreshold = amountThreshold;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "new-account-large-transfer";
    }

    @Override
    public FraudSignal evaluate(FraudCheckContext context) {
        return new NewAccountLargeTransferRule(minAccountAge, amountThreshold, clock.instant()).evaluate(context);
    }
}
