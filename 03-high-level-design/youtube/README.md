# Design YouTube (Video Upload, Transcoding, and Streaming)

> **The one hard problem this really tests:** the asynchronous transcoding pipeline that converts one uploaded video into many delivery-ready formats/resolutions, combined with global CDN-based delivery for adaptive bitrate streaming — this is a storage-and-pipeline problem far more than a database problem.

---

## 1. Requirements

### Functional
- Users upload videos; the system makes them available for streaming shortly after.
- Viewers can watch videos at a quality appropriate to their device/network (adaptive bitrate).
- Search, recommendations, comments, likes (mention, don't deep-dive unless asked — the video pipeline is the actual hard problem here).
- Video metadata (title, description, thumbnail).

### Non-Functional
- **Massive storage volume** — video is enormous compared to text/metadata; storage architecture must be cost-aware, not just "put it in a database."
- **Read-heavy, globally distributed** — the same popular video is watched millions of times from all over the world; this is a caching/CDN problem at its core.
- **Upload reliability** — large file uploads over unreliable networks must be resumable, not "start over from byte 0" on any hiccup.
- **Transcoding is resource-intensive and must not block the upload response** — a user shouldn't wait minutes staring at a spinner while transcoding happens synchronously.

---

## 2. Back-of-Envelope Estimation

- Assume 500 hours of video uploaded per minute (a commonly cited real-world figure for YouTube's actual scale) — this alone tells you storage growth is the dominant cost driver, not compute or typical database rows.
- A single hour of raw uploaded video, before any transcoding, might be several GB depending on source quality. Multiplied across 500 hours/minute, **raw storage growth is on the order of many terabytes per day**, before accounting for the fact that transcoding produces *multiple additional copies* (several resolutions × several formats/codecs) of every video.
- **Storage cost multiplier from transcoding:** if each video is transcoded into, say, 5 resolutions (e.g., 240p/360p/480p/720p/1080p) in 2 codecs, that's up to 10 derived copies stored per original upload — a huge multiplier on top of the raw ingestion volume, which is exactly why a tiered storage strategy (hot vs cold, discussed below) matters enormously here for cost, unlike almost any other system in this vault.
- Views vastly outnumber uploads — a read:write ratio easily in the millions:1 for popular content, reinforcing that this is fundamentally a **caching and CDN delivery problem** for the read path (see [CDN](../../02-building-blocks/cdn/README.md)).

---

## 3. High-Level Design

```
  1. Upload
  Client ──(chunked, resumable upload)──▶ Upload Service ──▶ Raw video blob storage
                                                │                (object storage: S3-like,
                                                │                 NOT a relational DB)
                                                ▼
                                     Publish "video uploaded" event
                                       to a message queue
                                                │
  2. Async Transcoding Pipeline                ▼
                                     Transcoding Orchestrator
                                     (breaks video into chunks,
                                      dispatches parallel encode
                                      jobs -- see Real-World Example)
                                                │
                              ┌─────────────────┼─────────────────┐
                              ▼                 ▼                 ▼
                     Encode: 240p          Encode: 720p      Encode: 1080p
                     (worker fleet,        (worker fleet)    (worker fleet)
                      horizontally
                      scaled, disposable)
                              │                 │                 │
                              └─────────────────┴─────────────────┘
                                                │
                                     Stitch/package into adaptive
                                     bitrate manifest (e.g., HLS/DASH)
                                                │
                                     Store transcoded renditions in
                                     object storage; update video
                                     metadata status: READY
                                                │
                                     Push/replicate to CDN edge
                                     locations (see CDN)

  3. Playback
  Client ──GET manifest + video chunks──▶ CDN edge (cache hit for popular
                                            content) ──(miss)──▶ Origin
                                            object storage
```

---

## 4. Component Deep Dive: The Transcoding Pipeline

This is the component that makes YouTube architecturally distinct from a typical CRUD system — it's a **large-scale, embarrassingly parallel batch processing pipeline**, directly connected to the [Scalability](../../01-foundations/scalability/README.md#4-real-world-example-netflixs-horizontal-scaling-of-the-video-encoding-pipeline) foundation's discussion of Netflix's near-identical problem.

- **Chunking:** the source video is split into small segments (e.g., a few seconds each). Each segment can be transcoded **independently and in parallel** — this is the parallelizable 90% from Amdahl's Law discussed in the Scalability foundation doc.
- **Parallel, disposable workers:** a large, horizontally-scaled, often spot/preemptible fleet of transcoding workers each picks up chunks (via a work queue — a point-to-point pattern from [Message Queues](../../02-building-blocks/message-queues/README.md)) and encodes them into the required output resolutions/codecs. Workers are stateless and disposable — a worker crashing mid-chunk simply means that chunk's job gets retried by another worker, since chunks are small and idempotent to redo.
- **The serial bottleneck (Amdahl's Law's non-parallelizable part):** after all chunks for a given resolution/codec are encoded, they must be **stitched back together in the correct order** into a final deliverable file/manifest. This reassembly step is inherently sequential (or at least far less parallelizable) and is the part of the pipeline that determines the *minimum possible* time from "upload finished" to "video available," regardless of how many parallel workers are thrown at the encoding step itself.
- **Status tracking:** the video's metadata record moves through states (`UPLOADING → PROCESSING → READY` or `FAILED`), and the upload-confirmation response to the user happens immediately after raw upload completes — the user sees a "processing" state, not a blocked wait, directly avoiding the anti-pattern of synchronous, in-request transcoding.

---

## 5. Adaptive Bitrate Streaming — Connecting to Latency vs Throughput

Once transcoded, video is delivered via **adaptive bitrate streaming** (HLS or MPEG-DASH): the video is available in multiple bitrate/resolution renditions, and the client player **continuously measures actual achieved network throughput** and requests the next chunk at whichever rendition best matches currently available bandwidth — switching up or down dynamically mid-playback.

This is a direct, concrete instance of the [Latency vs Throughput](../../01-foundations/latency-vs-throughput/README.md#5-real-world-example-netflixs-adaptive-bitrate-streaming--choosing-throughput-over-instant-latency) trade-off: a small amount of upfront buffering latency is accepted in exchange for a smoothly sustained playback throughput that adapts to real, currently-observed network conditions rather than being fixed at design time.

---

## 6. Storage Strategy — Hot vs Cold Tiering (Cost Is a First-Class Design Constraint Here)

Unlike most systems in this vault, **storage cost itself** is a primary architectural driver for YouTube-scale video, because of the sheer multiplier effect of storing many renditions of every uploaded video, most of which will only ever be watched a handful of times.

- **Hot tier:** frequently-accessed video (popular/recent uploads) — replicated aggressively to CDN edges (see [CDN](../../02-building-blocks/cdn/README.md)), stored on fast, more expensive storage tiers at origin.
- **Cold tier:** the long tail of rarely-watched videos — kept on cheaper, higher-latency object storage tiers (e.g., S3 Glacier-class storage), fetched to a warmer tier on-demand (pull-CDN-style, cache-aside behavior) if an old video suddenly gets a burst of views (e.g., goes viral again years after upload).
- **Access-pattern-driven tiering is itself a caching decision** — recognizing that this is precisely the same cache-aside/hot-vs-cold pattern from [Caching](../../02-building-blocks/caching/README.md), just applied to object storage economics instead of database query latency, is a strong senior-level connection to draw explicitly in an interview.

---

## 7. Data Model

```sql
CREATE TABLE videos (
    video_id       BIGINT PRIMARY KEY,
    uploader_id    BIGINT NOT NULL,
    title          VARCHAR(200),
    description    TEXT,
    status         ENUM('UPLOADING','PROCESSING','READY','FAILED'),
    duration_sec   INT,
    uploaded_at    TIMESTAMP,
    raw_blob_url   TEXT,      -- pointer into object storage, NOT the video itself
    manifest_url   TEXT NULL  -- populated once transcoding completes -- HLS/DASH manifest location
);

CREATE TABLE video_renditions (
    video_id       BIGINT NOT NULL,
    resolution     VARCHAR(10),   -- '240p', '720p', '1080p', ...
    codec          VARCHAR(10),
    blob_url       TEXT NOT NULL, -- object storage pointer for this specific rendition
    PRIMARY KEY (video_id, resolution, codec)
);
```

The actual video **bytes never live in a relational database row** — only pointers (URLs/keys) into object storage do. This is a common, important clarification to make explicit in an interview: metadata and blob storage are architecturally separate systems with very different scaling characteristics.

---

## 8. API Design

```
POST /api/v1/videos/upload-init
  Response: { "uploadId": "...", "chunkUploadUrls": [...] }
  -- Enables resumable, chunked upload (see below)

PUT /api/v1/videos/upload/{uploadId}/chunk/{chunkIndex}
  -- Each chunk uploaded independently; retryable per-chunk on failure, without
  -- restarting the entire upload -- critical for large files over unreliable networks.

POST /api/v1/videos/upload-complete
  Request: { "uploadId": "..." }
  Response: { "videoId": "...", "status": "PROCESSING" }

GET /api/v1/videos/{videoId}/manifest
  Response: HLS/DASH manifest listing available renditions and chunk URLs
```

---

## 9. Trade-offs & Follow-Up Questions to Anticipate

| Follow-up | Strong answer direction |
|---|---|
| "How do you make uploads resumable over flaky mobile networks?" | Chunked upload with per-chunk acknowledgment (as above); client tracks which chunks succeeded and only retries missing ones, rather than restarting the whole file. |
| "How do you prioritize transcoding for popular vs unpopular uploads?" | A priority queue (or separate queues per priority tier) in the transcoding job dispatch — e.g., a verified/high-subscriber creator's upload might jump the queue relative to an anonymous upload, an explicit, discussable business trade-off. |
| "What if a viral old video needs to move from cold to hot storage instantly?" | On-demand promotion: a cache-miss-like event triggers copying the cold-tier blob to a hot tier / pre-warming CDN edges, similar to the push-CDN pre-warming discussed in the CDN building block for predictable high-demand events — except here triggered reactively rather than proactively. |
| "How would you deduplicate identical or near-identical re-uploads?" | Content-based hashing (perceptual hashing for near-duplicates, exact hashing for byte-identical re-uploads) checked at ingest, potentially avoiding redundant transcoding of content that already exists in the system. |

---

## 10. 60-Second Interview Answer

> "The core challenge here is the transcoding pipeline, not the database — uploaded video needs to be converted into multiple resolutions and codecs for adaptive streaming, and that has to happen asynchronously, since synchronous transcoding in the upload request path would mean users waiting minutes for a response. I'd chunk the video and transcode chunks in parallel across a large, disposable worker fleet — the same embarrassingly-parallel pattern Netflix uses for its encoding pipeline — with a final stitching step that's inherently more sequential and sets the floor on total processing time. For delivery, adaptive bitrate streaming lets the client dynamically pick a rendition based on measured throughput, trading a bit of upfront buffering latency for smooth sustained playback. And because storage cost scales with the number of renditions times upload volume, I'd tier storage — hot, CDN-replicated storage for popular and recent content, cheaper cold storage for the long tail, promoted on-demand if an old video suddenly goes viral again."

**Related:** [Scalability](../../01-foundations/scalability/README.md) · [CDN](../../02-building-blocks/cdn/README.md) · [Latency vs Throughput](../../01-foundations/latency-vs-throughput/README.md) · [Message Queues](../../02-building-blocks/message-queues/README.md) · [Netflix](../netflix/README.md)
