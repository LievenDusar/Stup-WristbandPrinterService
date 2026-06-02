package com.stup.wristbandprinter.editor.domain;

import java.util.UUID;

public record AssetResponse(UUID id, String name, int width, int height) {
}
