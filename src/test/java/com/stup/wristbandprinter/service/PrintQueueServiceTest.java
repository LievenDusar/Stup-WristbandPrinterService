package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.QueueProperties;
import com.stup.wristbandprinter.domain.PrintJob;
import com.stup.wristbandprinter.domain.PrintJobStatus;
import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import com.stup.wristbandprinter.exception.PrinterUnavailableException;
import com.stup.wristbandprinter.exception.QueueFullException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PrintQueueServiceTest {

    @Mock private WristbandLayoutService layoutService;
    @Mock private ZplGeneratorService zplGeneratorService;
    @Mock private PrinterService printerService;

    private PrintQueueService service;

    @BeforeEach
    void setUp() {
        // Worker is started per-test: tests that observe the PENDING state leave it
        // unstarted so jobs are never picked up; tests that exercise processing start it.
        service = newService(100);
    }

    private PrintQueueService newService(int maxDepth) {
        QueueProperties queueProperties = new QueueProperties();
        queueProperties.setMaxDepth(maxDepth);
        return new PrintQueueService(layoutService, zplGeneratorService, printerService, queueProperties);
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
        service.enqueue(sampleRequest());
        service.enqueue(sampleRequest());

        assertThatThrownBy(() -> service.enqueue(sampleRequest()))
            .isInstanceOf(QueueFullException.class);
    }

    @Test
    void enqueue_jobBecomesAfterProcessing() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        when(layoutService.buildData(any())).thenReturn(sampleData());
        when(zplGeneratorService.generate(any())).thenReturn("^XA^XZ");
        doAnswer(inv -> { latch.countDown(); return null; }).when(printerService).send(any());

        service.startWorker();
        PrintJob job = service.enqueue(sampleRequest());
        boolean processed = latch.await(3, TimeUnit.SECONDS);

        assertThat(processed).isTrue();
        assertThat(job.getStatus()).isEqualTo(PrintJobStatus.DONE);
    }

    @Test
    void enqueue_jobBecomesFailed_whenPrinterThrows() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        when(layoutService.buildData(any())).thenReturn(sampleData());
        when(zplGeneratorService.generate(any())).thenReturn("^XA^XZ");
        doAnswer(inv -> {
            latch.countDown();
            throw new PrinterUnavailableException("Printer down");
        }).when(printerService).send(any());

        service.startWorker();
        PrintJob job = service.enqueue(sampleRequest());
        latch.await(3, TimeUnit.SECONDS);
        Thread.sleep(100); // allow status update to propagate

        assertThat(job.getStatus()).isEqualTo(PrintJobStatus.FAILED);
        assertThat(job.getError()).contains("Printer down");
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
        when(layoutService.buildData(any())).thenReturn(sampleData());
        when(zplGeneratorService.generate(any())).thenReturn("^XA^XZ");
        doAnswer(inv -> { latch.countDown(); return null; }).when(printerService).send(any());

        service.startWorker();
        PrintJob job = service.enqueue(sampleRequest());
        latch.await(3, TimeUnit.SECONDS);
        Thread.sleep(100);

        assertThat(service.getJobs(null)).hasSize(1);
        service.clearCompleted();
        assertThat(service.getJobs(null)).isEmpty();
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

    private WristbandData sampleData() {
        return new WristbandData("Pukkelpop 2026", "Jan", "Janssens", "STUP vzw", "123456789");
    }
}
