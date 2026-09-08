# AI Code Review Platform

A Spring Boot implementation of a multi-agent AI code review pipeline for GitHub
pull requests, inspired by LinkedIn's engineering write-up on scaling high-signal
AI code review. This is a reference implementation, not a drop-in production
system - swap the stub provider calls for real API keys, point conventions at
your actual policy store, and load-test before trusting it against real traffic.

## Architecture

```
GitHub PR event -> Webhook controller (HMAC verified) -> Kafka (durable queue)
    -> Worker pods (Kubernetes, horizontally scaled)
        -> N independent review agents run in parallel (different models/focus areas)
        -> Aggregator: cross-validates findings, boosts confidence on agent agreement
        -> Signal filter: drops repo-suppressed, low-confidence, cosmetic findings
        -> GitHub comment publisher: posts a single review with inline comments
    -> Metrics: latency, acceptance rate, provider failures (Micrometer/Prometheus)
```

Key design choices, and why:

- **Multiple independent agents, not one model with different prompts.** Each
  agent in `review.agents` (application.yml) can use a different provider and
  model. This is what catches blind spots any single model shares across all
  its own outputs.
- **Convergence-based confidence.** `FindingAggregator` treats agreement between
  independently-run agents as evidence, boosting confidence on convergent
  findings while still keeping - not discarding - unique ones, subject to a
  higher confidence bar.
- **Composable customization.** `RepoConventionService` layers org-wide policy
  (`conventions/default-conventions.yml`) under repo-specific overrides
  (`conventions/{owner}__{repo}.yml`), so a payments-service repo can require
  `BigDecimal` for money without every repo in the org inheriting that rule.
- **Operational control.** Durable Kafka queue with manual ack + DLQ, circuit
  breakers/rate limiters per LLM provider, and Micrometer counters for every
  stage so the pipeline can be monitored and tuned like any other production
  service, not treated as a black box.
- **Acceptance-rate tracking.** `AcceptanceEvaluationService` sketches the
  offline job that answers "are developers actually using these suggestions,"
  the same question LinkedIn's 5,230-comment sample was built to answer.

## Running locally

```bash
docker compose up -d kafka   # or point KAFKA_BOOTSTRAP_SERVERS at an existing cluster
export GITHUB_APP_TOKEN=...
export GITHUB_WEBHOOK_SECRET=...
export ANTHROPIC_API_KEY=...
export OPENAI_API_KEY=...
./mvnw spring-boot:run
```

Register a GitHub webhook (or use `.github/workflows/trigger-ai-review.yml` in
a consumer repo) pointing at `POST /webhooks/github/pull-request`.

## Testing

```bash
./mvnw test
```

Covers HMAC signature verification (valid, tampered, wrong-secret cases) and
the aggregator's convergence math (merge-on-agreement, confidence boosting,
non-merging across files, failed-agent isolation).

## Deploying

`.github/workflows/ci-build-test.yml` builds, tests, publishes a container
image to GHCR, and rolls it out to the `code-review` namespace via
`k8s/deployment.yaml`, which includes an HPA scaffolded for CPU-based scaling
(swap in Kafka-consumer-lag scaling via KEDA for closer parity with a queue-
depth-driven autoscaling policy).

The `deploy` job is **opt-in**: a `preflight` job checks whether the
`KUBE_CONFIG_BASE64` secret is set and `deploy` is gated on that output, so a
clone with no cluster gets a green run with `deploy` skipped rather than a red
one. (The check lives in its own job because GitHub does not expose the
`secrets` context to a job-level `if:`.)

To enable the rollout:

1. Base64-encode a kubeconfig scoped to the `code-review` namespace — ideally a
   ServiceAccount token with just `patch`/`get` on `deployments`, not your admin
   context:

   ```bash
   base64 -w0 ~/.kube/config
   ```

2. Add it as the repository secret `KUBE_CONFIG_BASE64` under
   **Settings -> Secrets and variables -> Actions -> New repository secret**.

3. Provision the workloads once, since the rollout step uses
   `kubectl set image` and expects the Deployment to already exist:

   ```bash
   kubectl create namespace code-review
   kubectl apply -f k8s/deployment.yaml
   ```

   The pods also expect an `ai-code-review-secrets` Secret and an
   `ai-code-review-config` ConfigMap (see `envFrom` in `k8s/deployment.yaml`)
   carrying `GITHUB_APP_TOKEN`, `GITHUB_WEBHOOK_SECRET`, the provider API keys,
   and `KAFKA_BOOTSTRAP_SERVERS`.

The `deploy` job targets a `production` GitHub environment, which GitHub creates
on first reference; add required reviewers there if you want the rollout gated
on a manual approval.

## What's intentionally simplified

- The acceptance evaluator's match logic is a stub - LinkedIn's real version
  diffs the flagged hunk against the merged code with much more care.
- Only two providers are wired up (Anthropic, OpenAI-shaped). Adding a third
  is a new `ReviewAgent` implementation plus a `ReviewAgentFactory` case.
- Repo conventions load from local YAML files; production would likely back
  this with a config repo or database so repo owners can self-serve edits.
