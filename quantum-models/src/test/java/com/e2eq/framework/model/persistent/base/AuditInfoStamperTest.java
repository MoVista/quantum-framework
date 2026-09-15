package com.e2eq.framework.model.persistent.base;

import com.e2eq.framework.model.securityrules.PrincipalContext;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class AuditInfoStamperTest {

   private static DataDomain domain() {
      DataDomain dd = new DataDomain();
      dd.setTenantId("acme-com");
      dd.setOrgRefName("ACME");
      dd.setAccountNum("0001");
      dd.setOwnerId("john@acme.com");
      dd.setDataSegment(0);
      return dd;
   }

   private static PrincipalContext.Builder baseBuilder() {
      return new PrincipalContext.Builder()
            .withDefaultRealm("acme-com")
            .withDataDomain(domain())
            .withRoles(new String[] {"USER"})
            .withScope("Authentication");
   }

   private static PrincipalContext targetUser() {
      return baseBuilder().withUserId("john@acme.com").build();
   }

   private static PrincipalContext impersonating() {
      return baseBuilder()
            .withUserId("john@acme.com")
            .withImpersonatedByUserId("admin@system.com")
            .withImpersonatedBySubject("admin-subject")
            .build();
   }

   @Test
   void impersonatedCreate_stampsCurrentWriteAndStickyPair() {
      AuditInfo info = new AuditInfo();
      Date now = new Date(1_700_000_000_000L);

      AuditInfoStamper.stamp(info, impersonating(), true, now);

      assertEquals("john@acme.com", info.getCreationIdentity());
      assertSame(now, info.getCreationTs());
      assertEquals("admin@system.com", info.getImpersonatorUserId());
      assertEquals("admin-subject", info.getImpersonatorSubject());
      assertEquals("admin@system.com", info.getLastImpersonatedByUserId());
      assertSame(now, info.getLastImpersonatedAt());
   }

   @Test
   void realUserUpdate_clearsCurrentWriteImpersonator_keepsStickyPair() {
      AuditInfo info = new AuditInfo();
      Date impersonatedAt = new Date(1_700_000_000_000L);
      AuditInfoStamper.stamp(info, impersonating(), true, impersonatedAt);

      Date later = new Date(1_700_000_100_000L);
      AuditInfoStamper.stamp(info, targetUser(), false, later);

      assertEquals("john@acme.com", info.getLastUpdateIdentity());
      assertSame(later, info.getLastUpdateTs());
      assertNull(info.getImpersonatorUserId());
      assertNull(info.getImpersonatorSubject());
      assertEquals("admin@system.com", info.getLastImpersonatedByUserId());
      assertSame(impersonatedAt, info.getLastImpersonatedAt());
   }

   @Test
   void secondImpersonatedUpdate_refreshesStickyTimestamp() {
      AuditInfo info = new AuditInfo();
      AuditInfoStamper.stamp(info, impersonating(), true, new Date(1_700_000_000_000L));

      Date later = new Date(1_700_000_100_000L);
      AuditInfoStamper.stamp(info, impersonating(), false, later);

      assertEquals("admin@system.com", info.getImpersonatorUserId());
      assertEquals("admin@system.com", info.getLastImpersonatedByUserId());
      assertSame(later, info.getLastImpersonatedAt());
   }

   @Test
   void actingOnBehalfOf_isClearedWhenCallerActsAsThemselves() {
      PrincipalContext onBehalf = baseBuilder()
            .withUserId("agent@acme.com")
            .withActingOnBehalfOfUserId("customer@acme.com")
            .withActingOnBehalfOfSubject("customer-subject")
            .build();
      AuditInfo info = new AuditInfo();
      AuditInfoStamper.stamp(info, onBehalf, true, new Date());

      assertEquals("customer@acme.com", info.getActingOnBehalfOfUserId());

      AuditInfoStamper.stamp(info, targetUser(), false, new Date());

      assertNull(info.getActingOnBehalfOfUserId());
      assertNull(info.getActingOnBehalfOfSubject());
   }
}
