package com.e2eq.framework.api.security;

import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStore;
import com.e2eq.framework.model.securityrules.SecurityCheckException;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.model.persistent.security.ApplicationRegistration;
import com.e2eq.framework.model.persistent.security.CredentialUserIdPassword;
import com.e2eq.framework.model.persistent.security.UserProfile;
import com.e2eq.framework.model.persistent.morphia.ApplicationRegistrationRequestRepo;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.rest.exceptions.DatabaseMigrationException;
import com.e2eq.framework.util.SecurityUtils;
import com.e2eq.framework.util.TestUtils;
import dev.morphia.Datastore;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.ibm.icu.impl.Assert.fail;


@QuarkusTest
public class TestUserProfile extends BaseRepoTest {

    @Inject
    MigrationService migrationService;

    @Inject
    UserProfileRepo userProfileRepo;

    @Inject
    ApplicationRegistrationRequestRepo regRepo;

    @Inject
    CredentialRepo credentialRepo;

    @Inject
    SecurityUtils securityUtils;

    @Inject
    TestUtils testUtils;
    @Inject
    MorphiaDataStore morphiaDataStore;


    @Test
    public void testMigration() throws Exception {

        try(final SecuritySession ignored = new SecuritySession(pContext, rContext)) {

            try {
                migrationService.checkMigrationRequired();
                Log.info("======   No migration required ======");
            } catch (DatabaseMigrationException ex) {
                ex.printStackTrace();

                Log.info("==== Attempting to run migration scripts ======");
                // attempt to mitigate by running migrations
                Multi.createFrom().emitter(emitter -> {
                    migrationService.runAllUnRunMigrations(testUtils.getTestRealm(), emitter);
                    migrationService.runAllUnRunMigrations(testUtils.getDefaultRealm(), emitter);
                    migrationService.runAllUnRunMigrations(testUtils.getSystemRealm(), emitter);
                }).subscribe().with(
                   item -> System.out.println("Emitting: " + item),
                   failure ->fail("Failed with: " + failure)
                );
            }

            Optional<CredentialUserIdPassword> opCreds = credentialRepo.findByUserId(securityUtils.getSystemUserId(), testUtils.getTestRealm());
            if (opCreds.isPresent()) {
                Log.debug("Found it");
            } else {
                Assertions.fail("No credentials found");
            }
        }
        finally {
            ruleContext.clear();
        }
    }

    @Test void testCredentialsNoSecuritySession() {
        Datastore datastore = morphiaDataStore.getDataStore(testUtils.getTestRealm());


        Optional<CredentialUserIdPassword> opCreds = credentialRepo.findByUserId( securityUtils.getTestUserId(), securityUtils.getTestRealm(), true);
        if (opCreds.isPresent()) {
            Log.debug("Found it");
        } else {
            Assertions.fail("No credentials found");
        }
    }


    @Test
    public void testCreate() throws Exception {

        try(final SecuritySession s = new SecuritySession(pContext, rContext)) {

            if (userProfileRepo == null) {
                Log.warn("userProfile is null?");
                Assertions.fail("userProfileService was not injected properly");
            } else {
                // find something missing.
                Optional<UserProfile> oProfile = userProfileRepo.findByRefName( "xxxxx", testUtils.getTestRealm());
                assert(!oProfile.isPresent());

                oProfile = userProfileRepo.findByRefName(testUtils.getTestUserId());
                if (!oProfile.isPresent()) {
                    Log.info("About to execute");
                    UserProfile profile = new UserProfile();
                    profile.setUsername(testUtils.getTestUserId());
                    profile.setEmail(testUtils.getTestEmail());
                    profile.setUserId(testUtils.getTestUserId());
                    profile.setRefName(testUtils.getTestUserId());
                    profile.setDataDomain(testUtils.getTestDataDomain());

                    //profile.setDataSegment(0);

                    profile = userProfileRepo.save(testUtils.getTestRealm(),profile);
                    assert (profile.getId() != null);


                    // check if getRefId works
                    oProfile = userProfileRepo.findByRefName(profile.getRefName(), testUtils.getTestRealm());
                    if (!oProfile.isPresent()) {
                        assert (false);
                    }

                    oProfile = userProfileRepo.findById(oProfile.get().getId(), testUtils.getTestRealm());
                    if (!oProfile.isPresent()) {
                        assert (false);
                    }

                    long count = userProfileRepo.delete(testUtils.getTestRealm(), profile);
                    assert (count == 1);
                }

                Log.info("Executed");
            }
        } finally {
            ruleContext.clear();
        }
    }

