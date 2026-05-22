package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.domain.WristbandData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ZplGeneratorServiceTest {

    @Mock
    private LogoConversionService logoConversionService;

    private ZplGeneratorService service;

    @BeforeEach
    void setUp() {
        when(logoConversionService.getGfCommand()).thenReturn("^GFA,8,8,1,FF000000");
        when(logoConversionService.getLogoHeightDots()).thenReturn(50);

        WristbandProperties props = new WristbandProperties();
        service = new ZplGeneratorService(props, logoConversionService);
    }

    @Test
    void generate_producesZplWithStartAndEndCommands() {
        WristbandData data = sampleData();
        String zpl = service.generate(data);
        assertThat(zpl).startsWith("^XA");
        assertThat(zpl).endsWith("^XZ");
    }

    @Test
    void generate_containsTextRotationCommand() {
        String zpl = service.generate(sampleData());
        assertThat(zpl).contains("^A0B");
    }

    @Test
    void generate_containsBarcodeRotationCommand() {
        String zpl = service.generate(sampleData());
        assertThat(zpl).contains("^BCB");
    }

    @Test
    void generate_containsLogoGraphicField() {
        String zpl = service.generate(sampleData());
        assertThat(zpl).contains("^GFA");
    }

    @Test
    void generate_containsAllTextFields() {
        WristbandData data = sampleData();
        String zpl = service.generate(data);
        assertThat(zpl).contains("Pukkelpop 2026");
        assertThat(zpl).contains("Jan");
        assertThat(zpl).contains("Janssens");
        assertThat(zpl).contains("STUP vzw");
        assertThat(zpl).contains("123456789");
    }

    @Test
    void generate_stripsCaret_fromUserInput() {
        WristbandData data = new WristbandData(
            "Event^Name", "Ja^n", "Jan^ssens", "STUP^vzw", "^123"
        );
        String zpl = service.generate(data);
        assertThat(zpl).doesNotContain("Event^Name");
        assertThat(zpl).contains("EventName");
    }

    @Test
    void generate_containsLogoDuplicatedTwice() {
        String zpl = service.generate(sampleData());
        // logo GF command appears twice (top and bottom logo)
        int firstOccurrence = zpl.indexOf("^GFA,8,8");
        int secondOccurrence = zpl.indexOf("^GFA,8,8", firstOccurrence + 1);
        assertThat(secondOccurrence).isGreaterThan(firstOccurrence);
    }

    private WristbandData sampleData() {
        return new WristbandData("Pukkelpop 2026", "Jan", "Janssens", "STUP vzw", "123456789");
    }
}
