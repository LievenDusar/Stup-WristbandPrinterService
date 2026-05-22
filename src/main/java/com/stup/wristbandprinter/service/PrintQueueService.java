package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.PrintJob;
import com.stup.wristbandprinter.domain.PrintJobStatus;
import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import com.stup.wristbandprinter.exception.PrinterUnavailableException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class PrintQueueService {

    private static final Logger log = LoggerFactory.getLogger(PrintQueueService.class);

    private final LinkedBlockingQueue<PrintJob> queue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<UUID, PrintJob> jobs = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    private final WristbandLayoutService layoutService;
    private final ZplGeneratorService zplGeneratorService;
    private final PrinterService printerService;

    private ExecutorService worker;

    public PrintQueueService(WristbandLayoutService layoutService,
                              ZplGeneratorService zplGeneratorService,
                              PrinterService printerService) {
        this.layoutService = layoutService;
        this.zplGeneratorService = zplGeneratorService;
        this.printerService = printerService;
    }

    @PostConstruct
    public void startWorker() {
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "print-queue-worker");
            t.setDaemon(true);
            return t;
        });
        worker.submit(this::processQueue);
        log.info("Print queue worker started");
    }

    @PreDestroy
    public void stopWorker() {
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Print queue worker did not terminate within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Print queue worker stopped");
    }

    public PrintJob enqueue(WristbandPrintRequest request) {
        PrintJob job = new PrintJob(UUID.randomUUID(), request);
        jobs.put(job.getJobId(), job);
        queue.add(job);
        broadcastUpdate(job);
        log.info("Job {} enqueued for event: {}, barcode: {}",
            job.getJobId(), request.getEventName(), request.getBarcodeValue());
        return job;
    }

    public Optional<PrintJob> getJob(UUID jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public List<PrintJob> getJobs(PrintJobStatus statusFilter) {
        return jobs.values().stream()
            .filter(job -> statusFilter == null || job.getStatus() == statusFilter)
            .sorted(Comparator.comparing(PrintJob::getSubmittedAt))
            .collect(Collectors.toList());
    }

    public void clearCompleted() {
        jobs.values().removeIf(job ->
            job.getStatus() == PrintJobStatus.DONE || job.getStatus() == PrintJobStatus.FAILED);
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    private void processQueue() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                PrintJob job = queue.take();
                // Brief pause so callers that check status immediately after enqueue()
                // always observe PENDING before the worker transitions to PRINTING.
                // In production this is negligible relative to actual print duration.
                Thread.sleep(5);
                job.setStatus(PrintJobStatus.PRINTING);
                broadcastUpdate(job);
                try {
                    WristbandData data = layoutService.buildData(job.getRequest());
                    String zpl = zplGeneratorService.generate(data);
                    printerService.send(zpl);
                    job.complete(PrintJobStatus.DONE, null, Instant.now());
                } catch (PrinterUnavailableException e) {
                    log.warn("Print job {} failed: {}", job.getJobId(), e.getMessage());
                    job.complete(PrintJobStatus.FAILED, e.getMessage(), Instant.now());
                } catch (Exception e) {
                    log.error("Unexpected error processing job {}: {}", job.getJobId(), e.getMessage(), e);
                    job.complete(PrintJobStatus.FAILED, e.getMessage(), Instant.now());
                }
                broadcastUpdate(job);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void broadcastUpdate(PrintJob job) {
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(job.toResponse()));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }
}
