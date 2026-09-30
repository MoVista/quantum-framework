package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityContext;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepoSecurityFilterBuilderTest {

    @AfterEach
    void clearContext() {
        SecurityContext.clear();
    }

    @Test
    void reportsWhichSecurityContextsAreMissing() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> new RepoSecurityFilterBuilder(
                        new RepoSecurityContextResolver(null, null, null, null, "system-com"),
                        null)
                        .buildSecuredFilters(List.of(), TestModel.class));

        assertTrue(ex.getMessage().contains("PrincipalContext"));
    }

    /**
     * MOV-12294: MorphiaRepo {@code getListByQuery}, {@code getStreamByQuery},
     * {@code getListFromRefNames}, {@code getListFromIds}, {@code getCount}, and
     * {@code getEntityReferenceListByQuery} call {@code buildSecuredFilters} directly.
     * Ignore-rules must short-circuit here (not only in {@code getFilterArray}), so a
     * null {@link com.e2eq.framework.security.runtime.RuleContext} is never consulted.
     */
    @Test
    void buildSecuredFilters_returnsBaseFilters_whenIgnoringRules() {
        Filter sentinel = Filters.eq("refName", "keep-me");
        List<Filter> input = List.of(sentinel);
        RepoSecurityFilterBuilder builder = new RepoSecurityFilterBuilder(
                new RepoSecurityContextResolver(null, null, null, null, "system-com"),
                null);

        try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
            List<Filter> result = builder.buildSecuredFilters(input, TestModel.class);
            assertSame(input, result);
            assertSame(sentinel, result.get(0));
        }
    }

    @Test
    void getFilterArray_skipsRuleEvaluation_whenIgnoringRules() {
        Filter sentinel = Filters.eq("refName", "keep-me");
        RepoSecurityFilterBuilder builder = new RepoSecurityFilterBuilder(
                new RepoSecurityContextResolver(null, null, null, null, "system-com"),
                null);

        try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
            Filter[] result = builder.getFilterArray(List.of(sentinel), TestModel.class);
            assertTrue(result.length == 1);
            assertSame(sentinel, result[0]);
        }
    }

    static class TestModel extends UnversionedBaseModel {
        @Override
        public String bmFunctionalArea() {
            return "ops";
        }

        @Override
        public String bmFunctionalDomain() {
            return "orders";
        }
    }
}
