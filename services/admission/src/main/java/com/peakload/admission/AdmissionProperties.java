package com.peakload.admission;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings bound from {@code peakload.admission} in application.yml. */
@ConfigurationProperties("peakload.admission")
public record AdmissionProperties(Upstream upstream) {

    /**
     * @param orderUrl                base URL of the order service
     * @param connectTimeout          longest wait to open a connection
     * @param readTimeout             longest wait for the upstream's response
     * @param connectionRequestTimeout longest wait for a free pooled connection
     * @param maxConnections          upper bound on simultaneous upstream connections
     */
    public record Upstream(String orderUrl, Duration connectTimeout, Duration readTimeout,
                           Duration connectionRequestTimeout, int maxConnections) {}
}
