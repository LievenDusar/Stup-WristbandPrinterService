package com.stup.wristbandprinter.persistence;

import com.stup.wristbandprinter.domain.PrintJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.UUID;

public interface PrintJobRepository extends JpaRepository<PrintJobEntity, UUID> {

    long deleteByStatusIn(Collection<PrintJobStatus> statuses);
}
