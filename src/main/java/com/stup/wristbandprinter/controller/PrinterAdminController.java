package com.stup.wristbandprinter.controller;

import com.stup.wristbandprinter.cluster.Printer;
import com.stup.wristbandprinter.cluster.PrinterRegistry;
import com.stup.wristbandprinter.cluster.WorkerClient;
import com.stup.wristbandprinter.cluster.dto.PrinterEvent;
import com.stup.wristbandprinter.controller.dto.RenamePrinterRequest;
import com.stup.wristbandprinter.service.PrintQueueService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Operator (admin-cookie) printer management: rename, hide, on-demand test, set-default.
 *  Each mutation broadcasts a `printer` SSE event so connected jobs UIs update live (D6). */
@Profile("!worker")
@RestController
@RequestMapping("/api/wristbands/printers")
public class PrinterAdminController {

    private final PrinterRegistry registry;
    private final PrintQueueService printQueueService;
    private final WorkerClient workerClient;

    public PrinterAdminController(PrinterRegistry registry, PrintQueueService printQueueService,
                                 WorkerClient workerClient) {
        this.registry = registry;
        this.printQueueService = printQueueService;
        this.workerClient = workerClient;
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Void> rename(@PathVariable String id, @Valid @RequestBody RenamePrinterRequest req) {
        registry.rename(id, req.displayName());
        broadcast(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/hide")
    public ResponseEntity<Void> hide(@PathVariable String id) {
        registry.setHidden(id, true);
        broadcast(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/default")
    public ResponseEntity<Void> setDefault(@PathVariable String id) {
        registry.setDefault(id);
        broadcast(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable String id) {
        Printer printer = registry.get(id);   // UnknownPrinterException -> 400 if absent in routing map
        boolean reachable = workerClient.isReachable(printer.baseUrl());
        if (reachable) {
            registry.markOnline(id);
        } else {
            registry.markOffline(id);
        }
        broadcast(id);
        return ResponseEntity.ok(Map.of("reachable", reachable, "online", reachable));
    }

    private void broadcast(String id) {
        PrinterEvent event = registry.snapshot(id);
        if (event != null) {
            printQueueService.broadcastPrinter(event);
        }
    }
}
