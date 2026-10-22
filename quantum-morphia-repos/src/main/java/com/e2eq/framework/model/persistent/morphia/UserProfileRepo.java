package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.auth.UserManagement;

import com.e2eq.framework.util.SecurityUtils;
import com.mongodb.client.model.ReturnDocument;
import dev.morphia.Datastore;
import dev.morphia.ModifyOptions;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import dev.morphia.query.updates.UpdateOperators;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.NonNull;

import java.util.*;

@ApplicationScoped
public class UserProfileRepo extends MorphiaRepo<UserProfile> {

   @Inject
   CredentialRepo credRepo;

   @Inject
   SecurityUtils securityUtils;


   @Inject
   AuthProviderFactory authProviderFactory;
   @Inject
   CredentialRepo credentialRepo;

   public Optional<UserProfile> updateStatus( @NotNull String userId, @NotNull UserProfile.Status status) {
      return updateStatus(getSecurityContextRealmId(), userId, status);
   }

   public Optional<UserProfile> updateStatus(String realm, @NotNull String userId, @NotNull UserProfile.Status status) {
     UserProfile p = this.morphiaDataStoreWrapper.getDataStore(realm)
             .find(UserProfile.class)
             .filter(Filters.eq("userId", userId)).modify(new ModifyOptions().returnDocument(ReturnDocument.AFTER), UpdateOperators.set("status", status.value()));

     return Optional.ofNullable(p);
   }

   public Optional<UserProfile> getBySubject(@NotNull String subject) {
     return getBySubject(getSecurityContextRealmId(), subject);
   }

   public Optional<UserProfile> getBySubject(String realm, @NotNull String subject) {
      return getBySubject(  morphiaDataStoreWrapper.getDataStore(realm), subject);
   }

   public Optional<UserProfile> getBySubject(Datastore datastore,@NotNull String subject) {
      Query<UserProfile> q = datastore.find(this.getPersistentClass()).filter(
         Filters.or(
            Filters.eq("credentialUserIdPasswordRef.entityRefName", subject),
            Filters.eq("additionalCredentialRefs.entityRefName", subject)
         )
      );

      UserProfile p = q.first();

      return Optional.ofNullable(p);
   }


