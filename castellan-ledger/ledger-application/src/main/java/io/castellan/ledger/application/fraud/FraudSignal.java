package io.castellan.ledger.application.fraud;

/** What one {@link FraudRule} decided about one proposed transaction. */
public sealed interface FraudSignal permits FraudSignal.Allow, FraudSignal.Flag, FraudSignal.Block {

    FraudSignal ALLOW = new Allow();

    record Allow() implements FraudSignal {
    }

    /** Recorded for audit and investigation, but does not by itself stop the transaction. */
    record Flag(String reason) implements FraudSignal {
    }

    /** Stops the transaction outright — {@link FraudRuleEngine} short-circuits the remaining
     * rules the instant any one of them returns a {@code Block}. */
    record Block(String reason) implements FraudSignal {
    }
}
