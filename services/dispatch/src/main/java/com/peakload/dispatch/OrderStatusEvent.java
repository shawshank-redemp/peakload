package com.peakload.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.UUID;

/** The fields of an OrderStatusChanged event that dispatch needs: which order, and its new status. */
record OrderStatusEvent(UUID orderId, String status) {

    /** @throws IllegalArgumentException if the payload is not JSON or a required field is missing */
    static OrderStatusEvent parse(ObjectMapper mapper, String json) {
        try {
            JsonNode node = mapper.readTree(json);
            return new OrderStatusEvent(
                    UUID.fromString(required(node, "orderId").asText()),
                    required(node, "status").asText());
        } catch (IOException e) {
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
