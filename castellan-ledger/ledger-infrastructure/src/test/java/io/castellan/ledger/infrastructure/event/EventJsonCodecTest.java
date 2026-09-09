package io.castellan.ledger.infrastructure.event;

import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.EntryType;
import io.castellan.ledger.domain.IdempotencyKey;
import io.castellan.ledger.domain.Money;
import io.castellan.ledger.domain.Posting;
import io.castellan.ledger.domain.SagaId;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.TransactionId;
import io.castellan.ledger.domain.events.AccountOpened;
import io.castellan.ledger.domain.events.DomainEvent;
import io.castellan.ledger.domain.events.FraudFlagRaised;
import io.castellan.ledger.domain.events.TransactionPosted;
import io.castellan.ledger.domain.events.TransferSagaStarted;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventJsonCodecTest {

    private final EventJsonCodec codec = new EventJsonCodec();
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final Instant now = Instant.parse("2026-03-15T12:34:56.789Z");

    @Test
    void roundTripsAnAccountOpenedEventExactly() {
        AccountOpened event = new AccountOpened(
                AccountId.newId(), tenant, Currency.getInstance("EUR"), "Ada Lovelace", now);

        String json = codec.serialize(event);
        DomainEvent restored = codec.deserialize(EventTypeRegistry.tagFor(event), json);

        assertThat(restored).isEqualTo(event);
    }

    @Test
    void roundTripsATransactionPostedEventWithMultiplePostingsAndMetadataExactly() {
        TransactionPosted event = new TransactionPosted(
                TransactionId.newId(), tenant,
                List.of(
                        new Posting(AccountId.newId(), EntryType.DEBIT, new Money(12_345, Currency.getInstance("USD"))),
                        new Posting(AccountId.newId(), EntryType.CREDIT, new Money(12_345, Currency.getInstance("USD")))
                ),
                "rent payment",
                Map.of("channel", "mobile", "region", "eu-west-1"),
                new IdempotencyKey("idem-1"),
                now);

        String json = codec.serialize(event);
        DomainEvent restored = codec.deserialize("TransactionPosted", json);

        assertThat(restored).isEqualTo(event);
        assertThat(((TransactionPosted) restored).postings()).hasSize(2);
        assertThat(((TransactionPosted) restored).metadata()).containsEntry("channel", "mobile");
    }

    @Test
    void roundTripsATransferSagaStartedEventWithMoneyAndSagaId() {
        TransferSagaStarted event = new TransferSagaStarted(
                SagaId.newId(), tenant, AccountId.newId(), AccountId.newId(),
                new Money(500, Currency.getInstance("GBP")), new IdempotencyKey("transfer-1"), now);

        DomainEvent restored = codec.deserialize("TransferSagaStarted", codec.serialize(event));

        assertThat(restored).isEqualTo(event);
    }

    @Test
    void roundTripsAFraudFlagRaisedEventPreservingTheSeverityEnum() {
        FraudFlagRaised event = new FraudFlagRaised(
                TransactionId.newId(), tenant, "velocity", "too many recent transactions",
                FraudFlagRaised.Severity.BLOCK, now);

        DomainEvent restored = codec.deserialize("FraudFlagRaised", codec.serialize(event));

        assertThat(restored).isEqualTo(event);
        assertThat(((FraudFlagRaised) restored).severity()).isEqualTo(FraudFlagRaised.Severity.BLOCK);
    }

    @Test
    void currencyIsSerializedAsAPlainThreeLetterCodeStringNotAJacksonDefaultObject() {
        AccountOpened event = new AccountOpened(
                AccountId.newId(), tenant, Currency.getInstance("JPY"), "Grace Hopper", now);

        String json = codec.serialize(event);

        assertThat(json).contains("\"currency\":\"JPY\"");
        assertThat(json).doesNotContain("\"numericCode\"").doesNotContain("\"defaultFractionDigits\"");
    }

    @Test
    void occurredAtIsSerializedAsAnIsoTimestampNotAsARawEpochNumber() {
        AccountOpened event = new AccountOpened(AccountId.newId(), tenant, Currency.getInstance("USD"), "X", now);

        String json = codec.serialize(event);

        assertThat(json).contains("\"occurredAt\":\"2026-03-15T12:34:56.789Z\"");
    }

    @Test
    void deserializingWithAnUnknownTagFailsClosedRatherThanGuessing() {
        assertThatThrownBy(() -> codec.deserialize("NotARealEventType", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deserializingMalformedJsonThrowsEventSerializationException() {
        assertThatThrownBy(() -> codec.deserialize("AccountOpened", "{not valid json"))
                .isInstanceOf(EventJsonCodec.EventSerializationException.class);
    }

    @Test
    void extraUnknownJsonFieldsAreIgnoredRatherThanFailingDeserialization() {
        AccountOpened event = new AccountOpened(AccountId.newId(), tenant, Currency.getInstance("USD"), "X", now);
        String json = codec.serialize(event);
        String withExtraField = json.substring(0, json.length() - 1) + ",\"somethingNew\":\"future-field\"}";

        DomainEvent restored = codec.deserialize("AccountOpened", withExtraField);

        assertThat(restored).isEqualTo(event);
    }
}
