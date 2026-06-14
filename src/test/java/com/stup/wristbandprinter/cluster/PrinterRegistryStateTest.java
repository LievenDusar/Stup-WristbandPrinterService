package com.stup.wristbandprinter.cluster;

import com.stup.wristbandprinter.exception.NoPrintersAvailableException;
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
class PrinterRegistryStateTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PrinterRepository repo;

    private PrinterRegistry registry() {
        PrinterRegistry r = new PrinterRegistry(repo);
        r.init();
        return r;
    }

    @Test
    void init_loadsExistingPrintersFromDb() {
        repo.save(new PrinterEntity("printer-1", "Inkom", "http://printer-1:8080"));
        PrinterRegistry r = new PrinterRegistry(repo);
        r.init();
        assertThat(r.get("printer-1").displayName()).isEqualTo("Inkom");
    }

    @Test
    void register_thenGetAndRoute() {
        PrinterRegistry r = registry();
        r.register("printer-1", "Inkom", "http://printer-1:8080");
        assertThat(r.get("printer-1").baseUrl()).isEqualTo("http://printer-1:8080");
        assertThat(repo.findById("printer-1")).get().satisfies(e -> {
            assertThat(e.isOnline()).isTrue();
            assertThat(e.isHidden()).isFalse();
            assertThat(e.getLastSeenAt()).isNotNull();
        });
    }

    @Test
    void register_existing_doesNotOverwriteDisplayNameButUpdatesBaseUrl() {
        PrinterEntity seed = new PrinterEntity("printer-1", "Operator Renamed", "http://old:8080");
        repo.save(seed);
        PrinterRegistry r = registry();

        r.register("printer-1", "Worker Default Name", "http://new:8080");

        PrinterEntity e = repo.findById("printer-1").orElseThrow();
        assertThat(e.getDisplayName()).isEqualTo("Operator Renamed");
        assertThat(e.getBaseUrl()).isEqualTo("http://new:8080");
        assertThat(e.isOnline()).isTrue();
    }

    @Test
    void markOffline_setsOnlineFalse() {
        PrinterRegistry r = registry();
        r.register("printer-1", "Inkom", "http://printer-1:8080");
        r.markOffline("printer-1");
        assertThat(repo.findById("printer-1")).get().satisfies(e -> assertThat(e.isOnline()).isFalse());
    }

    @Test
    void getDefault_emptyCluster_throws() {
        PrinterRegistry r = registry();
        assertThatThrownBy(r::getDefault).isInstanceOf(NoPrintersAvailableException.class);
    }

    @Test
    void getDefault_prefersExplicitDefaultEvenIfOffline() {
        PrinterRegistry r = registry();
        r.register("a", "A", "http://a:8080");
        r.register("b", "B", "http://b:8080");
        PrinterEntity b = repo.findById("b").orElseThrow();
        b.setDefault(true); b.setOnline(false); repo.save(b);
        assertThat(r.getDefault().id()).isEqualTo("b");
    }

    @Test
    void getDefault_fallsBackToEarliestOnlineNotHidden_whenNoExplicitDefault() {
        PrinterRegistry r = registry();
        r.register("a", "A", "http://a:8080");
        r.markOffline("a");
        r.register("b", "B", "http://b:8080");
        assertThat(r.getDefault().id()).isEqualTo("b");
    }

    @Test
    void snapshot_reflectsCurrentRow() {
        PrinterRegistry r = registry();
        r.register("printer-1", "Inkom", "http://printer-1:8080");
        var snap = r.snapshot("printer-1");
        assertThat(snap).isNotNull();
        assertThat(snap.id()).isEqualTo("printer-1");
        assertThat(snap.online()).isTrue();
        assertThat(r.snapshot("nope")).isNull();
    }

    @Test
    void all_returnsPrintersInStableRegistrationOrder() {
        PrinterRegistry r = registry();
        r.register("a", "A", "http://a:8080");
        r.register("b", "B", "http://b:8080");
        r.register("c", "C", "http://c:8080");
        assertThat(r.all()).extracting(Printer::id).containsExactly("a", "b", "c");
    }
}
