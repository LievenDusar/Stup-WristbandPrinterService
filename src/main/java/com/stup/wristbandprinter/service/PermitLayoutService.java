package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.domain.CodeSymbology;
import com.stup.wristbandprinter.domain.PermitWristbandData;
import com.stup.wristbandprinter.domain.PermitWristbandPrintRequest;
import com.stup.wristbandprinter.exception.InvalidStockColorException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Builds a {@link PermitWristbandData} from a {@link PermitWristbandPrintRequest},
 * resolving defaults (symbology, stock color) and validating the stock-color code.
 */
@Profile("!worker")
@Service
public class PermitLayoutService {

    private final WristbandProperties props;

    public PermitLayoutService(WristbandProperties props) {
        this.props = props;
    }

    public PermitWristbandData buildData(PermitWristbandPrintRequest request) {
        CodeSymbology symbology = request.getCodeSymbology() != null
            ? request.getCodeSymbology()
            : props.getPermit().getCode().getDefaultSymbology();

        String stockHex = resolveStockColor(request.getStockColorCode());

        return new PermitWristbandData(
            request.getEventName(),
            request.getPermitLabel(),
            request.getClubName(),
            request.getCodeValue(),
            symbology,
            stockHex
        );
    }

    private String resolveStockColor(Integer code) {
        if (code == null) {
            // Default to white (code 1 if present, otherwise literal white)
            return props.getStockColors().getOrDefault(1, "#FFFFFF");
        }
        String hex = props.getStockColors().get(code);
        if (hex == null) {
            throw new InvalidStockColorException(
                "Unknown stock color code " + code
                    + ". Valid codes: " + props.getStockColors().keySet());
        }
        return hex;
    }
}
