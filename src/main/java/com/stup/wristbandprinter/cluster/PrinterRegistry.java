package com.stup.wristbandprinter.cluster;

import com.stup.wristbandprinter.persistence.PrinterEntity;
import com.stup.wristbandprinter.persistence.PrinterRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only view over the configured printers. Validates config at startup and
 *  seeds the printers table so live printers have a persisted row (phase 1). */
@Component
@Profile("!worker")
public class PrinterRegistry {

    private final Map<String, Printer> byId = new LinkedHashMap<>();
    private final PrinterRepository printerRepository;

    public PrinterRegistry(PrinterRegistryProperties props, PrinterRepository printerRepository) {
        this.printerRepository = printerRepository;
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

    /** Upsert the configured printers into the printers table. Each repository call is
     *  transactional on its own; runs at startup before the web server accepts traffic.
     *  Flyway migrations run during context initialization, before any bean's
     *  {@code @PostConstruct}, so the printers table (V9) is guaranteed to exist here.
     *  Intentionally not wrapped in a single {@code @Transactional}: each repository call
     *  commits independently and the loop is idempotent, so a crash mid-loop is harmless
     *  and completes on the next restart. */
    @PostConstruct
    public void seed() {
        for (Printer p : byId.values()) {
            PrinterEntity entity = printerRepository.findById(p.id())
                .orElseGet(() -> new PrinterEntity(p.id(), p.displayName(), p.baseUrl()));
            entity.setDisplayName(p.displayName());
            entity.setBaseUrl(p.baseUrl());
            printerRepository.save(entity);
        }
    }

    /** The printer used when a request does not specify one (phase 1: the first configured printer). */
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
