package com.stup.wristbandprinter.persistence;

import com.stup.wristbandprinter.domain.PrintJob;
import com.stup.wristbandprinter.domain.PrintJobStatus;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Profile("!worker")
@Component
public class JpaJobStore implements JobStore {

    private final PrintJobRepository repository;

    public JpaJobStore(PrintJobRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(PrintJob job) {
        WristbandPrintRequest r = job.getRequest();
        repository.save(new PrintJobEntity(
            job.getJobId(),
            job.getStatus(),
            r.getEventName(),
            r.getFirstName(),
            r.getLastName(),
            r.getAssociationName(),
            r.getBarcodeValue(),
            job.getSubmittedAt(),
            job.getCompletedAt(),
            job.getError()
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PrintJob> loadActive() {
        return repository.findByDeletedFalse().stream().map(JpaJobStore::toDomain).toList();
    }

    @Override
    @Transactional
    public void deleteById(UUID jobId) {
        repository.deleteById(jobId);
    }

    @Override
    @Transactional
    public void softDeleteCompleted() {
        repository.softDeleteByStatusIn(
            List.of(PrintJobStatus.DONE, PrintJobStatus.FAILED, PrintJobStatus.CANCELLED));
    }

    private static PrintJob toDomain(PrintJobEntity e) {
        WristbandPrintRequest request = new WristbandPrintRequest();
        request.setEventName(e.getEventName());
        request.setFirstName(e.getFirstName());
        request.setLastName(e.getLastName());
        request.setAssociationName(e.getAssociationName());
        request.setBarcodeValue(e.getBarcodeValue());
        return PrintJob.restore(
            e.getJobId(),
            request,
            e.getStatus(),
            e.getSubmittedAt(),
            e.getCompletedAt(),
            e.getError()
        );
    }
}