   public Optional<UserProfile> getByUserId(@NotNull String userId) {
      return getByUserId(  morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()), userId );
   }

   public Optional<UserProfile> getByUserId(@NotNull String realm, @NotNull String userId) {
      return getByUserId(  morphiaDataStoreWrapper.getDataStore(realm), userId );
   }

   /**
    * Gets a UserProfile by userId WITHOUT applying security rules.
    * Use this for internal lookups (e.g., from AccessListResolvers) where you need
    * to bypass rule evaluation to avoid recursion or circular dependencies.
    *
    * @param realm the realm/database to query
    * @param userId the userId to search for
    * @return the UserProfile if found
    */
   public Optional<UserProfile> getByUserIdWithIgnoreRules(@NotNull String realm, @NotNull String userId) {
      Log.debugf("UserProfileRepo.getByUserIdWithIgnoreRules: querying realm=%s for userId=%s (bypassing security rules)",
          realm, userId);

      Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
      Query<UserProfile> q = ds.find(getPersistentClass()).filter(
         Filters.or(
            Filters.eq("userId", userId),
            Filters.eq("credentialUserIdPasswordRef.entityRefName", userId),
            Filters.eq("email", userId))
      );

      return Optional.ofNullable(q.first());
   }

   @Override
   protected void setDefaultValues (UserProfile model) {
      if (model.getUserId() == null ) {
         model.setUserId(model.getEmail());
      }
      super.setDefaultValues(model);
   }

   public Optional<UserProfile> getByUserId(Datastore datastore, @NotNull String userId) {
      List<Filter> filters = new ArrayList<>();
      Filter[] filtersArray = getFilterArray(filters, getPersistentClass());
      Filter f;
      if (filtersArray.length > 0) {
         Filter and = Filters.and(filtersArray);

         Filter or = Filters.or(
            Filters.eq("userId", userId),
            Filters.eq("credentialUserIdPasswordRef.entityRefName", userId),
            Filters.eq("email", userId));

         f = Filters.and(and, or);
      } else {
         f = Filters.or(
            Filters.eq("userId", userId),
            Filters.eq("credentialUserIdPasswordRef.entityRefName", userId),
            Filters.eq("email", userId));
      }

      // look for the user profile by the userId field first or by the credentialRefName, or by the email field
      Query<UserProfile> q = datastore.find(this.getPersistentClass()).filter(f);
      UserProfile p = q.first();
      return Optional.ofNullable(p);


   }

   public UserProfile createUser(
                                  String userId,
                                  String fname,
                                  String lname,
                                  Boolean forceChangePassword,
                                  @NotNull @NotEmpty String[] roles,
                                  @NotNull @NonNull @NotEmpty @Size(min=8, max=50, message = "password must be between 8 and 50 characters") String password) {
      return createUser( userId, fname, lname, forceChangePassword, roles, password, securityUtils.getDefaultDomainContext());
   }

   public UserProfile createUser(
      String userId,
      String fname,
      String lname,
      Boolean forceChangePassword,
      @NotNull @NotEmpty String[] roles,
      @NotNull @NonNull @NotEmpty @Size(min=8, max=50, message = "password must be between 8 and 50 characters") String password,
      DomainContext domainContext
      ) {
         return createUser(userId, fname, lname, forceChangePassword, roles, password, domainContext,null);
   }

   public UserProfile createUser(
      String userId,
      String fname,
      String lname,
      Boolean forceChangePassword,
      @NotNull @NotEmpty String[] roles,
      @NotNull @NonNull @NotEmpty @Size(min=8, max=50, message = "password must be between 8 and 50 characters") String password,
      DomainContext domainContext,
      DataDomain dataDomain) {
      return createUser(userId, fname, lname, forceChangePassword, roles, password, domainContext, dataDomain, null);
   }


   public UserProfile createUser(
                                 String userId,
                                 String fname,
                                 String lname,
                                 Boolean forceChangePassword,
                                 @NotNull @NotEmpty String[] roles,
                                 @NotNull @NonNull @NotEmpty @Size(min=8, max=50, message = "password must be between 8 and 50 characters") String password,
                                 DomainContext domainContext,
                                 DataDomain dataDomain,
                                 String realm) {

      // convert roles array to a set of strings
      Set<String> roleSet = new HashSet<>(Arrays.asList(roles));

      // check if the user exists in the authProvider:
      // if not, create the user in the authProvider
      // if the user exists, update the roles in the auth provider
      UserManagement userManager = authProviderFactory.getUserManager();
      if (!userManager.userIdExists( userId)) {
         // create the user in the system using the provided auth provider
         // this will handle the password encryption and storage in the system
         // and will also handle the creation of the user in the authentication system
         // (like LDAP, Active Directory, etc.)
         authProviderFactory.getUserManager().createUser(
            userId, password, forceChangePassword, roleSet, domainContext, dataDomain, Boolean.TRUE);
      } else {
        Log.warnf("User  with userId %s already exists in the auth provider. skipping create", userId);
      }

      UserProfile up;
      Optional<UserProfile> oup = getByUserId( userId);
      if (!oup.isPresent()) {
         up = new UserProfile();
         up.setEmail(userId);
         up.setFname(fname);
         up.setLname(lname);
         up.setUserId(userId);
         up.setRefName(userId);

        // check if credential is present
         Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUserId( userId, envConfigUtils.getSystemRealm(), true);
         if (ocred.isPresent()) {
            // try to find the up
            up.setCredentialUserIdPasswordRef(ocred.get().createEntityReference(envConfigUtils.getSystemRealm()));
         } else {
            throw new IllegalStateException(String.format("Failed to find the user in the credential repository for userId %s", userId));
         }
         // now create the user profile
         up = (realm == null) ? save(up) : save(realm, up);
      } else {
         up = oup.get();
         Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUserId( userId, envConfigUtils.getSystemRealm(), true);
         if (ocred.isPresent()) {
            EntityReference ref = ocred.get().createEntityReference(envConfigUtils.getSystemRealm());
            // ensure the reference is correct
            if (!ref.equals(up.getCredentialUserIdPasswordRef())) {
               up.setCredentialUserIdPasswordRef(ref);
               up = (realm==null) ? save(up) : save(realm, up);
            }
         } else {
            throw new IllegalStateException(String.format("Failed to find the user in the credential repository for userId %s", userId));
         }
      }

      return up;
   }

   @Override
   public long delete (String realmId, UserProfile obj) throws ReferentialIntegrityViolationException {
      java.util.Objects.requireNonNull(obj, "The profile to delete is required");
      java.util.Objects.requireNonNull(obj.getId(), "The profile id is required");
      return delete(realmId, obj.getId());
   }

   @Override
   public long delete(String realmId, org.bson.types.ObjectId id) throws ReferentialIntegrityViolationException {
      java.util.Objects.requireNonNull(id, "The profile id is required");
      // Read using the caller's rules and use only persisted references, never references supplied
      // on the object passed to delete. A failed or unauthorized profile delete cannot cascade.
      Optional<UserProfile> existing = findById(id, realmId);
      if (existing.isEmpty()) {
         return 0;
      }
      UserProfile stored = existing.get();
      long deleted = super.delete(realmId, id);
      if (deleted == 0) {
         return 0;
      }
      // Deleting the profile authorizes deletion of its attached credentials. Their provisioning
      // tenant can differ from the profile's, so credential read rules must not hide them here.
      try (var ignored = com.e2eq.framework.model.securityrules.SecurityCallScope.openIgnoringRules()) {
         deleteAttachedCredential(stored.getCredentialUserIdPasswordRef());
         if (stored.getAdditionalCredentialRefs() != null) {
            for (EntityReference ref : stored.getAdditionalCredentialRefs()) {
               deleteAttachedCredential(ref);
            }
         }
      }
      return deleted;
   }

   private void deleteAttachedCredential(EntityReference ref) throws ReferentialIntegrityViolationException {
      if (ref == null) {
         return;
      }
      String realm = ref.getRealm() != null ? ref.getRealm() : envConfigUtils.getSystemRealm();
      // The stored id identifies the credential even if its reference spelling has changed. A
      // missing id is supported for legacy profile references; an unmatched id never falls back
      // to a potentially unrelated record with the same name.
      Optional<CredentialUserIdPassword> credential = ref.getEntityId() != null
            ? credentialRepo.findById(ref.getEntityId(), realm)
            : credentialRepo.findByRefName(ref.getEntityRefName(), realm);
      if (credential.isPresent() && credentialRepo.delete(realm, credential.get()) == 0) {
         throw new IllegalStateException("Failed to delete attached credential " + ref.getEntityId());
      }
   }
}
