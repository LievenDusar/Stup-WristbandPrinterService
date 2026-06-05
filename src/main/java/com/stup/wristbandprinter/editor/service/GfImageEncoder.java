package com.stup.wristbandprinter.editor.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;

/**
 * Encodes a 1-bit representation of an image as a ZPL {@code ^GFA} graphic field.
 * A pixel is "on" (printed) when its luminance is below the mid-point.
 */
@Profile("!worker")
@Component
public class GfImageEncoder {

    public String encode(BufferedImage img) {
        int width = img.getWidth();
        int height = img.getHeight();
        int bytesPerRow = (width + 7) / 8;
        StringBuilder hex = new StringBuilder();

        for (int y = 0; y < height; y++) {
            for (int bx = 0; bx < bytesPerRow; bx++) {
                int b = 0;
                for (int bit = 0; bit < 8; bit++) {
                    int x = bx * 8 + bit;
                    if (x < width) {
                        int rgb = img.getRGB(x, y);
                        int r = (rgb >> 16) & 0xFF;
                        int gv = (rgb >> 8) & 0xFF;
                        int bv = rgb & 0xFF;
                        int luminance = (int) (0.299 * r + 0.587 * gv + 0.114 * bv);
                        if (luminance < 128) {
                            b |= (1 << (7 - bit));
                        }
                    }
                }
                hex.append(String.format("%02X", b));
            }
        }

        int totalBytes = bytesPerRow * height;
        return String.format("^GFA,%d,%d,%d,%s", totalBytes, totalBytes, bytesPerRow, hex);
    }
}
