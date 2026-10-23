package com.urlshortener.exception;

public class UrlExpiredException extends RuntimeException {

    private final String shortCode;

    public UrlExpiredException(String shortCode) {
        super("URL has expired for code: " + shortCode);
        this.shortCode = shortCode;
    }

    public String getShortCode() {
        return shortCode;
    }
}