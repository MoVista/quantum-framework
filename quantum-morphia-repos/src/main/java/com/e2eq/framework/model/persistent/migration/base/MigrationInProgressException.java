package com.e2eq.framework.model.persistent.migration.base;

/**
 * Thrown when a migration call cannot acquire a realm's migration lock because another call is
 * still migrating that realm. The second call fails instead of reporting success while the first
 * one is still running.
 */
public class MigrationInProgressException extends RuntimeException {

    private final String realm;

    /**
     * @param realm the realm whose migration lock is held
     */
    public MigrationInProgressException(String realm) {
        super(String.format("A migration is already running for realm %s; retry after it completes.", realm));
        this.realm = realm;
    }

    /**
     * @return the realm whose migration lock is held
     */
    public String getRealm() {
        return realm;
    }
}
