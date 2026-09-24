package com.cobre.notification.config;

import com.cobre.notification.adapter.in.kafka.DeadLetterMetrics;
import com.cobre.notification.adapter.in.kafka.DeadLetterReason;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.ExponentialBackOff;

import java.nio.charset.StandardCharsets;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaRetryProperties.class)
class KafkaConfiguration {

    static final String REASON_HEADER = "x-cobre-dlt-reason";
    static final String DETAIL_HEADER = "x-cobre-dlt-detail";

    private static final Logger log = LoggerFactory.getLogger(KafkaConfiguration.class);

    /**
     * Only infrastructure failures (database unreachable, transaction cannot start) are retried, without limit and
     * without committing the offset. Everything else, invalid events and unexpected errors alike, goes to the DLT
     * at once so a poison message never stalls the partition. Exception messages and stack traces are not copied to
     * the DLT headers because they may quote the payload.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<String, String> kafkaTemplate,
                                          KafkaRetryProperties retry,
                                          DeadLetterMetrics metrics,
                                          @Value("${app.kafka.topics.platform-events-dlt}") String deadLetterTopic) {
        var deadLetterPublisher = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, failure) -> new TopicPartition(deadLetterTopic, -1));
        deadLetterPublisher.excludeHeader(HeadersToAdd.EX_MSG, HeadersToAdd.EX_STACKTRACE);
        deadLetterPublisher.setHeadersFunction((record, failure) -> {
            var headers = new RecordHeaders();
            headers.add(REASON_HEADER, DeadLetterReason.of(failure).tag().getBytes(StandardCharsets.UTF_8));
            headers.add(DETAIL_HEADER, DeadLetterReason.detail(failure).getBytes(StandardCharsets.UTF_8));
            return headers;
        });

        ConsumerRecordRecoverer recoverer = (record, failure) -> {
            deadLetterPublisher.accept(record, failure);
            DeadLetterReason reason = DeadLetterReason.of(failure);
            metrics.published(reason);
            log.atWarn()
                    .setMessage("Platform event sent to dead-letter topic")
                    .addKeyValue("topic", record.topic())
                    .addKeyValue("partition", record.partition())
                    .addKeyValue("offset", record.offset())
                    .addKeyValue("reason", reason.tag())
                    .addKeyValue("detail", DeadLetterReason.detail(failure))
                    .log();
        };

        var backOff = new ExponentialBackOff(retry.initialInterval().toMillis(), retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());

        var errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.defaultFalse();
        errorHandler.addRetryableExceptions(
                TransientDataAccessException.class,
                DataAccessResourceFailureException.class,
                RecoverableDataAccessException.class,
                CannotCreateTransactionException.class);
        return errorHandler;
    }
}
