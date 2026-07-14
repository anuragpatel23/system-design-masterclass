# Design Google Drive (Cloud File Storage & Sync)

> **The one hard problem this really tests:** efficiently storing and syncing files across multiple devices — using **chunking/deduplication** to avoid re-uploading unchanged data, and handling **conflicting concurrent edits** from multiple devices/collaborators without silently losing data.

---

## 1. Requirements

### Functional
- Upload, download, organize (folders), and share files.
- Sync across multiple devices — edit a file on your laptop, see the change reflected on your phone.
- Versioning — recover a previous version of a file.
- Collaborative editing (mention it; real-time operational-transform/CRDT-based collaborative editing, as in Google Docs, is a distinct and deeper sub-problem worth flagging as out of scope unless explicitly asked to go there).

### Non-Functional
- **Storage efficiency** — avoid re-uploading/re-storing unchanged bytes when a large file is only slightly modified.
- **Bandwidth efficiency for sync** — a user on a slow connection editing a large file shouldn't need to re-upload the whole file for a small change.
- **Consistency across devices** — devices should eventually converge to the same file state; conflicting simultaneous edits from two devices need a defined resolution strategy, not silent data loss.
- **High durability** — users treat this as their source of truth for irreplaceable data; data loss is unacceptable (much stronger durability requirement than most systems in this vault).

---

## 2. Back-of-Envelope Estimation

