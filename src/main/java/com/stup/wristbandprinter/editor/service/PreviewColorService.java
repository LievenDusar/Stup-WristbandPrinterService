package com.stup.wristbandprinter.editor.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * Tints a Labelary preview PNG (black content on white) so the background reflects the
 * physical wristband stock colour. White content blends to the chosen colour; dark content
 * stays dark. White/blank/null is a no-op (Labelary already renders on white).
 */
@Profile("!worker")
@Service
public class PreviewColorService {

    private static final Map<String, Color> PALETTE = Map.of(
        "white", Color.WHITE,
        "red", Color.RED,
        "blue", new Color(0x1E, 0x88, 0xE5),
        "green", new Color(0x43, 0xA0, 0x47),
        "yellow", new Color(0xFD, 0xD8, 0x35),
        "orange", new Color(0xFB, 0x8C, 0x00),
        "pink", new Color(0xEC, 0x40, 0x7A),
        "black", Color.BLACK
    );

    public byte[] tint(byte[] png, String color) {
        if (color == null || color.isBlank() || color.trim().equalsIgnoreCase("white")) {
            return png;
        }
        Color bg = resolve(color.trim());
        if (bg == null) {
            return png; // unknown colour name → leave unchanged rather than fail the preview
        }
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
            if (src == null) {
                return png;
            }
            BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < src.getHeight(); y++) {
                for (int x = 0; x < src.getWidth(); x++) {
                    int rgb = src.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                    double darkness = 1.0 - ((0.299 * r + 0.587 * g + 0.114 * b) / 255.0);
                    int nr = (int) Math.round(bg.getRed() * (1 - darkness));
                    int ng = (int) Math.round(bg.getGreen() * (1 - darkness));
                    int nb = (int) Math.round(bg.getBlue() * (1 - darkness));
                    out.setRGB(x, y, (nr << 16) | (ng << 8) | nb);
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(out, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            return png;
        }
    }

    private Color resolve(String color) {
        if (color.startsWith("#")) {
            try {
                return Color.decode(color);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return PALETTE.get(color.toLowerCase(Locale.ROOT));
    }
}
