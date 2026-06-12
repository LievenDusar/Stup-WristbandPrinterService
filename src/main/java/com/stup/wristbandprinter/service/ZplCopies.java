package com.stup.wristbandprinter.service;

/**
 * Appends the Zebra print-quantity command (^PQ) to a finished ZPL label so the printer
 * emits multiple physical copies from a single stream.
 *
 * <p>Applied on the PRINT path only — never inside {@code WristbandZplResolver}, which is
 * shared with preview endpoints (a preview must stay a single label).</p>
 *
 * <p>{@code ^PQq,p,r,o}: q=quantity, p=pause-between-groups (0), r=replicates (0),
 * o=override-pause (Y) so a continuous wristband roll prints without pausing.</p>
 */
public final class ZplCopies {

    private ZplCopies() {
    }

    public static String apply(String zpl, int copies) {
        if (zpl == null || copies <= 1) {
            return zpl;
        }
        String pq = "^PQ" + copies + ",0,0,Y";
        int idx = zpl.lastIndexOf("^XZ");
        if (idx < 0) {
            return zpl + pq;
        }
        return zpl.substring(0, idx) + pq + zpl.substring(idx);
    }
}
