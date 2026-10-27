package annotations;

import com.e2eq.framework.annotations.ValidQueryFilter;
import com.e2eq.framework.model.persistent.base.BaseModel;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ValidQueryFilterTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setup() {
        // quantum-framework supplies the validator through its service registration.
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    static class TestModel extends BaseModel {
        private String name;
        private String email;
        private Integer age;

       @Override
       public String bmFunctionalArea () {
          return "TST";
       }

       @Override
       public String bmFunctionalDomain () {
          return "TST";
       }
    }

    static class TestDTO {
        @ValidQueryFilter(modelClass = TestModel.class)
        private String filterQuery;

        public TestDTO(String filterQuery) {
            this.filterQuery = filterQuery;
        }
    }

    @Test
    void testValidQueryPasses() {
        TestDTO dto = new TestDTO("name:John&&email:test@example.com");
        Set<ConstraintViolation<TestDTO>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty());
    }

    @Test
    void testInvalidFieldFails() {
        TestDTO dto = new TestDTO("name:John&&invalidField:test");
        Set<ConstraintViolation<TestDTO>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream()
            .anyMatch(v -> v.getMessage().contains("invalidField")));
    }

    @Test
    void testNullQueryPasses() {
        TestDTO dto = new TestDTO(null);
        Set<ConstraintViolation<TestDTO>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty());
    }

    @Test
    void testEmptyQueryPasses() {
        TestDTO dto = new TestDTO("");
        Set<ConstraintViolation<TestDTO>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty());
    }
}
