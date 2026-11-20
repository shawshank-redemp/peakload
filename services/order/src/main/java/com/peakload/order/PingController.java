package com.peakload.order;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Week 0 placeholder endpoint so each service has something to load-test and scrape. */
@RestController
class PingController {

    @GetMapping("/api/ping")
    Map<String, String> ping() {
        return Map.of("service", "order", "status", "ok");
    }
}
