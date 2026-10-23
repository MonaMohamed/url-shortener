package com.urlshortener.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.model.ClickEvent;
import com.urlshortener.repository.ClickEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

@Service
public class ClickEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ClickEventConsumer.class);

    private final ClickEventRepository clickEventRepository;
    private final ObjectMapper objectMapper;

    public ClickEventConsumer(ClickEventRepository clickEventRepository,
                              ObjectMapper objectMapper) {
        this.clickEventRepository = clickEventRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${app.kafka.topic:url-clicks}",
                   groupId = "${spring.kafka.consumer.group-id:url-shortener-group}")
    public void consume(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);

            String eventIdStr = node.get("eventId").asText();
            UUID eventId = UUID.fromString(eventIdStr);

            if (clickEventRepository.existsByEventId(eventId)) {
                log.debug("Duplicate click event ignored: {}", eventIdStr);
                return;
            }

            ClickEvent event = new ClickEvent();
            event.setEventId(eventId);
            event.setShortCode(node.get("shortCode").asText());
            event.setClickedAt(Instant.parse(node.get("clickedAt").asText()).atZone(ZoneOffset.UTC));
            event.setIpAddress(node.get("ipAddress").asText());
            event.setUserAgent(node.get("userAgent").asText());
            event.setReferer(node.get("referer").asText());

            clickEventRepository.save(event);
            log.debug("Click event persisted for short code: {}", event.getShortCode());

        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize click event: {}", message, e);
        } catch (Exception e) {
            log.error("Failed to process click event: {}", message, e);
        }
    }
}
