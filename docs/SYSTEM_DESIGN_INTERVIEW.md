# URL Shortener — System Design Interview Guide

A complete preparation guide for the "Design a URL Shortener" interview question, grounded in the actual implementation in this repository.

---

## Table of Contents

1. [How to Approach the Interview](#how-to-approach-the-interview)
2. [Step 1: Clarify Requirements](#step-1-clarify-requirements)
3. [Step 2: Back-of-the-Envelope Estimation](#step-2-back-of-the-envelope-estimation)
4. [Step 3: High-Level Design](#step-3-high-level-design)
5. [Step 4: Deep Dive — Short Code Generation](#step-4-deep-dive--short-code-generation)
6. [Step 5: Deep Dive — Database Design](#step-5-deep-dive--database-design)
7. [Step 6: Deep Dive — Caching Strategy](#step-6-deep-dive--caching-strategy)
8. [Step 7: Deep Dive — Redirect Flow](#step-7-deep-dive--redirect-flow)
9. [Step 8: Deep Dive — Analytics](#step-8-deep-dive--analytics)
10. [Step 9: Scaling the System](#step-9-scaling-the-system)
11. [Step 10: Advanced Topics](#step-10-advanced-topics)
12. [Common Interview Questions & Answers](#common-interview-questions--answers)
13. [Mistakes to Avoid](#mistakes-to-avoid)
14. [5-Minute Summary Script](#5-minute-summary-script)

---

## How to Approach the Interview

The URL shortener question tests your ability to design a seemingly simple system that hides real complexity in scale, availability, and consistency. Interviewers evaluate:

1. **Requirement gathering** — Do you ask questions before jumping to solutions?
2. **Trade-off reasoning** — Can you explain WHY you chose an approach, not just WHAT?
3. **Scale awareness** — Do you think about what happens at 1M requests/second?
4. **Depth on demand** — Can you go deep when the interviewer probes?

**Time management (45-minute interview):**
- 5 min: Requirements & estimation
- 10 min: High-level design
- 20 min: Deep dives (interviewer will guide which areas)
- 5 min: Scaling & trade-offs
- 5 min: Questions back to interviewer

---

## Step 1: Clarify Requirements

Always ask these before designing. Never assume.

### Functional Requirements

| Question to Ask | Typical Answer | Impact on Design |
|----------------|----------------|-----------------|
| "What's the primary use case — shortening or redirecting?" | Read-heavy (100:1 read:write ratio) | Cache-heavy architecture |
| "Should users be able to pick custom aliases?" | Yes | Need alias uniqueness check |
| "Do URLs expire?" | Yes, configurable | TTL field + expiration logic |
| "Do we need analytics (click counts, location)?" | Yes | Event pipeline needed |
| "Should users be able to delete/deactivate URLs?" | Yes | Soft delete, cache invalidation |
| "Do we need user accounts/authentication?" | For V2, not MVP | Simplifies initial design |
| "What characters are allowed in the short code?" | [a-zA-Z0-9] | 62 characters per position |
| "How long should the short code be?" | 6-8 characters | Determines total capacity |

### Non-Functional Requirements

| Question to Ask | Typical Answer | Impact on Design |
|----------------|----------------|-----------------|
| "What's the expected scale?" | 100M URLs/month, 10B reads/month | Need horizontal scaling |
| "What's the latency requirement for redirects?" | < 100ms p99 | Must cache aggressively |
| "What's more important — availability or consistency?" | Availability (AP system) | Eventual consistency OK |
| "What's the durability requirement?" | URLs must never be lost | Replicated database |
| "What's the expected URL length?" | Average ~200 chars | Affects storage calculation |

**In our implementation:**
- Custom aliases: supported via `customAlias` field
- Expiration: supported via `expiresInHours`
- Analytics: Kafka-based click event pipeline
- Soft delete: `active` flag on the URL entity
- No authentication: simplified MVP

---

## Step 2: Back-of-the-Envelope Estimation

Interviewers love seeing you do quick math. Practice these calculations.

### Scenario: 100M new URLs per month

```
Write rate:
  100M URLs/month
  = 100M / (30 × 24 × 3600) ≈ ~40 URLs/second

Read rate (100:1 ratio):
  = 40 × 100 = ~4,000 redirects/second

Storage per URL:
  short_code:    7 bytes
  original_url:  200 bytes (average)
  timestamps:    16 bytes
  metadata:      50 bytes
  ≈ 300 bytes per record

Storage for 5 years:
  100M/month × 12 × 5 = 6 billion URLs
  6B × 300 bytes = 1.8 TB
  → Fits on a single modern SSD, but we'll want replication

Cache sizing (80-20 rule):
  20% of URLs get 80% of traffic
  Hot URLs: 6B × 20% = 1.2B
  Cache size: 1.2B × 300 bytes = 360 GB
  → Need distributed cache (Redis cluster)

Short code capacity:
  [a-zA-Z0-9] = 62 characters
  6 chars: 62^6 = 56.8 billion (enough for decades)
  7 chars: 62^7 = 3.5 trillion (what we use)

Bandwidth:
  Read: 4000 req/s × 300 bytes = 1.2 MB/s (negligible)
  Redirect response is tiny (just a 302 + Location header)
```

### Cheat Sheet for Quick Math

```
1 day   = 86,400 seconds ≈ 10^5 seconds
1 month = 2.6M seconds   ≈ 2.5 × 10^6 seconds
1 year  = 31.5M seconds  ≈ 3 × 10^7 seconds

62^6 = 56.8 billion
62^7 = 3.5 trillion
62^8 = 218 trillion
```

---

## Step 3: High-Level Design

Draw this on the whiteboard:

```
                              ┌─────────────┐
                              │   DNS /     │
                    Users ───>│   Load      │
                              │   Balancer  │
                              └──────┬──────┘
                                     │
                    ┌────────────────┬┴───────────────┐
                    │                │                 │
                    ▼                ▼                 ▼
              ┌──────────┐   ┌──────────┐      ┌──────────┐
              │ App      │   │ App      │      │ App      │
              │ Server 1 │   │ Server 2 │ ...  │ Server N │
              └─────┬────┘   └─────┬────┘      └─────┬────┘
                    │              │                  │
         ┌──────────┴──────────────┴──────────────────┘
         │                    │                    │
         ▼                    ▼                    ▼
   ┌──────────┐        ┌──────────┐        ┌──────────────┐
   │  Redis   │        │PostgreSQL│        │    Kafka     │
   │  Cache   │        │(Primary +│        │  (Analytics) │
   │ (Cluster)│        │ Replicas)│        │              │
   └──────────┘        └──────────┘        └──────────────┘
```

**Explain each component's role:**
- **Load Balancer**: Distributes requests across stateless app servers
- **App Servers**: Handle HTTP requests, business logic, stateless (can scale horizontally)
- **Redis Cache**: Stores hot URL mappings, eliminates DB reads for popular URLs
- **PostgreSQL**: Source of truth for all URL data, handles writes
- **Kafka**: Decouples click event recording from the redirect hot path

**In our implementation:**
- Single app server (scales via Docker replicas)
- Redis with graceful degradation (`CacheService` catches connection failures)
- PostgreSQL with Flyway migrations
- Kafka with KRaft (no ZooKeeper)

---

## Step 4: Deep Dive — Short Code Generation

This is the most frequently probed area. Know multiple approaches and their trade-offs.

### Approach 1: Random Generation (What We Implemented)

```
How it works:
  1. Generate random 7-char string from [a-zA-Z0-9]
  2. Check if code exists in DB
  3. If collision, retry (up to 5 times)
  4. Save with UNIQUE constraint as safety net

     SecureRandom ──> "aB3xK9m" ──> EXISTS? ──No──> INSERT
                                      │
                                     Yes
                                      │
                                      ▼
                                    Retry (up to 5x)
```

| Pros | Cons |
|------|------|
| Simple to implement | Collision risk increases as DB fills |
| Unpredictable codes (security) | Requires DB check on every create |
| No coordination needed | At 50% capacity, ~50% collision rate |
| Works with multiple servers | |

**When to use:** Low-to-medium scale, when code unpredictability matters.

**Our implementation details:**
- `ShortCodeGenerator` uses `SecureRandom` (cryptographically strong)
- `UrlService.generateUniqueCode()` retries up to 5 times
- Database `UNIQUE` constraint catches races between concurrent requests
- `DataIntegrityViolationException` converts to `ShortCodeConflictException`

### Approach 2: Counter-Based (Base62 Encoding)

```
How it works:
  1. Maintain a global auto-increment counter
  2. Encode counter value in Base62
  3. Each number maps to exactly one code (no collisions)

     Counter: 1000000 ──> Base62 ──> "4c92" ──> INSERT (no check needed)
```

| Pros | Cons |
|------|------|
| Zero collisions | Predictable (sequential) codes |
| No DB existence check | Need distributed counter (hard to scale) |
| Guaranteed unique | Single point of failure |
| Fast code generation | Codes are guessable |

**When to use:** Very high scale, when collision avoidance is critical.

**How to scale the counter:**
- **Approach A: Range-based** — Each server gets a pre-allocated range (Server 1: 1-1M, Server 2: 1M-2M). When exhausted, request a new range from a coordinator (ZooKeeper, etcd).
- **Approach B: Multiple sequences** — Each server appends its server ID to make codes unique. Server 1 generates codes ending in "1", Server 2 in "2", etc.

### Approach 3: Hash-Based (MD5/SHA256 + Truncate)

```
How it works:
  1. Hash the original URL: MD5("https://example.com") = "1a79a4d60de6718e..."
  2. Take first 7 characters: "1a79a4d"
  3. If collision, take next 7: "60de671"

     URL ──> MD5 ──> first 7 chars ──> EXISTS? ──No──> INSERT
                                         │
                                        Yes
                                         │
                                    Shift window or append salt
```

| Pros | Cons |
|------|------|
| Same URL → same code (dedup) | Hash collisions possible |
| Deterministic | Need collision resolution |
| No counter coordination | Same URL always maps to same code (may not want) |

**When to use:** When URL deduplication is a requirement.

### Approach 4: Pre-Generated Key Pool

```
How it works:
  1. Background worker generates millions of unique codes
  2. Stores them in a "key_pool" table marked "available"
  3. On create: grab one code, mark as "used"
  4. No collision checking needed at request time

     key_pool table:
     | code    | status    |
     | aB3xK9m | available |  ←── grab this one
     | xY7mN2p | used      |
     | ...     | ...       |
```

| Pros | Cons |
|------|------|
| Zero collision at request time | Extra infrastructure (key generation service) |
| Very fast creates | Need to manage pool size |
| Decouples generation from serving | Wasted codes if pool is over-provisioned |

**When to use:** Very high write throughput, when create latency must be minimal.

### Comparison Table (Interview Gold)

| Approach | Collision Risk | Scalability | Predictability | Complexity |
|----------|---------------|-------------|----------------|------------|
| Random | Low (retries) | High | Unpredictable | Low |
| Counter | None | Medium (needs coordination) | Sequential | Medium |
| Hash | Possible | High | Deterministic | Medium |
| Key Pool | None at runtime | Very High | Random | High |

**What the interviewer wants to hear:**
> "I'd start with random generation for its simplicity, but if we need to handle 100K writes/second, I'd switch to a pre-generated key pool or a range-based counter to eliminate the DB existence check from the hot path."

---

## Step 5: Deep Dive — Database Design

### Schema Discussion Points

```sql
-- Primary table
CREATE TABLE shortened_urls (
    id          BIGSERIAL PRIMARY KEY,
    short_code  VARCHAR(10) NOT NULL UNIQUE,  -- Lookup key
    original_url TEXT NOT NULL,                -- Can be very long
    created_at  TIMESTAMP WITH TIME ZONE,
    expires_at  TIMESTAMP WITH TIME ZONE,
    click_count BIGINT DEFAULT 0,             -- Denormalized counter
    active      BOOLEAN DEFAULT TRUE          -- Soft delete
);
```

### Question: "Why not use the short_code as the primary key?"

**Answer:** We use a surrogate key (`BIGSERIAL`) for several reasons:
- Primary keys are used in foreign key relationships and indexes — a 8-byte integer is more compact than a 10-byte varchar
- If we ever need to change the short code format, the PK remains stable
- Auto-increment PKs maintain insertion order, which helps with B-tree index locality

But the `short_code` column has a `UNIQUE` index, so lookups are equally fast.

### Question: "SQL or NoSQL?"

| Factor | SQL (PostgreSQL) | NoSQL (DynamoDB/Cassandra) |
|--------|-----------------|---------------------------|
| Data model | Simple key-value + metadata | Natural fit |
| Consistency | Strong (ACID transactions) | Eventual (configurable) |
| Joins | Supported (not needed here) | Not supported |
| Scaling | Vertical + read replicas | Horizontal sharding built-in |
| Operations | Familiar, mature tooling | Cloud-managed options |

**Answer for interview:**
> "The data model is essentially key-value (short_code → original_url), which fits NoSQL well. However, for a startup or mid-scale system, PostgreSQL is simpler operationally, gives us ACID guarantees for free, and handles millions of records without sharding. At massive scale (billions of URLs), I'd consider DynamoDB for automatic horizontal scaling — the access pattern (single-key lookups) is a perfect fit."

### Question: "How would you partition/shard the database?"

**Option 1: Range-based on short_code**
```
Shard A: codes starting with [a-m]
Shard B: codes starting with [n-z]
Shard C: codes starting with [A-M]
Shard D: codes starting with [N-Z0-9]
```
Problem: Uneven distribution if codes aren't uniformly random.

**Option 2: Hash-based on short_code**
```
shard_id = hash(short_code) % num_shards
```
Better distribution, but range queries across shards are expensive.

**Option 3: Consistent hashing**
Best for dynamically adding/removing shards with minimal data movement.

**Our implementation:** Single PostgreSQL instance — appropriate for moderate scale. Would add read replicas first, then shard only when needed.

---

## Step 6: Deep Dive — Caching Strategy

### Why Cache?

```
Without cache:
  4,000 reads/sec × 5ms DB query = 20 seconds of DB time per second
  → Database is overwhelmed

With cache (95% hit rate):
  4,000 × 5% = 200 DB queries/sec
  4,000 × 95% = 3,800 cache hits at <1ms each
  → Database is comfortable
```

### Cache Architecture

```
     Read path:
     ┌────────────┐    HIT     ┌────────────┐
     │  Request   │───────────>│   Return   │
     │            │            │   URL      │
     └─────┬──────┘            └────────────┘
           │ MISS
           ▼
     ┌────────────┐            ┌────────────┐
     │  Database  │───────────>│ Write to   │
     │  query     │            │ cache +    │
     │            │            │ return URL │
     └────────────┘            └────────────┘

     Write path:
     ┌────────────┐            ┌────────────┐
     │  Create    │───────────>│ Write to   │
     │  URL in DB │            │ cache      │
     └────────────┘            └────────────┘

     Delete path:
     ┌────────────┐            ┌────────────┐
     │ Deactivate │───────────>│ Evict from │
     │ in DB      │            │ cache      │
     └────────────┘            └────────────┘
```

### Cache Invalidation — "The Two Hard Problems"

| Event | Cache Action | Our Implementation |
|-------|-------------|-------------------|
| URL created | Write-through (cache immediately) | `cacheService.cacheUrl()` after save |
| URL accessed | Populate on miss | `cacheService.cacheUrl()` after DB read |
| URL deactivated | Evict | `cacheService.evict()` deletes both url: and stats: keys |
| URL expired | Evict on detection | `deactivateExpiredUrl()` evicts when redirect detects expiry |
| Natural expiry | TTL-based | Redis TTL = 24 hours |

### Question: "What about cache stampede?"

**Problem:** When a popular URL's cache entry expires, thousands of requests simultaneously hit the database.

**Solutions:**
1. **Probabilistic early expiration** — Randomly refresh cache before TTL expires
2. **Mutex/lock** — Only one thread queries DB, others wait
3. **No expiration for hot keys** — Refresh in background
4. **Our approach** — 24-hour TTL is long enough that natural access patterns refresh the cache. For extreme scale, add a distributed lock (Redisson) on cache miss.

### Question: "What if Redis goes down?"

**Our implementation handles this:**
```java
public Optional<String> getCachedUrl(String shortCode) {
    try {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key));
    } catch (RedisConnectionFailureException e) {
        log.warn("Redis unavailable");
        return Optional.empty();  // Falls back to database
    }
}
```

Redis is a **performance layer, not a correctness layer**. The system works without it — just slower.

---

## Step 7: Deep Dive — Redirect Flow

### HTTP Status Code Choice

| Code | Meaning | Browser Behavior | SEO Impact |
|------|---------|-----------------|------------|
| **301** Moved Permanently | Resource permanently moved | Browser caches, skips server next time | Link equity transferred |
| **302** Found | Resource temporarily at different URL | Browser always checks server | No link equity transfer |
| **307** Temporary Redirect | Same as 302, preserves HTTP method | Browser always checks server | No link equity transfer |

**Our choice: 302**

Why not 301? If we return 301, browsers cache the redirect and never hit our server again. We lose:
- Click counting (analytics)
- Ability to deactivate/change the URL
- Ability to detect expired URLs

**What the interviewer wants to hear:**
> "302 gives us control. Every click comes through our server, so we can count it, check expiration, and deactivate URLs. The trade-off is slightly higher latency compared to 301, but at <100ms it's imperceptible."

### Redirect Latency Breakdown

```
Total time: ~15-50ms

  DNS resolution:       ~0ms (connection reuse)
  TCP + TLS:            ~0ms (keep-alive)
  Server processing:
    Cache lookup:       ~1ms (Redis)
    OR DB query:        ~5ms (PostgreSQL)
    Kafka publish:      ~2ms (async, non-blocking)
    Click increment:    ~0ms (async, different thread)
    Build response:     ~0.1ms
  Network back:         ~10-40ms (depends on geography)
```

---

## Step 8: Deep Dive — Analytics

### Question: "Why use Kafka instead of just updating the database?"

```
Without Kafka (synchronous):
  Redirect request
       │
       ├── 1. Resolve URL (5ms)
       ├── 2. INSERT click_event (5ms)    ← Blocks the response
       ├── 3. UPDATE click_count (3ms)    ← Blocks the response
       │
       └── Return 302 (total: ~13ms)

With Kafka (asynchronous):
  Redirect request
       │
       ├── 1. Resolve URL (5ms)
       ├── 2. Kafka send (non-blocking, <1ms)
       ├── 3. Async click count (different thread)
       │
       └── Return 302 (total: ~6ms)    ← 2x faster

                    Later, asynchronously:
                    Kafka consumer ──> INSERT click_event
```

**Benefits:**
- Redirect latency reduced by 50%
- Database write load decoupled from read traffic
- Can replay events if consumer crashes
- Can add multiple consumers (real-time dashboard, batch analytics, fraud detection)

### Exactly-Once Processing

```
Kafka delivers at-least-once. Our consumer ensures exactly-once via idempotency:

  Message arrives ──> Extract eventId (UUID)
                          │
                    ┌─────┴─────┐
                    │ EXISTS in │
                    │ click_    │
                    │ events?   │
                    └─────┬─────┘
                   Yes/   │   \No
                   ▼      │    ▼
              ┌────────┐  │  ┌─────────┐
              │  Skip  │  │  │  Save   │
              │  (log) │  │  │ to DB   │
              └────────┘  │  └─────────┘
```

**Our implementation:**
- Producer generates a UUID `eventId` per click
- Database has `UNIQUE` constraint on `event_id`
- Consumer checks `existsByEventId()` before saving
- Duplicate messages are silently dropped

---

## Step 9: Scaling the System

### Scaling Roadmap

```
Stage 1: Single Server (our implementation)
  ┌─────────┐
  │ App + DB│  Handles ~1,000 req/s
  │ + Redis │  Good for MVP / small product
  └─────────┘

Stage 2: Separate Services
  ┌─────┐  ┌──────┐  ┌─────┐
  │ App │──│  DB  │──│Redis│  Handles ~10,000 req/s
  └─────┘  └──────┘  └─────┘

Stage 3: Horizontal App Scaling
  ┌────┐
  │ LB │──┬── App 1 ──┐
  └────┘  ├── App 2 ──├── DB (primary + read replicas)
          └── App N ──┤
                      └── Redis Cluster

  Handles ~100,000 req/s

Stage 4: Database Sharding
  ┌────┐
  │ LB │──── App Cluster ──── Redis Cluster
  └────┘                  │
                          ├── DB Shard 1 (codes a-m)
                          ├── DB Shard 2 (codes n-z)
                          └── DB Shard 3 (codes A-Z0-9)

  Handles ~1,000,000 req/s

Stage 5: Global Distribution
  ┌─────────────────────────────────────────┐
  │              Global DNS                  │
  │  US users → US region                   │
  │  EU users → EU region                   │
  │  Asia users → Asia region               │
  └──────────────────┬──────────────────────┘
                     │
         ┌───────────┼───────────┐
         ▼           ▼           ▼
    US Region    EU Region   Asia Region
    (full stack) (full stack) (full stack)
    
  Each region: App cluster + DB + Redis + Kafka
  Cross-region replication for data consistency
```

### Question: "What's the bottleneck at each stage?"

| Stage | Bottleneck | Solution |
|-------|-----------|----------|
| 1 | Single server CPU/memory | Separate DB from app |
| 2 | Database read throughput | Add Redis cache |
| 3 | Database write throughput | Read replicas, then shard |
| 4 | Single region latency | Global distribution |
| 5 | Cross-region consistency | Accept eventual consistency |

---

## Step 10: Advanced Topics

### Rate Limiting

**Question:** "How do you prevent abuse?"

```
Options:
1. Token bucket per IP (Redis-based)
   - INCR rate:create:{ip} with TTL
   - Reject if count > threshold

2. API key with quotas
   - Each user gets a key
   - Track usage per key per time window

3. CAPTCHA for anonymous users
   - Only for create endpoint
   - Not for redirects (must be fast)

Our implementation doesn't include rate limiting yet,
but would add it as middleware using Redis counters.
```

### URL Sanitization & Security

**Question:** "How do you prevent malicious URLs?"

```
Our implementation handles:
├── SSRF: Block localhost, private IPs, cloud metadata
├── Validation: Check URL format with URI parser
└── XSS: Don't echo user input in error messages

Additional measures for production:
├── Google Safe Browsing API integration
├── Phishing URL detection
├── Preview page before redirect (optional)
└── Rate limiting on create endpoint
```

### Telemetry & Monitoring

**Question:** "How do you know the system is healthy?"

```
Key metrics to track:
├── Redirect latency (p50, p95, p99)
├── Cache hit rate (target: >95%)
├── Short code collision rate
├── Kafka consumer lag
├── Database connection pool usage
├── Error rate by type (404, 410, 500)

Our implementation provides:
├── Spring Actuator /health endpoint
├── /metrics endpoint (Micrometer)
├── Structured logging with correlation
└── Kafka consumer offset tracking
```

### Handling Hot URLs

**Question:** "What if one URL gets millions of clicks per second?"

```
Problem: Even with caching, one URL can overwhelm a single Redis node.

Solutions:
1. Local in-memory cache (Caffeine) for top-N URLs
   App Server ──> Local Cache ──> Redis ──> DB
                  (L1, 1000 entries)  (L2)    (L3)

2. Read replicas for Redis
   App Server ──> Redis Replica 1
              ──> Redis Replica 2
              ──> Redis Replica 3

3. CDN-level redirect
   DNS ──> CDN edge ──> 302 (cached at edge for 60s)
   
   The CDN serves the redirect without hitting our servers.
```

---

## Common Interview Questions & Answers

### Q1: "How would you handle the case where two users create the same custom alias simultaneously?"

**Answer:**
> "We handle this at two levels. First, the application checks `findByShortCodeAndActiveTrue()` — but this has a TOCTOU race condition. Two threads could both see the alias as available. So second, the database has a `UNIQUE` constraint on `short_code`. If both threads try to INSERT, one will get a `DataIntegrityViolationException`, which we catch and convert to a 409 Conflict response. The database is the ultimate arbiter of uniqueness."

### Q2: "What happens if Redis goes down?"

**Answer:**
> "The system continues working with degraded performance. Our `CacheService` catches `RedisConnectionFailureException` and returns `Optional.empty()`, causing every request to fall through to PostgreSQL. The redirect still works — just with ~5ms latency instead of ~1ms. We log warnings so operations can investigate. Redis is a performance optimization, not a correctness requirement."

### Q3: "How do you prevent someone from enumerating all short URLs?"

**Answer:**
> "Three defenses: (1) We use `SecureRandom` for code generation, making codes unpredictable — you can't guess the next code from previous ones. (2) The 7-character code space has 3.5 trillion possibilities, making brute force impractical. (3) We could add rate limiting on the redirect endpoint to slow down enumeration attempts. For highly sensitive deployments, we'd also add a CAPTCHA or require authentication for URL creation."

### Q4: "How would you implement URL expiration at scale?"

**Answer:**
> "We use lazy expiration — URLs are checked for expiration only when accessed. When a redirect request comes in and the URL is expired, we deactivate it and return 410 Gone. For proactive cleanup, we'd add a scheduled job that runs `findExpiredActiveUrls()` periodically (every hour) to deactivate and evict expired URLs from cache. This two-pronged approach handles both active and dormant expired URLs."

### Q5: "Why not use a NoSQL database like DynamoDB?"

**Answer:**
> "The access pattern — single-key lookup by short code — is a natural fit for both SQL and NoSQL. I'd choose based on scale and team expertise. At moderate scale (<10M URLs), PostgreSQL is simpler to operate, has mature tooling, and provides ACID guarantees. At massive scale (>1B URLs), DynamoDB's automatic sharding and millisecond latency at any scale become compelling. The switch would be straightforward because our data model is essentially key-value."

### Q6: "How would you make the system globally distributed?"

**Answer:**
> "I'd deploy region-specific clusters behind geographic DNS routing. Each region has its own app servers, Redis cache, and database. For writes, I'd use a single primary region or a conflict-free approach. For reads (95% of traffic), each region serves from its local replica — giving users <50ms latency regardless of location. The trade-off is eventual consistency: a URL created in the US might take 1-2 seconds to be resolvable in Europe."

### Q7: "What's the difference between your analytics pipeline and just incrementing a counter?"

**Answer:**
> "The counter gives us the total click count — fast to update and query. But the Kafka pipeline gives us rich, per-click data: when each click happened, from where, what browser was used, what page linked to us. This enables analytics like 'clicks over time', 'top referrers', 'geographic distribution'. The counter is for the stats API response; the event pipeline is for detailed analytics dashboards."

### Q8: "How do you handle Kafka consumer failures?"

**Answer:**
> "Kafka retains messages for a configurable period (default 7 days). If the consumer crashes, it restarts and resumes from the last committed offset — no data loss. If the same message is processed twice (at-least-once delivery), our idempotency check (`existsByEventId`) prevents duplicate entries. The UUID-based deduplication ensures exactly-once semantics at the application level."

---

## Mistakes to Avoid

### 1. Jumping to the solution
**Wrong:** "I'll use a hash function to generate codes..."
**Right:** "Before designing, let me clarify the requirements. What's our expected scale?"

### 2. Over-engineering from the start
**Wrong:** "We need 5 microservices, Kubernetes, and a global CDN."
**Right:** "For the initial design, a monolithic app with PostgreSQL and Redis handles our requirements. Here's how we'd scale when needed..."

### 3. Ignoring the read/write ratio
**Wrong:** Spending 20 minutes on write optimization.
**Right:** "This is a read-heavy system (100:1), so the redirect path optimization and caching strategy are the critical design decisions."

### 4. Forgetting about 301 vs 302
**Wrong:** "I'll return a redirect."
**Right:** "I'd use 302 specifically, because 301 would cause browsers to cache the redirect and bypass our analytics tracking."

### 5. Not discussing trade-offs
**Wrong:** "Random generation is the best approach."
**Right:** "Random generation is simplest and gives us unpredictable codes. The trade-off is a DB check per create and potential collisions at high fill rates. If we need to eliminate that DB check, we could switch to a pre-generated key pool."

---

## 5-Minute Summary Script

Use this to practice your opening:

> "A URL shortener has two core operations: shortening URLs and redirecting. The system is heavily read-biased — for every URL created, it's accessed hundreds of times.
>
> **For shortening:** A client sends a long URL. We generate a 7-character alphanumeric code using SecureRandom — giving us 3.5 trillion possible codes. We validate the URL, check for collisions, store the mapping in PostgreSQL, and cache it in Redis. We return the short URL.
>
> **For redirecting:** When someone visits the short URL, we first check Redis — cache hits return in under 1ms. On a miss, we query PostgreSQL, populate the cache, and return a 302 redirect. We use 302 instead of 301 so every click passes through our server for analytics.
>
> **For analytics:** We publish click events to Kafka asynchronously — this adds zero latency to the redirect. A consumer persists events to a click_events table with UUID-based deduplication for exactly-once processing.
>
> **For scaling:** The app servers are stateless and scale horizontally behind a load balancer. Redis handles the read load. PostgreSQL handles writes with the option to add read replicas. At massive scale, we'd shard the database by short code hash and add a pre-generated key pool to eliminate the existence check on create."

---

## Quick Reference Card

```
┌────────────────────────────────────────────────────────┐
│              URL SHORTENER CHEAT SHEET                  │
├────────────────────────────────────────────────────────┤
│                                                        │
│  Scale: 100M URLs/month = 40 writes/s, 4K reads/s    │
│  Storage: 300 bytes/URL, 1.8TB for 5 years            │
│  Codes: 62^7 = 3.5 trillion possibilities             │
│                                                        │
│  Code Gen: Random (simple) vs Counter (no collision)  │
│            vs Hash (dedup) vs Key Pool (fast)          │
│                                                        │
│  Cache: Redis, 24h TTL, 95%+ hit rate target          │
│  Redirect: 302 (not 301) for analytics                │
│  Analytics: Kafka async + UUID idempotency            │
│                                                        │
│  DB Choice: PostgreSQL (moderate scale)               │
│             DynamoDB (massive scale)                   │
│                                                        │
│  Scale Path: Cache → Read replicas → Sharding → CDN  │
│                                                        │
│  Key Trade-off: Availability > Consistency             │
│  Key Metric: Redirect p99 latency < 100ms             │
└────────────────────────────────────────────────────────┘
```
