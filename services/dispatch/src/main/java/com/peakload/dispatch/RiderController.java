package com.peakload.dispatch;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Rider endpoints: register, read, and location pings. */
@RestController
class RiderController {

    private final RiderService riders;

    RiderController(RiderService riders) {
        this.riders = riders;
    }

    @PostMapping("/riders")
    ResponseEntity<Rider> register(@RequestBody(required = false) CreateRiderRequest body) {
        if (body == null) {
            throw ApiException.badRequest("missing_body", "request body is required");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(riders.register(body));
    }

    @GetMapping("/riders/{id}")
    Rider get(@PathVariable UUID id) {
        return riders.find(id);
    }

    @PostMapping("/riders/{id}/location")
    ResponseEntity<Void> ping(@PathVariable UUID id, @RequestBody(required = false) LocationPing body) {
        if (body == null) {
            throw ApiException.badRequest("missing_body", "request body with lat and lon is required");
        }
        riders.recordLocation(id, body);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> apiError(ApiException e) {
        return ResponseEntity.status(e.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", e.code(), "message", e.getMessage()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<Map<String, String>> missingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "missing_header", "message", e.getHeaderName() + " header is required"));
    }
}
