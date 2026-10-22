package com.e2eq.framework.model.persistent.base;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an entity whose references identify one record across all tenants.
 *
 * <p>Reference lookups in the shared system database are normally limited to the caller's tenant,
 * because there a reference is unique only within a tenant. A global identity, such as a realm
 * (which routes requests) or a login credential (which a tenant administrator may manage although
 * the provisioning caller's tenant was stamped on it), must resolve regardless of the tenant
 * stamped on the record. Permission rules still apply to these lookups.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface GlobalReferenceIdentity {
}
