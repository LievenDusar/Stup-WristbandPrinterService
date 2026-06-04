package com.stup.wristbandprinter.worker;

import com.stup.wristbandprinter.controller.WristbandController;
import com.stup.wristbandprinter.persistence.JobStore;
import com.stup.wristbandprinter.service.PrinterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("worker")
@TestPropertySource(properties = "security.api-key=test-key")
class WorkerProfileContextTest {

    @Autowired
    ApplicationContext ctx;

    @Test
    void workerContextBootsWithoutManagementBeans() {
        assertThat(ctx.getBeanNamesForType(PrinterService.class)).isNotEmpty();
        assertThat(ctx.getBeanNamesForType(WorkerPrintController.class)).isNotEmpty();

        assertThat(ctx.getBeanNamesForType(JobStore.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(WristbandController.class)).isEmpty();
        // Routing beans are management-only; a worker must never try to forward to itself.
        assertThat(ctx.getBeanNamesForType(com.stup.wristbandprinter.cluster.PrinterRegistry.class)).isEmpty();
        assertThat(ctx.getBeanNamesForType(com.stup.wristbandprinter.cluster.WorkerClient.class)).isEmpty();

        // The worker has no database: the DataSource autoconfig is excluded in application-worker.yml.
        assertThat(ctx.getBeanNamesForType(DataSource.class)).isEmpty();
    }
}
