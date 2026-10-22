package com.e2eq.framework.csv;

import com.e2eq.framework.model.persistent.morphia.RefNameMatching;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tracks which rows of one imported file carry each reference, compared like the
 * reference-identity unique index ({@link RefNameMatching}). A reference may appear only once per
 * file: every row of a group that shares one is rejected, rather than letting the first or last
 * row win.
 */
final class DuplicateReferenceRows {

    private final Map<String, List<Integer>> rowsByReference = RefNameMatching.newMap();

    /**
     * Records that a row carries a reference. Rows without a reference are ignored.
     *
     * @param refName the row's reference, may be null or blank
     * @param rowNumber the row's 1-based data row number
     */
    void record(String refName, int rowNumber) {
        if (refName == null || refName.isBlank()) {
            return;
        }
        rowsByReference.computeIfAbsent(refName, key -> new ArrayList<>()).add(rowNumber);
    }

    /**
     * Returns an error message for every row whose reference appears in more than one row.
     *
     * @return row number to error message, in ascending row order within each group
     */
    Map<Integer, String> conflicts() {
        Map<Integer, String> conflicts = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> group : rowsByReference.entrySet()) {
            List<Integer> rows = group.getValue();
            if (rows.size() < 2) {
                continue;
            }
            String message = String.format(
                    "Reference '%s' appears in rows %s of this file; each reference may appear only once.",
                    group.getKey(),
                    rows.stream().sorted().map(String::valueOf).collect(Collectors.joining(", ")));
            rows.forEach(row -> conflicts.put(row, message));
        }
        return conflicts;
    }
}
