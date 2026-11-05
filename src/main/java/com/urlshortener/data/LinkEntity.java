package com.urlshortener.data;

public record LinkEntity(String code, String url, long created, Long expires) {
}