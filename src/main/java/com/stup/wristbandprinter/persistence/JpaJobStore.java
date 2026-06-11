package com.stup.wristbandprinter.persistence;

import com.stup.wristbandprinter.domain.CodeSymbology;
import com.stup.wristbandprinter.domain.PermitWristbandPrintRequest;
import com.stup.wristbandprinter.domain.PrintJob;
import com.stup.wristbandprinter.domain.PrintJobStatus;
import com.stup.wristbandprinter.domain.PrintableRequest;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import com.stup.wristbandprinter.domain.WristbandType;
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
        PrintableRequest r = job.getRequest();
        String eventName = null, firstName = null, lastName = null,
               assocName = null, barcodeValue = null,
               permitLabel = null, iconName = null,
               codeValue = null;
        CodeSymbology codeSymbology = null;

        if (r instanceof WristbandPrintRequest w) {
            eventName    = w.getEventName();
            firstName    = w.getFirstName();
            lastName     = w.getLastName();
            assocName    = w.getAssociationName();
            barcodeValue = w.getBarcodeValue();
            codeSymbology = w.getCodeSymbology();
        } else if (r instanceof PermitWristbandPrintRequest p) {
            eventName    = p.getEventName();
            assocName    = p.getAssociationName();
            permitLabel  = p.getPermitLabel();
            iconName     = p.getIconName();
            codeValue    = p.getCodeValue();
            codeSymbology = p.getCodeSymbology();
        }

        repository.save(new PrintJobEntity(
            job.getJobId(),
            job.getStatus(),
            r.getWristbandType(),
            job.getPrinterId(),
            job.getPrinterName(),
            eventName, firstName, lastName, assocName, barcodeValue,
            permitLabel, iconName,
            r.getStockColorCode(), codeValue, codeSymbology,
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
        PrintableRequest request;
        WristbandType type = e.getWristbandType() != null ? e.getWristbandType() : WristbandType.CREW;

        if (type == WristbandType.PERMIT) {
            PermitWristbandPrintRequest p = new PermitWristbandPrintRequest();
            p.setEventName(e.getEventName());
            p.setAssociationName(e.getAssociationName());
            p.setPermitLabel(e.getPermitLabel());
            p.setIconName(e.getIconName());
            p.setCodeValue(e.getCodeValue());
            p.setCodeSymbology(e.getCodeSymbology());
            p.setStockColorCode(e.getStockColorCode());
            p.setPrinterId(e.getPrinterId());
            request = p;
        } else {
            WristbandPrintRequest w = new WristbandPrintRequest();
            w.setEventName(e.getEventName());
            w.setFirstName(e.getFirstName());
            w.setLastName(e.getLastName());
            w.setAssociationName(e.getAssociationName());
            w.setBarcodeValue(e.getBarcodeValue());
            w.setCodeSymbology(e.getCodeSymbology());
            w.setStockColorCode(e.getStockColorCode());
            w.setPrinterId(e.getPrinterId());
            request = w;
        }

        return PrintJob.restore(
            e.getJobId(),
            request,
            e.getPrinterId(),
            e.getPrinterName(),
            e.getStatus(),
            e.getSubmittedAt(),
            e.getCompletedAt(),
            e.getError()
        );
    }
}
