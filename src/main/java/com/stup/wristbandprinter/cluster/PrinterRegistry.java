package com.stup.wristbandprinter.cluster;

import com.stup.wristbandprinter.cluster.dto.PrinterEvent;
import com.stup.wristbandprinter.exception.NoPrintersAvailableException;
import com.stup.wristbandprinter.exception.PrinterNotFoundException;
import com.stup.wristbandprinter.exception.PrinterStateConflictException;
import com.stup.wristbandprinter.exception.UnknownPrinterException;
import com.stup.wristbandprinter.persistence.PrinterEntity;
import com.stup.wristbandprinter.persistence.PrinterRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routing view over the printers. The in-memory {@code byId} map (id -> routing info: display name +
 * base URL) is loaded from the printers table at startup and mutated by {@link #register}. Printer state
 * (online/hidden/default) lives only in the table and is queried on demand. Printers are created
 * exclusively by worker self-registration (no static config).
 */
@Component
@Profile("!worker")
public class PrinterRegistry {

    private final Map<String, Printer> byId = new ConcurrentHashMap<>();
    private final PrinterRepository printerRepository;

    public PrinterRegistry(PrinterRepository printerRepository) {
        this.printerRepository = printerRepository;
    }

    /** Load every persisted printer into the routing map at startup (Flyway has already run). */
    @PostConstruct
    public void init() {
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
        entity.setHidden(false);   // D7: coming back online auto-unhides (an operator hide only sticks while offline)
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
            .or(printerRepository::findFirstByOnlineTrueAndHiddenFalseOrderByRegisteredAtAscIdAsc)
            .or(printerRepository::findFirstByHiddenFalseOrderByRegisteredAtAscIdAsc)
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

    /** All printers, in a stable order (registration time, then id) for deterministic UI listing. */
    public List<Printer> all() {
        return printerRepository.findAll(Sort.by(Sort.Order.asc("registeredAt"), Sort.Order.asc("id"))).stream()
            .map(e -> new Printer(e.getId(), e.getDisplayName(), e.getBaseUrl()))
            .toList();
    }

    /** Current public state of a printer as an SSE event payload; null if unknown. */
    public PrinterEvent snapshot(String id) {
        return printerRepository.findById(id)
            .map(e -> new PrinterEvent(e.getId(), e.getDisplayName(), e.isOnline(), e.isHidden(),
                e.isDefault(), e.getLastSeenAt()))
            .orElse(null);
    }

    /** Rename (operator). Updates the table and the routing map. 404 if unknown. */
    public void rename(String id, String displayName) {
        PrinterEntity e = printerRepository.findById(id)
            .orElseThrow(() -> new PrinterNotFoundException("Unknown printer id: " + id));
        e.setDisplayName(displayName);
        printerRepository.save(e);
        byId.computeIfPresent(id, (k, p) -> new Printer(id, displayName, p.baseUrl()));
    }

    /** Soft-hide an OFFLINE printer (D7). Hiding the current default also clears its default flag.
     *  404 if unknown; 409 if hiding while online. Unhide (hidden=false) is always allowed. */
    public void setHidden(String id, boolean hidden) {
        PrinterEntity e = printerRepository.findById(id)
            .orElseThrow(() -> new PrinterNotFoundException("Unknown printer id: " + id));
        if (hidden && e.isOnline()) {
            throw new PrinterStateConflictException("Cannot hide an online printer: " + id);
        }
        e.setHidden(hidden);
        if (hidden && e.isDefault()) {
            e.setDefault(false);
        }
        printerRepository.save(e);
    }

    /** Set the single default printer (D9). 404 if unknown; 409 if hidden. Clears others in one tx. */
    @Transactional
    public void setDefault(String id) {
        PrinterEntity target = printerRepository.findById(id)
            .orElseThrow(() -> new PrinterNotFoundException("Unknown printer id: " + id));
        if (target.isHidden()) {
            throw new PrinterStateConflictException("Cannot set a hidden printer as default: " + id);
        }
        // Clear the previous default and FLUSH before setting the new one, so the two UPDATEs never
        // co-exist as two is_default=true rows (which would trip the printers_one_default unique index).
        printerRepository.findByIsDefaultTrue().ifPresent(current -> {
            if (!current.getId().equals(id)) {
                current.setDefault(false);
                printerRepository.saveAndFlush(current);
            }
        });
        target.setDefault(true);
        printerRepository.save(target);
    }

    /** Mark online (e.g. a successful liveness probe — D8): online=true, hidden cleared, last_seen refreshed. */
    public void markOnline(String id) {
        printerRepository.findById(id).ifPresent(e -> {
            e.setOnline(true);
            e.setHidden(false);
            e.setLastSeenAt(Instant.now());
            printerRepository.save(e);
        });
    }

    /** All printers as SSE-shaped views, ordered (registration time, then id) — for GET /printers. */
    public List<PrinterEvent> snapshotAll() {
        return printerRepository.findAll(Sort.by(Sort.Order.asc("registeredAt"), Sort.Order.asc("id"))).stream()
            .map(e -> new PrinterEvent(e.getId(), e.getDisplayName(), e.isOnline(), e.isHidden(),
                e.isDefault(), e.getLastSeenAt()))
            .toList();
    }
}
