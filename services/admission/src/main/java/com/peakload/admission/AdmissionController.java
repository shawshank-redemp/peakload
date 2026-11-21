package com.peakload.admission;

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

    AdmissionController(ProxyService proxy, MenuService menus, AdmissionMetrics metrics) {
        this.proxy = proxy;
        this.menus = menus;
        this.metrics = metrics;
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
            if (route.target() == RouteTable.Target.LOCAL_MENU) {
                return browse(path, requestClass);
            }
            return forward(request, method, body, route.requestClass(), requestClass);
        } finally {
            metrics.exit(route.requestClass());
        }
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

    private static ResponseEntity<byte[]> error(HttpStatus status, String code, String message) {
        return json(status, Map.of("error", code, "message", message));
    }

    private static ResponseEntity<byte[]> json(HttpStatus status, Object body) {
        try {
            byte[] bytes = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(body);
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(bytes);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
