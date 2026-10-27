package com.e2eq.framework.csv;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        assertEquals("Reference 'Store-1' appears in rows 2, 5 of this file; each reference may appear only once.",
                conflicts.get(2));
        assertSame(conflicts.get(2), conflicts.get(5));
    }

    @Test
    void aGroupOfExactlyTheListLimitListsEveryRow() {
        DuplicateReferenceRows rows = new DuplicateReferenceRows();
        for (int row = DuplicateReferenceRows.MAX_LISTED_ROWS; row >= 1; row--) {
            rows.record(row % 2 == 0 ? "Store-1" : "STORE-1", row);
        }

        Map<Integer, String> conflicts = rows.conflicts();

        assertEquals(10, conflicts.size());
        assertEquals("Reference 'Store-1' appears in rows 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 of this file; "
                + "each reference may appear only once.", conflicts.get(1));
        assertFalse(conflicts.get(1).contains("more"));
    }

    @Test
    void aLargeGroupListsItsFirstRowsAndCountsTheRest() {
        DuplicateReferenceRows rows = new DuplicateReferenceRows();
        // Every row from 2 to 10,001, recorded in a scrambled order with case variants.
        for (int i = 0; i < 10_000; i++) {
            int row = 2 + (int) ((i * 7_919L) % 10_000);
            rows.record(i % 3 == 0 ? "store-1" : i % 3 == 1 ? "Store-1" : "STORE-1", row);
        }

        Map<Integer, String> conflicts = rows.conflicts();

        assertEquals(10_000, conflicts.size());
        String message = conflicts.get(2);
        conflicts.values().forEach(value -> assertSame(message, value));
        assertEquals("Reference 'store-1' appears in 10,000 rows of this file "
                + "(rows 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 and 9,990 more); each reference may appear only once.",
                message);
        assertTrue(message.length() < 300, message);
    }

    @Test
    void eachGroupGetsItsOwnBoundedMessage() {
        DuplicateReferenceRows rows = new DuplicateReferenceRows();
        for (int row = 1; row <= 25; row++) {
            rows.record("Big", row);
        }
        rows.record("small", 26);
        rows.record("SMALL", 30);
        rows.record("unique", 27);

        Map<Integer, String> conflicts = rows.conflicts();

        assertEquals(27, conflicts.size());
        assertEquals("Reference 'Big' appears in 25 rows of this file "
                + "(rows 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 and 15 more); each reference may appear only once.",
                conflicts.get(25));
        assertEquals("Reference 'small' appears in rows 26, 30 of this file; each reference may appear only once.",
                conflicts.get(30));
        assertFalse(conflicts.containsKey(27));
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
    @Test
    void sharpSAndDoubleSAreDistinctAtMongoEnglishSecondaryStrength() {
        DuplicateReferenceRows rows = new DuplicateReferenceRows();
        rows.record("strasse", 1);
        rows.record("straße", 2);
        assertTrue(rows.conflicts().isEmpty());
        rows.record("STRAẞE", 3);
        assertEquals(java.util.Set.of(2, 3), rows.conflicts().keySet());
    }

}
