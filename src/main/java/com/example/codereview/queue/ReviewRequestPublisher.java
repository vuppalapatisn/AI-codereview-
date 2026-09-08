package com.example.codereview.queue;

import com.example.codereview.model.ReviewModels.ReviewRequestedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes review requests onto a durable, partitioned Kafka topic.
 * Partition key = "owner/repo" so all events for one repository land on
 * the same partition and are processed in order per-repo, while different
 * repos parallelize freely across partitions.
 */
@Slf4j
@Component
public class ReviewRequestPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Value("${review.queue.topic}")
    private String topic;

    public ReviewRequestPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                   ObjectMapper objectMapper,
                                   MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public void publish(ReviewRequestedEvent event) {
        try {
            String key = event.getRepoOwner() + "/" + event.getRepoName();
            String payload = objectMapper.writeValueAsString(event);

            kafkaTemplate.send(topic, key, payload).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish review request {} to Kafka", event.getEventId(), ex);
                    meterRegistry.counter("queue.publish.failure").increment();
                } else {
                    meterRegistry.counter("queue.publish.success").increment();
                }
            });
        } catch (Exception e) {
            log.error("Serialization failure publishing review request {}", event.getEventId(), e);
            meterRegistry.counter("queue.publish.serialization_error").increment();
            throw new IllegalStateException("Could not publish review request", e);
        }
    }
}
