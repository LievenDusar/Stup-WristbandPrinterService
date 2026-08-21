package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.domain.FreeTextWristbandData;
import com.stup.wristbandprinter.domain.FreeTextWristbandPrintRequest;
import com.stup.wristbandprinter.exception.InvalidStockColorException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Builds a {@link FreeTextWristbandData} from a {@link FreeTextWristbandPrintRequest},
 * resolving the stock-color default and validating the stock-color code.
 */
@Profile("!worker")
@Service
public class FreeTextLayoutService {

    private final WristbandProperties props;

    public FreeTextLayoutService(WristbandProperties props) {
        this.props = props;
    }

    public FreeTextWristbandData buildData(FreeTextWristbandPrintRequest request) {
        String stockHex = resolveStockColor(request.getStockColorCode());
        return new FreeTextWristbandData(request.getText(), stockHex);
    }

    private String resolveStockColor(Integer code) {
        if (code == null) {
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
