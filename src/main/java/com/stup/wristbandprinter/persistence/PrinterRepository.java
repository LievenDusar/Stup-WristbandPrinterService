package com.stup.wristbandprinter.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PrinterRepository extends JpaRepository<PrinterEntity, String> {

    Optional<PrinterEntity> findByIsDefaultTrue();

    Optional<PrinterEntity> findFirstByOnlineTrueAndHiddenFalseOrderByRegisteredAtAscIdAsc();

    Optional<PrinterEntity> findFirstByHiddenFalseOrderByRegisteredAtAscIdAsc();
}
