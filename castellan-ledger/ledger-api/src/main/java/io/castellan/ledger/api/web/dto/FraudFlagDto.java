package io.castellan.ledger.api.web.dto;

import io.castellan.ledger.application.fraud.FraudVerdict;

public record FraudFlagDto(String ruleName, String reason, boolean isBlock) {
    public static FraudFlagDto of(FraudVerdict.RaisedFlag flag) {
        return new FraudFlagDto(flag.ruleName(), flag.reason(), flag.isBlock());
    }
}
