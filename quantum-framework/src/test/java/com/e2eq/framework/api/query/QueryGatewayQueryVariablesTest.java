package com.e2eq.framework.api.query;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gateway plans a query the way entity list endpoints parse theirs, and adds the caller's
 * variables only when the query needs them.
 */
class QueryGatewayQueryVariablesTest {

    public static class DummyModel extends UnversionedBaseModel {
        @Override
        public String bmFunctionalArea() { return "test-area"; }

        @Override
        public String bmFunctionalDomain() { return "test-domain"; }
    }

    private final AtomicInteger variableLookups = new AtomicInteger();

    private final Supplier<Map<String, String>> variables = () -> {
        variableLookups.incrementAndGet();
        return Map.of("principalId", "user-1", "pTenantId", "tenant-1");
    };

    private Object plannedValue(String query) {
        return QueryGatewayResource.planQuery(query, DummyModel.class, null, null, null, variables)
                .getFilter()
                .getValue();
    }

    @Test
    void aDateFilterIsPlannedAsADateWithoutTheCallersVariables() {
        assertEquals(Date.from(Instant.parse("2026-11-21T07:00:00Z")), plannedValue("jobStartDate:<2026-11-21T07:00:00Z"));
        assertEquals(60L, plannedValue("estimatedTimeInMinutes:>#60"));
        assertEquals(0, variableLookups.get());
    }

    @Test
    void aQueryVariableIsStillFilledInFromTheCaller() {
        assertEquals("user-1", plannedValue("dataDomain.ownerId:${principalId}"));
        assertEquals(1, variableLookups.get());
    }

    @Test
    void variablesAndOntologyPredicatesNeedTheCallersVariables() {
        assertTrue(QueryGatewayResource.usesQueryVariables("dataDomain.ownerId:${principalId}"));
        assertTrue(QueryGatewayResource.usesQueryVariables("hasEdge(placedInOrg, ORG-1)"));
        assertTrue(QueryGatewayResource.usesQueryVariables("hasOutgoingEdge(placedInOrg, ORG-1)"));
        assertTrue(QueryGatewayResource.usesQueryVariables("activeStatus:ACTIVE&&hasIncomingEdge (manages, LOC-1)"));
    }

    @Test
    void ordinaryQueriesDoNot() {
        assertFalse(QueryGatewayResource.usesQueryVariables(null));
        assertFalse(QueryGatewayResource.usesQueryVariables(""));
        assertFalse(QueryGatewayResource.usesQueryVariables(
                "jobStatus:^[PENDING,IN_PROGRESS]&&jobStartDate:<2026-11-21T07:00:00Z"));
        assertFalse(QueryGatewayResource.usesQueryVariables("hasEdgeCount:>#3"));
    }
}
