package com.e2eq.framework.model.validators;

import com.e2eq.framework.model.persistent.base.MailingAddress;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailingAddressValidatorTest {

    private static Validator jakartaValidator;

    @BeforeAll
    static void setupJakartaValidator() {
        jakartaValidator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void defaultListAcceptsExistingStateAndTerritoryCodes() {
        for (String code : List.of("CA", "TX", "DC", "PR", "VI", "GU")) {
            assertFalse(hasStateCodeViolation(jakartaValidator.validate(usAddress(code))),
                    "Expected built-in code to remain valid: " + code);
        }
    }

    @Test
    void defaultListRejectsUnsupportedCode() {
        assertTrue(hasStateCodeViolation(jakartaValidator.validate(usAddress("ZZ"))));
    }

    @Test
    void defaultListDoesNotAcceptMilitaryPostalCodes() {
        for (String code : List.of("AA", "AE", "AP")) {
            assertTrue(hasStateCodeViolation(jakartaValidator.validate(usAddress(code))),
                    "Expected " + code + " to stay invalid until a consumer registers it");
        }
    }

    @Test
    void extensionAcceptsMilitaryPostalCodes() {
        MailingAddressValidator validator = validatorWithAdditionalCodes("AA", "AE", "AP");
        assertTrue(validator.isValidUsStateCode("AA"));
        assertTrue(validator.isValidUsStateCode("AE"));
        assertTrue(validator.isValidUsStateCode("AP"));
    }

    @Test
    void extensionStillRejectsUnsupportedCode() {
        assertFalse(validatorWithAdditionalCodes("AA", "AE", "AP").isValidUsStateCode("ZZ"));
    }

    @Test
    void builtInCodesRemainValidWhenExtensionAddsMilitaryCodes() {
        MailingAddressValidator validator = validatorWithAdditionalCodes("AA", "AE", "AP");
        assertTrue(validator.isValidUsStateCode("CA"));
        assertTrue(validator.isValidUsStateCode("DC"));
        assertTrue(validator.isValidUsStateCode("PR"));
    }

    private static MailingAddressValidator validatorWithAdditionalCodes(String... codes) {
        MailingAddressValidator validator = new MailingAddressValidator();
        validator.additionalUsStateCodes = Set.of(codes);
        return validator;
    }

    private static MailingAddress usAddress(String stateTwoLetterCode) {
        return MailingAddress.builder()
                .addressLine1("123 Main St")
                .city("Anytown")
                .stateTwoLetterCode(stateTwoLetterCode)
                .zip5("90210")
                .countryTwoLetterCode("US")
                .build();
    }

    private static boolean hasStateCodeViolation(Set<ConstraintViolation<MailingAddress>> violations) {
        return violations.stream().anyMatch(v ->
                v.getMessage() != null && v.getMessage().contains("State two letter code is not a valid US state"));
    }
}
