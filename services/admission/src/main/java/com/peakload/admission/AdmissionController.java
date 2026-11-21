package com.peakload.admission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * The front door. One catch-all handler: it looks the request up in {@link RouteTable}, then either proxies
 * it to the order service or answers it locally. Unknown routes get a 404.
 */
@RestController
class AdmissionController {

    private static final String BROWSE_PREFIX = "/browse/restaurants/";

    private final ProxyService proxy;
    private final MenuService menus;
    private final AdmissionMetrics metrics;
    private final ObjectMapper mapper;
    private final RateLimiter limiter;

    AdmissionController(ProxyService proxy, MenuService menus, AdmissionMetrics metrics, ObjectMapper mapper,
                        RateLimiter limiter) {
        this.proxy = proxy;
        this.menus = menus;
        this.metrics = metrics;
        this.mapper = mapper;
        this.limiter = limiter;
    }

    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PATCH})
    ResponseEntity<byte[]> handle(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        String path = request.getRequestURI();

        Optional<RouteTable.Route> match = RouteTable.match(method, path);
        if (match.isEmpty()) {
            metrics.requestFinished("none", "not_found");
            return error(HttpStatus.NOT_FOUND, "route_not_found", "no route for " + method + " " + path);
        }

        RouteTable.Route route = match.get();
        String requestClass = route.requestClass().name();
        metrics.enter(route.requestClass());
        try {
            // Runs after routing and classification, before any upstream call or menu read.
            RateLimiter.Decision decision = limiter.check(clientId(request), route.requestClass());
            if (!decision.allowed()) {
                metrics.rateLimited(route.requestClass());
                metrics.requestFinished(requestClass, "rate_limited");
                return rateLimited(decision.retryAfterMillis());
            }
            if (route.target() == RouteTable.Target.LOCAL_MENU) {
                return browse(path, requestClass);
            }
            return forward(request, method, body, route.requestClass(), requestClass);
        } finally {
            metrics.exit(route.requestClass());
        }
    }

    /** The X-Client-Id header if present, otherwise the caller's address. X-Forwarded-For is not trusted. */
    private static String clientId(HttpServletRequest request) {
        String header = request.getHeader("X-Client-Id");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        return "ip:" + request.getRemoteAddr();
    }

    /** 429 with Retry-After in whole seconds, rounded up, never less than 1. */
    private ResponseEntity<byte[]> rateLimited(long retryAfterMillis) {
        long seconds = Math.max(1, (retryAfterMillis + 999) / 1000);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(seconds))
                .contentType(MediaType.APPLICATION_JSON)
                .body(bytes(Map.of("error", "rate_limited",
                        "message", "too many requests; retry after " + seconds + " s")));
    }

    private ResponseEntity<byte[]> forward(HttpServletRequest request, HttpMethod method, byte[] body,
                                           RequestClass requestClass, String label) {
        String pathAndQuery = request.getRequestURI()
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        HttpHeaders inbound = new ServletServerHttpRequest(request).getHeaders();
        try {
            ProxyService.Answer answer = proxy.forward(requestClass, method, pathAndQuery, inbound, body);
            metrics.requestFinished(label, outcome(answer.status()));
            return ResponseEntity.status(answer.status()).headers(answer.headers()).body(answer.body());
        } catch (UpstreamException e) {
            metrics.requestFinished(label, "upstream_error");
            return error(e.status(), e.type().name().toLowerCase(), e.getMessage());
        }
    }

    private ResponseEntity<byte[]> browse(String path, String label) {
        String id = path.substring(BROWSE_PREFIX.length(), path.length() - "/menu".length());
        UUID restaurantId;
        try {
            restaurantId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            metrics.requestFinished(label, "client_error");
            return error(HttpStatus.BAD_REQUEST, "invalid_restaurant_id", "restaurant id must be a UUID");
        }
        Optional<Map<String, Object>> menu = menus.menu(restaurantId);
        if (menu.isEmpty()) {
            metrics.requestFinished(label, "not_found");
            return error(HttpStatus.NOT_FOUND, "restaurant_not_found", "restaurant " + restaurantId + " does not exist");
        }
        metrics.requestFinished(label, "success");
        return json(HttpStatus.OK, menu.get());
    }

    private static String outcome(int status) {
        if (status >= 500) {
            return "server_error";
        }
        if (status >= 400) {
            return "client_error";
        }
        return "success";
    }

    private ResponseEntity<byte[]> error(HttpStatus status, String code, String message) {
        return json(status, Map.of("error", code, "message", message));
    }

    private ResponseEntity<byte[]> json(HttpStatus status, Object body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(bytes(body));
    }

    private byte[] bytes(Object body) {
        try {
            return mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
