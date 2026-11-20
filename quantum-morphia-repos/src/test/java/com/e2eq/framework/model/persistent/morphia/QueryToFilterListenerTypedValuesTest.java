package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.query.filters.Filter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The query gateway always plans queries with the caller's variable map. Values must keep their
 * type there as they do without one: a date compared as text matches nothing stored as a date.
 */
class QueryToFilterListenerTypedValuesTest {

    public static class DummyModel extends UnversionedBaseModel {
        @Override
        public String bmFunctionalArea() { return "test-area"; }

        @Override
        public String bmFunctionalDomain() { return "test-domain"; }
    }

    private static final Map<String, String> VARIABLES = Map.of("tenantId", "tenant-1");

    private static Object plannedValue(String query) {
        Filter filter = MorphiaUtils.convertToPlannedQuery(query, DummyModel.class, null, null, null, VARIABLES)
                .getFilter();
        return filter.getValue();
    }

    @Test
    void aDateTimeStaysADate() {
        assertEquals(
                Date.from(Instant.parse("2026-11-21T07:00:00Z")),
                plannedValue("jobStartDate:<2026-11-21T07:00:00Z"));
        assertEquals(
                Date.from(Instant.parse("2026-11-20T07:00:00Z")),
                plannedValue("jobDueDate:>=2026-11-20T07:00:00.000Z"));
    }

    @Test
    void aDateStaysADate() {
        assertEquals(LocalDate.of(2026, 11, 20), plannedValue("startDate:>=2026-11-20"));
    }

    @Test
    void numbersStayNumbers() {
        assertEquals(60L, plannedValue("estimatedTimeInMinutes:>#60"));
        assertEquals(0.5d, plannedValue("ratio:<##0.5"));
    }

    @Test
    void aVariableIsStillSubstituted() {
        assertEquals("tenant-1", plannedValue("dataDomain.tenantId:${tenantId}~cs"));
    }
}
