package com.e2eq.framework.persistent;

import com.e2eq.framework.model.general.MenuItemModel;
import com.e2eq.framework.model.persistent.morphia.MenuItemRepo;
import com.e2eq.framework.security.runtime.SecuritySession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a document whose stored enum value ({@code activeStatus}) no longer matches any
 * Java enum constant is skipped -- rather than aborting the whole fetch -- when listing via
 * {@link com.e2eq.framework.model.persistent.morphia.MorphiaRepo#getListByQuery}.
 *
 * <p>A single-document fetch ({@code findById}) of the same corrupted document is expected to
 * still fail, since only list fetches are meant to tolerate a handful of unparseable documents.
 */
@QuarkusTest
public class MorphiaRepoSkipUnparseableIntegrationTest extends BaseRepoTest {

    @Inject
    MenuItemRepo menuItemRepo;

    @Inject
    MongoClient mongoClient;

    @Test
    public void listByQuerySkipsDocumentWithUnknownEnumValue() {
        try (final SecuritySession ss = new SecuritySession(pContext, rContext)) {
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

            // Corrupt the "activeStatus" field of the third document directly in Mongo so it no
            // longer matches any Java enum constant -- simulating a value written by a newer (or
            // older) version of the application that this build's enum does not know about.
            String realm = testUtils.getTestRealm();
            String dbName = menuItemRepo.getMorphiaDataStoreWrapper().getDataStore(realm).getDatabase().getName();
            String collectionName = menuItemRepo.getMorphiaDataStoreWrapper().getDataStore(realm)
                    .getMapper().getEntityModel(MenuItemModel.class).collectionName();
            mongoClient.getDatabase(dbName).getCollection(collectionName)
                    .updateOne(Filters.eq("_id", bad.getId()), Updates.set("activeStatus", "NOT_A_REAL_STATUS"));

            List<MenuItemModel> list = menuItemRepo.getListByQuery(0, 0, null);
            List<String> refNames = list.stream()
                    .map(MenuItemModel::getRefName)
                    .filter(refName -> refName != null && refName.startsWith(marker))
                    .toList();

            assertEquals(2, refNames.size(), "expected only the two valid documents to be returned");
            assertTrue(refNames.contains(valid1.getRefName()));
            assertTrue(refNames.contains(valid2.getRefName()));
            assertFalse(refNames.contains(bad.getRefName()));

            // Only list fetches tolerate a handful of unparseable documents; a direct fetch of
            // the corrupted document should still fail loudly.
            var badId = bad.getId();
            assertThrows(RuntimeException.class, () -> menuItemRepo.findById(badId));
        }
    }
}
