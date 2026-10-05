package com.e2eq.framework.persistent;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.base.ActiveStatus;
import com.e2eq.framework.model.persistent.base.AuditInfo;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.security.runtime.SecuritySession;
import com.e2eq.framework.test.ParentModel;
import com.e2eq.framework.test.UnversionedTestModel;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code auditInfo.lastUpdateTs} as a "last changed" timestamp: set on create, and bumped by
 * {@code updateActiveStatus} so soft deletes reach {@code lastUpdateTs}-based incremental exports.
 */
@QuarkusTest
public class TestAuditInfoStamping extends BaseRepoTest {

    @Inject
    TestParentRepo parentRepo;

    @Inject
    TestUnversionedRepo unversionedRepo;

    @Test
    public void save_newEntity_stampsLastUpdateEqualToCreation() throws ReferentialIntegrityViolationException {
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            ParentModel saved = parentRepo.save(newParent());
            try {
                AuditInfo stored = parentRepo.findById(saved.getId()).orElseThrow().getAuditInfo();

                assertNotNull(stored.getCreationTs());
                assertEquals(stored.getCreationTs(), stored.getLastUpdateTs());
                assertEquals(pContext.getUserId(), stored.getCreationIdentity());
                assertEquals(stored.getCreationIdentity(), stored.getLastUpdateIdentity());
            } finally {
                parentRepo.delete(saved);
            }
        }
    }

    @Test
    public void updateActiveStatus_stampsAuditInfoAndBumpsVersion() throws Exception {
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            ParentModel saved = parentRepo.save(newParent());
            try {
                ParentModel before = parentRepo.findById(saved.getId()).orElseThrow();
                Thread.sleep(5);

                long matched = parentRepo.updateActiveStatus(saved.getId().toHexString(), ActiveStatus.INACTIVE);

                ParentModel after = parentRepo.findById(saved.getId()).orElseThrow();
                assertEquals(1, matched);
                assertEquals(ActiveStatus.INACTIVE, after.getActiveStatus());
                assertTrue(after.getAuditInfo().getLastUpdateTs().after(before.getAuditInfo().getLastUpdateTs()));
                assertEquals(before.getAuditInfo().getCreationTs(), after.getAuditInfo().getCreationTs());
                // No authenticated SecurityIdentity in this test, so the identity comes from the security context.
                assertEquals(pContext.getUserId(), after.getAuditInfo().getLastUpdateIdentity());
                assertEquals(before.getVersion() + 1, after.getVersion());
            } finally {
                parentRepo.delete(parentRepo.findById(saved.getId()).orElseThrow());
            }
        }
    }

    @Test
    @TestSecurity(user = "active-status-caller@test.com", roles = {"admin"})
    public void updateActiveStatus_prefersAuthenticatedSecurityIdentity() throws Exception {
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            ParentModel saved = parentRepo.save(newParent());
            try {
                parentRepo.updateActiveStatus(saved.getId().toHexString(), ActiveStatus.INACTIVE);

                ParentModel after = parentRepo.findById(saved.getId()).orElseThrow();
                assertEquals("active-status-caller@test.com", after.getAuditInfo().getLastUpdateIdentity());
            } finally {
                parentRepo.delete(parentRepo.findById(saved.getId()).orElseThrow());
            }
        }
    }

    @Test
    public void updateActiveStatus_missingId_matchesNothing() {
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            assertEquals(0, parentRepo.updateActiveStatus(new ObjectId().toHexString(), ActiveStatus.INACTIVE));
        }
    }

    @Test
    public void updateActiveStatus_unversionedModel_getsNoVersionField() throws Exception {
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            UnversionedTestModel model = new UnversionedTestModel();
            model.setRefName("audit-unversioned-" + new ObjectId().toHexString());
            model.setDisplayName("Audit unversioned");
            UnversionedTestModel saved = unversionedRepo.save(model);
            try {
                Date lastUpdateBefore = saved.getAuditInfo().getLastUpdateTs();
                Thread.sleep(5);

                assertEquals(1, unversionedRepo.updateActiveStatus(saved.getId().toHexString(), ActiveStatus.INACTIVE));

                Document raw = unversionedRepo.getMorphiaDataStore().getCollection(UnversionedTestModel.class)
                        .withDocumentClass(Document.class)
                        .find(new Document("_id", saved.getId())).first();
                assertNotNull(raw);
                assertFalse(raw.containsKey("version"));
                assertEquals(ActiveStatus.INACTIVE.name(), raw.getString("activeStatus"));
                Document auditInfo = raw.get("auditInfo", Document.class);
                assertTrue(auditInfo.getDate("lastUpdateTs").after(lastUpdateBefore));
            } finally {
                unversionedRepo.delete(unversionedRepo.findById(saved.getId()).orElseThrow());
            }
        }
    }

    @Test
    public void updateActiveStatus_whileImpersonating_stampsImpersonator() throws Exception {
        PrincipalContext impersonated = new PrincipalContext.Builder()
                .withDataDomain(pContext.getDataDomain())
                .withDefaultRealm(pContext.getDefaultRealm())
                .withUserId(pContext.getUserId())
                .withScope("Authentication")
                .withRoles(roles)
                .withImpersonatedByUserId("impersonator@test.com")
                .withImpersonatedBySubject("impersonator-subject")
                .build();

        ParentModel saved;
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            saved = parentRepo.save(newParent());
        }
        try (final SecuritySession s = new SecuritySession(impersonated, rContext)) {
            parentRepo.updateActiveStatus(saved.getId().toHexString(), ActiveStatus.INACTIVE);

            AuditInfo stored = parentRepo.findById(saved.getId()).orElseThrow().getAuditInfo();
            assertEquals("impersonator@test.com", stored.getImpersonatorUserId());
            assertEquals("impersonator-subject", stored.getImpersonatorSubject());
            assertEquals("impersonator@test.com", stored.getLastImpersonatedByUserId());
            assertNotNull(stored.getLastImpersonatedAt());
            assertNull(stored.getActingOnBehalfOfUserId());
        } finally {
            try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
                parentRepo.delete(parentRepo.findById(saved.getId()).orElseThrow());
            }
        }
    }

    private static ParentModel newParent() {
        ParentModel model = new ParentModel();
        model.setRefName("audit-stamping-" + new ObjectId().toHexString());
        model.setDisplayName("Audit stamping");
        return model;
    }
}
