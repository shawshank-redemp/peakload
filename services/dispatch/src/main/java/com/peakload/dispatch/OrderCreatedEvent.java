package com.peakload.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;

/** The fields of an OrderCreated event that dispatch needs. Parsed from the outbox payload. */
record OrderCreatedEvent(UUID orderId, String city, double pickupLat, double pickupLon) {

    /** @throws IllegalArgumentException if the payload is not JSON or a required field is missing */
    static OrderCreatedEvent parse(ObjectMapper mapper, String json) {
        try {
            JsonNode node = mapper.readTree(json);
            return new OrderCreatedEvent(
                    UUID.fromString(required(node, "orderId").asText()),
                    required(node, "city").asText(),
                    required(node, "pickupLat").asDouble(),
                    required(node, "pickupLon").asDouble());
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("payload is not valid JSON", e);
        }
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("payload has no " + field);
        }
        return value;
    }
}
