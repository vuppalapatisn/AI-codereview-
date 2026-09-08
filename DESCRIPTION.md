# AI Code Review Platform — Description

## Background: what LinkedIn built

LinkedIn engineers built a multi-agent AI code review platform that understands
the organization's coding context, treats code review as production
infrastructure, and minimizes hallucinations and low-signal feedback. At
LinkedIn's scale — tens of thousands of pull requests weekly across
10,000+ repositories — relying solely on human reviewers, or simply putting
an off-the-shelf AI reviewer in front of GitHub, isn't an effective way to
manage PRs.

Their goal was generating reviews that developers find worth acting on,
maximizing signal-to-noise, and accounting for the codebase's standards,
conventions, and tribal knowledge that generic AI models consistently miss.
Generating AI review comments at scale is trivial — the hard part is
everything that comes after: making them factually grounded in the diff
rather than hallucinated, high-signal rather than noisy, specific to the
conventions of this codebase rather than generic best practices, and
arriving before the human reviewer, not after.

They identified three structural limitations of a single off-the-shelf AI
reviewer:

1. **Blind spots from a single model** — one model tends to miss the same
   class of bugs and repeat the same low-signal flags every time.
2. **Insufficient customization** — hard to simultaneously encode
   org-wide policy, repo-specific conventions, and targeted guidance for
   high-risk scenarios with a single generic reviewer.
3. **Lack of operational control** — hard to monitor, evaluate, and run the
   reviewer as real infrastructure rather than a black box.

Their platform addresses each limitation through:

- **Multiple independent AI reviewers** using distinct models and reasoning
  approaches, enabling cross-validation. When multiple agents independently
  identify the same issue, that convergence is treated as strong evidence.
  Unique findings aren't automatically discarded but verified separately;
  cosmetic, already-fixed, irrelevant, or repository-inconsistent
  suggestions are filtered before posting.
- **Deep, composable customization** spanning organization-wide policies,
  repository-level conventions, and context-specific rules.
- **A Kubernetes-based architecture** supporting an event-driven pipeline
  with durable queues and horizontally scaled workers, enabling monitoring
  of latency, acceptance and completion rates, and provider failures.

To measure real-world impact, LinkedIn built an automated acceptance-rate
evaluation pipeline comparing posted suggestions against the resulting
merged codebase. Across 5,230 sampled review comments over 1,727 PRs,
90.1% could be evaluated with high confidence, and 63.9% of suggestions
were accepted overall — with wide variance by category: 80% of logic
errors, 100% of concurrency bugs, 58.1% of bug fixes, 43.5% of refactors,
and 40.6% of security fixes were accepted.

## Architecture (this implementation)

```
GitHub PR event -> Webhook controller (HMAC verified) -> Kafka (durable queue)
    -> Worker pods (Kubernetes, horizontally scaled)
        -> N independent review agents run in parallel (different models/focus areas)
        -> Aggregator: cross-validates findings, boosts confidence on agent agreement
        -> Signal filter: drops repo-suppressed, low-confidence, cosmetic findings
        -> GitHub comment publisher: posts a single review with inline comments
    -> Metrics: latency, acceptance rate, provider failures (Micrometer/Prometheus)
```

### Ingestion

`GitHubWebhookController` verifies every inbound delivery's
`X-Hub-Signature-256` header via `GitHubSignatureVerifier` (constant-time
HMAC-SHA256 comparison, fails closed on missing secret) before touching the
payload. It parses only the fields it needs, builds a `ReviewRequestedEvent`,
and publishes to Kafka — returning in milliseconds regardless of PR size,
which matters because GitHub enforces a webhook delivery timeout.

### Durable queue

`ReviewRequestPublisher` / `ReviewRequestConsumer` use Kafka with an
idempotent producer, manual acknowledgment, and a bounded retry-then-DLQ
policy. Partition key is `owner/repo`, so events for one repository process
in order while different repos parallelize freely across partitions.

### Multi-agent review core

Each entry in `review.agents` (application.yml) becomes a live `ReviewAgent`
instance via `ReviewAgentFactory` — a different provider, model, and focus
area (logic, security, conventions, concurrency, performance) per agent.
`AnthropicReviewAgent` and `OpenAiReviewAgent` each wire their own circuit
breaker, retry, and rate limiter *programmatically* rather than via
annotations, since these agents are built manually from config rather than
being Spring-managed beans (annotation-based AOP silently no-ops on manually
constructed objects). `ReviewPromptBuilder` layers org-wide policy,
repo-specific conventions, and focus-specific instructions into each
agent's prompt, so generic model knowledge gets grounded in the actual
rules of the repo being reviewed.

### Cross-validation & filtering — the hard part

`FindingAggregator` clusters findings across agents by (file, nearby line,
category) rather than exact text match, since different models describe the
same bug differently. Clusters backed by enough independent agents
(configurable, default 2) are marked convergent and get a confidence boost;
unique findings are kept, not discarded, but held to a stricter confidence
bar downstream. `SignalFilter` then drops repo-suppressed categories,
filters out low-confidence unique findings, and downgrades cosmetic
(INFO-severity) noise unless a repo's conventions explicitly track that
category.

### Repo-specific customization

`RepoConventionService` loads and caches org-wide policy
(`conventions/default-conventions.yml`) layered under repo-specific
overrides (`conventions/{owner}__{repo}.yml`) — e.g. a payments-service repo
can require `BigDecimal` for money and flag `**/ledger/**` as high-risk
without every other repo in the org inheriting those rules.

### Operational infrastructure

`ReviewOrchestrator` runs all enabled agents in parallel on virtual threads
per PR event, then hands results through the aggregator and filter before
`GitHubApiClient` posts a single review with capped inline comments (never
more than `review.github.max-inline-comments-per-pr`). Micrometer counters
and timers track agent success/failure, latency, and findings posted at
every stage, matching LinkedIn's point about running AI review as monitored
infrastructure rather than a black box. `AcceptanceEvaluationService`
sketches the offline job that would answer "are developers actually using
these suggestions" — the same question LinkedIn's sampled-comment pipeline
was built to answer.

### Deployment

`k8s/deployment.yaml` defines a Deployment, Service, and HorizontalPodAutoscaler
for the worker pods, with a commented-in external metric for scaling on Kafka
consumer lag (via KEDA or a custom-metrics adapter) for closer parity with
queue-depth-driven autoscaling. `.github/workflows/ci-build-test.yml` builds,
tests, publishes a container image to GHCR, and rolls it out; a second
workflow, `.github/workflows/trigger-ai-review.yml`, is a drop-in file for any
consumer repository that wants to call the platform directly from Actions
instead of relying purely on GitHub's own webhook delivery.

## What's intentionally simplified

- The acceptance evaluator's match logic is a stub — LinkedIn's real version
  diffs the flagged hunk against the merged code with much more care, tuned
  empirically against sampled data.
- Only two providers are wired up (Anthropic, an OpenAI-shaped chat API).
  Adding a third is a new `ReviewAgent` implementation plus a
  `ReviewAgentFactory` case — no other code changes.
- Repo conventions load from local YAML files; production would likely back
  this with a config repo or database so repo owners can self-serve edits.

## Source

LinkedIn Engineering Blog — "High-Signal AI Code Review That Adapts to Your
Codebase at Scale":
https://www.linkedin.com/blog/engineering/ai/high-signal-ai-code-review-that-adapts-to-your-codebase-at-scale
