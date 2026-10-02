/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;

/** Resolves a PRTS category master switch: system property, then category file, then built-in default. */
public final class PrtsSwitches {

    public static final String FIXES = PrtsConfigManager.FIXES;
    public static final String MODSUPPORT = PrtsConfigManager.MODSUPPORT;
    public static final String PERFORMANCE = PrtsConfigManager.PERFORMANCE;
    public static final String OPTIONAL_SERVERCORE = PrtsConfigManager.OPTIONAL_SERVERCORE;
    public static final String KERNEL = PrtsConfigManager.KERNEL;

    private PrtsSwitches() {
    }

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
