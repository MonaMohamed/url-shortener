package com.urlshortener.exception;

public class InvalidUrlException extends RuntimeException {

    private final String url;
    private final String reason;

    public InvalidUrlException(String url, String reason) {
        super("Invalid URL '" + url + "': " + reason);
        this.url = url;
        this.reason = reason;
    }

    public String getUrl() {
        return url;
    }

    public String getReason() {
        return reason;
    }
}