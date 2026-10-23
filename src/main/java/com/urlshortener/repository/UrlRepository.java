package com.urlshortener.repository;

import com.urlshortener.model.ShortenedUrl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UrlRepository extends JpaRepository<ShortenedUrl, Long> {

    Optional<ShortenedUrl> findByShortCode(String shortCode);

    Optional<ShortenedUrl> findByShortCodeAndActiveTrue(String shortCode);

    boolean existsByShortCode(String shortCode);

    @Modifying
    @Query("UPDATE ShortenedUrl u SET u.clickCount = u.clickCount + 1 WHERE u.shortCode = :shortCode")
    int incrementClickCount(@Param("shortCode") String shortCode);

    @Query("SELECT u FROM ShortenedUrl u WHERE u.expiresAt IS NOT NULL AND u.expiresAt < :now AND u.active = true")
    List<ShortenedUrl> findExpiredActiveUrls(@Param("now") ZonedDateTime now);
}