package com.stup.wristbandprinter.controller;

/**
 * Canonical, valid request-body examples for the polymorphic print/preview endpoints.
 *
 * <p>The auto-generated Swagger example for a {@code oneOf} body with a shared enum discriminator
 * is incoherent (it fills {@code wristbandType} with the first enum value regardless of variant and
 * defaults {@code stockColorCode} to an unconfigured {@code 0}). These constants are surfaced as
 * explicit Swagger {@code @ExampleObject}s so "Try it out" produces a correct, copy-pasteable body,
 * and are verified valid by {@code WristbandRequestExamplesTest}.</p>
 */
final class WristbandRequestExamples {

    private WristbandRequestExamples() {
    }

    static final String CREW = """
        {
          "wristbandType": "crew",
          "eventName": "Pukkelpop 2026",
          "firstName": "Annechien",
          "lastName": "Van De Wall",
          "clubName": "Chiro Sint-Christina Brustem",
          "barcodeValue": "12345654245524789",
          "stockColorCode": 1,
          "copies": 1
        }""";

    static final String PERMIT = """
        {
          "wristbandType": "permit",
          "eventName": "Pukkelpop 2026",
          "permitLabel": "ELEKTRICITEIT",
          "stockColorCode": 1,
          "copies": 1
        }""";

    static final String FREETEXT = """
        {
          "wristbandType": "freetext",
          "text": "Backstage",
          "stockColorCode": 1,
          "copies": 1
        }""";
}
