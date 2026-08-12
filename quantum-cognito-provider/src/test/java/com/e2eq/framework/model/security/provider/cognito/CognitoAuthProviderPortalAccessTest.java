package com.e2eq.framework.model.security.provider.cognito;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitoAuthProviderPortalAccessTest {

    @Test
    void emptyRolesAreMobileOnly() {
        assertFalse(CognitoAuthProvider.rolesGrantPortalAccess(Collections.emptySet()));
        assertFalse(CognitoAuthProvider.rolesGrantPortalAccess(null));
    }

    @Test
    void portalAssociateGrantsPortalAccess() {
        assertTrue(CognitoAuthProvider.rolesGrantPortalAccess(Set.of("user", "portal-associate")));
    }

    @Test
    void adminAndSystemGrantPortalAccess() {
        assertTrue(CognitoAuthProvider.rolesGrantPortalAccess(Set.of("admin")));
        assertTrue(CognitoAuthProvider.rolesGrantPortalAccess(Set.of("system")));
    }

    @Test
    void userAloneDoesNotGrantPortalAccess() {
        assertFalse(CognitoAuthProvider.rolesGrantPortalAccess(Set.of("user")));
    }
}
