package com.example.codereview.queue;

import com.example.codereview.model.ReviewModels.ReviewRequestedEvent;
import com.example.codereview.service.ReviewOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Each pod running this service is a stateless worker that can be scaled
 * horizontally (Kubernetes HPA scales replica count based on consumer lag).
 * Manual ack ensures a crashed worker mid-review doesn't lose the message -
 * Kafka redelivers it to another pod instead. After a fixed number of
 * failed attempts the event is routed to a dead-letter topic for later
 * inspection rather than looping forever.
 */
@Slf4j
@Component
public class ReviewRequestConsumer {

    private static final int MAX_DELIVERY_ATTEMPTS = 3;

    private final ReviewOrchestrator orchestrator;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${review.queue.dlq-topic}")
    private String dlqTopic;

    @Value("${review.queue.topic}")
    private String reviewTopic;

    public ReviewRequestConsumer(ReviewOrchestrator orchestrator,
                                  ObjectMapper objectMapper,
                                  KafkaTemplate<String, String> kafkaTemplate,
                                  MeterRegistry meterRegistry) {
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(topics = "${review.queue.topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onReviewRequested(String payload, Acknowledgment ack) {
        Timer.Sample sample = Timer.start(meterRegistry);
        ReviewRequestedEvent event = null;
        try {
            event = objectMapper.readValue(payload, ReviewRequestedEvent.class);
            log.info("Processing review request {} for {}/{}#{}",
                    event.getEventId(), event.getRepoOwner(), event.getRepoName(), event.getPullRequestNumber());

            orchestrator.reviewPullRequest(event);

            ack.acknowledge();
            meterRegistry.counter("review.completed").increment();
        } catch (Exception e) {
            log.error("Review pipeline failed for payload={}", payload, e);
            meterRegistry.counter("review.failed").increment();
            handleFailure(event, payload, ack);
        } finally {
            sample.stop(Timer.builder("review.latency").register(meterRegistry));
        }
    }

    private void handleFailure(ReviewRequestedEvent event, String rawPayload, Acknowledgment ack) {
        int attempt = event != null ? event.getDeliveryAttempt() : MAX_DELIVERY_ATTEMPTS;
        if (event != null && attempt < MAX_DELIVERY_ATTEMPTS) {
            try {
                event.setDeliveryAttempt(attempt + 1);
                String retryPayload = objectMapper.writeValueAsString(event);
                kafkaTemplate.send(reviewTopic, event.getRepoOwner() + "/" + event.getRepoName(), retryPayload);
                meterRegistry.counter("review.retry_requeued").increment();
            } catch (Exception ex) {
                log.error("Failed to requeue event {} for retry", event.getEventId(), ex);
            }
        } else {
            kafkaTemplate.send(dlqTopic, rawPayload);
            meterRegistry.counter("review.dead_lettered").increment();
            log.warn("Event exceeded max delivery attempts, sent to DLQ: {}", rawPayload);
        }
        // Acknowledge regardless: we've handled the failure ourselves via retry-requeue
        // or DLQ, so we don't want Kafka's own redelivery to double-process it.
        ack.acknowledge();
    }
}
