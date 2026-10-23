package com.urlshortener.dto;

import java.time.ZonedDateTime;

public record UrlStatsResponse(
        String shortCode,
        String shortUrl,
        String originalUrl,
        long clickCount,
        boolean active,
        ZonedDateTime createdAt,
        ZonedDateTime expiresAt
) {
}
