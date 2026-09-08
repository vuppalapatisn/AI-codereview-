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
   `kubectl set image` and expects the Deployment to already exist. Config and
   credentials must land *before* the Deployment, or the first pods boot with
   empty values:

   ```bash
   kubectl create namespace code-review
   kubectl apply -f k8s/configmap.yaml
   kubectl create secret generic ai-code-review-secrets \
     --namespace code-review \
     --from-literal=GITHUB_APP_TOKEN="$GITHUB_APP_TOKEN" \
     --from-literal=GITHUB_WEBHOOK_SECRET="$GITHUB_WEBHOOK_SECRET" \
     --from-literal=ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY" \
     --from-literal=OPENAI_API_KEY="$OPENAI_API_KEY"
   kubectl apply -f k8s/deployment.yaml
   ```

The `deploy` job targets a `production` GitHub environment, which GitHub creates
on first reference; add required reviewers there if you want the rollout gated
on a manual approval.

### Configuration reference

Six environment variables are read, all via
`src/main/resources/application.yml`. Two are plain config
(`k8s/configmap.yaml`), four are credentials (`k8s/secret.example.yaml` is a
committed template — create the real Secret imperatively as above, or via
External Secrets / Vault, so values never enter git).

| Variable | Source | Default | Effect if unset |
| --- | --- | --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | ConfigMap | `localhost:9092` | Pods can't reach the broker; consumers retry-loop and no PR is ever reviewed |
| `CONVENTIONS_PATH` | ConfigMap | `./conventions` | Resolves to the image's baked-in `/app/conventions`, so this is safe to omit |
| `GITHUB_WEBHOOK_SECRET` | Secret | empty | **Hard stop.** `GitHubSignatureVerifier` fails closed on a blank secret, so every delivery is rejected 401 and the pipeline sits idle |
| `GITHUB_APP_TOKEN` | Secret | empty | Diff fetches and review posts get 401 from the GitHub API |
| `ANTHROPIC_API_KEY` | Secret | empty | The `logic-reviewer` and `security-reviewer` agents fail; their circuit breakers open |
| `OPENAI_API_KEY` | Secret | empty | The `convention-reviewer` agent fails the same way |

Agent failures are isolated — each agent catches its own provider errors and
degrades to zero findings rather than failing the PR, and the orchestrator
aggregates whatever succeeded. So a missing provider key quietly lowers review
quality instead of erroring loudly. Alert on it:

```promql
rate(agent_run_total{outcome="failure"}[5m]) > 0
```

If you only have one provider, trim
`review.agents` in `application.yml` instead of leaving dead agents configured;
convergence needs at least `review.aggregation.convergence-min-agents` (default
2) agents to mark anything convergent.

## What's intentionally simplified

- The acceptance evaluator's match logic is a stub - LinkedIn's real version
  diffs the flagged hunk against the merged code with much more care.
- Only two providers are wired up (Anthropic, OpenAI-shaped). Adding a third
  is a new `ReviewAgent` implementation plus a `ReviewAgentFactory` case.
- Repo conventions load from local YAML files; production would likely back
  this with a config repo or database so repo owners can self-serve edits.
