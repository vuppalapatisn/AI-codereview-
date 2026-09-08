package com.example.codereview.controller;

import com.example.codereview.model.ReviewModels.ReviewRequestedEvent;
import com.example.codereview.queue.ReviewRequestPublisher;
import com.example.codereview.security.GitHubSignatureVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Entry point for GitHub's pull_request webhook. Kept intentionally thin:
 * verify -> parse -> enqueue. All actual review work happens asynchronously
 * downstream so this endpoint returns in milliseconds regardless of PR size,
 * satisfying GitHub's webhook delivery timeout.
 */
@Slf4j
@RestController
@RequestMapping("/webhooks/github")
public class GitHubWebhookController {

    private final GitHubSignatureVerifier signatureVerifier;
    private final ReviewRequestPublisher publisher;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Value("${review.github.webhook-secret}")
    private String webhookSecret;

    private static final Set<String> RELEVANT_ACTIONS = Set.of("opened", "synchronize", "reopened", "ready_for_review");

    /**
     * Startup diagnostic for the most common misconfiguration: an unset
     * webhook secret. {@link GitHubSignatureVerifier} fails closed on a blank
     * value, so every delivery would be rejected with a 401 while the pod
     * otherwise looks perfectly healthy - worth one loud line at boot.
     *
     * Logs only whether the secret is present and how long it is. Never the
     * value: org-wide policy (conventions/default-conventions.yml) forbids
     * logging secrets in plaintext, and pod logs are typically shipped to a
     * cluster-wide aggregator that far more people can read than can read the
     * Secret itself. Length alone is enough to catch a truncated or
     * accidentally-quoted value.
     */
    @PostConstruct
    void logWebhookSecretStatus() {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.error("review.github.webhook-secret is not set - every webhook delivery will be "
                    + "rejected with 401. Set GITHUB_WEBHOOK_SECRET (see k8s/secret.example.yaml).");
        } else {
            log.info("Webhook signature verification enabled (secret present, {} chars)", webhookSecret.length());
        }
    }

    public GitHubWebhookController(GitHubSignatureVerifier signatureVerifier,
                                    ReviewRequestPublisher publisher,
                                    ObjectMapper objectMapper,
                                    MeterRegistry meterRegistry) {
        this.signatureVerifier = signatureVerifier;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping("/pull-request")
    public ResponseEntity<String> handlePullRequestEvent(
            @RequestHeader("X-Hub-Signature-256") String signature,
            @RequestHeader("X-GitHub-Event") String githubEvent,
            @RequestBody String rawPayload) {

        Counter.builder("webhook.received").tag("event", githubEvent).register(meterRegistry).increment();

        if (!signatureVerifier.isValid(rawPayload, signature, webhookSecret)) {
            log.warn("Rejected webhook delivery with invalid signature");
            Counter.builder("webhook.rejected.signature").register(meterRegistry).increment();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("invalid signature");
        }

        if (!"pull_request".equals(githubEvent)) {
            // We only wired up pull_request in the route, but double check the header
            // in case the webhook config on GitHub's side gets edited later.
            return ResponseEntity.ok("ignored: not a pull_request event");
        }

        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            String action = root.path("action").asText();
            if (!RELEVANT_ACTIONS.contains(action)) {
                return ResponseEntity.ok("ignored: action=" + action);
            }

            JsonNode pr = root.path("pull_request");
            JsonNode repo = root.path("repository");

            ReviewRequestedEvent event = ReviewRequestedEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .repoOwner(repo.path("owner").path("login").asText())
                    .repoName(repo.path("name").asText())
                    .pullRequestNumber(pr.path("number").asInt())
                    .headSha(pr.path("head").path("sha").asText())
                    .baseSha(pr.path("base").path("sha").asText())
                    .requestedAt(Instant.now())
                    .deliveryAttempt(1)
                    .build();

            publisher.publish(event);
            log.info("Enqueued review request for {}/{}#{}", event.getRepoOwner(), event.getRepoName(), event.getPullRequestNumber());
            Counter.builder("webhook.enqueued").register(meterRegistry).increment();
            return ResponseEntity.accepted().body(event.getEventId());

        } catch (Exception e) {
            log.error("Failed to parse or enqueue pull_request webhook payload", e);
            Counter.builder("webhook.error").register(meterRegistry).increment();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("processing error");
        }
    }

    // health-friendly root so load balancers / GitHub delivery pings have a cheap target
    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("ok");
    }
}
