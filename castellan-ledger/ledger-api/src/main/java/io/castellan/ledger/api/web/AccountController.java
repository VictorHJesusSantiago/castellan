package io.castellan.ledger.api.web;

import io.castellan.ledger.api.service.AccountLifecycleService;
import io.castellan.ledger.api.tenant.TenantContext;
import io.castellan.ledger.api.web.dto.AccountResponse;
import io.castellan.ledger.api.web.dto.OpenAccountRequest;
import io.castellan.ledger.api.web.dto.ReasonRequest;
import io.castellan.ledger.application.ports.BalancePort;
import io.castellan.ledger.domain.Account;
import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.TenantId;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Currency;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountLifecycleService accountLifecycleService;
    private final BalancePort balancePort;

    public AccountController(AccountLifecycleService accountLifecycleService, BalancePort balancePort) {
        this.accountLifecycleService = accountLifecycleService;
        this.balancePort = balancePort;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request) {
        TenantId tenantId = TenantContext.current();
        AccountId accountId = AccountId.newId();
        Account account = accountLifecycleService.open(
                tenantId, accountId, Currency.getInstance(request.currency()), request.ownerName());
        AccountResponse body = AccountResponse.of(account, balancePort.currentBalance(tenantId, accountId));
        return ResponseEntity.created(URI.create("/accounts/" + accountId)).body(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> get(@PathVariable("id") String id) {
        TenantId tenantId = TenantContext.current();
        AccountId accountId = AccountId.of(id);
        Account account = accountLifecycleService.load(tenantId, accountId);
        if (!account.exists()) {
            throw new Account.AccountNotFoundException(accountId);
        }
        return ResponseEntity.ok(AccountResponse.of(account, balancePort.currentBalance(tenantId, accountId)));
    }

    @PostMapping("/{id}/freeze")
    public ResponseEntity<AccountResponse> freeze(@PathVariable("id") String id, @Valid @RequestBody ReasonRequest request) {
        TenantId tenantId = TenantContext.current();
        AccountId accountId = AccountId.of(id);
        Account account = accountLifecycleService.freeze(tenantId, accountId, request.reason());
        return ResponseEntity.ok(AccountResponse.of(account, balancePort.currentBalance(tenantId, accountId)));
    }

    @PostMapping("/{id}/unfreeze")
    public ResponseEntity<AccountResponse> unfreeze(@PathVariable("id") String id, @Valid @RequestBody ReasonRequest request) {
        TenantId tenantId = TenantContext.current();
        AccountId accountId = AccountId.of(id);
        Account account = accountLifecycleService.unfreeze(tenantId, accountId, request.reason());
        return ResponseEntity.ok(AccountResponse.of(account, balancePort.currentBalance(tenantId, accountId)));
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<AccountResponse> close(@PathVariable("id") String id, @Valid @RequestBody ReasonRequest request) {
        TenantId tenantId = TenantContext.current();
        AccountId accountId = AccountId.of(id);
        Account account = accountLifecycleService.close(tenantId, accountId, request.reason());
        return ResponseEntity.ok(AccountResponse.of(account, balancePort.currentBalance(tenantId, accountId)));
    }
}
