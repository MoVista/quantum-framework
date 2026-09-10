package com.e2eq.framework.model.persistent.base;

import com.e2eq.framework.model.securityrules.PrincipalContext;

import java.util.Date;

/**
 * Stamps {@link AuditInfo} for the current persist. Current-write impersonation /
 * acting-on-behalf-of fields describe this write only; {@code lastImpersonated*} is sticky.
 */
public final class AuditInfoStamper {

   public static final String ANONYMOUS = "ANONYMOUS";

   private AuditInfoStamper() {}

   public static boolean isImpersonating(PrincipalContext ctx) {
      return ctx != null
            && (ctx.getImpersonatedByUserId() != null || ctx.getImpersonatedBySubject() != null);
   }

   public static boolean isActingOnBehalfOf(PrincipalContext ctx) {
      return ctx != null
            && (ctx.getActingOnBehalfOfUserId() != null || ctx.getActingOnBehalfOfSubject() != null);
   }

   public static String identity(PrincipalContext ctx) {
      return ctx != null && ctx.getUserId() != null ? ctx.getUserId() : ANONYMOUS;
   }

   /**
    * @param creating true when this is the first persist (no {@code creationTs} yet)
    */
   public static void stamp(AuditInfo auditInfo, PrincipalContext ctx, boolean creating, Date now) {
      if (creating) {
         auditInfo.setCreationTs(now);
         auditInfo.setCreationIdentity(identity(ctx));
      } else {
         auditInfo.setLastUpdateTs(now);
         auditInfo.setLastUpdateIdentity(identity(ctx));
      }
      applyCurrentWrite(auditInfo, ctx);
      if (isImpersonating(ctx)) {
         if (ctx.getImpersonatedByUserId() != null) {
            auditInfo.setLastImpersonatedByUserId(ctx.getImpersonatedByUserId());
         }
         auditInfo.setLastImpersonatedAt(now);
      }
   }

   /**
    * Current-write delegation fields only. Nulls the impersonator / acting-on-behalf-of pair
    * when the caller is acting as themselves so a later real-user save does not keep a stale
    * impersonation stamp. Does not touch {@code lastImpersonated*}.
    */
   public static void applyCurrentWrite(AuditInfo auditInfo, PrincipalContext ctx) {
      if (isImpersonating(ctx)) {
         auditInfo.setImpersonatorSubject(ctx.getImpersonatedBySubject());
         auditInfo.setImpersonatorUserId(ctx.getImpersonatedByUserId());
      } else {
         auditInfo.setImpersonatorSubject(null);
         auditInfo.setImpersonatorUserId(null);
      }
      if (isActingOnBehalfOf(ctx)) {
         auditInfo.setActingOnBehalfOfSubject(ctx.getActingOnBehalfOfSubject());
         auditInfo.setActingOnBehalfOfUserId(ctx.getActingOnBehalfOfUserId());
      } else {
         auditInfo.setActingOnBehalfOfSubject(null);
         auditInfo.setActingOnBehalfOfUserId(null);
      }
   }
}
