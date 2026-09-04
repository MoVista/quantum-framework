package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.util.EnvConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Container-free unit tests for GET /security/me under impersonation.
 * Drives {@link SecurityResource#me} with stubbed repos so the 404 and
 * PrincipalContext-roles paths are covered without Mongo.
 */
public class SecurityResourceMeImpersonationTest {

    private static final String SYSTEM_REALM = "system-com";
    private static final String TENANT_REALM = "tenant-com";
    private static final String TARGET_USER = "target@example.com";
    private static final String TARGET_SUBJECT = "target-subject";

    @AfterEach
    public void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    public void meReturns404WhenImpersonatedCredentialIsMissing() throws Exception {
        StubCredentialRepo credentials = new StubCredentialRepo();
        RecordingUserProfileRepo profiles = new RecordingUserProfileRepo();
        SecurityResource resource = newResource(credentials, profiles);

        setImpersonatedPrincipal(TARGET_USER, TENANT_REALM, new String[]{"pcOnlyRole"});

        Response response = resource.me(jaxRsContext("operator-subject"));

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
        assertInstanceOf(RestError.class, response.getEntity());
        RestError error = (RestError) response.getEntity();
        assertTrue(error.getStatusMessage().contains(TARGET_USER),
                "404 message should identify the missing impersonated userId: " + error.getStatusMessage());
    }

    @Test
    public void meUsesPrincipalContextRolesAndImpersonatedRealm() throws Exception {
        DomainContext domainContext = DomainContext.builder()
                .tenantId("t1")
                .defaultRealm(TENANT_REALM)
                .orgRefName("org1")
                .accountId("acct1")
                .build();

        CredentialUserIdPassword cred = CredentialUserIdPassword.builder()
                .userId(TARGET_USER)
                .subject(TARGET_SUBJECT)
                .domainContext(domainContext)
                .lastUpdate(new Date())
                .roles(new String[]{"credRole"})
                .build();

        UserProfile tenantProfile = UserProfile.builder()
                .userId(TARGET_USER)
                .email(TARGET_USER)
                .fname("Tenant")
                .credentialUserIdPasswordRef(EntityReference.builder()
                        .entityRefName(TARGET_SUBJECT)
                        .entityDisplayName(TARGET_USER)
                        .build())
                .build();
        UserProfile systemProfile = UserProfile.builder()
                .userId(TARGET_USER)
                .email(TARGET_USER)
                .fname("System")
                .credentialUserIdPasswordRef(EntityReference.builder()
                        .entityRefName(TARGET_SUBJECT)
                        .entityDisplayName(TARGET_USER)
                        .build())
                .build();

        StubCredentialRepo credentials = new StubCredentialRepo();
        credentials.putByUserId(TARGET_USER, cred);

        RecordingUserProfileRepo profiles = new RecordingUserProfileRepo();
        profiles.putByUserId(TENANT_REALM, TARGET_USER, tenantProfile);
        profiles.putByUserId(SYSTEM_REALM, TARGET_USER, systemProfile);

        SecurityResource resource = newResource(credentials, profiles);
        setImpersonatedPrincipal(TARGET_USER, TENANT_REALM, new String[]{"pcOnlyRole", "user"});

        Response response = resource.me(jaxRsContext("operator-subject"));

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus(), String.valueOf(response.getEntity()));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();

        assertEquals(TARGET_USER, body.get("userId"));
        assertEquals("Tenant", body.get("fname"),
                "UserProfile must be loaded from the impersonated realm, not the system realm");

        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) body.get("roles");
        assertNotNull(roles, "/me must include PrincipalContext effective roles");
        assertTrue(roles.contains("pcOnlyRole"),
                "roles must include a PrincipalContext-only role, not just credential.roles: " + roles);
        assertFalse(roles.contains("credRole"),
                "credential document roles must not win over PrincipalContext roles: " + roles);

        assertFalse(profiles.userIdLookups.isEmpty(), "UserProfile lookup should run");
        assertEquals(TENANT_REALM, profiles.userIdLookups.get(0).realm,
                "first UserProfile lookup must use the impersonated realm, not the system realm");
    }

    private static SecurityResource newResource(StubCredentialRepo credentials, RecordingUserProfileRepo profiles)
            throws Exception {
        SecurityResource resource = new SecurityResource();
        EnvConfigUtils env = new EnvConfigUtils();
        env.setSystemRealm(SYSTEM_REALM);

        setField(resource, "credentialRepo", credentials);
        setField(resource, "userProfileRepo", profiles);
        setField(resource, "realmRepo", new StubRealmRepo());
        setField(resource, "objectMapper", new ObjectMapper());
        setField(resource, "envConfigUtils", env);
        return resource;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = SecurityResource.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void setImpersonatedPrincipal(String userId, String realm, String[] roles) {
        DataDomain dataDomain = DataDomain.builder()
                .orgRefName("org1")
                .accountNum("acct1")
                .tenantId("t1")
                .dataSegment(0)
                .ownerId(userId)
                .build();
        PrincipalContext pc = new PrincipalContext.Builder()
                .withUserId(userId)
                .withDefaultRealm(realm)
                .withDataDomain(dataDomain)
                .withRoles(roles)
                .withScope("AUTHENTICATED")
                .withImpersonatedByUserId("admin@example.com")
                .withImpersonatedBySubject("admin-subject")
                .build();
        SecurityContext.setPrincipalContext(pc);
    }

    private static jakarta.ws.rs.core.SecurityContext jaxRsContext(String principalName) {
        return new jakarta.ws.rs.core.SecurityContext() {
            @Override
            public Principal getUserPrincipal() {
                return () -> principalName;
            }

            @Override
            public boolean isUserInRole(String role) {
                return false;
            }

            @Override
            public boolean isSecure() {
                return false;
            }

            @Override
            public String getAuthenticationScheme() {
                return "Bearer";
            }
        };
    }

    private static final class Lookup {
        final String realm;

        Lookup(String realm) {
            this.realm = realm;
        }
    }

    private static class StubCredentialRepo extends CredentialRepo {
        private final Map<String, CredentialUserIdPassword> byUserId = new HashMap<>();

        void putByUserId(String userId, CredentialUserIdPassword cred) {
            byUserId.put(userId, cred);
        }

        @Override
        public Optional<CredentialUserIdPassword> findBySubject(String subject, String realmId, boolean ignoreRules) {
            return Optional.empty();
        }

        @Override
        public Optional<CredentialUserIdPassword> findByUserId(String userId, String realmId, boolean ignoreRules) {
            return Optional.ofNullable(byUserId.get(userId));
        }
    }

    private static class RecordingUserProfileRepo extends UserProfileRepo {
        final List<Lookup> userIdLookups = new ArrayList<>();
        private final Map<String, UserProfile> byRealmUserId = new HashMap<>();

        void putByUserId(String realm, String userId, UserProfile profile) {
            byRealmUserId.put(realm + "|" + userId, profile);
        }

        @Override
        public Optional<UserProfile> getByUserIdWithIgnoreRules(String realm, String userId) {
            userIdLookups.add(new Lookup(realm));
            return Optional.ofNullable(byRealmUserId.get(realm + "|" + userId));
        }

        @Override
        public Optional<UserProfile> getBySubject(String realm, String subject) {
            return Optional.empty();
        }

        @Override
        public UserProfile fillUIActions(UserProfile model) {
            return model;
        }
    }

    private static class StubRealmRepo extends RealmRepo {
        @Override
        public List<Realm> computeAllowedRealms(CredentialUserIdPassword credential) {
            return List.of();
        }
    }
}
