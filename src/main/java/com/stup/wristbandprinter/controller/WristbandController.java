package com.stup.wristbandprinter.controller;

import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import com.stup.wristbandprinter.service.LabelaryPreviewService;
import com.stup.wristbandprinter.service.PrintQueueService;
import com.stup.wristbandprinter.service.WristbandLayoutService;
import com.stup.wristbandprinter.service.ZplGeneratorService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wristbands")
public class WristbandController {

    private final PrintQueueService printQueueService;
    private final WristbandLayoutService wristbandLayoutService;
    private final ZplGeneratorService zplGeneratorService;
    private final LabelaryPreviewService labelaryPreviewService;

    public WristbandController(PrintQueueService printQueueService,
                               WristbandLayoutService wristbandLayoutService,
                               ZplGeneratorService zplGeneratorService,
                               LabelaryPreviewService labelaryPreviewService) {
        this.printQueueService = printQueueService;
        this.wristbandLayoutService = wristbandLayoutService;
        this.zplGeneratorService = zplGeneratorService;
        this.labelaryPreviewService = labelaryPreviewService;
    }

    @PostMapping("/preview/zpl")
    public ResponseEntity<String> previewZpl(@Valid @RequestBody WristbandPrintRequest request) {
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
