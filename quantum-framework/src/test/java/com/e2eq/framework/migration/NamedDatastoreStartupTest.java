package com.e2eq.framework.migration;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.test.ExcludedIndexTestModel;
import com.e2eq.framework.test.UnversionedTestModel;
import com.mongodb.client.MongoClient;
import dev.morphia.MorphiaDatastore;
import io.quarkus.mongodb.MongoClientName;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real extension configuration, synthetic named bean, and startup observer. */
@QuarkusTest
@TestProfile(NamedDatastoreStartupTest.Profile.class)
public class NamedDatastoreStartupTest {
    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.mongodb.reviewArchive.connection-string", "mongodb://localhost:27017/?retryWrites=false",
                    "quarkus.morphia.mapper-configs.reviewArchive.database", "pr33-named-startup",
                    "quarkus.morphia.mapper-configs.reviewArchive.packages", "com.e2eq.framework.test",
                    "quarkus.morphia.mapper-configs.reviewArchive.create-indexes", "true");
        }
    }

    @Inject @MongoClientName("reviewArchive") MongoClient client;
    @Inject @Named("reviewArchive") MorphiaDatastore datastore;

    @Test
    void startupIndexesTheActualNamedDatastoreWithExclusions() {
        try {
            assertTrue(indexes(UnversionedTestModel.class).contains(UnversionedBaseModel.REF_NAME_TENANT_UNIQUE_INDEX));
            Set<String> excluded = indexes(ExcludedIndexTestModel.class);
            assertTrue(excluded.contains(ExcludedIndexTestModel.OWN_INDEX));
            assertFalse(excluded.contains(UnversionedBaseModel.REF_NAME_TENANT_UNIQUE_INDEX));
            assertFalse(excluded.contains(UnversionedBaseModel.REF_NAME_TENANT_LOOKUP_INDEX));
        } finally {
            client.getDatabase("pr33-named-startup").drop();
        }
    }

    private Set<String> indexes(Class<?> type) {
        String collection = datastore.getMapper().getEntityModel(type).collectionName();
        return datastore.getDatabase().getCollection(collection).listIndexes().into(new java.util.ArrayList<>())
                .stream().map(index -> index.getString("name")).collect(Collectors.toSet());
    }
}
