package com.stup.wristbandprinter.cluster;

import com.stup.wristbandprinter.exception.PrinterNotFoundException;
import com.stup.wristbandprinter.exception.PrinterStateConflictException;
import com.stup.wristbandprinter.persistence.PrinterEntity;
import com.stup.wristbandprinter.persistence.PrinterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PrinterRegistryAdminTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired PrinterRepository repo;

    private PrinterRegistry registry() {
        PrinterRegistry r = new PrinterRegistry(repo);
        r.init();
        return r;
    }

    @Test
    void rename_updatesNameInDbAndRouting() {
        PrinterRegistry r = registry();
        r.register("p1", "Old", "http://p1:8080");
        r.rename("p1", "New name");
        assertThat(repo.findById("p1")).get().extracting(PrinterEntity::getDisplayName).isEqualTo("New name");
        assertThat(r.get("p1").displayName()).isEqualTo("New name");
    }

    @Test
    void rename_unknown_throwsNotFound() {
        assertThatThrownBy(() -> registry().rename("nope", "X"))
            .isInstanceOf(PrinterNotFoundException.class);
    }

    @Test
    void setHidden_offlinePrinter_hides() {
        PrinterRegistry r = registry();
        r.register("p1", "P1", "http://p1:8080");
        r.markOffline("p1");
        r.setHidden("p1", true);
        assertThat(repo.findById("p1")).get().extracting(PrinterEntity::isHidden).isEqualTo(true);
    }

    @Test
    void setHidden_onlinePrinter_throwsConflict() {
        PrinterRegistry r = registry();
        r.register("p1", "P1", "http://p1:8080"); // online
        assertThatThrownBy(() -> r.setHidden("p1", true))
            .isInstanceOf(PrinterStateConflictException.class);
    }

    @Test
    void setHidden_clearsDefaultWhenHidingCurrentDefault() {
        PrinterRegistry r = registry();
        r.register("p1", "P1", "http://p1:8080");
        r.setDefault("p1");
        r.markOffline("p1");
        r.setHidden("p1", true);
        assertThat(repo.findById("p1")).get().extracting(PrinterEntity::isDefault).isEqualTo(false);
    }

    @Test
    void setDefault_clearsOthers_andRejectsHidden() {
        PrinterRegistry r = registry();
        r.register("a", "A", "http://a:8080");
        r.register("b", "B", "http://b:8080");
        r.setDefault("a");
        r.setDefault("b");
        assertThat(repo.findById("a")).get().extracting(PrinterEntity::isDefault).isEqualTo(false);
        assertThat(repo.findById("b")).get().extracting(PrinterEntity::isDefault).isEqualTo(true);

        r.markOffline("a");
        r.setHidden("a", true);
        assertThatThrownBy(() -> r.setDefault("a")).isInstanceOf(PrinterStateConflictException.class);
    }

    @Test
    void markOnline_setsOnlineAndClearsHidden() {
        PrinterRegistry r = registry();
        r.register("p1", "P1", "http://p1:8080");
        r.markOffline("p1");
        r.setHidden("p1", true);
        r.markOnline("p1");
        assertThat(repo.findById("p1")).get()
            .satisfies(e -> { assertThat(e.isOnline()).isTrue(); assertThat(e.isHidden()).isFalse(); });
    }

    @Test
    void snapshotAll_returnsAllOrderedByRegisteredAtThenId() {
        PrinterRegistry r = registry();
        r.register("a", "A", "http://a:8080");
        r.register("b", "B", "http://b:8080");
        assertThat(r.snapshotAll()).extracting(com.stup.wristbandprinter.cluster.dto.PrinterEvent::id)
            .containsExactly("a", "b");
    }
}
