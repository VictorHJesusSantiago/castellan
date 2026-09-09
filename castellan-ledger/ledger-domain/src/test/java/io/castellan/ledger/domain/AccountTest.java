package io.castellan.ledger.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private final AccountId id = AccountId.newId();
    private final TenantId tenant = new TenantId(java.util.UUID.randomUUID());
    private final Currency usd = Currency.getInstance("USD");

    @Test
    void opensWithStatusOpen() {
        var opened = Account.open(id, tenant, usd, "Ada Lovelace", Instant.now());
        Account account = Account.replay(List.of(opened));

        assertThat(account.status()).isEqualTo(AccountStatus.OPEN);
        assertThat(account.ownerName()).isEqualTo("Ada Lovelace");
        assertThat(account.version()).isEqualTo(1);
        account.requireCanPost();
    }

    @Test
    void freezeThenUnfreezeRoundTrips() {
        var opened = Account.open(id, tenant, usd, "Ada Lovelace", Instant.now());
        Account account = Account.replay(List.of(opened));

        var frozen = account.freeze("suspicious activity", Instant.now());
        account.apply(frozen);
        assertThat(account.status()).isEqualTo(AccountStatus.FROZEN);
        assertThatThrownBy(account::requireCanPost).isInstanceOf(Account.AccountNotPostableException.class);

        var unfrozen = account.unfreeze("cleared review", Instant.now());
        account.apply(unfrozen);
        assertThat(account.status()).isEqualTo(AccountStatus.OPEN);
        account.requireCanPost();
    }

    @Test
    void closedAccountCannotBeFrozenOrPosted() {
        var opened = Account.open(id, tenant, usd, "Ada Lovelace", Instant.now());
        Account account = Account.replay(List.of(opened));
        account.apply(account.close("account holder request", Instant.now()));

        assertThat(account.status()).isEqualTo(AccountStatus.CLOSED);
        assertThatThrownBy(account::requireCanPost).isInstanceOf(Account.AccountNotPostableException.class);
        assertThatThrownBy(() -> account.freeze("too late", Instant.now())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unfreezingAnAccountThatIsNotFrozenIsRejected() {
        var opened = Account.open(id, tenant, usd, "Ada Lovelace", Instant.now());
        Account account = Account.replay(List.of(opened));

        assertThatThrownBy(() -> account.unfreeze("nothing to clear", Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replayReconstructsIdenticalStateRegardlessOfHowManyTimesItsCalled() {
        var opened = Account.open(id, tenant, usd, "Ada Lovelace", Instant.now());
        Account first = Account.replay(List.of(opened));
        var frozen = first.freeze("x", Instant.now());

        Account replayed = Account.replay(List.of(opened, frozen));

        assertThat(replayed.status()).isEqualTo(AccountStatus.FROZEN);
        assertThat(replayed.id()).isEqualTo(id);
        assertThat(replayed.version()).isEqualTo(2);
    }

    @Test
    void anAccountWithNoEventsDoesNotExist() {
        Account account = Account.replay(List.of());

        assertThat(account.exists()).isFalse();
        assertThatThrownBy(account::requireCanPost).isInstanceOf(IllegalStateException.class);
    }
}
