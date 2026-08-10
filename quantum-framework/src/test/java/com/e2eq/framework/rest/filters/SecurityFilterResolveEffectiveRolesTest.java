package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.UserGroup;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.persistent.morphia.UserGroupRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.Principal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SecurityFilter.resolveEffectiveRoles.
 * These tests validate that roles are the union of:
 * - SecurityIdentity roles
 * - CredentialUserIdPassword roles
 * - Roles from all UserGroups associated to the UserProfile referenced by the credential's subject
 */
public class SecurityFilterResolveEffectiveRolesTest {

    private static SecurityIdentity identityWithRoles(String principalName, Set<String> roles) {
        Principal principal = principalName == null ? null : () -> principalName;
        return (SecurityIdentity) java.lang.reflect.Proxy.newProxyInstance(
                SecurityIdentity.class.getClassLoader(),
                new Class[]{SecurityIdentity.class},
                (proxy, method, args) -> {
                    if ("getRoles".equals(method.getName())) {
                        return roles;
                    }
                    if ("getPrincipal".equals(method.getName())) {
                        return principal;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt.equals(boolean.class)) return false;
                    if (rt.isPrimitive()) {
                        if (rt.equals(int.class)) return 0;
                        if (rt.equals(long.class)) return 0L;
                        if (rt.equals(double.class)) return 0.0;
                        if (rt.equals(float.class)) return 0f;
                        if (rt.equals(short.class)) return (short)0;
                        if (rt.equals(byte.class)) return (byte)0;
                        if (rt.equals(char.class)) return (char)0;
                    }
                    return null;
                }
        );
    }

    private static SecurityIdentity identityWithRoles(Set<String> roles) {
        return identityWithRoles(null, roles);
    }

    // Simple stubs for repos
    private static class StubUserProfileRepo extends UserProfileRepo {
        private final Map<String, UserProfile> bySubject = new HashMap<>();
        public void put(UserProfile up, String subject) { bySubject.put(subject, up); }
        @Override
        public Optional<UserProfile> getBySubject(String realm, String subject) { return Optional.ofNullable(bySubject.get(subject)); }
        @Override
        public Optional<UserProfile> getBySubject(String subject) { return Optional.ofNullable(bySubject.get(subject)); }
    }

    private static class StubUserGroupRepo extends UserGroupRepo {
        private final Map<String, List<UserGroup>> byEntityRef = new HashMap<>();
        public void put(EntityReference ref, List<UserGroup> groups) { byEntityRef.put(ref.getEntityRefName(), groups); }
        @Override
        public List<UserGroup> findByUserProfileRef(EntityReference userProfileRef) {
            return byEntityRef.getOrDefault(userProfileRef.getEntityRefName(), Collections.emptyList());
        }
    }

