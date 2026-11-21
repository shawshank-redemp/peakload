package com.peakload.order;

/**
 * A validated, trimmed order body. Only this type is hashed and stored, so the same order always
 * produces the same canonical form.
 */
record OrderInput(String customerId, String city, long totalPaise) {

    private static final int MAX_TEXT_LENGTH = 255;

    /**
     * @throws ApiException 400 if a field is missing, blank, too long, or totalPaise is not positive
     */
    static OrderInput validated(CreateOrderRequest body) {
        if (body == null) {
            throw ApiException.badRequest("missing_body", "request body is required");
        }
        String customerId = requireText("customerId", body.customerId());
        String city = requireText("city", body.city());
        if (body.totalPaise() == null || body.totalPaise() <= 0) {
            throw ApiException.badRequest("invalid_total", "totalPaise must be greater than 0");
        }
        return new OrderInput(customerId, city, body.totalPaise());
    }

    private static String requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("invalid_" + field, field + " must not be empty");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_TEXT_LENGTH) {
            throw ApiException.badRequest("invalid_" + field, field + " is longer than " + MAX_TEXT_LENGTH);
        }
        return trimmed;
    }
}
