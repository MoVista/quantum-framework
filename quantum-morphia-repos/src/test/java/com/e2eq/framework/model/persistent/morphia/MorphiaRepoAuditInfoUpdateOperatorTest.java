package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import dev.morphia.query.updates.UpdateOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pair-update audit operators: clear current-write impersonator fields, keep sticky lastImpersonated*.
 */
class MorphiaRepoAuditInfoUpdateOperatorTest {

    public static class AuditUpdateModel extends UnversionedBaseModel {
        @Override
        public String bmFunctionalArea() {
            return "test-area";
        }

        @Override
        public String bmFunctionalDomain() {
            return "test-domain";
        }
    }

    static class TestRepo extends MorphiaRepo<AuditUpdateModel> {
        List<UpdateOperator> callAddAuditInfoUpdateOperators(String lastUpdateIdentity) {
            List<UpdateOperator> ops = new ArrayList<>();
            addAuditInfoUpdateOperators(ops, lastUpdateIdentity);
            return ops;
        }
    }

    private final TestRepo repo = new TestRepo();

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

    private static PrincipalContext.Builder baseBuilder() {
        return new PrincipalContext.Builder()
                .withDefaultRealm("acme-com")
                .withDataDomain(domain())
                .withRoles(new String[] {"USER"})
                .withScope("Authentication")
                .withUserId("john@acme.com");
    }

    @Test
    void notImpersonating_unsetsCurrentWriteImpersonatorFields() {
        SecurityContext.setPrincipalContext(baseBuilder().build());

        List<UpdateOperator> ops = repo.callAddAuditInfoUpdateOperators("john@acme.com");

        assertTrue(hasUnset(ops, "auditInfo.impersonatorUserId"));
        assertTrue(hasUnset(ops, "auditInfo.impersonatorSubject"));
        assertTrue(ops.stream().noneMatch(op ->
                "$set".equals(op.operator()) && "admin@system.com".equals(op.value())));
    }

    @Test
    void impersonating_setsCurrentWriteAndStickyPair() {
        SecurityContext.setPrincipalContext(baseBuilder()
                .withImpersonatedByUserId("admin@system.com")
                .withImpersonatedBySubject("admin-subject")
                .build());

        List<UpdateOperator> ops = repo.callAddAuditInfoUpdateOperators("john@acme.com");

        long adminUserSets = ops.stream()
                .filter(op -> "$set".equals(op.operator()) && "admin@system.com".equals(op.value()))
                .count();
        assertEquals(2, adminUserSets, "impersonatorUserId and lastImpersonatedByUserId");
        assertTrue(ops.stream().anyMatch(op ->
                "$set".equals(op.operator()) && "admin-subject".equals(op.value())));
        assertTrue(ops.stream().noneMatch(op ->
                "$unset".equals(op.operator())
                        && String.valueOf(op.value()).contains("lastImpersonated")));
        assertEquals("$set", ops.get(0).operator());
    }

    private static boolean hasUnset(List<UpdateOperator> ops, String field) {
        return ops.stream().anyMatch(op ->
                "$unset".equals(op.operator()) && String.valueOf(op.value()).contains(field));
    }
}
