package com.peakload.dispatch;

/** The JSON body of POST /riders. Checked by {@link #validated()}. */
record CreateRiderRequest(String name, String city) {

    private static final int MAX_LENGTH = 255;

    /** @throws ApiException 400 if name or city is missing, blank or too long */
    CreateRiderRequest validated() {
        return new CreateRiderRequest(requireText("name", name), requireText("city", city));
    }

    private static String requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("invalid_" + field, field + " must not be empty");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw ApiException.badRequest("invalid_" + field, field + " is longer than " + MAX_LENGTH);
        }
        return trimmed;
    }
}
