/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import java.util.Locale;

/** Which native entity program the process runs: empty or unrecognised keeps the host path, serial
 * runs read nodes on the calling thread, parallel on the pooled workers, and shadow keeps the serial
 * read wave but recomputes every staged search inline and publishes no staged result; read at
 * initialization. */
public final class NativeEntityPhase {

    /** The dormant internal switch; the shipped value is empty and every build stays on the host. */
    public static final String PROPERTY = "arclight.prts.entityNative";

    public enum Mode {
        OFF,
        SERIAL,
        PARALLEL,
        SHADOW
    }

    private static final Mode MODE = parse(System.getProperty(PROPERTY, ""));

    private NativeEntityPhase() {
    }

    public static Mode mode() {
        return MODE;
    }

    public static boolean enabled() {
        return MODE != Mode.OFF;
    }

    static Mode parse(String value) {
        if (value == null) {
            return Mode.OFF;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "serial" -> Mode.SERIAL;
            case "parallel", "true" -> Mode.PARALLEL;
            case "shadow" -> Mode.SHADOW;
            default -> Mode.OFF;
        };
    }
}
