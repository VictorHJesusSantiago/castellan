package io.castellan.ledger.api.web.dto;

import java.util.List;

public record ErrorResponse(
        String error,
        List<FraudFlagDto> fraudFlags
) {
    public static ErrorResponse of(String error) {
        return new ErrorResponse(error, null);
    }

    public static ErrorResponse ofFraud(String error, List<FraudFlagDto> fraudFlags) {
        return new ErrorResponse(error, fraudFlags);
    }
}
