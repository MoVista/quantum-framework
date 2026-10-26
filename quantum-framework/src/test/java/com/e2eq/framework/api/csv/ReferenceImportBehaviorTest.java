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
import com.e2eq.framework.test.HiddenFieldImportModel;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    public void referenceLookupsKeepExactPermissionComparisons() {
        String refName = unique("Exact");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            CSVModel model = saveModel(refName, "Hidden", null);
            String tenant = model.getDataDomain().getTenantId();
            assertNotEquals(tenant, tenant.toUpperCase());
            // The caller's rules admit only its own tenant; this record differs from it only in case.
            collection().updateOne(Filters.eq("_id", model.getId()),
                    new Document("$set", new Document("dataDomain.tenantId", tenant.toUpperCase())));

            assertTrue(repo.findById(model.getId()).isEmpty(), "the ordinary secured query excludes the record");
            assertTrue(repo.findByRefName(refName).isEmpty());
            assertTrue(repo.findByRefName(refName.toLowerCase()).isEmpty());
            assertTrue(repo.getListFromRefNames(List.of(refName, refName.toUpperCase())).isEmpty());
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
    public void manyRowsOfOneReferenceGetABoundedMessage() throws IOException {
        String repeated = unique("Many");
        StringBuilder rows = new StringBuilder();
        for (int row = 1; row <= 40; row++) {
            rows.append(row % 2 == 0 ? repeated.toLowerCase() : repeated).append(",Row ").append(row).append('\n');
        }
        String expected = "Reference '" + repeated + "' appears in 40 rows of this file "
                + "(rows 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 and 30 more); each reference may appear only once.";
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            CSVImportHelper.ImportResult<CSVModel> imported = importCsv(rows.toString());
            assertEquals(40, imported.getFailedCount());
            imported.getRowResults().forEach(row -> assertEquals(expected, row.getErrors().get(0).getMessage()));

            CSVImportHelper.ImportResult<CSVModel> preview = analyze("refName,displayName\n" + rows);
            try {
                assertEquals(40, preview.getErrorRows());
                for (int row : List.of(1, 20, 40)) {
                    ImportSessionRow staged = importSessionRowRepo
                            .findByRefName(ImportSessionRow.stagingRefName(preview.getSessionId(), row)).orElseThrow();
                    assertTrue(staged.isHasErrors());
                    assertTrue(staged.getErrorsJson().contains(expected), staged.getErrorsJson());
                    assertTrue(staged.getErrorsJson().length() < 500, staged.getErrorsJson());
                }
            } finally {
                csvImportHelper.cancelImport(preview.getSessionId());
            }
            assertEquals(0, collection().countDocuments(refFilter(repeated)));
        } finally {
            deleteByRefPattern(repeated);
        }
    }

    @Test
    public void commitRejectsAnInsertWhoseReferenceWasCreatedAfterPreview() throws IOException {
        String raced = unique("Raced");
        String fresh = unique("Fresh");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            CSVImportHelper.ImportResult<CSVModel> preview = analyze("refName,displayName\n"
                    + raced + ",Imported\n" + fresh + ",Imported\n");
            try {
                assertEquals(2, preview.getInsertCount());
                // Another writer creates the reference between preview and commit.
                CSVModel other = saveModel(raced.toUpperCase(), "Other writer", "kept");

                CSVImportHelper.CommitResult commit = csvImportHelper.commitImport(preview.getSessionId(), repo);

                assertEquals(1, commit.getImported());
                assertEquals(1, commit.getFailed());
                assertEquals(1, collection().countDocuments(refFilter(raced)));
                CSVModel kept = repo.findByRefName(raced).orElseThrow();
                assertEquals(other.getId(), kept.getId());
                assertEquals("Other writer", kept.getDisplayName(), "the other writer's record is not overwritten");
                assertEquals(1, collection().countDocuments(refFilter(fresh)));
            } finally {
                csvImportHelper.cancelImport(preview.getSessionId());
            }
        } finally {
            deleteByRefPattern(raced);
            deleteByRefPattern(fresh);
        }
    }

    @Test
    public void commitKeepsInsertsStoredByItsOwnPartlyFailedBatch() throws IOException {
        String first = unique("Stored");
        String second = unique("Clash");
        String displayName = unique("SameName");
        // A unique index on another field makes the second insert of the batch fail after the first
        // has been stored.
        String index = collection().createIndex(new Document("displayName", 1), new com.mongodb.client.model.IndexOptions()
                .name("test_" + displayName).unique(true)
                .partialFilterExpression(new Document("displayName", displayName)));
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            CSVImportHelper.ImportResult<CSVModel> preview = analyze("refName,displayName\n"
                    + first + "," + displayName + "\n" + second + "," + displayName + "\n");
            try {
                assertEquals(2, preview.getInsertCount());

                CSVImportHelper.CommitResult commit = csvImportHelper.commitImport(preview.getSessionId(), repo);

                assertEquals(1, commit.getImported(), "the stored insert is not reported as created by someone else");
                assertEquals(1, commit.getFailed());
                assertEquals(1, collection().countDocuments(refFilter(first)));
                assertEquals(0, collection().countDocuments(refFilter(second)));
            } finally {
                csvImportHelper.cancelImport(preview.getSessionId());
            }
        } finally {
            collection().dropIndex(index);
            deleteByRefPattern(first);
            deleteByRefPattern(second);
        }
    }

    @Test
    public void retryDoesNotMergeOntoAReferenceCreatedDuringTheFailedSave() throws IOException {
        String first = unique("Stored");
        String raced = unique("Raced");
        String imported = unique("Imported");
        String otherName = unique("Other writer");
        // The reference-identity unique index, limited to this test's documents.
        String index = collection().createIndex(new Document("refName", 1).append("dataDomain.tenantId", 1),
                new com.mongodb.client.model.IndexOptions().name("test_" + imported).unique(true)
                        .collation(com.e2eq.framework.model.persistent.morphia.RefNameMatching.COLLATION)
                        .partialFilterExpression(new Document("displayName",
                                new Document("$in", List.of(imported, otherName)))));
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            CSVImportHelper.ImportResult<CSVModel> preview = analyze("refName,displayName\n"
                    + first + "," + imported + "\n" + raced + "," + imported + "\n");
            try {
                assertEquals(2, preview.getInsertCount());
                // Another writer creates the second reference after the commit's lookup and just
                // before its batch save, which then fails on that reference after storing the first.
                CSVModel[] other = new CSVModel[1];
                @SuppressWarnings("unchecked")
                com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo<CSVModel> racing =
                        (com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo<CSVModel>) java.lang.reflect.Proxy
                                .newProxyInstance(getClass().getClassLoader(), new Class<?>[]{
                                        com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo.class},
                                        (proxy, method, args) -> {
                                            if (other[0] == null && method.getName().equals("save") && args.length == 1
                                                    && args[0] instanceof List) {
                                                other[0] = saveModel(raced.toUpperCase(), otherName, "kept");
                                            }
                                            try {
                                                return method.invoke(repo, args);
                                            } catch (java.lang.reflect.InvocationTargetException e) {
                                                throw e.getCause();
                                            }
                                        });

                CSVImportHelper.CommitResult commit = csvImportHelper.commitImport(preview.getSessionId(), racing);

                assertEquals(1, commit.getImported());
                assertEquals(1, commit.getFailed());
                assertEquals(1, collection().countDocuments(refFilter(first)));
                assertEquals(1, collection().countDocuments(refFilter(raced)));
                CSVModel kept = repo.findByRefName(raced).orElseThrow();
                assertEquals(other[0].getId(), kept.getId());
                assertEquals(otherName, kept.getDisplayName(), "the other writer's record is not overwritten");
            } finally {
                csvImportHelper.cancelImport(preview.getSessionId());
            }
        } finally {
            collection().dropIndex(index);
            deleteByRefPattern(first);
            deleteByRefPattern(raced);
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


    @Inject
    com.e2eq.framework.persistent.RequiredImportModelRepo requiredRepo;

    @Test
    public void partialUpdatesValidateMergedDocumentInDirectAndBothPreviewPaths() throws IOException {
        String ref = unique("Required");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            var original = new com.e2eq.framework.test.RequiredImportModel();
            original.setRefName(ref);
            original.setRequiredValue("preserved");
            requiredRepo.save(original);
            String csv = "refName,displayName\n" + ref + ",Changed\n";
            var direct = csvImportHelper.importCSV(requiredRepo, stream(csv), ',', '"', true, COLUMNS,
                    StandardCharsets.UTF_8, false, "QUOTE_WHERE_ESSENTIAL", record -> { });
            assertEquals(1, direct.getImportedCount(), direct.getRowResults().toString());
            var plain = csvImportHelper.analyzeCSV(requiredRepo, stream(csv), ',', '"', true, COLUMNS,
                    StandardCharsets.UTF_8, false, "QUOTE_WHERE_ESSENTIAL");
            assertEquals(0, plain.getErrorRows());
            assertEquals(1, csvImportHelper.commitImport(plain.getSessionId(), requiredRepo).getImported());
            var profile = csvImportHelper.analyzeCSVWithProfile(requiredRepo, stream(csv), new ImportProfile(),
                    COLUMNS, testUtils.getTestRealm());
            assertEquals(0, profile.getErrorRows());
            assertEquals(1, csvImportHelper.commitImport(profile.getSessionId(), requiredRepo).getImported());
            assertEquals("preserved", requiredRepo.findByRefName(ref).orElseThrow().getRequiredValue());

            // Unrelated invalid data is still checked on the merged document.
            var ds = morphiaDataStoreWrapper.getDataStore(testUtils.getTestRealm());
            var collection = ds.getDatabase().getCollection(ds.getMapper()
                    .getEntityModel(com.e2eq.framework.test.RequiredImportModel.class).collectionName());
            collection.updateOne(Filters.eq("refName", ref), new Document("$unset", new Document("requiredValue", "")));
            var invalid = csvImportHelper.analyzeCSV(requiredRepo, stream(csv), ',', '"', true, COLUMNS,
                    StandardCharsets.UTF_8, false, "QUOTE_WHERE_ESSENTIAL");
            assertEquals(1, invalid.getErrorRows());
            csvImportHelper.cancelImport(invalid.getSessionId());
            var invalidDirect = csvImportHelper.importCSV(requiredRepo, stream(csv), ',', '"', true, COLUMNS,
                    StandardCharsets.UTF_8, false, "QUOTE_WHERE_ESSENTIAL", record -> { });
            assertEquals(1, invalidDirect.getErrorRows());
            assertEquals(0, invalidDirect.getValidRows());
            assertEquals(0, invalidDirect.getImportedCount());
        } finally {
            var ds = morphiaDataStoreWrapper.getDataStore(testUtils.getTestRealm());
            ds.getDatabase().getCollection(ds.getMapper().getEntityModel(
                    com.e2eq.framework.test.RequiredImportModel.class).collectionName()).deleteMany(Filters.eq("refName", ref));
        }
    }

    @Test
    public void profileComputedValuesPersistOnUpdateAndMasksArePerRow() throws IOException {
        String first = unique("Computed");
        String second = unique("Unchanged");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            saveModel(first, "Original", "keep-first");
            saveModel(second, "Original", "keep-second");
            ImportProfile profile = new ImportProfile();
            profile.setPreValidationTransformerNames(List.of("pr33-row-mask"));
            profile.setInlineCalculators(List.of(com.e2eq.framework.model.persistent.imports.InlineFieldCalculator.builder()
                    .fieldName("displayName")
                    .type(com.e2eq.framework.model.persistent.imports.InlineFieldCalculator.CalculationType.STATIC)
                    .staticValue("Calculated").build()));
            var preview = csvImportHelper.analyzeCSVWithProfile(repo,
                    stream("refName,testField3\n" + first + ",one\n" + second + ",two\n"), profile,
                    List.of("refName", "testField3"), testUtils.getTestRealm());
            assertEquals(0, preview.getErrorRows());
            ImportSessionRow staged = importSessionRowRepo.findByRefName(
                    ImportSessionRow.stagingRefName(preview.getSessionId(), 1)).orElseThrow();
            assertTrue(staged.getImportedColumns().contains("displayName"));
            assertFalse(staged.getImportedColumns().contains("testField"));
            assertTrue(staged.getImportedColumns().contains("numberField"));
            ImportSessionRow secondStaged = importSessionRowRepo.findByRefName(
                    ImportSessionRow.stagingRefName(preview.getSessionId(), 2)).orElseThrow();
            assertFalse(secondStaged.getImportedColumns().contains("numberField"));

            // A value not supplied or changed by hooks must retain edits made after preview.
            CSVModel edited = repo.findByRefName(first).orElseThrow();
            edited.setTestField("changed-after-preview");
            repo.save(edited);
            assertEquals(2, csvImportHelper.commitImport(preview.getSessionId(), repo).getImported());
            assertEquals("Calculated", repo.findByRefName(first).orElseThrow().getDisplayName());
            assertEquals("changed-after-preview", repo.findByRefName(first).orElseThrow().getTestField());
            assertEquals("keep-second", repo.findByRefName(second).orElseThrow().getTestField());
            assertEquals(0, repo.findByRefName(first).orElseThrow().getNumberField());
            assertEquals(7, repo.findByRefName(second).orElseThrow().getNumberField());
        } finally {
            deleteByRefPattern(first);
            deleteByRefPattern(second);
        }
    }

    @Test
    public void importNeverUpdatesAnotherTenantsOnlyMatch() {
        String ref = unique("Foreign");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            CSVModel other = saveModel(ref, "Foreign original", "untouched");
            collection().updateOne(Filters.eq("_id", other.getId()),
                    new Document("$set", new Document("dataDomain.tenantId", "foreign-tenant")));
            var imported = importCsv(ref + ",Own record\n");
            assertEquals(1, imported.getInsertCount());
            assertEquals(0, imported.getUpdateCount());
            assertEquals("Foreign original", collection().find(Filters.eq("_id", other.getId())).first().getString("displayName"));
            assertEquals(2, collection().countDocuments(refFilter(ref)));
            var updated = importCsv(ref + ",Own changed\n");
            assertEquals(1, updated.getUpdateCount());
            assertEquals("Foreign original", collection().find(Filters.eq("_id", other.getId())).first().getString("displayName"));
        } finally {
            deleteByRefPattern(ref);
        }
    }

    @Test
    public void sharedDatabaseReferenceQueriesRequireTheCallersTenant() {
        String ref = unique("SharedScope");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            var ds = morphiaDataStoreWrapper.getDataStore(testUtils.getSystemRealm());
            CSVModel foreign = new CSVModel();
            foreign.setRefName(ref);
            foreign.setDataDomain(DataDomain.builder().tenantId("foreign.example")
                    .orgRefName(pContext.getDataDomain().getOrgRefName())
                    .accountNum(pContext.getDataDomain().getAccountNum())
                    .ownerId(pContext.getDataDomain().getOwnerId()).build());
            ds.save(foreign);
            assertTrue(repo.findByRefName(ds, ref, true).isEmpty());
            assertTrue(repo.getListFromRefNames(ds, List.of(ref)).isEmpty());
            CSVModel own = new CSVModel();
            own.setRefName(ref);
            own.setDataDomain(pContext.getDataDomain());
            ds.save(own);
            assertEquals(own.getId(), repo.findByRefName(ds, ref, true).orElseThrow().getId());
            assertEquals(List.of(own.getId()), repo.getListFromRefNames(ds, List.of(ref)).stream()
                    .map(CSVModel::getId).toList());
        } finally {
            var ds = morphiaDataStoreWrapper.getDataStore(testUtils.getSystemRealm());
            ds.getDatabase().getCollection(ds.getMapper().getEntityModel(CSVModel.class).collectionName())
                    .deleteMany(Filters.eq("refName", ref));
        }
    }

    @Inject
    com.e2eq.framework.model.persistent.morphia.UserProfileRepo userProfileRepo;

    @Inject
    com.e2eq.framework.model.persistent.morphia.CredentialRepo credentialRepo;

    @Inject
    com.e2eq.framework.util.SecurityUtils securityUtils;

    @Test
    public void profileDeleteIsRefusedWhileALinkedCredentialIsHiddenFromTheCaller() throws Exception {
        String userId = "xtenant-" + UUID.randomUUID().toString().substring(0, 8) + "@test.example";
        String systemRealm = testUtils.getSystemRealm();
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            var profile = userProfileRepo.createUser(userId, "Cross", "Tenant", false, new String[] {"user"},
                    "Passw0rd-xtenant", testRealmDomainContext());
            var credential = credentialRepo.findByUserId(userId, systemRealm, true).orElseThrow();
            var additional = new com.e2eq.framework.model.security.CredentialUserIdPassword();
            additional.setUserId("token-" + userId);
            additional.setSubject(UUID.randomUUID().toString());
            additional.setRefName(additional.getSubject());
            additional.setDomainContext(credential.getDomainContext());
            additional.setLastUpdate(new java.util.Date());
            additional.setRoles(new String[]{"user"});
            additional.setCredentialType(com.e2eq.framework.model.security.CredentialType.SERVICE_TOKEN);
            additional.setParentCredentialSubject(credential.getSubject());
            credentialRepo.save(additional);
            profile.setAdditionalCredentialRefs(List.of(additional.createEntityReference(systemRealm)));
            userProfileRepo.save(profile);
            // As for a tenant's initial admin, whose credential carries the provisioning caller's tenant.
            var ds = morphiaDataStoreWrapper.getDataStore(systemRealm);
            ds.getDatabase().getCollection(ds.getMapper().getEntityModel(
                    com.e2eq.framework.model.security.CredentialUserIdPassword.class).collectionName())
                    .updateMany(Filters.in("_id", List.of(credential.getId(), additional.getId())),
                            new Document("$set", new Document("dataDomain.tenantId", "provisioning-tenant.example")));

            // Ordinary credential rules hide these records from the caller. Deleting the profile must
            // neither bypass those rules nor leave a working login behind, so nothing is deleted.
            assertTrue(credentialRepo.findByRefName(ds, credential.getRefName(), true).isPresent(),
                    "a credential's reference is global, not scoped to the caller's tenant");
            assertTrue(credentialRepo.findByRefName(ds, credential.getRefName(), false).isEmpty());
            var refused = org.junit.jupiter.api.Assertions.assertThrows(com.e2eq.framework.exceptions.ReferentialIntegrityViolationException.class,
                    () -> userProfileRepo.delete(testUtils.getTestRealm(), profile.getId()));
            assertTrue(refused.getMessage().contains("not permitted"), refused.getMessage());
            assertFalse(com.e2eq.framework.model.securityrules.SecurityContext.isIgnoringRules());
            assertTrue(userProfileRepo.findById(profile.getId(), testUtils.getTestRealm()).isPresent());
            assertTrue(credentialRepo.findByUserId(userId, systemRealm, true).isPresent());
            assertTrue(credentialRepo.findByUserId("token-" + userId, systemRealm, true).isPresent());

            // A caller whose rules reach the credentials deletes the profile and both credentials.
            try (var system = com.e2eq.framework.model.securityrules.SecurityCallScope.openIgnoringRules()) {
                assertEquals(1, userProfileRepo.delete(testUtils.getTestRealm(), profile.getId()));
            }
            assertTrue(credentialRepo.findByUserId(userId, systemRealm, true).isEmpty());
            assertTrue(credentialRepo.findByUserId("token-" + userId, systemRealm, true).isEmpty());
        } finally {
            var ds = morphiaDataStoreWrapper.getDataStore(systemRealm);
            ds.getDatabase().getCollection(ds.getMapper().getEntityModel(
                    com.e2eq.framework.model.security.CredentialUserIdPassword.class).collectionName())
                    .deleteMany(Filters.in("userId", List.of(userId, "token-" + userId)));
            var tds = morphiaDataStoreWrapper.getDataStore(testUtils.getTestRealm());
            tds.getDatabase().getCollection(tds.getMapper().getEntityModel(
                    com.e2eq.framework.model.security.UserProfile.class).collectionName())
                    .deleteMany(Filters.in("userId", List.of(userId, "token-" + userId)));
        }
    }

    @Test
    public void profileCascadeUsesPersistedReferencesAndRequiresAnAuthorizedProfile() throws Exception {
        String firstId = "cascade-first-" + UUID.randomUUID().toString().substring(0, 8) + "@test.example";
        String secondId = "cascade-second-" + UUID.randomUUID().toString().substring(0, 8) + "@test.example";
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            var first = userProfileRepo.createUser(firstId, "First", "Owner", false, new String[]{"user"},
                    "Passw0rd-cascade", testRealmDomainContext());
            var second = userProfileRepo.createUser(secondId, "Second", "Owner", false, new String[]{"user"},
                    "Passw0rd-cascade", testRealmDomainContext());
            // A caller cannot replace the persisted reference just by passing a different object to delete.
            first.setCredentialUserIdPasswordRef(second.getCredentialUserIdPasswordRef());
            assertEquals(1, userProfileRepo.delete(testUtils.getTestRealm(), first));
            assertTrue(credentialRepo.findByUserId(firstId, testUtils.getSystemRealm(), true).isEmpty());
            assertTrue(credentialRepo.findByUserId(secondId, testUtils.getSystemRealm(), true).isPresent());

            var ds = morphiaDataStoreWrapper.getDataStore(testUtils.getTestRealm());
            ds.getDatabase().getCollection(ds.getMapper().getEntityModel(com.e2eq.framework.model.security.UserProfile.class).collectionName())
                    .updateOne(Filters.eq("_id", second.getId()), new Document("$set", new Document("dataDomain.tenantId", "invisible.example")));
            assertEquals(0, userProfileRepo.delete(testUtils.getTestRealm(), second));
            assertTrue(credentialRepo.findByUserId(secondId, testUtils.getSystemRealm(), true).isPresent());
            assertFalse(com.e2eq.framework.model.securityrules.SecurityContext.isIgnoringRules());
        } finally {
            for (String realm : List.of(testUtils.getSystemRealm(), testUtils.getTestRealm())) {
                var ds = morphiaDataStoreWrapper.getDataStore(realm);
                for (Class<?> type : List.of(com.e2eq.framework.model.security.CredentialUserIdPassword.class,
                        com.e2eq.framework.model.security.UserProfile.class)) {
                    ds.getDatabase().getCollection(ds.getMapper().getEntityModel(type).collectionName())
                            .deleteMany(Filters.in("userId", List.of(firstId, secondId)));
                }
            }
        }
    }

    @Test
    public void profileReferencesCannotDeleteCredentialsHiddenFromTheCaller() throws Exception {
        String attackerId = "cascade-attacker-" + UUID.randomUUID().toString().substring(0, 8) + "@test.example";
        String victimId = "cascade-victim-" + UUID.randomUUID().toString().substring(0, 8) + "@test.example";
        String foreignId = "cascade-foreign-" + UUID.randomUUID().toString().substring(0, 8) + "@test.example";
        String tokenId = "svc:" + UUID.randomUUID();
        List<String> userIds = List.of(attackerId, victimId, foreignId, tokenId);
        String systemRealm = testUtils.getSystemRealm();
        var credentials = morphiaDataStoreWrapper.getDataStore(systemRealm);
        MongoCollection<Document> credentialCollection = credentials.getDatabase().getCollection(credentials.getMapper()
                .getEntityModel(com.e2eq.framework.model.security.CredentialUserIdPassword.class).collectionName());
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            var attacker = userProfileRepo.createUser(attackerId, "Attacker", "Admin", false, new String[]{"user"},
                    "Passw0rd-cascade", testRealmDomainContext());
            userProfileRepo.createUser(victimId, "Victim", "User", false, new String[]{"user"},
                    "Passw0rd-cascade", testRealmDomainContext());
            var victim = credentialRepo.findByUserId(victimId, systemRealm, true).orElseThrow();
            var foreign = new com.e2eq.framework.model.security.CredentialUserIdPassword();
            foreign.setUserId(foreignId);
            foreign.setSubject(UUID.randomUUID().toString());
            foreign.setRefName(foreign.getSubject());
            foreign.setDomainContext(new com.e2eq.framework.model.security.DomainContext(
                    pContext.getDataDomain(), "another-tenant-com"));
            foreign.setLastUpdate(new java.util.Date());
            foreign.setRoles(new String[]{"user"});
            credentialRepo.save(foreign);
            // The victim's service token, which has no profile of its own.
            var token = new com.e2eq.framework.model.security.CredentialUserIdPassword();
            token.setUserId(tokenId);
            token.setSubject(tokenId.substring(4));
            token.setRefName(tokenId);
            token.setDomainContext(testRealmDomainContext());
            token.setLastUpdate(new java.util.Date());
            token.setRoles(new String[]{"user"});
            token.setCredentialType(com.e2eq.framework.model.security.CredentialType.SERVICE_TOKEN);
            token.setParentCredentialSubject(victim.getSubject());
            credentialRepo.save(token);
            // All three are hidden from the caller by its ordinary credential rules.
            credentialCollection.updateMany(Filters.in("_id", List.of(victim.getId(), foreign.getId(), token.getId())),
                    new Document("$set", new Document("dataDomain.tenantId", "provisioning-tenant.example")));

            // Through ordinary profile saves: the profile takes the token's userId and login, and
            // references the other users' credentials.
            attacker.setUserId(tokenId);
            attacker.setCredentialUserIdPasswordRef(token.createEntityReference(systemRealm));
            attacker.setAdditionalCredentialRefs(List.of(victim.createEntityReference(systemRealm),
                    foreign.createEntityReference(systemRealm)));
            userProfileRepo.save(attacker);

            org.junit.jupiter.api.Assertions.assertThrows(com.e2eq.framework.exceptions.ReferentialIntegrityViolationException.class,
                    () -> userProfileRepo.delete(testUtils.getTestRealm(), attacker.getId()));
            assertFalse(com.e2eq.framework.model.securityrules.SecurityContext.isIgnoringRules());
            assertTrue(userProfileRepo.findById(attacker.getId(), testUtils.getTestRealm()).isPresent(),
                    "nothing is deleted");
            for (String userId : List.of(attackerId, victimId, foreignId, tokenId)) {
                assertTrue(credentialRepo.findByUserId(userId, systemRealm, true).isPresent(), userId);
            }
        } finally {
            credentialCollection.deleteMany(Filters.in("userId", userIds));
            var tds = morphiaDataStoreWrapper.getDataStore(testUtils.getTestRealm());
            tds.getDatabase().getCollection(tds.getMapper().getEntityModel(
                    com.e2eq.framework.model.security.UserProfile.class).collectionName())
                    .deleteMany(Filters.in("userId", userIds));
        }
    }

    @Test
    public void failedCredentialDeleteRestoresTheProfileSoTheDeleteCanBeRetried() throws Exception {
        String userId = "cascade-retry-" + UUID.randomUUID().toString().substring(0, 8) + "@test.example";
        String tokenId = "svc:" + UUID.randomUUID();
        String systemRealm = testUtils.getSystemRealm();
        var credentials = morphiaDataStoreWrapper.getDataStore(systemRealm);
        MongoCollection<Document> credentialCollection = credentials.getDatabase().getCollection(credentials.getMapper()
                .getEntityModel(com.e2eq.framework.model.security.CredentialUserIdPassword.class).collectionName());
        var profiles = morphiaDataStoreWrapper.getDataStore(testUtils.getTestRealm());
        MongoCollection<Document> profileCollection = profiles.getDatabase().getCollection(profiles.getMapper()
                .getEntityModel(com.e2eq.framework.model.security.UserProfile.class).collectionName());
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            var profile = userProfileRepo.createUser(userId, "Retry", "Cascade", false, new String[]{"user"},
                    "Passw0rd-cascade", testRealmDomainContext());
            var credential = credentialRepo.findByUserId(userId, systemRealm, true).orElseThrow();
            // A service token issued under the user's login, as AuthResource links it.
            var token = new com.e2eq.framework.model.security.CredentialUserIdPassword();
            token.setUserId(tokenId);
            token.setSubject(tokenId.substring(4));
            token.setRefName(tokenId);
            token.setDomainContext(testRealmDomainContext());
            token.setLastUpdate(new java.util.Date());
            token.setRoles(new String[]{"user"});
            token.setCredentialType(com.e2eq.framework.model.security.CredentialType.SERVICE_TOKEN);
            token.setParentCredentialSubject(credential.getSubject());
            credentialRepo.save(token);
            profile.setAdditionalCredentialRefs(List.of(token.createEntityReference(systemRealm)));
            userProfileRepo.save(profile);
            Document before = profileCollection.find(Filters.eq("_id", profile.getId())).first();
            assertNotNull(before);
            // A live back-reference (here, to the system user's credential) blocks deleting the token.
            var blocker = credentialRepo.findByUserId(testUtils.getSystemUserId(), systemRealm, true).orElseThrow();
            credentialCollection.updateOne(Filters.eq("_id", token.getId()), new Document("$set",
                    new Document("references", List.of(new Document("referencedId", blocker.getId())
                            .append("type", com.e2eq.framework.model.security.CredentialUserIdPassword.class.getName())
                            .append("refName", blocker.getRefName())))));

            org.junit.jupiter.api.Assertions.assertThrows(
                    com.e2eq.framework.exceptions.ReferentialIntegrityViolationException.class,
                    () -> userProfileRepo.delete(testUtils.getTestRealm(), profile.getId()));
            assertFalse(com.e2eq.framework.model.securityrules.SecurityContext.isIgnoringRules());
            assertTrue(credentialRepo.findByUserId(tokenId, systemRealm, true).isPresent());
            assertTrue(credentialRepo.findByUserId(userId, systemRealm, true).isPresent(),
                    "the login is deleted last, so it survives a failed cascade");
            Document restored = profileCollection.find(Filters.eq("_id", profile.getId())).first();
            assertEquals(before, restored, "the profile is restored exactly as it was stored");

            // Once the token can be deleted, retrying the profile delete completes the cascade.
            credentialCollection.updateOne(Filters.eq("_id", token.getId()),
                    new Document("$unset", new Document("references", "")));
            assertEquals(1, userProfileRepo.delete(testUtils.getTestRealm(), profile.getId()));
            assertTrue(credentialRepo.findByUserId(userId, systemRealm, true).isEmpty());
            assertTrue(credentialRepo.findByUserId(tokenId, systemRealm, true).isEmpty());
            assertEquals(0, profileCollection.countDocuments(Filters.eq("_id", profile.getId())));
        } finally {
            credentialCollection.deleteMany(Filters.in("userId", List.of(userId, tokenId)));
            profileCollection.deleteMany(Filters.eq("userId", userId));
        }
    }

    /** A login homed in the test realm, where these tests store user profiles. */
    private com.e2eq.framework.model.security.DomainContext testRealmDomainContext() {
        return new com.e2eq.framework.model.security.DomainContext(pContext.getDataDomain(), testUtils.getTestRealm());
    }

    @Test
    @io.quarkus.test.security.TestSecurity(user = "test@system.com", roles = {"admin"})
    public void sharedDatabaseLookupResolvesTheCallerFromTheRequestIdentity() {
        String ref = unique("LazyPrincipal");
        var ds = morphiaDataStoreWrapper.getDataStore(testUtils.getSystemRealm());
        com.e2eq.framework.model.securityrules.SecurityContext.clear();
        try {
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> repo.findByRefName(ds, ref));
            assertTrue(com.e2eq.framework.model.securityrules.SecurityContext.getPrincipalContext().isPresent(),
                    "the principal was resolved from the request identity");
        } finally {
            com.e2eq.framework.model.securityrules.SecurityContext.clear();
        }
    }

    @Test
    public void exactCaseLookupConsidersLegacyMatchesBeyondFirstTwo() {
        String ref = unique("Legacy");
        String realm = "legacy-lookup-" + UUID.randomUUID();
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            var ds = new MorphiaDatastore(mongoClient, dev.morphia.config.ManualMorphiaConfig.configure()
                    .database(realm).applyIndexes(false));
            ds.getMapper().map(CSVModel.class);
            List<CSVModel> documents = new ArrayList<>();
            for (String spelling : List.of(ref.toLowerCase(), ref, ref.toUpperCase())) {
                CSVModel model = new CSVModel();
                model.setRefName(spelling);
                model.setDataDomain(pContext.getDataDomain());
                documents.add(ds.save(model));
            }
            assertEquals(documents.get(2).getId(),
                    repo.findByRefName(ds, ref.toUpperCase(), true).orElseThrow().getId());
        } finally {
            mongoClient.getDatabase(realm).drop();
        }
    }

    @Test
    public void unicodeReferenceComparisonAgreesWithMongo() {
        String prefix = unique("Unicode");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            var result = importCsv(prefix + "-strasse,Double S\n" + prefix + "-straße,Sharp S\n");
            assertEquals(2, result.getImportedCount());
            assertEquals(1, repo.getListFromRefNames(List.of(prefix + "-STRASSE")).size());
            assertEquals(1, repo.getListFromRefNames(List.of(prefix + "-STRAẞE")).size());
        } finally {
            collection().deleteMany(Filters.regex("refName", "^" + Pattern.quote(prefix)));
        }
    }

    @Test
    public void duplicatesAcrossBatchBoundaryAreBothRejectedBeforeSaving() {
        String prefix = unique("Batches");
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 1001; i++) {
            rows.append(prefix).append('-').append(i == 1000 ? 0 : i).append(",Row\n");
        }
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            var result = importCsv(rows.toString());
            assertEquals(999, result.getImportedCount());
            assertEquals(2, result.getFailedCount());
            assertEquals(0, collection().countDocuments(Filters.eq("refName", prefix + "-0")));
            assertEquals(List.of(1, 1001), result.getRowResults().stream()
                    .map(CSVImportHelper.ImportRowResult::getRowNumber).sorted().toList());
        } finally {
            collection().deleteMany(Filters.regex("refName", "^" + Pattern.quote(prefix)));
        }
    }

    @Inject
    com.e2eq.framework.persistent.HiddenFieldImportModelRepo hiddenRepo;

    @Test
    public void updatesKeepPersistedValuesHiddenFromJsonOnEveryImportPath() throws IOException {
        String ref = unique("Hidden");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            HiddenFieldImportModel original = new HiddenFieldImportModel();
            original.setRefName(ref);
            original.setRequiredKey("key");
            original.setSecret("original");
            hiddenRepo.save(original);

            // The parsed row leaves requiredKey null; replaying it must not call the null-rejecting setter.
            var direct = csvImportHelper.importCSV(hiddenRepo, stream("refName,displayName\n" + ref + ",Direct\n"),
                    ',', '"', true, COLUMNS, StandardCharsets.UTF_8, false, "QUOTE_WHERE_ESSENTIAL", record -> { });
            assertEquals(1, direct.getUpdateCount(), direct.getRowResults().toString());
            assertHiddenValuesKept(ref, "Direct");

            var plain = csvImportHelper.analyzeCSV(hiddenRepo, stream("refName,displayName\n" + ref + ",Plain\n"),
                    ',', '"', true, COLUMNS, StandardCharsets.UTF_8, false, "QUOTE_WHERE_ESSENTIAL");
            assertEquals(0, plain.getErrorRows());
            assertEquals(1, csvImportHelper.commitImport(plain.getSessionId(), hiddenRepo).getImported());
            assertHiddenValuesKept(ref, "Plain");

            var profile = csvImportHelper.analyzeCSVWithProfile(hiddenRepo,
                    stream("refName,displayName\n" + ref + ",Profile\n"), new ImportProfile(), COLUMNS,
                    testUtils.getTestRealm());
            assertEquals(0, profile.getErrorRows());
            assertEquals(1, csvImportHelper.commitImport(profile.getSessionId(), hiddenRepo).getImported());
            assertHiddenValuesKept(ref, "Profile");
        } finally {
            hiddenCollection().deleteMany(Filters.eq("refName", ref));
        }
    }

    @Test
    public void directImportInsertKeepsParsedValuesExactly() {
        String ref = unique("HiddenInsert");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            var result = csvImportHelper.importCSV(hiddenRepo,
                    stream("refName,requiredKey,secret,status\n" + ref + ",key,supplied,\n"), ',', '"', true,
                    List.of("refName", "requiredKey", "secret", "status"), StandardCharsets.UTF_8, false,
                    "QUOTE_WHERE_ESSENTIAL", record -> { });
            assertEquals(1, result.getInsertCount(), result.getRowResults().toString());
            Document saved = hiddenCollection().find(Filters.eq("refName", ref)).first();
            assertNotNull(saved);
            org.junit.jupiter.api.Assertions.assertAll(
                    () -> assertEquals("hashed:supplied", saved.getString("secretHash")),
                    () -> assertNull(saved.get("status"), "a blank cell clears a property that has a default"));
        } finally {
            hiddenCollection().deleteMany(Filters.eq("refName", ref));
        }
    }

    @Test
    public void directImportReplaysQuotedMultiLineValuesExactly() {
        String ref = unique("MultiLine");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            var result = importCsv(ref + ",\"first \"\"line\"\"\r\nsecond, line\"\n");
            assertEquals(1, result.getInsertCount(), result.getRowResults().toString());
            assertEquals("first \"line\"\nsecond, line", repo.findByRefName(ref).orElseThrow().getDisplayName());
        } finally {
            deleteByRefPattern(ref);
        }
    }

    @Test
    public void profileThatChangesAnExistingRecordsReferenceIsRejected() throws IOException {
        String ref = unique("Target");
        String changed = ref + "-normalized";
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            CSVModel original = saveModel(ref, "Original", "kept");
            ImportProfile profile = new ImportProfile();
            profile.setInlineCalculators(List.of(com.e2eq.framework.model.persistent.imports.InlineFieldCalculator.builder()
                    .fieldName("refName")
                    .type(com.e2eq.framework.model.persistent.imports.InlineFieldCalculator.CalculationType.STATIC)
                    .staticValue(changed).build()));

            var preview = csvImportHelper.analyzeCSVWithProfile(repo, stream("refName,displayName\n" + ref + ",Renamed\n"),
                    profile, COLUMNS, testUtils.getTestRealm());

            assertEquals(1, preview.getErrorRows());
            assertTrue(preview.getRowResults().get(0).getErrors().stream()
                    .anyMatch(error -> error.getMessage().contains("changed the reference")),
                    preview.getRowResults().get(0).getErrors().toString());
            assertEquals(0, csvImportHelper.commitImport(preview.getSessionId(), repo).getImported());
            Document kept = collection().find(Filters.eq("_id", original.getId())).first();
            assertNotNull(kept);
            assertEquals(ref, kept.getString("refName"));
            assertEquals("Original", kept.getString("displayName"));
            assertEquals(0, collection().countDocuments(refFilter(changed)));
        } finally {
            deleteByRefPattern(ref);
            deleteByRefPattern(changed);
        }
    }

    @Test
    public void directImportUpdateAppliesValuesOnlyASetterReceives() {
        String ref = unique("WriteOnly");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            HiddenFieldImportModel original = new HiddenFieldImportModel();
            original.setRefName(ref);
            original.setRequiredKey("key");
            original.setSecret("original");
            hiddenRepo.save(original);

            var result = csvImportHelper.importCSV(hiddenRepo,
                    stream("refName,displayName,secret\n" + ref + ",Replaced,replacement\n"), ',', '"', true,
                    List.of("refName", "displayName", "secret"), StandardCharsets.UTF_8, false,
                    "QUOTE_WHERE_ESSENTIAL", record -> { });

            assertEquals(1, result.getUpdateCount(), result.getRowResults().toString());
            Document saved = hiddenCollection().find(Filters.eq("refName", ref)).first();
            assertNotNull(saved);
            assertEquals("hashed:replacement", saved.getString("secretHash"));
            assertEquals("Replaced", saved.getString("displayName"));
            assertEquals("key", saved.getString("requiredKey"));
        } finally {
            hiddenCollection().deleteMany(Filters.eq("refName", ref));
        }
    }

    @Test
    public void virtualSetterReplayKeepsTheCsvColumnOrder() {
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            for (boolean aliasFirst : List.of(true, false)) {
                String ref = unique("VirtualSetterOrder");
                try {
                    var original = new HiddenFieldImportModel();
                    original.setRefName(ref); original.setRequiredKey("key"); hiddenRepo.save(original);
                    List<String> columns = aliasFirst
                            ? List.of("refName", "displayAlias", "displayName")
                            : List.of("refName", "displayName", "displayAlias");
                    String values = aliasFirst ? ",alias,explicit" : ",explicit,alias";
                    var result = csvImportHelper.importCSV(hiddenRepo, stream(String.join(",", columns) + "\n" + ref + values + "\n"),
                            ',', '"', true, columns, StandardCharsets.UTF_8, false, "QUOTE_WHERE_ESSENTIAL", record -> { });
                    assertEquals(1, result.getUpdateCount(), result.getRowResults().toString());
                    assertEquals(aliasFirst ? "explicit" : "derived:alias",
                            hiddenCollection().find(Filters.eq("refName", ref)).first().getString("displayName"));
                } finally { hiddenCollection().deleteMany(Filters.eq("refName", ref)); }
            }
        }
    }

    @Test
    public void previewSessionsRejectValuesOnlyASetterReceives() throws IOException {
        String ref = unique("WriteOnlySession");
        List<String> columns = List.of("refName", "displayName", "secret");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            HiddenFieldImportModel original = new HiddenFieldImportModel();
            original.setRefName(ref);
            original.setRequiredKey("key");
            original.setSecret("original");
            hiddenRepo.save(original);
            String csv = "refName,displayName,secret\n" + ref + ",Replaced,replacement\n";

            var plain = csvImportHelper.analyzeCSV(hiddenRepo, stream(csv), ',', '"', true, columns,
                    StandardCharsets.UTF_8, false, "QUOTE_WHERE_ESSENTIAL");
            var profile = csvImportHelper.analyzeCSVWithProfile(hiddenRepo, stream(csv), new ImportProfile(), columns,
                    testUtils.getTestRealm());

            for (var preview : List.of(plain, profile)) {
                assertEquals(1, preview.getErrorRows());
                assertTrue(preview.getRowResults().get(0).getErrors().stream()
                        .anyMatch(error -> error.getMessage().contains("secret")),
                        preview.getRowResults().get(0).getErrors().toString());
                assertEquals(0, csvImportHelper.commitImport(preview.getSessionId(), hiddenRepo).getImported());
            }
            Document saved = hiddenCollection().find(Filters.eq("refName", ref)).first();
            assertNotNull(saved);
            assertEquals("hashed:original", saved.getString("secretHash"));
            assertFalse("Replaced".equals(saved.getString("displayName")));
        } finally {
            hiddenCollection().deleteMany(Filters.eq("refName", ref));
        }
    }

    @Inject
    com.e2eq.framework.persistent.GlobalIdentityImportModelRepo globalRepo;

    @Test
    public void importUpdatesAGlobalIdentityStampedWithAnotherTenant() throws Exception {
        String ref = unique("Global");
        MorphiaDatastore datastore = morphiaDataStoreWrapper.getDataStore(testUtils.getTestRealm());
        MongoCollection<Document> collection = mongoClient.getDatabase(testUtils.getTestRealm()).getCollection(
                datastore.getMapper().getEntityModel(com.e2eq.framework.test.GlobalIdentityImportModel.class).collectionName());
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            var original = new com.e2eq.framework.test.GlobalIdentityImportModel();
            original.setRefName(ref);
            original.setDisplayName("Original");
            original.setTestField("kept");
            globalRepo.save(original);
            // As for a credential carrying its provisioning caller's tenant.
            collection.updateOne(Filters.eq("_id", original.getId()),
                    new Document("$set", new Document("dataDomain.tenantId", "provisioning-tenant.example")));

            // Permission rules may scope reads by tenant; the import helper itself must not, so with rules
            // bypassed the matched global record is updated rather than inserted again or rejected.
            CSVImportHelper.ImportResult<com.e2eq.framework.test.GlobalIdentityImportModel> result;
            try (var bypass = com.e2eq.framework.model.securityrules.SecurityCallScope.openIgnoringRules()) {
                result = csvImportHelper.importCSV(globalRepo, stream("refName,displayName\n" + ref + ",Updated\n"),
                        ',', '"', true, COLUMNS, StandardCharsets.UTF_8, false, "QUOTE_WHERE_ESSENTIAL", record -> { });
            }

            assertEquals(1, result.getUpdateCount(), result.getRowResults().toString());
            assertEquals(1, collection.countDocuments(refFilter(ref)));
            Document saved = collection.find(Filters.eq("_id", original.getId())).first();
            assertNotNull(saved);
            assertEquals("Updated", saved.getString("displayName"));
            assertEquals("kept", saved.getString("testField"));
        } finally {
            collection.deleteMany(refFilter(ref));
        }
    }

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper jsonMapper;

    @Test
    public void persistedSessionsKeepHiddenInsertValuesAndExplicitBlanks() throws IOException {
        List<String> columns = List.of("refName", "requiredKey", "secret", "status");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            for (boolean withProfile : List.of(false, true)) {
                String ref = unique("HiddenSessionInsert");
                try {
                    String csv = "refName,requiredKey,secret,status\n" + ref + ",key,supplied,\n";
                    var preview = withProfile
                            ? csvImportHelper.analyzeCSVWithProfile(hiddenRepo, stream(csv), new ImportProfile(), columns,
                                    testUtils.getTestRealm())
                            : csvImportHelper.analyzeCSV(hiddenRepo, stream(csv), ',', '"', true, columns,
                                    StandardCharsets.UTF_8, false, "QUOTE_WHERE_ESSENTIAL");
                    assertEquals(0, preview.getErrorRows(), preview.getRowResults().toString());
                    var row = importSessionRowRepo.findByRefName(ImportSessionRow.stagingRefName(preview.getSessionId(), 1)).orElseThrow();
                    assertNotNull(row.getRecordStateJson());
                    assertFalse(row.getRecordJson().contains("secretHash"));
                    assertFalse(jsonMapper.valueToTree(row).has("recordStateJson"), "internal state must not be exposed by row APIs");
                    assertEquals(1, csvImportHelper.commitImport(preview.getSessionId(), hiddenRepo).getImported());
                    Document saved = hiddenCollection().find(Filters.eq("refName", ref)).first();
                    assertNotNull(saved);
                    assertEquals("hashed:supplied", saved.getString("secretHash"));
                    assertNull(saved.get("status"));
                } finally { hiddenCollection().deleteMany(Filters.eq("refName", ref)); }
            }
        }
    }

    @Test
    public void persistedSessionUpdatesClearDefaultedFieldsAndRejectLegacyState() throws IOException {
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            for (boolean withProfile : List.of(false, true)) {
                String ref = unique("BlankSessionUpdate");
                try {
                    var original = new HiddenFieldImportModel();
                    original.setRefName(ref); original.setRequiredKey("key"); original.setSecret("original");
                    hiddenRepo.save(original);
                    List<String> columns = List.of("refName", "status");
                    String csv = "refName,status\n" + ref + ",\n";
                    var preview = withProfile
                            ? csvImportHelper.analyzeCSVWithProfile(hiddenRepo, stream(csv), new ImportProfile(), columns,
                                    testUtils.getTestRealm())
                            : csvImportHelper.analyzeCSV(hiddenRepo, stream(csv), ',', '"', true, columns,
                                    StandardCharsets.UTF_8, false, "QUOTE_WHERE_ESSENTIAL");
                    assertEquals(0, preview.getErrorRows());
                    assertEquals(1, csvImportHelper.commitImport(preview.getSessionId(), hiddenRepo).getImported());
                    Document saved = hiddenCollection().find(Filters.eq("refName", ref)).first();
                    assertNull(saved.get("status"));
                    assertEquals("hashed:original", saved.getString("secretHash"));
                    assertEquals("key", saved.getString("requiredKey"));

                    var legacy = csvImportHelper.analyzeCSV(hiddenRepo, stream(csv), ',', '"', true, columns,
                            StandardCharsets.UTF_8, false, "QUOTE_WHERE_ESSENTIAL");
                    var row = importSessionRowRepo.findByRefName(ImportSessionRow.stagingRefName(legacy.getSessionId(), 1)).orElseThrow();
                    row.setRecordStateJson(null);
                    importSessionRowRepo.save(row);
                    var commit = csvImportHelper.commitImport(legacy.getSessionId(), hiddenRepo);
                    assertEquals(0, commit.getImported());
                    assertEquals(1, commit.getFailed());
                } finally { hiddenCollection().deleteMany(Filters.eq("refName", ref)); }
            }
        }
    }

    @Test
    public void credentialBatchLookupUsesTheSystemDatabaseForATenantCaller() throws Exception {
        String userId = "credential-import-" + UUID.randomUUID().toString().substring(0, 8) + "@test.example";
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            var profile = userProfileRepo.createUser(userId, "Import", "Target", false, new String[]{"user"},
                    "Passw0rd-import", securityUtils.getDefaultDomainContext());
            var credential = credentialRepo.findByUserId(userId, testUtils.getSystemRealm(), true).orElseThrow();
            String ref = credential.getRefName();
            var result = csvImportHelper.importCSV(credentialRepo, stream("refName,description\n" + ref + ",Updated\n"),
                    ',', '"', true, List.of("refName", "description"), StandardCharsets.UTF_8, false,
                    "QUOTE_WHERE_ESSENTIAL", record -> { });
            assertEquals(1, result.getUpdateCount(), result.getRowResults().toString());
            assertEquals("Updated", credentialRepo.findByUserId(userId, testUtils.getSystemRealm(), true).orElseThrow().getDescription());
            assertEquals(credential.getPasswordHash(), credentialRepo.findByUserId(userId, testUtils.getSystemRealm(), true).orElseThrow().getPasswordHash());
            assertEquals(1, userProfileRepo.delete(testUtils.getTestRealm(), profile));
        } finally {
            for (String realm : List.of(testUtils.getSystemRealm(), testUtils.getTestRealm())) {
                var ds = morphiaDataStoreWrapper.getDataStore(realm);
                for (Class<?> type : List.of(com.e2eq.framework.model.security.CredentialUserIdPassword.class,
                        com.e2eq.framework.model.security.UserProfile.class)) {
                    ds.getDatabase().getCollection(ds.getMapper().getEntityModel(type).collectionName())
                            .deleteMany(Filters.eq("userId", userId));
                }
            }
        }
    }

    private void assertHiddenValuesKept(String ref, String displayName) {
        Document saved = hiddenCollection().find(Filters.eq("refName", ref)).first();
        assertNotNull(saved);
        assertEquals(displayName, saved.getString("displayName"));
        assertEquals("hashed:original", saved.getString("secretHash"));
        assertEquals("key", saved.getString("requiredKey"));
    }

    private MongoCollection<Document> hiddenCollection() {
        MorphiaDatastore datastore = morphiaDataStoreWrapper.getDataStore(testUtils.getTestRealm());
        String name = datastore.getMapper().getEntityModel(HiddenFieldImportModel.class).collectionName();
        return mongoClient.getDatabase(testUtils.getTestRealm()).getCollection(name);
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
