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
 * Independent reviewer backed by an OpenAI-compatible chat completions API.
 * Kept as a separate class (rather than parameterizing one HTTP agent) so
 * each provider's request/response shape and quirks stay isolated - the
 * chat-completions envelope differs enough from Anthropic's messages
 * envelope that sharing code would mean conditional branches everywhere.
 */
@Slf4j
public class OpenAiReviewAgent implements ReviewAgent {

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

    public OpenAiReviewAgent(String agentName, String model, ReviewFocus focus,
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
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "temperature", 0.2
        );

        return webClient.post()
                .header("Authorization", "Bearer " + apiKey)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(30))
                .block();
    }

    private String extractText(JsonNode response) {
        if (response == null) return "";
        JsonNode choices = response.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            return choices.get(0).path("message").path("content").asText("");
        }
        return "";
    }
}
