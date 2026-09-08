package com.example.codereview.service;

import com.example.codereview.config.ReviewProperties;
import com.example.codereview.model.ReviewModels.AgentFinding;
import com.example.codereview.model.ReviewModels.AgentResult;
import com.example.codereview.model.ReviewModels.AggregatedFinding;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implements the cross-validation idea at the heart of LinkedIn's design:
 * when multiple independent agents flag the same underlying issue, that
 * convergence is treated as strong evidence and the finding's confidence
 * is boosted accordingly. Findings only one agent raised aren't discarded -
 * they're kept but marked non-convergent, so downstream filtering can
 * apply a stricter confidence bar to them.
 *
 * "Same issue" is approximated by (filePath, nearby line, same category)
 * rather than exact text match, since two models describing the same bug
 * rarely use identical wording.
 */
@Component
public class FindingAggregator {

    private static final int LINE_PROXIMITY_WINDOW = 2;

    private final ReviewProperties properties;

    public FindingAggregator(ReviewProperties properties) {
        this.properties = properties;
    }

    public List<AggregatedFinding> aggregate(List<AgentResult> agentResults) {
        List<AgentFinding> allFindings = agentResults.stream()
                .filter(AgentResult::isSuccess)
                .flatMap(r -> r.getFindings().stream())
                .collect(Collectors.toList());

        List<AggregatedFinding> result = new ArrayList<>();
        List<AgentFinding> remaining = new ArrayList<>(allFindings);

        while (!remaining.isEmpty()) {
            AgentFinding seed = remaining.remove(0);
            List<AgentFinding> cluster = new ArrayList<>();
            cluster.add(seed);

            remaining.removeIf(candidate -> {
                if (isSameIssue(seed, candidate)) {
                    cluster.add(candidate);
                    return true;
                }
                return false;
            });

            result.add(buildAggregatedFinding(cluster));
        }

        return result;
    }

    private boolean isSameIssue(AgentFinding a, AgentFinding b) {
        if (!a.getFilePath().equals(b.getFilePath())) {
            return false;
        }
        if (!a.getCategory().equalsIgnoreCase(b.getCategory())) {
            return false;
        }
        if (a.getLine() == null || b.getLine() == null) {
            return a.getLine() == null && b.getLine() == null;
        }
        return Math.abs(a.getLine() - b.getLine()) <= LINE_PROXIMITY_WINDOW;
    }

    private AggregatedFinding buildAggregatedFinding(List<AgentFinding> cluster) {
        Set<String> agentNames = cluster.stream().map(AgentFinding::getAgentName).collect(Collectors.toSet());
        boolean convergent = agentNames.size() >= properties.getAggregation().getConvergenceMinAgents();

        // Convergent findings: average confidence, boosted for agreement.
        // Unique findings: kept at the reporting agent's own confidence, no boost.
        double baseConfidence = cluster.stream().mapToDouble(AgentFinding::getConfidence).average().orElse(0.5);
        double aggregateConfidence = convergent ? Math.min(1.0, baseConfidence + 0.15) : baseConfidence;

        // Take the most severe classification and the most detailed message
        // among the cluster - different agents often word the same bug
        // differently, and the more detailed writeup is usually more useful.
        AgentFinding representative = cluster.stream()
                .max((x, y) -> {
                    int severityCompare = x.getSeverity().compareTo(y.getSeverity());
                    if (severityCompare != 0) return severityCompare;
                    return Integer.compare(x.getMessage().length(), y.getMessage().length());
                })
                .orElse(cluster.get(0));

        return AggregatedFinding.builder()
                .filePath(representative.getFilePath())
                .line(representative.getLine())
                .severity(representative.getSeverity())
                .category(representative.getCategory())
                .message(representative.getMessage())
                .suggestedFix(representative.getSuggestedFix())
                .corroboratingAgents(new ArrayList<>(agentNames))
                .aggregateConfidence(aggregateConfidence)
                .convergent(convergent)
                .build();
    }
}
