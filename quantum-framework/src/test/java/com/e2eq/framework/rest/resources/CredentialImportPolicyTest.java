package com.e2eq.framework.rest.resources;

import com.e2eq.framework.csv.CSVImportHelper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestSecurity(user = "system@system.com", roles = {"admin", "system"})
public class CredentialImportPolicyTest {
    private static final String ROOT = "/user/credentials/csv";
    private static final String TYPE = "com.e2eq.framework.model.security.CredentialUserIdPassword";

    private RequestSpecification csvUpload() {
        return given().multiPart("file", "credentials.csv", "refName\n".getBytes(StandardCharsets.UTF_8), "text/csv")
                .queryParam("requestedColumns", "refName");
    }

    @Test
    void rejectsPlainCredentialSessionPreview() {
        csvUpload().post(ROOT + "/session").then().statusCode(400)
                .body("statusMessage", equalTo(CSVImportHelper.CREDENTIAL_DIRECT_IMPORT_REQUIRED));
    }

    @Test
    void rejectsProfileCredentialSessionPreviewBeforeLookingUpTheProfile() {
        csvUpload().queryParam("profileRefName", "missing-profile").post(ROOT + "/session/profile")
                .then().statusCode(400)
                .body("statusMessage", equalTo(CSVImportHelper.CREDENTIAL_DIRECT_IMPORT_REQUIRED));
    }

    @Test
    void rejectsCredentialSessionCommitsBeforeLoadingTheSession() {
        given().post(ROOT + "/session/previously-staged/commit").then().statusCode(400)
                .body("statusMessage", equalTo(CSVImportHelper.CREDENTIAL_DIRECT_IMPORT_REQUIRED));
    }

    @Test
    void directCredentialImportRemainsAvailable() {
        csvUpload().post(ROOT).then().statusCode(200).body("totalRows", equalTo(0));
    }

    @Test
    void queryGatewayCannotCreateOrCommitCredentialSessions() {
        given().contentType("application/json").body(Map.of("rootType", TYPE,
                        "csvContent", "refName\n", "columns", List.of("refName")))
                .post("/api/query/import/analyze").then().statusCode(400)
                .body(containsString(CSVImportHelper.CREDENTIAL_DIRECT_IMPORT_REQUIRED));
        given().contentType("application/json").body(Map.of("rootType", TYPE, "sessionId", "previously-staged"))
                .post("/api/query/import/commit").then().statusCode(400)
                .body(containsString(CSVImportHelper.CREDENTIAL_DIRECT_IMPORT_REQUIRED));
    }

    @Test
    void openApiMarksCredentialSessionEndpointsDisabledWithoutSuccessResponses() {
        Map<String, Object> paths = given().accept("application/json").get("/q/openapi").then().statusCode(200)
                .extract().jsonPath().getMap("paths");
        for (String path : List.of(ROOT + "/session", ROOT + "/session/profile", ROOT + "/session/{sessionId}/commit")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> operation = (Map<String, Object>) ((Map<?, ?>) paths.get(path)).get("post");
            assertTrue(operation.get("summary").toString().startsWith("Disabled:"), path);
            assertEquals(CSVImportHelper.CREDENTIAL_DIRECT_IMPORT_REQUIRED, operation.get("description"));
            Map<?, ?> responses = (Map<?, ?>) operation.get("responses");
            assertTrue(responses.containsKey("400"));
            assertFalse(responses.keySet().stream().map(Object::toString).anyMatch(code -> code.startsWith("2")), path);
        }
        assertFalse(((Map<?, ?>) ((Map<?, ?>) paths.get(ROOT)).get("post")).get("summary").toString().startsWith("Disabled:"));
    }
}
