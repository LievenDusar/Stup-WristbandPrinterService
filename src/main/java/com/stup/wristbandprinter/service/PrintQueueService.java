package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.QueueProperties;
import com.stup.wristbandprinter.domain.PrintJob;
import com.stup.wristbandprinter.domain.PrintJobStatus;
import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import com.stup.wristbandprinter.exception.JobNotCancellableException;
import com.stup.wristbandprinter.exception.PrinterUnavailableException;
import com.stup.wristbandprinter.exception.QueueFullException;
import com.stup.wristbandprinter.persistence.JobStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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

    private final LinkedBlockingQueue<PrintJob> queue;
    private final ConcurrentHashMap<UUID, PrintJob> jobs = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    private final WristbandLayoutService layoutService;
    private final ZplGeneratorService zplGeneratorService;
    private final PrinterService printerService;
    private final QueueProperties queueProperties;
    private final JobStore jobStore;

    private final Counter submittedCounter;
    private final Counter doneCounter;
    private final Counter failedCounter;

    private ExecutorService worker;

    public PrintQueueService(WristbandLayoutService layoutService,
                              ZplGeneratorService zplGeneratorService,
                              PrinterService printerService,
                              QueueProperties queueProperties,
                              JobStore jobStore,
                              MeterRegistry meterRegistry) {
        this.layoutService = layoutService;
        this.zplGeneratorService = zplGeneratorService;
        this.printerService = printerService;
        this.queueProperties = queueProperties;
        this.jobStore = jobStore;
        this.queue = new LinkedBlockingQueue<>(queueProperties.getMaxDepth());

        this.submittedCounter = Counter.builder("wristband.jobs.submitted")
            .description("Total print jobs accepted into the queue").register(meterRegistry);
        this.doneCounter = Counter.builder("wristband.jobs.completed")
            .tag("status", "done").register(meterRegistry);
        this.failedCounter = Counter.builder("wristband.jobs.completed")
            .tag("status", "failed").register(meterRegistry);
        Gauge.builder("wristband.queue.depth", queue, Collection::size)
            .description("Pending print jobs waiting to be processed").register(meterRegistry);
    }

    @PostConstruct
    public void init() {
        recoverJobs();
        startWorker();
    }

    /**
     * Load persisted jobs on startup. Any job left PENDING or PRINTING by a previous
     * run is marked FAILED (we can't know whether the wristband was partially printed);
     * the operator can reprint it deliberately. Completed jobs are restored as-is.
     */
    public void recoverJobs() {
        for (PrintJob job : jobStore.loadAll()) {
            if (job.getStatus() == PrintJobStatus.PENDING
                || job.getStatus() == PrintJobStatus.PRINTING) {
                job.complete(PrintJobStatus.FAILED, "Interrupted by service restart", Instant.now());
                jobStore.save(job);
                log.warn("Recovered interrupted job {} as FAILED", job.getJobId());
            }
            jobs.put(job.getJobId(), job);
        }
    }

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
        if (worker == null) {
            return;
        }
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
        if (queue.size() >= queueProperties.getMaxDepth()) {
            throw queueFull(request);
        }

        PrintJob job = new PrintJob(UUID.randomUUID(), request);
        // Persist before exposing the job to the worker: otherwise the worker thread can
        // dequeue and save it concurrently with this thread's save, causing duplicate inserts.
        jobStore.save(job);
        jobs.put(job.getJobId(), job);

        if (!queue.offer(job)) {
            // Lost a capacity race against another submitter; undo the persisted row.
            jobs.remove(job.getJobId());
            jobStore.deleteById(job.getJobId());
            throw queueFull(request);
        }

        submittedCounter.increment();
        broadcastUpdate(job);
        log.info("Job {} enqueued for event: {}, barcode: {}",
            job.getJobId(), request.getEventName(), request.getBarcodeValue());
        return job;
    }

    private QueueFullException queueFull(WristbandPrintRequest request) {
        log.warn("Print queue full (max depth {}); rejecting job for event: {}",
            queueProperties.getMaxDepth(), request.getEventName());
        return new QueueFullException(
            "Print queue is full (" + queueProperties.getMaxDepth()
                + " jobs pending). Please retry shortly.");
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
        jobStore.deleteCompleted();
    }

    /**
     * Cancel a job that has not started printing. Only valid while PENDING: the job is
     * removed from the worker queue and marked CANCELLED. Returns null if no such job
     * exists; throws JobNotCancellableException if the job is no longer pending (the
     * worker has already taken it or it has finished).
     */
    public PrintJob cancel(UUID jobId) {
        PrintJob job = jobs.get(jobId);
        if (job == null) {
            return null;
        }
        if (job.getStatus() != PrintJobStatus.PENDING) {
            throw new JobNotCancellableException(
                "Job " + jobId + " is " + job.getStatus() + " and cannot be cancelled");
        }
        if (!queue.remove(job)) {
            // The worker dequeued it between the status check and now.
            throw new JobNotCancellableException(
                "Job " + jobId + " has already started printing");
        }
        job.complete(PrintJobStatus.CANCELLED, null, Instant.now());
        jobStore.save(job);
        broadcastUpdate(job);
        log.info("Job {} cancelled", jobId);
        return job;
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
                MDC.put("jobId", job.getJobId().toString());
                try {
                    job.setStatus(PrintJobStatus.PRINTING);
                    jobStore.save(job);
                    broadcastUpdate(job);
                    try {
                        WristbandData data = layoutService.buildData(job.getRequest());
                        String zpl = zplGeneratorService.generate(data);
                        printerService.send(zpl);
                        job.complete(PrintJobStatus.DONE, null, Instant.now());
                        doneCounter.increment();
                    } catch (PrinterUnavailableException e) {
                        log.warn("Print job {} failed: {}", job.getJobId(), e.getMessage());
                        job.complete(PrintJobStatus.FAILED, e.getMessage(), Instant.now());
                        failedCounter.increment();
                    } catch (Exception e) {
                        log.error("Unexpected error processing job {}: {}", job.getJobId(), e.getMessage(), e);
                        job.complete(PrintJobStatus.FAILED, e.getMessage(), Instant.now());
                        failedCounter.increment();
                    }
                    jobStore.save(job);
                    broadcastUpdate(job);
                } finally {
                    MDC.remove("jobId");
                }
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
