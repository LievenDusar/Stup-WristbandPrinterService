package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.cluster.Printer;
import com.stup.wristbandprinter.cluster.PrinterRegistry;
import com.stup.wristbandprinter.cluster.WorkerClient;
import com.stup.wristbandprinter.config.PrintProperties;
import com.stup.wristbandprinter.config.QueueProperties;
import com.stup.wristbandprinter.domain.PrintJob;
import com.stup.wristbandprinter.domain.PrintJobStatus;
import com.stup.wristbandprinter.domain.PrintableRequest;
import com.stup.wristbandprinter.exception.InvalidCopiesException;
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
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Profile("!worker")
@Service
public class PrintQueueService {

    private static final Logger log = LoggerFactory.getLogger(PrintQueueService.class);

    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.BlockingQueue<PrintJob>> queues
        = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> started = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, PrintJob> jobs = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.ConcurrentHashMap<UUID, java.util.List<SseEmitter>> jobEmitters
        = new java.util.concurrent.ConcurrentHashMap<>();

    private final WristbandZplResolver wristbandZplResolver;
    private final PrinterRegistry printerRegistry;
    private final WorkerClient workerClient;
    private final QueueProperties queueProperties;
    private final PrintProperties printProperties;
    private final JobStore jobStore;

    private final Counter submittedCounter;
    private final Counter doneCounter;
    private final Counter failedCounter;

    private ExecutorService worker;

    public PrintQueueService(WristbandZplResolver wristbandZplResolver,
                              PrinterRegistry printerRegistry,
                              WorkerClient workerClient,
                              QueueProperties queueProperties,
                              PrintProperties printProperties,
                              JobStore jobStore,
                              MeterRegistry meterRegistry) {
        this.wristbandZplResolver = wristbandZplResolver;
        this.printerRegistry = printerRegistry;
        this.workerClient = workerClient;
        this.queueProperties = queueProperties;
        this.printProperties = printProperties;
        this.jobStore = jobStore;

        this.submittedCounter = Counter.builder("wristband.jobs.submitted")
            .description("Total print jobs accepted into the queue").register(meterRegistry);
        this.doneCounter = Counter.builder("wristband.jobs.completed")
            .tag("status", "done").register(meterRegistry);
        this.failedCounter = Counter.builder("wristband.jobs.completed")
            .tag("status", "failed").register(meterRegistry);
        Gauge.builder("wristband.queue.depth", queues,
                q -> q.values().stream().mapToInt(java.util.Collection::size).sum())
            .description("Pending print jobs waiting to be processed").register(meterRegistry);
    }

    private java.util.concurrent.BlockingQueue<PrintJob> queueFor(String printerId) {
        return queues.computeIfAbsent(printerId,
            id -> new java.util.concurrent.LinkedBlockingQueue<>(queueProperties.getMaxDepth()));
    }

    /** Ensure a queue and a dedicated worker thread exist for this printer. Idempotent. */
    public void ensureQueue(String printerId) {
        java.util.concurrent.BlockingQueue<PrintJob> q = queueFor(printerId);
        if (worker != null && started.add(printerId)) {
            worker.submit(() -> processQueue(q));
            log.info("Started print-queue worker for printer {}", printerId);
        }
    }

