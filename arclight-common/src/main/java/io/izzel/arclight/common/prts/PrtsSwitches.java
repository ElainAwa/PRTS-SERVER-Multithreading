/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;

/**
 * Resolves the master switch of a PRTS category in the order system property (wins), category file,
 * built-in default - so a category can be disabled even before the configuration directory is read.
 * Resolution never throws: a failure falls back to the built-in default.
 */
public final class PrtsSwitches {

    public static final String FIXES = PrtsConfigManager.FIXES;
    public static final String MODSUPPORT = PrtsConfigManager.MODSUPPORT;
    public static final String PERFORMANCE = PrtsConfigManager.PERFORMANCE;
    public static final String OPTIONAL_SERVERCORE = PrtsConfigManager.OPTIONAL_SERVERCORE;
    public static final String KERNEL = PrtsConfigManager.KERNEL;

    private PrtsSwitches() {
    }

    /** @return true when the category should be applied */
    public static boolean enabled(String category) {
        String property = systemOverride(category);
        if (property != null) {
            return Boolean.parseBoolean(property);
        }
        try {
            return PrtsConfigManager.isEnabled(category);
        } catch (Throwable ignored) {
            return defaultEnabled(category);
        }
    }

    /** @return the raw value of the overriding system property, or null when the file and the default decide */
    public static String systemOverride(String category) {
        try {
            return System.getProperty("arclight.prts." + category);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean defaultEnabled(String category) {
        return switch (category) {
            case FIXES, MODSUPPORT, PERFORMANCE -> true;
            default -> false;
        };
    }
}
