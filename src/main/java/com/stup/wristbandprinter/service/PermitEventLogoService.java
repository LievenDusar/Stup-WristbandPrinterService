package com.stup.wristbandprinter.service;

import com.stup.wristbandprinter.config.WristbandProperties;
import com.stup.wristbandprinter.editor.service.GfImageEncoder;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Loads and caches the permit-band event logo as a ZPL {@code ^GF} command.
 * Follows the same pipeline as {@link LogoConversionService}: scale to band width,
 * pre-rotate 180°, encode as ZPL graphic field.
 *
 * <p>When the configured image is not found or cannot be decoded, the service
 * degrades gracefully — {@link #getGfCommand()} returns {@code null} and
 * {@link #getLogoHeightDots()} returns {@code 0}. The permit ZPL generator
 * omits the event-logo block in this case.</p>
 */
@Profile("!worker")
@Service
public class PermitEventLogoService {

    private static final Logger log = LoggerFactory.getLogger(PermitEventLogoService.class);

    private final WristbandProperties props;
    private final GfImageEncoder gfImageEncoder;

    private String cachedGfCommand;
    private int logoHeightDots;

    public PermitEventLogoService(WristbandProperties props, GfImageEncoder gfImageEncoder) {
        this.props = props;
        this.gfImageEncoder = gfImageEncoder;
    }

    @PostConstruct
    public void loadAndConvertLogo() {
        String logoPath = props.getPermit().getEventLogoPath();
        log.info("Loading permit event logo from: {}", logoPath);
        try {
            Resource resource = resolveResource(logoPath);
            if (!resource.exists()) {
                log.warn("Permit event logo not found at '{}' — event-logo block will be omitted", logoPath);
                return;
            }
            BufferedImage original = ImageIO.read(resource.getInputStream());
            if (original == null) {
                log.warn("Could not decode permit event logo at '{}' — event-logo block will be omitted", logoPath);
                return;
            }

            int targetWidth = props.getWidthDots() - 2 * props.getLogoSideMarginDots();
            int targetHeight = (int) ((double) original.getHeight() / original.getWidth() * targetWidth);

            BufferedImage scaled = scaleImage(original, targetWidth, targetHeight);
            // Pre-rotate 180° — same orientation as the STUP logo
            BufferedImage rotated = rotate180(scaled);
            this.logoHeightDots = rotated.getHeight();
            this.cachedGfCommand = gfImageEncoder.encode(rotated);

            log.info("Permit event logo converted successfully. Dimensions: {}x{} dots", targetWidth, targetHeight);
        } catch (IOException e) {
            log.warn("Failed to load permit event logo '{}': {} — event-logo block will be omitted",
                    logoPath, e.getMessage());
        }
    }

    /**
     * Returns the cached {@code ^GF} ZPL command, or {@code null} if the logo was not loaded.
     */
    public String getGfCommand() {
        return cachedGfCommand;
    }

    /**
     * Returns the logo height in dots, or {@code 0} if the logo was not loaded.
     */
    public int getLogoHeightDots() {
        return logoHeightDots;
    }

    /** Returns {@code true} when the logo was successfully loaded and is ready to use. */
    public boolean isAvailable() {
        return cachedGfCommand != null;
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

    private Resource resolveResource(String path) {
        if (path.startsWith("classpath:")) {
            return new ClassPathResource(path.substring("classpath:".length()));
        }
        return new FileSystemResource(path);
    }
}
