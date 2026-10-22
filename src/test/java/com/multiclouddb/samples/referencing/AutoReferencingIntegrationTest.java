// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.samples.referencing;

import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.samples.ConfigLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.multiclouddb.samples.referencing.AutoReferencingStore.Mode.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in tests: provision one sample collection and remove only records with this run's unique PK. */
@EnabledIfSystemProperty(named = "referencing.integration", matches = "true")
class AutoReferencingIntegrationTest {
    static MulticloudDbClient client;
    static ResourceAddress address;
    static AutoReferencingStore store;
    static final String PK = "reference-it-" + UUID.randomUUID();

    @BeforeAll
    static void connect() {
        var config = ConfigLoader.load("todo-app-cosmos.properties");
        address = new ResourceAddress(System.getProperty("referencing.database", "multiclouddb-referencing"),
                System.getProperty("referencing.collection", "documents"));
        client = MulticloudDbClientFactory.create(config.sdkWithoutExtendedRetention());
        client.ensureDatabase(address.database());
        client.ensureContainer(address);
        store = new AutoReferencingStore(client, address);
    }

    @AfterAll
    static void close() throws Exception {
        if (client != null) client.close();
    }

    static Stream<DocumentScenarios.Scenario> scenarios() {
        return DocumentScenarios.largeDocuments().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void largeRoundTrip(DocumentScenarios.Scenario scenario) {
        var receipt = store.write(PK, scenario.name(), scenario.document(), WHEN_OVERSIZED);
        try {
            assertTrue(receipt.logicalBytes() > 400 * 1024);
            assertEquals(AutoReferencingStore.decode(AutoReferencingStore.encode(scenario.document())),
                    store.read(PK, scenario.name()));
            verifyPhysicalItems(receipt);
            System.out.printf("LIVE %s %s: %d logical bytes, %d fragments%n",
                    client.providerId(), scenario.name(), receipt.logicalBytes(), receipt.fragmentCount());
        } finally {
            cleanup(List.of(receipt));
        }
    }

    @Test
    void moreThanOneHundredFragmentsAndReplacement() {
        var scenario = DocumentScenarios.largeDocuments().stream().filter(s -> s.name().equals("wide-130"))
                .findFirst().orElseThrow();
        List<AutoReferencingStore.WriteReceipt> receipts = new ArrayList<>();
        try {
            var first = store.write(PK, "wide-replace", scenario.document(), ALWAYS);
            receipts.add(first);
            assertTrue(first.fragmentCount() > 100, "Must exceed native transaction operation ceiling");
            assertEquals(scenario.document(), store.read(PK, "wide-replace"));
            verifyPhysicalItems(first);
            var small = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("updated", true);
            receipts.add(store.write(PK, "wide-replace", small, WHEN_OVERSIZED));
            assertEquals(small, store.read(PK, "wide-replace"));
            assertNotNull(client.read(address, MulticloudDbKey.of(PK, first.fragmentKeys().get(0))),
                    "Old generations must survive until readers are quiescent");
            System.out.printf("LIVE %s ALWAYS: %d fragments; replacement verified%n",
                    client.providerId(), first.fragmentCount());
        } finally {
            cleanup(receipts);
        }
    }

    private static void verifyPhysicalItems(AutoReferencingStore.WriteReceipt receipt) {
        for (String sk : receipt.fragmentKeys()) {
            var record = client.read(address, MulticloudDbKey.of(PK, sk));
            assertNotNull(record);
            // Physical payloads are flat and bounded, even for deep logical documents.
            assertTrue(record.document().path("payload").isTextual());
            assertEquals(receipt.generation(), record.document().path("generation").asText());
        }
        assertTrue(receipt.largestPhysicalBytes() <= AutoReferencingStore.DEFAULT_ITEM_BYTES);
    }

    private static void cleanup(List<AutoReferencingStore.WriteReceipt> receipts) {
        // Only safe because these tests own the isolated partition and have no concurrent readers.
        for (var receipt : receipts) client.delete(address, MulticloudDbKey.of(PK, receipt.sortKey()));
        for (var receipt : receipts) {
            for (String sk : receipt.fragmentKeys()) {
                client.delete(address, MulticloudDbKey.of(PK, sk));
                assertNull(client.read(address, MulticloudDbKey.of(PK, sk)), "Fragment cleanup failed");
            }
            assertNull(store.read(PK, receipt.sortKey()));
        }
    }
}
