package com.e2eq.framework.model.securityrules;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MOV-12294 follow-up: {@link SecurityContext#clear()} must reset ignore-rules
 * depth so a leaked {@code enterIgnoreRulesMode()} cannot survive request
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
}
