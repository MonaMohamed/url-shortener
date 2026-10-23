package com.urlshortener.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.analytics.ClickEventProducer;
import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.CreateUrlResponse;
import com.urlshortener.dto.UrlStatsResponse;
import com.urlshortener.exception.ShortCodeConflictException;
import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.model.ShortenedUrl;
import com.urlshortener.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UrlServiceTest {

    @Mock
    private UrlRepository urlRepository;
    @Mock
    private ShortCodeGenerator shortCodeGenerator;
    @Mock
    private CacheService cacheService;
    @Mock
    private ClickEventProducer clickEventProducer;
    @Mock
    private ClickCountService clickCountService;
    @Mock
    private ObjectMapper objectMapper;

    private UrlService urlService;

    private static final String BASE_URL = "http://localhost:8080";

    @BeforeEach
    void setUp() {
        urlService = new UrlService(
                urlRepository,
                shortCodeGenerator,
                cacheService,
                clickEventProducer,
                clickCountService,
                objectMapper,
                BASE_URL
        );
    }

    @Test
    void createShortUrl_success() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", null, null);
        when(shortCodeGenerator.generate()).thenReturn("abc1234");
        when(urlRepository.existsByShortCode("abc1234")).thenReturn(false);
        when(urlRepository.save(any(ShortenedUrl.class))).thenAnswer(inv -> {
            ShortenedUrl entity = inv.getArgument(0);
            entity.setCreatedAt(ZonedDateTime.now());
            return entity;
        });

        CreateUrlResponse response = urlService.createShortUrl(request);

        assertEquals("abc1234", response.shortCode());
        assertEquals("http://localhost:8080/abc1234", response.shortUrl());
        assertEquals("https://example.com", response.originalUrl());
        assertNotNull(response.createdAt());
        assertNull(response.expiresAt());

        verify(urlRepository).save(any(ShortenedUrl.class));
        verify(cacheService).cacheUrl("abc1234", "https://example.com");
    }

    @Test
    void createShortUrl_withCustomAlias() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", "myalias", null);
        when(urlRepository.findByShortCodeAndActiveTrue("myalias")).thenReturn(Optional.empty());
        when(urlRepository.save(any(ShortenedUrl.class))).thenAnswer(inv -> {
            ShortenedUrl entity = inv.getArgument(0);
            entity.setCreatedAt(ZonedDateTime.now());
            return entity;
        });

        CreateUrlResponse response = urlService.createShortUrl(request);

        assertEquals("myalias", response.shortCode());
        verify(urlRepository).findByShortCodeAndActiveTrue("myalias");
        verify(shortCodeGenerator, never()).generate();
    }

    @Test
    void createShortUrl_customAlias_conflict() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", "taken", null);
        ShortenedUrl existing = new ShortenedUrl();
        existing.setShortCode("taken");
        when(urlRepository.findByShortCodeAndActiveTrue("taken")).thenReturn(Optional.of(existing));

        assertThrows(ShortCodeConflictException.class, () -> urlService.createShortUrl(request));
    }

    @Test
    void createShortUrl_withExpiration() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", null, 24);
        when(shortCodeGenerator.generate()).thenReturn("abc1234");
        when(urlRepository.existsByShortCode("abc1234")).thenReturn(false);
        when(urlRepository.save(any(ShortenedUrl.class))).thenAnswer(inv -> {
            ShortenedUrl entity = inv.getArgument(0);
            entity.setCreatedAt(ZonedDateTime.now());
            return entity;
        });

        CreateUrlResponse response = urlService.createShortUrl(request);

        ArgumentCaptor<ShortenedUrl> captor = ArgumentCaptor.forClass(ShortenedUrl.class);
        verify(urlRepository).save(captor.capture());
        ShortenedUrl saved = captor.getValue();
        assertNotNull(saved.getExpiresAt());
        assertTrue(saved.getExpiresAt().isAfter(ZonedDateTime.now().plusHours(23)));
    }

    @Test
    void resolveUrl_fromCache() {
        when(cacheService.getCachedUrl("abc1234")).thenReturn(Optional.of("https://example.com"));

        String result = urlService.resolveUrl("abc1234", "127.0.0.1", "Mozilla", null);

        assertEquals("https://example.com", result);
        verify(urlRepository, never()).findByShortCodeAndActiveTrue(anyString());
        verify(clickEventProducer).publishClickEvent(eq("abc1234"), eq("127.0.0.1"), eq("Mozilla"), isNull());
    }

    @Test
    void resolveUrl_cacheMiss_fromDb() {
        when(cacheService.getCachedUrl("abc1234")).thenReturn(Optional.empty());
        ShortenedUrl entity = new ShortenedUrl();
        entity.setShortCode("abc1234");
        entity.setOriginalUrl("https://example.com");
        entity.setActive(true);
        entity.setCreatedAt(ZonedDateTime.now());
        when(urlRepository.findByShortCodeAndActiveTrue("abc1234")).thenReturn(Optional.of(entity));

        String result = urlService.resolveUrl("abc1234", "127.0.0.1", "Mozilla", "https://google.com");

        assertEquals("https://example.com", result);
        verify(cacheService).cacheUrl("abc1234", "https://example.com");
        verify(clickEventProducer).publishClickEvent("abc1234", "127.0.0.1", "Mozilla", "https://google.com");
    }

    @Test
    void resolveUrl_notFound() {
        when(cacheService.getCachedUrl("missing")).thenReturn(Optional.empty());
        when(urlRepository.findByShortCodeAndActiveTrue("missing")).thenReturn(Optional.empty());

        assertThrows(UrlNotFoundException.class,
                () -> urlService.resolveUrl("missing", "127.0.0.1", null, null));
    }

    @Test
    void resolveUrl_expired() {
        when(cacheService.getCachedUrl("expired1")).thenReturn(Optional.empty());
        ShortenedUrl entity = new ShortenedUrl();
        entity.setShortCode("expired1");
        entity.setOriginalUrl("https://example.com");
        entity.setActive(true);
        entity.setCreatedAt(ZonedDateTime.now().minusDays(2));
        entity.setExpiresAt(ZonedDateTime.now().minusHours(1));
        when(urlRepository.findByShortCodeAndActiveTrue("expired1")).thenReturn(Optional.of(entity));

        assertThrows(UrlExpiredException.class,
                () -> urlService.resolveUrl("expired1", "127.0.0.1", null, null));

        verify(urlRepository).save(entity);
        assertFalse(entity.isActive());
        verify(cacheService).evict("expired1");
    }

    @Test
    void getUrlStats_success() {
        when(cacheService.getCachedStats("abc1234")).thenReturn(Optional.empty());
        ShortenedUrl entity = new ShortenedUrl();
        entity.setShortCode("abc1234");
        entity.setOriginalUrl("https://example.com");
        entity.setActive(true);
        entity.setClickCount(42);
        entity.setCreatedAt(ZonedDateTime.now());
        when(urlRepository.findByShortCode("abc1234")).thenReturn(Optional.of(entity));

        UrlStatsResponse stats = urlService.getUrlStats("abc1234");

        assertEquals("abc1234", stats.shortCode());
        assertEquals("https://example.com", stats.originalUrl());
        assertEquals(42, stats.clickCount());
        assertTrue(stats.active());
        assertEquals("http://localhost:8080/abc1234", stats.shortUrl());
    }

    @Test
    void deactivateUrl_success() {
        ShortenedUrl entity = new ShortenedUrl();
        entity.setShortCode("abc1234");
        entity.setOriginalUrl("https://example.com");
        entity.setActive(true);
        when(urlRepository.findByShortCodeAndActiveTrue("abc1234")).thenReturn(Optional.of(entity));

        urlService.deactivateUrl("abc1234");

        assertFalse(entity.isActive());
        verify(urlRepository).save(entity);
        verify(cacheService).evict("abc1234");
    }

    @Test
    void deactivateUrl_notFound() {
        when(urlRepository.findByShortCodeAndActiveTrue("missing")).thenReturn(Optional.empty());

        assertThrows(UrlNotFoundException.class, () -> urlService.deactivateUrl("missing"));
    }
}
