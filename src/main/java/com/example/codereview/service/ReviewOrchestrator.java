package com.example.codereview.service;

import com.example.codereview.agent.ReviewAgent;
import com.example.codereview.agent.ReviewAgentFactory;
import com.example.codereview.model.ReviewModels.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Coordinates one full review pass for a single PR event. This is the
 * class a Kafka consumer worker pod invokes per message; everything below
 * is per-message, stateless work, which is what lets pods be scaled
 * horizontally without any coordination between them.
 */
@Slf4j
@Component
public class ReviewOrchestrator {

    private final ReviewAgentFactory agentFactory;
    private final GitHubApiClient gitHubApiClient;
    private final RepoConventionService conventionService;
    private final FindingAggregator aggregator;
    private final SignalFilter signalFilter;
    private final MeterRegistry meterRegistry;
    private final Executor agentExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private List<ReviewAgent> agents;

    public ReviewOrchestrator(ReviewAgentFactory agentFactory,
                               GitHubApiClient gitHubApiClient,
                               RepoConventionService conventionService,
                               FindingAggregator aggregator,
                               SignalFilter signalFilter,
                               MeterRegistry meterRegistry) {
        this.agentFactory = agentFactory;
        this.gitHubApiClient = gitHubApiClient;
        this.conventionService = conventionService;
        this.aggregator = aggregator;
        this.signalFilter = signalFilter;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void init() {
        // Built once at startup from config, reused across every PR this pod handles.
        this.agents = agentFactory.buildEnabledAgents();
    }

    public void reviewPullRequest(ReviewRequestedEvent event) {
        PullRequestContext context = buildContext(event);

        if (context.getFiles().isEmpty()) {
            log.info("No reviewable diff hunks for {}/{}#{}, skipping",
                    event.getRepoOwner(), event.getRepoName(), event.getPullRequestNumber());
            return;
        }

        List<AgentResult> agentResults = runAgentsInParallel(context);
        recordAgentMetrics(agentResults);

        List<AggregatedFinding> aggregated = aggregator.aggregate(agentResults);
        List<AggregatedFinding> highSignal = signalFilter.filter(aggregated, context.getConventions());

        gitHubApiClient.postReviewComments(
                event.getRepoOwner(), event.getRepoName(), event.getPullRequestNumber(),
                event.getHeadSha(), highSignal);

        meterRegistry.counter("review.findings.posted").increment(highSignal.size());
    }

    private PullRequestContext buildContext(ReviewRequestedEvent event) {
        List<FileDiff> files = gitHubApiClient.fetchPullRequestFiles(
                event.getRepoOwner(), event.getRepoName(), event.getPullRequestNumber());
        String title = gitHubApiClient.fetchPullRequestTitle(
                event.getRepoOwner(), event.getRepoName(), event.getPullRequestNumber());
        RepoConventions conventions = conventionService.loadConventions(event.getRepoOwner(), event.getRepoName());

        return PullRequestContext.builder()
                .repoOwner(event.getRepoOwner())
                .repoName(event.getRepoName())
                .number(event.getPullRequestNumber())
                .headSha(event.getHeadSha())
                .title(title)
                .files(files)
                .conventions(conventions)
                .build();
    }

    private List<AgentResult> runAgentsInParallel(PullRequestContext context) {
        List<CompletableFuture<AgentResult>> futures = agents.stream()
                .map(agent -> CompletableFuture.supplyAsync(() -> agent.review(context), agentExecutor))
                .toList();

        return futures.stream().map(CompletableFuture::join).toList();
    }

    private void recordAgentMetrics(List<AgentResult> results) {
        for (AgentResult result : results) {
            Counter.builder("agent.run")
                    .tag("agent", result.getAgentName())
                    .tag("outcome", result.isSuccess() ? "success" : "failure")
                    .register(meterRegistry)
                    .increment();
            meterRegistry.timer("agent.latency", "agent", result.getAgentName())
                    .record(java.time.Duration.ofMillis(result.getLatencyMs()));
            if (!result.isSuccess()) {
                log.warn("Agent {} failed: {}", result.getAgentName(), result.getErrorMessage());
            }
        }
    }
}
