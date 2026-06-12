package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.PrintProperties;
import com.stup.wristbandprinter.config.QueueProperties;
import com.stup.wristbandprinter.domain.PrintJob;
import com.stup.wristbandprinter.domain.PrintJobStatus;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import com.stup.wristbandprinter.exception.InvalidCopiesException;
import com.stup.wristbandprinter.exception.JobNotCancellableException;
import com.stup.wristbandprinter.exception.PrinterUnavailableException;
import com.stup.wristbandprinter.exception.QueueFullException;
import com.stup.wristbandprinter.persistence.JobStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PrintQueueServiceTest {

    @Mock private WristbandZplResolver wristbandZplResolver;
    @Mock private com.stup.wristbandprinter.cluster.PrinterRegistry printerRegistry;
    @Mock private com.stup.wristbandprinter.cluster.WorkerClient workerClient;

    private PrintQueueService service;
    private InMemoryJobStore jobStore;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        // Worker is started per-test: tests that observe the PENDING state leave it
        // unstarted so jobs are never picked up; tests that exercise processing start it.
        service = newService(100);
        org.mockito.Mockito.lenient().when(printerRegistry.getDefault())
            .thenReturn(new com.stup.wristbandprinter.cluster.Printer("printer-1", "Test Printer", "http://worker:8080"));
        org.mockito.Mockito.lenient().when(printerRegistry.get("printer-1"))
            .thenReturn(new com.stup.wristbandprinter.cluster.Printer("printer-1", "Test Printer", "http://worker:8080"));
        org.mockito.Mockito.lenient().when(printerRegistry.all())
            .thenReturn(java.util.List.of(
                new com.stup.wristbandprinter.cluster.Printer("printer-1", "Test Printer", "http://worker:8080")));
    }

    private PrintProperties printProperties;

    private PrintQueueService newService(int maxDepth) {
        QueueProperties queueProperties = new QueueProperties();
        queueProperties.setMaxDepth(maxDepth);
        printProperties = new PrintProperties();
        jobStore = new InMemoryJobStore();
        meterRegistry = new SimpleMeterRegistry();
        return new PrintQueueService(wristbandZplResolver, printerRegistry, workerClient,
            queueProperties, printProperties, jobStore, meterRegistry);
    }

    @AfterEach
    void tearDown() {
        service.stopWorker();
    }

    @Test
    void enqueue_returnsJobWithPendingStatus() {
        PrintJob job = service.enqueue(sampleRequest());
        assertThat(job.getStatus()).isEqualTo(PrintJobStatus.PENDING);
        assertThat(job.getJobId()).isNotNull();
    }

    @Test
    void stopWorker_beforeStart_doesNotThrow() {
        service.stopWorker();
    }

    @Test
    void enqueue_throwsWhenQueueFull() {
        // No worker started, so nothing drains the queue.
        service = newService(2);
        // Re-stub for the new service instance
        org.mockito.Mockito.lenient().when(printerRegistry.getDefault())
            .thenReturn(new com.stup.wristbandprinter.cluster.Printer("printer-1", "Test Printer", "http://worker:8080"));
        service.enqueue(sampleRequest());
        service.enqueue(sampleRequest());

        assertThatThrownBy(() -> service.enqueue(sampleRequest()))
            .isInstanceOf(QueueFullException.class);
    }

    @Test
    void enqueue_jobBecomesAfterProcessing() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        when(wristbandZplResolver.resolve(any())).thenReturn("^XA^XZ");
        doAnswer(inv -> { latch.countDown(); return null; }).when(workerClient).print(any(), any(), any());

        service.startWorker();
        PrintJob job = service.enqueue(sampleRequest());
        boolean processed = latch.await(3, TimeUnit.SECONDS);

        assertThat(processed).isTrue();
        assertThat(job.getStatus()).isEqualTo(PrintJobStatus.DONE);
    }

    @Test
    void worker_sendsResolvedZpl() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        when(wristbandZplResolver.resolve(any())).thenReturn("^XA^XZ-resolved");
        doAnswer(inv -> { latch.countDown(); return null; }).when(workerClient).print(any(), any(), any());

        service.startWorker();
        PrintJob job = service.enqueue(sampleRequest());

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        verify(workerClient).print("http://worker:8080", job.getJobId(), "^XA^XZ-resolved");
    }

    @Test
    void enqueue_jobBecomesFailed_whenPrinterThrows() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        when(wristbandZplResolver.resolve(any())).thenReturn("^XA^XZ");
        doAnswer(inv -> {
            latch.countDown();
            throw new PrinterUnavailableException("Printer down");
        }).when(workerClient).print(any(), any(), any());

        service.startWorker();
        PrintJob job = service.enqueue(sampleRequest());
        latch.await(3, TimeUnit.SECONDS);
        Thread.sleep(100); // allow status update to propagate

        assertThat(job.getStatus()).isEqualTo(PrintJobStatus.FAILED);
        assertThat(job.getError()).contains("Printer down");
    }

    @Test
    void enqueue_persistsJobToStore() {
        PrintJob job = service.enqueue(sampleRequest());
        assertThat(jobStore.loadActive())
            .extracting(PrintJob::getJobId)
            .contains(job.getJobId());
    }

    @Test
    void enqueue_incrementsSubmittedCounterAndQueueDepth() {
        service.enqueue(sampleRequest());
        service.enqueue(sampleRequest());

        assertThat(meterRegistry.get("wristband.jobs.submitted").counter().count()).isEqualTo(2);
        assertThat(meterRegistry.get("wristband.queue.depth").gauge().value()).isEqualTo(2);
    }

    @Test
    void recoverJobs_marksInterruptedJobsFailed() {
        PrintJob inFlight = PrintJob.restore(UUID.randomUUID(), sampleRequest(),
            PrintJobStatus.PRINTING, Instant.now(), null, null);
        jobStore.save(inFlight);

        service.recoverJobs();

        List<PrintJob> recovered = service.getJobs(null);
        assertThat(recovered).hasSize(1);
        assertThat(recovered.get(0).getStatus()).isEqualTo(PrintJobStatus.FAILED);
        assertThat(recovered.get(0).getError()).contains("restart");
    }

    @Test
    void recoverJobs_preservesCompletedJobs() {
        PrintJob done = PrintJob.restore(UUID.randomUUID(), sampleRequest(),
            PrintJobStatus.DONE, Instant.now(), Instant.now(), null);
        jobStore.save(done);

        service.recoverJobs();

        assertThat(service.getJobs(PrintJobStatus.DONE)).hasSize(1);
    }

    @Test
    void getJobs_returnsAllJobs() {
        service.enqueue(sampleRequest());
        service.enqueue(sampleRequest());
        assertThat(service.getJobs(null)).hasSize(2);
    }

    @Test
    void getJobs_filtersByStatus() {
        service.enqueue(sampleRequest());
        List<?> pending = service.getJobs(PrintJobStatus.PENDING);
        assertThat(pending).isNotEmpty();
        assertThat(pending).allMatch(j -> ((PrintJob) j).getStatus() == PrintJobStatus.PENDING);
    }

    @Test
    void clearCompleted_removesOnlyDoneAndFailedJobs() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        when(wristbandZplResolver.resolve(any())).thenReturn("^XA^XZ");
        doAnswer(inv -> { latch.countDown(); return null; }).when(workerClient).print(any(), any(), any());

        service.startWorker();
        PrintJob job = service.enqueue(sampleRequest());
        latch.await(3, TimeUnit.SECONDS);
        Thread.sleep(100);

        assertThat(service.getJobs(null)).hasSize(1);
        service.clearCompleted();
        assertThat(service.getJobs(null)).isEmpty();
    }

    @Test
    void cancel_pendingJob_marksCancelledAndRemovesFromQueue() {
        // No worker started, so the job stays PENDING in the queue.
        PrintJob job = service.enqueue(sampleRequest());

        PrintJob cancelled = service.cancel(job.getJobId());

        assertThat(cancelled.getStatus()).isEqualTo(PrintJobStatus.CANCELLED);
        assertThat(service.getJobs(PrintJobStatus.CANCELLED)).hasSize(1);
    }

    @Test
    void cancel_nonPendingJob_throws() {
        PrintJob job = service.enqueue(sampleRequest());
        job.setStatus(PrintJobStatus.DONE); // simulate already-processed

        assertThatThrownBy(() -> service.cancel(job.getJobId()))
            .isInstanceOf(JobNotCancellableException.class);
    }

    @Test
    void cancel_unknownJob_returnsNull() {
        assertThat(service.cancel(java.util.UUID.randomUUID())).isNull();
    }

    @Test
    void clearCompleted_alsoRemovesCancelledJobs() {
        PrintJob job = service.enqueue(sampleRequest());
        service.cancel(job.getJobId()); // now CANCELLED

        service.clearCompleted();

        assertThat(service.getJobs(null)).isEmpty();
    }

    @Test
    void subscribeToJob_unknownJob_returnsNull() {
        assertThat(service.subscribeToJob(java.util.UUID.randomUUID())).isNull();
    }

    @Test
    void subscribeToJob_knownJob_returnsEmitter() {
        PrintJob job = service.enqueue(sampleRequest());  // no worker started -> stays PENDING
        assertThat(service.subscribeToJob(job.getJobId())).isNotNull();
    }

    @Test
    void enqueue_stampsDefaultPrinter() {
        PrintJob job = service.enqueue(sampleRequest());
        assertThat(job.getPrinterId()).isEqualTo("printer-1");
        assertThat(job.getPrinterName()).isEqualTo("Test Printer");
    }

    @Test
    void enqueue_routesToRequestedPrinter() {
        org.mockito.Mockito.when(printerRegistry.get("printer-2"))
            .thenReturn(new com.stup.wristbandprinter.cluster.Printer("printer-2", "Second", "http://worker2:8080"));
        WristbandPrintRequest r = sampleRequest();
        r.setPrinterId("printer-2");
        PrintJob job = service.enqueue(r);
        assertThat(job.getPrinterId()).isEqualTo("printer-2");
        assertThat(job.getPrinterName()).isEqualTo("Second");
    }

    @Test
    void enqueue_persistsCopies() {
        WristbandPrintRequest req = sampleRequest();
        req.setCopies(120);

        PrintJob job = service.enqueue(req);

        assertThat(job.getRequest().getCopies()).isEqualTo(120);
    }

    @Test
    void enqueue_rejectsCopiesAboveMax() {
        printProperties.setMaxCopies(200);
        WristbandPrintRequest req = sampleRequest();
        req.setCopies(201);

        assertThatThrownBy(() -> service.enqueue(req))
            .isInstanceOf(InvalidCopiesException.class);
    }

    @Test
    void enqueue_rejectsCopiesBelowOne() {
        WristbandPrintRequest req = sampleRequest();
        req.setCopies(0);

        assertThatThrownBy(() -> service.enqueue(req))
            .isInstanceOf(InvalidCopiesException.class);
    }

    private WristbandPrintRequest sampleRequest() {
        WristbandPrintRequest r = new WristbandPrintRequest();
        r.setEventName("Pukkelpop 2026");
        r.setFirstName("Jan");
        r.setLastName("Janssens");
        r.setAssociationName("STUP vzw");
        r.setBarcodeValue("123456789");
        return r;
    }

    /** Minimal in-memory JobStore so the queue service can be unit-tested without a database. */
    private static class InMemoryJobStore implements JobStore {
        private final Map<UUID, PrintJob> store = new LinkedHashMap<>();
        private final Set<UUID> deleted = new HashSet<>();

        @Override
        public void save(PrintJob job) {
            store.put(job.getJobId(), job);
        }

        @Override
        public List<PrintJob> loadActive() {
            List<PrintJob> active = new ArrayList<>();
            store.forEach((id, job) -> { if (!deleted.contains(id)) active.add(job); });
            return active;
        }

        @Override
        public void deleteById(UUID jobId) {
            store.remove(jobId);
            deleted.remove(jobId);
        }

        @Override
        public void softDeleteCompleted() {
            store.forEach((id, job) -> {
                if (job.getStatus() == PrintJobStatus.DONE
                    || job.getStatus() == PrintJobStatus.FAILED
                    || job.getStatus() == PrintJobStatus.CANCELLED) {
                    deleted.add(id);
                }
            });
        }
    }
}
