package com.example.codereview.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "review")
public class ReviewProperties {

    private Queue queue = new Queue();
    private GitHub github = new GitHub();
    private Conventions conventions = new Conventions();
    private Aggregation aggregation = new Aggregation();
    private List<AgentConfig> agents = List.of();
    private Map<String, ProviderConfig> providers = Map.of();

    @Data
    public static class Queue {
        private String topic;
        private String dlqTopic;
    }

    @Data
    public static class GitHub {
        private String apiBaseUrl;
        private String appToken;
        private String webhookSecret;
        private int maxInlineCommentsPerPr = 25;
    }

    @Data
    public static class Conventions {
        private String repoConfigPath;
        private String defaultConfig;
    }

    @Data
    public static class Aggregation {
        private int convergenceMinAgents = 2;
        private double uniqueFindingConfidenceThreshold = 0.72;
        private double dedupSimilarityThreshold = 0.85;
    }

    @Data
    public static class AgentConfig {
        private String name;
        private String provider;
        private String model;
        private String focus;
        private boolean enabled = true;
    }

    @Data
    public static class ProviderConfig {
        private String baseUrl;
        private String apiKey;
        private int timeoutMs = 30000;
    }
}
