package com.cobre.notification.adapter.out.webhook;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;

@Configuration(proxyBeanMethods = false)
class WebhookOutboundConfiguration {

    @Bean
    WebhookDestinationGuard.NameResolver webhookNameResolver() {
        return InetAddress::getAllByName;
    }
}
