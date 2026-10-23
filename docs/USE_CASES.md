# URL Shortener — Use Cases, Flowcharts & Sequence Diagrams

## Table of Contents

1. [UC-1: Create Short URL](#uc-1-create-short-url)
2. [UC-2: Redirect to Original URL](#uc-2-redirect-to-original-url)
3. [UC-3: Get URL Statistics](#uc-3-get-url-statistics)
4. [UC-4: Deactivate Short URL](#uc-4-deactivate-short-url)
5. [UC-5: Create Short URL with Custom Alias](#uc-5-create-short-url-with-custom-alias)
6. [UC-6: Create Short URL with Expiration](#uc-6-create-short-url-with-expiration)
7. [UC-7: Redirect Expired URL](#uc-7-redirect-expired-url)
8. [UC-8: Click Event Analytics Pipeline](#uc-8-click-event-analytics-pipeline)

---

## UC-1: Create Short URL

**Actor:** API Client  
**Precondition:** None  
**Postcondition:** A new short URL is stored in PostgreSQL and cached in Redis  
**Error Cases:** Invalid URL format (400), SSRF blocked URL (400)

### Flowchart

```
                    ┌─────────────┐
                    │   Client    │
                    │ POST /api/  │
                    │  v1/urls    │
                    └──────┬──────┘
                           │
                           ▼
                  ┌────────────────┐
                  │ Validate URL   │
                  │ format & host  │
                  └───────┬────────┘
                          │
                    ┌─────┴─────┐
                    │  Valid?   │
                    └─────┬─────┘
                   No/    │    \Yes
                  ▼       │     ▼
          ┌──────────┐    │  ┌──────────────┐
          │ Return   │    │  │ Is host      │
          │ 400 Bad  │    │  │ private/     │
          │ Request  │    │  │ internal?    │
          └──────────┘    │  └──────┬───────┘
                          │   Yes/  │  \No
                          │   ▼     │   ▼
                          │ ┌─────────┐ ┌──────────────────┐
                          │ │Return   │ │ Generate 7-char  │
                          │ │400 SSRF │ │ alphanumeric code│
                          │ └─────────┘ └────────┬─────────┘
                          │                      │
                          │                      ▼
                          │             ┌────────────────┐
                          │             │ Code exists    │
                          │             │ in DB?         │
                          │             └───────┬────────┘
                          │              Yes/   │   \No
                          │              ▼      │    ▼
                          │     ┌──────────┐    │  ┌───────────────┐
                          │     │ Retry    │    │  │ Save to       │
                          │     │ (max 5)  │────┘  │ PostgreSQL    │
                          │     └──────────┘       └───────┬───────┘
                          │                                │
                          │                                ▼
                          │                       ┌────────────────┐
                          │                       │ Cache URL in   │
                          │                       │ Redis (24h)    │
                          │                       └───────┬────────┘
                          │                               │
                          │                               ▼
                          │                      ┌─────────────────┐
                          │                      │ Return 201      │
                          │                      │ + Location      │
                          │                      │ + Response body │
                          │                      └─────────────────┘
```

### Sequence Diagram

```
┌──────┐     ┌──────────────┐     ┌──────────┐     ┌──────────┐     ┌───────┐
│Client│     │UrlController │     │UrlService│     │PostgreSQL│     │ Redis │
└──┬───┘     └──────┬───────┘     └────┬─────┘     └────┬─────┘     └───┬───┘
   │                │                  │                │               │
   │ POST /api/v1/  │                  │                │               │
   │ urls           │                  │                │               │
   │ {originalUrl}  │                  │                │               │
   │───────────────>│                  │                │               │
   │                │                  │                │               │
   │                │ createShortUrl   │                │               │
   │                │ (request)        │                │               │
   │                │─────────────────>│                │               │
   │                │                  │                │               │
   │                │                  │ validateUrl()  │               │
   │                │                  │───┐            │               │
   │                │                  │<──┘            │               │
   │                │                  │                │               │
   │                │                  │ generate()     │               │
   │                │                  │───┐            │               │
   │                │                  │<──┘ "aB3xK9m"  │               │
   │                │                  │                │               │
   │                │                  │ existsByShort  │               │
   │                │                  │ Code("aB3xK9m")│               │
   │                │                  │───────────────>│               │
   │                │                  │                │               │
   │                │                  │  false         │               │
   │                │                  │<───────────────│               │
   │                │                  │                │               │
   │                │                  │ save(entity)   │               │
   │                │                  │───────────────>│               │
   │                │                  │                │               │
   │                │                  │  saved         │               │
   │                │                  │<───────────────│               │
   │                │                  │                │               │
   │                │                  │ cacheUrl       │               │
   │                │                  │ ("aB3xK9m",   │               │
   │                │                  │  originalUrl)  │               │
   │                │                  │──────────────────────────────>│
   │                │                  │                │              │
   │                │                  │              cached           │
   │                │                  │<──────────────────────────────│
   │                │                  │                │               │
   │                │ CreateUrlResponse│                │               │
   │                │<─────────────────│                │               │
   │                │                  │                │               │
   │ 201 Created    │                  │                │               │
   │ Location: /    │                  │                │               │
   │ aB3xK9m       │                  │                │               │
   │<───────────────│                  │                │               │
   │                │                  │                │               │
```

---

## UC-2: Redirect to Original URL

**Actor:** End User (browser or HTTP client)  
**Precondition:** Short URL exists and is active  
**Postcondition:** User is redirected; click event published to Kafka; click count incremented  
**Error Cases:** URL not found (404), URL expired (410)

### Flowchart

```
                    ┌─────────────┐
                    │  End User   │
                    │ GET /{code} │
                    └──────┬──────┘
                           │
                           ▼
                  ┌────────────────┐
                  │ Check Redis    │
                  │ cache for code │
                  └───────┬────────┘
                          │
                    ┌─────┴─────┐
                    │Cache hit? │
                    └─────┬─────┘
                   No/    │    \Yes
                  ▼       │     ▼
         ┌──────────────┐ │  ┌──────────────────┐
         │ Query DB for │ │  │ Publish click    │
         │ active URL   │ │  │ event to Kafka   │
         └──────┬───────┘ │  └────────┬─────────┘
                │         │           │
          ┌─────┴─────┐   │           ▼
          │  Found?   │   │  ┌──────────────────┐
          └─────┬─────┘   │  │ Increment click  │
         No/    │   \Yes  │  │ count (async)    │
         ▼      │    ▼    │  └────────┬─────────┘
   ┌──────────┐ │ ┌─────────┐        │
   │ Return   │ │ │Expired? │        ▼
   │ 404 Not  │ │ └────┬────┘  ┌──────────────┐
   │ Found    │ │ Yes/ │  \No  │ Return 302   │
   └──────────┘ │  ▼   │   ▼   │ + Location   │
                │┌──────────┐ ┌──────────────┐ └──────────────┘
                ││Deactivate│ │Cache in Redis│
                ││URL       │ │+ publish     │
                ││Return 410│ │+ increment   │
                │└──────────┘ │+ Return 302  │
                │             └──────────────┘
```

### Sequence Diagram

```
┌────────┐  ┌─────────────────┐  ┌──────────┐  ┌─────┐  ┌──────────┐  ┌─────┐  ┌──────────────┐
│End User│  │RedirectController│  │UrlService│  │Redis│  │PostgreSQL│  │Kafka│  │ClickCountSvc │
└───┬────┘  └────────┬────────┘  └────┬─────┘  └──┬──┘  └────┬─────┘  └──┬──┘  └──────┬───────┘
    │                │                │            │          │           │             │
    │ GET /aB3xK9m   │                │            │          │           │             │
    │───────────────>│                │            │          │           │             │
    │                │                │            │          │           │             │
    │                │ resolveUrl     │            │          │           │             │
    │                │ (code, ip,     │            │          │           │             │
    │                │  ua, referer)  │            │          │           │             │
    │                │───────────────>│            │          │           │             │
    │                │                │            │          │           │             │
    │                │                │ getCachedUrl│          │           │             │
    │                │                │───────────>│          │           │             │
    │                │                │            │          │           │             │
    │                │                │            │          │           │             │
    │            ┌───┴── CACHE HIT ──┴────────────┤          │           │             │
    │            │   │                │  url found │          │           │             │
    │            │   │                │<───────────│          │           │             │
    │            │   │                │            │          │           │             │
    │            └───┴────────────────┴────────────┘          │           │             │
    │                │                │                       │           │             │
    │            ┌───┴─ CACHE MISS ──┴────────────────────────┤           │             │
    │            │   │                │  empty                │           │             │
    │            │   │                │<───────────│          │           │             │
    │            │   │                │            │          │           │             │
    │            │   │                │ findByShortCodeAnd    │           │             │
    │            │   │                │ ActiveTrue            │           │             │
    │            │   │                │──────────────────────>│           │             │
    │            │   │                │                       │           │             │
    │            │   │                │  ShortenedUrl entity  │           │             │
    │            │   │                │<──────────────────────│           │             │
    │            │   │                │            │          │           │             │
    │            │   │                │ cacheUrl   │          │           │             │
    │            │   │                │───────────>│          │           │             │
    │            └───┴────────────────┴────────────┘          │           │             │
    │                │                │            │          │           │             │
    │                │                │ publishClickEvent     │           │             │
    │                │                │──────────────────────────────────>│             │
    │                │                │            │          │           │             │
    │                │                │ incrementClickCountAsync          │             │
    │                │                │────────────────────────────────────────────────>│
    │                │                │            │          │           │             │
    │                │                │            │          │     incrementClickCount │
    │                │                │            │          │           │  (async)    │
    │                │                │            │          │<────────────────────────│
    │                │                │            │          │           │             │
    │                │  originalUrl   │            │          │           │             │
    │                │<───────────────│            │          │           │             │
    │                │                │            │          │           │             │
    │ 302 Found      │                │            │          │           │             │
    │ Location:      │                │            │          │           │             │
    │ https://...    │                │            │          │           │             │
    │<───────────────│                │            │          │           │             │
    │                │                │            │          │           │             │
```

---

## UC-3: Get URL Statistics

**Actor:** API Client  
**Precondition:** Short URL was created (active or inactive)  
**Postcondition:** Statistics returned, cached in Redis for 5 minutes  
**Error Cases:** URL not found (404)

### Flowchart

```
                    ┌──────────────┐
                    │   Client     │
                    │ GET /api/v1/ │
                    │ urls/{code}/ │
                    │ stats        │
                    └──────┬───────┘
                           │
                           ▼
                  ┌────────────────┐
                  │ Check Redis    │
                  │ stats cache    │
                  └───────┬────────┘
                          │
                    ┌─────┴─────┐
                    │Cache hit? │
                    └─────┬─────┘
                   No/    │    \Yes
                  ▼       │     ▼
         ┌──────────────┐ │  ┌──────────────────┐
         │ Query DB for │ │  │ Deserialize      │
         │ URL by code  │ │  │ cached stats     │
         └──────┬───────┘ │  │ Return 200       │
                │         │  └──────────────────┘
          ┌─────┴─────┐   │
          │  Found?   │   │
          └─────┬─────┘   │
         No/    │   \Yes  │
         ▼      │    ▼    │
   ┌──────────┐ │ ┌──────────────────┐
   │ Return   │ │ │ Build stats      │
   │ 404 Not  │ │ │ response         │
   │ Found    │ │ └────────┬─────────┘
   └──────────┘ │          │
                │          ▼
                │ ┌──────────────────┐
                │ │ Cache stats in   │
                │ │ Redis (5 min)    │
                │ └────────┬─────────┘
                │          │
                │          ▼
                │ ┌──────────────────┐
                │ │ Return 200       │
                │ │ UrlStatsResponse │
                │ └──────────────────┘
```

### Sequence Diagram

```
┌──────┐     ┌──────────────┐     ┌──────────┐     ┌─────┐     ┌──────────┐
│Client│     │UrlController │     │UrlService│     │Redis│     │PostgreSQL│
└──┬───┘     └──────┬───────┘     └────┬─────┘     └──┬──┘     └────┬─────┘
   │                │                  │              │              │
   │ GET /api/v1/   │                  │              │              │
   │ urls/aB3xK9m/  │                  │              │              │
   │ stats          │                  │              │              │
   │───────────────>│                  │              │              │
   │                │                  │              │              │
   │                │ getUrlStats      │              │              │
   │                │ ("aB3xK9m")     │              │              │
   │                │─────────────────>│              │              │
   │                │                  │              │              │
   │                │                  │getCachedStats│              │
   │                │                  │─────────────>│              │
   │                │                  │              │              │
   │                │                  │ empty        │              │
   │                │                  │<─────────────│              │
   │                │                  │              │              │
   │                │                  │ findByShortCode              │
   │                │                  │────────────────────────────>│
   │                │                  │              │              │
   │                │                  │ ShortenedUrl entity         │
   │                │                  │<────────────────────────────│
   │                │                  │              │              │
   │                │                  │cacheUrlStats │              │
   │                │                  │(JSON, 5 min) │              │
   │                │                  │─────────────>│              │
   │                │                  │              │              │
   │                │ UrlStatsResponse │              │              │
   │                │<─────────────────│              │              │
   │                │                  │              │              │
   │ 200 OK         │                  │              │              │
   │ {shortCode,    │                  │              │              │
   │  clickCount,   │                  │              │              │
   │  active, ...}  │                  │              │              │
   │<───────────────│                  │              │              │
   │                │                  │              │              │
```

---

## UC-4: Deactivate Short URL

**Actor:** API Client  
**Precondition:** Short URL exists and is active  
**Postcondition:** URL marked inactive in DB, evicted from Redis cache  
**Error Cases:** URL not found or already inactive (404)

### Flowchart

```
                    ┌──────────────┐
                    │   Client     │
                    │ DELETE /api/ │
                    │ v1/urls/     │
                    │ {code}       │
                    └──────┬───────┘
                           │
                           ▼
                  ┌────────────────────┐
                  │ Find active URL    │
                  │ by short code      │
                  └───────┬────────────┘
                          │
                    ┌─────┴─────┐
                    │  Found?   │
                    └─────┬─────┘
                   No/    │    \Yes
                  ▼       │     ▼
          ┌──────────┐    │  ┌──────────────────┐
          │ Return   │    │  │ Set active=false  │
          │ 404 Not  │    │  │ Save to DB        │
          │ Found    │    │  └────────┬──────────┘
          └──────────┘    │           │
                          │           ▼
                          │  ┌──────────────────┐
                          │  │ Evict from Redis  │
                          │  │ (url + stats)     │
                          │  └────────┬──────────┘
                          │           │
                          │           ▼
                          │  ┌──────────────────┐
                          │  │ Return 204       │
                          │  │ No Content       │
                          │  └──────────────────┘
```

### Sequence Diagram

```
┌──────┐     ┌──────────────┐     ┌──────────┐     ┌──────────┐     ┌─────┐
│Client│     │UrlController │     │UrlService│     │PostgreSQL│     │Redis│
└──┬───┘     └──────┬───────┘     └────┬─────┘     └────┬─────┘     └──┬──┘
   │                │                  │                │               │
   │ DELETE /api/   │                  │                │               │
   │ v1/urls/       │                  │                │               │
   │ aB3xK9m       │                  │                │               │
   │───────────────>│                  │                │               │
   │                │                  │                │               │
   │                │ deactivateUrl    │                │               │
   │                │ ("aB3xK9m")     │                │               │
   │                │─────────────────>│                │               │
   │                │                  │                │               │
   │                │                  │ findByShort    │               │
   │                │                  │ CodeAndActive  │               │
   │                │                  │ True           │               │
   │                │                  │───────────────>│               │
   │                │                  │                │               │
   │                │                  │  entity        │               │
   │                │                  │<───────────────│               │
   │                │                  │                │               │
   │                │                  │ save(active    │               │
   │                │                  │ =false)        │               │
   │                │                  │───────────────>│               │
   │                │                  │                │               │
   │                │                  │ evict          │               │
   │                │                  │ ("aB3xK9m")   │               │
   │                │                  │──────────────────────────────>│
   │                │                  │                │              │
   │                │  void            │                │               │
   │                │<─────────────────│                │               │
   │                │                  │                │               │
   │ 204 No Content │                  │                │               │
   │<───────────────│                  │                │               │
   │                │                  │                │               │
```

---

## UC-5: Create Short URL with Custom Alias

**Actor:** API Client  
**Precondition:** Custom alias is alphanumeric, max 10 characters  
**Postcondition:** URL stored with user-chosen short code  
**Error Cases:** Alias already taken (409), invalid alias format (400)

### Flowchart

```
                    ┌──────────────────┐
                    │     Client       │
                    │ POST /api/v1/urls│
                    │ {originalUrl,    │
                    │  customAlias}    │
                    └───────┬──────────┘
                            │
                            ▼
                   ┌────────────────┐
                   │ Validate URL   │
                   └───────┬────────┘
                           │ Valid
                           ▼
                   ┌────────────────┐
                   │ Validate alias │
                   │ (alphanumeric, │
                   │  max 10 chars) │
                   └───────┬────────┘
                           │
                     ┌─────┴─────┐
                     │  Valid?   │
                     └─────┬─────┘
                    No/    │    \Yes
                   ▼       │     ▼
           ┌──────────┐    │  ┌────────────────┐
           │ Return   │    │  │ Check if alias │
           │ 400      │    │  │ already exists │
           └──────────┘    │  └───────┬────────┘
                           │          │
                           │    ┌─────┴─────┐
                           │    │  Taken?   │
                           │    └─────┬─────┘
                           │   Yes/   │   \No
                           │   ▼      │    ▼
                           │ ┌──────────┐ ┌──────────────┐
                           │ │ Return   │ │ Save with    │
                           │ │ 409      │ │ custom alias │
                           │ │ Conflict │ │ + cache      │
                           │ └──────────┘ │ Return 201   │
                           │              └──────────────┘
```

---

## UC-6: Create Short URL with Expiration

**Actor:** API Client  
**Precondition:** expiresInHours between 1 and 8760  
**Postcondition:** URL stored with expiresAt timestamp  
**Error Cases:** Invalid expiration value (400)

### Flowchart

```
                    ┌──────────────────┐
                    │     Client       │
                    │ POST /api/v1/urls│
                    │ {originalUrl,    │
                    │  expiresInHours} │
                    └───────┬──────────┘
                            │
                            ▼
                   ┌────────────────┐
                   │ Validate URL   │
                   │ + expiration   │
                   │ (1-8760 hours) │
                   └───────┬────────┘
                           │ Valid
                           ▼
                   ┌────────────────────┐
                   │ Calculate          │
                   │ expiresAt =        │
                   │ now + N hours      │
                   └───────┬────────────┘
                           │
                           ▼
                   ┌────────────────────┐
                   │ Generate code      │
                   │ Save entity with   │
                   │ expiresAt set      │
                   └───────┬────────────┘
                           │
                           ▼
                   ┌────────────────────┐
                   │ Cache in Redis     │
                   │ Return 201 with    │
                   │ expiresAt in body  │
                   └────────────────────┘
```

---

## UC-7: Redirect Expired URL

**Actor:** End User  
**Precondition:** Short URL exists but expiresAt is in the past  
**Postcondition:** URL deactivated, cache evicted, 410 returned  

### Flowchart

```
                    ┌─────────────┐
                    │  End User   │
                    │ GET /{code} │
                    └──────┬──────┘
                           │
                           ▼
                  ┌────────────────┐
                  │ Cache miss     │
                  │ (or not cached)│
                  └───────┬────────┘
                          │
                          ▼
                 ┌─────────────────┐
                 │ Query DB for    │
                 │ active URL      │
                 └────────┬────────┘
                          │ Found
                          ▼
                 ┌─────────────────┐
                 │ Check           │
                 │ isExpired()     │
                 └────────┬────────┘
                          │ Yes, expired
                          ▼
                 ┌─────────────────┐
                 │ Set active=false│
                 │ Save to DB      │
                 └────────┬────────┘
                          │
                          ▼
                 ┌─────────────────┐
                 │ Evict from      │
                 │ Redis cache     │
                 └────────┬────────┘
                          │
                          ▼
                 ┌─────────────────┐
                 │ Return 410 GONE │
                 │ "URL has        │
                 │  expired"       │
                 └─────────────────┘
```

### Sequence Diagram

```
┌────────┐  ┌─────────────────┐  ┌──────────┐  ┌─────┐  ┌──────────┐
│End User│  │RedirectController│  │UrlService│  │Redis│  │PostgreSQL│
└───┬────┘  └────────┬────────┘  └────┬─────┘  └──┬──┘  └────┬─────┘
    │                │                │            │          │
    │ GET /old1234   │                │            │          │
    │───────────────>│                │            │          │
    │                │                │            │          │
    │                │ resolveUrl     │            │          │
    │                │───────────────>│            │          │
    │                │                │            │          │
    │                │                │getCachedUrl│          │
    │                │                │───────────>│          │
    │                │                │  empty     │          │
    │                │                │<───────────│          │
    │                │                │            │          │
    │                │                │ findByShortCodeAnd    │
    │                │                │ ActiveTrue            │
    │                │                │──────────────────────>│
    │                │                │                       │
    │                │                │ entity (expiresAt     │
    │                │                │  in the past)         │
    │                │                │<──────────────────────│
    │                │                │            │          │
    │                │                │ isExpired() │          │
    │                │                │───┐ true   │          │
    │                │                │<──┘        │          │
    │                │                │            │          │
    │                │                │ save(active=false)    │
    │                │                │──────────────────────>│
    │                │                │            │          │
    │                │                │ evict      │          │
    │                │                │───────────>│          │
    │                │                │            │          │
    │                │  UrlExpired    │            │          │
    │                │  Exception     │            │          │
    │                │<───────────────│            │          │
    │                │                │            │          │
    │ 410 GONE       │                │            │          │
    │ "URL has       │                │            │          │
    │  expired"      │                │            │          │
    │<───────────────│                │            │          │
    │                │                │            │          │
```

---

## UC-8: Click Event Analytics Pipeline

**Actor:** System (triggered by redirect)  
**Precondition:** A redirect occurs (UC-2)  
**Postcondition:** Click event persisted in click_events table with deduplication  

### Flowchart

```
                    ┌──────────────────┐
                    │ Redirect occurs  │
                    │ (UC-2)           │
                    └───────┬──────────┘
                            │
                            ▼
                   ┌────────────────────┐
                   │ ClickEventProducer │
                   │ Build JSON payload │
                   │ {eventId (UUID),   │
                   │  shortCode,        │
                   │  clickedAt,        │
                   │  ipAddress,        │
                   │  userAgent,        │
                   │  referer}          │
                   └───────┬────────────┘
                           │
                           ▼
                   ┌────────────────────┐
                   │ Send to Kafka      │
                   │ topic: url-clicks  │
                   │ key: shortCode     │
                   └───────┬────────────┘
                           │
                           ▼
                 ┌──────── │ ────────┐
                 │  Kafka  │ Broker  │
                 │  (3 partitions)   │
                 └──────── │ ────────┘
                           │
                           ▼
                   ┌────────────────────┐
                   │ ClickEventConsumer │
                   │ @KafkaListener     │
                   └───────┬────────────┘
                           │
                           ▼
                   ┌────────────────────┐
                   │ Parse JSON         │
                   │ Extract eventId    │
                   └───────┬────────────┘
                           │
                     ┌─────┴─────┐
                     │ eventId   │
                     │ exists    │
                     │ in DB?    │
                     └─────┬─────┘
                    Yes/   │   \No
                    ▼      │    ▼
            ┌──────────┐   │  ┌──────────────────┐
            │ Skip     │   │  │ Save ClickEvent  │
            │ duplicate│   │  │ to click_events  │
            │ (log)    │   │  │ table            │
            └──────────┘   │  └──────────────────┘
```

### Sequence Diagram

```
┌──────────┐     ┌──────────────────┐     ┌─────┐     ┌──────────────────┐     ┌──────────┐
│UrlService│     │ClickEventProducer│     │Kafka│     │ClickEventConsumer│     │PostgreSQL│
└────┬─────┘     └────────┬─────────┘     └──┬──┘     └────────┬─────────┘     └────┬─────┘
     │                    │                  │                  │                    │
     │ publishClickEvent  │                  │                  │                    │
     │ (code, ip, ua,     │                  │                  │                    │
     │  referer)          │                  │                  │                    │
     │───────────────────>│                  │                  │                    │
     │                    │                  │                  │                    │
     │                    │ Generate UUID    │                  │                    │
     │                    │ Build JSON       │                  │                    │
     │                    │───┐              │                  │                    │
     │                    │<──┘              │                  │                    │
     │                    │                  │                  │                    │
     │                    │ send(topic,      │                  │                    │
     │                    │  key=shortCode,  │                  │                    │
     │                    │  payload=JSON)   │                  │                    │
     │                    │─────────────────>│                  │                    │
     │                    │                  │                  │                    │
     │                    │  ack             │                  │                    │
     │                    │<─────────────────│                  │                    │
     │                    │                  │                  │                    │
     │  (fire & forget)   │                  │                  │                    │
     │<───────────────────│                  │                  │                    │
     │                    │                  │                  │                    │
     │                    │                  │  consume(JSON)   │                    │
     │                    │                  │─────────────────>│                    │
     │                    │                  │                  │                    │
     │                    │                  │                  │ existsByEventId    │
     │                    │                  │                  │ (UUID)             │
     │                    │                  │                  │───────────────────>│
     │                    │                  │                  │                    │
     │                    │                  │                  │  false (new)       │
     │                    │                  │                  │<───────────────────│
     │                    │                  │                  │                    │
     │                    │                  │                  │ save(ClickEvent)   │
     │                    │                  │                  │───────────────────>│
     │                    │                  │                  │                    │
     │                    │                  │                  │  saved             │
     │                    │                  │                  │<───────────────────│
     │                    │                  │                  │                    │
```

---

## Use Case Summary Matrix

| UC | Name | Method | Endpoint | Success | Error Codes |
|----|------|--------|----------|---------|-------------|
| 1 | Create Short URL | POST | /api/v1/urls | 201 | 400 |
| 2 | Redirect | GET | /{code} | 302 | 404, 410 |
| 3 | Get Statistics | GET | /api/v1/urls/{code}/stats | 200 | 404 |
| 4 | Deactivate URL | DELETE | /api/v1/urls/{code} | 204 | 404 |
| 5 | Custom Alias | POST | /api/v1/urls | 201 | 400, 409 |
| 6 | With Expiration | POST | /api/v1/urls | 201 | 400 |
| 7 | Redirect Expired | GET | /{code} | — | 410 |
| 8 | Analytics Pipeline | — | Kafka internal | — | — |
