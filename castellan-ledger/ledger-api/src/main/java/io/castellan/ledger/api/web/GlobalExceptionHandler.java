package io.castellan.ledger.api.web;

import io.castellan.ledger.api.web.dto.ErrorResponse;
import io.castellan.ledger.api.web.dto.FraudFlagDto;
import io.castellan.ledger.application.InsufficientFundsException;
import io.castellan.ledger.application.TransactionBlockedException;
import io.castellan.ledger.application.ports.IdempotencyStore;
import io.castellan.ledger.domain.Account;
import io.castellan.ledger.domain.UnbalancedTransactionException;
import io.castellan.ledger.domain.ports.ConcurrencyConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The one place every domain/application exception this API can throw becomes an HTTP status --
 * see each handler for the reasoning; the mapping is deliberately specific (never a blanket
 * "anything unexpected is a 400") so a client can branch on status code alone.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(UnbalancedTransactionException.class)
    public ResponseEntity<ErrorResponse> onUnbalanced(UnbalancedTransactionException e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(Account.AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> onAccountNotFound(Account.AccountNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(Account.AccountNotPostableException.class)
    public ResponseEntity<ErrorResponse> onAccountNotPostable(Account.AccountNotPostableException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ErrorResponse> onInsufficientFunds(InsufficientFundsException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ErrorResponse.of(e.getMessage()));
    }

    /** The audit trail (FraudFlagRaised events) is already committed by the time this is thrown --
     * see {@link TransactionBlockedException}'s own docs -- so the response body's job is just to
     * tell the caller *why*, with every flag the pipeline raised, not only the one that blocked. */
    @ExceptionHandler(TransactionBlockedException.class)
    public ResponseEntity<ErrorResponse> onTransactionBlocked(TransactionBlockedException e) {
        var flags = e.verdict().flags().stream().map(FraudFlagDto::of).toList();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.ofFraud(e.getMessage(), flags));
    }

    @ExceptionHandler(IdempotencyStore.AlreadyReservedException.class)
    public ResponseEntity<ErrorResponse> onIdempotencyConflict(IdempotencyStore.AlreadyReservedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(ConcurrencyConflictException.class)
    public ResponseEntity<ErrorResponse> onConcurrencyConflict(ConcurrencyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(e.getMessage()));
    }

    /** {@link Account#freeze}/{@link Account#unfreeze}/{@link Account#close} throw plain
     * {@link IllegalStateException} for an invalid lifecycle transition (e.g. freezing an
     * already-closed account) -- a conflict with the resource's current state, not a malformed
     * request. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> onIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(e.getMessage()));
    }

    /** Catches every plain-validation failure below the DTO/Bean-Validation layer: an unparseable
     * account id or currency code, a blank idempotency key, {@code Money}/{@code Posting}'s own
     * constructor guards, etc. -- anything that's ultimately "the request itself is malformed". */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> onIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> onValidationFailure(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("validation failed");
        return ResponseEntity.badRequest().body(ErrorResponse.of(message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> onMalformedBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("malformed request body"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> onUnexpected(Exception e) {
        log.error("unhandled exception reaching the API boundary", e);
        return ResponseEntity.internalServerError().body(ErrorResponse.of("internal error"));
    }
}
