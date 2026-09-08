package com.example.codereview.service;

import com.example.codereview.config.ReviewProperties;
import com.example.codereview.model.ReviewModels.AggregatedFinding;
import com.example.codereview.model.ReviewModels.RepoConventions;
import com.example.codereview.model.ReviewModels.Severity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The "everything that comes after generating a finding" step LinkedIn
 * calls out as the hard part: this is where low-signal noise gets cut so
 * only things worth a developer's attention are posted. Three independent
 * gates, applied in order so cheap checks run before more nuanced ones:
 *
 *   1. Repo-level suppression - conventions explicitly say "don't tell us about X"
 *   2. Confidence bar - unique (non-convergent) findings need higher confidence
 *      than convergent ones, since they lack cross-validation
 *   3. Cosmetic downgrade - INFO-severity style nits are dropped unless the
 *      repo's conventions explicitly care about that category
 */
@Slf4j
@Component
public class SignalFilter {

    private final ReviewProperties properties;

    public SignalFilter(ReviewProperties properties) {
        this.properties = properties;
    }

    public List<AggregatedFinding> filter(List<AggregatedFinding> findings, RepoConventions conventions) {
        Set<String> ignoredCategories = conventions != null && conventions.getIgnoredCategories() != null
                ? conventions.getIgnoredCategories().stream().map(String::toLowerCase).collect(Collectors.toSet())
                : Set.of();

        double uniqueThreshold = properties.getAggregation().getUniqueFindingConfidenceThreshold();

        List<AggregatedFinding> passed = findings.stream()
                .filter(f -> !ignoredCategories.contains(f.getCategory().toLowerCase()))
                .filter(f -> f.isConvergent() || f.getAggregateConfidence() >= uniqueThreshold)
                .filter(f -> f.getSeverity() != Severity.INFO || isExplicitlyTracked(f.getCategory(), conventions))
                .sorted((a, b) -> b.getSeverity().compareTo(a.getSeverity())) // most severe first
                .collect(Collectors.toList());

        log.info("Signal filter: {} findings in, {} passed", findings.size(), passed.size());
        return passed;
    }

    private boolean isExplicitlyTracked(String category, RepoConventions conventions) {
        if (conventions == null || conventions.getRepoSpecificRules() == null) {
            return false;
        }
        return conventions.getRepoSpecificRules().stream()
                .anyMatch(rule -> rule.toLowerCase().contains(category.toLowerCase()));
    }
}
