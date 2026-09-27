package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

/**
 * Switches for the PRTS-owned code tree (see docs/PRTS-CONVENTIONS.md, C-003).
 *
 * <p>Every category under {@code io.izzel.arclight.common.prts} has exactly one master switch, so a
 * category can be turned off as a whole when the new kernel takes over the seam it serves.</p>
 */
@ConfigSerializable
public class PrtsSpec {

    @Setting("fixes")
    private CategorySpec fixes;

    @Setting("modsupport")
    private CategorySpec modsupport;

    @Setting("performance")
    private CategorySpec performance;

    @Setting("optional")
    private OptionalSpec optional;

    public boolean isFixesEnabled() {
        return fixes == null || fixes.isEnabled();
    }

    public boolean isModsupportEnabled() {
        return modsupport == null || modsupport.isEnabled();
    }

    public boolean isPerformanceEnabled() {
        return performance == null || performance.isEnabled();
    }

    /** Optional layers are opt-in: a missing section means "off". */
    public boolean isOptionalServerCoreEnabled() {
        return optional != null && optional.getServercore() != null && optional.getServercore().isEnabled();
    }

    @ConfigSerializable
    public static class CategorySpec {

        @Setting("enabled")
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }
    }

    @ConfigSerializable
    public static class OptionalSpec {

        @Setting("servercore")
        private CategorySpec servercore;

        public CategorySpec getServercore() {
            return servercore;
        }
    }
}
