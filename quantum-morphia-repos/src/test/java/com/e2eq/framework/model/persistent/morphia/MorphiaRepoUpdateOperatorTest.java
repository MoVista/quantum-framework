package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.query.updates.UnsetOperator;
import dev.morphia.query.updates.UpdateOperator;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
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

        /**
         * Calls the single validation method that every pair-based update path (including the
         * {@code MorphiaSession} overloads, which have no other guard of their own) routes through,
         * so a test here proves the check applies everywhere, not just to the bulk paths.
         *
         * <p>{@code fieldName} is resolved up the class hierarchy the same way production code does,
         * since some reserved field names (e.g. {@code version}) live on a base class rather than on
         * {@link UpdateTestModel} itself.
         */
        void callValidateUpdatableField(String fieldName, Object value) throws Exception {
            Class<?> current = UpdateTestModel.class;
            Field field = null;
            while (current != null) {
                try {
                    field = current.getDeclaredField(fieldName);
                    break;
                } catch (NoSuchFieldException e) {
                    current = current.getSuperclass();
                }
            }
            if (field == null) {
                throw new NoSuchFieldException(fieldName);
            }
            Method method = MorphiaRepo.class.getDeclaredMethod("validateUpdatableField", Field.class, Pair.class);
            method.setAccessible(true);
            try {
                method.invoke(this, field, Pair.of(fieldName, value));
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException cause) {
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

    @Test
    void reservedField_isRejectedByTheSharedValidationEveryPathUses() {
        // Guards every pair-based update path, including the MorphiaSession overloads that have no
        // reserved-field check of their own.
        assertThrows(IllegalArgumentException.class, () -> repo.callValidateUpdatableField("refName", "PG-1"));
    }

    @Test
    void wrongTypeValue_isRejectedForNonEnumField() {
        // Guards every pair-based update path against a value of the wrong runtime type, including
        // the MorphiaSession overloads, which previously only checked enum fields.
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> repo.callValidateUpdatableField("note", 42));

        assertTrue(
                e.getMessage().contains("Invalid value for field note"),
                "Unexpected message: " + e.getMessage());
    }

    @Test
    void wrongTypeValue_isRejectedForEnumField() {
        // An enum-typed field must reject a non-enum value even when its toString() happens to
        // match a constant's name: the enum-constant check alone is not sufficient type safety.
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> repo.callValidateUpdatableField("requiredSeverity", "HIGH"));

        assertTrue(
                e.getMessage().contains("Invalid value for field requiredSeverity"),
                "Unexpected message: " + e.getMessage());
    }
}
