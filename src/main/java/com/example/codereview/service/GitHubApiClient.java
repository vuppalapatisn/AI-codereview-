package com.example.codereview.service;

import com.example.codereview.config.ReviewProperties;
import com.example.codereview.model.ReviewModels.AggregatedFinding;
import com.example.codereview.model.ReviewModels.FileDiff;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the GitHub REST API for the two operations the
 * pipeline needs: reading a PR's file diffs, and posting review comments
 * back onto it. Kept separate from the orchestrator so it can be mocked
 * cleanly in tests and swapped for GraphQL or GitHub App auth later
 * without touching pipeline logic.
 */
@Slf4j
@Component
public class GitHubApiClient {

    private final WebClient webClient;
    private final ReviewProperties properties;

    public GitHubApiClient(WebClient.Builder webClientBuilder, ReviewProperties properties) {
        this.properties = properties;
        this.webClient = webClientBuilder
                .baseUrl(properties.getGithub().getApiBaseUrl())
                .defaultHeader("Accept", "application/vnd.github+json")
                .build();
    }

    public List<FileDiff> fetchPullRequestFiles(String owner, String repo, int pullNumber) {
        JsonNode files = webClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{number}/files?per_page=100", owner, repo, pullNumber)
                .header("Authorization", "Bearer " + properties.getGithub().getAppToken())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        List<FileDiff> diffs = new ArrayList<>();
        if (files != null && files.isArray()) {
            for (JsonNode f : files) {
                // Binary files and renames-with-no-content-change have no "patch" field.
                if (!f.hasNonNull("patch")) {
                    continue;
                }
                diffs.add(FileDiff.builder()
                        .path(f.path("filename").asText())
                        .patch(f.path("patch").asText())
                        .additions(f.path("additions").asInt(0))
                        .deletions(f.path("deletions").asInt(0))
                        .build());
            }
        }
        return diffs;
    }

    public String fetchPullRequestTitle(String owner, String repo, int pullNumber) {
        JsonNode pr = webClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{number}", owner, repo, pullNumber)
                .header("Authorization", "Bearer " + properties.getGithub().getAppToken())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
        return pr != null ? pr.path("title").asText("") : "";
    }

    /**
     * Posts findings as a single review with multiple inline comments,
     * which is far less noisy for the PR author than one notification per
     * comment. Caps the count to avoid drowning a PR in feedback - the
     * cap is a config value, not a hardcoded number, per LinkedIn's point
     * about signal-to-noise being a first-class operational concern.
     */
    public void postReviewComments(String owner, String repo, int pullNumber, String headSha,
                                    List<AggregatedFinding> findings) {
        if (findings.isEmpty()) {
            log.info("No findings to post for {}/{}#{}", owner, repo, pullNumber);
            return;
        }

        int cap = properties.getGithub().getMaxInlineCommentsPerPr();
        List<AggregatedFinding> capped = findings.size() > cap ? findings.subList(0, cap) : findings;

        List<Map<String, Object>> comments = capped.stream()
                .map(f -> Map.<String, Object>of(
                        "path", f.getFilePath(),
                        "line", f.getLine() != null ? f.getLine() : 1,
                        "body", formatCommentBody(f)))
                .toList();

        Map<String, Object> requestBody = Map.of(
                "commit_id", headSha,
                "event", "COMMENT",
                "body", "Automated review found " + capped.size() + " item(s) worth a look.",
                "comments", comments
        );

        try {
            webClient.post()
                    .uri("/repos/{owner}/{repo}/pulls/{number}/reviews", owner, repo, pullNumber)
                    .header("Authorization", "Bearer " + properties.getGithub().getAppToken())
                    .bodyValue(requestBody)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("Posted {} review comments to {}/{}#{}", capped.size(), owner, repo, pullNumber);
        } catch (Exception e) {
            log.error("Failed to post review comments to {}/{}#{}", owner, repo, pullNumber, e);
            throw e;
        }
    }

    private String formatCommentBody(AggregatedFinding finding) {
        StringBuilder sb = new StringBuilder();
        sb.append("**[").append(finding.getSeverity()).append("] ").append(finding.getCategory()).append("**\n\n");
        sb.append(finding.getMessage()).append("\n");
        if (finding.isConvergent()) {
            sb.append("\n_Flagged independently by ").append(finding.getCorroboratingAgents().size())
              .append(" reviewers._");
        }
        if (finding.getSuggestedFix() != null && !finding.getSuggestedFix().isBlank()) {
            sb.append("\n\n```suggestion\n").append(finding.getSuggestedFix()).append("\n```");
        }
        return sb.toString();
    }
}
