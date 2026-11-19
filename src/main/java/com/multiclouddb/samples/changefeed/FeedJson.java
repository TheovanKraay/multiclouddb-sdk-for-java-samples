package com.multiclouddb.samples.changefeed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;

/** Application-owned JSON for persisted cursors and event previews. */
final class FeedJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FeedJson() {}

    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Object read(String json, Class<?> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
