package com.stup.wristbandprinter.worker;

import com.stup.wristbandprinter.cluster.dto.PrintForwardRequest;
import com.stup.wristbandprinter.service.PrinterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal endpoint a printer-worker exposes to receive ready-to-print ZPL from management. */
@RestController
@RequestMapping("/api/internal")
@Profile("worker")
public class WorkerPrintController {

    private static final Logger log = LoggerFactory.getLogger(WorkerPrintController.class);

    private final PrinterService printerService;

    public WorkerPrintController(PrinterService printerService) {
        this.printerService = printerService;
    }

    @PostMapping("/print")
    public ResponseEntity<Void> print(@RequestBody PrintForwardRequest request) {
        log.info("Worker processing print for job {}", request.jobId());
        printerService.send(request.zpl());
        log.info("Worker completed print for job {}", request.jobId());
        return ResponseEntity.ok().build();
    }
}
