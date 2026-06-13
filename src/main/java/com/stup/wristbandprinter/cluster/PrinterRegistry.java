package com.stup.wristbandprinter.cluster;

import com.stup.wristbandprinter.cluster.dto.PrinterEvent;
import com.stup.wristbandprinter.exception.NoPrintersAvailableException;
import com.stup.wristbandprinter.exception.UnknownPrinterException;
import com.stup.wristbandprinter.persistence.PrinterEntity;
import com.stup.wristbandprinter.persistence.PrinterRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routing view over the printers. The in-memory {@code byId} map (id → routing info: display name +
 * base URL) is loaded from the printers table at startup and mutated by {@link #register}. Printer
 * <em>state</em> (online/hidden/default) lives only in the table and is queried on demand, so there
 * is a single source of truth and no in-memory/DB drift.
 */
@Component
@Profile("!worker")
public class PrinterRegistry {

    private final Map<String, Printer> byId = new ConcurrentHashMap<>();
    private final PrinterRegistryProperties props;
    private final PrinterRepository printerRepository;

    public PrinterRegistry(PrinterRegistryProperties props, PrinterRepository printerRepository) {
        this.props = props;
        this.printerRepository = printerRepository;
        Map<String, Boolean> seen = new HashMap<>();
        for (PrinterRegistryProperties.Entry e : props.getPrinters()) {
            if (seen.put(e.getId(), Boolean.TRUE) != null) {
                throw new IllegalStateException("Duplicate printer id in cluster.printers: " + e.getId());
            }
        }
    }

    /**
     * Seed configured printers into the table (insert-if-absent, refresh name/base_url on existing),
     * then load every printer row into the routing map. Flyway runs before any {@code @PostConstruct},
     * so the printers table (V9) exists here; each repository call is its own transaction.
     */
    @PostConstruct
    public void init() {
        for (PrinterRegistryProperties.Entry e : props.getPrinters()) {
            PrinterEntity entity = printerRepository.findById(e.getId())
                .orElseGet(() -> new PrinterEntity(e.getId(), e.getDisplayName(), e.getBaseUrl()));
            entity.setDisplayName(e.getDisplayName());
            entity.setBaseUrl(e.getBaseUrl());
            printerRepository.save(entity);
        }
        for (PrinterEntity e : printerRepository.findAll()) {
            byId.put(e.getId(), new Printer(e.getId(), e.getDisplayName(), e.getBaseUrl()));
        }
    }

    /**
     * Register (or refresh) a printer: upsert the row online, refreshing base_url + last_seen_at and
     * clearing hidden. The display name is set only when the row is first created — an operator rename
     * (part 3) is not overwritten by a re-registering worker. Updates the routing map.
     */
    public void register(String id, String displayName, String baseUrl) {
        Optional<PrinterEntity> existing = printerRepository.findById(id);
        PrinterEntity entity = existing.orElseGet(() -> new PrinterEntity(id, displayName, baseUrl));
        entity.setBaseUrl(baseUrl);
        entity.setOnline(true);
        entity.setHidden(false);
        entity.setLastSeenAt(Instant.now());
        printerRepository.save(entity);
        byId.put(id, new Printer(id, entity.getDisplayName(), baseUrl));
    }

    /** Mark a printer offline (its row + routing entry persist). */
    public void markOffline(String id) {
        printerRepository.findById(id).ifPresent(e -> {
            e.setOnline(false);
            printerRepository.save(e);
        });
    }

    /**
     * The printer used when a request does not specify one (D5): the explicitly-set default if it
     * exists and is not hidden (even if offline — operator intent wins); else the earliest-registered
     * online, non-hidden printer; else the earliest-registered non-hidden printer; else none → 503.
     */
    public Printer getDefault() {
        PrinterEntity chosen = printerRepository.findByIsDefaultTrue()
            .filter(e -> !e.isHidden())
            .or(printerRepository::findFirstByOnlineTrueAndHiddenFalseOrderByRegisteredAtAsc)
            .or(printerRepository::findFirstByHiddenFalseOrderByRegisteredAtAsc)
            .orElseThrow(() -> new NoPrintersAvailableException(
                "No printers are registered. Start a printer worker (or register one) and retry."));
        return new Printer(chosen.getId(), chosen.getDisplayName(), chosen.getBaseUrl());
    }

    public Printer get(String id) {
        Printer printer = byId.get(id);
        if (printer == null) {
            throw new UnknownPrinterException("Unknown printer id: " + id);
        }
        return printer;
    }

    public List<Printer> all() {
        return List.copyOf(byId.values());
    }

    /** Current public state of a printer as an SSE event payload; null if unknown. */
    public PrinterEvent snapshot(String id) {
        return printerRepository.findById(id)
            .map(e -> new PrinterEvent(e.getId(), e.getDisplayName(), e.isOnline(), e.isHidden(),
                e.isDefault(), e.getLastSeenAt()))
            .orElse(null);
    }
}
