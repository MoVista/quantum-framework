package com.e2eq.framework.model.validators;

import java.util.Set;

/** Optional extra US postal codes from a consuming application. */
public interface MailingAddressValidationExtension {
    Set<String> additionalUsStateCodes();
}

