package com.peakload.admission;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;

/**
 * The one place that maps an incoming request to a request class and a target. Every route, its class and
 * its target is listed here. Anything not listed is unknown and gets a 404.
 */
final class RouteTable {

    /** Where a route is served. */
    enum Target {
        /** Proxied to the order service. */
        ORDER_SERVICE,
        /** Answered by admission from its own Postgres schema. */
        LOCAL_MENU
    }

    /** One route. The pattern matches the request path only, not the query string. */
    record Route(HttpMethod method, Pattern path, RequestClass requestClass, Target target) {}

    private static final List<Route> ROUTES = List.of(
            new Route(HttpMethod.POST, Pattern.compile("^/orders$"), RequestClass.CRITICAL, Target.ORDER_SERVICE),
            new Route(HttpMethod.GET, Pattern.compile("^/orders/[^/]+$"), RequestClass.STANDARD, Target.ORDER_SERVICE),
            new Route(HttpMethod.PATCH, Pattern.compile("^/orders/[^/]+/status$"), RequestClass.STANDARD, Target.ORDER_SERVICE),
            new Route(HttpMethod.GET, Pattern.compile("^/browse/restaurants/[^/]+/menu$"), RequestClass.LOW, Target.LOCAL_MENU));

    private RouteTable() {}

    /** The route for this method and path, or empty if none matches. */
    static Optional<Route> match(HttpMethod method, String path) {
        return ROUTES.stream()
                .filter(route -> route.method() == method && route.path().matcher(path).matches())
                .findFirst();
    }

    static List<Route> all() {
        return ROUTES;
    }
}
