# Prometheus & Grafana — Observability, Implemented

> **Mental model:** Prometheus is a **pull-based time-series database with a query language**: it scrapes `/metrics` endpoints on an interval, stores label-dimensional series, and evaluates **PromQL** for dashboards (Grafana) and alerts (Alertmanager). This is the concrete implementation of everything in [Observability](../../10-security-observability/observability/README.md) — golden signals, histogram percentiles, burn-rate alerts — and "how would you actually monitor this?" is answered in this stack's vocabulary.

---

## 1. The architecture and its one contrarian choice

```
Services expose GET /metrics (text format)          Grafana ──PromQL──► Prometheus
        ▲         ▲         ▲                                              │
        └──── Prometheus scrapes every 15s ────┘                    Alertmanager
              (PULL, not push)                              (dedup, group, route → pager/Slack)
```

- **Pull, not push:** Prometheus fetches from targets (discovered via [Kubernetes](../kubernetes/README.md)/[service discovery](../../07-microservices/service-discovery/README.md)). Why it's clever: the monitoring system *knows when a target is down* (scrape fails ⇒ `up == 0` — failure detection for free); no client buffering/backpressure problems; and adding monitoring can't overload the app (Prometheus controls the rate). Push gateways exist only for short-lived batch jobs. This pull-vs-push trade is itself a fine interview vignette.
- **The data model is labels:** `http_requests_total{service="orders", method="GET", status="500"}` — every unique label combination is a separate time series. Powerful — and the source of the classic failure: a **high-cardinality label** (user_id, URL-with-ids) explodes series count and OOMs Prometheus. [Same warning as before](../../10-security-observability/observability/README.md), now with a mechanism.

## 2. The four metric types and the histogram trick

- **Counter** (only goes up: `http_requests_total`) — always queried with `rate()`: `rate(http_requests_total[5m])` = per-second rate over 5 minutes. Rates from counters survive scrape gaps and restarts (the counter-reset logic is built in).
- **Gauge** (goes both ways: queue depth, memory, in-flight requests) — the [saturation signals](../../10-security-observability/observability/README.md).
- **Histogram** — observations land in **cumulative buckets** (`le="0.1"`, `le="0.5"`, `le="+Inf"`), each a counter. Percentiles are *computed at query time*: `histogram_quantile(0.99, sum by (le) (rate(http_request_seconds_bucket[5m])))`. **This is the mechanical answer to "how do you aggregate p99 across 50 instances?"** — you can't average percentiles, but you *can* sum bucket counters across instances and then extract the quantile. The estimate's precision is bounded by bucket boundaries — choose them around your SLO threshold.
- **Summary** — client-side precomputed quantiles; accurate per-instance but **not aggregatable** — which is exactly why histograms won.

**The golden-signals starter kit in PromQL:**

```promql
sum(rate(http_requests_total[5m])) by (service)                          # traffic
sum(rate(http_requests_total{status=~"5.."}[5m]))
  / sum(rate(http_requests_total[5m]))                                   # error ratio
histogram_quantile(0.99, sum by (le) (rate(http_request_seconds_bucket[5m])))  # latency p99
sum(queue_depth) by (service)   /   max(threadpool_active / threadpool_max)    # saturation
```

**Burn-rate alerting** (the [SLO](../../10-security-observability/observability/README.md) method, concretely): alert when the error ratio over a short window says you'd exhaust the month's budget in hours — e.g., for a 99.9% SLO: `error_ratio[5m] > 14.4 * 0.001` (fast burn) paired with a slower 1h window; two windows kill flapping.

## 3. Installation (the full stack in three containers)

```bash
docker run -d --name prometheus -p 9090:9090 \
  -v $PWD/prometheus.yml:/etc/prometheus/prometheus.yml prom/prometheus
docker run -d --name grafana -p 3000:3000 grafana/grafana   # admin/admin; add Prometheus datasource
docker run -d --name node-exporter -p 9100:9100 prom/node-exporter   # host metrics

# prometheus.yml (minimal)
# scrape_configs:
#   - job_name: myapp
#     static_configs: [{ targets: ["host.docker.internal:8080"] }]
```

Java instrumentation: Micrometer (`io.micrometer:micrometer-registry-prometheus`) — Spring Boot exposes `/actuator/prometheus` with one dependency; or `io.prometheus:simpleclient_httpserver` standalone.

## 4. The from-scratch implementation

[`MiniPrometheus.java`](MiniPrometheus.java) implements the pipeline end to end: **an instrumented demo service exposing `/metrics` in the real Prometheus text format** (counter + gauge + histogram with cumulative `le` buckets), **a scraper** that pulls on an interval and stores time series, **`rate()` and `histogram_quantile()` evaluation** (the actual bucket-interpolation algorithm), and **a burn-rate alert rule** that fires when injected errors exceed the SLO budget. Run it and read your own p99 off your own histogram.

## 5. Interview soundbites

- "Pull-based scraping gives failure detection for free — a failed scrape *is* the down signal — and the monitoring system controls its own load."
- "Counters + `rate()`, gauges for saturation, histograms for latency — and percentiles come from summing bucket counters across instances then `histogram_quantile`, which is why you can aggregate histograms but never averages of percentiles."
- "The classic self-inflicted outage is a high-cardinality label — user IDs in labels turn metrics into a time-series explosion."
- "Alerts are burn-rate on SLO error budgets with two windows — pages map to user pain, not CPU thresholds."

**Related:** [Observability](../../10-security-observability/observability/README.md) · [Kubernetes](../kubernetes/README.md) · [Deployment Patterns](../../07-microservices/deployment-patterns/README.md) · [Latency vs Throughput](../../01-foundations/latency-vs-throughput/README.md)
