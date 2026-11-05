package com.urlshortener.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record LinkStats(
        String code,
        @JsonProperty("total_clicks") long totalClicks,
        Map<String, Long> daily) {
}