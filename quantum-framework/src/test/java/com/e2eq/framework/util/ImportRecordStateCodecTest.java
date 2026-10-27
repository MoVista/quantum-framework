package com.e2eq.framework.csv;

import com.e2eq.framework.test.HiddenFieldImportModel;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImportRecordStateCodecTest {
    @com.fasterxml.jackson.annotation.JsonTypeInfo(use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,
            property = "kind")
    @com.fasterxml.jackson.annotation.JsonSubTypes(@com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = Detail.class, name = "detail"))
    public interface Value { }

    public static class Detail implements Value {
        @com.fasterxml.jackson.annotation.JsonIgnore public String hidden;
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties("internalName")
    public static class Record {
        public Value value;
        @com.fasterxml.jackson.annotation.JsonProperty(value = "api-name", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String internalName;
    }

    @Test
    void retainsHiddenValuesAndExplicitNullsWithoutCallingSetters() throws Exception {
        for (JsonInclude.Include inclusion : new JsonInclude.Include[]{JsonInclude.Include.ALWAYS, JsonInclude.Include.NON_NULL}) {
            var codec = new ImportRecordStateCodec(new ObjectMapper().setSerializationInclusion(inclusion));
            HiddenFieldImportModel row = new HiddenFieldImportModel();
            row.setRefName("record");
            row.setSecret("supplied");
            row.setStatus(null);
            HiddenFieldImportModel replay = codec.read(codec.write(row), HiddenFieldImportModel.class);
            assertNull(replay.getRequiredKey(), "omitted null-rejecting property remains partial");
            assertNull(replay.getStatus(), "an explicit blank must not restore the field initializer");
            assertEquals("hashed:supplied", replay.getSecretHash());
        }
    }

    @Test
    void retainsDeclaredPolymorphismAndFieldsRenamedOrReadOnlyInTheApi() throws Exception {
        var codec = new ImportRecordStateCodec(new ObjectMapper());
        var row = new Record();
        var detail = new Detail(); detail.hidden = "kept";
        row.value = detail; row.internalName = "original";
        var replay = codec.read(codec.write(row), Record.class);
        assertEquals("kept", ((Detail) replay.value).hidden);
        assertEquals("original", replay.internalName);
    }
}
