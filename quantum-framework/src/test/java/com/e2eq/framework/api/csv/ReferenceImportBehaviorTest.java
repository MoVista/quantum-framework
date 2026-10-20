package com.e2eq.framework.api.csv;

import com.e2eq.framework.csv.CSVImportHelper;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.imports.ImportProfile;
import com.e2eq.framework.model.persistent.imports.ImportSessionRow;
import com.e2eq.framework.model.persistent.morphia.ImportSessionRowRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.persistent.TestCSVModelRepo;
import com.e2eq.framework.security.runtime.SecuritySession;
import com.e2eq.framework.test.CSVModel;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import dev.morphia.MorphiaDatastore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reference lookups and CSV import behavior under the reference-identity rules: lookups and
 * insert/update classification compare references case-insensitively, an UPDATE row changes only
 * the imported columns of the existing document, a reference may appear only once per file, and
 * staged rows have their own identity.
 */
@QuarkusTest
public class ReferenceImportBehaviorTest extends BaseRepoTest {

    private static final List<String> COLUMNS = List.of("refName", "displayName");

    @Inject
    TestCSVModelRepo repo;

    @Inject
    CSVImportHelper csvImportHelper;

    @Inject
    ImportSessionRowRepo importSessionRowRepo;

    @Inject
    com.e2eq.framework.model.persistent.morphia.ImportSessionRepo importSessionRepo;

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @Inject
    MongoClient mongoClient;

