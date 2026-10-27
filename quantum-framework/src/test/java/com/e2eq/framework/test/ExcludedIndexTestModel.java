package com.e2eq.framework.test;

import com.e2eq.framework.model.persistent.base.ExcludeInheritedIndexes;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Field;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.IndexOptions;
import dev.morphia.annotations.Indexes;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/** An entity that leaves out the inherited reference-identity indexes and declares one of its own. */
@Entity
@ExcludeInheritedIndexes({
    UnversionedBaseModel.REF_NAME_TENANT_UNIQUE_INDEX,
    UnversionedBaseModel.REF_NAME_TENANT_LOOKUP_INDEX
})
@Indexes(@Index(fields = @Field("testField"), options = @IndexOptions(name = ExcludedIndexTestModel.OWN_INDEX)))
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
public class ExcludedIndexTestModel extends UnversionedBaseModel {

    public static final String OWN_INDEX = "idx_excludedIndexTestModel_testField";

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
