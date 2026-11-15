package com.coldchain.common.json;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single place where we decide how our events look as JSON, so every service
 * (simulator, ingestion, stream processor, api) reads and writes exactly the same format.
 */
public final class EventJson {

    // A JsonMapper is immutable and thread-safe once built, so one shared instance is enough.
    private static final JsonMapper MAPPER = JsonMapper.builder()
            // Instants are written as ISO-8601 strings ("2026-01-15T10:00:00Z"), not as numbers.
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            // Ignore fields we do not know yet: a newer producer can add a field without
            // breaking older consumers (forward compatibility).
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private EventJson() {
        // static helper only
    }

    public static JsonMapper mapper() {
        return MAPPER;
    }
}
