package com.e2eq.framework.csv;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.test.CSVModel;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportTenantMatchingTest {

    @Test
    void foreignTenantMatchIsIgnoredWhenThePrincipalIsResolvedDuringTheLookup() {
        CSVModel own = existing("STORE-1", "own-tenant");
        CSVModel foreign = existing("Store-2", "foreign-tenant");
        PrincipalContext principal = new PrincipalContext.Builder()
                .withDefaultRealm("test-realm")
                .withDataDomain(new DataDomain("org", "0000000001", "own-tenant", 0, "importer@own-tenant"))
                .withUserId("importer@own-tenant")
                .withRoles(new String[]{"admin"})
                .withScope("AUTHENTICATED")
                .build();
        // As MorphiaRepo does, the secured lookup resolves the principal from the request identity
        // when the framework security context starts empty.
        @SuppressWarnings("unchecked")
        BaseMorphiaRepo<CSVModel> repo = (BaseMorphiaRepo<CSVModel>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{BaseMorphiaRepo.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getPersistentClass":
                            return CSVModel.class;
                        case "getListFromRefNames":
                            SecurityContext.setPrincipalContext(principal);
                            return List.of(own, foreign);
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });

        SecurityContext.clear();
        try {
            Map<String, CSVModel> existing = new CSVImportHelper().findExistingByRefName(repo,
                    List.of(row("store-1"), row("store-2")));

            assertTrue(SecurityContext.getPrincipalContext().isPresent());
            assertEquals(1, existing.size());
            assertEquals(own.getId(), existing.get("store-1").getId());
            assertTrue(!existing.containsKey("store-2"), "a foreign tenant's record is not an update target");
        } finally {
            SecurityContext.clear();
        }
    }

    private static CSVModel row(String refName) {
        CSVModel row = new CSVModel();
        row.setRefName(refName);
        return row;
    }

    private static CSVModel existing(String refName, String tenantId) {
        CSVModel model = row(refName);
        model.setId(new ObjectId());
        model.setDataDomain(new DataDomain("org", "0000000001", tenantId, 0, "owner@" + tenantId));
        return model;
    }
}
