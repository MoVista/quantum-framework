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
}
