// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.samples.referencing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiclouddb.api.DocumentResult;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.ResourceAddress;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Application-layer experiment, not an SDK feature or a transaction implementation.
 * Immutable generations are retained so a reader can finish after a concurrent replacement.
 */
public final class AutoReferencingStore {
    public enum Mode { WHEN_OVERSIZED, ALWAYS }

    public static final int DEFAULT_ITEM_BYTES = 300 * 1024;
    public static final int MAX_LOGICAL_BYTES = 16 * 1024 * 1024;
    public static final int MAX_FRAGMENTS = 8192;
    private static final int MAX_DEPTH = 64;
    private static final String FORMAT = "auto-reference-v1";
    private static final String PREFIX = "_ar_";
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    /** Minimal seam for deterministic failures in tests; production uses only portable SDK calls. */
    public interface Records {
        void create(String partitionKey, String sortKey, Map<String, Object> record);
        void publish(String partitionKey, String sortKey, Map<String, Object> record);
        JsonNode read(String partitionKey, String sortKey);
    }

    public record WriteReceipt(String partitionKey, String sortKey, String generation,
                               int fragmentCount, int logicalBytes, int largestPhysicalBytes) {
        public List<String> fragmentKeys() {
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < fragmentCount; i++) keys.add(fragmentKey(generation, i));
            return List.copyOf(keys);
        }
    }

    private final Records records;
    private final int itemBytes;

    public AutoReferencingStore(MulticloudDbClient client, ResourceAddress address) {
        this(sdkRecords(client, address), DEFAULT_ITEM_BYTES);
    }

    public AutoReferencingStore(Records records, int itemBytes) {
        this.records = Objects.requireNonNull(records, "records");
        if (itemBytes < 4096 || itemBytes > 350 * 1024) {
            throw new IllegalArgumentException("Physical budget must be between 4 KiB and 350 KiB");
        }
        this.itemBytes = itemBytes;
    }

    private static Records sdkRecords(MulticloudDbClient client, ResourceAddress address) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(address, "address");
        return new Records() {
            public void create(String pk, String sk, Map<String, Object> record) {
                client.create(address, MulticloudDbKey.of(pk, sk), record);
            }
            public void publish(String pk, String sk, Map<String, Object> record) {
                client.upsert(address, MulticloudDbKey.of(pk, sk), record);
            }
            public JsonNode read(String pk, String sk) {
                DocumentResult result = client.read(address, MulticloudDbKey.of(pk, sk));
                return result == null ? null : result.document();
            }
        };
    }

    public WriteReceipt write(String partitionKey, String sortKey, ObjectNode document, Mode mode) {
        validateKey(partitionKey, sortKey);
        Objects.requireNonNull(mode, "mode");
        validateTree(Objects.requireNonNull(document, "document"), 0);
        byte[] bytes = encode(document).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_LOGICAL_BYTES) {
            throw new IllegalArgumentException("Logical document exceeds 16 MiB sample limit");
        }
        // A JSON snapshot prevents subsequent caller mutation from changing the planned generation.
        JsonNode snapshot = decode(new String(bytes, StandardCharsets.UTF_8));
        bytes = encode(snapshot).getBytes(StandardCharsets.UTF_8);
        String generation = UUID.randomUUID().toString();
        Plan plan = new Plan(partitionKey, generation, mode);
        Map<String, Object> manifest = new LinkedHashMap<>(Map.of(
                "format", FORMAT, "generation", generation, "count", 0,
                "bytes", bytes.length, "sha256", digest(bytes)));
        manifest.put("payload", encode(snapshot));
        if (mode == Mode.ALWAYS || physicalBytes(partitionKey, sortKey, manifest) > itemBytes) {
            manifest.remove("payload");
            manifest.put("root", plan.split(snapshot, mode == Mode.ALWAYS));
            manifest.put("count", plan.fragments.size());
        }
        plan.largest = Math.max(plan.largest, checkedSize(partitionKey, sortKey, manifest));

        // Never delete on failure: a timeout can mean a write (including publication) committed.
        try {
            for (int i = 0; i < plan.fragments.size(); i++) {
                records.create(partitionKey, fragmentKey(generation, i), plan.fragments.get(i));
            }
            records.publish(partitionKey, sortKey, manifest);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Write failed or outcome unknown for generation " + generation
                    + "; fragments retained. Re-read the root before deciding recovery.", failure);
        }
        return new WriteReceipt(partitionKey, sortKey, generation, plan.fragments.size(), bytes.length, plan.largest);
    }

    public ObjectNode read(String partitionKey, String sortKey) {
        validateKey(partitionKey, sortKey);
        JsonNode manifest = records.read(partitionKey, sortKey);
        if (manifest == null) return null;
        require(FORMAT.equals(text(manifest, "format")), "Unknown root format");
        String generation = text(manifest, "generation");
        require(generation.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid generation");
        int count = integer(manifest, "count");
        int bytes = integer(manifest, "bytes");
        require(count >= 0 && count <= MAX_FRAGMENTS, "Invalid fragment count");
        require(bytes > 0 && bytes <= MAX_LOGICAL_BYTES, "Invalid logical size");
        Reader reader = new Reader(partitionKey, generation, count);
        JsonNode document = count == 0 ? decode(text(manifest, "payload")) : reader.load(text(manifest, "root"), 0);
        require(document.isObject(), "Root must reconstruct an object");
        validateTree(document, 0);
        require(reader.seen.size() == count, "Manifest count does not match references");
        byte[] actual = encode(document).getBytes(StandardCharsets.UTF_8);
        require(actual.length == bytes && digest(actual).equals(text(manifest, "sha256")),
                "Logical document checksum/size mismatch");
        return (ObjectNode) document;
    }

    private final class Plan {
        final String pk;
        final String generation;
        final Mode mode;
        final List<Map<String, Object>> fragments = new ArrayList<>();
        int largest;

        Plan(String pk, String generation, Mode mode) {
            this.pk = pk;
            this.generation = generation;
            this.mode = mode;
        }

        String split(JsonNode value, boolean force) {
            ObjectNode leaf = JSON.createObjectNode().put("kind", "value");
            leaf.set("value", value);
            Map<String, Object> record = envelope(leaf);
            if ((!force || !value.isContainerNode() || value.isEmpty())
                    && physicalBytes(pk, fragmentKey(generation, fragments.size()), record) <= itemBytes) {
                return add(record);
            }
            if (value.isObject()) {
                List<String> names = new ArrayList<>();
                value.fieldNames().forEachRemaining(names::add);
                if (names.size() == 1) {
                    String name = names.get(0);
                    String child = split(value.get(name), mode == Mode.ALWAYS);
                    return add(envelope(JSON.createObjectNode().put("kind", "field")
                            .put("name", name).put("child", child)));
                }
                require(names.size() > 1, "Object cannot fit physical budget");
                ObjectNode left = JSON.createObjectNode();
                ObjectNode right = JSON.createObjectNode();
                for (int i = 0; i < names.size(); i++) {
                    (i < names.size() / 2 ? left : right).set(names.get(i), value.get(names.get(i)));
                }
                return branch("object", split(left, force), split(right, force));
            }
            if (value.isArray()) {
                require(!value.isEmpty(), "Array cannot fit physical budget");
                if (value.size() == 1) {
                    return add(envelope(JSON.createObjectNode().put("kind", "element")
                            .put("child", split(value.get(0), mode == Mode.ALWAYS))));
                }
                ArrayNode left = JSON.createArrayNode();
                ArrayNode right = JSON.createArrayNode();
                for (int i = 0; i < value.size(); i++) {
                    (i < value.size() / 2 ? left : right).add(value.get(i));
                }
                return branch("array", split(left, force), split(right, force));
            }
            if (value.isTextual()) {
                String text = value.textValue();
                int midpoint = text.length() / 2;
                if (midpoint > 0 && Character.isHighSurrogate(text.charAt(midpoint - 1))) midpoint--;
                require(midpoint > 0, "Scalar cannot fit physical budget");
                return branch("string", split(JSON.getNodeFactory().textNode(text.substring(0, midpoint)), false),
                        split(JSON.getNodeFactory().textNode(text.substring(midpoint)), false));
            }
            throw new IllegalArgumentException("Scalar cannot fit physical budget");
        }

        String branch(String kind, String left, String right) {
            return add(envelope(JSON.createObjectNode().put("kind", kind).put("left", left).put("right", right)));
        }

        Map<String, Object> envelope(JsonNode payload) {
            // JSON text preserves application key names, numeric precision and depth across beta SDK adapters.
            return Map.of("format", FORMAT, "generation", generation, "payload", encode(payload));
        }

        String add(Map<String, Object> record) {
            if (fragments.size() >= MAX_FRAGMENTS) throw new IllegalArgumentException("Too many fragments");
            String key = fragmentKey(generation, fragments.size());
            largest = Math.max(largest, checkedSize(pk, key, record));
            fragments.add(record);
            return key;
        }
    }

    private final class Reader {
        final String pk;
        final String generation;
        final int count;
        final Set<String> seen = new HashSet<>();
        long payloadBytes;

        Reader(String pk, String generation, int count) {
            this.pk = pk;
            this.generation = generation;
            this.count = count;
        }

        JsonNode load(String key, int depth) {
            require(depth <= 256, "Reference depth exceeds sample limit");
            String prefix = PREFIX + generation + "_";
            require(key.startsWith(prefix), "Reference points outside generation");
            String index = key.substring(prefix.length());
            require(index.matches("0|[1-9][0-9]{0,4}"), "Invalid fragment index");
            require(Integer.parseInt(index) < count && seen.add(key), "Invalid/repeated fragment reference");
            JsonNode record = records.read(pk, key);
            require(record != null, "Missing fragment " + key + "; retry the whole read if visibility is delayed");
            require(FORMAT.equals(text(record, "format"))
                    && generation.equals(text(record, "generation")), "Fragment format/generation mismatch");
            String payload = text(record, "payload");
            payloadBytes += payload.getBytes(StandardCharsets.UTF_8).length;
            require(payloadBytes <= 4L * MAX_LOGICAL_BYTES, "Fragment payload budget exceeded");
            JsonNode node = decode(payload);
            String kind = text(node, "kind");
            return switch (kind) {
                case "value" -> {
                    require(node.has("value"), "Missing fragment value");
                    JsonNode value = node.get("value");
                    validateTree(value, 0);
                    yield value;
                }
                case "field" -> JSON.createObjectNode().set(text(node, "name"), load(text(node, "child"), depth + 1));
                case "element" -> JSON.createArrayNode().add(load(text(node, "child"), depth + 1));
                case "object", "array", "string" -> {
                    JsonNode left = load(text(node, "left"), depth + 1);
                    JsonNode right = load(text(node, "right"), depth + 1);
                    if (kind.equals("object")) {
                        require(left.isObject() && right.isObject(), "Expected object fragments");
                        right.fieldNames().forEachRemaining(name -> require(!left.has(name), "Duplicate object field"));
                        yield ((ObjectNode) left).setAll((ObjectNode) right);
                    }
                    if (kind.equals("array")) {
                        require(left.isArray() && right.isArray(), "Expected array fragments");
                        yield ((ArrayNode) left).addAll((ArrayNode) right);
                    }
                    require(left.isTextual() && right.isTextual(), "Expected string fragments");
                    yield JSON.getNodeFactory().textNode(left.textValue() + right.textValue());
                }
                default -> throw new IllegalStateException("Unknown fragment kind: " + kind);
            };
        }
    }

    private int checkedSize(String pk, String sk, Map<String, Object> record) {
        int size = physicalBytes(pk, sk, record);
        if (size > itemBytes) throw new IllegalArgumentException("Physical record exceeds configured budget");
        return size;
    }

    static int physicalBytes(String pk, String sk, Map<String, Object> record) {
        Map<String, Object> physical = new LinkedHashMap<>(record);
        physical.put("partitionKey", pk);
        physical.put("sortKey", sk);
        physical.put("id", sk);
        return encode(physical).getBytes(StandardCharsets.UTF_8).length + 1024;
    }

    private static String fragmentKey(String generation, int index) {
        return PREFIX + generation + "_" + index;
    }

    private static void validateKey(String pk, String sk) {
        if (pk == null || pk.isBlank() || pk.getBytes(StandardCharsets.UTF_8).length > 1024
                || sk == null || sk.isBlank() || sk.getBytes(StandardCharsets.UTF_8).length > 512
                || sk.startsWith(PREFIX) || sk.chars().anyMatch(c -> "/\\?#".indexOf(c) >= 0)) {
            throw new IllegalArgumentException("Use a nonblank PK <= 1024 bytes and SK <= 512 bytes without /, \\, ?, # or _ar_ prefix");
        }
        validateText(pk);
        validateText(sk);
    }

    private static void validateTree(JsonNode value, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Logical depth exceeds 64");
        if (value.isContainerNode()) {
            if (value.isObject()) value.fieldNames().forEachRemaining(AutoReferencingStore::validateText);
            value.forEach(child -> validateTree(child, depth + 1));
        } else if (!(value.isNull() || value.isTextual() || value.isBoolean() || value.isNumber())
                || (value.isFloatingPointNumber() && !value.isBigDecimal() && !Double.isFinite(value.doubleValue()))) {
            throw new IllegalArgumentException("Only finite JSON values are supported");
        }
        if (value.isTextual()) validateText(value.textValue());
    }

    private static void validateText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw new IllegalArgumentException("Unpaired Unicode surrogate");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("Unpaired Unicode surrogate");
            }
        }
    }

    private static String text(JsonNode node, String name) {
        require(node != null && node.path(name).isTextual(), "Missing/invalid " + name);
        return node.get(name).textValue();
    }

    private static int integer(JsonNode node, String name) {
        require(node.path(name).isIntegralNumber() && node.get(name).canConvertToInt(), "Missing/invalid " + name);
        return node.get(name).intValue();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    static String encode(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot encode JSON", e);
        }
    }

    static JsonNode decode(String value) {
        try {
            return JSON.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid stored JSON", e);
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
