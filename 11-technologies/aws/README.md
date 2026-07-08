# AWS Core Services — The Default Cloud Vocabulary

> **Mental model:** in interviews, AWS service names function as **shorthand for architectural components**: say "S3" and you've said "11-nines-durable object store"; say "SQS" and you've said "managed at-least-once queue with visibility timeouts." This note maps the services worth knowing to the vault concepts they implement, with the per-service facts interviewers actually probe. (Deep dive per concept lives in the linked notes; this is the AWS-vocabulary layer.)

---

## 1. The services-to-concepts map

| Service | Is the managed version of | The 2–3 facts that matter |
|---|---|---|
| **EC2** | Virtual machines | Instance families (compute/memory/storage-optimized); pricing: on-demand vs **reserved/savings (≈steady core)** vs **spot (interruptible, ~70-90% off — batch/stateless only)** — the [utilization economics](../../07-microservices/serverless/README.md) dial |
| **S3** | Object storage / [blob tier](../../09-interview-prep/capacity-estimation.md) | 11 nines *durability* (≠ availability); strong read-after-write consistency (since 2020); storage classes + lifecycle (Standard → IA → Glacier); presigned URLs (clients upload/download directly — takes blobs off your servers); event notifications → Lambda/SQS |
| **EBS / EFS** | Block vs shared file storage | EBS attaches to one instance (a disk); EFS = NFS for many |
| **RDS / Aurora** | Managed [relational DB](../../02-building-blocks/databases/sql-vs-nosql/README.md) | Multi-AZ = synchronous standby for **HA failover**; read replicas = async **read scaling** ([the difference matters](../../02-building-blocks/databases/replication/README.md)); Aurora separates storage (6-way replicated) from compute |
| **DynamoDB** | Managed wide-column [NoSQL](../../02-building-blocks/databases/sql-vs-nosql/README.md) | Partition key design = [sharding](../../02-building-blocks/databases/sharding/README.md) discipline (hot partitions!); on-demand vs provisioned capacity; DynamoDB Streams → CDC; single-digit-ms at any scale, in exchange for query-by-key-only modeling |
| **ElastiCache** | Managed [Redis](../redis/README.md)/Memcached | Everything from the Redis note applies |
| **SQS** | Managed [queue](../../02-building-blocks/message-queues/README.md) | [Visibility timeout, long polling, DLQ](../message-polling/README.md); standard (at-least-once, best-effort order) vs **FIFO** (ordered, exactly-once-per-5-min-dedup, lower throughput) |
| **SNS** | Managed pub/sub fan-out | SNS→SQS fan-out is the standard [event distribution](../../05-distributed-systems/event-driven-architecture/README.md) combo; also mobile push/[SMS](../sms-sending/README.md) |
| **Kinesis / MSK** | Managed [Kafka-style log](../kafka/README.md) | Shards = partitions; MSK is literal Kafka |
| **Lambda** | [FaaS](../../07-microservices/serverless/README.md) | Cold starts, 15-min cap, concurrency = the whole serverless note |
| **ELB (ALB/NLB)** | [Load balancer](../../02-building-blocks/load-balancers/README.md) | ALB = L7 (paths, headers); NLB = L4 (TCP, extreme throughput, static IP) |
| **CloudFront** | [CDN](../../02-building-blocks/cdn/README.md) | Edge caching, origin shield, signed URLs |
| **Route 53** | DNS + routing policies | Weighted (canary!), latency-based, failover routing — [deployment](../../07-microservices/deployment-patterns/README.md) and geo tools hiding in DNS |
| **API Gateway** | Managed [gateway](../../02-building-blocks/api-gateway/README.md) | Auth, throttling, and the front door to Lambda |
| **EKS / ECS / Fargate** | Managed [Kubernetes](../kubernetes/README.md) / simpler orchestrator / serverless containers | Fargate = pods without nodes |
| **IAM** | [AuthZ](../../10-security-observability/authentication-authorization/README.md) for infrastructure | Roles > keys; least privilege; instance roles kill hardcoded creds |
| **KMS / Secrets Manager** | [Key & secret management](../../10-security-observability/security-essentials/README.md) | Envelope encryption as a service |
| **CloudWatch / X-Ray** | [Observability](../../10-security-observability/observability/README.md) | Metrics/logs/alarms; X-Ray = tracing |

## 2. The three AWS-specific ideas interviews reward

- **Regions & AZs:** a region = several isolated **availability zones** (distinct datacenters, ~<2ms apart). The standard HA posture: **multi-AZ everything** (LB across AZs, RDS multi-AZ, stateless fleet spread) — surviving an AZ loss is table stakes; multi-*region* is a deliberate, expensive [availability](../../01-foundations/availability-reliability/README.md) upgrade for DR/latency.
- **The default web-app skeleton** (memorize as a unit): Route 53 → CloudFront → ALB (multi-AZ) → ECS/EKS autoscaled services → ElastiCache → RDS multi-AZ + read replicas → S3 for blobs → SQS for async → Lambda for glue → CloudWatch + IAM everywhere. Every box maps to a section-02 concept.
- **Managed ≠ magic:** SQS still redelivers ([idempotency](../../08-api-design/idempotency/README.md) is yours), DynamoDB still hot-partitions (key design is yours), RDS replicas still lag ([read-your-writes](../../01-foundations/consistency-models/README.md) is yours). The trade is undifferentiated ops for cost + [vendor coupling](../../07-microservices/serverless/README.md).

## 3. Installation / try it

```bash
# CLI
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o awscliv2.zip
unzip awscliv2.zip && sudo ./aws/install && aws configure   # or SSO
# Free-tier-safe: make a bucket, a queue, send/receive
aws s3 mb s3://my-test-bucket-$RANDOM
aws sqs create-queue --queue-name jobs
# Or emulate locally, zero cost: LocalStack
docker run -d -p 4566:4566 localstack/localstack
aws --endpoint-url=http://localhost:4566 s3 mb s3://local-bucket
```

Java SDK v2 (Maven): `software.amazon.awssdk:s3|sqs|dynamodb` — builder-pattern clients, works against LocalStack by overriding `endpointOverride`.

## 4. The implementation

[`AwsCoreConcepts.java`](AwsCoreConcepts.java) implements the *behaviors* AWS is shorthand for, from scratch (zero dependencies, runnable offline): **an S3-style object store with presigned-URL semantics (HMAC-signed, expiring links), a DynamoDB-style partitioned KV with a hot-partition detector, and multi-AZ failover simulation** (kill the primary AZ, watch traffic shift). Each maps a marketing name to a mechanism you can now defend.

## 5. Interview soundbites

- "S3's 11 nines are durability, not availability — and presigned URLs take upload/download traffic off my fleet entirely."
- "Multi-AZ is synchronous HA; read replicas are async scaling — different features solving different problems."
- "SQS FIFO trades throughput for ordering + dedup; standard queues make idempotency my job."
- "Spot for stateless batch, reserved for the steady core, on-demand for the spikes — compute economics is a portfolio."

**Related:** [GCP](../gcp/README.md) · [Serverless](../../07-microservices/serverless/README.md) · [Message Polling](../message-polling/README.md) · [Availability](../../01-foundations/availability-reliability/README.md)
