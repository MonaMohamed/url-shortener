package com.urlshortener.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.analytics.ClickEventProducer;
import com.urlshortener.dto.CreateUrlRequest;
import com.urlshortener.service.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnableAutoConfiguration(exclude = {KafkaAutoConfiguration.class})
class UrlShortenerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ClickEventProducer clickEventProducer;

    @MockBean
    private CacheService cacheService;

    @BeforeEach
    void setUp() {
        when(cacheService.getCachedUrl(anyString())).thenReturn(Optional.empty());
        when(cacheService.getCachedStats(anyString())).thenReturn(Optional.empty());
        doNothing().when(cacheService).cacheUrl(anyString(), anyString());
        doNothing().when(cacheService).cacheUrlStats(anyString(), anyString());
        doNothing().when(cacheService).evict(anyString());
        doNothing().when(clickEventProducer).publishClickEvent(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void fullLifecycle() throws Exception {
        // Create a short URL
        MvcResult createResult = mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originalUrl\":\"https://example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").exists())
                .andExpect(jsonPath("$.originalUrl").value("https://example.com"))
                .andReturn();

        String shortCode = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("shortCode").asText();

        // Get stats - should show 0 clicks
        mockMvc.perform(get("/api/v1/urls/" + shortCode + "/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value(shortCode))
                .andExpect(jsonPath("$.clickCount").value(0))
                .andExpect(jsonPath("$.active").value(true));

        // Redirect
        mockMvc.perform(get("/" + shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com"));

        // Deactivate
        mockMvc.perform(delete("/api/v1/urls/" + shortCode))
                .andExpect(status().isNoContent());

        // Redirect should fail now
        mockMvc.perform(get("/" + shortCode))
                .andExpect(status().isNotFound());
    }

    @Test
    void createWithCustomAlias() throws Exception {
        String alias = "myalias1";

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originalUrl\":\"https://example.com\",\"customAlias\":\"" + alias + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value(alias));

        // Redirect using custom alias
        mockMvc.perform(get("/" + alias))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com"));
    }

    @Test
    void createDuplicateAlias() throws Exception {
        String alias = "dupalias1";

        // First creation should succeed
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originalUrl\":\"https://example.com\",\"customAlias\":\"" + alias + "\"}"))
                .andExpect(status().isCreated());

        // Second creation with same alias should fail with 409
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originalUrl\":\"https://other.com\",\"customAlias\":\"" + alias + "\"}"))
                .andExpect(status().isConflict());
    }
}
