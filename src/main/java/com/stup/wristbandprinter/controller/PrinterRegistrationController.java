package com.stup.wristbandprinter.controller;

import com.stup.wristbandprinter.cluster.PrinterRegistry;
import com.stup.wristbandprinter.cluster.dto.PrinterEvent;
import com.stup.wristbandprinter.cluster.dto.RegisterPrinterRequest;
import com.stup.wristbandprinter.service.PrintQueueService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Internal endpoints a printer-worker calls to announce/retire itself. API-key protected. */
@Profile("!worker")
@RestController
@RequestMapping("/api/internal/printers")
public class PrinterRegistrationController {

    private final PrinterRegistry registry;
    private final PrintQueueService printQueueService;

    public PrinterRegistrationController(PrinterRegistry registry, PrintQueueService printQueueService) {
        this.registry = registry;
        this.printQueueService = printQueueService;
    }

    /** Register or refresh a worker. Idempotent (also serves as the heartbeat). */
    @PostMapping("/register")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterPrinterRequest req) {
        registry.register(req.id(), req.displayName(), req.baseUrl());
        printQueueService.ensureQueue(req.id());
        broadcast(req.id());
        return ResponseEntity.ok().build();
    }

    /** Mark a worker offline (best-effort, called on graceful worker shutdown). */
    @PostMapping("/{id}/deregister")
    public ResponseEntity<Void> deregister(@PathVariable String id) {
        registry.markOffline(id);
        broadcast(id);
        return ResponseEntity.ok().build();
    }

    private void broadcast(String id) {
        PrinterEvent event = registry.snapshot(id);
        if (event != null) {
            printQueueService.broadcastPrinter(event);
        }
    }
}
