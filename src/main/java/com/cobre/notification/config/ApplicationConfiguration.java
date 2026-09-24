package com.cobre.notification.config;

import com.cobre.notification.application.port.out.AuditLog;
import com.cobre.notification.application.port.out.DeliveryRepository;
import com.cobre.notification.application.port.out.IdGenerator;
import com.cobre.notification.application.port.out.NotificationEventQueryRepository;
import com.cobre.notification.application.port.out.NotificationEventRepository;
import com.cobre.notification.application.port.out.SubscriptionRepository;
import com.cobre.notification.application.port.out.WebhookClient;
import com.cobre.notification.application.service.DeliverNotificationService;
import com.cobre.notification.application.service.DeliveryAttemptProcessor;
import com.cobre.notification.application.service.IngestPlatformEventService;
import com.cobre.notification.application.service.NotificationQueryService;
import com.cobre.notification.application.service.RecoverExpiredLeasesService;
import com.cobre.notification.application.service.ReplayNotificationEventService;
import com.cobre.notification.domain.policy.DeliveryLifecycle;
import com.cobre.notification.domain.policy.DeliveryResultClassifier;
import com.cobre.notification.domain.policy.ExponentialBackoffRetryPolicy;
import com.cobre.notification.domain.policy.HttpStatusDeliveryResultClassifier;
import com.cobre.notification.domain.policy.RetryPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.random.RandomGenerator;

/**
 * Wires the framework-free application services.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({DeliveryProperties.class, WebhookProperties.class})
class ApplicationConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    IdGenerator idGenerator() {
        return UUID::randomUUID;
    }

    /**
     * {@link Random} is thread-safe, which matters because backoff is computed on concurrent delivery threads.
     */
    @Bean
    RandomGenerator retryJitterRandom() {
        return new Random();
    }

    @Bean
    IngestPlatformEventService ingestPlatformEventService(SubscriptionRepository subscriptions,
                                                          NotificationEventRepository notifications,
                                                          IdGenerator ids,
                                                          Clock clock) {
        return new IngestPlatformEventService(subscriptions, notifications, ids, clock);
    }

    @Bean
    NotificationQueryService notificationQueryService(NotificationEventQueryRepository queries, AuditLog auditLog) {
        return new NotificationQueryService(queries, auditLog);
    }

    @Bean
    ReplayNotificationEventService replayNotificationEventService(NotificationEventQueryRepository queries,
                                                                  NotificationEventRepository notifications,
                                                                  SubscriptionRepository subscriptions,
                                                                  AuditLog auditLog,
                                                                  Clock clock) {
        return new ReplayNotificationEventService(queries, notifications, subscriptions, auditLog, clock);
    }

    @Bean
    DeliveryResultClassifier deliveryResultClassifier() {
        return new HttpStatusDeliveryResultClassifier();
    }

    @Bean
    RetryPolicy retryPolicy(DeliveryProperties properties, RandomGenerator random) {
        DeliveryProperties.Retry retry = properties.retry();
        return new ExponentialBackoffRetryPolicy(retry.baseDelay(), retry.maxDelay(), retry.maxAttempts(), random);
    }

    @Bean
    DeliveryLifecycle deliveryLifecycle(DeliveryResultClassifier classifier, RetryPolicy retryPolicy) {
        return new DeliveryLifecycle(classifier, retryPolicy);
    }

    @Bean
    WorkerIdentity workerIdentity(DeliveryProperties properties) {
        String configured = properties.worker().id();
        return new WorkerIdentity(configured == null || configured.isBlank() ? defaultWorkerId() : configured);
    }

    @Bean
    DeliveryExecutor deliveryExecutor() {
        return new DeliveryExecutor(
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("delivery-", 0).factory()));
    }

    @Bean
    DeliveryAttemptProcessor deliveryAttemptProcessor(DeliveryRepository deliveries,
                                                      WebhookClient webhookClient,
                                                      DeliveryLifecycle lifecycle,
                                                      Clock clock,
                                                      WorkerIdentity worker) {
        return new DeliveryAttemptProcessor(deliveries, webhookClient, lifecycle, clock, System::nanoTime,
                worker.id());
    }

    @Bean
    DeliverNotificationService deliverNotificationService(DeliveryRepository deliveries,
                                                          DeliveryAttemptProcessor processor,
                                                          DeliveryExecutor deliveryExecutor,
                                                          IdGenerator ids,
                                                          Clock clock,
                                                          DeliveryProperties properties,
                                                          WorkerIdentity worker) {
        return new DeliverNotificationService(deliveries, processor, deliveryExecutor.executor(),
                properties.worker().maxConcurrency(), ids, clock, worker.id(), properties.leaseDuration());
    }

    @Bean
    RecoverExpiredLeasesService recoverExpiredLeasesService(DeliveryRepository deliveries,
                                                            DeliveryLifecycle lifecycle,
                                                            Clock clock,
                                                            DeliveryProperties properties) {
        return new RecoverExpiredLeasesService(deliveries, lifecycle, clock, properties.worker().recoveryBatchSize());
    }

    /**
     * Unique per process, so a restarted instance never inherits the leases of its previous run.
     */
    private static String defaultWorkerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown-host";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    record WorkerIdentity(String id) {
    }

    /**
     * One virtual thread per delivery; concurrency is bounded by the semaphore in {@link DeliverNotificationService}.
     * Not exposed as an {@code Executor} bean so Spring Boot's own task executor is still auto-configured. Closing
     * waits for in-flight deliveries, each bounded by the webhook timeouts.
     */
    record DeliveryExecutor(ExecutorService executor) implements AutoCloseable {

        @Override
        public void close() {
            executor.close();
        }
    }
}
