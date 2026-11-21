package com.peakload.admission;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AdmissionProperties.class, RateLimitProperties.class})
public class AdmissionApplication {
    public static void main(String[] args) {
        SpringApplication.run(AdmissionApplication.class, args);
    }
}
