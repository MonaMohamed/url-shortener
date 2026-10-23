package com.urlshortener.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClickEventProducerTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private ObjectMapper objectMapper;

    private ClickEventProducer producer;

    private static final String TOPIC = "url-clicks-test";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        producer = new ClickEventProducer(kafkaTemplate, objectMapper, TOPIC);
    }

    @Test
    void publishClickEvent_success() {
        CompletableFuture<SendResult<String, String>> future = completedSendFuture();
        when(kafkaTemplate.send(eq(TOPIC), eq("abc123"), anyString())).thenReturn(future);

        producer.publishClickEvent("abc123", "127.0.0.1", "Mozilla/5.0", "https://example.com");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TOPIC), eq("abc123"), payloadCaptor.capture());

        String payload = payloadCaptor.getValue();
        assertThatCode(() -> objectMapper.readTree(payload)).doesNotThrowAnyException();
    }

    @Test
    void publishClickEvent_withNullFields() {
        CompletableFuture<SendResult<String, String>> future = completedSendFuture();
        when(kafkaTemplate.send(eq(TOPIC), eq("xyz789"), anyString())).thenReturn(future);

        assertThatCode(() ->
                producer.publishClickEvent("xyz789", null, null, null)
        ).doesNotThrowAnyException();

        verify(kafkaTemplate).send(eq(TOPIC), eq("xyz789"), anyString());
    }

    @Test
    void publishClickEvent_kafkaFailure_noExceptionPropagated() {
        when(kafkaTemplate.send(eq(TOPIC), eq("fail1"), anyString()))
                .thenThrow(new RuntimeException("Kafka broker unavailable"));

        assertThatCode(() ->
                producer.publishClickEvent("fail1", "127.0.0.1", "agent", "ref")
        ).doesNotThrowAnyException();
    }

    private CompletableFuture<SendResult<String, String>> completedSendFuture() {
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition(TOPIC, 0), 0L, 0, 0L, 0, 0);
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, "key", "value");
        SendResult<String, String> sendResult = new SendResult<>(record, metadata);
        return CompletableFuture.completedFuture(sendResult);
    }
}
