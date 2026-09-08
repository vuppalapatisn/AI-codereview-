package com.example.codereview.agent;

import com.example.codereview.model.ReviewModels.AgentResult;
import com.example.codereview.model.ReviewModels.PullRequestContext;
import com.example.codereview.model.ReviewModels.ReviewFocus;

/**
 * One independent AI reviewer. Deliberately model-agnostic: LinkedIn's
 * key insight was that using multiple *distinct* models/reasoning styles,
 * not just multiple prompts against the same model, is what catches the
 * blind spots any single model shares across all its outputs.
 */
public interface ReviewAgent {

    String name();

    ReviewFocus focus();

    /**
     * Runs a full review pass over the PR context and returns findings.
     * Implementations must never throw for provider-level failures (timeouts,
     * rate limits, malformed responses) - they should catch those and return
     * an AgentResult with success=false so one flaky provider never blocks
     * the whole pipeline.
     */
    AgentResult review(PullRequestContext context);
}
