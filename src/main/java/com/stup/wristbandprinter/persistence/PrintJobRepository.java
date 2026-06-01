package com.stup.wristbandprinter.persistence;

import com.stup.wristbandprinter.domain.PrintJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PrintJobRepository extends JpaRepository<PrintJobEntity, UUID> {

    List<PrintJobEntity> findByDeletedFalse();

    @Modifying
    @Query("update PrintJobEntity e set e.deleted = true where e.status in :statuses and e.deleted = false")
    int softDeleteByStatusIn(Collection<PrintJobStatus> statuses);
}
