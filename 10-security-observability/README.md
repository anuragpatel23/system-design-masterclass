# 10 — Security & Observability

> The two question families that reliably appear *after* your design is drawn: **"how do you secure it?"** and **"how do you know it's working in production?"** Neither is decoration — at senior/staff loops these follow-ups are where offers are separated, because they test whether you've *operated* systems, not just diagrammed them. This section gives each a mechanism-level treatment consistent with the rest of the vault.

## Topics in this section

| Topic | The concrete question it answers |
|---|---|
| [Authentication & Authorization](authentication-authorization/README.md) | Sessions vs JWTs, OAuth 2.0 / OIDC, SSO, API keys, mTLS — who are you, and what may you do? |
| [Security Essentials](security-essentials/README.md) | TLS, encryption at rest, secrets management, OWASP top risks, defense in depth — the checklist that survives follow-ups |
| [Observability](observability/README.md) | Metrics, logs, traces; golden signals; SLIs/SLOs and error budgets — how production tells you the truth |

## The two framing sentences for this whole section

- **Security:** "every request crosses trust boundaries; at each one, authenticate (who), authorize (may they), validate (is the input sane), and assume the layer before you failed" — defense in depth as a request's journey, not a checklist.
- **Observability:** "monitoring tells you *that* something is wrong (a known signal crossed a threshold); observability lets you ask *why* — including questions you didn't plan for" — and SLOs turn it from dashboards into engineering policy.

## How this connects to the rest of the vault

- The [API Gateway](../02-building-blocks/api-gateway/README.md) is where authN and [rate limiting](../02-building-blocks/rate-limiting/README.md) are enforced; the [service mesh](../07-microservices/service-mesh/README.md) provides mTLS and telemetry east-west.
- [Canary deploys](../07-microservices/deployment-patterns/README.md) are only as good as the metrics judging them; [circuit breakers](../07-microservices/resilience-patterns/README.md) trip on the signals observability provides.
- Every HLD in [03](../03-high-level-design/README.md) can absorb a closing minute of "and we'd watch p99, error rate, and saturation per the golden signals, with authN at the gateway and per-object authZ in the service" — this section is that minute, expanded.

Previous: [09 — Interview Prep](../09-interview-prep/README.md) · Next: [11 — Technology Deep Dives](.