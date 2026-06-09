package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.CodeSymbology;
import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Profile("!worker")
@Service
public class WristbandLayoutService {

    public WristbandData buildData(WristbandPrintRequest request) {
        CodeSymbology symbology = request.getCodeSymbology() != null
            ? request.getCodeSymbology()
            : CodeSymbology.CODE128;
        return new WristbandData(
            request.getEventName(),
            request.getFirstName(),
            request.getLastName(),
            request.getAssociationName(),
            request.getBarcodeValue(),
            symbology
        );
    }
}