    public int queueDepth(String printerId) {
        return queueFor(printerId).size();
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
        for (PrintJob job : jobStore.loadActive()) {
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
        worker = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "print-queue-worker");
            t.setDaemon(true);
            return t;
        });
        for (Printer p : printerRegistry.all()) {
            ensureQueue(p.id());
        }
        log.info("Started print-queue workers for {} printer(s)", printerRegistry.all().size());
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

    public PrintJob enqueue(PrintableRequest request) {
        int copies = request.getCopies();
        if (copies < 1 || copies > printProperties.getMaxCopies()) {
            throw new InvalidCopiesException(
                "copies must be between 1 and " + printProperties.getMaxCopies()
                    + " (was " + copies + ")");
        }

        Printer printer = (request.getPrinterId() == null || request.getPrinterId().isBlank())
            ? printerRegistry.getDefault()
            : printerRegistry.get(request.getPrinterId());   // throws UnknownPrinterException -> 400

        ensureQueue(printer.id());

        // Stamp the resolved printerId onto the request so it's persisted correctly.
        PrintableRequest stamped = request.withPrinterId(printer.id());

        java.util.concurrent.BlockingQueue<PrintJob> q = queueFor(printer.id());
        if (q.size() >= queueProperties.getMaxDepth()) {
            throw queueFull(stamped);
        }

        PrintJob job = new PrintJob(UUID.randomUUID(), stamped, printer.id(), printer.displayName());
        // Persist before exposing the job to the worker: otherwise the worker thread can
        // dequeue and save it concurrently with this thread's save, causing duplicate inserts.
        jobStore.save(job);
        jobs.put(job.getJobId(), job);

        if (!q.offer(job)) {
            // Lost a capacity race against another submitter; undo the persisted row.
            jobs.remove(job.getJobId());
            jobStore.deleteById(job.getJobId());
            throw queueFull(stamped);
        }

        submittedCounter.increment();
        broadcastUpdate(job);
        log.info("Job {} ({}) enqueued for printer {} ({})",
            job.getJobId(), stamped.getWristbandType(), printer.id(), printer.displayName());
        return job;
    }

    private QueueFullException queueFull(PrintableRequest request) {
        log.warn("Print queue full (max depth {}); rejecting {} job",
            queueProperties.getMaxDepth(), request.getWristbandType());
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
            job.getStatus() == PrintJobStatus.DONE
                || job.getStatus() == PrintJobStatus.FAILED
                || job.getStatus() == PrintJobStatus.CANCELLED);
        jobStore.softDeleteCompleted();
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
        java.util.concurrent.BlockingQueue<PrintJob> q = queueFor(job.getPrinterId());
        if (!q.remove(job)) {
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

    /** Subscribe to one job's updates; null if the job is unknown. Completes on a terminal status. */
    public SseEmitter subscribeToJob(UUID jobId) {
        PrintJob job = jobs.get(jobId);
        if (job == null) {
            return null;
        }
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        jobEmitters.computeIfAbsent(jobId, id -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeJobEmitter(jobId, emitter));
        emitter.onTimeout(() -> removeJobEmitter(jobId, emitter));
        emitter.onError(e -> removeJobEmitter(jobId, emitter));
        try {
            emitter.send(SseEmitter.event().data(job.toResponse()));   // current snapshot
            if (isTerminal(job.getStatus())) {
                emitter.complete();
            }
        } catch (IOException | IllegalStateException e) {
            // IllegalStateException: a concurrent terminal broadcast already completed this emitter.
            removeJobEmitter(jobId, emitter);
        }
        return emitter;
    }

    private void removeJobEmitter(UUID jobId, SseEmitter emitter) {
        java.util.List<SseEmitter> list = jobEmitters.get(jobId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                jobEmitters.remove(jobId);
            }
        }
    }

    private static boolean isTerminal(PrintJobStatus status) {
        return status == PrintJobStatus.DONE
            || status == PrintJobStatus.FAILED
            || status == PrintJobStatus.CANCELLED;
    }

    private void processQueue(java.util.concurrent.BlockingQueue<PrintJob> q) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                PrintJob job = q.take();
                MDC.put("jobId", job.getJobId().toString());
                MDC.put("printerId", String.valueOf(job.getPrinterId()));
                try {
                    job.setStatus(PrintJobStatus.PRINTING);
                    jobStore.save(job);
                    broadcastUpdate(job);
                    try {
                        String zpl = wristbandZplResolver.resolve(job.getRequest());
                        zpl = ZplCopies.apply(zpl, job.getRequest().getCopies());
                        Printer printer = printerRegistry.get(job.getPrinterId());
                        workerClient.print(printer.baseUrl(), job.getJobId(), zpl);
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
                    MDC.remove("printerId");
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

        java.util.List<SseEmitter> perJob = jobEmitters.get(job.getJobId());
        if (perJob != null) {
            boolean terminal = isTerminal(job.getStatus());
            for (SseEmitter emitter : perJob) {
                try {
                    emitter.send(SseEmitter.event().data(job.toResponse()));
                    if (terminal) {
                        emitter.complete();
                    }
                } catch (IOException | IllegalStateException e) {
                    removeJobEmitter(job.getJobId(), emitter);
                }
            }
        }
    }

    /** Broadcast a printer state change to all jobs-stream subscribers as a named "printer" event (D6). */
    public void broadcastPrinter(com.stup.wristbandprinter.cluster.dto.PrinterEvent event) {
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("printer").data(event));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }
}
