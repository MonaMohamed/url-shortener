package com.urlshortener.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

public record CreateUrlRequest(
        @NotBlank(message = "URL is required")
        @URL(message = "Must be a valid URL")
        String originalUrl,

        @Size(max = 10, message = "Custom alias must be at most 10 characters")
        @Pattern(regexp = "^[a-zA-Z0-9]*$", message = "Custom alias must be alphanumeric")
        String customAlias,

        @Min(value = 1, message = "Expiration must be at least 1 hour")
        @Max(value = 8760, message = "Expiration must be at most 8760 hours (1 year)")
        Integer expiresInHours
) {
}