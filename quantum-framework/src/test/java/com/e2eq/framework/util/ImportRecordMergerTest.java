package com.e2eq.framework.csv;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportRecordMergerTest {

    public static class Address {
        public String street;
        public String city;
        public Coord coordinates;
    }

    /** Like the framework's Coordinate, whose setters reject null. */
    public static class Coord {
        private Double latitude;
        private Double longitude;

        public Double getLatitude() { return latitude; }
        public void setLatitude(Double latitude) { this.latitude = java.util.Objects.requireNonNull(latitude); }
        public Double getLongitude() { return longitude; }
        public void setLongitude(Double longitude) { this.longitude = java.util.Objects.requireNonNull(longitude); }
    }

    public static class Item {
        public String id;
        public String refName;
        public Long version;
        public String displayName;
        public String notes;
        public int count;
        public Address address;
        public List<String> tags = new ArrayList<>();
        public Map<String, Object> dataDomain;
        public Map<String, String> attributes;
        public Map<String, Address> places;
        public Map<String, Object> settings;
    }

    private final ImportRecordMerger merger = new ImportRecordMerger(new ObjectMapper());

    private static Item existing() {
        Item item = new Item();
        item.id = "abc123";
        item.refName = "Item-A";
        item.version = 4L;
        item.displayName = "Original";
        item.notes = "keep";
        item.count = 9;
        item.address = new Address();
        item.address.street = "1 Main St";
        item.address.city = "Springfield";
        item.tags = new ArrayList<>(List.of("one", "two"));
        item.dataDomain = Map.of("ownerId", "owner@a.example");
        item.attributes = new java.util.LinkedHashMap<>(Map.of("plan", "basic", "region", "east"));
        Address home = new Address();
        home.street = "1 Main St";
        home.city = "Springfield";
        item.places = new java.util.LinkedHashMap<>(Map.of("home", home));
        item.settings = new java.util.LinkedHashMap<>(Map.of("theme", "dark",
                "limits", new java.util.LinkedHashMap<>(Map.of("daily", 5, "monthly", 50))));
        return item;
    }

    @Test
    void copiesOnlyListedColumnsAndKeepsIdentity() {
        Item incoming = new Item();
        incoming.id = "other";
        incoming.refName = "item-a";
        incoming.version = 0L;
        incoming.displayName = "Updated";
        incoming.notes = "should not be copied";

        Item merged = merger.merge(existing(), incoming, List.of("refName", "displayName"));

        assertEquals("abc123", merged.id);
        assertEquals("Item-A", merged.refName);
        assertEquals(4L, merged.version);
        assertEquals("Updated", merged.displayName);
        assertEquals("keep", merged.notes);
        assertEquals(9, merged.count);
    }

    @Test
    void nestedColumnChangesOnlyThatProperty() {
        Item incoming = new Item();
        incoming.address = new Address();
        incoming.address.city = "Shelbyville";

        Item merged = merger.merge(existing(), incoming, List.of("address.city"));

        assertEquals("1 Main St", merged.address.street);
        assertEquals("Shelbyville", merged.address.city);
    }

    @Test
    void blankListedColumnClearsTheProperty() {
        Item incoming = new Item();

        Item merged = merger.merge(existing(), incoming, List.of("displayName", "address.city"));

        assertNull(merged.displayName);
        assertNull(merged.address.city);
        assertEquals("1 Main St", merged.address.street);
    }

    @Test
    void indexedColumnReplacesTheWholeCollection() {
        Item incoming = new Item();
        incoming.tags = new ArrayList<>(List.of("three"));

        Item merged = merger.merge(existing(), incoming, List.of("tags[0]"));

        assertEquals(List.of("three"), merged.tags);
    }

    @Test
    void blankObjectIsClearedAsAWholeWhenItsPropertiesRejectNull() {
        Item current = existing();
        current.address.coordinates = new Coord();
        current.address.coordinates.setLatitude(33.4);
        current.address.coordinates.setLongitude(-81.9);
        Item incoming = new Item();
        incoming.address = new Address();
        incoming.address.city = "Augusta";

        Item merged = merger.merge(current, incoming,
                List.of("address.city", "address.coordinates.latitude", "address.coordinates.longitude"));

        assertNull(merged.address.coordinates);
        assertEquals("Augusta", merged.address.city);
        assertEquals("1 Main St", merged.address.street);
    }

    @Test
    void valueAModelCannotAcceptIsReportedWithItsPath() {
        Item current = existing();
        current.address.coordinates = new Coord();
        current.address.coordinates.setLatitude(33.4);
        current.address.coordinates.setLongitude(-81.9);
        Item incoming = new Item();
        incoming.address = new Address();
        incoming.address.coordinates = new Coord();
        incoming.address.coordinates.setLongitude(-80.0);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                merger.merge(current, incoming, List.of("address.coordinates.latitude")));
        assertTrue(failure.getMessage().contains("latitude"), failure.getMessage());
    }

    @Test
    void requiresTheImportedColumns() {
        Item incoming = new Item();
        incoming.displayName = "Updated";

        assertThrows(IllegalArgumentException.class, () -> merger.merge(existing(), incoming, null));
        assertThrows(IllegalArgumentException.class, () -> merger.merge(existing(), incoming, List.of()));
    }
    @Test
    void dataDomainIsProtectedIncludingNestedColumns() {
        Item incoming = new Item();
        incoming.dataDomain = Map.of("ownerId", "attacker", "tenantId", "other");
        for (String column : List.of("dataDomain", "dataDomain.ownerId", "dataDomain.tenantId")) {
            assertEquals(Map.of("ownerId", "owner@a.example"),
                    merger.merge(existing(), incoming, List.of(column)).dataDomain);
        }
    }

    @Test
    void inheritedTagsArrayIsReplacedRatherThanAppended() {
        com.e2eq.framework.test.CSVModel current = new com.e2eq.framework.test.CSVModel();
        current.setTags(new String[]{"old-tag"});
        com.e2eq.framework.test.CSVModel incoming = new com.e2eq.framework.test.CSVModel();
        incoming.setTags(new String[]{"new-tag"});
        merger.merge(current, incoming, List.of("tags[0]"));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new String[]{"new-tag"}, current.getTags());
        merger.merge(current, incoming, List.of("tags[0]"));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new String[]{"new-tag"}, current.getTags());
    }

    public static class ThreeCoordinates {
        public Coord first;
        public Coord second;
        public Coord third;
    }

    @Test
    void clearsAllThreeIndependentBlankObjects() {
        ThreeCoordinates current = new ThreeCoordinates();
        current.first = new Coord();
        current.second = new Coord();
        current.third = new Coord();
        for (Coord coord : List.of(current.first, current.second, current.third)) {
            coord.setLatitude(1.0);
            coord.setLongitude(2.0);
        }
        merger.merge(current, new ThreeCoordinates(), List.of(
                "first.latitude", "second.latitude", "third.latitude"));
        assertNull(current.first);
        assertNull(current.second);
        assertNull(current.third);
    }

    public static class ArrayItem {
        public int[] values;
    }

    @Test
    void otherArrayTypesAlsoReplace() {
        ArrayItem current = new ArrayItem();
        current.values = new int[]{1, 2};
        ArrayItem incoming = new ArrayItem();
        incoming.values = new int[]{3};
        merger.merge(current, incoming, List.of("values[0]"));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[]{3}, current.values);
    }

    @Test
    void mapKeyColumnChangesOnlyThatEntry() {
        Item incoming = new Item();
        incoming.attributes = Map.of("plan", "premium");

        Item merged = merger.merge(existing(), incoming, List.of("attributes.plan"));

        assertEquals(Map.of("plan", "premium", "region", "east"), merged.attributes);
    }

    @Test
    void blankMapKeyColumnRemovesOnlyThatEntry() {
        Item merged = merger.merge(existing(), new Item(), List.of("attributes.plan"));

        assertEquals(Map.of("region", "east"), merged.attributes);
    }

    @Test
    void mapColumnReplacesTheWholeMap() {
        Item incoming = new Item();
        incoming.attributes = Map.of("plan", "premium");

        Item merged = merger.merge(existing(), incoming, List.of("attributes"));

        assertEquals(Map.of("plan", "premium"), merged.attributes);
    }

    @Test
    void nestedColumnUnderAMapValueKeepsItsOtherProperties() {
        Item incoming = new Item();
        Address home = new Address();
        home.city = "Shelbyville";
        incoming.places = Map.of("home", home);

        Item merged = merger.merge(existing(), incoming, List.of("places.home.city"));

        assertEquals("Shelbyville", merged.places.get("home").city);
        assertEquals("1 Main St", merged.places.get("home").street);
    }

    @Test
    void nestedUntypedMapKeepsItsOtherEntries() {
        Item incoming = new Item();
        incoming.settings = Map.of("limits", Map.of("daily", 10));

        Item merged = merger.merge(existing(), incoming, List.of("settings.limits.daily"));

        assertEquals("dark", merged.settings.get("theme"));
        assertEquals(Map.of("daily", 10, "monthly", 50), merged.settings.get("limits"));
    }

    @Test
    void valuesOnlyASetterReceivesAreReportedAndNotAppliedAsBlank() {
        com.e2eq.framework.model.security.CredentialUserIdPassword existing =
                new com.e2eq.framework.model.security.CredentialUserIdPassword();
        existing.setPassword("original-password");
        String originalHash = existing.getPasswordHash();
        com.e2eq.framework.model.security.CredentialUserIdPassword incoming =
                new com.e2eq.framework.model.security.CredentialUserIdPassword();
        incoming.setPassword("replacement-password");
        incoming.setDescription("Updated");

        assertEquals(java.util.Set.of("password"), merger.uncarriedColumns(
                com.e2eq.framework.model.security.CredentialUserIdPassword.class,
                List.of("refName", "password", "description")));
        var merged = merger.merge(existing, incoming, List.of("password", "description"));

        assertEquals("Updated", merged.getDescription());
        assertEquals(originalHash, merged.getPasswordHash(), "the caller applies or rejects the password");
    }
    public static class SecretAddress extends Address {
        @com.fasterxml.jackson.annotation.JsonIgnore
        public String secret;
    }

    @Test
    void partialMapsKeepHiddenValuesAndUntouchedRuntimeTypes() {
        Item current = existing();
        SecretAddress home = new SecretAddress();
        home.city = "Original";
        home.secret = "hidden";
        current.places.put("home", home);
        java.util.Date created = new java.util.Date(1700000000000L);
        current.settings.put("created", created);
        Item incoming = new Item();
        Address changed = new Address();
        changed.city = "Changed";
        incoming.places = Map.of("home", changed);
        incoming.settings = Map.of("plan", "premium");

        merger.merge(current, incoming, List.of("places.home.city", "settings.plan"));

        assertEquals("Changed", current.places.get("home").city);
        org.junit.jupiter.api.Assertions.assertSame(home, current.places.get("home"));
        assertEquals("hidden", ((SecretAddress) current.places.get("home")).secret);
        org.junit.jupiter.api.Assertions.assertSame(created, current.settings.get("created"));
    }

    @Test
    void partialMapUpdatesSupportImmutableMapsAndNestedBlankEntries() {
        Item current = existing();
        current.settings = Map.of("limits", Map.of("daily", 5, "monthly", 50), "theme", "dark");
        Item incoming = new Item();
        incoming.settings = Map.of("limits", Map.of("monthly", 100));

        merger.merge(current, incoming, List.of("settings.limits.daily", "settings.limits.monthly"));

        assertEquals(Map.of("limits", Map.of("monthly", 100), "theme", "dark"), current.settings);
    }

    @Test
    void partialUntypedMapsCanReplaceAScalarWithAnObject() {
        Item current = existing();
        current.settings = Map.of("limits", "legacy", "theme", "dark");
        Item incoming = new Item();
        incoming.settings = Map.of("limits", Map.of("daily", 10));

        merger.merge(current, incoming, List.of("settings.limits.daily"));

        assertEquals(Map.of("limits", Map.of("daily", 10), "theme", "dark"), current.settings);
    }

}
