package com.peakload.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Hashes an order body so that a reused idempotency key can be checked against the first request.
 *
 * <p>The hash covers a canonical form: only the known fields, with keys in sorted order and no
 * whitespace. Two bodies that mean the same thing therefore get the same hash, even if the client
 * sends the fields in a different order. Unknown fields are ignored and not hashed.
 */
@Component
class RequestHasher {

    private final ObjectMapper mapper;

    RequestHasher(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** The canonical JSON for a validated order, e.g. {"city":"Pune","customerId":"c1","totalPaise":1200}. */
    String canonicalJson(OrderInput input) {
        // TreeMap writes its keys in sorted order, which makes the output stable.
        Map<String, Object> fields = new TreeMap<>();
        fields.put("city", input.city());
        fields.put("customerId", input.customerId());
        fields.put("totalPaise", input.totalPaise());
        try {
            return mapper.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            // Cannot happen for a map of strings and longs.
            throw new IllegalStateException(e);
        }
    }

    /** SHA-256 of the canonical JSON, as lowercase hex. */
    String hash(OrderInput input) {
        return sha256Hex(canonicalJson(input));
    }

    static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime has SHA-256.
            throw new IllegalStateException(e);
        }
    }
}
