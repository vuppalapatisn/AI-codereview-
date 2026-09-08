package com.example.codereview.agent;

import com.example.codereview.model.ReviewModels.FileDiff;
import com.example.codereview.model.ReviewModels.PullRequestContext;
import com.example.codereview.model.ReviewModels.RepoConventions;
import com.example.codereview.model.ReviewModels.ReviewFocus;
import org.springframework.stereotype.Component;

/**
 * Builds the prompt each agent sends to its model. This is where
 * "deep, composable customization" happens: org-wide policy, repo-specific
 * conventions, and focus-specific instructions are layered together so
 * generic model knowledge gets grounded in this codebase's actual rules.
 */
@Component
public class ReviewPromptBuilder {

    public String build(PullRequestContext context, ReviewFocus focus) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an AI code reviewer focused specifically on: ").append(describeFocus(focus)).append(".\n\n");
        sb.append("Only report issues within your focus area. Do not comment on unrelated style ")
          .append("nits or restate what the diff obviously does.\n\n");

        RepoConventions conventions = context.getConventions();
        if (conventions != null) {
            appendListSection(sb, "Organization-wide policies", conventions.getOrgWidePolicies());
            appendListSection(sb, "Repository-specific conventions", conventions.getRepoSpecificRules());
            appendListSection(sb, "High-risk paths requiring extra scrutiny", conventions.getHighRiskPaths());
            appendListSection(sb, "Categories to ignore in this repo", conventions.getIgnoredCategories());
        }

        sb.append("Pull request: ").append(context.getTitle()).append("\n");
        if (context.getDescription() != null) {
            sb.append("Description: ").append(context.getDescription()).append("\n");
        }
        sb.append("\nDiff hunks follow. For each finding, respond ONLY in the structured JSON format ")
          .append("described below - no prose outside the JSON array.\n\n");

        for (FileDiff file : context.getFiles()) {
            sb.append("--- FILE: ").append(file.getPath()).append(" ---\n");
            sb.append(file.getPatch()).append("\n\n");
        }

        sb.append("Respond with a JSON array of findings, each with fields: ")
          .append("filePath, line, severity (INFO|MINOR|MAJOR|CRITICAL), category, message, ")
          .append("suggestedFix (nullable), confidence (0.0-1.0). ")
          .append("Return an empty array if you find nothing genuinely worth flagging - ")
          .append("do not manufacture low-value findings to appear thorough.");

        return sb.toString();
    }

    private void appendListSection(StringBuilder sb, String title, java.util.List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        sb.append(title).append(":\n");
        for (String item : items) {
            sb.append("- ").append(item).append("\n");
        }
        sb.append("\n");
    }

    private String describeFocus(ReviewFocus focus) {
        return switch (focus) {
            case LOGIC_CORRECTNESS -> "logic errors, incorrect conditionals, off-by-one bugs, and unhandled edge cases";
            case SECURITY -> "security vulnerabilities such as injection, auth bypass, secrets exposure, and unsafe deserialization";
            case STYLE_AND_CONVENTIONS -> "adherence to this codebase's naming, structure, and idiomatic conventions";
            case CONCURRENCY -> "race conditions, deadlocks, unsafe shared state, and incorrect async/await usage";
            case PERFORMANCE -> "algorithmic complexity issues, unnecessary allocations, and N+1 query patterns";
        };
    }
}
