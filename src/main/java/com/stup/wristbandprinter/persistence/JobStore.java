package com.stup.wristbandprinter.persistence;

import com.stup.wristbandprinter.domain.PrintJob;

import java.util.List;
import java.util.UUID;

/**
 * Durable backing store for print jobs. Lets the queue service survive restarts
 * while keeping the JPA concern out of the hot path so it stays unit-testable.
 */
public interface JobStore {

    void save(PrintJob job);

    List<PrintJob> loadAll();

    void deleteById(UUID jobId);

    void deleteCompleted();
}
