/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;

/**
 * Resolves the master switch of a PRTS category (docs/PRTS-CONVENTIONS.md, C-003 and C-004).
 *
 * <p>Resolution order, so a category can be disabled even before the configuration directory has
 * been read (mixin plugins run that early):</p>
 * <ol>
 *   <li>system property {@code -Darclight.prts.<category>=false} — always available, wins;</li>
 *   <li>{@code prts-config/<category>.yml} through {@link PrtsConfigManager};</li>
 *   <li>the built-in default of the category.</li>
 * </ol>
 *
 * <p>Resolution never throws: a failure falls back to the built-in default, so a broken
 * configuration cannot turn a category on by accident.</p>
 */
public final class PrtsSwitches {

    /** Category of correctness fixes and shared PRTS infrastructure. */
    public static final String FIXES = PrtsConfigManager.FIXES;
    /** Category of interoperability work for mods on the registered mod list. */
    public static final String MODSUPPORT = PrtsConfigManager.MODSUPPORT;
    /** Category of performance work that does not land on a new-kernel seam. */
    public static final String PERFORMANCE = PrtsConfigManager.PERFORMANCE;
    /** Optional ServerCore layer; opt-in and free of kernel coupling. */
    public static final String OPTIONAL_SERVERCORE = PrtsConfigManager.OPTIONAL_SERVERCORE;
    /** Placeholder category reserved for the new kernel. */
    public static final String KERNEL = PrtsConfigManager.KERNEL;

    private PrtsSwitches() {
    }

    /**
     * Returns whether the given category is enabled.
     *
     * @param category one of the category constants of this class
     * @return {@code true} when the category should be applied
     */
    public static boolean enabled(String category) {
        String property = System.getProperty("arclight.prts." + category);
        if (property != null) {
            return Boolean.parseBoolean(property);
        }
        try {
            return PrtsConfigManager.isEnabled(category);
        } catch (Throwable ignored) {
            return defaultEnabled(category);
        }
    }

    private static boolean defaultEnabled(String category) {
        return switch (category) {
            case FIXES, MODSUPPORT, PERFORMANCE -> true;
            default -> false;
        };
    }
}
