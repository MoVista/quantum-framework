package com.e2eq.framework.test;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Entity
@Data
@EqualsAndHashCode(callSuper = true)
public class RequiredImportModel extends BaseModel {
    @NotBlank
    private String requiredValue;

    @Override
    public String bmFunctionalArea() { return "TEST"; }
    @Override
    public String bmFunctionalDomain() { return "CSV"; }
}
