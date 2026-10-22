package com.e2eq.framework.test;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/**
 * Drops the shared test realm before Quarkus starts, so every test run begins with an empty
 * {@value #TEST_REALM} database: data, indexes, and migration status ({@code databaseVersion},
 * {@code changeSetRecord}) all live in that database. Startup and {@code BaseRepoTest} migrate and
 * seed it again.
 *
 * <p>The database name is deliberately not configurable: this resource may only drop the dedicated
 * {@value #TEST_REALM} database. Registered for every test in this module through
 * {@code META-INF/services/io.quarkus.test.common.QuarkusTestResourceLifecycleManager}.
 */
public final class ResetTestRealmResource implements QuarkusTestResourceLifecycleManager {

    static final String TEST_REALM = "test-quantum-com";
    private static final String DEFAULT_CONNECTION_STRING = "mongodb://localhost:27017/?retryWrites=false";

    @Override
    public Map<String, String> start() {
        String connectionString = System.getenv().getOrDefault("MONGODB_CONNECTION_STRING", DEFAULT_CONNECTION_STRING);
        try (MongoClient mongoClient = MongoClients.create(connectionString)) {
            mongoClient.getDatabase(TEST_REALM).drop();
        }
        return Map.of();
    }

    @Override
    public void stop() {
        // The test realm is intentionally retained for test diagnostics.
    }
}
