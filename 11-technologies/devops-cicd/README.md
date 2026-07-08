# DevOps & CI/CD — How Code Reaches Production

> **Mental model:** DevOps is the practice of making **deployment a boring, automated, high-frequency event** instead of a quarterly ceremony. The machinery: CI (every commit built + tested automatically), CD (every green build deployable — or deployed — automatically), IaC (infrastructure as reviewed, versioned code), and GitOps (git as the single source of desired state). The interview version: "walk me through what happens between `git push` and production" — a question that filters operators from theoreticians.

---

## 1. The pipeline (the answer to "what happens after git push")

```
git push ──► CI: compile ─ unit tests ─ static analysis/security scan
                 ─ build Docker image (tag = git SHA) ─ push to registry
                 ─ integration tests against the image
         ──► CD: deploy to STAGING (automatic)
                 ─ smoke/e2e tests
                 ─ deploy to PROD via canary/rolling      ← 07-deployment-patterns
                 ─ automated canary analysis on golden signals ← 10-observability
                 ─ auto-rollback on burn                   ← SLO/error budgets
```

- **CI's contract:** the main branch is always releasable. Enablers: **trunk-based development** (short-lived branches, [feature flags](../../07-microservices/deployment-patterns/README.md) hide incomplete work), the **test pyramid** (many fast unit tests, fewer integration, few e2e — inverted pyramids make pipelines slow and flaky), and **build-once-deploy-many**: the *same immutable artifact* (image digest) goes to staging and prod — only config differs (12-factor) — so "it worked in staging" means something.
- **CD's dial:** delivery (auto-to-staging, gated-to-prod) vs deployment (all the way, no human) — the gate's cost is cycle time; elite teams deploy on demand, many times a day, *because* the [deployment patterns](../../07-microservices/deployment-patterns/README.md) + [observability](../../10-security-observability/observability/README.md) make each deploy low-stakes. DORA metrics (deploy frequency, lead time, change-failure rate, MTTR) are the scoreboard worth name-dropping.
- **Tools (know one per box):** CI: GitHub Actions / GitLab CI / Jenkins. Artifact: Docker registry. CD: Argo CD / Flux (GitOps), Spinnaker (canary analysis). Don't tool-shop in interviews — name the *stages* and one tool each.

## 2. IaC and GitOps (the config half)

- **Infrastructure as Code:** Terraform/OpenTofu (declarative, `plan` shows the diff before `apply` — infrastructure change review becomes code review) or CloudFormation/Pulumi. The property that matters: **environments are reproducible** — staging is prod's twin because both are the same module with different variables, and disaster recovery is `terraform apply` in a new region, not archaeology.
- **GitOps** (Argo CD/Flux): desired state (K8s manifests/Helm) lives in git; an in-cluster operator **continuously reconciles** cluster ← git ([the Kubernetes reconciliation idea](../kubernetes/README.md) extended to deployment itself). Deploy = merge a PR; rollback = revert a commit; audit log = git history; drift (manual `kubectl edit`) is auto-corrected. Contrast push-based CD (pipeline runs kubectl) — GitOps is pull-based, and the cluster credentials never leave the cluster.
- **Config & secrets:** config in env/ConfigMaps (12-factor: same image everywhere); secrets never in git — [vault/sealed-secrets](../../10-security-observability/security-essentials/README.md).

## 3. Installation / try it (a real pipeline in 15 minutes)

```yaml
# .github/workflows/ci.yml — a minimal, real CI/CD skeleton
name: ci
on: { push: { branches: [main] } }
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - run: mvn -q verify                          # CI: build + tests
      - run: docker build -t ghcr.io/${{github.repository}}:${{github.sha}} .
      - run: docker push  ghcr.io/${{github.repository}}:${{github.sha}}
  deploy-staging:
    needs: build
    runs-on: ubuntu-latest
    steps:                                          # GitOps flavor: bump the image tag in a config repo
      - run: |
          echo "update k8s manifest image tag to ${{github.sha}} and merge PR;"
          echo "Argo CD reconciles the cluster to match."
```

Local: `act` runs GitHub Actions locally; `kind` + Argo CD gives a full GitOps loop on a laptop.

## 4. The from-scratch implementation

[`MiniPipeline.java`](MiniPipeline.java) implements a CI/CD engine: **a stage DAG executor** (stages with dependencies, fail-fast, per-stage logs), an **immutable artifact store keyed by commit SHA**, **environment promotion** (staging → canary → prod of the *same* digest), **automated canary analysis** (error-rate comparison gates promotion — a failing canary auto-rolls back), and a **GitOps reconciler** (desired-version file vs running-version map, converged on a loop). One run prints the whole `push → prod` story.

## 5. Interview soundbites

- "Build once, deploy many: the artifact that reaches prod is the exact digest that passed CI — environments differ only in config."
- "Trunk-based development plus feature flags keeps main releasable; long-lived branches are where CI goes to die."
- "GitOps makes deployment a merge and rollback a revert, with drift auto-corrected — git is the source of truth, the cluster follows it."
- "The pipeline's later stages are just section 07's deployment patterns automated: canary, automated analysis on golden signals, rollback on error-budget burn."

**Related:** [Deployment Patterns](../../07-microservices/deployment-patterns/README.md) · [Docker](../docker/README.md) · [Kubernetes](../kubernetes/README.md) · [Observability](../../10-security-observability/observability/README.md)
