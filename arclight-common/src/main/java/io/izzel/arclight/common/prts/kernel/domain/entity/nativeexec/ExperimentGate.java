/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

/** Shared read-only parser for experiment switches. An absent, empty or unrecognised value always
 * means disabled, so shipped behaviour stays identical when no switch is provided. */
public final class ExperimentGate {

    private ExperimentGate() {
    }

    /** Returns true only for the explicit affirmative spellings, compared after trimming. */
    public static boolean parse(String raw) {
        if (raw == null) return false;
        String value = raw.trim();
        return value.equals("1") || value.equalsIgnoreCase("true")
            || value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("on");
    }
}