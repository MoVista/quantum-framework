package com.e2eq.framework.test;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.morphia.annotations.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.NonNull;

/**
 * Like a credential: a persisted value hidden from JSON, a setter that rejects null, and a property
 * with a non-null default.
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class HiddenFieldImportModel extends BaseModel {
    @NonNull
    private String requiredKey;

    @JsonIgnore
    private String secretHash;

    private String status = "initial";

    /** Like {@code CredentialUserIdPassword.setPassword}: stores only a derived, hidden value. */
    public void setSecret(String secret) {
        if (secret != null) {
            this.secretHash = "hashed:" + secret;
        }
    }

    @Override
    public String bmFunctionalArea() { return "TEST"; }
    @Override
    public String bmFunctionalDomain() { return "CSV"; }
}
