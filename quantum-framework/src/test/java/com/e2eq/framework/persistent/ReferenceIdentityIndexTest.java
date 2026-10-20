package com.e2eq.framework.persistent;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.imports.ImportSessionRow;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.test.UnversionedTestModel;
import com.e2eq.framework.util.TestUtils;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import dev.morphia.MorphiaDatastore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the reference-identity indexes declared on {@link UnversionedBaseModel} and {@link Realm}:
 * their definitions as built by Morphia, and the uniqueness MongoDB enforces with them.
 */
@QuarkusTest
public class ReferenceIdentityIndexTest {

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @Inject
    MongoClient mongoClient;

    @Inject
    TestUtils testUtils;

    @Test
    public void declaresUniqueAndLookupIndexesOnRefNameAndTenant() {
        MongoCollection<Document> collection = ensureIndexedCollection(testUtils.getTestRealm(), UnversionedTestModel.class);

        Document unique = findIndex(collection, UnversionedBaseModel.REF_NAME_TENANT_UNIQUE_INDEX)
            .orElseThrow(() -> new AssertionError("missing " + UnversionedBaseModel.REF_NAME_TENANT_UNIQUE_INDEX));
        assertEquals(new Document("refName", 1).append("dataDomain.tenantId", 1), unique.get("key"));
        assertTrue(unique.getBoolean("unique", false));
        Document collation = unique.get("collation", Document.class);
        assertEquals("en", collation.getString("locale"));
        assertEquals(2, collation.getInteger("strength"));

        Document lookup = findIndex(collection, UnversionedBaseModel.REF_NAME_TENANT_LOOKUP_INDEX)
            .orElseThrow(() -> new AssertionError("missing " + UnversionedBaseModel.REF_NAME_TENANT_LOOKUP_INDEX));
        assertEquals(new Document("refName", 1).append("dataDomain.tenantId", 1), lookup.get("key"));
        assertFalse(lookup.getBoolean("unique", false));
        assertNull(lookup.get("collation"), "lookup index must use the collection's simple collation");
    }

    @Test
    public void rejectsCaseVariantReferenceInSameTenantRegardlessOfOwner() {
        MongoCollection<Document> collection = ensureIndexedCollection(testUtils.getTestRealm(), UnversionedTestModel.class);
        String refName = "RefIdx-" + UUID.randomUUID();
        String tenantId = testUtils.getTestTenantId();
        List<Object> inserted = new ArrayList<>();
        try {
            inserted.add(insert(collection, refName, tenantId, "owner-a@test.com", "org-a"));

            MongoWriteException duplicate = assertThrows(MongoWriteException.class,
                () -> insert(collection, refName.toLowerCase(), tenantId, "owner-b@test.com", "org-b"));
            assertEquals(ErrorCategory.DUPLICATE_KEY, duplicate.getError().getCategory());
        } finally {
            inserted.forEach(id -> collection.deleteOne(Filters.eq("_id", id)));
        }
    }

    @Test
    public void allowsSameReferenceForDifferentTenants() {
        MongoCollection<Document> collection = ensureIndexedCollection(testUtils.getTestRealm(), UnversionedTestModel.class);
        String refName = "RefIdx-" + UUID.randomUUID();
        List<Object> inserted = new ArrayList<>();
        try {
            inserted.add(insert(collection, refName, "tenant-a.example", "owner@a.example", "org-a"));
            assertDoesNotThrow(() -> inserted.add(insert(collection, refName, "tenant-b.example", "owner@b.example", "org-b")));
        } finally {
            inserted.forEach(id -> collection.deleteOne(Filters.eq("_id", id)));
        }
    }

    @Test
    public void realmReferenceIsGloballyUniqueIgnoringTenant() {
        MongoCollection<Document> realms = ensureIndexedCollection(testUtils.getSystemRealm(), Realm.class);

        Document index = findIndex(realms, Realm.REF_NAME_UNIQUE_INDEX)
            .orElseThrow(() -> new AssertionError("missing " + Realm.REF_NAME_UNIQUE_INDEX));
        assertEquals(new Document("refName", 1), index.get("key"));
        assertTrue(index.getBoolean("unique", false));
        assertEquals(2, index.get("collation", Document.class).getInteger("strength"));

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String refName = "IdxTest-" + suffix + "-com";
        List<Object> inserted = new ArrayList<>();
        try {
            inserted.add(insertRealm(realms, refName, "tenant-a.example", suffix + "-a.example"));

            MongoWriteException duplicate = assertThrows(MongoWriteException.class,
                () -> insertRealm(realms, refName.toLowerCase(), "tenant-b.example", suffix + "-b.example"));
            assertEquals(ErrorCategory.DUPLICATE_KEY, duplicate.getError().getCategory());
        } finally {
            inserted.forEach(id -> realms.deleteOne(Filters.eq("_id", id)));
        }
    }

    @Test
    public void declaresStagingRowLookupIndexes() {
        MongoCollection<Document> rows = ensureIndexedCollection(testUtils.getTestRealm(), ImportSessionRow.class);

        Document byRow = findIndex(rows, ImportSessionRow.SESSION_ROW_INDEX)
            .orElseThrow(() -> new AssertionError("missing " + ImportSessionRow.SESSION_ROW_INDEX));
        assertEquals(new Document("sessionRefName", 1).append("rowNumber", 1), byRow.get("key"));
        assertNull(byRow.get("collation"));

        Document forCommit = findIndex(rows, ImportSessionRow.SESSION_COMMIT_INDEX)
            .orElseThrow(() -> new AssertionError("missing " + ImportSessionRow.SESSION_COMMIT_INDEX));
        assertEquals(new Document("sessionRefName", 1).append("hasErrors", 1).append("intent", 1), forCommit.get("key"));
    }

    private MongoCollection<Document> ensureIndexedCollection(String realm, Class<?> type) {
        MorphiaDatastore datastore = morphiaDataStoreWrapper.getDataStore(realm);
        datastore.ensureIndexes(type);
        String collectionName = datastore.getMapper().getEntityModel(type).collectionName();
        return mongoClient.getDatabase(realm).getCollection(collectionName);
    }

    private static Optional<Document> findIndex(MongoCollection<Document> collection, String name) {
        for (Document index : collection.listIndexes()) {
            if (name.equals(index.getString("name"))) {
                return Optional.of(index);
            }
        }
        return Optional.empty();
    }

    private static Object insert(MongoCollection<Document> collection, String refName, String tenantId,
                                 String ownerId, String orgRefName) {
        Document doc = new Document("refName", refName)
            .append("dataDomain", new Document("tenantId", tenantId)
                .append("ownerId", ownerId)
                .append("orgRefName", orgRefName));
        collection.insertOne(doc);
        return doc.get("_id");
    }

    private static Object insertRealm(MongoCollection<Document> realms, String refName, String tenantId, String domain) {
        Document doc = new Document("refName", refName)
            .append("emailDomain", domain)
            .append("databaseName", refName + "-" + domain)
            .append("dataDomain", new Document("tenantId", tenantId).append("ownerId", "system@system.com"));
        realms.insertOne(doc);
        return doc.get("_id");
    }
}
