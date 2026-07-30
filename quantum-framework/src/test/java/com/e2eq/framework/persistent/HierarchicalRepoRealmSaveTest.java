package com.e2eq.framework.persistent;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.general.MenuHierarchyModel;
import com.e2eq.framework.model.persistent.morphia.MenuHierarchyRepo;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.security.runtime.SecuritySession;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

@QuarkusTest
public class HierarchicalRepoRealmSaveTest extends BaseRepoTest {

    @Inject
    MenuHierarchyRepo menuHierarchyRepo;

    /**
     * Ambient security context stays on the system realm while saves target the test realm,
     * proving {@code save(realmId, model)} maintains descendants in the explicit realm.
     */
    @Test
    public void saveWithExplicitRealmMaintainsDescendantsAndReparenting() throws ReferentialIntegrityViolationException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String testRealm = testUtils.getTestRealm();
        Assertions.assertNotEquals(testUtils.getSystemRealm(), testRealm,
                "test realm must differ from system realm for isolation");

        PrincipalContext ambientSystemContext =
                testUtils.getSystemPrincipalContext(testUtils.getSystemUserId(), roles);

        MenuHierarchyModel root = null;
        MenuHierarchyModel otherRoot = null;
        MenuHierarchyModel child = null;

        try (final SecuritySession ignored = new SecuritySession(ambientSystemContext, rContext)) {
            try {
                root = new MenuHierarchyModel();
                root.setRefName("realm-root-" + suffix);
                root.setDisplayName("Realm Root " + suffix);
                root = menuHierarchyRepo.save(testRealm, root);

                otherRoot = new MenuHierarchyModel();
                otherRoot.setRefName("realm-other-root-" + suffix);
                otherRoot.setDisplayName("Realm Other Root " + suffix);
                otherRoot = menuHierarchyRepo.save(testRealm, otherRoot);

                child = new MenuHierarchyModel();
                child.setRefName("realm-child-" + suffix);
                child.setDisplayName("Realm Child " + suffix);
                child.setParent(root.createEntityReference());
                child = menuHierarchyRepo.save(testRealm, child);

                Optional<MenuHierarchyModel> rootInTestRealm =
                        menuHierarchyRepo.findById(root.getId(), testRealm);
                Assertions.assertTrue(rootInTestRealm.isPresent(), "root must exist in test realm");
                Assertions.assertNotNull(rootInTestRealm.get().getDescendants(),
                        "root descendants must be initialized");
                Assertions.assertTrue(
                        rootInTestRealm.get().getDescendants().contains(child.getId()),
                        "root in test realm must list the child after explicit-realm save");

                // Ambient (system) realm must not hold these nodes
                Assertions.assertTrue(
                        menuHierarchyRepo.findById(root.getId(), testUtils.getSystemRealm()).isEmpty(),
                        "root must not exist in ambient system realm");

                // Reparent child from root -> otherRoot via explicit-realm save
                child.setParent(otherRoot.createEntityReference());
                child = menuHierarchyRepo.save(testRealm, child);

                Optional<MenuHierarchyModel> oldParent =
                        menuHierarchyRepo.findById(root.getId(), testRealm);
                Optional<MenuHierarchyModel> newParent =
                        menuHierarchyRepo.findById(otherRoot.getId(), testRealm);
                Assertions.assertTrue(oldParent.isPresent());
                Assertions.assertTrue(newParent.isPresent());

                ObjectId childId = child.getId();
                Assertions.assertFalse(
                        oldParent.get().getDescendants() != null
                                && oldParent.get().getDescendants().contains(childId),
                        "old parent must no longer list the child after reparent");
                Assertions.assertTrue(
                        newParent.get().getDescendants() != null
                                && newParent.get().getDescendants().contains(childId),
                        "new parent must list the child after reparent");
            } finally {
                deleteInRealmSafe(testRealm, child);
                deleteInRealmSafe(testRealm, otherRoot);
                deleteInRealmSafe(testRealm, root);
            }
        }
    }

    private void deleteInRealmSafe(String realmId, MenuHierarchyModel node)
            throws ReferentialIntegrityViolationException {
        if (node != null && node.getId() != null) {
            menuHierarchyRepo.delete(realmId, node.getId());
        }
    }
}
