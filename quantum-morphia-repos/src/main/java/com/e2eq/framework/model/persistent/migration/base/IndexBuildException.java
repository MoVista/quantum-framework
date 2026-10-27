package com.e2eq.framework.model.persistent.migration.base;

import java.util.List;

/**
 * Thrown when one or more declared indexes could not be built in a realm. Every mapped entity is
 * attempted before this is thrown, so the message lists all failures from one run.
 */
public class IndexBuildException extends RuntimeException {

    private final String realm;
    private final List<String> failures;

    /**
     * @param realm the realm whose indexes were being applied
     * @param failures one description per failing entity, naming its collection and the error
     */
    public IndexBuildException(String realm, List<String> failures) {
        super(String.format("Failed to apply declared indexes in realm %s for %d collection(s): %s",
                realm, failures.size(), String.join("; ", failures)));
        this.realm = realm;
        this.failures = List.copyOf(failures);
    }

    /**
     * @return the realm whose indexes were being applied
     */
    public String getRealm() {
        return realm;
    }

    /**
     * @return one description per failing entity
     */
    public List<String> getFailures() {
        return failures;
    }
}
