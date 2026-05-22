package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.WristbandData;
import com.stup.wristbandprinter.domain.WristbandPrintRequest;
import org.springframework.stereotype.Service;

@Service
public class WristbandLayoutService {

    public WristbandData buildData(WristbandPrintRequest request) {
        return new WristbandData(
            request.getEventName(),
            request.getFirstName(),
            request.getLastName(),
            request.getAssociationName(),
            request.getBarcodeValue()
        );
    }
}
