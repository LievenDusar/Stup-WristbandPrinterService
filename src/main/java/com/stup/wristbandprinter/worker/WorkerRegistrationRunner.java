package com.stup.wristbandprinter.worker;

import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Registers this worker with management on startup (initial delay 0) and re-asserts on a heartbeat;
 * best-effort deregister on graceful shutdown. All HTTP failures are swallowed by {@link ManagementClient}.
 */
@Component
@Profile("worker")
public class WorkerRegistrationRunner {

    private final ManagementClient client;

    public WorkerRegistrationRunner(ManagementClient client) {
        this.client = client;
    }

    @Scheduled(initialDelayString = "0", fixedDelayString = "${worker.heartbeat-millis:30000}")
    public void registerHeartbeat() {
        client.register();
    }

    @PreDestroy
    public void deregister() {
        client.deregister();
    }
}
