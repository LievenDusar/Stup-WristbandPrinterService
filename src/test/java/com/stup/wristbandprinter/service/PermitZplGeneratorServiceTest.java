package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.domain.CodeSymbology;
import com.stup.wristbandprinter.domain.PermitWristbandData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermitZplGeneratorServiceTest {

    @Mock LogoConversionService stuplogoService;
    @Mock PermitEventLogoService eventLogoService;

    private WristbandProperties props;
    private PermitZplGeneratorService service;

    @BeforeEach
    void setUp() {
        lenient().when(stuplogoService.getGfCommand()).thenReturn("^GFA,8,8,1,FF");
        lenient().when(stuplogoService.getLogoHeightDots()).thenReturn(100);
        lenient().when(eventLogoService.isAvailable()).thenReturn(false); // no event logo by default

        props = new WristbandProperties();
        props.setWidthDots(300);
        props.setLengthDots(3300);
        props.setLogoSideMarginDots(75);

        service = new PermitZplGeneratorService(props, stuplogoService, eventLogoService);
    }

    private PermitWristbandData sampleData(boolean hasCode) {
        return new PermitWristbandData(
            "Pukkelpop 2026",
            "ELEKTRICITEIT",
            null,
            hasCode ? "EL-001" : null,
            CodeSymbology.CODE128,
            "#FFFFFF"
        );
    }

    @Test
    void generate_producesZplWithStartAndEnd() {
        String zpl = service.generate(sampleData(false));
        assertThat(zpl).startsWith("^XA");
        assertThat(zpl).endsWith("^XZ");
    }

    @Test
    void generate_containsPermitLabel() {
        String zpl = service.generate(sampleData(false));
        assertThat(zpl).contains("Toelating ELEKTRICITEIT");
    }

    @Test
    void generate_containsEventName() {
        String zpl = service.generate(sampleData(false));
        assertThat(zpl).contains("Pukkelpop 2026");
    }

    @Test
    void generate_containsStuplogoGfCommand() {
        String zpl = service.generate(sampleData(false));
        assertThat(zpl).contains("^GFA,8,8,1,FF");
    }

    @Test
    void generate_withoutCode_doesNotContainBarcodeCommand() {
        String zpl = service.generate(sampleData(false));
        assertThat(zpl).doesNotContain("^BC").doesNotContain("^BQ");
    }

    @Test
    void generate_withCode_containsCode128Command() {
        String zpl = service.generate(sampleData(true));
        assertThat(zpl).contains("^BCB");
    }

    @Test
    void generate_noAssociation_containsDots() {
        String zpl = service.generate(sampleData(false));
        // Default dot count is 20 — check at least 5 consecutive dots
        assertThat(zpl).contains(".....");
    }

    @Test
    void generate_withAssociation_containsAssocName() {
        PermitWristbandData data = new PermitWristbandData(
            "Pukkelpop 2026", "PARKING", "STUP vzw", null, CodeSymbology.CODE128, "#FFFFFF");
        String zpl = service.generate(data);
        assertThat(zpl).contains("STUP vzw");
        // Dots should NOT appear when an association name is given
        assertThat(zpl).doesNotContain(".....");
    }

    @Test
    void generate_containsRotationCommand_A0B_forPermitLabel() {
        String zpl = service.generate(sampleData(false));
        assertThat(zpl).contains("^A0B");
    }

    @Test
    void generate_containsRotationCommand_A0B_forEventName() {
        // Event name runs along the band length (^A0B, 270°/bottom-up), reading the same
        // direction as block 2, not across the narrow width (^A0R/^A0I), so it never overflows.
        String zpl = service.generate(sampleData(false));
        assertThat(zpl).contains("^A0B");
        assertThat(zpl).doesNotContain("^A0R");
        assertThat(zpl).doesNotContain("^A0I");
    }

    @Test
    void generate_withEventLogo_containsLogoTwice() {
        when(eventLogoService.isAvailable()).thenReturn(true);
        when(eventLogoService.getGfCommand()).thenReturn("^GFA,8,8,1,AA");
        when(eventLogoService.getLogoHeightDots()).thenReturn(80);

        String zpl = service.generate(sampleData(false));
        // STUP logo appears once; event logo appears once → total 2 GF commands
        int firstGf  = zpl.indexOf("^GFA");
        int secondGf = zpl.indexOf("^GFA", firstGf + 1);
        assertThat(secondGf).isGreaterThan(firstGf);
    }

    @Test
    void generate_sanitizesCaretInPermitLabel() {
        PermitWristbandData data = new PermitWristbandData(
            "Event", "ELEKTRI^CITEIT", null, null, CodeSymbology.CODE128, "#FFFFFF");
        String zpl = service.generate(data);
        assertThat(zpl).doesNotContain("ELEKTRI^CITEIT");
        assertThat(zpl).contains("ELEKTRICITEIT");
    }
}
