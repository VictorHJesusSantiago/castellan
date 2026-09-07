package io.castellan.ledger.api.service;

import io.castellan.ledger.application.ports.AccountRepository;
import io.castellan.ledger.domain.Account;
import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.TenantId;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Currency;
import java.util.List;

/**
 * Account open/freeze/unfreeze/close, wired straight against {@link AccountRepository} and
 * {@link Account}'s own command methods -- there is no {@code ledger-application} handler for
 * account lifecycle (it only provides {@code PostTransactionHandler} and
 * {@code TransferSagaOrchestrator}), so this thin service is {@code ledger-api}'s own composition,
 * exactly like a repository-backed command handler anywhere else in this codebase: load, call the
 * command method to get the resulting event, append it back at the version just loaded.
 *
 * <p>Single-attempt, not retrying on {@link io.castellan.ledger.domain.ports.ConcurrencyConflictException}:
 * two concurrent freezes of the same account is a genuine, rare race a real deployment would want
 * a retry loop for, but that's an orthogonal concern to what this exercise is demonstrating and is
 * called out here as a deliberate scope cut rather than silently ignored.
 */
@Service
public final class AccountLifecycleService {

    private final AccountRepository accountRepository;
    private final Clock clock;

    public AccountLifecycleService(AccountRepository accountRepository, Clock clock) {
        this.accountRepository = accountRepository;
        this.clock = clock;
    }

    public Account open(TenantId tenantId, AccountId accountId, Currency currency, String ownerName) {
        var opened = Account.open(accountId, tenantId, currency, ownerName, clock.instant());
        accountRepository.append(tenantId, accountId, 0, List.of(opened));
        return Account.replay(List.of(opened));
    }

    public Account load(TenantId tenantId, AccountId accountId) {
        return accountRepository.load(tenantId, accountId);
    }

    public Account freeze(TenantId tenantId, AccountId accountId, String reason) {
        Account account = requireExisting(tenantId, accountId);
        var frozen = account.freeze(reason, clock.instant());
        accountRepository.append(tenantId, accountId, account.version(), List.of(frozen));
        account.apply(frozen);
        return account;
    }

    public Account unfreeze(TenantId tenantId, AccountId accountId, String reason) {
        Account account = requireExisting(tenantId, accountId);
        var unfrozen = account.unfreeze(reason, clock.instant());
        accountRepository.append(tenantId, accountId, account.version(), List.of(unfrozen));
        account.apply(unfrozen);
        return account;
    }

    public Account close(TenantId tenantId, AccountId accountId, String reason) {
        Account account = requireExisting(tenantId, accountId);
        var closed = account.close(reason, clock.instant());
        accountRepository.append(tenantId, accountId, account.version(), List.of(closed));
        account.apply(closed);
        return account;
    }

    private Account requireExisting(TenantId tenantId, AccountId accountId) {
        Account account = accountRepository.load(tenantId, accountId);
        if (!account.exists()) {
            throw new Account.AccountNotFoundException(accountId);
        }
        return account;
    }
}
