package com.e2eq.framework.rest.resources;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.general.FeatureFlag;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.FeatureFlagRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.rest.models.AuthRequest;
import com.e2eq.framework.security.runtime.SecuritySession;
import com.e2eq.framework.util.EncryptionUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MOV-12871: DELETE with X-Realm must look up (key, realm), matching MorphiaRepo.
 */
@QuarkusTest
public class BaseResourceXRealmDeleteIT extends BaseRepoTest {

    private static final String FLAGS = "/features/flags";

    @Inject
    CredentialRepo credRepo;

    @Inject
    FeatureFlagRepo featureFlagRepo;

    @Inject
    AuthProviderFactory authProviderFactory;

    @Inject
    ObjectMapper mapper;

    private final java.util.List<String> createdRefNames = new java.util.ArrayList<>();

    private String accessToken;

    @BeforeEach
    void setUp() throws Exception {
        try (SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            ensureAdminUserWithRealmOverride();
            accessToken = loginAndGetToken();
        }
    }

    @AfterEach
    void tearDown() {
        try (SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            deleteCreatedFlags();
        } catch (Exception e) {
            // Cleanup must not hide the assertion that failed.
        }
    }

    @Test
    @DisplayName("DELETE /id/{id} with X-Realm returns 200 and removes the entity")
    void deleteByIdPath_withXRealm_returns200AndRemovesTheEntity() throws Exception {
        String refName = uniqueRef();
        String id = postFlag(refName, testUtils.getTestRealm());

        Response deleted = deletePath("id/" + id, testUtils.getTestRealm());

        assertEquals(200, deleted.statusCode(), deleted.asString());
        assertEquals("Delete successful", deleted.jsonPath().getString("message"));
        assertEquals(404, getPath("id/" + id, testUtils.getTestRealm()).statusCode());
    }

    @Test
    @DisplayName("DELETE /refName/{refName} with X-Realm returns 200 and removes the entity")
    void deleteByRefNamePath_withXRealm_returns200AndRemovesTheEntity() throws Exception {
        String refName = uniqueRef();
        postFlag(refName, testUtils.getTestRealm());

        Response deleted = deletePath("refName/" + refName, testUtils.getTestRealm());

        assertEquals(200, deleted.statusCode(), deleted.asString());
        assertEquals("Delete successful", deleted.jsonPath().getString("message"));
        assertEquals(404, getPath("refName/" + refName, testUtils.getTestRealm()).statusCode());
    }

    @Test
    @DisplayName("DELETE /id?id= with X-Realm returns 200 and removes the entity")
    void deleteByIdQuery_withXRealm_returns200AndRemovesTheEntity() throws Exception {
        String refName = uniqueRef();
        String id = postFlag(refName, testUtils.getTestRealm());

        Response deleted = given()
                .header("Authorization", "Bearer " + accessToken)
                .header("X-Realm", testUtils.getTestRealm())
                .queryParam("id", id)
                .when()
                .delete(FLAGS + "/id")
                .then()
                .extract()
                .response();

        assertEquals(200, deleted.statusCode(), deleted.asString());
        assertEquals(404, getPath("id/" + id, testUtils.getTestRealm()).statusCode());
    }

    @Test
    @DisplayName("DELETE /refName?refName= with X-Realm returns 200 and removes the entity")
    void deleteByRefNameQuery_withXRealm_returns200AndRemovesTheEntity() throws Exception {
        String refName = uniqueRef();
        postFlag(refName, testUtils.getTestRealm());

        Response deleted = given()
                .header("Authorization", "Bearer " + accessToken)
                .header("X-Realm", testUtils.getTestRealm())
                .queryParam("refName", refName)
                .when()
                .delete(FLAGS + "/refName")
                .then()
                .extract()
                .response();

        assertEquals(200, deleted.statusCode(), deleted.asString());
        assertEquals(404, getPath("refName/" + refName, testUtils.getTestRealm()).statusCode());
    }

    @Test
    @DisplayName("DELETE /id/{id} without X-Realm still returns 200")
    void deleteByIdPath_withoutXRealm_returns200() throws Exception {
        String refName = uniqueRef();
        String id = postFlag(refName, null);

        Response deleted = given()
                .header("Authorization", "Bearer " + accessToken)
                .when()
                .delete(FLAGS + "/id/" + id)
                .then()
                .extract()
                .response();

        assertEquals(200, deleted.statusCode(), deleted.asString());
        assertEquals(404, getPath("id/" + id, null).statusCode());
    }

    @Test
    @DisplayName("DELETE /id/{id} with X-Realm for an unknown id returns 404")
    void deleteByIdPath_withXRealm_unknownId_returns404() {
        String missingId = new org.bson.types.ObjectId().toHexString();

        Response deleted = deletePath("id/" + missingId, testUtils.getTestRealm());

        assertEquals(404, deleted.statusCode(), deleted.asString());
    }

    @Test
    @DisplayName("DELETE /id/{id} with X-Realm of a different realm returns 404 and leaves the entity")
    void deleteByIdPath_withXRealm_forADifferentRealm_returns404AndLeavesTheEntity() throws Exception {
        String refName = uniqueRef();
        String id = postFlag(refName, testUtils.getTestRealm());

        Response deleted = deletePath("id/" + id, testUtils.getSystemRealm());

        assertEquals(404, deleted.statusCode(), deleted.asString());
        assertEquals(200, getPath("id/" + id, testUtils.getTestRealm()).statusCode());
    }