    private static SecurityFilter newFilterWithRepos(StubUserProfileRepo upr, StubUserGroupRepo ugr) throws Exception {
        SecurityFilter filter = new SecurityFilter();
        setField(filter, "userProfileRepo", upr);
        setField(filter, "userGroupRepo", ugr);
        return filter;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = SecurityFilter.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static String[] invokeResolveEffectiveRoles(SecurityFilter filter, SecurityIdentity identity, CredentialUserIdPassword cred) throws Exception {
        Method m = SecurityFilter.class.getDeclaredMethod("resolveEffectiveRoles", SecurityIdentity.class, CredentialUserIdPassword.class);
        m.setAccessible(true);
        return (String[]) m.invoke(filter, identity, cred);
    }

    @Test
    public void testUnionOfIdentityCredentialAndUserGroupRoles() throws Exception {
        // Identity roles
        Set<String> identityRoles = new HashSet<>(Arrays.asList("user", "viewer"));
        SecurityIdentity identity = identityWithRoles(identityRoles);

        // Credential with roles and subject
        String subject = "sub-123";
        CredentialUserIdPassword cred = CredentialUserIdPassword.builder()
                .userId("u1")
                .subject(subject)
                .domainContext(com.e2eq.framework.model.security.DomainContext.builder()
                        .tenantId("t1").defaultRealm("realmA").orgRefName("org1").accountId("acct1").build())
                .lastUpdate(new Date())
                .roles(new String[]{"editor", "user"}) // note: overlap with identity "user"
                .build();

        // UserProfile referenced by subject
        UserProfile userProfile = UserProfile.builder()
                .credentialUserIdPasswordRef(EntityReference.builder().entityRefName(subject).entityDisplayName("cred").build())
                .email("test@example.com")
                .build();

        // Ensure UserProfile has a refName and displayName so createEntityReference() works
        userProfile.setRefName("userProfile-" + subject);
        userProfile.setDisplayName("User Profile for " + subject);

        // UserGroups that include this profile with their roles
        UserGroup group1 = new UserGroup();
        group1.setRoles(Arrays.asList("user", "admin"));
        UserGroup group2 = new UserGroup();
        group2.setRoles(Arrays.asList("developer"));

        // Stub repos wired with data
        StubUserProfileRepo upr = new StubUserProfileRepo();
        upr.put(userProfile, subject);

        StubUserGroupRepo ugr = new StubUserGroupRepo();
        ugr.put(userProfile.createEntityReference(), Arrays.asList(group1, group2));

        SecurityFilter filter = newFilterWithRepos(upr, ugr);

        String[] effective = invokeResolveEffectiveRoles(filter, identity, cred);
        Set<String> result = new HashSet<>(Arrays.asList(effective));

        // Expected union: identity(user, viewer) U cred(editor, user) U groups(user, admin, developer)
        Set<String> expected = new HashSet<>(Arrays.asList("user", "viewer", "editor", "admin", "developer"));
        assertEquals(expected, result, "Effective roles should be the union without duplicates");
    }

    @Test
    public void testAnonymousWhenNoRolesAnywhere() throws Exception {
        SecurityIdentity identity = identityWithRoles(Collections.emptySet());
        // No credential provided -> also exercises identity == provided but empty, and no profile/groups
        StubUserProfileRepo upr = new StubUserProfileRepo();
        StubUserGroupRepo ugr = new StubUserGroupRepo();
        SecurityFilter filter = newFilterWithRepos(upr, ugr);

        String[] effective = invokeResolveEffectiveRoles(filter, identity, null);
        assertArrayEquals(new String[]{"ANONYMOUS"}, effective);
    }

    /**
     * Regression for impersonation: buildImpersonatedContext must call
     * resolveEffectiveRoles(null, targetCreds, ...) so operator TOKEN/JWT roles are not
     * unioned into the impersonated PrincipalContext.
     */
    @Test
    public void testBuildImpersonatedContextExcludesOperatorTokenRoles() throws Exception {
        DomainContext domainContext = DomainContext.builder()
                .tenantId("t1")
                .defaultRealm("realmA")
                .orgRefName("org1")
                .accountId("acct1")
                .build();

        CredentialUserIdPassword targetCreds = CredentialUserIdPassword.builder()
                .userId("target@example.com")
                .subject("target-subject")
                .domainContext(domainContext)
                .lastUpdate(new Date())
                .roles(new String[]{"user", "viewer"})
                .build();

        CredentialUserIdPassword operatorCreds = CredentialUserIdPassword.builder()
                .userId("admin@example.com")
                .subject("admin-subject")
                .domainContext(domainContext)
                .lastUpdate(new Date())
                .roles(new String[]{"admin"})
                .build();

        SecurityIdentity operatorIdentity = identityWithRoles(
                "admin-subject",
                new HashSet<>(Arrays.asList("admin", "superuser")));

        StubUserProfileRepo upr = new StubUserProfileRepo();
        StubUserGroupRepo ugr = new StubUserGroupRepo();
        SecurityFilter filter = newFilterWithRepos(upr, ugr);
        // Keep identityRoleResolver null so the unit-test fallback path is used (no CDI).

        // Control: passing the operator identity unions TOKEN roles into the result.
        String[] withOperatorIdentity = invokeResolveEffectiveRoles(filter, operatorIdentity, targetCreds);
        Set<String> leaked = new HashSet<>(Arrays.asList(withOperatorIdentity));
        assertTrue(leaked.contains("admin") || leaked.contains("superuser"),
                "control case should include operator TOKEN roles when identity is present: " + leaked);

        Method build = SecurityFilter.class.getDeclaredMethod(
                "buildImpersonatedContext",
                CredentialUserIdPassword.class,
                CredentialUserIdPassword.class,
                String.class,
                String.class);
        build.setAccessible(true);
        PrincipalContext impersonated = (PrincipalContext) build.invoke(
                filter, targetCreds, operatorCreds, null, null);

        assertEquals("target@example.com", impersonated.getUserId());
        assertEquals("admin@example.com", impersonated.getImpersonatedByUserId());
        assertEquals("admin-subject", impersonated.getImpersonatedBySubject());

        Set<String> impersonatedRoles = new HashSet<>(Arrays.asList(impersonated.getRoles()));
        assertTrue(impersonatedRoles.contains("user"),
                "target credential roles should be present: " + impersonatedRoles);
        assertTrue(impersonatedRoles.contains("viewer"),
                "target credential roles should be present: " + impersonatedRoles);
        assertFalse(impersonatedRoles.contains("admin"),
                "operator TOKEN role 'admin' must not leak into impersonated context: " + impersonatedRoles);
        assertFalse(impersonatedRoles.contains("superuser"),
                "operator TOKEN role 'superuser' must not leak into impersonated context: " + impersonatedRoles);
    }
}
