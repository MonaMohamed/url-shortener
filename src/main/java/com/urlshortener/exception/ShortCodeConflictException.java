package com.urlshortener.exception;

public class ShortCodeConflictException extends RuntimeException {

    private final String shortCode;

    public ShortCodeConflictException(String shortCode) {
        super("Short code already exists: " + shortCode);
        this.shortCode = shortCode;
    }

    public String getShortCode() {
        return shortCode;
    }
}