package com.peakload.dispatch;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class KafkaConfig {

    /** Created at startup if missing. The order-events topic is created by the order service. */
    @Bean
    NewTopic dispatchEventsTopic() {
        return TopicBuilder.name(EventNames.DISPATCH_EVENTS).partitions(3).replicas(1).build();
    }

    /**
     * Retry a failed record every second, with no limit. The only failures that reach here are database or
     * Redis failures, so the record is retried until the dependency is back. Skipping it would lose an order.
     * Malformed records never reach here: the listener logs and skips them.
     */
    @Bean
    CommonErrorHandler orderEventsErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
