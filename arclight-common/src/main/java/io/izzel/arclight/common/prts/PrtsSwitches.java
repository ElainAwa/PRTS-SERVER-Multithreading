package io.izzel.arclight.common.prts;

import io.izzel.arclight.i18n.ArclightConfig;

/**
 * Master switches for the PRTS-owned categories (docs/PRTS-CONVENTIONS.md, C-003).
 *
 * <p>Resolution order, so that a category can be disabled even before the server configuration is
 * parsed (mixin plugins run that early):</p>
 * <ol>
 *   <li>system property {@code -Darclight.prts.<category>=false} — always available, wins;</li>
 *   <li>the {@code prts { ... }} section of {@code arclight.conf}, when it has been loaded;</li>
 *   <li>the built-in default of the category.</li>
 * </ol>
 *
 * <p>Reading the configuration never throws: a failure falls back to the built-in default, so a
 * broken config cannot turn a category on by accident.</p>
 */
public final class PrtsSwitches {

    public static final String FIXES = "fixes";
    public static final String MODSUPPORT = "modsupport";
    public static final String PERFORMANCE = "performance";
    public static final String OPTIONAL_SERVERCORE = "optional-servercore";

    private PrtsSwitches() {
    }

    public static boolean enabled(String category) {
        String property = System.getProperty("arclight.prts." + category);
        if (property != null) {
            return Boolean.parseBoolean(property);
        }
        try {
            var spec = ArclightConfig.spec().getPrts();
            if (spec != null) {
                return switch (category) {
                    case FIXES -> spec.isFixesEnabled();
                    case MODSUPPORT -> spec.isModsupportEnabled();
                    case PERFORMANCE -> spec.isPerformanceEnabled();
                    case OPTIONAL_SERVERCORE -> spec.isOptionalServerCoreEnabled();
                    default -> false;
                };
            }
        } catch (Throwable ignored) {
            // configuration not loaded yet (or unreadable): use the built-in default
        }
        return defaultEnabled(category);
    }

    private static boolean defaultEnabled(String category) {
        return switch (category) {
            case FIXES, MODSUPPORT, PERFORMANCE -> true;
            // Optional layers are opt-in by construction.
            default -> false;
        };
    }
}
