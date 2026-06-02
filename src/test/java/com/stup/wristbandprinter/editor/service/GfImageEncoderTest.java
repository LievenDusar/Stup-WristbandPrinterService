package com.stup.wristbandprinter.editor.service;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class GfImageEncoderTest {

    private final GfImageEncoder encoder = new GfImageEncoder();

    @Test
    void encode_emitsGfaHeaderWithCorrectByteCounts() {
        // 8x1 all-black image → 1 byte per row, 1 row → totalBytes=1, bytesPerRow=1
        BufferedImage img = new BufferedImage(8, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 8, 1);
        g.dispose();

        String gf = encoder.encode(img);

        assertThat(gf).startsWith("^GFA,1,1,1,");
        assertThat(gf).endsWith("FF"); // 8 black bits = 0xFF
    }

    @Test
    void encode_whitePixelsProduceZeroBytes() {
        BufferedImage img = new BufferedImage(8, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 8, 1);
        g.dispose();

        assertThat(encoder.encode(img)).isEqualTo("^GFA,1,1,1,00");
    }
}
