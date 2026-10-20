package com.e2eq.framework.model.persistent.imports;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Field;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.IndexOptions;
import dev.morphia.annotations.Indexes;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@EqualsAndHashCode(callSuper = true)
@RegisterForReflection
@SuperBuilder
@NoArgsConstructor
@Entity
/*
 * Staging rows are read by session: page through a session in row order, select rows to commit
 * (no errors, INSERT/UPDATE intent), and address one row by number. The {sessionRefName, rowNumber}
 * index also serves session-only queries, so separate single-field indexes on sessionRefName and
 * rowNumber are not declared. All staging queries use the default (simple) collation.
 */
@Indexes({
        @Index(fields = {@Field(value = "sessionRefName"), @Field(value = "rowNumber")},
                options = @IndexOptions(name = ImportSessionRow.SESSION_ROW_INDEX)),
        @Index(fields = {@Field(value = "sessionRefName"), @Field(value = "hasErrors"), @Field(value = "intent")},
                options = @IndexOptions(name = ImportSessionRow.SESSION_COMMIT_INDEX))
})
public class ImportSessionRow extends BaseModel {

    /** Name of the index used to read a session's rows in row order or address one row. */
    public static final String SESSION_ROW_INDEX = "idx_importSessionRow_session_row";

    /** Name of the index used to select a session's committable rows. */
    public static final String SESSION_COMMIT_INDEX = "idx_importSessionRow_session_commit";

    private String sessionRefName; // ImportSession.refName
    private int rowNumber;

    /**
     * Reference ({@code refName}) of the target document this row would insert or update, or null
     * when the row could not be parsed. The row's own {@code refName} is its staging identity (see
     * {@link #stagingRefName(String, int)}), so the same target can be staged by any number of
     * sessions, users, and target models.
     */
    private String targetRefName;
    private String intent; // INSERT, UPDATE, SKIP
    private boolean hasErrors;

    // JSON-serialized List<CSVImportHelper.FieldError>
    private String errorsJson;

    // JSON-serialized T (target entity)
    private String recordJson;

    // Original CSV line
    private String rawLine;

    /**
     * Returns the staging identity used as a row's {@code refName}: unique within the session's
     * collection and independent of the target reference.
     *
     * @param sessionRefName the owning import session's refName
     * @param rowNumber the 1-based data row number in the imported file
     * @return {@code <sessionRefName>:<rowNumber>}
     */
    public static String stagingRefName(String sessionRefName, int rowNumber) {
        return sessionRefName + ":" + rowNumber;
    }

    @Override
    public String bmFunctionalArea() {
        return "IMPORTS";
    }

    @Override
    public String bmFunctionalDomain() {
        return "IMPORTS";
    }
}
