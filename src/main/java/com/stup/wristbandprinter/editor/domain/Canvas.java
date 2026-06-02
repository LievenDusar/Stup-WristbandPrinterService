package com.stup.wristbandprinter.editor.domain;

/** Wristband print area in printer dots, plus the printer DPI. */
public record Canvas(int widthDots, int lengthDots, int dpi) {
}
