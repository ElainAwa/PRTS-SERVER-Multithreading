/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** The two folds a run-start range seal carries: one over the cells its batch covers, one over the
 * status map it proves. Neither is trusted on the way in: a witness recomputes both. The space fold
 * repeats the write-controlled range encoding, which a test pins to this one. */
public final class RangeDigests {

    /** The declared bound of the cells one seal may name. */
    public static final int MAX_CELLS = 16;

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    /** Whether a cell list may be sealed at all: at least one, at most {@value #MAX_CELLS}, strictly
     * ascending and therefore distinct. */
    public static boolean wellFormed(long[] cells) {
        if (cells == null || cells.length < 1 || cells.length > MAX_CELLS) {
            return false;
        }
        for (int index = 1; index < cells.length; index++) {
            if (cells[index] <= cells[index - 1]) {
                return false;
            }
        }
        return true;
    }

    /** The fold of one sealed range over its cells; empty text for a range that may not be sealed. */
    public static String spaceDigest(long[] cells) {
        if (!wellFormed(cells)) {
            return "";
        }
        List<String> tokens = new ArrayList<>(cells.length);
        for (long cell : cells) {
            tokens.add("CHUNK:" + Long.toUnsignedString(cell, 16));
        }
        return Long.toHexString(fold(new TreeSet<>(tokens)));
    }

    /** The fold of a per-cell status map in ascending cell order. Empty text for an empty map: a
     * seal that proves no cell is not a seal. */
    public static String statusMapDigest(Map<Long, FrozenDeclarations.CellStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return "";
        }
        long[] cells = new long[statuses.size()];
        int index = 0;
        for (Long cell : statuses.keySet()) {
            cells[index++] = cell == null ? 0L : cell;
        }
        Arrays.sort(cells);
        StringBuilder text = new StringBuilder(cells.length * 24);
        for (long cell : cells) {
            FrozenDeclarations.CellStatus status = statuses.get(cell);
            text.append(segment(Long.toUnsignedString(cell, 16)));
            text.append(segment(status == null ? "" : status.targetStatus()));
        }
        return String.format("%016x", fold(text.toString()));
    }

    private static String segment(String value) {
        String text = value == null ? "" : value;
        return text.length() + ":" + text;
    }

    private static long fold(Iterable<String> tokens) {
        long hash = OFFSET_BASIS;
        for (String token : tokens) {
            hash = fold(hash, token);
        }
        return hash;
    }

    private static long fold(String text) {
        return fold(OFFSET_BASIS, text);
    }

    private static long fold(long hash, String text) {
        long value = hash;
        for (int index = 0; index < text.length(); index++) {
            value = (value ^ text.charAt(index)) * PRIME;
        }
        return value;
    }

    private RangeDigests() {
    }
}
