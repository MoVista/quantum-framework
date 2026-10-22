package com.e2eq.framework.model.persistent.morphia;

import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;

/**
 * The bundled Morphia extension creates indexes inside its datastore constructor and again while
 * mapping entities. Disable those ordinary Morphia passes before config mapping occurs, retaining
 * each requested setting for the framework's exclusion-aware index pass: the default datastore's
 * under {@link #REQUESTED}, and a named datastore's under {@link #requestedFor(String)}.
 */
public final class DeclaredIndexConfigInterceptor implements ConfigSourceInterceptor {
    public static final String REQUESTED = "quantum.morphia.declared-indexes-on-create";
    private static final String AUTOMATIC = "quarkus.morphia.create-indexes";
    private static final String MORPHIA_PREFIX = "quarkus.morphia.mapper-configs.";
    private static final String AUTOMATIC_SUFFIX = ".create-indexes";
    private static final String REQUESTED_PREFIX = "quantum.morphia.mapper-configs.";
    private static final String REQUESTED_SUFFIX = ".declared-indexes-on-create";

    /**
     * The property holding the index setting a named datastore requested with
     * {@code quarkus.morphia.mapper-configs.<client>.create-indexes}.
     *
     * @param client the Mongo client name, as it appears in the property name
     * @return the property name
     */
    public static String requestedFor(String client) {
        return REQUESTED_PREFIX + client + REQUESTED_SUFFIX;
    }

    /**
     * The client name of a named datastore's {@code create-indexes} property, or null if the
     * property is not one.
     *
     * @param property a configuration property name
     * @return the client name, as it appears in the property name, or null
     */
    public static String namedClient(String property) {
        return between(property, MORPHIA_PREFIX, AUTOMATIC_SUFFIX);
    }

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        if (REQUESTED.equals(name)) {
            return requested(context, AUTOMATIC, name);
        }
        String requestingClient = between(name, REQUESTED_PREFIX, REQUESTED_SUFFIX);
        if (requestingClient != null) {
            return requested(context, MORPHIA_PREFIX + requestingClient + AUTOMATIC_SUFFIX, name);
        }
        ConfigValue value = context.proceed(name);
        if (AUTOMATIC.equals(name) || namedClient(name) != null) {
            return value == null ? ConfigValue.builder().withName(name).withValue("false").build()
                    : value.withValue("false");
        }
        return value;
    }

    private static ConfigValue requested(ConfigSourceInterceptorContext context, String automatic, String name) {
        ConfigValue requested = context.proceed(automatic);
        String client = namedClient(automatic);
        if (requested == null && client != null && !client.startsWith("\"")) {
            requested = context.proceed(MORPHIA_PREFIX + '"' + client + '"' + AUTOMATIC_SUFFIX);
        }
        return requested == null ? ConfigValue.builder().withName(name).withValue("false").build()
                : requested.withName(name);
    }

    private static String between(String name, String prefix, String suffix) {
        if (name.length() > prefix.length() + suffix.length() && name.startsWith(prefix) && name.endsWith(suffix)) {
            return name.substring(prefix.length(), name.length() - suffix.length());
        }
        return null;
    }
}
