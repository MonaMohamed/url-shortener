# URL Shortener Platform

A production-quality URL shortener built with Java 17, Spring Boot, PostgreSQL, Redis, and Kafka.

## Architecture

```
Client --> RedirectController (GET /{code}) --> UrlService --> Redis Cache
                                                         \--> PostgreSQL
       --> UrlController (REST API)                       \--> Kafka (click events)
                                                                  |
                                                          ClickEventConsumer
                                                                  |
                                                          click_events table
```

**Components:**
- **Spring Boot 3.2** — REST API and application framework
- **PostgreSQL 16** — Primary data store for URLs and click events
- **Redis 7** — Caching layer with configurable TTL and graceful degradation
- **Kafka (KRaft)** — Asynchronous click event streaming with idempotent consumers
- **Flyway** — Database schema migrations
- **Docker Compose** — Local development environment

## Prerequisites

- Java 17+
- Maven 3.9+
- Docker and Docker Compose

## Getting Started

### Step 1: Clone the Repository

```bash
git clone https://github.com/MonaMohamed/url-shortener.git
cd url-shortener
```

### Step 2: Start Infrastructure Services

```bash
docker-compose up -d postgres redis kafka
```

This starts:
- PostgreSQL on port `5432` (database: `urlshortener`)
- Redis on port `6379`
- Kafka on port `9092` (KRaft mode, no ZooKeeper)

Wait for all services to be healthy:

```bash
docker-compose ps
```

### Step 3: Build the Application

```bash
mvn clean package -DskipTests
```

### Step 4: Run the Application

```bash
mvn spring-boot:run
```

The application starts on `http://localhost:8080`.

### Step 5: Create a Short URL

```bash
curl -X POST http://localhost:8080/api/v1/urls \
  -H "Content-Type: application/json" \
  -d '{"originalUrl": "https://www.example.com/very/long/path"}'
```

Response:

```json
{
  "shortCode": "aB3xK9m",
  "shortUrl": "http://localhost:8080/aB3xK9m",
  "originalUrl": "https://www.example.com/very/long/path",
  "createdAt": "2026-10-23T15:30:00+02:00",
  "expiresAt": null
}
```

### Step 6: Use the Short URL

Open `http://localhost:8080/aB3xK9m` in your browser or:

```bash
curl -L http://localhost:8080/aB3xK9m
```

You will be redirected (HTTP 302) to the original URL.

### Step 7: Check URL Statistics

```bash
curl http://localhost:8080/api/v1/urls/aB3xK9m/stats
```

Response:

```json
{
  "shortCode": "aB3xK9m",
  "shortUrl": "http://localhost:8080/aB3xK9m",
  "originalUrl": "https://www.example.com/very/long/path",
  "clickCount": 1,
  "active": true,
  "createdAt": "2026-10-23T15:30:00+02:00",
  "expiresAt": null
}
```

### Step 8: Deactivate a Short URL

```bash
curl -X DELETE http://localhost:8080/api/v1/urls/aB3xK9m
```

Returns HTTP 204. Subsequent redirects will return HTTP 404.

## API Reference

| Method | Endpoint | Description | Status |
|--------|----------|-------------|--------|
| `POST` | `/api/v1/urls` | Create a short URL | 201 Created |
| `GET` | `/{shortCode}` | Redirect to original URL | 302 Found |
| `GET` | `/api/v1/urls/{shortCode}/stats` | Get URL statistics | 200 OK |
| `DELETE` | `/api/v1/urls/{shortCode}` | Deactivate a URL | 204 No Content |

### Create URL Request Body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `originalUrl` | string | Yes | The URL to shorten (must be valid HTTP/HTTPS) |
| `customAlias` | string | No | Custom short code (alphanumeric, max 10 chars) |
| `expiresInHours` | integer | No | Auto-expire after N hours (1-8760) |

**Example with all options:**

```bash
curl -X POST http://localhost:8080/api/v1/urls \
  -H "Content-Type: application/json" \
  -d '{
    "originalUrl": "https://example.com",
    "customAlias": "mylink",
    "expiresInHours": 48
  }'
```

## Running Tests

```bash
# Run all 51 tests
mvn test

# Run a specific test class
mvn test -Dtest=UrlServiceTest

# Run integration tests only
mvn test -Dtest=UrlShortenerIntegrationTest
```

Tests use H2 in-memory database and mocked Redis/Kafka — no infrastructure required.

## Running with Docker Compose (Full Stack)

To run everything in containers:

```bash
docker-compose up --build
```

This starts PostgreSQL, Redis, Kafka, and the application. Access it at `http://localhost:8080`.

To stop:

```bash
docker-compose down
```

To stop and remove data volumes:

```bash
docker-compose down -v
```

## Configuration

Key settings in `src/main/resources/application.yml`:

| Property | Default | Description |
|----------|---------|-------------|
| `app.base-url` | `http://localhost:8080` | Base URL for generated short links |
| `app.short-code-length` | `7` | Length of generated short codes |
| `app.cache.ttl-hours` | `24` | Redis cache TTL in hours |
| `app.kafka.topic` | `url-clicks` | Kafka topic for click events |

Override via environment variables:

```bash
APP_BASE_URL=https://short.example.com mvn spring-boot:run
```

## Health Checks

```bash
# Application health
curl http://localhost:8080/actuator/health

# Available metrics
curl http://localhost:8080/actuator/metrics
```

## Project Structure

```
src/main/java/com/urlshortener/
  analytics/          Kafka producer and consumer for click events
  config/             Redis, Kafka, and async configuration
  controller/         REST API and redirect controllers
  dto/                Request and response records
  exception/          Custom exceptions and global error handler
  model/              JPA entities (ShortenedUrl, ClickEvent)
  repository/         Spring Data JPA repositories
  service/            Business logic (URL, cache, code generation)
```

## Security Features

- SSRF protection: blocks localhost, private IPs, and cloud metadata endpoints
- Input validation: URL format, custom alias pattern, expiration bounds
- No user input reflected in error messages (XSS prevention)
- Parameterized queries (SQL injection prevention)
- Race condition handling with database unique constraints

## License

MIT
