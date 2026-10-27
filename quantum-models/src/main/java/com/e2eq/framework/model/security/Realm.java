package com.e2eq.framework.model.security;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.GlobalReferenceIdentity;
import com.mongodb.client.model.CollationStrength;
import dev.morphia.annotations.Collation;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Field;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.IndexOptions;
import dev.morphia.annotations.Indexed;
import dev.morphia.annotations.Indexes;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.Date;

/*
 * Realm refNames are globally unique, independent of dataDomain.tenantId: request routing resolves
 * a realm by refName alone and uses it as the MongoDB database name, so two realms must never share
 * a refName, including case variants (MongoDB also rejects database names differing only in case).
 * This index is in addition to the reference indexes inherited from UnversionedBaseModel.
 */
@Entity
@Indexes({
   @Index(fields = @Field("refName"),
      options = @IndexOptions(unique = true, collation = @Collation(locale = "en", strength = CollationStrength.SECONDARY),
         name = Realm.REF_NAME_UNIQUE_INDEX))
})
@GlobalReferenceIdentity
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Realm extends BaseModel {

   /** Name of the global, case-insensitive unique index on {@code refName}. */
   public static final String REF_NAME_UNIQUE_INDEX = "idx_realm_refName_unique";

   /**
    this is the last part of a given email which is being used as an identifier.  So
    mingardia@end2endlogic.com for example the email domain would be "end2endlogic.com".
    */
   @Indexed(options= @IndexOptions(unique=true))
   @NotNull
   @NonNull
   protected String emailDomain;

   /**
    This is the connection string to use to connect to the database server
    */
   protected String connectionString;

   /**
    The database to use with-in that database server.
    */
   @Indexed(options= @IndexOptions(unique=true))
   @NotNull
   @NonNull
   protected String databaseName;

   /** the user id of the owner of this realm */
   protected String defaultAdminUserId;


   public String getDefaultAdminUserId() {
      if (defaultAdminUserId == null) {
         return "admin@" + emailDomain;
      } else {
         return defaultAdminUserId;
      }
   }
   /**
    * The domain context to use by default for uses that authenticate with in this realm.
    */
   @Valid
   @NotNull
   @NonNull
   protected DomainContext domainContext;

   /**
    * When true, seed packs will be automatically applied to this realm on startup.
    * This flag is unioned with the quantum.seed-pack.apply.realms configuration property.
    * Defaults to false.
    */
   @Builder.Default
   protected boolean applySeedsOnStartup = false;

   /**
    * Default UX perspective for this realm. DI Studio uses this to determine
    * which shell/menu shape to present when a user switches into the realm.
   */
   protected String defaultPerspective;

   /**
    * Tenant setup projection used by system-perspective catalogs so they can show
    * whether a tenant is ready to launch without recalculating cross-realm state.
    */
   @Builder.Default
   protected RealmSetupStatus setupStatus = RealmSetupStatus.NOT_STARTED;

   @Builder.Default
   protected Integer setupCompletionPercent = 0;

   @Builder.Default
   protected Integer configuredSolutionCount = 0;

   @Builder.Default
   protected Integer readySolutionCount = 0;

   @Builder.Default
   protected Integer pendingSeedPackCount = 0;

   @Builder.Default
   protected Integer pendingMigrationCount = 0;

   protected String setupSummary;

   protected Date setupLastUpdated;

   @Override
   public String bmFunctionalArea() {
      return "SECURITY";
   }

   @Override
   public String bmFunctionalDomain() {
      return "REALM";
   }


}
