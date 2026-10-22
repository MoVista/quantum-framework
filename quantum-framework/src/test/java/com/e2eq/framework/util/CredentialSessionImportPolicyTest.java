package com.e2eq.framework.csv;

import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CredentialSessionImportPolicyTest {
    @Test
    void sharedHelperRejectsCredentialSessionsBeforeParsingOrLoadingState() {
        var helper = new CSVImportHelper();
        var repo = new CredentialRepo();
        assertEquals(CSVImportHelper.CREDENTIAL_DIRECT_IMPORT_REQUIRED, assertThrows(BadRequestException.class,
                () -> helper.analyzeCSV(repo, null, ',', '"', true, List.of("refName"),
                        StandardCharsets.UTF_8, false, "QUOTE_WHERE_ESSENTIAL")).getMessage());
        assertThrows(BadRequestException.class,
                () -> helper.analyzeCSVWithProfile(repo, null, null, List.of("refName"), "tenant-com"));
        assertThrows(BadRequestException.class, () -> helper.commitImport("already-staged", repo));
    }
}
