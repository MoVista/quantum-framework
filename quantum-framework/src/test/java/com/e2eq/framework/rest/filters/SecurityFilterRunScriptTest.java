package com.e2eq.framework.rest.filters;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SecurityFilter.runScript constant fast-path.
 *
 * The fast-path is a new allow/deny decision on the impersonation path and must
 * stay an exact match after trim(); a contains/startsWith refactor would turn
 * scripts such as {@code subject == 'x' ? true : false} into an unconditional allow.
 *
 * Scripting is disabled for these tests so fall-through cases never create a Graal
 * context (deterministic, and the actual outage the fast-path fixes).
 */
public class SecurityFilterRunScriptTest {

    private static final String SCRIPTING_ENABLED_PROP = "quantum.security.scripting.enabled";

    private String previousScriptingEnabled;

    @BeforeEach
    public void disableScripting() {
        previousScriptingEnabled = System.getProperty(SCRIPTING_ENABLED_PROP);
        System.setProperty(SCRIPTING_ENABLED_PROP, "false");
    }

    @AfterEach
    public void restoreScripting() {
        if (previousScriptingEnabled == null) {
            System.clearProperty(SCRIPTING_ENABLED_PROP);
        } else {
            System.setProperty(SCRIPTING_ENABLED_PROP, previousScriptingEnabled);
        }
    }

    @Test
    public void constantTrueAllows() throws Exception {
        assertTrue(runScript("true"));
    }

    @Test
    public void constantFalseDenies() throws Exception {
        assertFalse(runScript("false"));
    }

    @Test
    public void trimAndCaseAreHonoured() throws Exception {
        assertTrue(runScript("  true  "));
        assertTrue(runScript("TRUE"));
        assertFalse(runScript(" False\n"));
    }

    @Test
    public void constantsHonouredWhenScriptingDisabled() throws Exception {
        SecurityFilter filter = newFilterWithScriptingDisabled();
        assertTrue(invokeRunScript(filter, "true"),
                "constant true must allow even when quantum.security.scripting.enabled=false");
        assertFalse(invokeRunScript(filter, "false"),
                "constant false must deny even when quantum.security.scripting.enabled=false");
    }

    @Test
    public void expressionsAreNotFastPathedToAllow() throws Exception {
        assertFalse(runScript("true && false"),
                "boolean expression must not match the constant true fast-path");
        assertFalse(runScript("truex"),
                "prefix of true must not match the constant true fast-path");
        assertFalse(runScript("subject == 'x' ? true : false"),
                "conditional that would allow if evaluated must not be fast-pathed to allow");
    }

    private static boolean runScript(String script) throws Exception {
        return invokeRunScript(newFilterWithScriptingDisabled(), script);
    }

    private static SecurityFilter newFilterWithScriptingDisabled() throws Exception {
        SecurityFilter filter = new SecurityFilter();
        Field enabled = SecurityFilter.class.getDeclaredField("scriptingEnabled");
        enabled.setAccessible(true);
        enabled.setBoolean(filter, false);
        return filter;
    }

    private static boolean invokeRunScript(SecurityFilter filter, String script) throws Exception {
        Method m = SecurityFilter.class.getDeclaredMethod(
                "runScript", String.class, String.class, String.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(filter, "subject-x", "user-x", "realm-x", script);
    }
}
