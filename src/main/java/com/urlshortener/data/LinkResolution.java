package com.urlshortener.data;

public record LinkResolution(Status status, String url) {
    public enum Status {
        FOUND,
        NOT_FOUND,
        EXPIRED_OR_DISABLED
    }
}