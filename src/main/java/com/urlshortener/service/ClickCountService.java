package com.urlshortener.service;

import com.urlshortener.repository.UrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClickCountService {

    private static final Logger log = LoggerFactory.getLogger(ClickCountService.class);

    private final UrlRepository urlRepository;

    public ClickCountService(UrlRepository urlRepository) {
        this.urlRepository = urlRepository;
    }

    @Async
    @Transactional
    public void incrementClickCountAsync(String shortCode) {
        try {
            urlRepository.incrementClickCount(shortCode);
        } catch (Exception e) {
            log.error("Failed to increment click count for {}", shortCode, e);
        }
    }
}
