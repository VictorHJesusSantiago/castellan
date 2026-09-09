package io.castellan.ledger.application.fraud;

import java.util.List;

/** The engine's aggregate decision: every {@link FraudSignal.Flag}/{@link FraudSignal.Block}
 * raised across all rules (for audit — {@code PostTransactionHandler} turns each into a
 * {@code FraudFlagRaised} event, allowed or not), plus whether the transaction is actually
 * allowed to proceed. */
public record FraudVerdict(boolean blocked, List<RaisedFlag> flags) {

    public record RaisedFlag(String ruleName, String reason, boolean isBlock) {
    }

    public static FraudVerdict allow() {
        return new FraudVerdict(false, List.of());
    }
}
