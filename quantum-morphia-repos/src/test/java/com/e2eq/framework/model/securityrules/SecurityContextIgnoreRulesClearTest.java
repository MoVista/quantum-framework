package com.e2eq.framework.model.securityrules;

import com.e2eq.framework.model.persistent.base.DataDomain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MOV-12294 follow-up: {@link SecurityContext#clear()} must reset ignore-rules
 * depth and context stacks so leaked enter/push cannot survive request
 * teardown on a pooled worker thread.
 */
class SecurityContextIgnoreRulesClearTest {

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
    }

    @Test
    void clear_resetsIgnoreRulesDepth() {
        SecurityContext.enterIgnoreRulesMode();
        assertTrue(SecurityContext.isIgnoringRules());

        SecurityContext.clear();

        assertFalse(SecurityContext.isIgnoringRules());
    }

    @Test
    void clear_resetsNestedIgnoreRulesDepth() {
        SecurityContext.enterIgnoreRulesMode();
        SecurityContext.enterIgnoreRulesMode();
        assertTrue(SecurityContext.isIgnoringRules());

        SecurityContext.clear();

        assertFalse(SecurityContext.isIgnoringRules());
    }

    @Test
    void clear_allowsReenteringIgnoreRulesAfterwards() {
        SecurityContext.enterIgnoreRulesMode();
        SecurityContext.clear();
        assertFalse(SecurityContext.isIgnoringRules());

        SecurityContext.enterIgnoreRulesMode();
        assertTrue(SecurityContext.isIgnoringRules());
    }

    @Test
    void clear_dropsPushedContextsSoPopCannotRestoreStaleIdentity() {
        PrincipalContext outerPrincipal = principal("outer-user");
        PrincipalContext innerPrincipal = principal("inner-user");
        ResourceContext outerResource = resource("outer");
        ResourceContext innerResource = resource("inner");

        SecurityContext.setPrincipalContext(outerPrincipal);
        SecurityContext.setResourceContext(outerResource);
        SecurityContext.pushPrincipalContext(innerPrincipal);
        SecurityContext.pushResourceContext(innerResource);
        assertEquals("inner-user", SecurityContext.getPrincipalContext().orElseThrow().getUserId());
        assertEquals("inner", SecurityContext.getResourceContext().orElseThrow().getResourceId());

        SecurityContext.clear();

        assertTrue(SecurityContext.getPrincipalContext().isEmpty());
        assertTrue(SecurityContext.getResourceContext().isEmpty());

        SecurityContext.popPrincipalContext();
        SecurityContext.popResourceContext();

        assertTrue(SecurityContext.getPrincipalContext().isEmpty());
        assertTrue(SecurityContext.getResourceContext().isEmpty());
    }

    private static PrincipalContext principal(String userId) {
        return new PrincipalContext.Builder()
                .withUserId(userId)
                .withDefaultRealm("test-realm")
                .withScope("AUTHENTICATED")
                .withDataDomain(new DataDomain("end2endlogic", "0000000001", "tenant-1", 0, userId))
                .build();
    }

    private static ResourceContext resource(String resourceId) {
        return new ResourceContext.Builder()
                .withArea("sales")
                .withFunctionalDomain("order")
                .withAction("view")
                .withResourceId(resourceId)
                .build();
    }
}
