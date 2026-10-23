package com.urlshortener.controller;

import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.CreateUrlResponse;
import com.urlshortener.dto.UrlStatsResponse;
import com.urlshortener.service.UrlService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/urls")
public class UrlController {

    private static final Logger log = LoggerFactory.getLogger(UrlController.class);

    private final UrlService urlService;

    public UrlController(UrlService urlService) {
        this.urlService = urlService;
    }

    @PostMapping
    public ResponseEntity<CreateUrlResponse> createShortUrl(
            @Valid @RequestBody CreateUrlRequest request) {
        log.info("Creating short URL for: {}", request.originalUrl());
        CreateUrlResponse response = urlService.createShortUrl(request);
        URI location = URI.create("/" + response.shortCode());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{shortCode}/stats")
    public ResponseEntity<UrlStatsResponse> getUrlStats(@PathVariable String shortCode) {
        log.debug("Fetching stats for short code: {}", shortCode);
        UrlStatsResponse stats = urlService.getUrlStats(shortCode);
        return ResponseEntity.ok(stats);
    }

    @DeleteMapping("/{shortCode}")
    public ResponseEntity<Void> deactivateUrl(@PathVariable String shortCode) {
        log.info("Deactivating short URL: {}", shortCode);
        urlService.deactivateUrl(shortCode);
        return ResponseEntity.noContent().build();
    }
}