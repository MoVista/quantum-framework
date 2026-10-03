package com.e2eq.framework.model.persistent.morphia;

import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Morphia 3 merges varargs filters into one document. Two {@code $in} on {@code refName}
 * (security row-filter plus {@code getListFromRefNames}) nested {@code $and} under that field
 * and MongoDB returned {@code unknown operator: $and}. Combining into a single top-level
 * {@code $and} keeps each clause as its own document.
 */
class MorphiaRepoQueryCombineTest {

    @Test
    void empty_staysEmpty() {
        assertEquals(0, MorphiaRepo.combineForMorphiaQuery(new Filter[0]).length);
        assertEquals(0, MorphiaRepo.combineForMorphiaQuery((List<Filter>) null).length);
    }

    @Test
    void singleFilter_isUnchanged() {
        Filter in = Filters.in("refName", List.of("loc-a"));
        Filter[] combined = MorphiaRepo.combineForMorphiaQuery(new Filter[] {in});
        assertEquals(1, combined.length);
        assertSame(in, combined[0]);
    }

    @Test
    void twoInOnSameField_collapseToTopLevelAnd() {
        Filter security = Filters.in("refName", List.of("loc-a", "loc-b"));
        Filter lookup = Filters.in("refName", List.of("loc-a"));
        Filter[] combined = MorphiaRepo.combineForMorphiaQuery(new Filter[] {security, lookup});
        assertEquals(1, combined.length);
        assertEquals("$and", combined[0].getName());
    }
}
