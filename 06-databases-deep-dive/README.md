# 06 — Databases Deep Dive

> Section 02's [SQL vs NoSQL](../02-building-blocks/databases/sql-vs-nosql/README.md), [Sharding](../02-building-blocks/databases/sharding/README.md), and [Replication](../02-building-blocks/databases/replication/README.md) docs treat "the database" as a building block you select and configure. This section opens up *what's actually running inside it* — the storage engine internals that explain **why** a given database is fast at some workloads and slow at others, which is exactly the kind of "go one level deeper" question a staff+ interviewer asks when they want to distinguish "knows how to configure Postgres" from "understands why Postgres and Cassandra make opposite trade-offs by design."

## Topics in this section

| Topic | The concrete question it answers |
|---|---|
| [B-Trees vs LSM-Trees](b-trees-lsm-trees/README.md) | Why are some databases read-optimized and others write-optimized, at the data-structure level? |
| [Indexing Strategies](indexing-strategies/README.md) | Beyond "add an index," which kind of index, and why does the wrong one make queries slower, not faster? |
| [Transactions & ACID](transactions-acid/README.md) | What do the four ACID letters actually guarantee, mechanically, and what are the real isolation-level trade-offs? |
| [Database Scaling](database-scaling/README.md) | A capstone: given everything in this section and section 02, what's the actual decision tree for scaling a database as load grows? |

Previous: [05 — Distributed Systems](../05-distributed-systems/README.md) · Next: [07 — Microservices & Architecture Patterns](../07-microservices/README.md)
