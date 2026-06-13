package com.stup.wristbandprinter.controller;

import com.stup.wristbandprinter.cluster.PrinterRegistry;
import com.stup.wristbandprinter.cluster.dto.PrinterEvent;
import com.stup.wristbandprinter.cluster.dto.RegisterPrinterRequest;
import com.stup.wristbandprinter.service.PrintQueueService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PrinterRegistrationControllerTest {

    private final PrinterRegistry registry = mock(PrinterRegistry.class);
    private final PrintQueueService queue = mock(PrintQueueService.class);
    private final PrinterRegistrationController controller =
        new PrinterRegistrationController(registry, queue);

    @Test
    void register_registersEnsuresQueueAndBroadcasts() {
        when(registry.snapshot("printer-1")).thenReturn(
            new PrinterEvent("printer-1", "Inkom", true, false, false, Instant.now()));

        controller.register(new RegisterPrinterRequest("printer-1", "Inkom", "http://printer-1:8080"));

        verify(registry).register("printer-1", "Inkom", "http://printer-1:8080");
        verify(queue).ensureQueue("printer-1");
        ArgumentCaptor<PrinterEvent> cap = ArgumentCaptor.forClass(PrinterEvent.class);
        verify(queue).broadcastPrinter(cap.capture());
        assertThat(cap.getValue().id()).isEqualTo("printer-1");
    }

    @Test
    void deregister_marksOfflineAndBroadcasts() {
        when(registry.snapshot("printer-1")).thenReturn(
            new PrinterEvent("printer-1", "Inkom", false, false, false, Instant.now()));

        controller.deregister("printer-1");

        verify(registry).markOffline("printer-1");
        verify(queue).broadcastPrinter(any());
    }

    @Test
    void register_snapshotNull_doesNotBroadcast() {
        when(registry.snapshot("ghost")).thenReturn(null);
        controller.register(new RegisterPrinterRequest("ghost", "Ghost", "http://ghost:8080"));
        verify(queue, never()).broadcastPrinter(any());
    }
}
