package com.example.codereview.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Domain models for the review pipeline. Kept in one file for readability;
 * split into per-class files in a real codebase.
 */
public class ReviewModels {

    /** What a single AI reviewer specializes in. Mirrors LinkedIn's approach of
     *  giving each agent a distinct reasoning lens rather than one generic pass. */
    public enum ReviewFocus {
        LOGIC_CORRECTNESS,
        SECURITY,
        STYLE_AND_CONVENTIONS,
        CONCURRENCY,
        PERFORMANCE
    }

    public enum Severity { INFO, MINOR, MAJOR, CRITICAL }

    /** Message that lands on the durable queue once a PR event is ingested. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReviewRequestedEvent {
        private String eventId;
        private String repoOwner;
        private String repoName;
        private int pullRequestNumber;
        private String headSha;
        private String baseSha;
        private Instant requestedAt;
        private int deliveryAttempt;
    }

    /** A single file's diff, fetched from GitHub once a request is dequeued. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileDiff {
        private String path;
        private String patch;      // unified diff hunk text
        private String fullContentAfter; // optional, for context beyond the hunk
        private int additions;
        private int deletions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PullRequestContext {
        private String repoOwner;
        private String repoName;
        private int number;
        private String headSha;
        private String title;
        private String description;
        private List<FileDiff> files;
        private RepoConventions conventions;
    }

    /** A raw finding produced by one reviewer agent, before aggregation. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentFinding {
        private String agentName;
        private ReviewFocus focus;
        private String filePath;
        private Integer line;           // line in the new file version; null if file-level
        private Severity severity;
        private String category;        // e.g. "null-pointer", "sql-injection", "naming"
        private String message;
        private String suggestedFix;     // optional patch/snippet
        private double confidence;       // 0.0-1.0, agent's own estimate
    }

    /** Result of one agent's full pass over a PR, including failures. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentResult {
        private String agentName;
        private boolean success;
        private String errorMessage;
        private long latencyMs;
        private List<AgentFinding> findings;
    }

    /** A finding after cross-validation: may be backed by 1..N agents. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AggregatedFinding {
        private String filePath;
        private Integer line;
        private Severity severity;
        private String category;
        private String message;
        private String suggestedFix;
        private List<String> corroboratingAgents;
        private double aggregateConfidence;
        private boolean convergent; // true if 2+ independent agents agreed
    }

    /** Repository-specific conventions layered on top of org-wide policy. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RepoConventions {
        private String repoName;
        private List<String> orgWidePolicies;
        private List<String> repoSpecificRules;
        private List<String> ignoredCategories;   // e.g. suppress "line-length" for this repo
        private List<String> highRiskPaths;        // extra scrutiny, e.g. "**/payments/**"
    }
}
