package com.example.codereview.service;

import com.example.codereview.model.ReviewModels.AggregatedFinding;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Mirrors the acceptance-rate evaluation LinkedIn built to answer "are
 * developers actually acting on this?" - the number that ultimately
 * justifies (or kills) an AI review investment. In production this would
 * run as a scheduled job days after merge, diffing the finding's target
 * line/file against the final merged content: if the flagged code no
 * longer exists at that location in a way consistent with the suggested
 * fix, we count it accepted.
 *
 * Kept as a narrow, isolated component so acceptance tracking - which is
 * an offline analytics job - has no ability to slow down or fail the
 * synchronous review-posting path in {@link ReviewOrchestrator}.
 */
@Slf4j
@Component
public class AcceptanceEvaluationService {

    private final GitHubApiClient gitHubApiClient;
    private final MeterRegistry meterRegistry;

    public AcceptanceEvaluationService(GitHubApiClient gitHubApiClient, MeterRegistry meterRegistry) {
        this.gitHubApiClient = gitHubApiClient;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Call this once a PR this pipeline reviewed has been merged. Compares
     * each posted finding's file/line against the final content on the
     * default branch to classify it as accepted, rejected, or inconclusive.
     */
    public AcceptanceReport evaluate(String owner, String repo, int pullNumber,
                                      List<AggregatedFinding> postedFindings, String mergedFileContentByPath) {
        int accepted = 0;
        int rejected = 0;
        int inconclusive = 0;

        for (AggregatedFinding finding : postedFindings) {
            AcceptanceOutcome outcome = classify(finding, mergedFileContentByPath);
            switch (outcome) {
                case ACCEPTED -> accepted++;
                case REJECTED -> rejected++;
                case INCONCLUSIVE -> inconclusive++;
            }
            meterRegistry.counter("review.acceptance",
                    "category", finding.getCategory(),
                    "outcome", outcome.name().toLowerCase()).increment();
        }

        AcceptanceReport report = new AcceptanceReport(owner + "/" + repo, pullNumber, accepted, rejected, inconclusive);
        log.info("Acceptance evaluation for {}/{}#{}: accepted={} rejected={} inconclusive={}",
                owner, repo, pullNumber, accepted, rejected, inconclusive);
        return report;
    }

    /**
     * Simplified classifier: a real implementation diffs the exact hunk
     * around the flagged line against the merged version and checks
     * whether the suggested fix (or something materially similar) landed.
     * Left as a pluggable seam here since the matching heuristic is the
     * kind of thing that gets tuned empirically against sampled data,
     * exactly as LinkedIn describes doing across their 5,230-comment sample.
     */
    private AcceptanceOutcome classify(AggregatedFinding finding, String mergedFileContent) {
        if (mergedFileContent == null) {
            return AcceptanceOutcome.INCONCLUSIVE;
        }
        if (finding.getSuggestedFix() != null && mergedFileContent.contains(finding.getSuggestedFix().trim())) {
            return AcceptanceOutcome.ACCEPTED;
        }
        return AcceptanceOutcome.INCONCLUSIVE;
    }

    public enum AcceptanceOutcome { ACCEPTED, REJECTED, INCONCLUSIVE }

    public record AcceptanceReport(String repo, int pullNumber, int accepted, int rejected, int inconclusive) {
        public double acceptanceRate() {
            int evaluable = accepted + rejected;
            return evaluable == 0 ? 0.0 : (double) accepted / evaluable;
        }
    }
}
