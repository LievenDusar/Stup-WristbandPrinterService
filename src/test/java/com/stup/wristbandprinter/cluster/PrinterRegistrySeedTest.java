package com.stup.wristbandprinter.cluster;

import com.stup.wristbandprinter.persistence.PrinterEntity;
import com.stup.wristbandprinter.persistence.PrinterRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PrinterRegistrySeedTest {

    private final PrinterRepository repo = mock(PrinterRepository.class);

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
    void seed_insertsNewConfiguredPrinter() {
        when(repo.findById("printer-1")).thenReturn(Optional.empty());
        PrinterRegistry registry = new PrinterRegistry(
            props(entry("printer-1", "Inkom links", "http://printer-1:8080")), repo);

        registry.seed();

        verify(repo).save(argThat(e ->
            e.getId().equals("printer-1")
                && e.getDisplayName().equals("Inkom links")
                && e.getBaseUrl().equals("http://printer-1:8080")));
    }

    @Test
    void seed_updatesExistingPrinterFromConfig() {
        PrinterEntity existing = new PrinterEntity("printer-1", "Old name", "http://old:8080");
        when(repo.findById("printer-1")).thenReturn(Optional.of(existing));
        PrinterRegistry registry = new PrinterRegistry(
            props(entry("printer-1", "New name", "http://new:8080")), repo);

        registry.seed();

        verify(repo).save(eq(existing));
        org.assertj.core.api.Assertions.assertThat(existing.getDisplayName()).isEqualTo("New name");
        org.assertj.core.api.Assertions.assertThat(existing.getBaseUrl()).isEqualTo("http://new:8080");
    }
}
