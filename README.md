<div align="center">

<img src="https://readme-typing-svg.demolab.com?font=Fira+Code&weight=700&size=32&pause=1000&color=6366F1&center=true&vCenter=true&width=700&lines=⚙️+System+Design+MasterClass;System+Design+%7C+Built+%26+Documented;From+Concepts+to+Production+Systems" alt="SystemDesignMasterclass" />

<br/>

# System Design Masterclass
### *A living encyclopedia of System Design — concepts, implementations, and real-world architectures, all in one place.*

<br/>

[![Stars](https://img.shields.io/github/stars/anuragpatel23/system-design-masterclass?style=for-the-badge&color=6366f1&labelColor=1e1e2e)](https://github.com/anuragpatel23/system-design-masterclass/stargazers)
[![Forks](https://img.shields.io/github/forks/https://github.com/anuragpatel23/system-design-masterclass?style=for-the-badge&color=10b981&labelColor=1e1e2e)](https://github.com/anuragpatel23/system-design-masterclass/network/members)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=for-the-badge&labelColor=1e1e2e)](CONTRIBUTING.md)
[![License](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge&labelColor=1e1e2e)](LICENSE)
[![Last Commit](https://img.shields.io/github/last-commit/https://github.com/anuragpatel23/system-design-masterclass?style=for-the-badge&color=f59e0b&labelColor=1e1e2e)](https://github.com/anuragpatel23/system-design-masterclass/commits)

<br/>

> **Who is this for?** Senior engineers preparing for staff/principal interviews · Developers leveling up their architecture skills · Anyone who wants to *understand* systems, not just memorize them.

</div>

---

## 📖 What is System Design Masterclass?

Most system design resources give you theory. System Design Masterclass gives you **implementation** alongside it.

Every topic in this vault follows the same pattern:
1. **Concept** — the "why" explained clearly
2. **Design** — diagrams, trade-offs, and decision points
3. **Implementation** — working code or pseudocode
4. **Real-world reference** — how companies like Netflix, Uber, Discord solved it

No fluff. No shallow overviews. Just deep, honest engineering.

---

## 🗂️ Repository Structure

```
system-design-masterclass/
│
├── 📁 01-foundations/               # Core concepts every design builds on
│   ├── scalability/
│   ├── availability-reliability/
│   ├── cap-theorem/
│   ├── consistency-models/
│   ├── latency-vs-throughput/
│   └── README.md
│
├── 📁 02-building-blocks/           # Lego pieces of every large-scale system
│   ├── load-balancers/
│   ├── caching/
│   ├── databases/
│   │   ├── sql-vs-nosql/
│   │   ├── sharding/
│   │   └── replication/
│   ├── message-queues/
│   ├── cdn/
│   ├── api-gateway/
│   ├── rate-limiting/
│   └── README.md
│
├── 📁 03-high-level-design/         # HLD: end-to-end system architectures
│   ├── url-shortener/               # TinyURL, Bitly
│   │   ├── design.md
│   │   ├── architecture.png
│   │   └── implementation/
│   ├── twitter-feed/
│   ├── whatsapp/
│   ├── youtube/
│   ├── uber/
│   ├── netflix/
│   ├── google-drive/
│   ├── search-autocomplete/
│   ├── notification-system/
│   ├── payment-system/
│   ├── hotel-booking/
│   └── README.md
│
├── 📁 04-low-level-design/          # LLD: OOP, design patterns, machine coding
│   ├── design-patterns/
│   │   ├── creational/
│   │   ├── structural/
│   │   └── behavioral/
│   ├── parking-lot/
│   ├── library-management/
│   ├── chess-game/
│   ├── elevator-system/
│   ├── food-delivery-app/
│   ├── lru-cache/
│   └── README.md
│
├── 📁 05-distributed-systems/       # The hard problems in distributed computing
│   ├── consensus-algorithms/
│   │   ├── raft/
│   │   └── paxos/
│   ├── distributed-transactions/
│   ├── event-driven-architecture/
│   ├── saga-pattern/
│   ├── cqrs-event-sourcing/
│   ├── leader-election/
│   └── README.md
│
├── 📁 06-databases-deep-dive/       # Database internals and trade-offs
│   ├── b-trees-lsm-trees/
│   ├── indexing-strategies/
│   ├── transactions-acid/
│   ├── database-scaling/
│   └── README.md
│
├── 📁 07-real-world-architectures/  # Reverse-engineered from engineering blogs
│   ├── discord-messages/
│   ├── instagram-stories/
│   ├── airbnb-search/
│   ├── linkedin-feed/
│   ├── slack-real-time/
│   └── README.md
│
├── 📁 08-api-design/                # Designing clean, scalable APIs
│   ├── rest-best-practices/
│   ├── graphql-design/
│   ├── grpc-design/
│   ├── websockets/
│   ├── pagination-patterns/
│   └── README.md
│
├── 📁 09-interview-prep/            # Structured interview playbook
│   ├── interview-framework.md
│   ├── common-mistakes.md
│   ├── estimation-cheatsheet.md
│   ├── question-bank.md
│   └── README.md
│
├── 📁 10-resources/                 # Curated books, blogs, videos, papers
│   ├── books.md
│   ├── papers.md
│   ├── engineering-blogs.md
│   ├── youtube-channels.md
│   └── README.md
│
├── 📄 CONTRIBUTING.md
├── 📄 LICENSE
└── 📄 README.md                     ← You are here
```

---

## 🧭 How to Navigate This Vault

> **Not sure where to start?** Use this guide based on your goal.

| Goal | Start Here |
|------|-----------|
| 🆕 New to system design | `01-foundations` → `02-building-blocks` |
| 🎯 Interview in 2 weeks | `09-interview-prep` → `03-high-level-design` |
| 🔩 Preparing for LLD round | `04-low-level-design` |
| 🌐 Distributed systems deep dive | `05-distributed-systems` |
| 🏢 Real architecture case studies | `07-real-world-architectures` |
| 📚 Building long-term knowledge | Read everything, in order |

---

## ✅ Design Coverage Tracker

Each system follows a consistent structure. Here's the current build status:

### 🏗️ High-Level Designs

| System | Concept | Diagram | Implementation | Real-World Ref |
|--------|---------|---------|----------------|----------------|
| URL Shortener | ✅ | ✅ | ✅ | ✅ |
| Twitter Feed | ✅ | ✅ | ✅ | 🔄 |
| WhatsApp | ✅ | ✅ | 🔄 | ✅ |
| YouTube | ✅ | 🔄 | 🔄 | ✅ |
| Uber | 🔄 | 🔄 | ⬜ | 🔄 |
| Netflix | 🔄 | ⬜ | ⬜ | ✅ |
| Google Drive | ⬜ | ⬜ | ⬜ | ⬜ |
| Notification System | ⬜ | ⬜ | ⬜ | ⬜ |
| Payment System | ⬜ | ⬜ | ⬜ | ⬜ |

> ✅ Done &nbsp;&nbsp; 🔄 In Progress &nbsp;&nbsp; ⬜ Planned

### 🔩 Low-Level Designs

| System | Design | Code (Java) | Code (Python) | Patterns Used |
|--------|--------|-------------|---------------|---------------|
| Parking Lot | ✅ | ✅ | ✅ | Strategy, Singleton |
| LRU Cache | ✅ | ✅ | ✅ | — |
| Chess Game | ✅ | 🔄 | ⬜ | Observer, Command |
| Elevator System | 🔄 | ⬜ | ⬜ | State, Strategy |
| Library Management | ⬜ | ⬜ | ⬜ | — |

---

## 📐 Design Template

Every design in this vault follows this exact format so you always know what to expect:

```
system-name/
│
├── README.md              # Overview, scope, assumptions
├── design.md              # Full design document
│   ├── 1. Requirements    # Functional & non-functional
│   ├── 2. Estimations     # Scale, storage, bandwidth math
│   ├── 3. HLD Diagram     # High-level architecture
│   ├── 4. Component Deep Dive
│   ├── 5. Data Model      # Schema / data structures
│   ├── 6. API Design      # Key endpoints
│   ├── 7. Trade-offs      # Decisions and why
│   └── 8. Further Reading
│
├── architecture.png       # Clean architecture diagram
│
└── implementation/        # Code (where applicable)
    ├── Java/
    └── Python/
```

---

## 🔑 Key Concepts Quick Reference

<details>
<summary><strong>📊 CAP Theorem</strong></summary>

A distributed system can guarantee only **2 of 3**:

- **C**onsistency — every read gets the most recent write
- **A**vailability — every request gets a response
- **P**artition Tolerance — system works despite network failures

Since partition tolerance is non-negotiable in distributed systems, the real choice is **CP vs AP**.

→ [Full deep dive](01-foundations/cap-theorem/README.md)

</details>

<details>
<summary><strong>⚡ Caching Strategies</strong></summary>

| Strategy | When to use | Risk |
|----------|-------------|------|
| Cache-aside | Read-heavy, occasional updates | Cache miss on cold start |
| Write-through | Consistency critical | Write latency |
| Write-behind | Write-heavy workloads | Data loss on crash |
| Read-through | Simplicity needed | Cache miss penalty |

→ [Full deep dive](02-building-blocks/caching/README.md)

</details>

<details>
<summary><strong>🗄️ SQL vs NoSQL — When to choose what</strong></summary>

**Choose SQL when:** ACID compliance needed · Complex queries · Structured, relational data

**Choose NoSQL when:** Massive scale · Flexible schema · High write throughput · Key-value / document / graph data models

→ [Full deep dive](02-building-blocks/databases/sql-vs-nosql/README.md)

</details>

<details>
<summary><strong>📬 Message Queue Patterns</strong></summary>

- **Point-to-point** (Queues) — one consumer per message (SQS)
- **Pub/Sub** — multiple consumers per message (Kafka, SNS)
- **Dead Letter Queue** — handle failed messages gracefully

→ [Full deep dive](02-building-blocks/message-queues/README.md)

</details>

---

## 🎯 Interview Framework (RESHADED)

When answering any system design question, use this checklist:

```
R — Requirements        Clarify functional & non-functional needs
E — Estimation          Back-of-envelope: users, QPS, storage
S — Storage             Choose the right database(s) and why
H — High-Level Design   Draw the big picture first
A — APIs                Define key endpoints
D — Data Model          Schema, entities, relationships
E — Extended Design     Deep dive: caching, sharding, failover
D — Discussion          Trade-offs, bottlenecks, future scaling
```

→ [Full interview playbook](09-interview-prep/interview-framework.md)

---

## 📚 Recommended Learning Path

```
Week 1–2   ┌─────────────────────────────────┐
           │  01-foundations                  │
           │  02-building-blocks              │
           └─────────────────────────────────┘
                          ↓
Week 3–4   ┌─────────────────────────────────┐
           │  03-high-level-design (5 systems)│
           │  09-interview-prep (framework)   │
           └─────────────────────────────────┘
                          ↓
Week 5–6   ┌─────────────────────────────────┐
           │  04-low-level-design             │
           │  05-distributed-systems          │
           └─────────────────────────────────┘
                          ↓
Ongoing    ┌─────────────────────────────────┐
           │  07-real-world-architectures     │
           │  06-databases-deep-dive          │
           │  10-resources (papers & blogs)   │
           └─────────────────────────────────┘
```

---

## 🌍 Real-World Engineering Blogs Worth Reading

> Referenced throughout this vault. Bookmark all of them.

| Company | Blog |
|---------|------|
| Netflix | [netflixtechblog.com](https://netflixtechblog.com) |
| Uber | [eng.uber.com](https://eng.uber.com) |
| Discord | [discord.com/blog](https://discord.com/blog/engineering) |
| Airbnb | [medium.com/airbnb-engineering](https://medium.com/airbnb-engineering) |
| LinkedIn | [engineering.linkedin.com](https://engineering.linkedin.com/blog) |
| Meta | [engineering.fb.com](https://engineering.fb.com) |
| Figma | [figma.com/blog/engineering](https://www.figma.com/blog/section/engineering) |
| Shopify | [shopify.engineering](https://shopify.engineering) |

---

## 🤝 Contributing

Contributions are what make this vault grow. Here's how to help:

1. **Fork** the repository
2. Create a feature branch: `git checkout -b add/twitter-hld`
3. Follow the [design template](#-design-template) for consistency
4. Submit a **Pull Request** with a clear description

Please read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting.

**Good first contributions:**
- Add a missing diagram to an existing design
- Add implementation code for a designed system
- Add a real-world reference with a link + summary
- Fix incorrect information with a source

---

## ⭐ Show Your Support

If this vault helped you land an interview, understand a system better, or just saved you hours of searching — **drop a star**. It helps others find this resource.

[![Star this repo](https://img.shields.io/github/stars/YOUR_USERNAME/archvault?style=social)](https://github.com/anuragpatel23/system-design-masterclass)

---

## 📄 License

This project is licensed under the **MIT License** — see [LICENSE](LICENSE) for details.

---

<div align="center">

*Built with ❤️ for engineers who care about building things that scale.*

**[⬆ Back to top](#)**

</div>