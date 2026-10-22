package com.e2eq.framework.model.persistent.morphia;

import dev.morphia.MorphiaDatastore;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import com.end2endlogic.quantum.extension.QuarkusMorphiaConfig;
import io.smallrye.config.SmallRyeConfig;
import jakarta.inject.Named;

import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.function.Function;

/**
 * Creates declared indexes for the Morphia extension's named datastores
 * ({@code quarkus.morphia.mapper-configs.<client>.create-indexes=true}).
 *
 * <p>{@link DeclaredIndexConfigInterceptor} turns the extension's own index creation off for every
 * datastore, because Morphia's {@code ensureIndexes} ignores {@code @ExcludeInheritedIndexes}. The
 * default datastore and realm datastores get the exclusion-aware pass from
 * {@link MorphiaDataStoreWrapper}; this bean gives each named datastore that requested indexes the
 * same pass at startup.</p>
 */
@ApplicationScoped
public class NamedDatastoreDeclaredIndexes {

    @Inject
    Config config;

    @Inject
    @Any
    Instance<MorphiaDatastore> datastores;

    void onStart(@Observes StartupEvent event) {
        // Enumerate the actual beans: a named client can inherit the default mapper configuration
        // without having any quarkus.morphia.mapper-configs.<client> properties of its own.
        Collection<String> clients = new LinkedHashSet<>();
        for (Instance.Handle<MorphiaDatastore> handle : datastores.handles()) {
            handle.getBean().getQualifiers().stream().filter(Named.class::isInstance)
                    .map(Named.class::cast).map(Named::value).forEach(clients::add);
        }
        apply(config, clients, client -> {
            Instance<MorphiaDatastore> named = datastores.select(NamedLiteral.of(client));
            return named.isResolvable() ? Optional.of(named.get()) : Optional.empty();
        });
    }

    /**
     * Applies the declared indexes to each named datastore whose configuration requested them.
     *
     * @param config the application configuration (with {@link DeclaredIndexConfigInterceptor})
     * @param datastores finds a client's datastore; empty if the application has none for it
     * @return the clients whose datastores were indexed
     */
    public static List<String> apply(Config config, Collection<String> clients,
            Function<String, Optional<MorphiaDatastore>> datastores) {
        QuarkusMorphiaConfig mapping = config.unwrap(SmallRyeConfig.class).getConfigMapping(QuarkusMorphiaConfig.class);
        List<String> indexed = new ArrayList<>();
        for (String client : new LinkedHashSet<>(clients)) {
            String requested = mapping.mapperConfigs().containsKey(client)
                    ? DeclaredIndexConfigInterceptor.requestedFor(client)
                    : DeclaredIndexConfigInterceptor.REQUESTED;
            if (!config.getOptionalValue(requested,
                    Boolean.class).orElse(false)) {
                continue;
            }
            Optional<MorphiaDatastore> datastore = datastores.apply(client);
            if (datastore.isEmpty()) {
                Log.debugf("No Morphia datastore is defined for client %s; no indexes to create", client);
                continue;
            }
            DeclaredIndexes.ensureAll(datastore.get());
            indexed.add(client);
        }
        return indexed;
    }

}
