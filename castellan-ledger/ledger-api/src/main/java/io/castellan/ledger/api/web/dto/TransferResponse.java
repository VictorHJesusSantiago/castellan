package io.castellan.ledger.api.web.dto;

public record TransferResponse(
        String sagaId,
        String status,
        String reason
) {
}
