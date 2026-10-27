package com.e2eq.framework.model.persistent.base;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Leaves indexes that a superclass declares off this entity's collection.
 *
 * <p>Morphia applies every {@code @Indexes} entry of an entity's superclasses to the entity's
 * collection. An entity whose collection does not follow a superclass's identity rules (for example
 * a derived cache that identifies its documents by other fields) names the inherited indexes it
 * leaves out. The framework's index operations ({@code DeclaredIndexes}) honor this annotation;
 * Morphia's own {@code ensureIndexes} does not, so index creation must go through the framework.</p>
 *
 * <p>Only indexes declared by a superclass can be excluded. The entity's own declarations are
 * always applied.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ExcludeInheritedIndexes {

    /**
     * Names of the inherited indexes to leave out, as declared in {@code @IndexOptions(name = ...)}.
     *
     * @return the excluded index names
     */
    String[] value();
}
