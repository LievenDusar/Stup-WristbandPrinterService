package com.stup.wristbandprinter.cluster;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.List;

/** Binds `cluster.printers[*]` from configuration. Management-only. */
@ConfigurationProperties(prefix = "cluster")
@Profile("!worker")
public class PrinterRegistryProperties {

    private List<Entry> printers = new ArrayList<>();

    public List<Entry> getPrinters() { return printers; }
    public void setPrinters(List<Entry> printers) { this.printers = printers; }

    /** Mutable holder for binding a single printer entry. */
    public static class Entry {
        private String id;
        private String displayName;
        private String baseUrl;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }
}
