package com.e2eq.framework.csv;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.type.ArrayType;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies an imported row to the existing document it updates.
 *
 * <p>An imported record only carries the properties its file supplied. Saving it in place of the
 * existing document would clear every other property and re-stamp ownership and audit metadata,
 * so an UPDATE instead copies the imported properties onto the existing document, which is then
 * saved through the repository's normal save path (validation, versioning, audit, and lifecycle
 * hooks apply as for any other update).</p>
 *
 * <ul>
 *   <li>With a column list, exactly those property paths are copied, including empty values, so
 *       a blank cell clears the property. Nested paths ({@code address.city}) change only that
 *       nested property. An indexed path ({@code tags[0]}) replaces the whole collection.</li>
 *   <li>Identity and system-managed properties are never copied: {@code id}, {@code refName}
 *       (the existing spelling is kept, since other documents' references copy it),
 *       {@code version}, {@code dataDomain}, and {@code auditInfo}.</li>
 * </ul>
 */
public final class ImportRecordMerger {

    private static final Set<String> PROTECTED_ROOTS = Set.of(
            "id", "_id", "refName", "version", "auditInfo", "dataDomain",
            "actionList", "modelSourceRealm", "skipValidation", "defaultUIActions");

    private static final List<Class<?>> REPLACED_CONTAINER_TYPES = List.of(
            Collection.class, List.class, Set.class, Map.class,
            java.util.ArrayList.class, java.util.LinkedList.class, java.util.HashSet.class,
            java.util.LinkedHashSet.class, java.util.TreeSet.class, java.util.HashMap.class,
            java.util.LinkedHashMap.class, java.util.TreeMap.class);

    private final ObjectMapper source;
    private final ObjectMapper deepMerge;
    private final ObjectMapper shallowMerge;

    /**
     * Creates a merger that serializes and deserializes records with copies of the given mapper,
     * so the application's modules and serializers apply.
     *
     * @param objectMapper the application's object mapper
     */
    public ImportRecordMerger(ObjectMapper objectMapper) {
        this.source = objectMapper;
        this.deepMerge = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .setDefaultMergeable(Boolean.TRUE);
        // Merging a collection appends to it; an imported collection replaces the existing one.
        for (Class<?> type : REPLACED_CONTAINER_TYPES) {
            deepMerge.configOverride(type).setMergeable(Boolean.FALSE);
        }
        SimpleModule arrays = new SimpleModule("import-array-replacement");
        arrays.setDeserializerModifier(new BeanDeserializerModifier() {
            @Override
            public JsonDeserializer<?> modifyArrayDeserializer(DeserializationConfig config,
                    ArrayType type, com.fasterxml.jackson.databind.BeanDescription bean,
                    JsonDeserializer<?> deserializer) {
                return new ReplacingArrayDeserializer(deserializer);
            }
        });
        deepMerge.registerModule(arrays);
        this.shallowMerge = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private static final class ReplacingArrayDeserializer extends DelegatingDeserializer {
        private ReplacingArrayDeserializer(JsonDeserializer<?> delegate) {
            super(delegate);
        }

        @Override
        protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> delegate) {
            return new ReplacingArrayDeserializer(delegate);
        }

        @Override
        public Boolean supportsUpdate(DeserializationConfig config) {
            return Boolean.FALSE;
        }
    }

    /**
     * Copies the imported properties of {@code incoming} onto {@code existing}.
     *
     * @param existing the persisted document to update; modified in place
     * @param incoming the record built from the imported row
     * @param columns the imported property paths; required, because an imported record cannot
     *                distinguish a property the file left out from one it set to its default
     *                (for example a primitive {@code false} or {@code 0})
     * @param <T> record type
     * @return {@code existing}, updated
     * @throws IllegalArgumentException if no columns are given
     */
    public <T> T merge(T existing, T incoming, List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("The imported columns are required to update an existing record");
        }
        JsonNode incomingTree = source.valueToTree(incoming);
        if (!(incomingTree instanceof ObjectNode incomingObject)) {
            return existing;
        }
        JsonNode existingTree = source.valueToTree(existing);
        Set<String> blankObjects = new java.util.LinkedHashSet<>();
        ObjectNode patch = buildPatch(incomingObject,
                existingTree instanceof ObjectNode existingObject ? existingObject : null, columns, blankObjects);
        if (patch.isEmpty()) {
            return existing;
        }
        resolveRejectedBlanks(existing, patch, blankObjects);
        return apply(existing, patch);
    }

    /**
     * Some models reject null for individual properties (for example a coordinate's latitude). When
     * every imported column under an object was blank and one of its properties rejects null, the
     * object is cleared as a whole instead. Trial merges run on a copy so the existing document is
     * only changed once, by the final patch.
     */
    private <T> void resolveRejectedBlanks(T existing, ObjectNode patch, Set<String> blankObjects) {
        if (blankObjects.isEmpty()) {
            return;
        }
        while (!blankObjects.isEmpty()) {
            Object trial;
            try {
                trial = source.convertValue(existing, existing.getClass());
            } catch (IllegalArgumentException e) {
                return; // cannot copy this type; apply the patch as built
            }
            try {
                apply(trial, patch);
                return;
            } catch (IllegalArgumentException e) {
                if (!(e.getCause() instanceof JsonMappingException mapping)) {
                    return;
                }
                String failing = fieldPath(mapping);
                String clear = blankObjects.stream()
                        .filter(blank -> failing.equals(blank) || failing.startsWith(blank + "."))
                        .max(java.util.Comparator.comparingInt(String::length))
                        .orElse(null);
                if (clear == null) {
                    return; // not a blank object; the final apply reports the error
                }
                putNull(patch, clear.split("\\."));
                blankObjects.remove(clear);
            }
        }
    }

    private <T> T apply(T existing, ObjectNode patch) {
        try {
            return deepMerge.readerForUpdating(existing).readValue(patch);
        } catch (InvalidDefinitionException e) {
            // Some property types (for example polymorphic ones) cannot be merged; replace the
            // imported top-level properties instead of merging into them.
            try {
                return shallowMerge.readerForUpdating(existing).readValue(patch);
            } catch (IOException inner) {
                throw new IllegalArgumentException("Unable to apply imported values: " + inner.getMessage(), inner);
            }
        } catch (JsonMappingException e) {
            String path = fieldPath(e);
            throw new IllegalArgumentException("Unable to apply imported values to "
                    + (path.isEmpty() ? "the record" : "'" + path + "'") + ": " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to apply imported values: " + e.getMessage(), e);
        }
    }

    /**
     * Builds the JSON patch holding the imported values: each imported property path is copied
     * from the imported record, with a blank (missing or null) value as an explicit null. When the
     * imported record has no object on a path where the existing document has one (every imported
     * column under it was blank), the path is recorded in {@code blankObjects}.
     */
    static ObjectNode buildPatch(ObjectNode incoming, ObjectNode existing, Collection<String> paths, Set<String> blankObjects) {
        Set<String> imported = new java.util.LinkedHashSet<>();
        for (String column : paths) {
            String path = normalize(column);
            if (!path.isEmpty() && !PROTECTED_ROOTS.contains(path.split("\\.")[0])) {
                imported.add(path);
            }
        }
        ObjectNode patch = JsonNodeFactory.instance.objectNode();
        for (String path : imported) {
            copyPath(incoming, existing, patch, path.split("\\."), blankObjects);
        }
        return patch;
    }

    /** Drops list indexes ({@code tags[0]} becomes {@code tags}) and surrounding whitespace. */
    static String normalize(String column) {
        if (column == null) {
            return "";
        }
        String path = column.trim();
        int bracket = path.indexOf('[');
        if (bracket >= 0) {
            path = path.substring(0, bracket);
        }
        while (path.endsWith(".")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static void copyPath(ObjectNode from, ObjectNode existing, ObjectNode to, String[] segments, Set<String> blankObjects) {
        JsonNode sourceNode = from;
        JsonNode existingNode = existing;
        ObjectNode target = to;
        String prefix = "";
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            String path = prefix.isEmpty() ? segment : prefix + "." + segment;
            JsonNode child = sourceNode == null ? null : sourceNode.get(segment);
            JsonNode existingChild = existingNode == null ? null : existingNode.get(segment);
            JsonNode patchChild = target.get(segment);
            if (i == segments.length - 1) {
                target.set(segment, child == null ? NullNode.getInstance() : child.deepCopy());
                return;
            }
            if (patchChild != null && patchChild.isNull()) {
                return; // already cleared as a whole
            }
            if (child == null || child.isNull()) {
                // The imported row has no value under this object.
                if (existingChild == null || !existingChild.isObject()) {
                    target.putNull(segment);
                    return;
                }
                blankObjects.add(path);
                target = patchChild instanceof ObjectNode objectChild ? objectChild : target.putObject(segment);
                sourceNode = null;
                existingNode = existingChild;
                prefix = path;
                continue;
            }
            if (!child.isObject()) {
                target.set(segment, child.deepCopy());
                return;
            }
            target = patchChild instanceof ObjectNode objectChild ? objectChild : target.putObject(segment);
            sourceNode = child;
            existingNode = existingChild;
            prefix = path;
        }
    }

    private static void putNull(ObjectNode patch, String[] segments) {
        ObjectNode target = patch;
        for (int i = 0; i < segments.length - 1; i++) {
            JsonNode child = target.get(segments[i]);
            if (!(child instanceof ObjectNode objectChild)) {
                return;
            }
            target = objectChild;
        }
        target.putNull(segments[segments.length - 1]);
    }

    /** The dotted property path a mapping failure occurred at, or empty if unknown. */
    private static String fieldPath(JsonMappingException e) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : e.getPath()) {
            if (reference.getFieldName() != null) {
                if (path.length() > 0) {
                    path.append('.');
                }
                path.append(reference.getFieldName());
            }
        }
        return path.toString();
    }
}
