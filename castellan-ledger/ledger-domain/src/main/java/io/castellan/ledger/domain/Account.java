package io.castellan.ledger.domain;

import io.castellan.ledger.domain.events.AccountClosed;
import io.castellan.ledger.domain.events.AccountFrozen;
import io.castellan.ledger.domain.events.AccountOpened;
import io.castellan.ledger.domain.events.AccountUnfrozen;
import io.castellan.ledger.domain.events.DomainEvent;

import java.time.Instant;
import java.util.Currency;
import java.util.List;

/**
 * The account aggregate — deliberately <strong>not</strong> where balance lives. Everything here
 * is lifecycle (open/freeze/unfreeze/close) and the invariants that guard whether a posting may
 * touch this account at all; the balance itself is a projection over every
 * {@code TransactionPosted} event that references this account (see
 * {@code AccountBalanceProjection} in {@code ledger-infrastructure}) — computing it doesn't
 * require replaying *this* aggregate at all, only the ledger stream. Keeping the two apart is
 * what lets balance queries (read-heavy, cacheable, eventually-consistent-is-fine) and lifecycle
 * commands (write-rare, must-be-strongly-consistent) scale independently, which is the entire
 * point of CQRS.
 *
 * <p>Instances are built two ways: {@link #open} for a brand-new account (produces the first
 * event, doesn't yet reflect it — call {@link #replay} on {@code List.of(that event)} to get a
 * live instance, exactly like every other command-then-replay round trip in this codebase), or
 * {@link #replay} to reconstruct current state from a stored event stream.
 */
public final class Account {

    private AccountId id;
    private TenantId tenantId;
    private Currency currency;
    private String ownerName;
    private AccountStatus status;
    private Instant openedAt;
    private long version;

    private Account() {
    }

    public static AccountOpened open(AccountId id, TenantId tenantId, Currency currency, String ownerName, Instant now) {
        if (ownerName == null || ownerName.isBlank()) {
            throw new IllegalArgumentException("ownerName must not be blank");
        }
        return new AccountOpened(id, tenantId, currency, ownerName, now);
    }

    public static Account replay(List<DomainEvent> events) {
        Account account = new Account();
        for (DomainEvent event : events) {
            account.apply(event);
        }
        return account;
    }

    public void apply(DomainEvent event) {
        switch (event) {
            case AccountOpened e -> {
                this.id = e.accountId();
                this.tenantId = e.tenantId();
                this.currency = e.currency();
                this.ownerName = e.ownerName();
                this.status = AccountStatus.OPEN;
                this.openedAt = e.occurredAt();
            }
            case AccountFrozen e -> this.status = AccountStatus.FROZEN;
            case AccountUnfrozen e -> this.status = AccountStatus.OPEN;
            case AccountClosed e -> this.status = AccountStatus.CLOSED;
            default -> {
            }
        }
        version++;
    }

    public AccountFrozen freeze(String reason, Instant now) {
        requireExists();
        if (status == AccountStatus.CLOSED) {
            throw new IllegalStateException("cannot freeze a closed account: " + id);
        }
        return new AccountFrozen(id, tenantId, reason, now);
    }

    public AccountUnfrozen unfreeze(String reason, Instant now) {
        requireExists();
        if (status != AccountStatus.FROZEN) {
            throw new IllegalStateException("account is not frozen: " + id);
        }
        return new AccountUnfrozen(id, tenantId, reason, now);
    }

    public AccountClosed close(String reason, Instant now) {
        requireExists();
        if (status == AccountStatus.CLOSED) {
            throw new IllegalStateException("account is already closed: " + id);
        }
        return new AccountClosed(id, tenantId, reason, now);
    }

    /** Whether a new posting may currently touch this account — open accounts only; a frozen or
     * closed account rejects new debits/credits (a frozen account can still be read, just not
     * written to; see this class's own docstring). */
    public void requireCanPost() {
        requireExists();
        if (status != AccountStatus.OPEN) {
            throw new AccountNotPostableException(id, status);
        }
    }

    private void requireExists() {
        if (id == null) {
            throw new IllegalStateException("account has no events applied yet — does it exist?");
        }
    }

    public AccountId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public Currency currency() {
        return currency;
    }

    public String ownerName() {
        return ownerName;
    }

    public AccountStatus status() {
        return status;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public long version() {
        return version;
    }

    public boolean exists() {
        return id != null;
    }

    /** Raised when a posting is attempted against an account that isn't {@link AccountStatus#OPEN}. */
    public static final class AccountNotPostableException extends RuntimeException {
        public AccountNotPostableException(AccountId accountId, AccountStatus status) {
            super("account " + accountId + " cannot accept postings, status=" + status);
        }
    }

    /** Raised when a command references an account id with no {@code AccountOpened} event ever
     * recorded for it. */
    public static final class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException(AccountId accountId) {
            super("no such account: " + accountId);
        }
    }
}
