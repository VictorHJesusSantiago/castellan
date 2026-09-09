package io.castellan.ledger.api;

import io.castellan.ledger.api.tenant.TenantResolvingFilter;
import io.castellan.ledger.api.web.dto.AccountResponse;
import io.castellan.ledger.api.web.dto.ErrorResponse;
import io.castellan.ledger.api.web.dto.MoneyDto;
import io.castellan.ledger.api.web.dto.OpenAccountRequest;
import io.castellan.ledger.api.web.dto.PostTransactionRequest;
import io.castellan.ledger.api.web.dto.PostingDto;
import io.castellan.ledger.api.web.dto.ReasonRequest;
import io.castellan.ledger.api.web.dto.TransactionResponse;
import io.castellan.ledger.api.web.dto.TransferRequest;
import io.castellan.ledger.api.web.dto.TransferResponse;
import io.castellan.ledger.application.ports.AccountRepository;
import io.castellan.ledger.domain.Account;
import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.EntryType;
import io.castellan.ledger.domain.IdempotencyKey;
import io.castellan.ledger.domain.Money;
import io.castellan.ledger.domain.Posting;
import io.castellan.ledger.domain.Streams;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.TransactionId;
import io.castellan.ledger.domain.events.TransactionPosted;
import io.castellan.ledger.domain.ports.EventStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests over real HTTP, against the real Spring context wired to a real (Flyway-
 * migrated, embedded) H2 database -- see {@code src/test/resources/application.yml} for the
 * per-context-unique database URL. No mocks anywhere in this class: every assertion is either an
 * HTTP response this suite got back from the running server, or a fact read back from the same
 * database through a second, independent path (direct port access), to catch a bug where the HTTP
 * layer and the persistence layer silently disagree.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerApiIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private EventStore eventStore;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private Clock clock;

    private final Currency usd = Currency.getInstance("USD");


    private <T> ResponseEntity<T> post(String path, TenantId tenantId, Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(TenantResolvingFilter.HEADER_NAME, tenantId.toString());
        return restTemplate.exchange("http://localhost:" + port + path, HttpMethod.POST,
                new HttpEntity<>(body, headers), responseType);
    }

    private <T> ResponseEntity<T> get(String path, TenantId tenantId, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TenantResolvingFilter.HEADER_NAME, tenantId.toString());
        return restTemplate.exchange("http://localhost:" + port + path, HttpMethod.GET,
                new HttpEntity<>(headers), responseType);
    }


    private AccountId openAccountViaHttp(TenantId tenantId, String owner) {
        ResponseEntity<AccountResponse> response = post("/accounts", tenantId,
                new OpenAccountRequest(owner, "USD"), AccountResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return AccountId.of(response.getBody().accountId());
    }

    private void seedBalance(TenantId tenantId, AccountId accountId, long minorUnits) {
        AccountId capital = AccountId.newId();
        accountRepository.append(tenantId, capital, 0,
                List.of(Account.open(capital, tenantId, usd, "SYSTEM:capital", clock.instant())));
        TransactionPosted seed = new TransactionPosted(
                TransactionId.newId(), tenantId,
                List.of(
                        new Posting(capital, EntryType.DEBIT, new Money(minorUnits, usd)),
                        new Posting(accountId, EntryType.CREDIT, new Money(minorUnits, usd))),
                "seed", Map.of(), new IdempotencyKey("seed-" + accountId), clock.instant());
        eventStore.append(Streams.ledger(tenantId), eventStore.currentVersion(Streams.ledger(tenantId)), List.of(seed));
    }


    @Test
    void openingAnAccountAndPostingATransactionWorksEndToEndOverHttp() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        AccountId alice = openAccountViaHttp(tenant, "Alice");
        AccountId bob = openAccountViaHttp(tenant, "Bob");
        seedBalance(tenant, alice, 10_000);

        PostTransactionRequest request = new PostTransactionRequest(
                List.of(
                        new PostingDto(alice.value(), EntryType.DEBIT, new MoneyDto(500, "USD")),
                        new PostingDto(bob.value(), EntryType.CREDIT, new MoneyDto(500, "USD"))),
                "rent", Map.of(), "e2e-tx-1");

        ResponseEntity<TransactionResponse> response = post("/transactions", tenant, request, TransactionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().wasIdempotentReplay()).isFalse();

        ResponseEntity<AccountResponse> aliceView = get("/accounts/" + alice, tenant, AccountResponse.class);
        ResponseEntity<AccountResponse> bobView = get("/accounts/" + bob, tenant, AccountResponse.class);
        assertThat(aliceView.getBody().balance().minorUnits()).isEqualTo(9_500);
        assertThat(bobView.getBody().balance().minorUnits()).isEqualTo(500);
    }


    @Test
    void aCompletingTransferMovesFundsAndReturns200() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        AccountId source = openAccountViaHttp(tenant, "Source");
        AccountId destination = openAccountViaHttp(tenant, "Destination");
        seedBalance(tenant, source, 5_000);

        TransferRequest request = new TransferRequest(
                source.value(), destination.value(), new MoneyDto(1_500, "USD"), "rent", "e2e-transfer-1");

        ResponseEntity<TransferResponse> response = post("/transfers", tenant, request, TransferResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("COMPLETED");

        assertThat(get("/accounts/" + source, tenant, AccountResponse.class).getBody().balance().minorUnits())
                .isEqualTo(3_500);
        assertThat(get("/accounts/" + destination, tenant, AccountResponse.class).getBody().balance().minorUnits())
                .isEqualTo(1_500);
    }


    @Test
    void aTransferToANonexistentDestinationCompensatesAndReturns409() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        AccountId source = openAccountViaHttp(tenant, "Source");
        seedBalance(tenant, source, 5_000);
        AccountId ghostDestination = AccountId.newId();

        TransferRequest request = new TransferRequest(
                source.value(), ghostDestination.value(), new MoneyDto(2_000, "USD"), "oops", "e2e-transfer-2");

        ResponseEntity<TransferResponse> response = post("/transfers", tenant, request, TransferResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().status()).isEqualTo("COMPENSATED");
        assertThat(response.getBody().reason()).isNotBlank();

        assertThat(get("/accounts/" + source, tenant, AccountResponse.class).getBody().balance().minorUnits())
                .isEqualTo(5_000);
    }


    @Test
    void postingAnUnbalancedTransactionReturns400() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        AccountId alice = openAccountViaHttp(tenant, "Alice");
        AccountId bob = openAccountViaHttp(tenant, "Bob");
        seedBalance(tenant, alice, 10_000);

        PostTransactionRequest request = new PostTransactionRequest(
                List.of(
                        new PostingDto(alice.value(), EntryType.DEBIT, new MoneyDto(500, "USD")),
                        new PostingDto(bob.value(), EntryType.CREDIT, new MoneyDto(499, "USD"))),
                "bad", Map.of(), "e2e-unbalanced");

        ResponseEntity<ErrorResponse> response = post("/transactions", tenant, request, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error()).containsIgnoringCase("unbalanced");
    }


    @Test
    void retryingTheSameIdempotencyKeyReturnsTheSameTransactionId() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        AccountId alice = openAccountViaHttp(tenant, "Alice");
        AccountId bob = openAccountViaHttp(tenant, "Bob");
        seedBalance(tenant, alice, 10_000);

        PostTransactionRequest request = new PostTransactionRequest(
                List.of(
                        new PostingDto(alice.value(), EntryType.DEBIT, new MoneyDto(500, "USD")),
                        new PostingDto(bob.value(), EntryType.CREDIT, new MoneyDto(500, "USD"))),
                "rent", Map.of(), "e2e-retry-key");

        TransactionResponse first = post("/transactions", tenant, request, TransactionResponse.class).getBody();
        TransactionResponse second = post("/transactions", tenant, request, TransactionResponse.class).getBody();

        assertThat(second.transactionId()).isEqualTo(first.transactionId());
        assertThat(second.wasIdempotentReplay()).isTrue();
        assertThat(get("/accounts/" + alice, tenant, AccountResponse.class).getBody().balance().minorUnits())
                .isEqualTo(9_500);
    }


    @Test
    void exceedingTheVelocityLimitReturns403WithFraudFlagsInTheBody() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        AccountId alice = openAccountViaHttp(tenant, "Alice");
        AccountId bob = openAccountViaHttp(tenant, "Bob");
        seedBalance(tenant, alice, 100_000);

        for (int i = 0; i < 19; i++) {
            PostTransactionRequest ok = new PostTransactionRequest(
                    List.of(
                            new PostingDto(alice.value(), EntryType.DEBIT, new MoneyDto(10, "USD")),
                            new PostingDto(bob.value(), EntryType.CREDIT, new MoneyDto(10, "USD"))),
                    "installment", Map.of(), "e2e-velocity-" + i);
            ResponseEntity<TransactionResponse> ok200 = post("/transactions", tenant, ok, TransactionResponse.class);
            assertThat(ok200.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        PostTransactionRequest blocked = new PostTransactionRequest(
                List.of(
                        new PostingDto(alice.value(), EntryType.DEBIT, new MoneyDto(10, "USD")),
                        new PostingDto(bob.value(), EntryType.CREDIT, new MoneyDto(10, "USD"))),
                "one too many", Map.of(), "e2e-velocity-blocked");

        ResponseEntity<ErrorResponse> response = post("/transactions", tenant, blocked, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().fraudFlags()).isNotNull();
        assertThat(response.getBody().fraudFlags()).anyMatch(f -> f.ruleName().equals("velocity") && f.isBlock());
    }


    @Test
    void missingTenantHeaderIsRejectedWith400() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/accounts/" + UUID.randomUUID(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void malformedTenantHeaderIsRejectedWith400() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TenantResolvingFilter.HEADER_NAME, "not-a-uuid");
        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + "/accounts/" + UUID.randomUUID(), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void gettingAnUnknownAccountReturns404() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        ResponseEntity<ErrorResponse> response = get("/accounts/" + UUID.randomUUID(), tenant, ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void postingAgainstAFrozenAccountReturns409() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        AccountId alice = openAccountViaHttp(tenant, "Alice");
        AccountId bob = openAccountViaHttp(tenant, "Bob");
        seedBalance(tenant, alice, 10_000);

        ResponseEntity<AccountResponse> frozen = post("/accounts/" + bob + "/freeze", tenant,
                new ReasonRequest("suspicious"), AccountResponse.class);
        assertThat(frozen.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(frozen.getBody().status()).isEqualTo("FROZEN");

        PostTransactionRequest request = new PostTransactionRequest(
                List.of(
                        new PostingDto(alice.value(), EntryType.DEBIT, new MoneyDto(500, "USD")),
                        new PostingDto(bob.value(), EntryType.CREDIT, new MoneyDto(500, "USD"))),
                "bad", Map.of(), "e2e-frozen");

        ResponseEntity<ErrorResponse> response = post("/transactions", tenant, request, ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void postingAgainstAnUnknownAccountReturns404() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        AccountId alice = openAccountViaHttp(tenant, "Alice");
        seedBalance(tenant, alice, 10_000);
        AccountId ghost = AccountId.newId();

        PostTransactionRequest request = new PostTransactionRequest(
                List.of(
                        new PostingDto(alice.value(), EntryType.DEBIT, new MoneyDto(500, "USD")),
                        new PostingDto(ghost.value(), EntryType.CREDIT, new MoneyDto(500, "USD"))),
                "bad", Map.of(), "e2e-ghost");

        ResponseEntity<ErrorResponse> response = post("/transactions", tenant, request, ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void overdrawingAnAccountReturns422() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        AccountId alice = openAccountViaHttp(tenant, "Alice");
        AccountId bob = openAccountViaHttp(tenant, "Bob");
        seedBalance(tenant, alice, 100);

        PostTransactionRequest request = new PostTransactionRequest(
                List.of(
                        new PostingDto(alice.value(), EntryType.DEBIT, new MoneyDto(999_999, "USD")),
                        new PostingDto(bob.value(), EntryType.CREDIT, new MoneyDto(999_999, "USD"))),
                "too much", Map.of(), "e2e-overdraw");

        ResponseEntity<ErrorResponse> response = post("/transactions", tenant, request, ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void closingThenReopeningLifecycleWorksThroughRest() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        AccountId alice = openAccountViaHttp(tenant, "Alice");

        ResponseEntity<AccountResponse> closed = post("/accounts/" + alice + "/close", tenant,
                new ReasonRequest("holder request"), AccountResponse.class);
        assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(closed.getBody().status()).isEqualTo("CLOSED");

        ResponseEntity<ErrorResponse> reclose = post("/accounts/" + alice + "/close", tenant,
                new ReasonRequest("again"), ErrorResponse.class);
        assertThat(reclose.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void auditTrailIncludesLifecycleAndPostingsInChronologicalOrder() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        AccountId alice = openAccountViaHttp(tenant, "Alice");
        AccountId bob = openAccountViaHttp(tenant, "Bob");
        seedBalance(tenant, alice, 10_000);

        post("/transactions", tenant, new PostTransactionRequest(
                List.of(
                        new PostingDto(alice.value(), EntryType.DEBIT, new MoneyDto(200, "USD")),
                        new PostingDto(bob.value(), EntryType.CREDIT, new MoneyDto(200, "USD"))),
                "audit-test", Map.of(), "e2e-audit-1"), TransactionResponse.class);

        ResponseEntity<Object[]> response = get("/audit/accounts/" + alice, tenant, Object[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
    }
}
