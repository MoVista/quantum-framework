package com.e2eq.framework.migration;

import com.e2eq.framework.model.persistent.base.ExcludeInheritedIndexes;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.model.persistent.morphia.DeclaredIndexConfigInterceptor;
import com.e2eq.framework.model.persistent.morphia.DeclaredIndexes;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.persistent.morphia.NamedDatastoreDeclaredIndexes;
import com.e2eq.framework.test.ExcludedIndexTestModel;
import com.e2eq.framework.test.UnversionedTestModel;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import dev.morphia.MorphiaDatastore;
import dev.morphia.annotations.internal.IndexHelper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ExcludeInheritedIndexes} is honored by every framework path that creates declared indexes. */
@QuarkusTest
@io.quarkus.test.junit.TestProfile(DeclaredIndexesTest.AutomaticIndexesProfile.class)
public class DeclaredIndexesTest {

    public static class AutomaticIndexesProfile implements io.quarkus.test.junit.QuarkusTestProfile {
        @Override
        public java.util.Map<String, String> getConfigOverrides() {
            return java.util.Map.of(
                    "quarkus.morphia.create-indexes", "true",
                    "quarkus.morphia.database", "pr33-auto-index-base",
                    "quarkus.morphia.packages", "com.e2eq.framework.model.persistent,com.e2eq.framework.test");
        }
    }

    @Inject
    MorphiaDatastore baseDatastore;

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @Inject
    MigrationService migrationService;

    @Inject
    MongoClient mongoClient;