    @Test
    public void testGetUserProfileList() throws Exception {
        pContext = testUtils.getSystemPrincipalContext(testUtils.getSystemUserId(), roles);
        try(final SecuritySession s = new SecuritySession(pContext, rContext)) {

            Log.infof("Default Realm:%s", SecurityContext.getPrincipalContext().get().getDefaultRealm());

            List<UserProfile> userProfiles = userProfileRepo.getList(testUtils.getTestRealm(), 0, 10, null, null);
            Assertions.assertTrue(!userProfiles.isEmpty());
            userProfiles.forEach((up) -> {
                Log.info(up.getId().toString() + ":" + up.getUserId() + ":" + up.getUsername());
            });
        } finally {
            ruleContext.clear();
        }
    }

    @Test
    public void testGetFiltersWithLimit() {

        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            //List<Filter> filters = new ArrayList<>();
            //Filter[] filterArray = userProfileRepo.getFilterArray(filters);
            List<UserProfile> userProfiles = userProfileRepo.getList(testUtils.getTestRealm(), 0,10,null, null);
            for (UserProfile up : userProfiles) {
                Log.info(up.getId().toString() + ":" + up.getUserId() + ":" + up.getUsername());
            }
        }
        finally {
            ruleContext.clear();
        }
    }

    @Test
    public void testGetFiltersWithNoLimit() {
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            //List<Filter> filters = new ArrayList<>();
            //Filter[] filterArray = userProfileRepo.getFilterArray(filters);
            List<UserProfile> userProfiles = userProfileRepo.getAllList(testUtils.getTestRealm());
            for (UserProfile up : userProfiles) {
                Log.info(up.getId().toString() + ":" + up.getUserId() + ":" + up.getUsername());
            }
        }
        finally {
            ruleContext.clear();
        }
    }

    //@Test
    public void testGetRegistrationCollection() throws Exception {


        try(final SecuritySession s = new SecuritySession(pContext, rContext)) {
         //   List<RegistrationRequest> registrationRequests = regRepo.getList(0, 10, null, null, null);
            List<ApplicationRegistration> registrationRequests = regRepo.getListByQuery(0,10, "userId:tuser@test-b2bintegrator.com");
            Assertions.assertFalse(registrationRequests.isEmpty());
            registrationRequests.forEach((req) -> {
                Log.info(req.getId().toString() + ":" + req.getUserId() + ":" + req.getUserDisplayName());
            });
        } finally {
            ruleContext.clear();
        }
    }

  //  @Test
  // removed due to this should only be run with a "test database"
  // need to introduce the not ion of a profile and this test should only be executed under that profile.
    public void testRegistrationApproval() throws Exception {

        try(SecuritySession s = new SecuritySession(pContext, rContext)) {
            //   List<RegistrationRequest> registrationRequests = regRepo.getList(0, 10, null, null, null);
            List<ApplicationRegistration> registrationRequests = regRepo.getListByQuery(0,10, "userId:tuser@test-b2bintegrator.com&&status:UNAPPROVED");
            if (!registrationRequests.isEmpty()) {
                registrationRequests.forEach((req) -> {
                    Log.info(req.getId().toString() + ":" + req.getUserId() + ":" + req.getUserDisplayName());
                });

                regRepo.approveRequest(registrationRequests.get(0).getId().toString());

            }
        } catch ( SecurityCheckException ex ) {
            Log.error(ex.getMessage());
        }
        finally {
            ruleContext.clear();
        }

    }


}
