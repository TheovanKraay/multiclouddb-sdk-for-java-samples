// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.samples.referencing;

import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.samples.ConfigLoader;

import java.util.UUID;

public final class AutoReferencingSample {
    private AutoReferencingSample() {}

    public static void main(String[] args) throws Exception {
        var config = ConfigLoader.load("todo-app-cosmos.properties");
        var address = new ResourceAddress(System.getProperty("referencing.database", "multiclouddb-referencing"),
                System.getProperty("referencing.collection", "documents"));
        var mode = AutoReferencingStore.Mode.valueOf(System.getProperty("referencing.mode", "WHEN_OVERSIZED"));
        String selected = System.getProperty("referencing.scenario", "all");
        var scenarios = DocumentScenarios.largeDocuments().stream()
                .filter(s -> selected.equals("all") || selected.equals(s.name())).toList();
        if (scenarios.isEmpty()) throw new IllegalArgumentException("Unknown scenario: " + selected);
        boolean keep = Boolean.parseBoolean(System.getProperty("referencing.keep", "false"));
        try (var client = MulticloudDbClientFactory.create(config.sdkWithoutExtendedRetention())) {
            client.ensureDatabase(address.database());
            client.ensureContainer(address);
            var store = new AutoReferencingStore(client, address);
            String pk = "referencing-" + UUID.randomUUID();
            System.out.println("Provider=" + client.providerId() + ", mode=" + mode + ", partition=" + pk);
            System.out.println("Point writes only. No transactions or atomic batch guarantees.");
            for (var scenario : scenarios) {
                var receipt = store.write(pk, scenario.name(), scenario.document(), mode);
                var actual = store.read(pk, scenario.name());
                var expected = AutoReferencingStore.decode(AutoReferencingStore.encode(scenario.document()));
                if (!expected.equals(actual)) throw new IllegalStateException("Round trip mismatch: " + scenario.name());
                System.out.printf("PASS %s: logical=%d bytes, fragments=%d, largest budgeted item=%d bytes%n",
                        scenario.name(), receipt.logicalBytes(), receipt.fragmentCount(), receipt.largestPhysicalBytes());
                if (!keep) {
                    // This isolated demo has no concurrent readers. Production needs a retention/GC policy.
                    client.delete(address, MulticloudDbKey.of(pk, scenario.name()));
                    for (String sk : receipt.fragmentKeys()) client.delete(address, MulticloudDbKey.of(pk, sk));
                    if (store.read(pk, scenario.name()) != null) throw new IllegalStateException("Root cleanup failed");
                    for (String sk : receipt.fragmentKeys()) {
                        if (client.read(address, MulticloudDbKey.of(pk, sk)) != null) {
                            throw new IllegalStateException("Fragment cleanup failed: " + sk);
                        }
                    }
                }
            }
            System.out.println(keep ? "Data retained; logical reads require this sample wrapper."
                    : "Verified test records removed; empty schema retained.");
        }
    }
}
