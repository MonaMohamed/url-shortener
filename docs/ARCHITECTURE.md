# URL Shortener — Detailed Architecture & Code Walkthrough

## Table of Contents

1. [System Overview](#system-overview)
2. [Technology Stack](#technology-stack)
3. [Project Structure](#project-structure)
4. [Request Lifecycle](#request-lifecycle)
5. [Layer-by-Layer Walkthrough](#layer-by-layer-walkthrough)
   - [Controllers](#1-controllers)
   - [DTOs](#2-dtos)
   - [Service Layer](#3-service-layer)
   - [Data Layer](#4-data-layer)
   - [Caching Layer](#5-caching-layer)
   - [Analytics Pipeline](#6-analytics-pipeline)
   - [Exception Handling](#7-exception-handling)
   - [Configuration](#8-configuration)
6. [Database Design](#database-design)
7. [Caching Strategy](#caching-strategy)
8. [Kafka Event Pipeline](#kafka-event-pipeline)
9. [Security Mechanisms](#security-mechanisms)
10. [Concurrency & Thread Model](#concurrency--thread-model)
11. [Infrastructure & Deployment](#infrastructure--deployment)
12. [Testing Strategy](#testing-strategy)

---

## System Overview

The URL shortener is a distributed system that converts long URLs into short, memorable codes. When a user visits the short URL, the system looks up the original URL and redirects the browser. Every click is tracked asynchronously through Kafka for analytics.

**High-level data flow:**

```
                          ┌─────────────────────────────────────────┐
                          │            Spring Boot App              │
                          │                                         │
  HTTP Request ──────────>│  Controller ──> Service ──> Repository  │
                          │                   │              │      │
                          │                   ▼              ▼      │
                          │              CacheService    PostgreSQL │
                          │               (Redis)                   │
                          │                   │                     │
                          │                   ▼                     │
                          │           ClickEventProducer            │
                          │                   │                     │
                          └───────────────────│─────────────────────┘
                                              │
                                              ▼
                                         ┌─────────┐
                                         │  Kafka  │
                                         └────┬────┘
                                              │
                                              ▼
                                     ClickEventConsumer
                                              │
                                              ▼
                                    click_events table
```

---

## Technology Stack

| Technology | Version | Purpose |
|-----------|---------|---------|
| Java | 17 | Language runtime |
| Spring Boot | 3.2.5 | Application framework |
| Spring Data JPA | 3.2.x | ORM and repository abstraction |
| Spring Data Redis | 3.2.x | Redis client integration |
| Spring Kafka | 3.1.x | Kafka producer/consumer |
| PostgreSQL | 16 | Primary relational database |
| Redis | 7 | Caching layer |
| Apache Kafka | 7.5 (Confluent) | Event streaming |
| Flyway | 9.x | Database migrations |
| Lombok | 1.18.x | Boilerplate reduction |
| JUnit 5 | 5.10.x | Testing framework |
| Mockito | 5.x | Mocking framework |
| H2 | 2.x | In-memory test database |
| Docker Compose | 3.9 | Local infrastructure |

---

## Project Structure

```
url-shortener/
├── pom.xml                              Maven build definition
├── Dockerfile                           Multi-stage Docker build
├── docker-compose.yml                   Full stack local environment
├── .github/workflows/ci.yml            GitHub Actions CI pipeline
│
├── src/main/java/com/urlshortener/
│   ├── UrlShortenerApplication.java     Spring Boot entry point
│   │
│   ├── controller/
│   │   ├── UrlController.java           REST API (create, stats, delete)
│   │   └── RedirectController.java      Short URL redirect handler
│   │
│   ├── dto/
│   │   ├── CreateUrlRequest.java        Input: URL to shorten
│   │   ├── CreateUrlResponse.java       Output: generated short URL
│   │   ├── UrlStatsResponse.java        Output: click statistics
│   │   └── ErrorResponse.java           Output: error details
│   │
│   ├── service/
│   │   ├── UrlService.java              Core business logic
│   │   ├── ShortCodeGenerator.java      Random code generation
│   │   ├── CacheService.java            Redis cache operations
│   │   └── ClickCountService.java       Async click counting
│   │
│   ├── analytics/
│   │   ├── ClickEventProducer.java      Publishes clicks to Kafka
│   │   └── ClickEventConsumer.java      Persists clicks from Kafka
│   │
│   ├── model/
│   │   ├── ShortenedUrl.java            JPA entity: URLs table
│   │   └── ClickEvent.java              JPA entity: click events table
│   │
│   ├── repository/
│   │   ├── UrlRepository.java           Database queries for URLs
│   │   └── ClickEventRepository.java    Database queries for clicks
│   │
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java  Centralized error handling
│   │   ├── UrlNotFoundException.java    404 error
│   │   ├── UrlExpiredException.java     410 error
│   │   ├── ShortCodeConflictException.java  409 error
│   │   └── InvalidUrlException.java     400 error
│   │
│   └── config/
│       ├── RedisConfig.java             Redis template setup
│       ├── KafkaConfig.java             Kafka topic creation
│       └── AsyncConfig.java             Thread pool for async ops
│
├── src/main/resources/
│   ├── application.yml                  Application configuration
│   └── db/migration/
│       ├── V1__create_urls_table.sql    URLs table schema
│       └── V2__create_click_events_table.sql  Click events schema
│
└── src/test/java/com/urlshortener/
    ├── controller/                      WebMvc controller tests
    ├── service/                         Unit tests with mocks
    ├── analytics/                       Kafka producer/consumer tests
    └── integration/                     Full stack integration tests
```

---

## Request Lifecycle

### What happens when someone creates a short URL

```
1. Client sends:  POST /api/v1/urls  {"originalUrl": "https://example.com"}
                         │
2. Spring Boot routes to UrlController.createShortUrl()
                         │
3. Bean validation (@NotBlank, @URL) runs on CreateUrlRequest
   └── Fails? → MethodArgumentNotValidException → GlobalExceptionHandler → 400
                         │
4. UrlService.createShortUrl() is called
   a. validateUrl() checks:
      - URL is not null/blank
      - URL has valid format (URI + toURL parse)
      - Host is not localhost/127.0.0.1/metadata endpoints (SSRF check)
      - Host is not a private IP (10.x, 172.16-31.x, 192.168.x)
      └── Fails? → InvalidUrlException → 400
                         │
   b. If customAlias provided:
      - Check DB for existing active URL with that code
      └── Exists? → ShortCodeConflictException → 409
      If no customAlias:
      - ShortCodeGenerator creates a 7-char alphanumeric code using SecureRandom
      - Check DB if code exists (existsByShortCode)
      - If collision, retry up to 5 times
      └── All collisions? → IllegalStateException → 500
                         │
   c. Calculate expiresAt if expiresInHours was set
      - expiresAt = ZonedDateTime.now().plusHours(N)
                         │
   d. Build ShortenedUrl entity, save to PostgreSQL
      - @PrePersist sets createdAt = ZonedDateTime.now()
      └── DB unique constraint violation? → ShortCodeConflictException → 409
                         │
   e. Cache the URL in Redis: key="url:{shortCode}", value=originalUrl, TTL=24h
                         │
   f. Build and return CreateUrlResponse with 201 + Location header
```

### What happens when someone clicks a short URL

```
1. Browser requests:  GET /aB3xK9m
                         │
2. Spring Boot matches RedirectController via path pattern [a-zA-Z0-9]{6,10}
   - Extracts client IP (checks X-Forwarded-For first for proxied requests)
   - Extracts User-Agent and Referer headers
                         │
3. UrlService.resolveUrl() is called
                         │
   a. Check Redis cache for "url:aB3xK9m"
      ┌── Cache HIT: skip to step (d)
      └── Cache MISS: continue to step (b)
                         │
   b. Query PostgreSQL: findByShortCodeAndActiveTrue("aB3xK9m")
      └── Not found? → UrlNotFoundException → 404
                         │
   c. Check expiration: entity.isExpired()
      └── Expired? → set active=false, save, evict cache → UrlExpiredException → 410
                         │
   d. Cache the URL in Redis (if it was a miss)
                         │
   e. ClickEventProducer publishes event to Kafka (fire-and-forget)
      - Generates UUID eventId for idempotency
      - Sends JSON to "url-clicks" topic with shortCode as partition key
      - Any Kafka error is logged but does NOT block the redirect
                         │
   f. ClickCountService.incrementClickCountAsync() called
      - Runs on a separate thread (@Async via Spring proxy)
      - Executes UPDATE shortened_urls SET click_count = click_count + 1
      - Errors are logged, never propagated
                         │
   g. Return original URL → RedirectController sends 302 with Location header
                         │
4. Browser follows redirect to original URL
```

---

## Layer-by-Layer Walkthrough

### 1. Controllers

#### UrlController.java — REST API

This controller handles the management API under `/api/v1/urls`. It has three endpoints:

**POST /** — Creates a new short URL.
- Accepts a JSON body validated by `@Valid` annotation, which triggers bean validation on `CreateUrlRequest`
- Delegates to `UrlService.createShortUrl()`
- Returns HTTP 201 with a `Location` header pointing to the redirect path (`/aB3xK9m`) and the full `CreateUrlResponse` in the body

**GET /{shortCode}/stats** — Returns click statistics.
- Path variable extracts the short code
- Delegates to `UrlService.getUrlStats()`
- Returns HTTP 200 with `UrlStatsResponse`

**DELETE /{shortCode}** — Soft-deletes a URL (sets active=false).
- Delegates to `UrlService.deactivateUrl()`
- Returns HTTP 204 (no body)

#### RedirectController.java — Redirect Handler

This controller handles the public-facing redirect at `GET /{shortCode}`.

**Path pattern:** `{shortCode:[a-zA-Z0-9]{6,10}}` — This regex constraint is critical. It ensures only alphanumeric codes of 6-10 characters match this route, preventing conflicts with `/api/v1/urls` and `/actuator` paths.

**IP extraction:** The `extractClientIp()` method checks the `X-Forwarded-For` header first. Behind a load balancer or reverse proxy, the real client IP is in this header (comma-separated; first entry is the client). Falls back to `request.getRemoteAddr()` for direct connections.

**Response:** Returns HTTP 302 (Found) with the `Location` header set to the original URL. The browser automatically follows this redirect.

### 2. DTOs

All DTOs are Java **records** — immutable data carriers introduced in Java 14. Records auto-generate constructors, getters, `equals()`, `hashCode()`, and `toString()`.

#### CreateUrlRequest

```java
public record CreateUrlRequest(
    @NotBlank @URL String originalUrl,    // Required, must be valid URL
    @Size(max=10) @Pattern(regexp="^[a-zA-Z0-9]*$") String customAlias,  // Optional
    @Min(1) @Max(8760) Integer expiresInHours  // Optional, 1 hour to 1 year
)
```

The validation annotations are from Jakarta Bean Validation. Spring automatically validates when `@Valid` is used on the controller parameter. If validation fails, Spring throws `MethodArgumentNotValidException`, which our `GlobalExceptionHandler` catches and converts to a 400 response with field-level error details.

#### CreateUrlResponse

Contains the generated `shortCode`, the full `shortUrl` (base URL + code), the `originalUrl` echoed back, and timestamps for `createdAt` and `expiresAt`.

#### UrlStatsResponse

Adds `clickCount` (number of redirects) and `active` (whether the URL is still serving redirects) to the URL information.

#### ErrorResponse

Standardized error format: `status` (HTTP code), `message` (human-readable), `timestamp`, and `path` (the request URI that caused the error).

### 3. Service Layer

#### UrlService.java — Core Business Logic

This is the central service orchestrating all operations. Key design decisions:

**Constructor injection:** All dependencies are injected via the constructor (no `@Autowired` on fields). This makes the class testable — tests can instantiate it with mocks directly.

**`@Transactional`:** Applied to methods that modify data. Spring wraps the method in a database transaction. If an exception is thrown, the transaction rolls back automatically.

**URL validation (`validateUrl`):** 
- Parses the URL using `java.net.URI` and `URI.toURL()` to validate format
- Checks for null host
- Blocks SSRF: a hardcoded set of dangerous hostnames (localhost, 127.0.0.1, cloud metadata endpoints) plus a dynamic check using `InetAddress.getByName()` to detect private/loopback/link-local addresses

**Code generation (`generateUniqueCode`):**
- Generates a random 7-character alphanumeric string
- Checks the database for collisions using `existsByShortCode`
- Retries up to 5 times on collision
- With 62 possible characters and 7 positions, there are 62^7 = 3.5 trillion possible codes, making collisions extremely rare

**Race condition handling on save:**
- Even with the existence check, two concurrent requests could both see the code as available
- The `save()` call is wrapped in a try-catch for `DataIntegrityViolationException`
- If the DB unique constraint fires, we convert it to `ShortCodeConflictException` (409)

**Resolve URL (`resolveUrl`):**
- Cache-first strategy: check Redis before hitting the database
- On cache hit, the database is never touched for the URL lookup
- On cache miss, query DB, populate cache, then continue
- Click tracking (Kafka + DB increment) happens regardless of cache hit/miss

#### ShortCodeGenerator.java — Random Code Generation

Uses `java.security.SecureRandom` (cryptographically strong random number generator) to select characters from `[a-zA-Z0-9]`. The default length (7) is configurable via `app.short-code-length` in `application.yml`.

Why SecureRandom over Random? Regular `java.util.Random` uses a predictable linear congruential generator. An attacker who observes a few generated codes could predict future ones. SecureRandom uses the OS entropy source, making codes unpredictable.

#### CacheService.java — Redis Operations

Wraps `StringRedisTemplate` with a prefix-based key scheme:
- URL cache: key = `url:{shortCode}`, value = original URL, TTL = 24 hours
- Stats cache: key = `stats:{shortCode}`, value = JSON string, TTL = 5 minutes

**Graceful degradation:** Every Redis operation is wrapped in a try-catch for `RedisConnectionFailureException`. If Redis is down:
- Writes silently skip caching (the app still works, just slower)
- Reads return `Optional.empty()`, causing a fallback to the database
- A warning is logged so operators can detect the Redis outage

This design ensures Redis is a **performance optimization, not a single point of failure**.

#### ClickCountService.java — Async Click Counting

This class exists solely to solve the **Spring AOP proxy problem**. 

When `UrlService.resolveUrl()` needs to increment the click count asynchronously, it cannot call `this.incrementClickCountAsync()` because Spring `@Async` works through proxy objects. A self-invocation (`this.method()`) bypasses the proxy, and `@Async` is silently ignored — the method runs synchronously on the request thread.

By extracting the async method into a separate `@Service`, the call goes through Spring's proxy, and `@Async` works correctly. The method runs on the thread pool defined in `AsyncConfig` (core=2, max=5 threads, queue capacity=100).

### 4. Data Layer

#### ShortenedUrl.java — JPA Entity

Maps to the `shortened_urls` table. Key annotations:
- `@GeneratedValue(strategy = IDENTITY)` — uses PostgreSQL's `BIGSERIAL` auto-increment
- `@Column(unique = true)` on `shortCode` — enforces uniqueness at the database level
- `@Builder.Default` on `clickCount` and `active` — ensures Lombok's builder uses the defaults (0 and true)
- `@PrePersist` — JPA lifecycle callback that sets `createdAt` before the first INSERT

The `isExpired()` method compares `expiresAt` against `ZonedDateTime.now()`. This is used during redirect to lazily deactivate expired URLs.

#### ClickEvent.java — JPA Entity

Maps to the `click_events` table. Each row represents one redirect event:
- `eventId` (UUID) — unique identifier for idempotency; if the same event is processed twice (Kafka redelivery), the consumer detects the duplicate
- `shortCode` — which URL was clicked (not a foreign key — the events table is append-only and shouldn't constrain URL deletion)
- `clickedAt` — when the click occurred
- `ipAddress`, `userAgent`, `referer` — request metadata for analytics

#### UrlRepository.java — Spring Data JPA

Spring Data JPA generates SQL from method names:
- `findByShortCode(String)` → `SELECT * FROM shortened_urls WHERE short_code = ?`
- `findByShortCodeAndActiveTrue(String)` → `SELECT * FROM shortened_urls WHERE short_code = ? AND active = true`
- `existsByShortCode(String)` → `SELECT EXISTS(SELECT 1 FROM shortened_urls WHERE short_code = ?)`

Custom queries:
- `incrementClickCount` — uses `@Modifying @Query` for an atomic `UPDATE ... SET click_count = click_count + 1`. This is safe under concurrent updates because the increment happens in a single SQL statement with row-level locking.
- `findExpiredActiveUrls` — finds URLs that have passed their expiration but haven't been deactivated yet (for batch cleanup jobs)

#### ClickEventRepository.java

- `existsByEventId(UUID)` — the idempotency check used by the Kafka consumer
- `findByShortCode(String)` — retrieve all click events for a URL
- `countByShortCode(String)` — count total clicks

### 5. Caching Strategy

```
     Request flow with caching:

     GET /{shortCode}
            │
            ▼
    ┌───────────────┐
    │ Redis lookup  │──── HIT ────> Return cached URL
    │ "url:{code}"  │              (skip DB entirely)
    └───────┬───────┘
            │ MISS
            ▼
    ┌───────────────┐
    │ PostgreSQL    │──── Found ──> Cache in Redis
    │ query         │              Return URL
    └───────┬───────┘
            │ Not Found
            ▼
       404 Response
```

**Why this works well:**
- Short URLs follow a power-law distribution: a small percentage of URLs get the vast majority of clicks
- Popular URLs stay hot in the Redis cache, eliminating DB load for the most common requests
- The 24-hour TTL balances memory usage with hit rate
- Stats have a shorter 5-minute TTL because click counts change frequently

**Cache invalidation:**
- On URL deactivation: both `url:` and `stats:` keys are explicitly deleted
- On URL expiration: keys are evicted when the expiration is detected during redirect
- Natural expiry: Redis TTL automatically removes stale entries

### 6. Kafka Event Pipeline

```
                    ┌─────────────────────────────────────────┐
                    │              Kafka Cluster               │
                    │                                         │
   Producer ───────>│  Topic: url-clicks                      │──────> Consumer
   (sync send,      │  Partitions: 3                          │  (poll loop,
    fire & forget)  │  Key: shortCode (partition affinity)    │   idempotent)
                    │  Replication: 1                          │
                    └─────────────────────────────────────────┘
```

#### ClickEventProducer — Publishing Events

When a redirect occurs, the producer:
1. Generates a UUID `eventId` for the click
2. Builds a JSON payload with click metadata
3. Sends to the `url-clicks` topic using `shortCode` as the key

**Why shortCode as the partition key?** Kafka hashes the key to determine the partition. All clicks for the same short code land on the same partition, preserving ordering per URL. This is useful for time-series analytics.

**Fire-and-forget pattern:** The `send()` call returns a `CompletableFuture`. We attach a `whenComplete` callback for logging, but we never block on the result. If Kafka is down, the error is logged and the redirect continues normally. Analytics data may be lost, but the user experience is not degraded.

**Error isolation:** The entire publish operation is wrapped in try-catch. Any exception — JSON serialization, Kafka connectivity, unexpected runtime errors — is caught and logged. The redirect always succeeds.

#### ClickEventConsumer — Persisting Events

The consumer runs in a background thread managed by Spring Kafka's `@KafkaListener`:
1. Receives a raw JSON string from the topic
2. Parses it with Jackson's `ObjectMapper`
3. Checks `existsByEventId(UUID)` for duplicate detection
4. If new, saves a `ClickEvent` entity to PostgreSQL

**Idempotency:** Kafka guarantees at-least-once delivery. If a consumer crashes after processing a message but before committing the offset, Kafka will redeliver the message. The UUID-based deduplication ensures each click is counted exactly once.

**Error handling:** JSON parse errors and database errors are caught and logged. A malformed message does not crash the consumer or block the partition.

### 7. Exception Handling

#### GlobalExceptionHandler.java — Centralized Error Responses

`@RestControllerAdvice` intercepts exceptions thrown by any controller and converts them to HTTP responses:

| Exception | HTTP Status | When |
|-----------|-------------|------|
| `UrlNotFoundException` | 404 Not Found | Short code doesn't exist or is inactive |
| `UrlExpiredException` | 410 Gone | URL past its expiration time |
| `ShortCodeConflictException` | 409 Conflict | Custom alias already in use |
| `InvalidUrlException` | 400 Bad Request | URL format invalid or SSRF blocked |
| `MethodArgumentNotValidException` | 400 Bad Request | Bean validation failure (@NotBlank, @URL, etc.) |
| `ConstraintViolationException` | 400 Bad Request | Path/query parameter validation failure |
| `Exception` (catch-all) | 500 Internal Server Error | Unexpected errors |

**Logging strategy:**
- Client errors (4xx): logged at WARN level — these are expected conditions
- Server errors (5xx): logged at ERROR level with full stack trace — these need investigation

**Security:** The catch-all handler returns a generic "An unexpected error occurred" message. It never exposes stack traces, class names, or internal state to the client.

### 8. Configuration

#### application.yml

```yaml
server.port: 8080                    # HTTP listen port

spring.datasource:
  url: jdbc:postgresql://...         # Database connection
  hikari.maximum-pool-size: 20       # Max concurrent DB connections

spring.jpa:
  hibernate.ddl-auto: validate       # Don't modify schema; Flyway handles it
  open-in-view: false                # Disable lazy-loading outside transactions

spring.flyway.enabled: true          # Run migrations on startup

spring.kafka.producer:
  acks: all                          # Wait for all replicas to acknowledge
  retries: 3                         # Retry on transient failures

app:
  base-url: http://localhost:8080    # Prefix for generated short URLs
  short-code-length: 7              # Characters in generated codes
  cache.ttl-hours: 24               # Redis URL cache lifetime
  kafka.topic: url-clicks           # Kafka topic name
```

**Key setting: `open-in-view: false`**
Spring's Open Session in View (OSIV) keeps a Hibernate session open for the entire HTTP request. This allows lazy-loading of entity relationships in the view layer, but it holds a database connection for the full request duration — bad for performance. We disable it and ensure all data access happens within `@Transactional` service methods.

**Key setting: `hibernate.ddl-auto: validate`**
In production, Hibernate should never modify the database schema. We use Flyway for versioned, repeatable migrations. The `validate` mode checks that the entity mappings match the actual schema and fails fast on startup if they don't.

#### RedisConfig.java

Creates a `RedisTemplate<String, String>` bean with `StringRedisSerializer` for both keys and values. This ensures all data stored in Redis is human-readable strings (useful for debugging with `redis-cli`).

#### KafkaConfig.java

Creates the `url-clicks` topic on startup using `TopicBuilder`:
- 3 partitions — allows up to 3 consumer instances for parallel processing
- 1 replica — sufficient for development; increase for production

#### AsyncConfig.java

`@EnableAsync` activates Spring's async method execution. The thread pool:
- Core pool: 2 threads (always running)
- Max pool: 5 threads (scales up under load)
- Queue capacity: 100 (tasks wait here when all threads are busy)
- Thread name prefix: `async-` (for easy identification in logs and thread dumps)

---

## Database Design

### shortened_urls table

```sql
CREATE TABLE shortened_urls (
    id          BIGSERIAL PRIMARY KEY,      -- Auto-increment PK
    short_code  VARCHAR(10) NOT NULL UNIQUE, -- The generated/custom code
    original_url TEXT NOT NULL,              -- The target URL (unlimited length)
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMP WITH TIME ZONE,   -- NULL = never expires
    click_count BIGINT NOT NULL DEFAULT 0,   -- Denormalized counter
    active      BOOLEAN NOT NULL DEFAULT TRUE, -- Soft delete flag
    created_by  VARCHAR(255)                 -- Optional creator identifier
);
```

**Indexes:**
- `idx_short_code` on `short_code` — fast lookups during redirect (most frequent query)
- `idx_expires_at` on `expires_at WHERE expires_at IS NOT NULL` — partial index for finding expired URLs efficiently
- `idx_active` on `active` — filter queries by active status

**Why `click_count` is denormalized:** Instead of counting rows in `click_events` every time stats are requested, we maintain an incrementing counter directly on the URL row. The `UPDATE SET click_count = click_count + 1` is atomic at the SQL level and avoids a full table scan on the events table.

### click_events table

```sql
CREATE TABLE click_events (
    id          BIGSERIAL PRIMARY KEY,
    short_code  VARCHAR(10) NOT NULL,       -- Which URL was clicked
    clicked_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ip_address  VARCHAR(45),                -- IPv4 or IPv6
    user_agent  TEXT,                        -- Browser/client identifier
    referer     TEXT,                        -- Where the click came from
    country     VARCHAR(2),                 -- ISO country code (future use)
    event_id    UUID NOT NULL UNIQUE         -- Idempotency key
);
```

**No foreign key to shortened_urls:** Intentional. This table is an append-only event log. A foreign key would prevent URL deletion and add write contention. The `short_code` is denormalized for query convenience.

**event_id unique index:** Enables O(1) duplicate detection by the Kafka consumer.

---

## Security Mechanisms

### 1. SSRF Prevention

The `validateUrl()` method blocks URLs targeting internal services:

```
User submits: http://169.254.169.254/latest/meta-data/iam/security-credentials/
                         │
validateUrl() checks:
  ├── Is host in BLOCKED_HOSTS? → "169.254.169.254" YES → InvalidUrlException
  │
  └── Is host a private IP? → InetAddress.getByName().isSiteLocalAddress()
      Blocks: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
```

### 2. Input Validation

Three layers of validation:
- **Bean validation** (`@NotBlank`, `@URL`, `@Size`, `@Pattern`) — catches obviously bad input at the controller boundary
- **Business validation** (`validateUrl()`) — catches semantically invalid URLs that pass format checks
- **Database constraints** (`UNIQUE`, `NOT NULL`) — last line of defense

### 3. No User Input in Error Messages

The `InvalidUrlException` stores the URL internally but the error message sent to clients uses generic text. This prevents reflected XSS if a client renders the error message in HTML.

### 4. SQL Injection Prevention

All database queries use either:
- Spring Data JPA derived queries (method name → parameterized SQL)
- `@Query` with named parameters (`:shortCode`) — never string concatenation

---

## Concurrency & Thread Model

```
┌─────────────────────────────────────────────────┐
│              Tomcat Thread Pool                  │
│  (handles HTTP requests, default 200 threads)    │
│                                                  │
│  Thread-1: POST /api/v1/urls ──> UrlService      │
│  Thread-2: GET /aB3xK9m ──> UrlService           │
│  Thread-3: GET /api/v1/urls/x/stats              │
└───────────────────────┬─────────────────────────┘
                        │
                        │ @Async calls
                        ▼
┌─────────────────────────────────────────────────┐
│           Async Thread Pool                      │
│  (core=2, max=5, queue=100)                      │
│                                                  │
│  async-1: incrementClickCount (SQL UPDATE)       │
│  async-2: incrementClickCount (SQL UPDATE)       │
└─────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────┐
│        Kafka Consumer Thread                     │
│  (managed by Spring Kafka, 1 per partition)      │
│                                                  │
│  consumer-0: poll() ──> ClickEventConsumer        │
└─────────────────────────────────────────────────┘
```

**Why click counting is async:** The `UPDATE` query for incrementing `click_count` requires a database roundtrip (~1-5ms). On the redirect hot path, every millisecond matters. By pushing this to a background thread, the 302 response returns faster.

**Kafka producer is non-blocking:** `KafkaTemplate.send()` is inherently async — it puts the record in an internal buffer and returns immediately. The actual network send happens on Kafka's I/O thread.

---

## Infrastructure & Deployment

### Docker Compose Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    docker-compose.yml                     │
│                                                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────┐ │
│  │ postgres │  │  redis   │  │  kafka   │  │   app   │ │
│  │  :5432   │  │  :6379   │  │  :9092   │  │  :8080  │ │
│  │          │  │          │  │ (KRaft)  │  │         │ │
│  └──────────┘  └──────────┘  └──────────┘  └─────────┘ │
│       │                           │             │        │
│       └────── health checks ──────┘             │        │
│                                                 │        │
│       app depends_on: postgres, redis, kafka    │        │
│       (waits for healthy status)                         │
└─────────────────────────────────────────────────────────┘
```

**Kafka in KRaft mode:** No ZooKeeper dependency. Kafka 3.3+ can manage its own metadata using the Raft consensus protocol. This simplifies the deployment from 4 containers to 3.

### Dockerfile — Multi-stage Build

```dockerfile
# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS build
COPY pom.xml .
RUN mvn dependency:go-offline     # Cache dependencies in a Docker layer
COPY src ./src
RUN mvn package -DskipTests       # Build the JAR

# Stage 2: Run
FROM eclipse-temurin:17-jre-alpine
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Why multi-stage?** The build stage includes Maven, JDK, and all build tools (~800MB). The runtime stage only needs the JRE and the JAR (~200MB). This reduces the container image size by 75%.

**Why `dependency:go-offline` first?** Docker caches each layer. By copying `pom.xml` and downloading dependencies before copying source code, a code change only rebuilds the `mvn package` step — dependencies stay cached.

---

## Testing Strategy

### Test Pyramid

```
         ┌───────────────────┐
         │   Integration (3) │  Full Spring context, H2 DB
         │   Slow but real   │  Tests complete workflows
         ├───────────────────┤
         │  Controller (10)  │  @WebMvcTest, mock service
         │  HTTP layer only  │  Tests routing, validation, serialization
         ├───────────────────┤
         │    Unit (38)      │  Pure Mockito, no Spring
         │  Fast, isolated   │  Tests business logic
         └───────────────────┘
              51 total tests
```

### How tests avoid infrastructure dependencies

| Dependency | Production | Test |
|-----------|-----------|------|
| PostgreSQL | Real DB via Flyway | H2 in-memory, create-drop |
| Redis | Real Redis server | Mocked CacheService |
| Kafka | Real Kafka broker | Excluded via `@EnableAutoConfiguration(exclude=KafkaAutoConfiguration)` + mocked producer |

### Test profiles

`application-test.yml` overrides production config:
- H2 in-memory database (no PostgreSQL needed)
- Flyway disabled (H2 doesn't support PostgreSQL SQL syntax; JPA `create-drop` builds schema)
- Shorter cache TTLs

### What each test layer validates

**Unit tests** (`@ExtendWith(MockitoExtension.class)`):
- Business logic in isolation
- All code paths (happy path, errors, edge cases)
- Mock all dependencies, test one class at a time

**Controller tests** (`@WebMvcTest`):
- HTTP routing (correct path → correct method)
- Request validation (missing/invalid fields → 400)
- Response serialization (JSON structure, status codes, headers)
- Exception handler integration (exceptions → correct HTTP errors)

**Integration tests** (`@SpringBootTest`):
- Full request lifecycle through all layers
- Database transactions work correctly
- Components wire together properly
- Edge cases that span multiple layers (create → redirect → deactivate → redirect fails)
