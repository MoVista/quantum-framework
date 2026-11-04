package com.e2eq.framework.security;

import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.security.CredentialType;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.security.runtime.SecuritySession;
import com.mongodb.client.model.Filters;
import dev.morphia.MorphiaDatastore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a user profile is found from a credential linked through {@code additionalCredentialRefs}, as
 * an SSO credential would be when an existing password user is converted (SSO plan, D19). Lookups
 * from a credential to its profile go through the subject copied into the reference's
 * {@code entityRefName}, so that copy must follow the credential when its subject changes at
 * first sign-in.
 */
@QuarkusTest
public class UserProfileAdditionalCredentialLookupTest extends BaseRepoTest {

    @Inject
    UserProfileRepo userProfileRepo;

    @Inject
    CredentialRepo credentialRepo;

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @Test
    public void profileIsFoundByThePrimaryAndTheAdditionalCredentialSubject() {
        String userId = unique("both");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            UserProfile profile = createPasswordUser(userId);
            CredentialUserIdPassword password = credentialRepo.findByUserId(userId, testUtils.getSystemRealm(), true).orElseThrow();
            CredentialUserIdPassword sso = createPendingSsoCredential(userId);
            attach(profile, sso);

            assertEquals(profile.getId(), userProfileRepo.getBySubject(testUtils.getTestRealm(), password.getSubject()).orElseThrow().getId());
            assertEquals(profile.getId(), userProfileRepo.getBySubject(testUtils.getTestRealm(), sso.getSubject()).orElseThrow().getId());
        } finally {
            cleanUp(userId);
        }
    }

    @Test
    public void bindingASubjectWithoutUpdatingTheReferenceLosesTheProfile() {
        String userId = unique("bind");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            UserProfile profile = createPasswordUser(userId);
            CredentialUserIdPassword sso = createPendingSsoCredential(userId);
            attach(profile, sso);
            String pending = sso.getSubject();

            // First sign-in replaces the placeholder with the Cognito sub on the credential only.
            String cognitoSub = UUID.randomUUID().toString();
            sso.setSubject(cognitoSub);
            sso.setRefName(cognitoSub);
            credentialRepo.save(testUtils.getSystemRealm(), sso);

            assertTrue(userProfileRepo.getBySubject(testUtils.getTestRealm(), cognitoSub).isEmpty(),
                    "the profile's reference still holds the placeholder, so the bound subject finds nothing");
            assertTrue(userProfileRepo.getBySubject(testUtils.getTestRealm(), pending).isPresent());

            // Updating the copy in the profile's reference restores the lookup.
            UserProfile stored = userProfileRepo.findById(profile.getId(), testUtils.getTestRealm()).orElseThrow();
            stored.getAdditionalCredentialRefs().forEach(ref -> {
                if (pending.equals(ref.getEntityRefName())) {
                    ref.setEntityRefName(cognitoSub);
                }
            });
            userProfileRepo.save(testUtils.getTestRealm(), stored);

            assertEquals(profile.getId(), userProfileRepo.getBySubject(testUtils.getTestRealm(), cognitoSub).orElseThrow().getId());
            assertTrue(userProfileRepo.getBySubject(testUtils.getTestRealm(), pending).isEmpty());
        } finally {
            cleanUp(userId);
        }
    }

    @Test
    public void userIdLookupsUseTheProfileAndPrimaryCredentialOnly() {
        String userId = unique("userid");
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            UserProfile profile = createPasswordUser(userId);
            CredentialUserIdPassword sso = createPendingSsoCredential(userId);
            attach(profile, sso);

            // The profile keeps the email as its userId; the SSO credential's Cognito-style userId
            // and subject are not profile userId keys.
            assertEquals(profile.getId(), userProfileRepo.getByUserIdWithIgnoreRules(testUtils.getTestRealm(), userId).orElseThrow().getId());
            assertTrue(userProfileRepo.getByUserIdWithIgnoreRules(testUtils.getTestRealm(), sso.getUserId()).isEmpty());
            assertTrue(userProfileRepo.getByUserIdWithIgnoreRules(testUtils.getTestRealm(), sso.getSubject()).isEmpty());
        } finally {
            cleanUp(userId);
        }
    }

    /**
     * A password user shaped as Cognito provisions one: the credential's refName is its subject, and
     * the profile's reference copies it. (The custom token provider's createUser leaves refName
     * unset, so it defaults to the id; that is not what deployed users have.)
     */
    private UserProfile createPasswordUser(String userId) {
        CredentialUserIdPassword password = new CredentialUserIdPassword();
        String subject = UUID.randomUUID().toString();
        password.setUserId(userId);
        password.setSubject(subject);
        password.setRefName(subject);
        password.setDomainContext(testRealmDomainContext());
        password.setLastUpdate(new Date());
        password.setRoles(new String[]{"user"});
        password.setCredentialType(CredentialType.PASSWORD);
        password = credentialRepo.save(testUtils.getSystemRealm(), password);

        UserProfile profile = new UserProfile();
        profile.setUserId(userId);
        profile.setEmail(userId);
        profile.setRefName(userId);
        profile.setFname("Sso");
        profile.setLname("Convert");
        profile.setCredentialUserIdPasswordRef(password.createEntityReference(testUtils.getSystemRealm()));
        return userProfileRepo.save(testUtils.getTestRealm(), profile);
    }

    /** A pending SSO credential as the conversion would create it: refName equals the placeholder subject. */
    private CredentialUserIdPassword createPendingSsoCredential(String userId) {
        CredentialUserIdPassword sso = new CredentialUserIdPassword();
        String pending = "pending-" + UUID.randomUUID();
        sso.setUserId(ssoUserId(userId));
        sso.setSubject(pending);
        sso.setRefName(pending);
        sso.setDomainContext(testRealmDomainContext());
        sso.setLastUpdate(new Date());
        sso.setRoles(new String[]{"user"});
        sso.setCredentialType(CredentialType.PASSWORD);
        return credentialRepo.save(testUtils.getSystemRealm(), sso);
    }

    private void attach(UserProfile profile, CredentialUserIdPassword credential) {
        UserProfile stored = userProfileRepo.findById(profile.getId(), testUtils.getTestRealm()).orElseThrow();
        List<EntityReference> refs = new ArrayList<>(stored.getAdditionalCredentialRefs());
        refs.add(credential.createEntityReference(testUtils.getSystemRealm()));
        stored.setAdditionalCredentialRefs(refs);
        userProfileRepo.save(testUtils.getTestRealm(), stored);
    }

    private DomainContext testRealmDomainContext() {
        return new DomainContext(pContext.getDataDomain(), testUtils.getTestRealm());
    }

    private static String unique(String label) {
        return "sso-" + label + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.example";
    }

    private static String ssoUserId(String userId) {
        return "jumpcloud_" + userId;
    }

    private void cleanUp(String userId) {
        MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(testUtils.getSystemRealm());
        ds.getDatabase().getCollection(ds.getMapper().getEntityModel(CredentialUserIdPassword.class).collectionName())
                .deleteMany(Filters.in("userId", List.of(userId, ssoUserId(userId))));
        MorphiaDatastore tds = morphiaDataStoreWrapper.getDataStore(testUtils.getTestRealm());
        tds.getDatabase().getCollection(tds.getMapper().getEntityModel(UserProfile.class).collectionName())
                .deleteMany(Filters.eq("userId", userId));
    }
}
