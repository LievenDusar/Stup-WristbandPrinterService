package com.stup.wristbandprinter.cluster;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only view over the configured printers. Validates the config at startup. */
@Component
@Profile("!worker")
public class PrinterRegistry {

    private final Map<String, Printer> byId = new LinkedHashMap<>();

    public PrinterRegistry(PrinterRegistryProperties props) {
        if (props.getPrinters().isEmpty()) {
            throw new IllegalStateException(
                "cluster.printers must define at least one printer for the management service");
        }
        for (PrinterRegistryProperties.Entry e : props.getPrinters()) {
            if (byId.containsKey(e.getId())) {
                throw new IllegalStateException("Duplicate printer id in cluster.printers: " + e.getId());
            }
            byId.put(e.getId(), new Printer(e.getId(), e.getDisplayName(), e.getBaseUrl()));
        }
    }

    /** The printer used when a request does not specify one (phase 1: the only printer). */
    public Printer getDefault() {
        return byId.values().iterator().next();
    }

    public Printer get(String id) {
        Printer printer = byId.get(id);
        if (printer == null) {
            throw new com.stup.wristbandprinter.exception.UnknownPrinterException("Unknown printer id: " + id);
        }
        return printer;
    }

    public List<Printer> all() {
        return List.copyOf(byId.values());
    }
}
