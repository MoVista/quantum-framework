package com.e2eq.framework.migration;

import com.coditory.sherlock.DistributedLock;
import com.coditory.sherlock.mongo.MongoSherlock;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.migration.base.IndexBuildException;
import com.e2eq.framework.model.persistent.migration.base.MigrationInProgressException;
import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.security.runtime.SecuritySession;
import com.e2eq.framework.test.CSVModel;
import com.e2eq.framework.test.UnversionedTestModel;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import dev.morphia.MorphiaDatastore;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The index step at the end of every migration call: it runs even when no change sets are pending,
 * reports every failing collection as one failure, reports undeclared indexes as drift, and the
 * call is rejected while another migration of the realm holds the lock.
 */
@QuarkusTest
public class MigrationIndexStepTest extends BaseRepoTest {

    @Inject
    MigrationService migrationService;

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @Inject
    MongoClient mongoClient;

    @Test
    public void migrationCallRebuildsDroppedIndexEvenWithNoPendingChangeSets() {
        MongoCollection<Document> collection = testModelCollection();
        collection.dropIndex(UnversionedBaseModel.REF_NAME_TENANT_LOOKUP_INDEX);
        assertFalse(hasIndex(collection, UnversionedBaseModel.REF_NAME_TENANT_LOOKUP_INDEX));

        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            migrationService.runAllUnRunMigrations(testUtils.getTestRealm(), recordingEmitter(new CopyOnWriteArrayList<>()));
        }

        assertTrue(hasIndex(collection, UnversionedBaseModel.REF_NAME_TENANT_LOOKUP_INDEX));
    }

    @Test
    public void failingIndexBuildFailsTheCallAndOtherCollectionsAreStillApplied() {
        MongoCollection<Document> collection = testModelCollection();
        collection.dropIndex(UnversionedBaseModel.REF_NAME_TENANT_LOOKUP_INDEX);
        // Occupy the declared name with a different definition so the declared index cannot be built.
        collection.createIndex(new Document("displayName", 1), new IndexOptions().name(UnversionedBaseModel.REF_NAME_TENANT_LOOKUP_INDEX));
        MongoCollection<Document> other = collectionFor(CSVModel.class);
        other.dropIndex(UnversionedBaseModel.REF_NAME_TENANT_LOOKUP_INDEX);
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            IndexBuildException failure = assertThrows(IndexBuildException.class, () ->
                    migrationService.runAllUnRunMigrations(testUtils.getTestRealm(), recordingEmitter(new CopyOnWriteArrayList<>())));
            assertTrue(failure.getMessage().contains(collection.getNamespace().getCollectionName()), failure.getMessage());
            assertEquals(1, failure.getFailures().size(), failure.getMessage());
            // Every other entity was still attempted despite the failure.
            assertTrue(hasIndex(other, UnversionedBaseModel.REF_NAME_TENANT_LOOKUP_INDEX));
        } finally {
            collection.dropIndex(UnversionedBaseModel.REF_NAME_TENANT_LOOKUP_INDEX);
            try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
                migrationService.applyAllIndexes(testUtils.getTestRealm());
            }
        }
    }

    @Test
    public void undeclaredIndexIsReportedAsDriftAndLeftInPlace() {
        MongoCollection<Document> collection = testModelCollection();
        String driftIndex = "zz_drift_check_testField";
        collection.createIndex(new Document("testField", 1), new IndexOptions().name(driftIndex));
        List<String> messages = new CopyOnWriteArrayList<>();
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            migrationService.applyAllIndexes(testUtils.getTestRealm(), recordingEmitter(messages));
            assertTrue(messages.stream().anyMatch(m -> m.contains("undeclared index " + driftIndex)), messages.toString());
            assertTrue(hasIndex(collection, driftIndex));
        } finally {
            collection.dropIndex(driftIndex);
        }
    }

    @Test
    public void migrationCallFailsWhileAnotherHoldsTheRealmLock() {
        String realm = testUtils.getTestRealm();
        DistributedLock lock = MongoSherlock.create(mongoClient.getDatabase("sherlock").getCollection("locks"))
                .createLock(String.format("migration-lock-%s", realm));
        assertTrue(lock.acquire(Duration.ofMinutes(1)), "test could not take the realm migration lock");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            assertThrows(MigrationInProgressException.class, () ->
                    migrationService.runAllUnRunMigrations(realm, recordingEmitter(new CopyOnWriteArrayList<>())));
        } finally {
            lock.release();
        }
    }

    @Test
    public void startupContinuesWhenAnotherMigrationHoldsLock() throws Exception {
        String realm = testUtils.getTestRealm();
        DistributedLock lock = MongoSherlock.create(mongoClient.getDatabase("sherlock").getCollection("locks"))
                .createLock(String.format("migration-lock-%s", realm));
        assertTrue(lock.acquire(Duration.ofMinutes(1)));
        try {
            var method = MigrationService.class.getDeclaredMethod("migrateRealmSynchronously", String.class);
            method.setAccessible(true);
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                    method.invoke(io.quarkus.arc.ClientProxy.unwrap(migrationService), realm));
        } finally {
            lock.release();
        }
    }

    private MongoCollection<Document> testModelCollection() {
        return collectionFor(UnversionedTestModel.class);
    }

    private MongoCollection<Document> collectionFor(Class<?> type) {
        MorphiaDatastore datastore = morphiaDataStoreWrapper.getDataStore(testUtils.getTestRealm());
        datastore.ensureIndexes(type);
        String name = datastore.getMapper().getEntityModel(type).collectionName();
        return mongoClient.getDatabase(testUtils.getTestRealm()).getCollection(name);
    }

    private static boolean hasIndex(MongoCollection<Document> collection, String name) {
        return StreamSupport.stream(collection.listIndexes().spliterator(), false)
                .anyMatch(index -> name.equals(index.getString("name")));
    }

    @SuppressWarnings("unchecked")
    private static MultiEmitter<String> recordingEmitter(List<String> messages) {
        return (MultiEmitter<String>) Proxy.newProxyInstance(
                MultiEmitter.class.getClassLoader(),
                new Class[]{MultiEmitter.class},
                (proxy, method, args) -> {
                    if ("emit".equals(method.getName()) && args != null && args.length == 1) {
                        messages.add(String.valueOf(args[0]));
                        return proxy;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == long.class) {
                        return 0L;
                    }
                    return method.getReturnType().isInstance(proxy) ? proxy : null;
                });
    }
}
