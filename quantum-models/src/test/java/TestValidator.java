import com.e2eq.framework.model.persistent.base.MailingAddress;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Set;


@QuarkusTest
public class TestValidator {
    @Inject
    Validator validator;
    @Test
    public void test() {
        //Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        MailingAddress ma = MailingAddress.builder()
                .addressName("John Doe")
                .addressLine1("123 Main St")
                .city("Anytown")
                .stateTwoLetterCode("CA")
                .zip("90210")
                .countryTwoLetterCode("US")
                .build();

        Set<ConstraintViolation<MailingAddress>> x =  validator.validate(ma);
        // printout the violations
        x.forEach(v -> System.out.println(v.getMessage()));
        Assertions.assertEquals(0, x.size());
        System.out.println("--");

        ma.setZip(null);
        x =  validator.validate(ma);
        x.forEach(v -> System.out.println(v.getMessage()));
        Assertions.assertEquals(2, x.size());
        System.out.println("--");

        ma.setZip5("123456");
        x =  validator.validate(ma);
        x.forEach(v -> System.out.println(v.getMessage()));
        Assertions.assertEquals(2, x.size());
        System.out.println("--");

        ma.setZip5("30022");
        x =  validator.validate(ma);
        // printout the violations
        x.forEach(v -> System.out.println(v.getMessage()));
        Assertions.assertEquals(0, x.size());
        System.out.println("--");

        ma.setStateTwoLetterCode(null);
        x =  validator.validate(ma);
        // printout the violations
        x.forEach(v -> System.out.println(v.getMessage()));
        Assertions.assertEquals(2, x.size());
        System.out.println("--");

        ma.setStateTwoLetterCode("CA");
        ma.setState("California");
        x =  validator.validate(ma);
        x.forEach(v -> System.out.println(v.getMessage()));
        Assertions.assertEquals(2, x.size());
        System.out.println("--");
        ma.setState(null);


        ma.setCountryTwoLetterCode("CN");
        ma.setZip5(null);
        ma.setPostalCode("123456--sdf");

        x =  validator.validate(ma);
        // printout the violations
        x.forEach(v -> System.out.println(v.getMessage()));
        Assertions.assertEquals(0, x.size());
        System.out.println("--");

    }

}