    private String uniqueRef() {
        return "xrealm-del-" + UUID.randomUUID();
    }

    private String postFlag(String refName, String realm) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("refName", refName);
        body.put("displayName", refName);
        body.put("enabled", true);
        body.put("type", "BOOLEAN");
        body.put("dataDomain", testDataDomainBody());

        io.restassured.specification.RequestSpecification request = given()
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .body(mapper.writeValueAsString(body));
        if (realm != null) {
            request = request.header("X-Realm", realm);
        }
        Response created = request.when().post(FLAGS).then().extract().response();
        assertEquals(200, created.statusCode(), created.asString());
        String id = created.jsonPath().getString("id");
        assertNotNull(id, created.asString());
        createdRefNames.add(refName);
        return id;
    }

    private Map<String, Object> testDataDomainBody() {
        Map<String, Object> dataDomain = new LinkedHashMap<>();
        dataDomain.put("orgRefName", testUtils.getTestOrgRefName());
        dataDomain.put("accountNum", testUtils.getTestAccountNumber());
        dataDomain.put("tenantId", testUtils.getTestTenantId());
        dataDomain.put("dataSegment", 0);
        dataDomain.put("ownerId", testUtils.getTestUserId());
        return dataDomain;
    }

    private Response deletePath(String suffix, String realm) {
        return given()
                .header("Authorization", "Bearer " + accessToken)
                .header("X-Realm", realm)
                .when()
                .delete(FLAGS + "/" + suffix)
                .then()
                .extract()
                .response();
    }

    private Response getPath(String suffix, String realm) {
        io.restassured.specification.RequestSpecification request = given()
                .header("Authorization", "Bearer " + accessToken);
        if (realm != null) {
            request = request.header("X-Realm", realm);
        }
        return request.when().get(FLAGS + "/" + suffix).then().extract().response();
    }

    private String loginAndGetToken() throws Exception {
        AuthRequest request = new AuthRequest();
        request.setUserId(testUtils.getTestUserId());
        request.setPassword(testUtils.getDefaultTestPassword());
        Response response = given()
                .header("Content-type", "application/json")
                .body(mapper.writeValueAsString(request))
                .when()
                .post("/security/login")
                .then()
                .statusCode(200)
                .extract()
                .response();
        return response.jsonPath().getString("access_token");
    }

    /**
     * Same setup as XRealmUserProvisioningIT: the test user must be an admin
     * whose realmRegEx is "*" or SecurityFilter rejects X-Realm.
     */
    private void ensureAdminUserWithRealmOverride() throws ReferentialIntegrityViolationException {
        Optional<CredentialUserIdPassword> credop = credRepo.findByUserId(
                testUtils.getTestUserId(), testUtils.getSystemRealm());
        CredentialUserIdPassword cred;
        if (credop.isPresent()) {
            cred = credop.get();
            boolean needsUpdate = false;
            if (cred.getRealmRegEx() == null || !cred.getRealmRegEx().equals("*")) {
                cred.setRealmRegEx("*");
                needsUpdate = true;
            }
            String[] currentRoles = cred.getRoles();
            boolean hasAdmin = false;
            if (currentRoles != null) {
                for (String role : currentRoles) {
                    if ("admin".equals(role)) {
                        hasAdmin = true;
                        break;
                    }
                }
            }
            if (!hasAdmin) {
                cred.setRoles(new String[] {"admin", "user"});
                needsUpdate = true;
            }
            if (needsUpdate) {
                credRepo.save(testUtils.getSystemRealm(), cred);
            }
            return;
        }
        cred = new CredentialUserIdPassword();
        cred.setUserId(testUtils.getTestUserId());
        cred.setPasswordHash(EncryptionUtils.hashPassword(testUtils.getDefaultTestPassword()));
        Optional<String> osubjectId = authProviderFactory.getUserManager()
                .getSubjectForUserId(testUtils.getTestUserId());
        cred.setSubject(osubjectId.orElse(testUtils.getTestUserId()));
        DataDomain dataDomain = new DataDomain();
        dataDomain.setOrgRefName(testUtils.getTestOrgRefName());
        dataDomain.setAccountNum(testUtils.getTestAccountNumber());
        dataDomain.setTenantId(testUtils.getTestTenantId());
        dataDomain.setOwnerId(testUtils.getTestUserId());
        cred.setRoles(new String[] {"admin", "user"});
        cred.setRefName(cred.getUserId());
        cred.setDomainContext(new DomainContext(dataDomain, testUtils.getTestRealm()));
        cred.setLastUpdate(new Date());
        cred.setDataDomain(dataDomain);
        cred.setRealmRegEx("*");
        credRepo.save(testUtils.getSystemRealm(), cred);
    }

    private void deleteCreatedFlags() throws ReferentialIntegrityViolationException {
        for (String refName : createdRefNames) {
            for (String realm : new String[] {testUtils.getTestRealm(), testUtils.getSystemRealm()}) {
                Optional<FeatureFlag> found = featureFlagRepo.findByRefName(refName, realm);
                if (found.isPresent()) {
                    featureFlagRepo.delete(realm, found.get());
                }
            }
        }
        createdRefNames.clear();
    }
}
