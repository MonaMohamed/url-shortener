package com.urlshortener.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.analytics.ClickEventProducer;
import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.CreateUrlResponse;
import com.urlshortener.dto.UrlStatsResponse;
import com.urlshortener.exception.InvalidUrlException;
import com.urlshortener.exception.ShortCodeConflictException;
import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.model.ShortenedUrl;
import com.urlshortener.repository.UrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Set;

@Service
public class UrlService {

    private static final Logger log = LoggerFactory.getLogger(UrlService.class);
    private static final int MAX_COLLISION_RETRIES = 5;
    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost", "127.0.0.1", "::1", "0.0.0.0",
            "169.254.169.254", "metadata.google.internal"
    );

    private final UrlRepository urlRepository;
    private final ShortCodeGenerator shortCodeGenerator;
    private final CacheService cacheService;
    private final ClickEventProducer clickEventProducer;
    private final ClickCountService clickCountService;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public UrlService(UrlRepository urlRepository,
                      ShortCodeGenerator shortCodeGenerator,
                      CacheService cacheService,
                      ClickEventProducer clickEventProducer,
                      ClickCountService clickCountService,
                      ObjectMapper objectMapper,
                      @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.urlRepository = urlRepository;
        this.shortCodeGenerator = shortCodeGenerator;
        this.cacheService = cacheService;
        this.clickEventProducer = clickEventProducer;
        this.clickCountService = clickCountService;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    @Transactional
    public CreateUrlResponse createShortUrl(CreateUrlRequest request) {
        validateUrl(request.originalUrl());

        String shortCode;
        if (request.customAlias() != null && !request.customAlias().isBlank()) {
            shortCode = request.customAlias().trim();
            if (urlRepository.findByShortCodeAndActiveTrue(shortCode).isPresent()) {
                throw new ShortCodeConflictException(shortCode);
            }
        } else {
            shortCode = generateUniqueCode();
        }

        ZonedDateTime expiresAt = null;
        if (request.expiresInHours() != null && request.expiresInHours() > 0) {
            expiresAt = ZonedDateTime.now().plusHours(request.expiresInHours());
        }

        ShortenedUrl entity = new ShortenedUrl();
        entity.setShortCode(shortCode);
        entity.setOriginalUrl(request.originalUrl());
        entity.setExpiresAt(expiresAt);
        entity.setActive(true);
        entity.setClickCount(0L);

        try {
            urlRepository.save(entity);
        } catch (DataIntegrityViolationException e) {
            throw new ShortCodeConflictException(shortCode);
        }
        cacheService.cacheUrl(shortCode, request.originalUrl());

        return new CreateUrlResponse(
                shortCode,
                buildShortUrl(shortCode),
                request.originalUrl(),
                entity.getCreatedAt(),
                entity.getExpiresAt()
        );
    }

    @Transactional
    public String resolveUrl(String shortCode, String ipAddress, String userAgent, String referer) {
        Optional<String> cached = cacheService.getCachedUrl(shortCode);
        if (cached.isPresent()) {
            clickEventProducer.publishClickEvent(shortCode, ipAddress, userAgent, referer);
            clickCountService.incrementClickCountAsync(shortCode);
            return cached.get();
        }

        ShortenedUrl url = urlRepository.findByShortCodeAndActiveTrue(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        if (url.isExpired()) {
            deactivateExpiredUrl(url);
            throw new UrlExpiredException(shortCode);
        }

        cacheService.cacheUrl(shortCode, url.getOriginalUrl());
        clickEventProducer.publishClickEvent(shortCode, ipAddress, userAgent, referer);
        clickCountService.incrementClickCountAsync(shortCode);

        return url.getOriginalUrl();
    }

    @Transactional(readOnly = true)
    public UrlStatsResponse getUrlStats(String shortCode) {
        Optional<String> cachedStats = cacheService.getCachedStats(shortCode);
        if (cachedStats.isPresent()) {
            try {
                return objectMapper.readValue(cachedStats.get(), UrlStatsResponse.class);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize cached stats for {}", shortCode, e);
            }
        }

        ShortenedUrl url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        UrlStatsResponse stats = new UrlStatsResponse(
                url.getShortCode(),
                buildShortUrl(url.getShortCode()),
                url.getOriginalUrl(),
                url.getClickCount(),
                url.isActive(),
                url.getCreatedAt(),
                url.getExpiresAt()
        );

        try {
            cacheService.cacheUrlStats(shortCode, objectMapper.writeValueAsString(stats));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize stats for caching for {}", shortCode, e);
        }

        return stats;
    }

    @Transactional
    public void deactivateUrl(String shortCode) {
        ShortenedUrl url = urlRepository.findByShortCodeAndActiveTrue(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
        url.setActive(false);
        urlRepository.save(url);
        cacheService.evict(shortCode);
    }

    private String generateUniqueCode() {
        for (int i = 0; i < MAX_COLLISION_RETRIES; i++) {
            String code = shortCodeGenerator.generate();
            if (!urlRepository.existsByShortCode(code)) {
                return code;
            }
            log.warn("Short code collision on attempt {}: {}", i + 1, code);
        }
        throw new IllegalStateException("Failed to generate unique short code after " + MAX_COLLISION_RETRIES + " attempts");
    }

    private void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new InvalidUrlException("", "URL must not be empty");
        }
        try {
            URI uri = new URI(url);
            uri.toURL();
            String host = uri.getHost();
            if (host == null) {
                throw new InvalidUrlException("", "URL must have a valid host");
            }
            if (BLOCKED_HOSTS.contains(host.toLowerCase())) {
                throw new InvalidUrlException("", "Internal URLs are not allowed");
            }
            if (isPrivateAddress(host)) {
                throw new InvalidUrlException("", "Private network URLs are not allowed");
            }
        } catch (URISyntaxException | MalformedURLException | IllegalArgumentException e) {
            throw new InvalidUrlException("", "Invalid URL format");
        }
    }

    private boolean isPrivateAddress(String host) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            return addr.isSiteLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private void deactivateExpiredUrl(ShortenedUrl url) {
        url.setActive(false);
        urlRepository.save(url);
        cacheService.evict(url.getShortCode());
    }

    private String buildShortUrl(String shortCode) {
        String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        return base + shortCode;
    }
}
