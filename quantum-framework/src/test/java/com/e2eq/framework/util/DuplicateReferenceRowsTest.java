package com.e2eq.framework.csv;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicateReferenceRowsTest {

    @Test
    void reportsEveryRowOfAGroupComparedCaseInsensitively() {
        DuplicateReferenceRows rows = new DuplicateReferenceRows();
        rows.record("Store-1", 2);
        rows.record("store-2", 3);
        rows.record("STORE-1", 5);
        rows.record(null, 6);
        rows.record(" ", 7);

        Map<Integer, String> conflicts = rows.conflicts();

        assertEquals(2, conflicts.size());
        assertTrue(conflicts.get(2).contains("rows 2, 5"));
        assertEquals(conflicts.get(2), conflicts.get(5));
    }

    @Test
    void accentsAreDistinctReferences() {
        DuplicateReferenceRows rows = new DuplicateReferenceRows();
        rows.record("cafe", 1);
        rows.record("café", 2);

        assertTrue(rows.conflicts().isEmpty());
    }

    @Test
    void rejectRepeatedReferencesAddsARowErrorToEveryRowOfAGroup() {
        java.util.List<CSVImportHelper.ImportRowResult<Object>> rows = new java.util.ArrayList<>();
        for (String refName : java.util.List.of("Store-1", "store-2", "STORE-1")) {
            CSVImportHelper.ImportRowResult<Object> row = new CSVImportHelper.ImportRowResult<>();
            row.setRowNumber(rows.size() + 1);
            row.setRefName(refName);
            rows.add(row);
        }

        int rejected = new CSVImportHelper().rejectRepeatedReferences(rows);

        assertEquals(2, rejected);
        assertTrue(rows.get(0).hasErrors());
        assertTrue(rows.get(0).getErrors().get(0).getMessage().contains("rows 1, 3"));
        assertTrue(rows.get(1).getErrors().isEmpty());
        assertTrue(rows.get(2).hasErrors());
    }
}
