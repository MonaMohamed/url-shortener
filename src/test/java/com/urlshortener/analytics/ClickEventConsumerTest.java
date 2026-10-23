package com.urlshortener.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.model.ClickEvent;
import com.urlshortener.repository.ClickEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClickEventConsumerTest {

    @Mock
    private ClickEventRepository clickEventRepository;

    private ObjectMapper objectMapper;

    private ClickEventConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        consumer = new ClickEventConsumer(clickEventRepository, objectMapper);
    }

    @Test
    void consume_success() {
        UUID eventId = UUID.randomUUID();
        String json = buildEventJson(eventId, "testcode", "127.0.0.1", "Mozilla/5.0", "https://ref.com");

        when(clickEventRepository.existsByEventId(eventId)).thenReturn(false);

        consumer.consume(json);

        ArgumentCaptor<ClickEvent> captor = ArgumentCaptor.forClass(ClickEvent.class);
        verify(clickEventRepository).save(captor.capture());

        ClickEvent saved = captor.getValue();
        assertThat(saved.getEventId()).isEqualTo(eventId);
        assertThat(saved.getShortCode()).isEqualTo("testcode");
        assertThat(saved.getIpAddress()).isEqualTo("127.0.0.1");
        assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(saved.getReferer()).isEqualTo("https://ref.com");
    }

    @Test
    void consume_duplicate() {
        UUID eventId = UUID.randomUUID();
        String json = buildEventJson(eventId, "dup1", "10.0.0.1", "agent", "ref");

        when(clickEventRepository.existsByEventId(eventId)).thenReturn(true);

        consumer.consume(json);

        verify(clickEventRepository, never()).save(any());
    }

    @Test
    void consume_invalidJson() {
        assertThatCode(() -> consumer.consume("not valid json {{{"))
                .doesNotThrowAnyException();

        verify(clickEventRepository, never()).save(any());
    }

    @Test
    void consume_missingFields() {
        String incomplete = "{\"eventId\":\"" + UUID.randomUUID() + "\"}";

        assertThatCode(() -> consumer.consume(incomplete))
                .doesNotThrowAnyException();

        verify(clickEventRepository, never()).save(any());
    }

    private String buildEventJson(UUID eventId, String shortCode, String ip, String ua, String referer) {
        return String.format(
                "{\"eventId\":\"%s\",\"shortCode\":\"%s\",\"clickedAt\":\"%s\",\"ipAddress\":\"%s\",\"userAgent\":\"%s\",\"referer\":\"%s\"}",
                eventId, shortCode, Instant.now().toString(), ip, ua, referer
        );
    }
}
