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

```
  Client (Desktop Sync Agent) ──▶ File Watcher detects local change
                                          │
                                  Split changed file into chunks,
                                  compute SHA-256 hash per chunk
                                          │
                                  Compare against LOCAL cache of
                                  previously-synced chunk hashes
                                  for this file
                                          │
                          ┌───────────────┴────────────────┐
                          │ chunk hash unchanged             │ chunk hash is NEW/changed
                          ▼                                  ▼
                  Skip -- chunk already                Upload ONLY this chunk to
                  synced, no upload needed              Chunk Storage Service
                                                                │
                                                        Content-addressed object
                                                        storage: key = chunk hash
                                                        (natural deduplication --
                                                        if hash already exists in
                                                        storage, skip the actual
                                                        write, just reference it)
                                                                │
                                                        Update file's Metadata
                                                        Service record: new
                                                        ordered list of chunk
                                                        hashes representing the
                                                        file's new version
                                                                │
                                                        Publish "file changed"
                                                        event (see Message Queues)
                                                        to notify OTHER devices
                                                        registered to this account
                                                                │
                          Other devices, on receiving the notification, diff their
                          own local chunk cache against the new metadata's chunk
                          list, and pull ONLY the chunks they don't already have
                          locally (same "diff and fetch only what's missing"
                          principle, applied to download instead of upload)
```

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

## 6. Data Model

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

## 7. API Design

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

## 8. Trade-offs & Follow-Up Questions to Anticipate

| Follow-up | Strong answer direction |
|---|---|
| "How do you handle a very large file (multi-GB video) upload reliably?" | Same chunked, resumable upload pattern as [YouTube](../youtube/README.md#8-api-design) — each chunk uploaded and acknowledged independently, retryable per-chunk on network failure. |
| "How do you garbage-collect orphaned chunks (e.g., after a file is deleted)?" | Reference counting on the chunk table (as modeled above) — decrement on file/version deletion, and only physically delete the chunk from storage once its reference count hits zero, run as an asynchronous background sweep rather than a synchronous delete-time operation. |
| "How would you support real-time collaborative editing (like Google Docs)?" | This moves beyond simple chunk-diff-based sync into operational transformation or CRDTs, which merge concurrent character-level edits automatically and continuously rather than detecting conflicts at a whole-file-version level — an explicitly deeper, separate problem worth naming but not designing in full unless specifically asked. |
| "How do you handle sharing/permissions efficiently?" | A separate permissions/ACL table keyed by file or folder ID, checked on every access — folder-level permission inheritance (a shared folder's permission applies to all files within it) is a common, worthwhile optimization to mention, avoiding a permission-check per individual file in a large shared folder. |

---

## 9. 60-Second Interview Answer

> "The core techniques here are chunking and content-addressed deduplication. Splitting every file into fixed-size chunks, each identified by a hash of its own content, means a small edit to a large file only requires re-uploading the changed chunks, not the whole file — and if two different files anywhere in the system happen to share an identical chunk, it's physically stored only once. For sync, each device tracks the last version it synced and diffs against the server's current chunk list to fetch or push only what's missing. For conflicting concurrent edits — two devices editing the same file while offline — I'd detect the conflict via a version number the client must present when committing a change, and on conflict, preserve both versions as a 'conflicted copy' rather than silently picking a winner, since silent data loss is unacceptable for a product where users treat their files as irreplaceable."

**Related:** [Message Queues](../../02-building-blocks/message-queues/README.md) · [Consistency Models](../../01-foundations/consistency-models/README.md) · [YouTube](../youtube/README.md) · [CAP Theorem](../../01-foundations/cap-theorem/README.md)
