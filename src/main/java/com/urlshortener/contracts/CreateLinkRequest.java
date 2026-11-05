package com.urlshortener.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateLinkRequest(
        @NotBlank @Size(max = 2048) String url,
        @Pattern(regexp = "^[A-Za-z0-9_-]{4,32}$") String alias,
        @JsonProperty("ttl_seconds") Long ttlSeconds) {
}