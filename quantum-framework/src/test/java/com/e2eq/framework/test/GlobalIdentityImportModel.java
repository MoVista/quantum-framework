package com.e2eq.framework.test;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.GlobalReferenceIdentity;
import dev.morphia.annotations.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Like a credential: references identify one record across tenants. */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@GlobalReferenceIdentity
public class GlobalIdentityImportModel extends BaseModel {
    private String testField;

    @Override
    public String bmFunctionalArea() { return "TEST"; }
    @Override
    public String bmFunctionalDomain() { return "CSV"; }
}
