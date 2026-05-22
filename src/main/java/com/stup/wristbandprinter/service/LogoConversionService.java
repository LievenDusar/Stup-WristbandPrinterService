package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.exception.LogoNotFoundException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;

@Service
public class LogoConversionService {

    private static final Logger log = LoggerFactory.getLogger(LogoConversionService.class);

    private final WristbandProperties props;
    private String cachedGfCommand;
    private int logoHeightDots;

    public LogoConversionService(WristbandProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void loadAndConvertLogo() {
        String logoPath = props.getLogoPath();
        log.info("Loading logo from: {}", logoPath);
        try {
            Resource resource = resolveResource(logoPath);
            if (!resource.exists()) {
                throw new LogoNotFoundException("Logo not found at: " + logoPath);
            }
            BufferedImage original = ImageIO.read(resource.getInputStream());
            if (original == null) {
                throw new LogoNotFoundException("Could not decode image at: " + logoPath);
            }

            int targetWidth = props.getWidthDots() - 2 * props.getLogoSideMarginDots();
            int targetHeight = (int) ((double) original.getHeight() / original.getWidth() * targetWidth);
            this.logoHeightDots = targetHeight;

            BufferedImage scaled = scaleImage(original, targetWidth, targetHeight);
            // Pre-rotate 180° — both logos are printed upside down on the wristband
            BufferedImage rotated = rotate180(scaled);
            this.cachedGfCommand = encodeAsGf(rotated);

            log.info("Logo converted successfully. Dimensions: {}x{} dots", targetWidth, targetHeight);
        } catch (IOException e) {
            throw new LogoNotFoundException("Failed to load logo: " + e.getMessage(), e);
        }
    }

    public String getGfCommand() {
        return cachedGfCommand;
    }

    public int getLogoHeightDots() {
        return logoHeightDots;
    }

    private BufferedImage scaleImage(BufferedImage src, int w, int h) {
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return result;
    }

    private BufferedImage rotate180(BufferedImage img) {
        BufferedImage result = new BufferedImage(img.getWidth(), img.getHeight(), img.getType());
        Graphics2D g = result.createGraphics();
        g.rotate(Math.PI, img.getWidth() / 2.0, img.getHeight() / 2.0);
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return result;
    }

    private String encodeAsGf(BufferedImage img) {
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
                        int luminance = (r + gv + bv) / 3;
                        if (luminance < 128) {  // dark pixel → print
                            b |= (1 << (7 - bit));
                        }
                    }
                }
                hex.append(String.format("%02X", b));
            }
        }

        int totalBytes = bytesPerRow * height;
        return String.format("^GFA,%d,%d,%d,%s", hex.length(), totalBytes, bytesPerRow, hex);
    }

    private Resource resolveResource(String path) {
        if (path.startsWith("classpath:")) {
            return new ClassPathResource(path.substring("classpath:".length()));
        }
        return new FileSystemResource(path);
    }
}
