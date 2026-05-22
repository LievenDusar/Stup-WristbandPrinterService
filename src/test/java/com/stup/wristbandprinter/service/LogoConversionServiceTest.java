package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.exception.LogoNotFoundException;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.*;

class LogoConversionServiceTest {

    private WristbandProperties defaultProps() {
        WristbandProperties props = new WristbandProperties();
        props.setWidthDots(203);
        props.setLogoSideMarginDots(10);
        return props;
    }

    @Test
    void loadAndConvertLogo_producesValidGfCommand() throws Exception {
        // Create a small test PNG on disk
        File tmpPng = File.createTempFile("test-logo", ".png");
        BufferedImage img = new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 100, 50);
        g.dispose();
        ImageIO.write(img, "png", tmpPng);

        WristbandProperties props = defaultProps();
        props.setLogoPath(tmpPng.getAbsolutePath());

        LogoConversionService service = new LogoConversionService(props);
        service.loadAndConvertLogo();

        String gf = service.getGfCommand();
        assertThat(gf).startsWith("^GFA,");
        assertThat(service.getLogoHeightDots()).isGreaterThan(0);

        tmpPng.delete();
    }

    @Test
    void loadAndConvertLogo_throwsLogoNotFoundException_whenFileDoesNotExist() {
        WristbandProperties props = defaultProps();
        props.setLogoPath("/nonexistent/path/logo.png");

        LogoConversionService service = new LogoConversionService(props);

        assertThatThrownBy(service::loadAndConvertLogo)
            .isInstanceOf(LogoNotFoundException.class)
            .hasMessageContaining("Logo not found");
    }
}
