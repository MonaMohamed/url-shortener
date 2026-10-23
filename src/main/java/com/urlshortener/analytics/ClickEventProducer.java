package com.urlshortener.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class ClickEventProducer {

    private static final Logger log = LoggerFactory.getLogger(ClickEventProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public ClickEventProducer(KafkaTemplate<String, String> kafkaTemplate,
                              ObjectMapper objectMapper,
                              @Value("${app.kafka.topic:url-clicks}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    public void publishClickEvent(String shortCode, String ipAddress, String userAgent, String referer) {
        try {
            Map<String, String> event = Map.of(
                    "eventId", UUID.randomUUID().toString(),
                    "shortCode", shortCode,
                    "clickedAt", Instant.now().toString(),
                    "ipAddress", ipAddress != null ? ipAddress : "",
                    "userAgent", userAgent != null ? userAgent : "",
                    "referer", referer != null ? referer : ""
            );

            String payload = objectMapper.writeValueAsString(event);

            kafkaTemplate.send(topic, shortCode, payload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish click event for {}: {}", shortCode, ex.getMessage());
                        } else {
                            log.debug("Click event published for {} to partition {}",
                                    shortCode, result.getRecordMetadata().partition());
                        }
                    });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize click event for {}", shortCode, e);
        } catch (Exception e) {
            log.error("Unexpected error publishing click event for {}", shortCode, e);
        }
    }
}
