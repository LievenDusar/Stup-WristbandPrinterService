package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.PrintJob;
import com.stup.wristbandprinter.domain.PrintJobResponse;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class PrintQueueService {

    public PrintJob submit(WristbandPrintRequest request) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public PrintJobResponse getJob(UUID jobId) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public List<PrintJobResponse> getAllJobs() {
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
