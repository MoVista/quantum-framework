package com.e2eq.framework.persistent;

import com.e2eq.framework.model.general.MenuItemModel;
import com.e2eq.framework.model.persistent.base.CloseableIterator;
import com.e2eq.framework.model.persistent.morphia.MenuItemRepo;
import com.e2eq.framework.security.runtime.SecuritySession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a document whose stored enum value ({@code activeStatus}) no longer matches any
 * Java enum constant is skipped -- rather than aborting the whole fetch -- when listing or
 * streaming via {@link com.e2eq.framework.model.persistent.morphia.MorphiaRepo#getListByQuery}
 * and {@link com.e2eq.framework.model.persistent.morphia.MorphiaRepo#getStreamByQuery}.
 *
 * <p>A single-document fetch ({@code findById}) of the same corrupted document is expected to
 * still fail, since only list/stream fetches are meant to tolerate a handful of unparseable
 * documents.
 */
@QuarkusTest
public class MorphiaRepoSkipUnparseableIntegrationTest extends BaseRepoTest {

    @Inject
    MenuItemRepo menuItemRepo;

    @Inject
    MongoClient mongoClient;

    private final List<ObjectId> createdIds = new ArrayList<>();

    /**
     * The documents created by {@link #createValidValidBadTrio} cannot all be removed through the
     * repo -- the corrupted one fails to decode -- so clean them up with a raw driver delete
     * instead, to avoid leaking permanently-corrupted documents into the shared test database.
     */
    @AfterEach
    void cleanupCreatedDocuments() {
        if (createdIds.isEmpty()) {
            return;
        }
        rawCollection().deleteMany(Filters.in("_id", createdIds));
        createdIds.clear();
    }

    @Test
    public void listByQuerySkipsDocumentWithUnknownEnumValue() {
        try (final SecuritySession ss = new SecuritySession(pContext, rContext)) {
            Trio trio = createValidValidBadTrio();

            List<MenuItemModel> list = menuItemRepo.getListByQuery(0, 0, "refName:" + trio.marker() + "*");
            List<String> refNames = list.stream().map(MenuItemModel::getRefName).toList();

            assertEquals(2, refNames.size(), "expected only the two valid documents to be returned");
            assertTrue(refNames.contains(trio.valid1().getRefName()));
            assertTrue(refNames.contains(trio.valid2().getRefName()));
            assertFalse(refNames.contains(trio.bad().getRefName()));

            // Only list/stream fetches tolerate a handful of unparseable documents; a direct
            // fetch of the corrupted document should still fail loudly.
            var badId = trio.bad().getId();
            assertThrows(RuntimeException.class, () -> menuItemRepo.findById(badId));
        }
    }

    @Test
    public void getStreamByQuerySkipsDocumentWithUnknownEnumValue() {
        try (final SecuritySession ss = new SecuritySession(pContext, rContext)) {
            Trio trio = createValidValidBadTrio();

            List<String> refNames = new ArrayList<>();
            try (CloseableIterator<MenuItemModel> iterator =
                         menuItemRepo.getStreamByQuery(0, -1, "refName:" + trio.marker() + "*", null, null)) {
                while (iterator.hasNext()) {
                    refNames.add(iterator.next().getRefName());
                }
                assertThrows(NoSuchElementException.class, iterator::next);
            }

            assertEquals(2, refNames.size(), "expected only the two valid documents to be streamed");
            assertTrue(refNames.contains(trio.valid1().getRefName()));
            assertTrue(refNames.contains(trio.valid2().getRefName()));
            assertFalse(refNames.contains(trio.bad().getRefName()));
        }
    }

    @Test
    public void getListFromRefNamesSkipsDocumentWithUnknownEnumValue() {
        try (final SecuritySession ss = new SecuritySession(pContext, rContext)) {
            Trio trio = createValidValidBadTrio();

            List<MenuItemModel> list = menuItemRepo.getListFromRefNames(
                    List.of(trio.valid1().getRefName(), trio.valid2().getRefName(), trio.bad().getRefName()));
            List<String> refNames = list.stream().map(MenuItemModel::getRefName).toList();

            assertEquals(2, refNames.size(), "expected only the two valid documents to be returned");
            assertTrue(refNames.contains(trio.valid1().getRefName()));
            assertTrue(refNames.contains(trio.valid2().getRefName()));
            assertFalse(refNames.contains(trio.bad().getRefName()));
        }
    }

    /**
     * Persists two valid {@link MenuItemModel} documents plus a third whose {@code activeStatus}
     * is then corrupted directly via the driver so it no longer matches any Java enum constant --
     * simulating a value written by a newer (or older) version of the application that this
     * build's enum does not know about. All three refNames share a random {@code marker} prefix
     * so callers can scope queries to just these documents.
     */
    private Trio createValidValidBadTrio() {
        String marker = "skipUnparseableTest-" + UUID.randomUUID();

        MenuItemModel valid1 = new MenuItemModel();
        valid1.setRefName(marker + "-valid1");
        valid1 = menuItemRepo.save(valid1);

        MenuItemModel valid2 = new MenuItemModel();
        valid2.setRefName(marker + "-valid2");
        valid2 = menuItemRepo.save(valid2);

        MenuItemModel bad = new MenuItemModel();
        bad.setRefName(marker + "-bad");
        bad = menuItemRepo.save(bad);

        createdIds.add(valid1.getId());
        createdIds.add(valid2.getId());
        createdIds.add(bad.getId());

        rawCollection().updateOne(Filters.eq("_id", bad.getId()), Updates.set("activeStatus", "NOT_A_REAL_STATUS"));

        return new Trio(marker, valid1, valid2, bad);
    }

    private MongoCollection<Document> rawCollection() {
        String realm = testUtils.getTestRealm();
        String dbName = menuItemRepo.getMorphiaDataStoreWrapper().getDataStore(realm).getDatabase().getName();
        String collectionName = menuItemRepo.getMorphiaDataStoreWrapper().getDataStore(realm)
                .getMapper().getEntityModel(MenuItemModel.class).collectionName();
        return mongoClient.getDatabase(dbName).getCollection(collectionName);
    }

    private record Trio(String marker, MenuItemModel valid1, MenuItemModel valid2, MenuItemModel bad) {
    }
}
