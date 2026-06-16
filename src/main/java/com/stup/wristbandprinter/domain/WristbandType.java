package com.stup.wristbandprinter.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum WristbandType {
    CREW,
    PERMIT;

    /** Lowercase wire form used in JSON — both the print/preview discriminator and responses. */
    @JsonValue
    public String wireValue() {
        return name().toLowerCase();
    }

    /** Parse the wire form back to the enum, case-insensitively for robustness. */
    @JsonCreator
    public static WristbandType fromWire(String value) {
        return WristbandType.valueOf(value.trim().toUpperCase());
    }
}
