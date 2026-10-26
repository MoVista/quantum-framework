package com.e2eq.framework.csv;

import com.e2eq.framework.model.persistent.morphia.RefNameMatching;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tracks which rows of one imported file carry each reference, compared like the
 * reference-identity unique index ({@link RefNameMatching}). A reference may appear only once per
 * file: every row of a group that shares one is rejected, rather than letting the first or last
 * row win.
 */
final class DuplicateReferenceRows {

    /** Row numbers listed in one message; a larger group's message gives the rest as a count. */
    static final int MAX_LISTED_ROWS = 10;

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
     * Returns an error message for every row whose reference appears in more than one row. Each
     * group's message lists at most {@link #MAX_LISTED_ROWS} row numbers, so its size does not grow
     * with the group, and every row of the group shares the same message instance.
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
            String message = message(group.getKey(), rows);
            rows.forEach(row -> conflicts.put(row, message));
        }
        return conflicts;
    }

    private static String message(String refName, List<Integer> rows) {
        String listed = rows.stream().sorted().limit(MAX_LISTED_ROWS).map(String::valueOf)
                .collect(Collectors.joining(", "));
        if (rows.size() <= MAX_LISTED_ROWS) {
            return String.format(
                    "Reference '%s' appears in rows %s of this file; each reference may appear only once.",
                    refName, listed);
        }
        return String.format(Locale.ROOT,
                "Reference '%s' appears in %,d rows of this file (rows %s and %,d more); "
                        + "each reference may appear only once.",
                refName, rows.size(), listed, rows.size() - MAX_LISTED_ROWS);
    }
}
