package com.peakload.admission;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for admission integration tests: a Postgres 16 container (for the menu) on a random port. The order
 * service is a stub, configured by each subclass.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractAdmissionIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    static {
        POSTGRES.start();
    }

    /** Seeded restaurant from V2__seed_sample_menu.sql. */
    static final String SPICE_ROUTE = "11111111-1111-4111-8111-111111111111";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    protected int port;

    private static final HttpClient HTTP = HttpClient.newBuilder().build();

    /** Sends a request to admission and returns the raw response (status, headers, body). */
    protected HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HTTP.send(request.build(), BodyHandlers.ofString());
    }

    /** A request to admission. Body may be null for requests without one. */
    protected HttpRequest.Builder request(String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
    }
}
