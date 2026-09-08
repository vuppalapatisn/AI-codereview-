package com.example.codereview.service;

import com.example.codereview.config.ReviewProperties;
import com.example.codereview.model.ReviewModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FindingAggregatorTest {

    private FindingAggregator aggregator;

    @BeforeEach
    void setUp() {
        ReviewProperties properties = new ReviewProperties();
        ReviewProperties.Aggregation aggregationConfig = new ReviewProperties.Aggregation();
        aggregationConfig.setConvergenceMinAgents(2);
        properties.setAggregation(aggregationConfig);
        aggregator = new FindingAggregator(properties);
    }

    @Test
    void twoAgentsFlaggingSameIssueBecomeConvergentWithBoostedConfidence() {
        AgentFinding fromLogicAgent = finding("logic-reviewer", "Order.java", 42, "null-pointer", 0.6);
        AgentFinding fromSecurityAgent = finding("security-reviewer", "Order.java", 43, "null-pointer", 0.7);

        List<AggregatedFinding> results = aggregator.aggregate(List.of(
                successResult("logic-reviewer", fromLogicAgent),
                successResult("security-reviewer", fromSecurityAgent)
        ));

        assertEquals(1, results.size(), "Nearby same-category findings on the same file should merge into one");
        AggregatedFinding merged = results.get(0);
        assertTrue(merged.isConvergent());
        assertEquals(2, merged.getCorroboratingAgents().size());
        // average of 0.6 and 0.7 is 0.65, boosted by 0.15 -> 0.80
        assertEquals(0.80, merged.getAggregateConfidence(), 0.001);
    }

    @Test
    void singleAgentFindingStaysNonConvergent() {
        AgentFinding onlyFinding = finding("convention-reviewer", "Utils.java", 10, "naming", 0.55);

        List<AggregatedFinding> results = aggregator.aggregate(List.of(
                successResult("convention-reviewer", onlyFinding)
        ));

        assertEquals(1, results.size());
        AggregatedFinding result = results.get(0);
        assertFalse(result.isConvergent());
        assertEquals(0.55, result.getAggregateConfidence(), 0.001, "Unique findings get no confidence boost");
    }

    @Test
    void differentFilesNeverMerge() {
        AgentFinding inFileA = finding("logic-reviewer", "A.java", 1, "null-pointer", 0.6);
        AgentFinding inFileB = finding("security-reviewer", "B.java", 1, "null-pointer", 0.6);

        List<AggregatedFinding> results = aggregator.aggregate(List.of(
                successResult("logic-reviewer", inFileA),
                successResult("security-reviewer", inFileB)
        ));

        assertEquals(2, results.size());
        assertTrue(results.stream().noneMatch(AggregatedFinding::isConvergent));
    }

    @Test
    void failedAgentResultsContributeNoFindings() {
        AgentResult failed = AgentResult.builder()
                .agentName("flaky-agent")
                .success(false)
                .errorMessage("timeout")
                .findings(List.of())
                .build();

        List<AggregatedFinding> results = aggregator.aggregate(List.of(failed));
        assertTrue(results.isEmpty());
    }

    private AgentFinding finding(String agent, String file, int line, String category, double confidence) {
        return AgentFinding.builder()
                .agentName(agent)
                .focus(ReviewFocus.LOGIC_CORRECTNESS)
                .filePath(file)
                .line(line)
                .severity(Severity.MAJOR)
                .category(category)
                .message("Potential issue near line " + line)
                .confidence(confidence)
                .build();
    }

    private AgentResult successResult(String agentName, AgentFinding... findings) {
        return AgentResult.builder()
                .agentName(agentName)
                .success(true)
                .latencyMs(100)
                .findings(List.of(findings))
                .build();
    }
}
