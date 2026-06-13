package com.stup.wristbandprinter.editor.domain;

import com.fasterxml.jackson.annotation.JsonAlias;

public enum DataBinding {
    EVENT_NAME, FIRST_NAME, LAST_NAME, FULL_NAME,
    // Renamed from ASSOCIATION_NAME; the alias keeps templates saved before the
    // rename (stored as jsonb) deserialising correctly.
    @JsonAlias("ASSOCIATION_NAME") CLUB_NAME,
    BARCODE_VALUE
}
