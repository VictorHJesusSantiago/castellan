package io.castellan.ledger.infrastructure.event;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.castellan.ledger.domain.events.DomainEvent;

import java.io.IOException;
import java.util.Currency;

/**
 * (De)serializes a {@link DomainEvent} to/from the exact JSON text stored in the {@code events}
 * and {@code outbox} tables' {@code payload_json} columns. Relies on every event and value type
 * in {@code ledger-domain} being a plain record with a canonical constructor -- Jackson maps JSON
 * fields to record components by name using the parameter names retained in bytecode (the root
 * POM compiles with {@code -parameters}), so no {@code @JsonCreator}/{@code @JsonProperty}
 * annotations are needed anywhere in a module this class is expressly forbidden from touching.
 *
 * <p>{@link Currency} gets an explicit, hand-written (de)serializer rather than relying on
 * whatever Jackson's JDK-type support happens to do for it in a given version: always exactly the
 * three-letter currency code as a JSON string, both ways, so the wire format is stable and
 * intentional rather than an implementation detail of the Jackson version in use.
 */
public final class EventJsonCodec {

    private final ObjectMapper mapper;

    public EventJsonCodec() {
        this.mapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        SimpleModule currencyModule = new SimpleModule("castellan-currency");
        currencyModule.addSerializer(Currency.class, new JsonSerializer<Currency>() {
            @Override
            public void serialize(Currency value, com.fasterxml.jackson.core.JsonGenerator gen,
                    com.fasterxml.jackson.databind.SerializerProvider serializers) throws IOException {
                gen.writeString(value.getCurrencyCode());
            }
        });
        currencyModule.addDeserializer(Currency.class, new JsonDeserializer<Currency>() {
            @Override
            public Currency deserialize(com.fasterxml.jackson.core.JsonParser p,
                    com.fasterxml.jackson.databind.DeserializationContext ctxt) throws IOException {
                return Currency.getInstance(p.getValueAsString());
            }
        });
        this.mapper.registerModule(currencyModule);
    }

    public String serialize(DomainEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new EventSerializationException("failed to serialize " + event.getClass().getSimpleName(), e);
        }
    }

    public DomainEvent deserialize(String typeTag, String payloadJson) {
        Class<? extends DomainEvent> type = EventTypeRegistry.classForTag(typeTag);
        try {
            return mapper.readValue(payloadJson, type);
        } catch (IOException e) {
            throw new EventSerializationException("failed to deserialize " + typeTag, e);
        }
    }

    public static final class EventSerializationException extends RuntimeException {
        public EventSerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
