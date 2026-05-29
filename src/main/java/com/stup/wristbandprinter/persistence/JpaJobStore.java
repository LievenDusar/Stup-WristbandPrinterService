package com.stup.wristbandprinter.persistence;

import com.stup.wristbandprinter.domain.PrintJob;
import com.stup.wristbandprinter.domain.PrintJobStatus;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
    public List<PrintJob> loadAll() {
        return repository.findAll().stream().map(JpaJobStore::toDomain).toList();
    }

    @Override
    @Transactional
    public void deleteCompleted() {
        repository.deleteByStatusIn(List.of(PrintJobStatus.DONE, PrintJobStatus.FAILED));
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
