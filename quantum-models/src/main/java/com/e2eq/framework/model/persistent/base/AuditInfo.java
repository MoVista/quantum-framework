package com.e2eq.framework.model.persistent.base;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Date;

@RegisterForReflection
@EqualsAndHashCode
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditInfo {
   protected Date creationTs;
   protected String creationIdentity;
   protected Date lastUpdateTs;
   protected String lastUpdateIdentity;
   /**
    * Operator on the current write when that write was impersonated. Cleared on a later
    * non-impersonated save so it stays aligned with {@link #lastUpdateIdentity}.
    */
   protected String impersonatorSubject;
   /**
    * Operator on the current write when that write was impersonated. Cleared on a later
    * non-impersonated save so it stays aligned with {@link #lastUpdateIdentity}.
    */
   protected String impersonatorUserId;
   /**
    * Operator who last wrote this record while impersonating. Sticky: set on an impersonated
    * create/update and never cleared by a later non-impersonated write. There is no event log
    * of every impersonated touch; this is the surviving hint.
    */
   protected String lastImpersonatedByUserId;
   /**
    * When {@link #lastImpersonatedByUserId} was last stamped.
    */
   protected Date lastImpersonatedAt;
   protected String actingOnBehalfOfSubject;
   protected String actingOnBehalfOfUserId;

   public AuditInfo(Date createTs, String createIdentity, Date lastUpdateTs, String lastUpdateIdentity) {
      this.creationTs = createTs;
      this.creationIdentity = createIdentity;
      this.lastUpdateTs = lastUpdateTs;
      this.lastUpdateIdentity = lastUpdateIdentity;
   }
}
