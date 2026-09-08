package com.example.codereview.agent;

import com.example.codereview.config.ReviewProperties;
import com.example.codereview.model.ReviewModels.ReviewFocus;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the `review.agents` list from configuration and instantiates one
 * ReviewAgent per enabled entry. Adding a new independent reviewer - a new
 * model, a new focus area, a new provider - is a config change, not a code
 * change, which is what lets an org layer in targeted guidance for
 * high-risk scenarios without redeploying agent logic.
 */
@Slf4j
@Component
public class ReviewAgentFactory {

    private final ReviewProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final ReviewPromptBuilder promptBuilder;
    private final FindingResponseParser parser;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final RateLimiterRegistry rateLimiterRegistry;

    public ReviewAgentFactory(ReviewProperties properties,
                               WebClient.Builder webClientBuilder,
                               ReviewPromptBuilder promptBuilder,
                               FindingResponseParser parser,
                               CircuitBreakerRegistry circuitBreakerRegistry,
                               RetryRegistry retryRegistry,
                               RateLimiterRegistry rateLimiterRegistry) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
        this.promptBuilder = promptBuilder;
        this.parser = parser;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
        this.rateLimiterRegistry = rateLimiterRegistry;
    }

    public List<ReviewAgent> buildEnabledAgents() {
        List<ReviewAgent> agents = new ArrayList<>();

        for (ReviewProperties.AgentConfig cfg : properties.getAgents()) {
            if (!cfg.isEnabled()) {
                continue;
            }
            ReviewFocus focus = ReviewFocus.valueOf(cfg.getFocus());
            ReviewProperties.ProviderConfig provider = properties.getProviders().get(cfg.getProvider());

            if (provider == null) {
                log.warn("Agent {} references unknown provider {}, skipping", cfg.getName(), cfg.getProvider());
                continue;
            }

            switch (cfg.getProvider()) {
                case "anthropic" -> agents.add(new AnthropicReviewAgent(
                        cfg.getName(), cfg.getModel(), focus,
                        webClientBuilder, provider.getBaseUrl(), provider.getApiKey(),
                        promptBuilder, parser,
                        circuitBreakerRegistry, retryRegistry, rateLimiterRegistry));
                case "openai" -> agents.add(new OpenAiReviewAgent(
                        cfg.getName(), cfg.getModel(), focus,
                        webClientBuilder, provider.getBaseUrl(), provider.getApiKey(),
                        promptBuilder, parser,
                        circuitBreakerRegistry, retryRegistry, rateLimiterRegistry));
                default -> log.warn("No agent implementation registered for provider {}", cfg.getProvider());
            }
        }

        log.info("Initialized {} review agents: {}", agents.size(),
                agents.stream().map(ReviewAgent::name).toList());
        return agents;
    }
}
