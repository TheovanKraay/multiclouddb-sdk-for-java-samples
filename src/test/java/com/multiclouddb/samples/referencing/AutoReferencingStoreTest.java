// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.samples.referencing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static com.multiclouddb.samples.referencing.AutoReferencingStore.Mode.*;
import static org.junit.jupiter.api.Assertions.*;

class AutoReferencingStoreTest {
    static final ObjectMapper JSON = new ObjectMapper();

    static Stream<org.junit.jupiter.params.provider.Arguments> scenarios() {
        return DocumentScenarios.largeDocuments().stream().flatMap(s -> Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(s.name(), s.document(), WHEN_OVERSIZED),
                org.junit.jupiter.params.provider.Arguments.of(s.name(), s.document(), ALWAYS)));
    }

    @ParameterizedTest(name = "{0} / {2}")
    @MethodSource("scenarios")
    void roundTripsLargeShapes(String name, ObjectNode input, AutoReferencingStore.Mode mode) {
        MemoryRecords records = new MemoryRecords();
        var store = new AutoReferencingStore(records, AutoReferencingStore.DEFAULT_ITEM_BYTES);
        String before = AutoReferencingStore.encode(input);
        var receipt = store.write("tenant", "logical", input, mode);
        assertTrue(receipt.logicalBytes() > 400 * 1024, "Fixture must actually exceed DynamoDB item limit");
        assertTrue(receipt.fragmentCount() > 1);
        assertEquals(AutoReferencingStore.decode(before), store.read("tenant", "logical"));
        assertEquals(before, AutoReferencingStore.encode(input), "Write must not mutate caller data");
        assertEquals(receipt.fragmentCount() + 1, records.values.size());
        assertEquals("logical", records.writes.get(records.writes.size() - 1).sk());
        for (var entry : records.values.entrySet()) {
            assertEquals("tenant", entry.getKey().pk());
            assertTrue(AutoReferencingStore.physicalBytes(entry.getKey().pk(), entry.getKey().sk(), entry.getValue())
                    <= AutoReferencingStore.DEFAULT_ITEM_BYTES);
        }
        if (name.equals("wide-130") && mode == ALWAYS) assertTrue(receipt.fragmentCount() > 100);
        if (name.equals("aggregate-3MiB")) assertTrue(receipt.logicalBytes() > 2 * 1024 * 1024);
        if (name.equals("aggregate-6MiB")) assertTrue(receipt.logicalBytes() > 4 * 1024 * 1024);
    }

    @Test
    void smallDocumentsAndMissingValues() {
        var records = new MemoryRecords();
        var store = new AutoReferencingStore(records, 4096);
        assertNull(store.read("p", "missing"));
        ObjectNode input = JSON.createObjectNode();
        input.put("id", "user-id").put("partitionKey", "user-pk").put("sortKey", "user-sk");
        input.putNull("nullValue").put("bool", false).put("number", 1.0);
        input.putObject("object");
        input.putArray("array");
        var receipt = store.write("p", "s", input, WHEN_OVERSIZED);
        assertEquals(0, receipt.fragmentCount(), "Small documents must stay inline in threshold mode");
        assertEquals(AutoReferencingStore.decode(AutoReferencingStore.encode(input)), store.read("p", "s"));
    }

    @Test
    void planningFailureWritesNothing() {
        var records = new MemoryRecords();
        var store = new AutoReferencingStore(records, 4096);
        ObjectNode tooDeep = JSON.createObjectNode();
        ObjectNode cursor = tooDeep;
        for (int i = 0; i < 66; i++) cursor = cursor.putObject("child");
        assertThrows(IllegalArgumentException.class, () -> store.write("p", "s", tooDeep, ALWAYS));
        assertThrows(IllegalArgumentException.class, () -> store.write("p", "_ar_reserved", JSON.createObjectNode(), ALWAYS));
        ObjectNode tooLarge = JSON.createObjectNode().put("text", "x".repeat(AutoReferencingStore.MAX_LOGICAL_BYTES));
        assertThrows(IllegalArgumentException.class, () -> store.write("p", "s", tooLarge, WHEN_OVERSIZED));
        ObjectNode hugeField = JSON.createObjectNode().put("x".repeat(5000), "value");
        assertThrows(IllegalArgumentException.class, () -> store.write("p", "s", hugeField, ALWAYS));
        assertThrows(IllegalArgumentException.class, () -> store.write("p", "s",
                JSON.createObjectNode().put("bad", "\uD800"), WHEN_OVERSIZED));
        assertTrue(records.values.isEmpty());
    }

    @Test
    void failedFragmentWritesDoNotPublishOrDamageOldGeneration() {
        var records = new MemoryRecords();
        var store = new AutoReferencingStore(records, 4096);
        ObjectNode old = JSON.createObjectNode().put("version", "old");
        store.write("p", "s", old, WHEN_OVERSIZED);
        int published = records.publishes;
        records.failAtCreate = records.creates + 3;
        var error = assertThrows(IllegalStateException.class, () -> store.write("p", "s",
                JSON.createObjectNode().put("text", "x".repeat(500 * 1024)), WHEN_OVERSIZED));
        assertTrue(error.getMessage().contains("fragments retained"));
        assertEquals(published, records.publishes);
        assertEquals(old, store.read("p", "s"));
        assertTrue(records.values.size() > 2, "Unpublished generation retained for explicit GC");
    }

    @Test
    void publicationFailuresBeforeAndAfterCommitAreNotRolledBack() {
        for (boolean commit : List.of(false, true)) {
            var records = new MemoryRecords();
            var store = new AutoReferencingStore(records, 4096);
            ObjectNode old = JSON.createObjectNode().put("version", "old");
            ObjectNode next = JSON.createObjectNode().put("version", "new");
            store.write("p", "s", old, WHEN_OVERSIZED);
            records.failPublication = true;
            records.commitBeforeFailure = commit;
            assertThrows(IllegalStateException.class, () -> store.write("p", "s", next, ALWAYS));
            assertEquals(commit ? next : old, store.read("p", "s"));
        }
    }

    @Test
    void replacementDuringReadDoesNotMixGenerations() {
        var records = new MemoryRecords();
        var store = new AutoReferencingStore(records, 4096);
        ObjectNode old = JSON.createObjectNode().put("text", "a".repeat(500 * 1024));
        ObjectNode next = JSON.createObjectNode().put("text", "b".repeat(600 * 1024));
        store.write("p", "s", old, WHEN_OVERSIZED);
        AtomicBoolean replace = new AtomicBoolean(true);
        records.afterRootRead = () -> {
            if (replace.getAndSet(false)) store.write("p", "s", next, WHEN_OVERSIZED);
        };
        assertEquals(old, store.read("p", "s"));
        assertEquals(next, store.read("p", "s"));
    }

    @Test
    void missingCorruptAndCrossGenerationFragmentsFailExplicitly() {
        for (String fault : List.of("missing", "checksum", "cycle", "foreign")) {
            var records = new MemoryRecords();
            var store = new AutoReferencingStore(records, 4096);
            var receipt = store.write("p", "s", JSON.createObjectNode().put("value", "original"), ALWAYS);
            var key = new Key("p", receipt.fragmentKeys().get(0));
            if (fault.equals("missing")) records.values.remove(key);
            else {
                String payload = switch (fault) {
                    case "checksum" -> "{\"kind\":\"value\",\"value\":{\"value\":\"tampered\"}}";
                    case "cycle" -> "{\"kind\":\"field\",\"name\":\"a\",\"child\":\"" + key.sk() + "\"}";
                    default -> "{\"kind\":\"field\",\"name\":\"a\",\"child\":\"_ar_foreign_0\"}";
                };
                records.values.get(key).put("payload", payload);
            }
            assertThrows(IllegalStateException.class, () -> store.read("p", "s"), fault);
        }
    }

    @Test
    void partitionsAndLogicalDocumentsAreIsolated() {
        var records = new MemoryRecords();
        var store = new AutoReferencingStore(records, 4096);
        for (String pk : List.of("p1", "p2")) {
            for (String sk : List.of("s1", "s2")) {
                store.write(pk, sk, JSON.createObjectNode().put("identity", pk + sk), ALWAYS);
            }

        }
        for (String pk : List.of("p1", "p2")) {
            for (String sk : List.of("s1", "s2")) assertEquals(pk + sk, store.read(pk, sk).get("identity").asText());
        }
    }

    @Test
    void overFragmentLimitIsRejectedBeforeWriting() {
        var records = new MemoryRecords();
        var store = new AutoReferencingStore(records, 4096);
        ObjectNode many = JSON.createObjectNode();
        for (int i = 0; i < 5000; i++) many.put("field" + i, "x");
        assertThrows(IllegalArgumentException.class, () -> store.write("p", "s", many, ALWAYS));
        assertTrue(records.values.isEmpty());
    }

    @Test
    void configuredThresholdAndStorageOverheadAreEnforced() {
        var records = new MemoryRecords();
        var store = new AutoReferencingStore(records, 4096);
        var inline = store.write("p", "small", JSON.createObjectNode().put("text", "x".repeat(1000)), WHEN_OVERSIZED);
        var split = store.write("p", "large", JSON.createObjectNode().put("text", "x".repeat(3900)), WHEN_OVERSIZED);
        assertEquals(0, inline.fragmentCount());
        assertTrue(split.fragmentCount() > 0, "Keys/envelope overhead must trigger splitting before raw data reaches budget");
        assertEquals("x".repeat(3900), store.read("p", "large").path("text").asText());
        assertThrows(IllegalArgumentException.class, () -> new AutoReferencingStore(records, 400 * 1024));
    }

    record Key(String pk, String sk) {}

    static final class MemoryRecords implements AutoReferencingStore.Records {
        final Map<Key, Map<String, Object>> values = new HashMap<>();
        final List<Key> writes = new ArrayList<>();
        int creates;
        int publishes;
        int failAtCreate = -1;
        boolean failPublication;
        boolean commitBeforeFailure;
        Runnable afterRootRead = () -> {};

        public void create(String pk, String sk, Map<String, Object> record) {
            if (++creates == failAtCreate) throw new IllegalStateException("Injected fragment failure");
            assertNull(values.putIfAbsent(new Key(pk, sk), new HashMap<>(record)), "Immutable fragment overwritten");
            writes.add(new Key(pk, sk));
        }
        public void publish(String pk, String sk, Map<String, Object> record) {
            if (!failPublication || commitBeforeFailure) {
                values.put(new Key(pk, sk), new HashMap<>(record));
                writes.add(new Key(pk, sk));
                publishes++;
            }
            if (failPublication) throw new IllegalStateException("Injected publication failure");
        }
        public JsonNode read(String pk, String sk) {
            var value = values.get(new Key(pk, sk));
            JsonNode result = value == null ? null : JSON.valueToTree(value);
            if (!sk.startsWith("_ar_")) afterRootRead.run();
            return result;
        }
    }
}
