package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.domain.FreeTextWristbandData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class FreeTextZplGeneratorServiceTest {

    @Mock LogoConversionService stuplogoService;

    private WristbandProperties props;
    private FreeTextZplGeneratorService service;

    @BeforeEach
    void setUp() {
        lenient().when(stuplogoService.getGfCommand()).thenReturn("^GFA,8,8,1,FF");
        lenient().when(stuplogoService.getLogoHeightDots()).thenReturn(100);

        props = new WristbandProperties();
        props.setWidthDots(300);
        props.setLengthDots(3300);
        props.setLogoSideMarginDots(75);

        service = new FreeTextZplGeneratorService(props, stuplogoService);
    }

    private FreeTextWristbandData sampleData() {
        return new FreeTextWristbandData("Backstage", "#FFFFFF");
    }

    @Test
    void generate_producesZplWithStartAndEnd() {
        String zpl = service.generate(sampleData());
        assertThat(zpl).startsWith("^XA");
        assertThat(zpl).endsWith("^XZ");
    }

    @Test
    void generate_containsText() {
        String zpl = service.generate(sampleData());
        assertThat(zpl).contains("Backstage");
    }

    @Test
    void generate_containsStuplogoGfCommandTwice() {
        String zpl = service.generate(sampleData());
        int first = zpl.indexOf("^GFA");
        int second = zpl.indexOf("^GFA", first + 1);
        assertThat(first).isGreaterThanOrEqualTo(0);
        assertThat(second).isGreaterThan(first);
    }

    @Test
    void generate_containsRotationCommand_A0B_forText() {
        String zpl = service.generate(sampleData());
        assertThat(zpl).contains("^A0B");
        assertThat(zpl).doesNotContain("^A0R");
        assertThat(zpl).doesNotContain("^A0I");
    }

    @Test
    void generate_sanitizesCaretInText() {
        FreeTextWristbandData data = new FreeTextWristbandData("Back^stage", "#FFFFFF");
        String zpl = service.generate(data);
        assertThat(zpl).doesNotContain("Back^stage");
        assertThat(zpl).contains("Backstage");
    }

    @Test
    void generate_gapAboveAndBelowTextIsEqualAndConfigured() {
        // logo -> gap -> text -> gap -> logo. Both gaps equal the configured betweenLogoAndText.
        int gap = props.getFreeText().getBetweenLogoAndText();
        int fontSize = props.getFreeText().getFontSize();
        int logoH = 100;

        String zpl = service.generate(sampleData());

        Matcher logo1 = Pattern.compile("\\^FO\\d+,(\\d+)\\^GFA").matcher(zpl);
        assertThat(logo1.find()).isTrue();
        int logo1Y = Integer.parseInt(logo1.group(1));

        Matcher text = Pattern.compile("\\^FO\\d+,(\\d+)\\^A0B," + fontSize + "," + fontSize).matcher(zpl);
        assertThat(text.find()).isTrue();
        int textY = Integer.parseInt(text.group(1));

        Matcher logo2 = Pattern.compile("\\^FO\\d+,(\\d+)\\^GFA").matcher(zpl);
        assertThat(logo2.find()).isTrue();
        assertThat(logo2.find()).isTrue(); // advance to the second match
        int logo2Y = Integer.parseInt(logo2.group(1));

        assertThat(textY - (logo1Y + logoH)).isEqualTo(gap);

        int textLen = (int) ("Backstage".length() * fontSize * FreeTextZplGeneratorService.CHAR_ADVANCE_RATIO);
        assertThat(logo2Y - (textY + textLen)).isEqualTo(gap);
    }

    @Test
    void generate_wholeBlockIsVerticallyCenteredOnTheBand() {
        int gap = props.getFreeText().getBetweenLogoAndText();
        int fontSize = props.getFreeText().getFontSize();
        int logoH = 100;
        int textLen = (int) ("Backstage".length() * fontSize * FreeTextZplGeneratorService.CHAR_ADVANCE_RATIO);
        int totalH = logoH + gap + textLen + gap + logoH;
        int expectedTopY = (props.getLengthDots() - totalH) / 2;

        String zpl = service.generate(sampleData());
        Matcher logo1 = Pattern.compile("\\^FO\\d+,(\\d+)\\^GFA").matcher(zpl);
        assertThat(logo1.find()).isTrue();
        assertThat(Integer.parseInt(logo1.group(1))).isEqualTo(expectedTopY);
    }

    @Test
    void generate_textIsCenteredAcrossBandWidth() {
        int fontSize = props.getFreeText().getFontSize();
        int expectedX = (props.getWidthDots() - fontSize) / 2;

        String zpl = service.generate(sampleData());
        Matcher text = Pattern.compile("\\^FO(\\d+),\\d+\\^A0B," + fontSize + "," + fontSize).matcher(zpl);
        assertThat(text.find()).isTrue();
        assertThat(Integer.parseInt(text.group(1))).isEqualTo(expectedX);
    }

    @Test
    void generate_logosAreCenteredAtConfiguredSideMargin() {
        String zpl = service.generate(sampleData());
        Matcher logo1 = Pattern.compile("\\^FO(\\d+),\\d+\\^GFA").matcher(zpl);
        assertThat(logo1.find()).isTrue();
        assertThat(Integer.parseInt(logo1.group(1))).isEqualTo(props.getLogoSideMarginDots());
    }
}