    @Test
    public void excludedInheritedIndexesAreLeftOutAndOwnIndexesApplied() {
        String realm = "declared-indexes-test-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            Set<String> baseIndexes = indexNames(collection(baseDatastore, ExcludedIndexTestModel.class));
            assertTrue(baseIndexes.contains(ExcludedIndexTestModel.OWN_INDEX), baseIndexes.toString());
            assertFalse(baseIndexes.contains(UnversionedBaseModel.REF_NAME_TENANT_UNIQUE_INDEX), baseIndexes.toString());
            MorphiaDatastore datastore = morphiaDataStoreWrapper.getDataStore(realm);
            Set<String> automatic = indexNames(collection(datastore, ExcludedIndexTestModel.class));
            assertTrue(automatic.contains(ExcludedIndexTestModel.OWN_INDEX), automatic.toString());
            assertFalse(automatic.contains(UnversionedBaseModel.REF_NAME_TENANT_UNIQUE_INDEX), automatic.toString());
            datastore.getMapper().map(ExcludedIndexTestModel.class);

            DeclaredIndexes.ensure(datastore, ExcludedIndexTestModel.class);
            DeclaredIndexes.ensure(datastore, UnversionedTestModel.class);
            // The post-migration index step and the per-realm apply endpoint take the same path.
            migrationService.applyAllIndexes(realm);
            migrationService.applyIndexes(realm);

            Set<String> excluded = indexNames(collection(datastore, ExcludedIndexTestModel.class));
            assertTrue(excluded.contains(ExcludedIndexTestModel.OWN_INDEX), excluded.toString());
            assertFalse(excluded.contains(UnversionedBaseModel.REF_NAME_TENANT_UNIQUE_INDEX), excluded.toString());
            assertFalse(excluded.contains(UnversionedBaseModel.REF_NAME_TENANT_LOOKUP_INDEX), excluded.toString());

            Set<String> regular = indexNames(collection(datastore, UnversionedTestModel.class));
            assertTrue(regular.contains(UnversionedBaseModel.REF_NAME_TENANT_UNIQUE_INDEX), regular.toString());
            assertTrue(regular.contains(UnversionedBaseModel.REF_NAME_TENANT_LOOKUP_INDEX), regular.toString());

            // Drift reporting compares against the same exclusion-aware names.
            IndexHelper indexHelper = new IndexHelper(datastore.getMapper());
            assertEquals(Set.of(ExcludedIndexTestModel.OWN_INDEX),
                    DeclaredIndexes.names(indexHelper, datastore.getMapper().getEntityModel(ExcludedIndexTestModel.class)));
        } finally {
            mongoClient.getDatabase(realm).drop();
            mongoClient.getDatabase("pr33-auto-index-base").drop();
        }
    }

    @Test
    public void namedDatastoreKeepsItsRequestedIndexesWithExclusions() {
        io.smallrye.config.SmallRyeConfig config = new io.smallrye.config.SmallRyeConfigBuilder()
                .withMapping(com.end2endlogic.quantum.extension.QuarkusMorphiaConfig.class)
                .withInterceptors(new DeclaredIndexConfigInterceptor())
                .withSources(new io.smallrye.config.PropertiesConfigSource(java.util.Map.ofEntries(
                        java.util.Map.entry("quarkus.morphia.database", "default"),
                        java.util.Map.entry("quarkus.morphia.packages", "com.e2eq.framework.test"),
                        java.util.Map.entry("quarkus.morphia.mapper-configs.archive.database", "archive"),
                        java.util.Map.entry("quarkus.morphia.mapper-configs.archive.packages", "com.e2eq.framework.test"),
                        java.util.Map.entry("quarkus.morphia.mapper-configs.archive.create-indexes", "true"),
                        java.util.Map.entry("quarkus.morphia.mapper-configs.reporting.database", "reporting"),
                        java.util.Map.entry("quarkus.morphia.mapper-configs.reporting.packages", "com.e2eq.framework.test"),
                        java.util.Map.entry("quarkus.morphia.mapper-configs.reporting.create-indexes", "false"),
                        java.util.Map.entry("quarkus.morphia.mapper-configs.omitted.database", "omitted"),
                        java.util.Map.entry("quarkus.morphia.mapper-configs.omitted.packages", "com.e2eq.framework.test"),
                        java.util.Map.entry("quarkus.morphia.mapper-configs.\"archive.dot\".database", "dotted"),
                        java.util.Map.entry("quarkus.morphia.mapper-configs.\"archive.dot\".packages", "com.e2eq.framework.test"),
                        java.util.Map.entry("quarkus.morphia.mapper-configs.\"archive.dot\".create-indexes", "true"),
                        java.util.Map.entry("quarkus.morphia.create-indexes", "true")), "test", 100))
                .build();
        // The extension never creates indexes itself, for the default or a named datastore.
        assertFalse(config.getValue("quarkus.morphia.mapper-configs.archive.create-indexes", Boolean.class));
        assertFalse(config.getValue("quarkus.morphia.create-indexes", Boolean.class));
        assertTrue(config.getValue(DeclaredIndexConfigInterceptor.requestedFor("archive"), Boolean.class));
        assertFalse(config.getValue(DeclaredIndexConfigInterceptor.requestedFor("reporting"), Boolean.class));
        assertTrue(config.getValue(DeclaredIndexConfigInterceptor.REQUESTED, Boolean.class));

        String database = "pr33-named-index-" + UUID.randomUUID().toString().substring(0, 8);
        MorphiaDatastore archive = new MorphiaDatastore(mongoClient,
                dev.morphia.config.ManualMorphiaConfig.configure().database(database).applyIndexes(false));
        archive.getMapper().map(ExcludedIndexTestModel.class, UnversionedTestModel.class);
        try {
            assertEquals(java.util.List.of("archive", "inherited", "archive.dot"), NamedDatastoreDeclaredIndexes.apply(config,
                    java.util.List.of("archive", "reporting", "omitted", "inherited", "archive.dot"),
                    client -> {
                        assertFalse(java.util.Set.of("reporting", "omitted").contains(client));
                        return java.util.Optional.of(archive);
                    }));

            Set<String> excluded = indexNames(collection(archive, ExcludedIndexTestModel.class));
            assertTrue(excluded.contains(ExcludedIndexTestModel.OWN_INDEX), excluded.toString());
            assertFalse(excluded.contains(UnversionedBaseModel.REF_NAME_TENANT_UNIQUE_INDEX), excluded.toString());
            Set<String> regular = indexNames(collection(archive, UnversionedTestModel.class));
            assertTrue(regular.contains(UnversionedBaseModel.REF_NAME_TENANT_UNIQUE_INDEX), regular.toString());
        } finally {
            mongoClient.getDatabase(database).drop();
        }
    }

    @Test
    public void excludingAnIndexNoSuperclassDeclaresIsRejected() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> DeclaredIndexes.excludedNames(MisnamedExclusion.class));
        assertTrue(failure.getMessage().contains("idx_not_declared"), failure.getMessage());
    }

    @ExcludeInheritedIndexes("idx_not_declared")
    static class MisnamedExclusion extends UnversionedTestModel {
    }

    private static MongoCollection<Document> collection(MorphiaDatastore datastore, Class<?> type) {
        return datastore.getDatabase().getCollection(datastore.getMapper().getEntityModel(type).collectionName());
    }

    private static Set<String> indexNames(MongoCollection<Document> collection) {
        Set<String> names = new java.util.TreeSet<>();
        StreamSupport.stream(collection.listIndexes().spliterator(), false)
                .forEach(index -> names.add(index.getString("name")));
        return names;
    }
}
