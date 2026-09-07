package io.castellan.ledger.api.config;

import io.castellan.ledger.api.fraud.LiveNewAccountLargeTransferRule;
import io.castellan.ledger.application.PostTransactionHandler;
import io.castellan.ledger.application.fraud.FraudRuleEngine;
import io.castellan.ledger.application.fraud.rules.LargeAmountRule;
import io.castellan.ledger.application.fraud.rules.VelocityRule;
import io.castellan.ledger.application.ports.AccountRepository;
import io.castellan.ledger.application.ports.BalancePort;
import io.castellan.ledger.application.ports.IdempotencyStore;
import io.castellan.ledger.application.ports.RecentActivityPort;
import io.castellan.ledger.application.saga.TransferSagaOrchestrator;
import io.castellan.ledger.domain.Money;
import io.castellan.ledger.domain.ports.EventStore;
import io.castellan.ledger.infrastructure.event.EventJsonCodec;
import io.castellan.ledger.infrastructure.event.JdbcEventStore;
import io.castellan.ledger.infrastructure.idempotency.JdbcIdempotencyStore;
import io.castellan.ledger.infrastructure.outbox.LoggingOutboxPublisher;
import io.castellan.ledger.infrastructure.outbox.OutboxPublisher;
import io.castellan.ledger.infrastructure.outbox.OutboxRelay;
import io.castellan.ledger.infrastructure.projection.AccountBalanceProjection;
import io.castellan.ledger.infrastructure.projection.TransactionHistoryProjection;
import io.castellan.ledger.infrastructure.repository.JdbcAccountRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.Currency;
import java.util.List;

/**
 * The composition root's bean graph: wires {@code ledger-infrastructure}'s JDBC adapters to the
 * ports {@code ledger-application} programs against, and configures two distinct
 * {@link PostTransactionHandler} instances -- see {@link #customerFacingPostTransactionHandler}
 * and {@link #internalPostTransactionHandler}'s own docs for exactly why two, not one.
 *
 * <p>{@link JdbcTemplate} and {@link PlatformTransactionManager} are not defined here -- Spring
 * Boot autoconfigures both from {@code spring-boot-starter-jdbc} plus the {@code DataSource} it
 * also autoconfigures from {@code application.yml}, and Flyway autoconfiguration runs
 * {@code ledger-infrastructure}'s {@code db/migration} scripts (on this module's classpath via
 * that dependency) against it before any of these beans are ever used.
 */
@Configuration
public class LedgerBeanConfiguration {

    /** No overdraft/no-fraud reason to distrust the system clock in a real deployment; a fixed
     * {@link Clock} is only ever substituted in tests. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    public EventJsonCodec eventJsonCodec() {
        return new EventJsonCodec();
    }

    @Bean
    public AccountBalanceProjection accountBalanceProjection(JdbcTemplate jdbc, EventJsonCodec codec) {
        return new AccountBalanceProjection(jdbc, codec);
    }

    @Bean
    public TransactionHistoryProjection transactionHistoryProjection(JdbcTemplate jdbc, Clock clock) {
        return new TransactionHistoryProjection(jdbc, clock);
    }

    @Bean
    public EventStore eventStore(
            JdbcTemplate jdbc,
            TransactionTemplate transactionTemplate,
            EventJsonCodec codec,
            AccountBalanceProjection accountBalanceProjection,
            TransactionHistoryProjection transactionHistoryProjection,
            Clock clock) {
        return new JdbcEventStore(jdbc, transactionTemplate, codec, accountBalanceProjection, transactionHistoryProjection, clock);
    }

    @Bean
    public AccountRepository accountRepository(EventStore eventStore) {
        return new JdbcAccountRepository(eventStore);
    }

    @Bean
    public IdempotencyStore idempotencyStore(JdbcTemplate jdbc, Clock clock) {
        return new JdbcIdempotencyStore(jdbc, clock);
    }

    @Bean
    public BalancePort balancePort(AccountBalanceProjection accountBalanceProjection) {
        return accountBalanceProjection;
    }

    @Bean
    public RecentActivityPort recentActivityPort(TransactionHistoryProjection transactionHistoryProjection) {
        return transactionHistoryProjection;
    }

    @Bean
    public OutboxPublisher outboxPublisher() {
        return new LoggingOutboxPublisher();
    }

    @Bean
    public OutboxRelay outboxRelay(JdbcTemplate jdbc, OutboxPublisher outboxPublisher,
            TransactionTemplate transactionTemplate, Clock clock) {
        return new OutboxRelay(jdbc, outboxPublisher, transactionTemplate, clock);
    }

    /** The customer-facing fraud pipeline: every rule {@code ledger-application} ships, wired
     * with thresholds reasonable for a demo/test deployment (a real deployment would source these
     * from config, per tenant risk tier). {@link LargeAmountRule} only flags (won't block by
     * itself); {@link VelocityRule} blocks outright above the limit; the new-account rule is the
     * {@link LiveNewAccountLargeTransferRule} adapter, not the raw application-layer rule --
     * see its own docs for why. */
    @Bean
    public FraudRuleEngine customerFacingFraudRuleEngine(Clock clock) {
        Currency usd = Currency.getInstance("USD");
        return new FraudRuleEngine(List.of(
                new LargeAmountRule(new Money(10_000_00, usd)),
                new LiveNewAccountLargeTransferRule(Duration.ofDays(2), new Money(5_000_00, usd), clock),
                new VelocityRule(20)
        ));
    }

    /** The customer-initiated posting path: full fraud scrutiny, {@code /transactions} and the
     * customer-visible half of {@code /transfers} both ultimately depend on this bean (a transfer
     * itself is orchestrated by {@link #internalPostTransactionHandler}/{@link
     * #transferSagaOrchestrator}, but a plain {@code POST /transactions} call goes through this
     * one directly). */
    @Bean
    public PostTransactionHandler customerFacingPostTransactionHandler(
            EventStore eventStore, AccountRepository accountRepository, IdempotencyStore idempotencyStore,
            RecentActivityPort recentActivityPort, BalancePort balancePort,
            @Qualifier("customerFacingFraudRuleEngine") FraudRuleEngine fraudRuleEngine, Clock clock) {
        return new PostTransactionHandler(eventStore, accountRepository, idempotencyStore, recentActivityPort,
                balancePort, fraudRuleEngine, Duration.ofMinutes(10), clock);
    }

    /** {@link TransferSagaOrchestrator}'s own docs are explicit that its internal
     * reserve/capture/compensate postings must go through a <em>separate</em>
     * {@link PostTransactionHandler} instance, configured with no (or a minimal) fraud rule set --
     * these are the system's own bookkeeping moves against the in-transit suspense account, not a
     * fresh customer-initiated transaction that should be re-scrutinized by the same
     * customer-facing pipeline a second and third time. */
    @Bean
    public PostTransactionHandler internalPostTransactionHandler(
            EventStore eventStore, AccountRepository accountRepository, IdempotencyStore idempotencyStore,
            RecentActivityPort recentActivityPort, BalancePort balancePort, Clock clock) {
        return new PostTransactionHandler(eventStore, accountRepository, idempotencyStore, recentActivityPort,
                balancePort, new FraudRuleEngine(List.of()), Duration.ofMinutes(10), clock);
    }

    @Bean
    public TransferSagaOrchestrator transferSagaOrchestrator(
            EventStore eventStore, AccountRepository accountRepository,
            @Qualifier("internalPostTransactionHandler") PostTransactionHandler internalPostTransactionHandler,
            Clock clock) {
        return new TransferSagaOrchestrator(eventStore, accountRepository, internalPostTransactionHandler, clock);
    }
}
