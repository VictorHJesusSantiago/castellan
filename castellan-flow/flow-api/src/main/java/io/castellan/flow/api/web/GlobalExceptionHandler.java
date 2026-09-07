package io.castellan.flow.api.web;

import io.castellan.bpmn.exec.BpmnExecutionException;
import io.castellan.bpmn.parser.BpmnParseException;
import io.castellan.flow.api.web.dto.ErrorResponse;
import io.castellan.flow.engine.NoSuchProcessInstanceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** The one place every exception a controller can throw becomes an HTTP status -- mapping is
 * deliberately specific rather than a blanket "anything unexpected is a 400", matching {@code
 * ledger-api}'s own {@code GlobalExceptionHandler}. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NoSuchProcessInstanceException.class)
    public ResponseEntity<ErrorResponse> onNoSuchInstance(NoSuchProcessInstanceException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    /** {@code ProcessEngine#deployDefinition} parses the submitted BPMN XML before persisting it
     * (see that method's own docs) -- malformed or structurally invalid BPMN is a client error,
     * not a server fault. */
    @ExceptionHandler(BpmnParseException.class)
    public ResponseEntity<ErrorResponse> onBpmnParseFailure(BpmnParseException e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(e.getMessage()));
    }

    /** A signal/compensate call that the process graph's current state can't accept (wrong token,
     * no matching join wave, etc.) -- the request was well-formed, but not currently valid. */
    @ExceptionHandler(BpmnExecutionException.class)
    public ResponseEntity<ErrorResponse> onBpmnExecution(BpmnExecutionException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ErrorResponse.of(e.getMessage()));
    }

    /** {@code ProcessEngine} throws this if an instance's pinned definition version was somehow
     * removed underneath it -- a genuine, if unlikely, conflict between the instance's
     * expectation and the current deployment state. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> onIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(e.getMessage()));
    }

    /** Covers an undeployed {@code processId}/version and any plain argument-validation failure
     * below the DTO/Bean-Validation layer. */
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
