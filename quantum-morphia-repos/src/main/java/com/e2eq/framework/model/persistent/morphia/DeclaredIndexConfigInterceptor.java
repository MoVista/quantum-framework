package com.e2eq.framework.model.persistent.morphia;

import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;

/**
 * The bundled Morphia extension creates indexes inside its datastore constructor and again while
 * mapping entities. Disable those ordinary Morphia passes before config mapping occurs, retaining
 * the requested default setting for the framework's exclusion-aware index pass.
 */
public final class DeclaredIndexConfigInterceptor implements ConfigSourceInterceptor {
    public static final String REQUESTED = "quantum.morphia.declared-indexes-on-create";
    private static final String AUTOMATIC = "quarkus.morphia.create-indexes";

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        if (REQUESTED.equals(name)) {
            ConfigValue requested = context.proceed(AUTOMATIC);
            return requested == null ? ConfigValue.builder().withName(name).withValue("false").build()
                    : requested.withName(name);
        }
        ConfigValue value = context.proceed(name);
        if (AUTOMATIC.equals(name) || (name.startsWith("quarkus.morphia.") && name.endsWith(".create-indexes"))) {
            return value == null ? ConfigValue.builder().withName(name).withValue("false").build()
                    : value.withValue("false");
        }
        return value;
    }
}
