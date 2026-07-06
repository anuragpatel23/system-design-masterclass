# 01 — Foundations

> The five ideas that every other design decision in this vault ultimately reduces to. If you can't explain these from first principles — including the exceptions and the math — you are not ready for a senior/staff architect interview.

## Why this section exists

Most candidates fail staff-level system design rounds not because they don't know Kafka or Redis, but because when the interviewer pushes on *"why availability over consistency here?"* or *"what exactly do you mean by scalable?"*, the answer collapses into buzzwords. This section forces the buzzwords to become precise, falsifiable statements.

## Topics in this section

| # | Topic | What it really tests in an interview |
|---|-------|----------------------------------------|
| 1 | [Scalability](scalability/README.md) | Can you scale a *specific* bottleneck, not just say "add servers"? |
| 2 | [Availability & Reliability](availability-reliability/README.md) | Do you know the math behind "five nines" and what actually breaks it? |
| 3 | [CAP Theorem](cap-theorem/README.md) | Can you apply CAP to a concrete component, not recite the triangle? |
| 4 | [Consistency Models](consistency-models/README.md) | Do you know the spectrum between strict and eventual, and where your system sits? |
| 5 | [Latency vs Throughput](latency-vs-throughput/README.md) | Can you reason about tail latency (p99) instead of averages? |

## How to use this section

1. Read each topic top to bottom once, ignoring the code.
2. Re-read and actually trace the Spring Boot example — run it if you can.
3. Cover the "Real-World Reference" with your hand and try to reconstruct it from memory.
4. Use the "60-Second Interview Answer" block at the end of each doc as your final drilling flashcard before an interview.

Next: [02 — Building Blocks](../02-building-blocks/README.md)
