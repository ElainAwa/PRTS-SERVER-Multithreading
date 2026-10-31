/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

/** The internal gate of the quota cost probes. It is off by default; a controlled start may turn
 * it on with the system property below. It is not a config option or readout key, and the
 * disabled path costs one property read plus one null check and changes no behaviour. */
public final class QuotaCostGate {

    /** Controlled-start switch, not a config key; absent means off. */
    public static final String PROPERTY = "arclight.prts.quotaCost";


    private static volatile Boolean testOverride;

    private QuotaCostGate() {
    }

    public static boolean enabled() {
        Boolean override = testOverride;
        return override != null ? override : Boolean.getBoolean(PROPERTY);
    }

    /** Test-only switch, kept beside the controlled-start property. */
    public static void setEnabledForTests(boolean value) {
        testOverride = value;
    }

    /** Removes the test override so the property decides again. */
    public static void clearTestOverride() {
        testOverride = null;
    }

}