    @Test
    public void findByRefNameAndListLookupsIgnoreCase() {
        String refName = unique("Lookup");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            saveModel(refName, "Lookup", "kept");

            Optional<CSVModel> found = repo.findByRefName(refName.toLowerCase());
            assertTrue(found.isPresent());
            assertEquals(refName, found.get().getRefName());

            List<CSVModel> listed = repo.getListFromRefNames(List.of(refName.toUpperCase()));
            assertEquals(1, listed.size());
            assertEquals(refName, listed.get(0).getRefName());
        } finally {
            deleteByRefPattern(refName);
        }
    }

    @Test
    public void findByRefNamePrefersCallersTenantWhenReferenceIsShared() {
        String refName = unique("Shared");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            CSVModel own = saveModel(refName, "Own tenant", null);

            // Same reference stamped with another tenant, as in a collection shared by tenants.
            MongoCollection<Document> collection = collection();
            Document other = collection.find(Filters.eq("_id", own.getId())).first();
            assertNotNull(other);
            other.put("_id", new ObjectId());
            other.put("displayName", "Other tenant");
            other.get("dataDomain", Document.class).put("tenantId", "other-tenant.example");
            collection.insertOne(other);

            MorphiaDatastore datastore = morphiaDataStoreWrapper.getDataStore(testUtils.getTestRealm());
            Optional<CSVModel> found = repo.findByRefName(datastore, refName.toUpperCase(), true);
            assertTrue(found.isPresent());
            assertEquals(own.getId(), found.get().getId());
        } finally {
            deleteByRefPattern(refName);
        }
    }

    @Test
    public void directImportUpdatesOnlyImportedColumnsOfCaseVariantReference() {
        String refName = unique("Update");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            CSVModel original = saveModel(refName, "Original", "kept");

            CSVImportHelper.ImportResult<CSVModel> result = importCsv(refName.toLowerCase() + ",Updated\n");

            assertEquals(1, result.getImportedCount());
            assertEquals(1, result.getUpdateCount());
            assertEquals(0, result.getInsertCount());
            assertEquals(1, collection().countDocuments(refFilter(refName)));

            CSVModel updated = repo.findByRefName(refName).orElseThrow();
            assertEquals(original.getId(), updated.getId());
            assertEquals(refName, updated.getRefName(), "existing reference spelling is kept");
            assertEquals("Updated", updated.getDisplayName());
            assertEquals("kept", updated.getTestField(), "columns not in the file are preserved");
            assertEquals(7, updated.getNumberField());
            assertEquals(original.getDataDomain().getOwnerId(), updated.getDataDomain().getOwnerId());
            assertEquals(original.getAuditInfo().getCreationTs(), updated.getAuditInfo().getCreationTs());
            assertTrue(updated.getVersion() > original.getVersion(), "saved through the versioned save path");
        } finally {
            deleteByRefPattern(refName);
        }
    }

    @Test
    public void directImportRejectsEveryRowOfARepeatedReference() {
        String repeated = unique("Repeated");
        String single = unique("Single");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            CSVImportHelper.ImportResult<CSVModel> result = importCsv(
                    repeated + ",First\n" + single + ",Only\n" + repeated.toUpperCase() + ",Second\n");

            assertEquals(1, result.getImportedCount());
            assertEquals(2, result.getFailedCount());
            List<CSVImportHelper.ImportRowResult<CSVModel>> rejected = result.getRowResults().stream()
                    .filter(CSVImportHelper.ImportRowResult::hasErrors)
                    .toList();
            assertEquals(List.of(1, 3), rejected.stream().map(CSVImportHelper.ImportRowResult::getRowNumber).sorted().toList());
            rejected.forEach(row -> assertTrue(row.getErrors().get(0).getMessage().contains("rows 1, 3"),
                    row.getErrors().get(0).getMessage()));
            assertEquals(0, collection().countDocuments(refFilter(repeated)));
            assertEquals(1, collection().countDocuments(refFilter(single)));
        } finally {
            deleteByRefPattern(repeated);
            deleteByRefPattern(single);
        }
    }

    @Test
    public void sessionPreviewStagesRowsByOwnIdentityAndCommitUpdatesInPlace() throws IOException {
        String existingRef = unique("Session");
        String insertedRef = unique("Inserted");
        String repeatedRef = unique("Twice");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            CSVModel original = saveModel(existingRef, "Original", "kept");
            String csv = "refName,displayName\n"
                    + existingRef.toLowerCase() + ",Renamed\n"
                    + insertedRef + ",Inserted\n"
                    + repeatedRef + ",First\n"
                    + repeatedRef.toLowerCase() + ",Second\n";

            CSVImportHelper.ImportResult<CSVModel> preview = analyze(csv);
            assertEquals(1, preview.getUpdateCount());
            assertEquals(1, preview.getInsertCount());
            assertEquals(2, preview.getValidRows());
            assertEquals(2, preview.getErrorRows());
            // The API contract: row results carry the target reference as it appeared in the file.
            assertEquals(existingRef.toLowerCase(), preview.getRowResults().get(0).getRefName());

            String sessionId = preview.getSessionId();
            ImportSessionRow first = importSessionRowRepo.findByRefName(ImportSessionRow.stagingRefName(sessionId, 1)).orElseThrow();
            assertEquals(sessionId + ":1", first.getRefName());
            assertEquals(existingRef.toLowerCase(), first.getTargetRefName());
            assertEquals("UPDATE", first.getIntent());
            ImportSessionRow repeatedRow = importSessionRowRepo.findByRefName(ImportSessionRow.stagingRefName(sessionId, 4)).orElseThrow();
            assertTrue(repeatedRow.isHasErrors());

            // The same targets can be staged again by another session.
            CSVImportHelper.ImportResult<CSVModel> secondPreview = analyze(csv);
            assertEquals(4, secondPreview.getTotalRows());

            CSVImportHelper.CommitResult commit = csvImportHelper.commitImport(sessionId, repo);
            assertEquals(2, commit.getImported());
            assertEquals(0, commit.getFailed());
            csvImportHelper.cancelImport(secondPreview.getSessionId());

            CSVModel updated = repo.findByRefName(existingRef).orElseThrow();
            assertEquals(original.getId(), updated.getId());
            assertEquals("Renamed", updated.getDisplayName());
            assertEquals("kept", updated.getTestField());
            assertEquals(1, collection().countDocuments(refFilter(existingRef)));
            assertEquals(1, collection().countDocuments(refFilter(insertedRef)));
            assertEquals(0, collection().countDocuments(refFilter(repeatedRef)));
        } finally {
            deleteByRefPattern(existingRef);
            deleteByRefPattern(insertedRef);
            deleteByRefPattern(repeatedRef);
        }
    }

    @Test
    public void commitRejectsUpdateRowsWhenSessionDoesNotRecordImportedColumns() throws IOException {
        String existingRef = unique("NoColumns");
        String insertedRef = unique("NoColumnsNew");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            CSVModel original = saveModel(existingRef, "Original", "kept");
            CSVImportHelper.ImportResult<CSVModel> preview = analyze("refName,displayName\n"
                    + existingRef + ",Changed\n"
                    + insertedRef + ",Inserted\n");

            // A session created by preview code that does not record its columns.
            com.e2eq.framework.model.persistent.imports.ImportSession session =
                    importSessionRepo.findByRefName(preview.getSessionId()).orElseThrow();
            session.setImportedColumns(null);
            importSessionRepo.save(session);

            CSVImportHelper.CommitResult commit = csvImportHelper.commitImport(preview.getSessionId(), repo);

            assertEquals(1, commit.getImported(), "the new record is still inserted");
            assertEquals(1, commit.getFailed(), "the update row is rejected");
            CSVModel unchanged = repo.findByRefName(existingRef).orElseThrow();
            assertEquals("Original", unchanged.getDisplayName());
            assertEquals(original.getVersion(), unchanged.getVersion());
            assertEquals(1, collection().countDocuments(refFilter(insertedRef)));
        } finally {
            deleteByRefPattern(existingRef);
            deleteByRefPattern(insertedRef);
        }
    }

    @Test
    public void profileIntentContradictingExistenceIsARowError() throws IOException {
        String existingRef = unique("Exists");
        String missingRef = unique("Missing");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            saveModel(existingRef, "Exists", null);
            ImportProfile profile = new ImportProfile();
            profile.setRefName(unique("profile"));
            profile.setIntentColumn("testField3");
            String csv = "refName,displayName,testField3\n"
                    + existingRef + ",Exists,INSERT\n"
                    + missingRef + ",Missing,UPDATE\n";

            CSVImportHelper.ImportResult<CSVModel> preview = csvImportHelper.analyzeCSVWithProfile(
                    repo, stream(csv), profile, List.of("refName", "displayName", "testField3"), testUtils.getTestRealm());

            assertEquals(2, preview.getErrorRows());
            assertTrue(preview.getRowResults().get(0).getErrors().get(0).getMessage().contains("already exists"));
            assertTrue(preview.getRowResults().get(1).getErrors().get(0).getMessage().contains("does not exist"));
            csvImportHelper.cancelImport(preview.getSessionId());
        } finally {
            deleteByRefPattern(existingRef);
            deleteByRefPattern(missingRef);
        }
    }

    private CSVImportHelper.ImportResult<CSVModel> importCsv(String rows) {
        return csvImportHelper.importCSV(repo, stream("refName,displayName\n" + rows), ',', '"', true, COLUMNS,
                StandardCharsets.UTF_8, false, "QUOTE_WHERE_ESSENTIAL", record -> { });
    }

    private CSVImportHelper.ImportResult<CSVModel> analyze(String csv) throws IOException {
        return csvImportHelper.analyzeCSV(repo, stream(csv), ',', '"', true, COLUMNS,
                StandardCharsets.UTF_8, false, "QUOTE_WHERE_ESSENTIAL");
    }

    private CSVModel saveModel(String refName, String displayName, String testField) {
        CSVModel model = new CSVModel();
        model.setRefName(refName);
        model.setDisplayName(displayName);
        model.setTestField(testField);
        model.setNumberField(7);
        return repo.save(model);
    }

    private MongoCollection<Document> collection() {
        MorphiaDatastore datastore = morphiaDataStoreWrapper.getDataStore(testUtils.getTestRealm());
        String name = datastore.getMapper().getEntityModel(CSVModel.class).collectionName();
        return mongoClient.getDatabase(testUtils.getTestRealm()).getCollection(name);
    }

    private static org.bson.conversions.Bson refFilter(String refName) {
        return Filters.regex("refName", "^" + Pattern.quote(refName) + "$", "i");
    }

    private void deleteByRefPattern(String refName) {
        collection().deleteMany(refFilter(refName));
    }

    private static ByteArrayInputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
