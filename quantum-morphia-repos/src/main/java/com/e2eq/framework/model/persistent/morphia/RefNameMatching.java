package com.e2eq.framework.model.persistent.morphia;

import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CollationStrength;

import com.ibm.icu.text.Collator;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * How references ({@code refName}) are compared, in MongoDB and in application code.
 *
 * <p>The reference-identity unique index ({@code idx_refNameTenant_unique} on
 * {@code UnversionedBaseModel}) uses English strength-2 collation: case-insensitive and
 * accent-sensitive. Reference lookups that decide whether a reference already exists must run
 * with the same collation, otherwise a lookup for {@code abc} misses an existing {@code ABC} and
 * the following insert fails with a duplicate-key error. Code that matches lookup results back to
 * requested references must compare the same way, which {@link #comparator()} and
 * {@link #newMap()} provide.</p>
 */
public final class RefNameMatching {

    /** Collation of the reference-identity unique index: English, strength 2 (secondary). */
    public static final Collation COLLATION = Collation.builder()
            .locale("en")
            .collationStrength(CollationStrength.SECONDARY)
            .build();

    private RefNameMatching() {
    }

    /**
     * Returns a comparator using ICU English secondary comparison, as in {@link #COLLATION}: references that differ only in case
     * compare as equal; references that differ in accents do not. A new instance is returned on
     * every call because {@link Collator} is not thread-safe.
     *
     * @return a comparator for references
     */
    public static Comparator<String> comparator() {
        Collator collator = Collator.getInstance(Locale.ENGLISH);
        collator.setStrength(Collator.SECONDARY);
        return collator::compare;
    }

    /**
     * Returns whether two references identify the same document under {@link #COLLATION}.
     *
     * @param a a reference, may be null
     * @param b another reference, may be null
     * @return true if both are null, or both are non-null and compare as equal
     */
    public static boolean sameReference(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        return comparator().compare(a, b) == 0;
    }

    /**
     * Creates an empty map keyed by reference, whose keys compare like {@link #COLLATION}, so a
     * value stored under {@code ABC} is found by {@code abc}. The map does not accept null keys.
     *
     * @param <V> value type
     * @return a new, empty, mutable map
     */
    public static <V> Map<String, V> newMap() {
        return new TreeMap<>(comparator());
    }
}
