package com.urlshortener.data;

import java.util.Map;
import java.util.Optional;

public interface LinkDataProvider {
    void addLink(LinkEntity link);

    LinkResolution resolveAndTrack(String code, long now, String utcDay);

    Optional<Map<String, Long>> getDailyClicks(String code);

    boolean disable(String code);

    boolean canConnect();
}