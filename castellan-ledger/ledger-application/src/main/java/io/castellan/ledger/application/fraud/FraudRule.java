package io.castellan.ledger.application.fraud;

public interface FraudRule {

    String name();

    FraudSignal evaluate(FraudCheckContext context);
}
