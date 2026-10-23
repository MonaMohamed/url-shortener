package com.urlshortener.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    public static final String CLICKS_TOPIC = "url-clicks";

    @Bean
    public NewTopic clicksTopic() {
        return TopicBuilder.name(CLICKS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}