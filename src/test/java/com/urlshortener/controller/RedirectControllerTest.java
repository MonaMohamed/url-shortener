package com.urlshortener.controller;

import com.urlshortener.exception.UrlExpiredException;
import com.urlshortener.exception.UrlNotFoundException;
import com.urlshortener.service.UrlService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RedirectController.class)
@ActiveProfiles("test")
class RedirectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UrlService urlService;

    @Test
    void redirect_success() throws Exception {
        when(urlService.resolveUrl(eq("abc1234"), nullable(String.class),
                nullable(String.class), nullable(String.class)))
                .thenReturn("https://example.com");

        mockMvc.perform(get("/abc1234"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com"));
    }

    @Test
    void redirect_notFound() throws Exception {
        when(urlService.resolveUrl(eq("missing1"), nullable(String.class),
                nullable(String.class), nullable(String.class)))
                .thenThrow(new UrlNotFoundException("missing1"));

        mockMvc.perform(get("/missing1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void redirect_expired() throws Exception {
        when(urlService.resolveUrl(eq("expired1"), nullable(String.class),
                nullable(String.class), nullable(String.class)))
                .thenThrow(new UrlExpiredException("expired1"));

        mockMvc.perform(get("/expired1"))
                .andExpect(status().isGone());
    }

    @Test
    void redirect_extractsHeaders() throws Exception {
        when(urlService.resolveUrl(eq("abc1234"), eq("10.0.0.1"),
                eq("TestAgent"), eq("https://referer.com")))
                .thenReturn("https://example.com");

        mockMvc.perform(get("/abc1234")
                        .header("X-Forwarded-For", "10.0.0.1")
                        .header("User-Agent", "TestAgent")
                        .header("Referer", "https://referer.com"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com"));

        verify(urlService).resolveUrl("abc1234", "10.0.0.1", "TestAgent", "https://referer.com");
    }
}
