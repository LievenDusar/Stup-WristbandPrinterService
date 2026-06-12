package com.stup.wristbandprinter.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ZplCopiesTest {

    @Test
    void copiesOfOne_leavesZplUnchanged() {
        String zpl = "^XA^FO10,10^FDhi^FS^XZ";
        assertThat(ZplCopies.apply(zpl, 1)).isEqualTo(zpl);
    }

    @Test
    void copiesOfZeroOrLess_leavesZplUnchanged() {
        String zpl = "^XA^XZ";
        assertThat(ZplCopies.apply(zpl, 0)).isEqualTo(zpl);
    }

    @Test
    void insertsPrintQuantityBeforeFinalXZ() {
        String zpl = "^XA^FO10,10^FDhi^FS^XZ";
        assertThat(ZplCopies.apply(zpl, 5)).isEqualTo("^XA^FO10,10^FDhi^FS^PQ5,0,0,Y^XZ");
    }

    @Test
    void insertsBeforeTheLastXZ_whenMultiplePresent() {
        // mimics clear-block + label-block; ^PQ must land in the LAST (label) block
        String zpl = "^XA^IDR:*.*^FS^XZ^XA^FDlabel^FS^XZ";
        assertThat(ZplCopies.apply(zpl, 3))
            .isEqualTo("^XA^IDR:*.*^FS^XZ^XA^FDlabel^FS^PQ3,0,0,Y^XZ");
    }

    @Test
    void noXZ_appendsDefensively() {
        assertThat(ZplCopies.apply("^XAbroken", 2)).isEqualTo("^XAbroken^PQ2,0,0,Y");
    }
}
