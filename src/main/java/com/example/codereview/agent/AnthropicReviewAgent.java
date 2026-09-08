package com.example.codereview.agent;

import com.example.codereview.model.ReviewModels.AgentResult;
import com.example.codereview.model.ReviewModels.PullRequestContext;
import com.example.codereview.model.ReviewModels.ReviewFocus;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * One of the independent reviewer agents, backed by an Anthropic model.
 * Instances are created per agent config entry in {@link ReviewAgentFactory}
 * rather than being Spring beans themselves, so resilience (retry, circuit
 * breaker, rate limiter) is composed programmatically here using the
 * Resilience4j registries injected from Spring - annotation-based AOP would
 * silently no-op on manually constructed objects like this one.
 */
@Slf4j
public class AnthropicReviewAgent implements ReviewAgent {

    private final String agentName;
    private final String model;
    private final ReviewFocus focus;
    private final WebClient webClient;
    private final String apiKey;
    private final ReviewPromptBuilder promptBuilder;
    private final FindingResponseParser parser;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final RateLimiter rateLimiter;

    public AnthropicReviewAgent(String agentName, String model, ReviewFocus focus,
                                 WebClient.Builder webClientBuilder, String baseUrl, String apiKey,
                                 ReviewPromptBuilder promptBuilder, FindingResponseParser parser,
                                 CircuitBreakerRegistry circuitBreakerRegistry,
                                 RetryRegistry retryRegistry,
                                 RateLimiterRegistry rateLimiterRegistry) {
        this.agentName = agentName;
        this.model = model;
        this.focus = focus;
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.promptBuilder = promptBuilder;
        this.parser = parser;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("llmProvider");
        this.retry = retryRegistry.retry("llmProvider");
        this.rateLimiter = rateLimiterRegistry.rateLimiter("llmProvider");
    }

    @Override
    public String name() {
        return agentName;
    }

    @Override
    public ReviewFocus focus() {
        return focus;
    }

    @Override
    public AgentResult review(PullRequestContext context) {
        long start = System.currentTimeMillis();

        Supplier<JsonNode> decoratedCall = RateLimiter.decorateSupplier(rateLimiter,
                CircuitBreaker.decorateSupplier(circuitBreaker,
                        Retry.decorateSupplier(retry, () -> callModel(context))));

        try {
            JsonNode response = decoratedCall.get();
            String text = extractText(response);
            var findings = parser.parse(text, agentName, focus);

            return AgentResult.builder()
                    .agentName(agentName)
                    .success(true)
                    .latencyMs(System.currentTimeMillis() - start)
                    .findings(findings)
                    .build();

        } catch (Exception e) {
            // Any provider failure (timeout, rate limit exhaustion, open circuit)
            // lands here. We intentionally degrade to zero findings from this
            // agent rather than failing the whole PR review.
            log.error("Agent {} failed to complete review", agentName, e);
            return AgentResult.builder()
                    .agentName(agentName)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .latencyMs(System.currentTimeMillis() - start)
                    .findings(List.of())
                    .build();
        }
    }

    private JsonNode callModel(PullRequestContext context) {
        String prompt = promptBuilder.build(context, focus);

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", 4000,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        return webClient.post()
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(30))
                .block();
    }

    private String extractText(JsonNode response) {
        if (response == null) return "";
        JsonNode content = response.path("content");
        if (content.isArray() && !content.isEmpty()) {
            return content.get(0).path("text").asText("");
        }
        return "";
    }
}
