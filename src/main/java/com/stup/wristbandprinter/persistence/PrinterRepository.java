package com.stup.wristbandprinter.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PrinterRepository extends JpaRepository<PrinterEntity, String> {
}
