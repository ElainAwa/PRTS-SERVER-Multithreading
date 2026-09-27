package io.izzel.arclight.common.mod;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.util.log.ArclightI18nLogger;
import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.mixin.MixinTools;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.connect.IMixinConnector;

public class ArclightConnector implements IMixinConnector {

    public static final Logger LOGGER = ArclightI18nLogger.getLogger("Arclight");

    @Override
    public void connect() {
        MixinTools.setup();
        Mixins.addConfiguration("mixins.arclight.core.json");
        Mixins.addConfiguration("mixins.arclight.bukkit.json");
        switch (ArclightPlatform.current()) {
            case VANILLA -> Mixins.addConfiguration("mixins.arclight.vanilla.json");
            case FORGE -> Mixins.addConfiguration("mixins.arclight.forge.json");
            case NEOFORGE -> Mixins.addConfiguration("mixins.arclight.neoforge.json");
        }
        LOGGER.info("mixin-load.core");
        Mixins.addConfiguration("mixins.arclight.impl.optimization.json");
        LOGGER.info("mixin-load.optimization");
        // PRTS-owned categories: one file per category under prts-config/ decides whether the
        // category is applied, so a whole category can be dropped (or handed to the new kernel)
        // without touching the upstream configurations. Configuration is read this early on
        // purpose: that is where the mixin plugins resolve their switches.
        PrtsConfigManager.ensureAndLoad();
        Mixins.addConfiguration("prts-fixes.mixins.json");
        Mixins.addConfiguration("prts-modsupport.mixins.json");
        Mixins.addConfiguration("prts-performance.mixins.json");
        Mixins.addConfiguration("prts-optional-servercore.mixins.json");
        LOGGER.info("mixin-load.prts");
    }
}
