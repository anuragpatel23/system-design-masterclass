# Databases — Deep Dive Index

Databases are where most system design interviews are won or lost at the senior level, because the trade-offs are numerous, concrete, and hard to hand-wave past.

| Topic | Question it answers |
|---|---|
| [SQL vs NoSQL](sql-vs-nosql/README.md) | When does relational structure and ACID actually matter, vs when is it dead weight? |
| [Sharding](sharding/README.md) | How do you split a dataset too large for one machine, and what breaks when you do? |
| [Replication](replication/README.md) | How do you get read scalability and fault tolerance from copies of the same data, and what does that cost you in consistency? |

These three are deliberately ordered: understand the SQL/NoSQL decision first (it shapes what sharding/replication tools are even available to you), then replication (the simpler, read-scaling technique), then sharding (the harder, write-scaling technique, usually reached for only after replication alone isn't enough).

See also: [06-databases-deep-dive](../../06-databases-deep-dive/README.md) for storage-engine internals (B-trees vs LSM-trees, indexing, transactions) that go one level deeper than this section.
