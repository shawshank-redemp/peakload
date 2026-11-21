package com.peakload.admission;

import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The HTTP client used to call the order service. One shared, bounded connection pool.
 *
 * <p>Timeouts: connect and read are separate. A read timeout means the upstream accepted the request but did
 * not answer (504). A connect timeout means it could not be reached (503). A pool wait timeout means admission
 * itself is at its limit (503).
 *
 * <p>No retries. A retry of a POST is only safe with idempotency keys, and that decision belongs to a later step.
 */
@Configuration
class UpstreamConfig {

    @Bean
    PoolingHttpClientConnectionManager upstreamConnectionManager(AdmissionProperties properties) {
        AdmissionProperties.Upstream upstream = properties.upstream();
        ConnectionConfig connection = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(upstream.connectTimeout().toMillis(), TimeUnit.MILLISECONDS))
                .setSocketTimeout(Timeout.of(upstream.readTimeout().toMillis(), TimeUnit.MILLISECONDS))
                .build();
        return PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(connection)
                .setMaxConnTotal(upstream.maxConnections())
                .setMaxConnPerRoute(upstream.maxConnections())
                .build();
    }

    @Bean
    RestClient upstreamRestClient(PoolingHttpClientConnectionManager connections, AdmissionProperties properties) {
        AdmissionProperties.Upstream upstream = properties.upstream();
        RequestConfig requests = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(upstream.connectionRequestTimeout().toMillis(), TimeUnit.MILLISECONDS))
                .build();
        var httpClient = HttpClients.custom()
                .setConnectionManager(connections)
                .setDefaultRequestConfig(requests)
                .disableAutomaticRetries()
                .build();
        return RestClient.builder()
                .baseUrl(upstream.orderUrl())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .build();
    }
}
