package io.castellan.ledger.infrastructure.projection;

import io.castellan.ledger.application.ports.BalancePort;
import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.EntryType;
import io.castellan.ledger.domain.Money;
import io.castellan.ledger.domain.Posting;
import io.castellan.ledger.domain.Streams;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.events.AccountOpened;
import io.castellan.ledger.domain.events.TransactionPosted;
import io.castellan.ledger.infrastructure.event.EventJsonCodec;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.Currency;
import java.util.List;

/**
 * The real, incrementally-maintained {@link BalancePort}: a running per-(tenant, account) balance
 * in the {@code account_balances} table, updated one posting at a time by {@link #apply} as
 * {@code TransactionPosted} events are appended -- never rebuilt by replaying the ledger stream.
 * {@link #apply} is only ever invoked by
 * {@link io.castellan.ledger.infrastructure.event.JdbcEventStore#append}, inside the very same
 * database transaction as the event's own insert, so a balance update and the event that caused
 * it are never observably out of sync (one commits, both commit; one rolls back, both roll back).
 *
 * <p>Sign convention matches {@link BalancePort}'s own docs: CREDIT increases, DEBIT decreases,
 * the customer-facing convention this whole system uses.
 */
public final class AccountBalanceProjection implements BalancePort {

    private final JdbcTemplate jdbc;
    private final EventJsonCodec codec;

    public AccountBalanceProjection(JdbcTemplate jdbc, EventJsonCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    @Override
    public Money currentBalance(TenantId tenantId, AccountId accountId) {
        List<Money> rows = jdbc.query(
                "SELECT balance_minor_units, currency FROM account_balances WHERE tenant_id = ? AND account_id = ?",
                (rs, i) -> new Money(rs.getLong(1), Currency.getInstance(rs.getString(2))),
                tenantId.value().toString(), accountId.value().toString());
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        return zeroBalanceForAccount(tenantId, accountId);
    }

    /** An account with no {@code account_balances} row yet has, by definition, never been posted
     * to -- its balance is zero, but zero <em>in its own currency</em>, which we resolve from its
     * own {@code AccountOpened} event rather than assuming a default. An account with neither a
     * balance row nor an {@code AccountOpened} event is one {@code PostTransactionHandler} would
     * itself have already rejected via {@code AccountRepository}/{@code Account.requireCanPost}
     * before ever asking this port for a balance -- callers here are trusted to have validated
     * existence already, so this falls back to a zero USD balance rather than throwing. */
    private Money zeroBalanceForAccount(TenantId tenantId, AccountId accountId) {
        String streamId = Streams.account(tenantId, accountId);
        List<String> payloads = jdbc.query(
                "SELECT payload_json FROM events WHERE stream_id = ? AND event_type = 'AccountOpened' ORDER BY version ASC",
                (rs, i) -> rs.getString(1), streamId);
        if (payloads.isEmpty()) {
            return Money.zero(Currency.getInstance("USD"));
        }
        AccountOpened opened = (AccountOpened) codec.deserialize("AccountOpened", payloads.get(0));
        return Money.zero(opened.currency());
    }

    public void apply(TenantId tenantId, TransactionPosted posted) {
        for (Posting p : posted.postings()) {
            long delta = p.entryType() == EntryType.CREDIT ? p.amount().minorUnits() : -p.amount().minorUnits();
            int updated = jdbc.update(
                    "UPDATE account_balances SET balance_minor_units = balance_minor_units + ?, updated_at = ? "
                            + "WHERE tenant_id = ? AND account_id = ?",
                    delta, Timestamp.from(posted.occurredAt()), tenantId.value().toString(), p.accountId().value().toString());
            if (updated == 0) {
                jdbc.update(
                        "INSERT INTO account_balances (tenant_id, account_id, currency, balance_minor_units, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?)",
                        tenantId.value().toString(), p.accountId().value().toString(),
                        p.amount().currency().getCurrencyCode(), delta, Timestamp.from(posted.occurredAt()));
            }
        }
    }
}
