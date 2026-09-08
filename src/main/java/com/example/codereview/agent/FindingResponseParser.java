package com.example.codereview.agent;

import com.example.codereview.model.ReviewModels.AgentFinding;
import com.example.codereview.model.ReviewModels.ReviewFocus;
import com.example.codereview.model.ReviewModels.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class FindingResponseParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Models occasionally wrap the JSON array in markdown fences or add a
     * leading sentence despite instructions. This extracts the first
     * top-level JSON array found in the text rather than failing outright.
     */
    public List<AgentFinding> parse(String rawModelOutput, String agentName, ReviewFocus focus) {
        List<AgentFinding> findings = new ArrayList<>();
        String jsonArrayText = extractJsonArray(rawModelOutput);
        if (jsonArrayText == null) {
            log.warn("Agent {} returned no parseable JSON array; treating as zero findings", agentName);
            return findings;
        }

        try {
            JsonNode array = objectMapper.readTree(jsonArrayText);
            if (!array.isArray()) {
                return findings;
            }
            for (JsonNode node : array) {
                AgentFinding finding = AgentFinding.builder()
                        .agentName(agentName)
                        .focus(focus)
                        .filePath(node.path("filePath").asText(null))
                        .line(node.hasNonNull("line") ? node.get("line").asInt() : null)
                        .severity(parseSeverity(node.path("severity").asText("MINOR")))
                        .category(node.path("category").asText("uncategorized"))
                        .message(node.path("message").asText(""))
                        .suggestedFix(node.hasNonNull("suggestedFix") ? node.get("suggestedFix").asText() : null)
                        .confidence(node.path("confidence").asDouble(0.5))
                        .build();
                if (finding.getFilePath() != null && !finding.getMessage().isBlank()) {
                    findings.add(finding);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse findings JSON from agent {}: {}", agentName, e.getMessage());
        }
        return findings;
    }

    private Severity parseSeverity(String raw) {
        try {
            return Severity.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            return Severity.MINOR;
        }
    }

    private String extractJsonArray(String text) {
        if (text == null) return null;
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start == -1 || end == -1 || end < start) {
            return null;
        }
        return text.substring(start, end + 1);
    }
}
