package com.e2eq.framework.model.persistent.morphia.interceptors;

import com.e2eq.framework.model.persistent.base.AuditInfo;
import com.e2eq.framework.model.persistent.base.AuditInfoStamper;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import dev.morphia.Datastore;
import dev.morphia.EntityListener;
import dev.morphia.annotations.PrePersist;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.Document;

import java.lang.annotation.Annotation;
import java.util.Date;

@ApplicationScoped
public class AuditInterceptor implements EntityListener<Object> {
    @Override
    @PrePersist
    public void prePersist(Object ent, Document document, Datastore datastore) {
        if (!(ent instanceof UnversionedBaseModel bm)) {
            return;
        }

        // DataDomain is set in ValidationInterceptor.
        boolean creating = bm.getAuditInfo() == null || bm.getAuditInfo().getCreationTs() == null;
        AuditInfo auditInfo = bm.getAuditInfo() == null ? new AuditInfo() : bm.getAuditInfo();
        PrincipalContext ctx = SecurityContext.getPrincipalContext().orElse(null);
        AuditInfoStamper.stamp(auditInfo, ctx, creating, new Date());
        bm.setAuditInfo(auditInfo);
        writeAuditInfoToDocument(document, auditInfo);
    }

    /**
     * Morphia builds the BSON document before {@code prePersist}. Nulls on the entity are omitted
     * from a re-encode, which would leave previously persisted impersonator fields in place, so
     * nested keys that are now null are removed from the document explicitly.
     */
    static void writeAuditInfoToDocument(Document document, AuditInfo auditInfo) {
        if (document == null || auditInfo == null) {
            return;
        }
        Document auditDoc = document.get("auditInfo", Document.class);
        if (auditDoc == null) {
            auditDoc = new Document();
            document.put("auditInfo", auditDoc);
        }
        putOrRemove(auditDoc, "creationTs", auditInfo.getCreationTs());
        putOrRemove(auditDoc, "creationIdentity", auditInfo.getCreationIdentity());
        putOrRemove(auditDoc, "lastUpdateTs", auditInfo.getLastUpdateTs());
        putOrRemove(auditDoc, "lastUpdateIdentity", auditInfo.getLastUpdateIdentity());
        putOrRemove(auditDoc, "impersonatorSubject", auditInfo.getImpersonatorSubject());
        putOrRemove(auditDoc, "impersonatorUserId", auditInfo.getImpersonatorUserId());
        putOrRemove(auditDoc, "lastImpersonatedByUserId", auditInfo.getLastImpersonatedByUserId());
        putOrRemove(auditDoc, "lastImpersonatedAt", auditInfo.getLastImpersonatedAt());
        putOrRemove(auditDoc, "actingOnBehalfOfSubject", auditInfo.getActingOnBehalfOfSubject());
        putOrRemove(auditDoc, "actingOnBehalfOfUserId", auditInfo.getActingOnBehalfOfUserId());
    }

    private static void putOrRemove(Document doc, String key, Object value) {
        if (value == null) {
            doc.remove(key);
        } else {
            doc.put(key, value);
        }
    }

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> type) {
        return false;
    }
}
