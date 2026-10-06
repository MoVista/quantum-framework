package com.e2eq.framework.test;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/** An entity without a {@code version} field, for update paths that only bump it on {@code BaseModel}. */
@Entity
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
public class UnversionedTestModel extends UnversionedBaseModel {

    protected String testField;

    @Override
    public String bmFunctionalArea() {
        return "QUANTUM";
    }

    @Override
    public String bmFunctionalDomain() {
        return "TEST";
    }
}
