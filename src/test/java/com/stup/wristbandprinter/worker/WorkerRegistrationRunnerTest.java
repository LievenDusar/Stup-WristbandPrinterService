package com.stup.wristbandprinter.worker;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class WorkerRegistrationRunnerTest {

    private final ManagementClient client = mock(ManagementClient.class);
    private final WorkerRegistrationRunner runner = new WorkerRegistrationRunner(client);

    @Test
    void heartbeat_registers() {
        runner.registerHeartbeat();
        verify(client).register();
    }

    @Test
    void shutdown_deregisters() {
        runner.deregister();
        verify(client).deregister();
    }
}
