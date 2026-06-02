package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.exception.LogoNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.*;

class LogoConversionServiceTest {

    @TempDir
    Path tempDir;

    private WristbandProperties defaultProps() {
        WristbandProperties props = new WristbandProperties();
        props.setWidthDots(203);
        props.setLogoSideMarginDots(10);
        return props;
    }

    @Test
    void loadAndConvertLogo_producesValidGfCommand() throws Exception {
        File tmpPng = tempDir.resolve("test-logo.png").toFile();
        BufferedImage img = new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 100, 50);
        g.dispose();
        ImageIO.write(img, "png", tmpPng);

        WristbandProperties props = defaultProps();
        props.setLogoPath(tmpPng.getAbsolutePath());

        LogoConversionService service = new LogoConversionService(props, new com.stup.wristbandprinter.editor.service.GfImageEncoder());
        service.loadAndConvertLogo();

        String gf = service.getGfCommand();
        assertThat(gf).startsWith("^GFA,");
        assertThat(service.getLogoHeightDots()).isGreaterThan(0);
    }

    @Test
    void loadAndConvertLogo_throwsLogoNotFoundException_whenFileDoesNotExist() {
        WristbandProperties props = defaultProps();
        props.setLogoPath("/nonexistent/path/logo.png");

        LogoConversionService service = new LogoConversionService(props, new com.stup.wristbandprinter.editor.service.GfImageEncoder());

        assertThatThrownBy(service::loadAndConvertLogo)
            .isInstanceOf(LogoNotFoundException.class)
            .hasMessageContaining("Logo not found");
    }
}
