// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.samples.referencing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/** Deterministic fixtures used by the executable sample and automated tests. */
public final class DocumentScenarios {
    private static final ObjectMapper JSON = new ObjectMapper();
    public record Scenario(String name, ObjectNode document) {
        @Override
        public String toString() {
            return name;
        }
    }

    private DocumentScenarios() {}

    public static List<Scenario> largeDocuments() {
        ObjectNode string = JSON.createObjectNode().put("text", "x".repeat(600 * 1024));
        ObjectNode deep = JSON.createObjectNode();
        ObjectNode cursor = deep;
        for (int i = 0; i < 24; i++) cursor = cursor.putObject("level" + i);
        cursor.put("text", "d".repeat(700 * 1024));
        ObjectNode wide = JSON.createObjectNode();
        for (int i = 0; i < 130; i++) wide.putObject("section" + i).put("text", "w".repeat(4096));
        ObjectNode array = JSON.createObjectNode();
        var entries = array.putArray("entries");
        for (int i = 0; i < 300; i++) entries.addObject().put("index", i).put("text", "a".repeat(2048));
        ObjectNode aggregate = JSON.createObjectNode();
        for (int i = 0; i < 12; i++) aggregate.putObject("section" + i).put("text", "b".repeat(256 * 1024));
        ObjectNode largerAggregate = JSON.createObjectNode();
        for (int i = 0; i < 16; i++) largerAggregate.putObject("section" + i).put("text", "c".repeat(384 * 1024));
        ObjectNode unicode = JSON.createObjectNode().put("text", "\uD83D\uDE80\u6F22\\\"\n".repeat(60_000))
                .put("decimal", new BigDecimal("1234567890.01234567890123456789"))
                .put("integer", new BigInteger("12345678901234567890123456789012345678901234567890"));
        unicode.putObject("payload").put("id", "application-id").put("partitionKey", "not-the-storage-key")
                .put("sortKey", "application-sort-key").put("kind", "field").putNull("nullable");
        unicode.putArray("emptyArray");
        unicode.putObject("emptyObject");
        return List.of(new Scenario("large-string", string), new Scenario("deep-24", deep),
                new Scenario("wide-130", wide), new Scenario("array-300", array),
                new Scenario("aggregate-3MiB", aggregate), new Scenario("aggregate-6MiB", largerAggregate),
                new Scenario("unicode-and-types", unicode));
    }
}
