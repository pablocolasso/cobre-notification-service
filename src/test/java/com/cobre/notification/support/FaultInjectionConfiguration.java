package com.cobre.notification.support;

import com.cobre.notification.application.service.IngestPlatformEventService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class FaultInjectionConfiguration {

    @Bean
    @Primary
    FaultInjectingIngestion faultInjectingIngestion(IngestPlatformEventService ingestPlatformEventService) {
        return new FaultInjectingIngestion(ingestPlatformEventService);
    }
}
