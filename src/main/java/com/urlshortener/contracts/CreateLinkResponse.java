package com.urlshortener.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateLinkResponse(String code, @JsonProperty("short_url") String shortUrl) {
}