package com.stup.wristbandprinter.editor.service;

import com.stup.wristbandprinter.editor.persistence.TemplateAssetEntity;
import com.stup.wristbandprinter.editor.persistence.TemplateAssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemplateAssetServiceTest {

    @Mock
    private TemplateAssetRepository repository;

    private TemplateAssetService service;

    @BeforeEach
    void setUp() {
        service = new TemplateAssetService(repository, new GfImageEncoder());
    }

    @Test
    void store_decodesDimensionsAndPersists() throws Exception {
        byte[] png = blackPng(40, 20);
        when(repository.save(any(TemplateAssetEntity.class))).thenAnswer(i -> i.getArgument(0));

        var response = service.store("logo.png", png);

        assertThat(response.width()).isEqualTo(40);
        assertThat(response.height()).isEqualTo(20);
        assertThat(response.id()).isNotNull();
    }

    @Test
    void store_rejectsUndecodableBytes() {
        assertThatThrownBy(() -> service.store("x.png", new byte[]{0, 1, 2}))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gfCommand_returnsGfaForStoredAsset() throws Exception {
        UUID id = UUID.randomUUID();
        TemplateAssetEntity e = new TemplateAssetEntity();
        e.setId(id);
        e.setName("logo.png");
        e.setPng(blackPng(40, 20));
        e.setWidth(40);
        e.setHeight(20);
        when(repository.findById(id)).thenReturn(Optional.of(e));

        String gf = service.gfCommand(id, 80, 40, 0);

        assertThat(gf).startsWith("^GFA,");
    }

    @Test
    void gfCommand_returnsEmptyWhenAssetMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThat(service.gfCommand(id, 80, 40, 0)).isEmpty();
    }

    private byte[] blackPng(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
