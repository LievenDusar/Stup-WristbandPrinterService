package com.stup.wristbandprinter.editor.service;

import com.stup.wristbandprinter.editor.domain.AssetResponse;
import com.stup.wristbandprinter.editor.persistence.TemplateAssetEntity;
import com.stup.wristbandprinter.editor.persistence.TemplateAssetRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Profile("!worker")
@Service
public class TemplateAssetService {

    private final TemplateAssetRepository repository;
    private final GfImageEncoder gfImageEncoder;

    public TemplateAssetService(TemplateAssetRepository repository, GfImageEncoder gfImageEncoder) {
        this.repository = repository;
        this.gfImageEncoder = gfImageEncoder;
    }

    @Transactional
    public AssetResponse store(String name, byte[] png) {
        BufferedImage img = decode(png);
        TemplateAssetEntity e = new TemplateAssetEntity();
        e.setId(UUID.randomUUID());
        e.setName(name);
        e.setPng(png);
        e.setWidth(img.getWidth());
        e.setHeight(img.getHeight());
        e.setCreatedAt(Instant.now());
        TemplateAssetEntity saved = repository.save(e);
        return new AssetResponse(saved.getId(), saved.getName(), saved.getWidth(), saved.getHeight());
    }

    @Transactional(readOnly = true)
    public Optional<byte[]> rawPng(UUID id) {
        return repository.findById(id).map(TemplateAssetEntity::getPng);
    }

    /**
     * Returns the {@code ^GF...} graphic command for the asset scaled to the target size and
     * rotated by the given multiple of 90°, or an empty string if the asset is unknown.
     */
    @Transactional(readOnly = true)
    public String gfCommand(UUID assetId, int targetWidthDots, int targetHeightDots, int rotation) {
        return repository.findById(assetId)
            .map(e -> {
                BufferedImage img = decode(e.getPng());
                BufferedImage scaled = scale(img, Math.max(1, targetWidthDots), Math.max(1, targetHeightDots));
                BufferedImage rotated = rotate(scaled, ((rotation % 360) + 360) % 360);
                return gfImageEncoder.encode(rotated);
            })
            .orElse("");
    }

    private BufferedImage decode(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) {
                throw new IllegalArgumentException("Could not decode image bytes as a supported format");
            }
            return img;
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read image bytes: " + e.getMessage(), e);
        }
    }

    private BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return result;
    }

    private BufferedImage rotate(BufferedImage img, int degrees) {
        if (degrees == 0) {
            return img;
        }
        boolean swap = degrees == 90 || degrees == 270;
        int w = swap ? img.getHeight() : img.getWidth();
        int h = swap ? img.getWidth() : img.getHeight();
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.translate((w - img.getWidth()) / 2.0, (h - img.getHeight()) / 2.0);
        g.rotate(Math.toRadians(degrees), img.getWidth() / 2.0, img.getHeight() / 2.0);
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return result;
    }
}
