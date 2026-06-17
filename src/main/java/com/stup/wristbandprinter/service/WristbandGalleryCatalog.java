package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.domain.WristbandGalleryEntry;
import com.stup.wristbandprinter.domain.WristbandType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * In-memory catalog of all registered wristband band types.
 * Consumed by {@code GET /api/wristbands/gallery}.
 * Sample payloads use fixed demo data; no user input required.
 */
@Profile("!worker")
@Service
public class WristbandGalleryCatalog {

    private static final List<WristbandGalleryEntry> ENTRIES = List.of(

        new WristbandGalleryEntry(
            WristbandType.CREW,
            "Crew wristband",
            "Staff / volunteer band with barcode for shift scanning",
            "/api/wristbands/preview/image",
            """
            {
              "wristbandType":   "crew",
              "eventName":       "Pukkelpop 2026",
              "firstName":       "Annechien",
              "lastName":        "Van De Wall",
              "clubName": "Chiro Sint-Christina Brustem",
              "barcodeValue":    "12345654245524789"
            }
            """
        ),

        new WristbandGalleryEntry(
            WristbandType.PERMIT,
            "Electricity permit",
            "Campsite power-box access permit",
            "/api/wristbands/preview/image",
            """
            {
              "wristbandType": "permit",
              "eventName":   "Pukkelpop 2026",
              "permitLabel": "ELEKTRICITEIT"
            }
            """
        ),

        new WristbandGalleryEntry(
            WristbandType.PERMIT,
            "Parking permit",
            "Vendor / VIP parking access permit",
            "/api/wristbands/preview/image",
            """
            {
              "wristbandType": "permit",
              "eventName":   "Pukkelpop 2026",
              "permitLabel": "PARKING"
            }
            """
        )
    );

    public List<WristbandGalleryEntry> entries() {
        return ENTRIES;
    }
}
