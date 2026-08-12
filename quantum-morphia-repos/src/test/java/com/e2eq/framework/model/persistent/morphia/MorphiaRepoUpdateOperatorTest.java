package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.query.updates.UnsetOperator;
import dev.morphia.query.updates.UpdateOperator;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the validation and operator construction behind the pair-based update methods on
 * {@link MorphiaRepo}, covering how a null value clears the field it targets.
 *
 * <p>These are plain JUnit tests (no {@code @QuarkusTest}) that drive the operator building
 * directly, so no Mongo or CDI container is required. See {@link MorphiaRepoSortCollationTest} for
 * the same pattern.
 */
class MorphiaRepoUpdateOperatorTest {

    enum Severity {
        LOW,
        HIGH
    }

    /** Minimal concrete entity carrying one optional and one required enum field. */
    public static class UpdateTestModel extends UnversionedBaseModel {
        protected Severity severity;

        @jakarta.validation.constraints.NotNull
        protected Severity requiredSeverity;

        protected String note;

        @Override
        public String bmFunctionalArea() { return "test-area"; }

        @Override
        public String bmFunctionalDomain() { return "test-domain"; }
    }

    /** Test subclass that reaches the private operator building without a datastore or session. */
    static class TestRepo extends MorphiaRepo<UpdateTestModel> {
        @SafeVarargs
        @SuppressWarnings({"unchecked", "varargs"})
        final List<UpdateOperator> callBuildValidatedUpdateOperators(Pair<String, Object>... pairs) throws Exception {
            Method method = MorphiaRepo.class.getDeclaredMethod("buildValidatedUpdateOperators", Pair[].class);
            method.setAccessible(true);
            try {
                return (List<UpdateOperator>) method.invoke(this, (Object) pairs);
            } catch (InvocationTargetException e) {
                // Surface what the method actually threw rather than the reflection wrapper.
                if (e.getCause() instanceof Exception cause) {
                    throw cause;
                }
                throw e;
            }
        }
    }

    private final TestRepo repo = new TestRepo();

    @Test
    void nullValue_clearsOptionalEnumField() throws Exception {
        List<UpdateOperator> operators = repo.callBuildValidatedUpdateOperators(Pair.of("severity", null));

        assertEquals(1, operators.size());
        assertInstanceOf(
                UnsetOperator.class,
                operators.get(0),
                "Null on an optional enum field should clear it rather than being rejected as an invalid constant");
        assertEquals("$unset", operators.get(0).operator());
        // Morphia's $unset carries the field names in the operator's value rather than its field.
        assertEquals(List.of("severity"), operators.get(0).value());
    }

    @Test
    void nullValue_clearsOptionalNonEnumField() throws Exception {
        List<UpdateOperator> operators = repo.callBuildValidatedUpdateOperators(Pair.of("note", null));

        assertEquals("$unset", operators.get(0).operator(), "Clearing should not be limited to enum fields");
    }

    @Test
    void enumValue_isSetWhenItNamesAConstant() throws Exception {
        List<UpdateOperator> operators = repo.callBuildValidatedUpdateOperators(Pair.of("severity", Severity.HIGH));

        assertEquals(1, operators.size());
        assertEquals("$set", operators.get(0).operator());
        assertEquals(Severity.HIGH, operators.get(0).value());
    }

    @Test
    void enumValue_isRejectedWhenItNamesNoConstant() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> repo.callBuildValidatedUpdateOperators(Pair.of("severity", "NOT_A_SEVERITY")));

        assertTrue(
                e.getMessage().contains("Invalid value for enum field severity"),
                "Unexpected message: " + e.getMessage());
    }

    @Test
    void nullValue_isRejectedForRequiredField() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> repo.callBuildValidatedUpdateOperators(Pair.of("requiredSeverity", null)));

        assertTrue(
                e.getMessage().contains("requiredSeverity is not nullable"),
                "A required field must not be clearable. Unexpected message: " + e.getMessage());
    }

    @Test
    void mixedPairs_produceOneOperatorEachInOrder() throws Exception {
        List<UpdateOperator> operators = repo.callBuildValidatedUpdateOperators(
                Pair.of("requiredSeverity", Severity.LOW),
                Pair.of("severity", null));

        assertEquals(2, operators.size());
        assertEquals("$set", operators.get(0).operator());
        assertEquals("$unset", operators.get(1).operator());
    }

    @Test
    void reservedField_isStillRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> repo.callBuildValidatedUpdateOperators(Pair.of("refName", null)));
    }
}
