package com.stup.wristbandprinter.editor.service;

import com.stup.wristbandprinter.domain.WristbandData;

/** Canned data used to render catalog/thumbnail previews. */
public final class SampleData {

    private SampleData() {
    }

    public static final WristbandData WRISTBAND = new WristbandData(
        "Pukkelpop 2026", "Annechien", "Van De Wall",
        "Chiro Sint-Christina Brustem", "12345654245524789");
}
