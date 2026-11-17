package com.e2eq.framework.csv;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FieldErrorJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aKeyedErrorRoundTripsWithItsKeyAndParams() throws Exception {
        CSVImportHelper.FieldError error = new CSVImportHelper.FieldError(
                "displayName", "Location Name is required.", CSVImportHelper.FieldErrorCode.VALIDATION,
                "locationImport.required", Map.of("field", "displayName"));

        CSVImportHelper.FieldError read = mapper.readValue(
                mapper.writeValueAsString(error), CSVImportHelper.FieldError.class);

        assertEquals("displayName", read.getField());
        assertEquals("Location Name is required.", read.getMessage());
        assertEquals(CSVImportHelper.FieldErrorCode.VALIDATION, read.getCode());
        assertEquals("locationImport.required", read.getMessageKey());
        assertEquals(Map.of("field", "displayName"), read.getParams());
    }

    @Test
    void anErrorWithoutAKeySerializesAsBefore() throws Exception {
        CSVImportHelper.FieldError error = new CSVImportHelper.FieldError(
                null, "'north' is not a number", CSVImportHelper.FieldErrorCode.PARSE);

        assertEquals("{\"field\":null,\"message\":\"'north' is not a number\",\"code\":\"PARSE\"}",
                mapper.writeValueAsString(error));
    }

    @Test
    void emptyParamsAreLeftOut() throws Exception {
        CSVImportHelper.FieldError error = new CSVImportHelper.FieldError(
                "activeStatus", "Status must be Active or Inactive.", CSVImportHelper.FieldErrorCode.VALIDATION,
                "locationImport.statusInvalid", Map.of());

        assertEquals("{\"field\":\"activeStatus\",\"message\":\"Status must be Active or Inactive.\","
                        + "\"code\":\"VALIDATION\",\"messageKey\":\"locationImport.statusInvalid\"}",
                mapper.writeValueAsString(error));
    }
}
