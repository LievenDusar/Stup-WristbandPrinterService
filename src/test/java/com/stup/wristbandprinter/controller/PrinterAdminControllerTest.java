package com.stup.wristbandprinter.controller;

import com.stup.wristbandprinter.cluster.Printer;
import com.stup.wristbandprinter.cluster.PrinterRegistry;
import com.stup.wristbandprinter.cluster.WorkerClient;
import com.stup.wristbandprinter.cluster.dto.PrinterEvent;
import com.stup.wristbandprinter.controller.dto.RenamePrinterRequest;
import com.stup.wristbandprinter.service.PrintQueueService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PrinterAdminControllerTest {

    private final PrinterRegistry registry = mock(PrinterRegistry.class);
    private final PrintQueueService queue = mock(PrintQueueService.class);
    private final WorkerClient workerClient = mock(WorkerClient.class);
    private final PrinterAdminController controller =
        new PrinterAdminController(registry, queue, workerClient);

    private void stubSnapshot(String id, boolean online) {
        when(registry.snapshot(id)).thenReturn(new PrinterEvent(id, "N", online, false, false, Instant.now()));
    }

    @Test
    void rename_renamesAndBroadcasts() {
        stubSnapshot("p1", true);
        controller.rename("p1", new RenamePrinterRequest("New"));
        verify(registry).rename("p1", "New");
        verify(queue).broadcastPrinter(any());
    }

    @Test
    void hide_hidesAndBroadcasts() {
        stubSnapshot("p1", false);
        controller.hide("p1");
        verify(registry).setHidden("p1", true);
        verify(queue).broadcastPrinter(any());
    }

    @Test
    void setDefault_setsAndBroadcasts() {
        stubSnapshot("p1", true);
        controller.setDefault("p1");
        verify(registry).setDefault("p1");
        verify(queue).broadcastPrinter(any());
    }

    @Test
    void test_reachable_marksOnlineAndReturnsReachable() {
        when(registry.get("p1")).thenReturn(new Printer("p1", "N", "http://p1:8080"));
        when(workerClient.isReachable("http://p1:8080")).thenReturn(true);
        stubSnapshot("p1", true);

        var resp = controller.test("p1");

        verify(registry).markOnline("p1");
        verify(queue).broadcastPrinter(any());
        assertThat(resp.getBody()).containsEntry("reachable", true).containsEntry("online", true);
    }

    @Test
    void test_unreachable_marksOfflineAndReturnsNotReachable() {
        when(registry.get("p1")).thenReturn(new Printer("p1", "N", "http://p1:8080"));
        when(workerClient.isReachable("http://p1:8080")).thenReturn(false);
        stubSnapshot("p1", false);

        var resp = controller.test("p1");

        verify(registry).markOffline("p1");
        verify(queue).broadcastPrinter(any());
        assertThat(resp.getBody()).containsEntry("reachable", false);
    }
}
