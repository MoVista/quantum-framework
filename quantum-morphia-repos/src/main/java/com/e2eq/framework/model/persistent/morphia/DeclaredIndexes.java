package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.ExcludeInheritedIndexes;
import com.mongodb.client.MongoCollection;
import dev.morphia.MorphiaDatastore;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.Indexed;
import dev.morphia.annotations.Indexes;
import dev.morphia.annotations.internal.IndexHelper;
import dev.morphia.mapping.codec.pojo.EntityModel;
import dev.morphia.mapping.codec.pojo.PropertyModel;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Applies and lists the indexes an entity declares, honoring {@link ExcludeInheritedIndexes}.
 *
 * <p>Every framework operation that creates declared indexes goes through {@link #ensure}, so an
 * entity that leaves out an inherited index never gets it from any path. An entity without the
 * annotation is handled by Morphia's own {@code ensureIndexes}, unchanged.</p>
 */
public final class DeclaredIndexes {

    private DeclaredIndexes() {
    }

    /**
     * Creates the indexes an entity declares in its collection, leaving out the inherited indexes
     * it excludes. Creating an index that already exists with the same name and definition is a
     * no-op.
     *
     * @param datastore the realm's datastore
     * @param type the entity type
     */
    public static void ensure(MorphiaDatastore datastore, Class<?> type) {
        Set<String> excluded = excludedNames(type);
        if (excluded.isEmpty()) {
            datastore.ensureIndexes(type);
            return;
        }
        EntityModel entity = datastore.getMapper().getEntityModel(type);
        IndexHelper indexHelper = new IndexHelper(datastore.getMapper());
        MongoCollection<Document> collection =
                datastore.getDatabase().getCollection(entity.collectionName());
        for (Index index : declared(indexHelper, entity)) {
            if (!excluded.contains(index.options().name())) {
                collection.createIndex(indexHelper.calculateKeys(entity, index), indexHelper.convert(index.options()));
            }
        }
    }

    /**
     * Applies {@link #ensure} to every concrete mapped entity of a datastore.
     *
     * @param datastore the realm's datastore
     */
    public static void ensureAll(MorphiaDatastore datastore) {
        for (EntityModel entity : new ArrayList<>(datastore.getMapper().getMappedEntities())) {
            if (isIndexable(entity)) {
                ensure(datastore, entity.getType());
            }
        }
    }

    /**
     * Applies {@link #ensure} to every concrete mapped entity of a datastore without failing: each
     * entity is attempted, and failures are logged and returned. Automatic index creation when a
     * datastore is created uses this, because it runs before migrations. Data that a pending
     * migration must repair (for example duplicate references that block a new unique index) must
     * not make the realm unreachable or stop that migration from running. The post-migration index
     * step ({@code MigrationService.applyAllIndexes}) remains authoritative and fails on the same
     * error.
     *
     * @param datastore the datastore to index
     * @return one message per entity whose indexes could not be applied; empty on success
     */
    public static List<String> ensureAllTolerating(MorphiaDatastore datastore) {
        List<String> failures = new ArrayList<>();
        for (EntityModel entity : new ArrayList<>(datastore.getMapper().getMappedEntities())) {
            if (!isIndexable(entity)) {
                continue;
            }
            try {
                ensure(datastore, entity.getType());
            } catch (RuntimeException e) {
                failures.add(String.format("%s (%s): %s", entity.collectionName(), entity.getType().getSimpleName(),
                        e.getMessage()));
            }
        }
        if (!failures.isEmpty()) {
            io.quarkus.logging.Log.errorf("Automatic index creation failed for %d collection(s) in %s; the next "
                    + "migration call retries them and reports the failure. %s", failures.size(),
                    datastore.getDatabase().getName(), String.join("; ", failures));
        }
        return failures;
    }

    /**
     * Whether an entity model gets indexes: a concrete {@code @Entity} with an id.
     *
     * @param entity the entity model
     * @return true if indexes apply
     */
    public static boolean isIndexable(EntityModel entity) {
        return entity.getEntityAnnotation() != null && !entity.isAbstract() && !entity.isInterface()
                && entity.getIdProperty() != null;
    }

    /**
     * Names of the indexes an entity gets: those declared through {@code @Indexes} (including
     * superclasses, less the ones it excludes) and {@code @Indexed}, using MongoDB's generated name
     * where none is declared.
     *
     * @param indexHelper Morphia's index helper for the entity's mapper
     * @param entity the entity model
     * @return the index names
     */
    public static Set<String> names(IndexHelper indexHelper, EntityModel entity) {
        Set<String> excluded = excludedNames(entity.getType());
        Set<String> names = new TreeSet<>();
        for (Index index : declared(indexHelper, entity)) {
            String name = indexName(index.options().name(), indexHelper.calculateKeys(entity, index));
            if (!excluded.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Inherited index names the type leaves out.
     *
     * @param type the entity type
     * @return the excluded names; empty when the type does not exclude any
     */
    public static Set<String> excludedNames(Class<?> type) {
        ExcludeInheritedIndexes exclusion = type.getAnnotation(ExcludeInheritedIndexes.class);
        if (exclusion == null) {
            return Collections.emptySet();
        }
        Set<String> inherited = new LinkedHashSet<>();
        for (Class<?> superclass = type.getSuperclass(); superclass != null; superclass = superclass.getSuperclass()) {
            Indexes indexes = superclass.getAnnotation(Indexes.class);
            if (indexes != null) {
                for (Index index : indexes.value()) {
                    inherited.add(index.options().name());
                }
            }
        }
        Set<String> excluded = new LinkedHashSet<>();
        for (String name : exclusion.value()) {
            if (!inherited.contains(name)) {
                throw new IllegalStateException(type.getName() + " excludes index " + name
                        + ", which none of its superclasses declares");
            }
            excluded.add(name);
        }
        return excluded;
    }

    /** MongoDB's default index name joins each key and its direction or type with underscores. */
    public static String indexName(String declaredName, Document keys) {
        if (declaredName != null && !declaredName.isBlank()) {
            return declaredName;
        }
        StringBuilder name = new StringBuilder();
        for (Map.Entry<String, Object> key : keys.entrySet()) {
            if (name.length() > 0) {
                name.append('_');
            }
            name.append(key.getKey()).append('_').append(key.getValue());
        }
        return name.toString();
    }

    /** The {@code @Indexes} entries of the entity and its superclasses, then its {@code @Indexed} properties. */
    private static List<Index> declared(IndexHelper indexHelper, EntityModel entity) {
        List<Index> declared = new ArrayList<>();
        for (EntityModel model = entity; model != null; model = model.getSuperClass()) {
            Indexes indexes = model.getAnnotation(Indexes.class);
            if (indexes != null) {
                Collections.addAll(declared, indexes.value());
            }
        }
        for (PropertyModel property : entity.getProperties()) {
            Indexed indexed = property.getAnnotation(Indexed.class);
            if (indexed != null) {
                Index index = indexHelper.convert(indexed, property.getMappedName());
                if (index != null) {
                    declared.add(index);
                }
            }
        }
        return declared;
    }
}