- Assume 1 billion users, average 10GB stored each → 10 exabytes of total storage — this scale immediately rules out any single-machine or even single-datacenter storage approach; this is squarely an **object storage** problem (see the storage-tiering discussion in [YouTube](../youtube/README.md#6-storage-strategy--hot-vs-cold-tiering-cost-is-a-first-class-design-constraint-here)), not a database-rows problem.
- Assume a much smaller fraction of files are actively edited/synced at any given moment vs simply stored at rest — the sync/edit-detection path and the bulk-storage path have very different access patterns and should be architected somewhat independently, similar to the location-update-vs-matching split in [Uber](../uber/README.md#2-back-of-envelope-estimation).
- File sizes vary enormously (a few KB text file vs a multi-GB video) — a fixed-size chunking strategy (discussed below) needs to handle this range efficiently.

---

## 3. Component Deep Dive: Chunking Files for Efficient Sync and Deduplication (the actual hard problem)

Rather than treating a file as one indivisible blob, split every file into **fixed-size chunks** (e.g., 4MB each) before storage, and store/sync at the chunk level rather than the whole-file level.

- **Efficient re-sync on edit:** if a user changes a small part of a large file, only the chunk(s) actually containing the changed bytes differ from the previously-synced version — the client can diff its local chunk hashes against the previously-uploaded chunk hashes and **only upload the changed chunks**, not the entire file. This is the single most important bandwidth optimization in the whole system, and directly why "chunking" is the headline of this design.
- **Deduplication:** each chunk is content-addressed — its storage key is a hash of its own content (e.g., SHA-256). If two different files (or two different users' files entirely) happen to contain an identical chunk of bytes, that chunk is **physically stored only once**, with both files' metadata simply referencing the same chunk hash. This can produce enormous storage savings in aggregate (common OS files, common email attachments, etc., are duplicated across millions of users' accounts without content-addressed deduplication).
- **Chunk size trade-off:** smaller chunks improve deduplication granularity and reduce re-upload size on small edits, but increase the number of chunk-metadata entries to track and the number of round trips needed to reconstruct/sync a file — a real, discussable trade-off, not a free parameter to wave away (worth naming explicitly if pushed on "why 4MB and not 64KB or 64MB").

---

## 4. High-Level Design

```mermaid
graph TD
    Client([Desktop Sync Agent])
    LB[Load Balancer]

    subgraph SyncTier["Sync/Metadata Tier — Auto Scaling Group"]
        SyncSvc[Sync Service]
        MetadataSvc[Metadata Service]
    end

    MetadataDB[(File Metadata DB<br/>versions, chunk hash lists)]
    ChunkStorage[(Chunk Storage<br/>content-addressed object storage)]
    MQ[[Message Queue<br/>"file changed" event]]
    OtherDevices([Other Registered Devices])

    Client -->|"1. local file change detected"| Client
    Client -->|"2. POST /sync-check (local chunk hashes)"| LB --> SyncSvc
    SyncSvc -->|"3. diff against current version"| MetadataDB
    MetadataDB -.->|"chunksNeeded: [...]"| Client

    Client -->|"4. PUT only the changed chunks"| ChunkStorage
    Client -->|"5. POST /commit-version"| LB --> MetadataSvc
    MetadataSvc -->|"6. update version record"| MetadataDB
    MetadataSvc -->|"7. publish change event"| MQ
    MQ -.->|notify| OtherDevices
    OtherDevices -->|"8. diff + pull only missing chunks"| ChunkStorage

    style ChunkStorage fill:#a8d5ff,stroke:#333
    style MetadataDB fill:#f9d976,stroke:#333
    style MQ fill:#c9f7d1,stroke:#333
```

**Take this as the reference shape of the whole system** — notice that at no point does a full file ever move as a single unit; every arrow that crosses the network carries only chunk hashes or the specific chunks that changed, which is the chunking/deduplication idea from §3 made concrete as an actual request flow.

**Step by step:**
1. The **Desktop Sync Agent** detects a local file change (a file-watcher process) and splits the changed file into fixed-size chunks, hashing each one.
2. It calls **`POST /sync-check`** with its base version and full list of local chunk hashes; the **Sync Service** compares this against the file's current version in the **Metadata DB** and returns only the hashes the client actually needs to upload — unchanged chunks are never even mentioned again.
3. The client uploads **only the changed chunks** to **Chunk Storage** — a content-addressed object store where the key is the chunk's own hash, so a chunk identical to one already stored anywhere in the system (even in a completely different user's file) is automatically deduplicated.
4. The client then calls **`POST /commit-version`**, and the **Metadata Service** validates the base version hasn't moved since step 2 (the optimistic-concurrency check from §5) before writing the new version record.
5. On success, the Metadata Service publishes a **"file changed" event** onto the **Message Queue** — every other device registered to the account is notified asynchronously.
6. Each other device independently runs the same diff-and-fetch logic in reverse: it compares the new version's chunk list against what it already has cached locally, and pulls **only the missing chunks** — the same bandwidth-saving principle applied to download instead of upload.

---

## 5. Component Deep Dive: Conflict Detection and Resolution

Two devices editing the same file while offline (or in a race condition even while both online) will eventually both attempt to push a new version. This system needs an explicit strategy — silently letting the last write win *can* be acceptable for some products, but for a product where users treat data as precious and irreplaceable, more care is warranted:

- **Versioning via a monotonic version number or vector clock per file:** each device tracks the version it last synced. When pushing an update, the client includes "I'm updating from version N." If the server's current version is still N (no one else has changed it since), the update is accepted as version N+1. If the server's version is already N+1 or higher (someone else pushed a change first), the server **rejects the naive overwrite** and returns a conflict.
- **Conflict resolution options, given a detected conflict:**
  - **Last-write-wins** (simplest, real data-loss risk — generally not acceptable for a product like this without more care).
  - **Keep both versions** (the common real-world approach — e.g., save the conflicting version as "filename (conflicted copy from Device B, 2026-07-07).ext"), preserving both sets of changes for the user to manually reconcile, rather than silently discarding either.
  - **Automatic merge** (only feasible for structured, mergeable content — e.g., certain document formats with operational-transform/CRDT support, as in real-time collaborative editors) — explicitly a much deeper, separate problem worth flagging as out of scope for a general file-storage design unless asked to go there.

**Senior-level answer:** "keep both versions as a conflicted copy" is the pragmatic, broadly-applicable default for a general-purpose file storage/sync product (this is, in fact, publicly how several major cloud storage products actually behave), because it guarantees **no silent data loss** — the worst outcome is mild user annoyance at needing to manually reconcile two files, never the far worse outcome of one person's edits vanishing without any indication it happened.

---

## 6. Components Used — What Each Piece Is and Why It's Here

| Component | Role in This Design | Why This Choice, Here Specifically | Deep Dive |
|---|---|---|---|
| **Load Balancer** | Fronts the Sync Service and Metadata Service, distributing sync-check and commit requests | Standard L7 HTTP routing with health checks; sync traffic is bursty (a user reconnecting after being offline can trigger a large batch of sync-checks at once) | [Load Balancers](../../02-building-blocks/load-balancers/README.md) |
| **Auto Scaling Group (Sync/Metadata Tier)** | Runs the stateless Sync Service and Metadata Service | Each request is self-contained (a version check, a diff, a commit) — no session state held between requests, so this tier scales horizontally with active sync volume | [Scalability](../../01-foundations/scalability/README.md) |
| **Metadata DB** | Authoritative store of file/folder structure, permissions, and the ordered chunk-hash list per version | A relational store fits because folder hierarchy and sharing permissions genuinely need referential integrity and multi-row transactional guarantees, unlike the chunk bytes themselves | [SQL vs NoSQL](../../02-building-blocks/databases/sql-vs-nosql/README.md) |
| **Chunk Storage (content-addressed object storage)** | Stores the actual file bytes, chunked and keyed by content hash | Content-addressing gives automatic, system-wide deduplication as a free property of the storage key itself, and at the exabyte scale estimated in §2, this must be object storage, not a relational table | [CDN](../../02-building-blocks/cdn/README.md) (storage-tiering pattern) |
| **Message Queue** | Carries "file changed" events from the device that made the edit to every other device registered on the account | Decouples "commit succeeded" from "every other device has been told" — a temporarily offline device simply catches up once reconnected, rather than the committing device waiting on every device's live acknowledgment | [Message Queues](../../02-building-blocks/message-queues/README.md) |

---

## 7. Data Model

```sql
-- File metadata: relational, needs referential integrity (folder hierarchy,
-- sharing permissions) and supports versioning via a simple append-only version table.
CREATE TABLE files (
    file_id       BIGINT PRIMARY KEY,
    owner_id      BIGINT NOT NULL,
    folder_id     BIGINT NULL,
    name          VARCHAR(255),
    current_version_id BIGINT NOT NULL
);

CREATE TABLE file_versions (
    version_id    BIGINT PRIMARY KEY,
    file_id       BIGINT NOT NULL,
    version_number INT NOT NULL,
    chunk_hashes  TEXT[],    -- ordered list of chunk hashes composing this version
    created_at    TIMESTAMP,
    created_by_device VARCHAR(100)
);

-- Chunk storage: content-addressed object storage, NOT relational rows for the
-- actual bytes -- the metadata table just tracks reference counts for garbage
-- collection (a chunk is only physically deleted once no file version anywhere
-- references it anymore).
CREATE TABLE chunk_references (
    chunk_hash    VARCHAR(64) PRIMARY KEY,   -- SHA-256 hex
    reference_count BIGINT NOT NULL DEFAULT 0,
    storage_url   TEXT NOT NULL
);
```

---

## 8. API Design

```
POST /api/v1/files/{fileId}/sync-check
  Request:  { "baseVersion": 5, "chunkHashes": ["a1b2...", "c3d4...", ...] }
  Response: { "chunksNeeded": ["c3d4..."], "conflict": false }
  -- Server compares client's baseVersion against its own current version and
  -- returns ONLY the chunk hashes the client doesn't already have (or need to send).

PUT /api/v1/chunks/{chunkHash}
  -- Idempotent by construction: uploading a chunk whose hash already exists in
  -- storage is a no-op (deduplication) -- safe to retry without side effects,
  -- a nice free property directly from content-addressing.

POST /api/v1/files/{fileId}/commit-version
  Request: { "baseVersion": 5, "newChunkHashes": [...] }
  Response: { "newVersion": 6 } OR { "conflict": true, "conflictedCopyId": "..." }
```

---

## 9. Trade-offs & Follow-Up Questions to Anticipate

| Follow-up | Strong answer direction |
|---|---|
| "How do you handle a very large file (multi-GB video) upload reliably?" | Same chunked, resumable upload pattern as [YouTube](../youtube/README.md#8-api-design) — each chunk uploaded and acknowledged independently, retryable per-chunk on network failure. |
| "How do you garbage-collect orphaned chunks (e.g., after a file is deleted)?" | Reference counting on the chunk table (as modeled above) — decrement on file/version deletion, and only physically delete the chunk from storage once its reference count hits zero, run as an asynchronous background sweep rather than a synchronous delete-time operation. |
| "How would you support real-time collaborative editing (like Google Docs)?" | This moves beyond simple chunk-diff-based sync into operational transformation or CRDTs, which merge concurrent character-level edits automatically and continuously rather than detecting conflicts at a whole-file-version level — an explicitly deeper, separate problem worth naming but not designing in full unless specifically asked. |
| "How do you handle sharing/permissions efficiently?" | A separate permissions/ACL table keyed by file or folder ID, checked on every access — folder-level permission inheritance (a shared folder's permission applies to all files within it) is a common, worthwhile optimization to mention, avoiding a permission-check per individual file in a large shared folder. |

---

## 10. 60-Second Interview Answer

> "The core techniques here are chunking and content-addressed deduplication. Splitting every file into fixed-size chunks, each identified by a hash of its own content, means a small edit to a large file only requires re-uploading the changed chunks, not the whole file — and if two different files anywhere in the system happen to share an identical chunk, it's physically stored only once. For sync, each device tracks the last version it synced and diffs against the server's current chunk list to fetch or push only what's missing. For conflicting concurrent edits — two devices editing the same file while offline — I'd detect the conflict via a version number the client must present when committing a change, and on conflict, preserve both versions as a 'conflicted copy' rather than silently picking a winner, since silent data loss is unacceptable for a product where users treat their files as irreplaceable."

**Related:** [Message Queues](../../02-building-blocks/message-queues/README.md) · [Consistency Models](../../01-foundations/consistency-models/README.md) · [YouTube](../youtube/README.md) · [CAP Theorem](../../01-foundations/cap-theorem/README.md)
