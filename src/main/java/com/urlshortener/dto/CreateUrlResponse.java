package com.urlshortener.dto;

import java.time.ZonedDateTime;

public record CreateUrlResponse(
        String shortCode,
        String shortUrl,
        String originalUrl,
        ZonedDateTime createdAt,
        ZonedDateTime expiresAt
) {
}