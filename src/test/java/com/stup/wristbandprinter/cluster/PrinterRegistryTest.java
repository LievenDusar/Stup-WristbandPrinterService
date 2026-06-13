package com.stup.wristbandprinter.cluster;

import com.stup.wristbandprinter.persistence.PrinterRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PrinterRegistryTest {

    private final PrinterRepository repo = mock(PrinterRepository.class);

    private static PrinterRegistryProperties props(PrinterRegistryProperties.Entry... entries) {
        PrinterRegistryProperties p = new PrinterRegistryProperties();
        for (PrinterRegistryProperties.Entry e : entries) {
            p.getPrinters().add(e);
        }
        return p;
    }

    private static PrinterRegistryProperties.Entry entry(String id) {
        PrinterRegistryProperties.Entry e = new PrinterRegistryProperties.Entry();
        e.setId(id);
        e.setDisplayName(id);
        e.setBaseUrl("http://" + id + ":8080");
        return e;
    }

    @Test
    void duplicateIds_throwAtConstruction() {
        assertThatThrownBy(() -> new PrinterRegistry(props(entry("dup"), entry("dup")), repo))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("dup");
    }

    @Test
    void emptyConfig_isAllowed_dynamicRegistrationOnly() {
        new PrinterRegistry(props(), repo);
    }
}
