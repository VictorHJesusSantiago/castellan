package io.castellan.ledger.api.web;

import io.castellan.ledger.api.tenant.TenantContext;
import io.castellan.ledger.api.web.dto.PostTransactionRequest;
import io.castellan.ledger.api.web.dto.PostingDto;
import io.castellan.ledger.api.web.dto.TransactionResponse;
import io.castellan.ledger.application.PostTransactionHandler;
import io.castellan.ledger.application.commands.PostTransactionCommand;
import io.castellan.ledger.application.commands.PostingRequest;
import io.castellan.ledger.application.results.TransactionResult;
import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.IdempotencyKey;
import io.castellan.ledger.domain.TenantId;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/transactions")
public class TransactionController {

    private final PostTransactionHandler postTransactionHandler;

    public TransactionController(
            @Qualifier("customerFacingPostTransactionHandler") PostTransactionHandler postTransactionHandler) {
        this.postTransactionHandler = postTransactionHandler;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> post(@Valid @RequestBody PostTransactionRequest request) {
        TenantId tenantId = TenantContext.current();
        List<PostingRequest> postings = request.postings().stream().map(TransactionController::toPostingRequest).toList();

        TransactionResult result = postTransactionHandler.handle(new PostTransactionCommand(
                tenantId, postings, request.description(), request.metadata(),
                new IdempotencyKey(request.idempotencyKey())));

        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponse.of(result));
    }

    private static PostingRequest toPostingRequest(PostingDto dto) {
        return new PostingRequest(new AccountId(dto.accountId()), dto.entryType(), dto.amount().toMoney());
    }
}
