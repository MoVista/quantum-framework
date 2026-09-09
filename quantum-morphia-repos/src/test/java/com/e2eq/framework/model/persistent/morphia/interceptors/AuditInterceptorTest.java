package com.e2eq.framework.model.persistent.morphia.interceptors;

import com.e2eq.framework.model.persistent.base.AuditInfo;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Container-free tests for how {@link AuditInterceptor} restamps {@code auditInfo} on persist.
 */
class AuditInterceptorTest {

    static class StampModel extends UnversionedBaseModel {}

    private final AuditInterceptor interceptor = new AuditInterceptor();

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    private static DataDomain domain() {
        DataDomain dd = new DataDomain();
        dd.setTenantId("acme-com");
        dd.setOrgRefName("ACME");
        dd.setAccountNum("0001");
        dd.setOwnerId("john@acme.com");
        dd.setDataSegment(0);
        return dd;
    }

    private static void setPrincipal(PrincipalContext ctx) {
        SecurityContext.setPrincipalContext(ctx);
    }

    private static PrincipalContext.Builder baseBuilder() {
        return new PrincipalContext.Builder()
                .withDefaultRealm("acme-com")
                .withDataDomain(domain())
                .withRoles(new String[] {"USER"})
                .withScope("Authentication");
    }

    @Test
    void impersonatedUpdateThenRealUserUpdate_clearsCurrentWriteFieldsFromDocument() {
        setPrincipal(baseBuilder()
                .withUserId("john@acme.com")
                .withImpersonatedByUserId("admin@system.com")
                .withImpersonatedBySubject("admin-subject")
                .build());

        StampModel model = new StampModel();
        Document document = new Document();
        interceptor.prePersist(model, document, null);

        Document auditDoc = document.get("auditInfo", Document.class);
        assertEquals("admin@system.com", auditDoc.get("impersonatorUserId"));
        assertEquals("admin@system.com", auditDoc.get("lastImpersonatedByUserId"));

        setPrincipal(baseBuilder().withUserId("john@acme.com").build());
        interceptor.prePersist(model, document, null);

        auditDoc = document.get("auditInfo", Document.class);
        assertFalse(auditDoc.containsKey("impersonatorUserId"));
        assertFalse(auditDoc.containsKey("impersonatorSubject"));
        assertEquals("admin@system.com", auditDoc.get("lastImpersonatedByUserId"));
        assertEquals("john@acme.com", model.getAuditInfo().getLastUpdateIdentity());
        assertNull(model.getAuditInfo().getImpersonatorUserId());
    }

    @Test
    void writeAuditInfoToDocument_removesNullCurrentWriteKeys_keepsSticky() {
        AuditInfo info = new AuditInfo();
        info.setLastUpdateIdentity("john@acme.com");
        info.setLastUpdateTs(new Date());
        info.setLastImpersonatedByUserId("admin@system.com");
        info.setLastImpersonatedAt(new Date());

        Document auditDoc = new Document();
        auditDoc.put("impersonatorUserId", "admin@system.com");
        auditDoc.put("impersonatorSubject", "admin-subject");
        auditDoc.put("lastImpersonatedByUserId", "admin@system.com");
        Document document = new Document("auditInfo", auditDoc);

        AuditInterceptor.writeAuditInfoToDocument(document, info);

        Document written = document.get("auditInfo", Document.class);
        assertFalse(written.containsKey("impersonatorUserId"));
        assertFalse(written.containsKey("impersonatorSubject"));
        assertEquals("admin@system.com", written.get("lastImpersonatedByUserId"));
        assertEquals("john@acme.com", written.get("lastUpdateIdentity"));
    }
}
