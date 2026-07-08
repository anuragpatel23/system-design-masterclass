# GCP Core Services — The Comparison Vocabulary

> **Mental model:** GCP's differentiators come from Google's internal systems made public: **Spanner** (globally consistent SQL — the [CAP](../../01-foundations/cap-theorem/README.md) conversation-piece), **BigQuery** (serverless analytics), **Bigtable** (the original wide-column paper), **Pub/Sub** (global messaging), **GKE** (Kubernetes came from Google). In interviews, GCP fluency mostly means (a) mapping services across clouds instantly and (b) knowing the two or three genuinely *different* offerings well enough to reach for them.

---

## 1. The cross-cloud map (answer "what's that in GCP?" instantly)

| Concept | AWS | GCP | GCP-specific notes |
|---|---|---|---|
| VMs | EC2 | **Compute Engine** | Sustained-use discounts are automatic; live migration during host maintenance |
| Object storage | S3 | **Cloud Storage (GCS)** | Same classes idea (Standard/Nearline/Coldline/Archive); one global namespace |
| Managed containers | EKS | **GKE** | The reference managed Kubernetes; Autopilot = node-less mode ([Fargate](../aws/README.md)-ish) |
| Serverless functions | Lambda | **Cloud Functions** | Also **Cloud Run**: serverless *containers* — any image, scale-to-zero, HTTP — often the better default |
| Relational | RDS/Aurora | **Cloud SQL / AlloyDB** | Managed Postgres/MySQL; AlloyDB = Aurora-class Postgres |
| Global SQL | — (no true equivalent) | **Spanner** | The headliner — see below |
| Wide-column NoSQL | DynamoDB | **Bigtable** | The 2006 paper DynamoDB/Cassandra descend from; HBase API |
| Document DB | DynamoDB-ish | **Firestore** | Mobile/web sync, offline support |
| Queue/pub-sub | SQS+SNS | **Pub/Sub** | One service does both; global by default; at-least-once, per-key ordering opt-in |
| Kafka-style log | Kinesis/MSK | Pub/Sub (+ Managed Kafka) | Pub/Sub covers most stream cases |
| Analytics warehouse | Redshift | **BigQuery** | The other headliner — see below |
| CDN / LB | CloudFront/ALB | **Cloud CDN / Cloud Load Balancing** | GCP's LB is **global anycast** — one IP worldwide, not per-region ([load balancing](../../02-building-blocks/load-balancers/README.md) at Google scale) |
| Cache | ElastiCache | **Memorystore** | Managed [Redis](../redis/README.md) |
| IAM/KMS/Secrets | IAM/KMS | **Cloud IAM / Cloud KMS / Secret Manager** | Same [concepts](../../10-security-observability/security-essentials/README.md) |
| Observability | CloudWatch/X-Ray | **Cloud Monitoring / Trace / Logging** | Dapper's descendants — [tracing](../../10-security-observability/observability/README.md)'s birthplace |

## 2. The two genuinely different services (worth real depth)

- **Spanner — globally distributed, strongly consistent SQL.** The "impossible" combination: horizontal scaling + SQL + **external consistency** (strictest [consistency model](../../01-foundations/consistency-models/README.md)) across regions. The trick to name: **TrueTime** — GPS + atomic clocks in every datacenter give bounded clock uncertainty; Spanner *waits out the uncertainty interval* before committing, making global timestamp ordering safe, plus [Paxos](../../05-distributed-systems/consensus-algorithms/paxos/README.md) groups per data split. The trade: commit latency (waiting + cross-region consensus) and cost — you buy consistency with milliseconds. Interview use: "if the requirement is truly global strongly-consistent transactions and budget allows, Spanner-class systems exist; otherwise I design around [regional consistency + async cross-region](../../02-building-blocks/databases/replication/README.md)."
- **BigQuery — serverless, columnar analytics.** No clusters to size (contrast Redshift): storage and compute fully separated; you pay per TB scanned; columnar layout + massive fan-out execution (Dremel lineage) scans terabytes in seconds. Interview use: the [OLTP-vs-OLAP split](../../06-databases-deep-dive/database-scaling/README.md) — "operational queries hit Postgres; analytics land in BigQuery via CDC/[event streams](../../05-distributed-systems/event-driven-architecture/README.md); never run analytics on the OLTP primary."

## 3. Installation / try it

```bash
# gcloud CLI
curl https://sdk.cloud.google.com | bash && gcloud init
gcloud projects create my-sandbox --set-as-default
# Free-tier-safe basics
gsutil mb gs://my-test-bucket-$RANDOM && echo hi > f.txt && gsutil cp f.txt gs://...
gcloud pubsub topics create orders
gcloud pubsub subscriptions create billing --topic orders
gcloud pubsub topics publish orders --message '{"id":1}'
gcloud pubsub subscriptions pull billing --auto-ack
# Cloud Run: deploy any container, scale-to-zero
gcloud run deploy hello --image gcr.io/cloudrun/hello --allow-unauthenticated
```

Java (Maven): `com.google.cloud:google-cloud-storage|pubsub|spanner` — or the emulators (`gcloud beta emulators pubsub start`) for offline dev.

## 4. The implementation

[`GcpCoreConcepts.java`](GcpCoreConcepts.java) implements the two distinctive mechanisms, from scratch: **a TrueTime-style commit-wait simulation** (bounded clock uncertainty; transactions wait out the interval, and the demo shows how ordering violations appear when you *don't* wait) and **a columnar mini-store** (row-store vs column-store scan cost measured side by side — why BigQuery reads 1 column of 10M rows cheaply while a row store reads everything). Zero dependencies.

## 5. Interview soundbites

- "GCP's load balancer is global anycast — one IP, traffic enters Google's backbone at the nearest edge; AWS's is per-region."
- "Spanner buys external consistency with TrueTime: bounded clock uncertainty plus deliberately waiting it out at commit — consistency paid for in milliseconds."
- "BigQuery separates storage from compute and bills per scan — the canonical 'don't run analytics on the OLTP database' destination."
- "Cloud Run is the sweet spot between Lambda and Kubernetes — serverless containers, any runtime, scale-to-zero."

**Related:** [AWS](../aws/README.md) · [CAP Theorem](../../01-foundations/cap-theorem/README.md) · [Consistency Models](../../01-foundations/consistency-models/README.md) · [Kubernetes](../kubernetes/README.md)
