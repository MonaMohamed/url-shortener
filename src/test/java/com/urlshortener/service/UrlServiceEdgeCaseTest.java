package com.urlshortener.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.analytics.ClickEventProducer;
import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.dto.CreateUrlResponse;
import com.urlshortener.dto.UrlStatsResponse;
import com.urlshortener.exception.InvalidUrlException;
import com.urlshortener.model.ShortenedUrl;
import com.urlshortener.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UrlServiceEdgeCaseTest {

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

    @BeforeEach
    void setUp() {
        urlService = new UrlService(urlRepository, shortCodeGenerator, cacheService,
                clickEventProducer, clickCountService, objectMapper, "http://localhost:8080");
    }

    @Test
    void createShortUrl_codeCollision_retriesSuccessfully() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", null, null);

        when(shortCodeGenerator.generate()).thenReturn("aaa1111", "aaa1111", "bbb2222");
        when(urlRepository.existsByShortCode("aaa1111")).thenReturn(true);
        when(urlRepository.existsByShortCode("bbb2222")).thenReturn(false);
        when(urlRepository.save(any(ShortenedUrl.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateUrlResponse response = urlService.createShortUrl(request);

        assertThat(response.shortCode()).isEqualTo("bbb2222");
        verify(shortCodeGenerator, times(3)).generate();
    }

    @Test
    void createShortUrl_allCollisions_throwsException() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", null, null);

        when(shortCodeGenerator.generate()).thenReturn("col1234");
        when(urlRepository.existsByShortCode("col1234")).thenReturn(true);

        assertThatThrownBy(() -> urlService.createShortUrl(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to generate unique short code");

        verify(shortCodeGenerator, times(5)).generate();
    }

    @Test
    void createShortUrl_invalidUrl_noScheme() {
        CreateUrlRequest request = new CreateUrlRequest("not-a-url", null, null);

        assertThatThrownBy(() -> urlService.createShortUrl(request))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void createShortUrl_invalidUrl_empty() {
        CreateUrlRequest request = new CreateUrlRequest("", null, null);

        assertThatThrownBy(() -> urlService.createShortUrl(request))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void resolveUrl_cacheHit_noDbQuery() {
        when(cacheService.getCachedUrl("cached1")).thenReturn(Optional.of("https://cached.example.com"));

        String result = urlService.resolveUrl("cached1", "127.0.0.1", "agent", "ref");

        assertThat(result).isEqualTo("https://cached.example.com");
        verify(urlRepository, never()).findByShortCode(anyString());
        verify(urlRepository, never()).findByShortCodeAndActiveTrue(anyString());
        verify(clickEventProducer).publishClickEvent("cached1", "127.0.0.1", "agent", "ref");
    }

    @Test
    void getUrlStats_cachedStats() throws JsonProcessingException {
        UrlStatsResponse cachedResponse = new UrlStatsResponse(
                "stat1", "http://localhost:8080/stat1", "https://example.com",
                42L, true, ZonedDateTime.now(), null);

        when(cacheService.getCachedStats("stat1")).thenReturn(Optional.of("{\"cached\":true}"));
        when(objectMapper.readValue("{\"cached\":true}", UrlStatsResponse.class)).thenReturn(cachedResponse);

        UrlStatsResponse result = urlService.getUrlStats("stat1");

        assertThat(result.shortCode()).isEqualTo("stat1");
        assertThat(result.clickCount()).isEqualTo(42L);
        verify(urlRepository, never()).findByShortCode(anyString());
    }
}
