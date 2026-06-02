package com.stup.wristbandprinter.editor.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PreviewColorServiceTest {

    private final PreviewColorService service = new PreviewColorService();

    @Test
    void tint_whiteOrBlank_returnsBytesUnchanged() throws Exception {
        byte[] png = png(Color.WHITE, Color.BLACK);
        assertThat(service.tint(png, "white")).isEqualTo(png);
        assertThat(service.tint(png, "  ")).isEqualTo(png);
        assertThat(service.tint(png, null)).isEqualTo(png);
    }

    @Test
    void tint_namedColour_recoloursWhitePixelsButKeepsBlack() throws Exception {
        byte[] png = png(Color.WHITE, Color.BLACK);
        BufferedImage out = read(service.tint(png, "red"));

        // pixel (0,0) was white → becomes red; pixel (1,0) was black → stays dark
        assertThat(new Color(out.getRGB(0, 0))).isEqualTo(Color.RED);
        Color dark = new Color(out.getRGB(1, 0));
        assertThat(dark.getRed() + dark.getGreen() + dark.getBlue()).isLessThan(60);
    }

    @Test
    void tint_hexColour_isAccepted() throws Exception {
        byte[] png = png(Color.WHITE, Color.BLACK);
        BufferedImage out = read(service.tint(png, "#00FF00"));
        assertThat(new Color(out.getRGB(0, 0))).isEqualTo(Color.GREEN);
    }

    private byte[] png(Color left, Color right) throws Exception {
        BufferedImage img = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, left.getRGB());
        img.setRGB(1, 0, right.getRGB());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private BufferedImage read(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }
}
