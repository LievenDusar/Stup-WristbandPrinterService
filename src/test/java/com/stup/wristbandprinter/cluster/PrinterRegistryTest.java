package com.stup.wristbandprinter.cluster;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrinterRegistryTest {

    private static PrinterRegistryProperties props(PrinterRegistryProperties.Entry... entries) {
        PrinterRegistryProperties p = new PrinterRegistryProperties();
        for (PrinterRegistryProperties.Entry e : entries) {
            p.getPrinters().add(e);
        }
        return p;
    }

    private static PrinterRegistryProperties.Entry entry(String id, String name, String url) {
        PrinterRegistryProperties.Entry e = new PrinterRegistryProperties.Entry();
        e.setId(id);
        e.setDisplayName(name);
        e.setBaseUrl(url);
        return e;
    }

    @Test
    void getDefault_returnsFirstConfiguredPrinter() {
        PrinterRegistry registry = new PrinterRegistry(
            props(entry("printer-1", "Inkom links", "http://printer-1:8080"),
                  entry("printer-2", "Inkom rechts", "http://printer-2:8080")));
        assertThat(registry.getDefault().id()).isEqualTo("printer-1");
        assertThat(registry.getDefault().displayName()).isEqualTo("Inkom links");
    }

    @Test
    void get_returnsPrinterById() {
        PrinterRegistry registry = new PrinterRegistry(
            props(entry("printer-1", "Inkom links", "http://printer-1:8080")));
        assertThat(registry.get("printer-1").baseUrl()).isEqualTo("http://printer-1:8080");
    }

    @Test
    void get_unknownId_throws() {
        PrinterRegistry registry = new PrinterRegistry(
            props(entry("printer-1", "Inkom links", "http://printer-1:8080")));
        assertThatThrownBy(() -> registry.get("nope"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nope");
    }

    @Test
    void all_returnsAllPrintersInOrder() {
        PrinterRegistry registry = new PrinterRegistry(
            props(entry("printer-1", "A", "http://a:8080"),
                  entry("printer-2", "B", "http://b:8080")));
        assertThat(registry.all()).extracting(Printer::id)
            .containsExactly("printer-1", "printer-2");
    }

    @Test
    void emptyRegistry_throwsAtConstruction() {
        assertThatThrownBy(() -> new PrinterRegistry(props()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cluster.printers");
    }

    @Test
    void duplicateIds_throwAtConstruction() {
        assertThatThrownBy(() -> new PrinterRegistry(
            props(entry("dup", "A", "http://a:8080"),
                  entry("dup", "B", "http://b:8080"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("dup");
    }
}
