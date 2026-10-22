package com.e2eq.framework.csv;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;

import java.io.IOException;

/**
 * Internal session state, distinct from API JSON. Field access retains hidden derived values and
 * explicit nulls, and avoids invoking null-rejecting or derived-value setters during replay.
 * Class-level polymorphism and the application's Jackson modules still apply; no default typing
 * or arbitrary class-name deserialization is enabled. This payload must never be returned by APIs.
 */
public final class ImportRecordStateCodec {
    private final ObjectMapper mapper;

    public ImportRecordStateCodec(ObjectMapper applicationMapper) {
        mapper = applicationMapper.copy()
                .disable(MapperFeature.INFER_PROPERTY_MUTATORS, MapperFeature.USE_GETTERS_AS_SETTERS)
                .setVisibility(PropertyAccessor.ALL, Visibility.NONE)
                .setVisibility(PropertyAccessor.FIELD, Visibility.ANY)
                .setAnnotationIntrospector(new JacksonAnnotationIntrospector() {
                    @Override
                    public boolean hasIgnoreMarker(AnnotatedMember member) {
                        return member instanceof AnnotatedMethod
                                || java.lang.reflect.Modifier.isTransient(member.getMember().getModifiers())
                                || member.hasAnnotation(dev.morphia.annotations.Transient.class);
                    }

                    @Override
                    public PropertyName findNameForSerialization(Annotated member) {
                        return member instanceof AnnotatedField ? PropertyName.construct(member.getName()) : null;
                    }

                    @Override
                    public PropertyName findNameForDeserialization(Annotated member) {
                        return member instanceof AnnotatedField ? PropertyName.construct(member.getName()) : null;
                    }

                    @Override
                    public JsonProperty.Access findPropertyAccess(Annotated member) {
                        return member instanceof AnnotatedField ? JsonProperty.Access.READ_WRITE : null;
                    }

                    @Override
                    public JsonInclude.Value findPropertyInclusion(Annotated member) {
                        return JsonInclude.Value.construct(JsonInclude.Include.ALWAYS, JsonInclude.Include.ALWAYS);
                    }

                    @Override
                    public Boolean hasAnyGetter(Annotated member) { return false; }

                    @Override
                    public Boolean hasAnySetter(Annotated member) { return false; }

                    @Override
                    public JsonIgnoreProperties.Value findPropertyIgnoralByName(MapperConfig<?> config, Annotated member) {
                        return JsonIgnoreProperties.Value.empty();
                    }

                    @Override
                    public JsonIncludeProperties.Value findPropertyInclusionByName(MapperConfig<?> config, Annotated member) {
                        return JsonIncludeProperties.Value.all();
                    }

                    @Override
                    public Boolean isIgnorableType(AnnotatedClass type) { return false; }
                });
    }

    public String write(Object record) throws IOException {
        return mapper.writeValueAsString(record);
    }

    public <T> T read(String state, Class<T> type) throws IOException {
        return mapper.readValue(state, type);
    }
}
