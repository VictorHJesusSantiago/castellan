package io.castellan.ledger.api.web;

import io.castellan.ledger.api.tenant.TenantContext;
import io.castellan.ledger.api.web.dto.TransferRequest;
import io.castellan.ledger.api.web.dto.TransferResponse;
import io.castellan.ledger.application.commands.InitiateTransferCommand;
import io.castellan.ledger.application.results.TransferResult;
import io.castellan.ledger.application.saga.TransferSagaOrchestrator;
import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.IdempotencyKey;
import io.castellan.ledger.domain.TenantId;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferSagaOrchestrator orchestrator;

    public TransferController(TransferSagaOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * A transfer's saga can legitimately end in one of two terminal states, and only one of them
     * is "success" in the HTTP sense: {@link TransferResult.Completed} maps to 200 (money moved),
     * {@link TransferResult.Compensated} maps to 409 Conflict -- money never left the source
     * account (it left and came straight back, net effect zero, fully audited -- see that record's
     * own docs), so this is never a 500: the system did exactly what it should when the
     * destination couldn't accept the funds. The response body always distinguishes the two via
     * {@code status}, so a client never has to guess from the HTTP code alone.
     */
    @PostMapping
    public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest request) {
        TenantId tenantId = TenantContext.current();
        TransferResult result = orchestrator.execute(new InitiateTransferCommand(
                tenantId,
                new AccountId(request.sourceAccountId()),
                new AccountId(request.destinationAccountId()),
                request.amount().toMoney(),
                request.description(),
                new IdempotencyKey(request.idempotencyKey())));

        return switch (result) {
            case TransferResult.Completed completed -> ResponseEntity.ok(
                    new TransferResponse(completed.sagaId().toString(), "COMPLETED", null));
            case TransferResult.Compensated compensated -> ResponseEntity.status(HttpStatus.CONFLICT).body(
                    new TransferResponse(compensated.sagaId().toString(), "COMPENSATED", compensated.reason()));
        };
    }
}
