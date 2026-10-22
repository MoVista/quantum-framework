package com.e2eq.framework.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.PolicyRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.seeds.ArchetypeSeeder;
import com.e2eq.framework.security.runtime.RuleContext;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import com.e2eq.framework.security.runtime.SecuritySession;
import org.bson.types.ObjectId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class SeedArchetypeBasicPermissionsImportTest extends BaseRepoTest {

    @Inject UserProfileRepo userProfileRepo;
    @Inject CredentialRepo credentialRepo;
    @Inject PolicyRepo policyRepo;
    @Inject RuleContext ruleContext;

    @Test
    @ActivateRequestContext
    @TestSecurity(user = "system@end2endlogic.com", roles = {"admin"})
    public void importSeedAndVerifyWrites() {
        String realm = testUtils.getTestRealm();
        DataDomain dd = new DataDomain("end2endlogic", "0000000001", "tenant-seed-1", 0, "system@end2endlogic.com");

        ArchetypeSeeder.ImportResult res = ArchetypeSeeder.importArchetype("basic-permissions-v1-test", realm, dd, true);
        assertNotNull(res);
        assertTrue(res.performedWrites, "Seeder should perform writes in test mode");

        // Verify credentials and profiles for the three users defined in the seed
        Map<String, ObjectId> profileIds = new LinkedHashMap<>();
        for (String userId : List.of("test-user@end2endlogic.com",
                "power-user@end2endlogic.com", "tenant-admin@end2endlogic.com")) {
            profileIds.put(userId, checkUser(realm, userId).getId());
        }

        // Reimporting must update the same profiles, with no new identity collisions.
        ArchetypeSeeder.importArchetype("basic-permissions-v1-test", realm, dd, true);
        profileIds.forEach((userId, id) -> assertEquals(id, checkUser(realm, userId).getId()));

        // Rule context should be able to reload without throwing (policies loaded)
        ruleContext.reloadFromRepo(realm);
    }

    private UserProfile checkUser(String realm, String userId) {
        // Establish a principal context aligned with repository rule filters, which commonly scope credentials
        // under the system tenant/org/account. This ensures read access during test verification.
        // Use the target tenant for verification to satisfy repository filters
        DataDomain systemScoped = new DataDomain(
                "end2endlogic",           // orgRefName
                "0000000001",             // accountNumber
                "tenant-seed-1",          // tenantId seeded
                0,
                "system@end2endlogic.com"
        );
        com.e2eq.framework.model.securityrules.PrincipalContext pc = new com.e2eq.framework.model.securityrules.PrincipalContext.Builder()
                .withDefaultRealm(realm)
                .withUserId("system@end2endlogic.com")
                .withRoles(new String[]{"admin"})
                .withDataDomain(systemScoped)
                .withScope("api")
                .build();
        // Also set a resource context so repository rule filters that depend on it are satisfied
        com.e2eq.framework.model.securityrules.ResourceContext rc = new com.e2eq.framework.model.securityrules.ResourceContext.Builder()
                .withRealm(realm)
                .withArea("security")
                .withFunctionalDomain("userProfile")
                .withAction("view")
                .withOwnerId(pc.getUserId())
                .build();
        try (SecuritySession ignored = new SecuritySession(pc, rc)) {
            Optional<CredentialUserIdPassword> oc = credentialRepo.findByUserId(userId, testUtils.getSystemRealm(), true);
            assertTrue(oc.isPresent(), () -> "Missing credential for " + userId);
            Optional<UserProfile> op = userProfileRepo.getByUserId(realm, userId);
            assertTrue(op.isPresent(), () -> "Missing user profile for " + userId);
            assertEquals(userId, op.get().getRefName(), "Each seeded profile has its user's reference");
            // ownerId should equal the userId in the seeded profile's data domain if present
            if (op.get().getDataDomain() != null) {
                assertTrue(userId.equalsIgnoreCase(op.get().getDataDomain().getOwnerId()), "ownerId should equal userId for seeded profile");
            }
            return op.get();
        }
    }
}
