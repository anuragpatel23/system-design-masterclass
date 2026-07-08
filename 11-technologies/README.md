# 11 — Technology Deep Dives

> Sections 01–10 teach the *concepts*; this section teaches the **named technologies** those concepts wear in production — and in interviews, where "we'd use Redis here" invites the immediate follow-up "okay, how does Redis actually behave?" Each subfolder contains: a README covering architecture, when to use it, and interview-grade internals; a **Java file implementing the core concept from scratch** (because implementing a thing is the fastest way to truly understand it); and an **installation guide** where applicable.

## Topics

| Category | Topic | Concept it embodies |
|---|---|---|
| Caching | [Redis](redis/README.md) | [Caching](../02-building-blocks/caching/README.md), data structures as a service |
| Messaging | [Kafka](kafka/README.md) | The distributed log, [event-driven architecture](../05-distributed-systems/event-driven-architecture/README.md) |
| Messaging | [RabbitMQ](rabbitmq/README.md) | Smart-broker [queueing](../02-building-blocks/message-queues/README.md), routing, acks |
| Messaging | [Solace](solace/README.md) | Enterprise event broker/mesh (finance-heavy shops) |
| Messaging | [Message Polling](message-polling/README.md) | Pull-based consumption, long polling, SQS-style receive loops |
| Communication | [Email Sending](email-sending/README.md) | SMTP, deliverability, async notification pipelines |
| Communication | [SMS Sending](sms-sending/README.md) | Provider APIs (Twilio-style), retries, delivery receipts |
| Edge | [Kong (API Gateway)](kong-api-gateway/README.md) | [API gateway](../02-building-blocks/api-gateway/README.md) as a product: plugins, rate limiting |
| Edge | [Nginx](nginx/README.md) | Reverse proxy, [load balancing](../02-building-blocks/load-balancers/README.md), static serving |
| Platform | [Docker](docker/README.md) | Containers: images, layers, isolation |
| Platform | [Kubernetes](kubernetes/README.md) | Orchestration: pods, services, deployments, autoscaling |
| Cloud | [AWS Core Services](aws/README.md) | EC2/S3/SQS/RDS/Lambda — the default cloud vocabulary |
| Cloud | [GCP Core Services](gcp/README.md) | GCE/GCS/PubSub/Spanner — the comparison vocabulary |
| Practice | [DevOps & CI/CD](devops-cicd/README.md) | Pipelines, IaC, GitOps — how code reaches production |
| Practice | [Linux Command Mastery](linux-mastery/README.md) | The debugging/ops toolbelt every backend interview assumes |
| Search | [Elasticsearch](elasticsearch/README.md) | Inverted indexes, full-text search, log analytics |
| Coordination | [ZooKeeper](zookeeper/README.md) | [Coordination/consensus](../05-distributed-systems/leader-election/README.md) as a service |
| Ops | [Prometheus & Grafana](monitoring-prometheus-grafana/README.md) | [Observability](../10-security-observability/observability/README.md) implemented |

## How to use this section

1. **Read the README** for the technology's mental model and the interview follow-ups it answers.
2. **Read, then run, the Java file.** Each implements the technology's *core mechanism* from scratch (an LRU+TTL cache for Redis, an append-only partitioned log for Kafka, a token-bucket gateway filter for Kong…). You don't understand a consumer group until you've written an offset commit.
3. **Install and poke it** using the installation guide — 15 minutes of hands-on beats hours of reading.

All Java files are plain **JDK 17+, zero dependencies** unless stated (files demonstrating vendor SDKs mark their Maven coordinates in comments) — compile with `javac File.java`, run with `java ClassName`.

Previous: [10 — Security & Observability](../10-security-observability/README.md) · Next: [12 — Application Servers](../12-app-servers/README.md)
