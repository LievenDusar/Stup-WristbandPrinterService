package com.stup.wristbandprinter.persistence;

import com.stup.wristbandprinter.domain.CodeSymbology;
import com.stup.wristbandprinter.domain.FreeTextWristbandPrintRequest;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Profile("!worker")
@Component
public class JpaJobStore implements JobStore {

    private final PrintJobRepository repository;
    private final PrinterRepository printerRepository;

    public JpaJobStore(PrintJobRepository repository, PrinterRepository printerRepository) {
        this.repository = repository;
        this.printerRepository = printerRepository;
    }

    @Override
    @Transactional
    public void save(PrintJob job) {
        PrintableRequest r = job.getRequest();
        String eventName = null, firstName = null, lastName = null,
               clubName = null, barcodeValue = null,
               permitLabel = null, iconName = null,
               freeText = null,
               codeValue = null;
        CodeSymbology codeSymbology = null;

        if (r instanceof WristbandPrintRequest w) {
            eventName    = w.getEventName();
            firstName    = w.getFirstName();
            lastName     = w.getLastName();
            clubName     = w.getClubName();
            barcodeValue = w.getBarcodeValue();
            codeSymbology = w.getCodeSymbology();
        } else if (r instanceof PermitWristbandPrintRequest p) {
            eventName    = p.getEventName();
            clubName     = p.getClubName();
            permitLabel  = p.getPermitLabel();
            iconName     = p.getIconName();
            codeValue    = p.getCodeValue();
            codeSymbology = p.getCodeSymbology();
        } else if (r instanceof FreeTextWristbandPrintRequest f) {
            freeText = f.getText();
        }

        repository.save(new PrintJobEntity(
            job.getJobId(),
            job.getStatus(),
            r.getWristbandType(),
            job.getPrinterId(),
            eventName, firstName, lastName, clubName, barcodeValue,
            permitLabel, iconName, freeText,
            r.getStockColorCode(), codeValue, codeSymbology,
            r.getCopies(),
            job.getSubmittedAt(),
            job.getCompletedAt(),
            job.getError()
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PrintJob> loadActive() {
        Map<String, String> namesById = printerRepository.findAll().stream()
            .collect(Collectors.toMap(PrinterEntity::getId, PrinterEntity::getDisplayName));
        return repository.findByDeletedFalse().stream()
            .map(e -> toDomain(e, namesById.get(e.getPrinterId())))
            .toList();
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

    private static PrintJob toDomain(PrintJobEntity e, String printerName) {
        PrintableRequest request;
        WristbandType type = e.getWristbandType() != null ? e.getWristbandType() : WristbandType.CREW;

        if (type == WristbandType.PERMIT) {
            PermitWristbandPrintRequest p = new PermitWristbandPrintRequest();
            p.setEventName(e.getEventName());
            p.setClubName(e.getClubName());
            p.setPermitLabel(e.getPermitLabel());
            p.setIconName(e.getIconName());
            p.setCodeValue(e.getCodeValue());
            p.setCodeSymbology(e.getCodeSymbology());
            p.setStockColorCode(e.getStockColorCode());
            p.setCopies(e.getCopies());
            p.setPrinterId(e.getPrinterId());
            request = p;
        } else if (type == WristbandType.FREETEXT) {
            FreeTextWristbandPrintRequest f = new FreeTextWristbandPrintRequest();
            f.setText(e.getFreeText());
            f.setStockColorCode(e.getStockColorCode());
            f.setCopies(e.getCopies());
            f.setPrinterId(e.getPrinterId());
            request = f;
        } else {
            WristbandPrintRequest w = new WristbandPrintRequest();
            w.setEventName(e.getEventName());
            w.setFirstName(e.getFirstName());
            w.setLastName(e.getLastName());
            w.setClubName(e.getClubName());
            w.setBarcodeValue(e.getBarcodeValue());
            w.setCodeSymbology(e.getCodeSymbology());
            w.setStockColorCode(e.getStockColorCode());
            w.setCopies(e.getCopies());
            w.setPrinterId(e.getPrinterId());
            request = w;
        }

        return PrintJob.restore(
            e.getJobId(),
            request,
            e.getPrinterId(),
            printerName,
            e.getStatus(),
            e.getSubmittedAt(),
            e.getCompletedAt(),
            e.getError()
        );
    }
}
