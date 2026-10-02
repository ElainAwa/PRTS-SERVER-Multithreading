/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport.minecolonies;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.support.PrtsModSupportStats;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replays the mod's own compatibility discovery step by step, catching the step that walks the
 * creative tabs: one mod whose configuration is not loaded yet would otherwise end the whole
 * sequence and leave the monster list empty, which logs an error on every later query.
 */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@LoadIfMod(modid = "minecolonies", condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "com.minecolonies.api.compatibility.CompatibilityManager", remap = false)
public abstract class PrtsMineColoniesDiscoverMixin {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-minecolonies");

    @Invoker("clear")
    protected abstract void prts$clear();

    @Invoker("discoverAllItems")
    protected abstract void prts$discoverAllItems(Level level);

    @Invoker("discoverModCompat")
    protected abstract void prts$discoverModCompat();

    @Invoker("discoverCompostRecipes")
    protected abstract void prts$discoverCompostRecipes(RecipeManager recipeManager);

    @Invoker("discoverMobs")
    protected abstract void prts$discoverMobs();

    @Inject(method = "discover", at = @At("HEAD"), cancellable = true, remap = false)
    private void prts$discoverResilient(RecipeManager recipeManager, Level level, CallbackInfo ci) {
        if (!PrtsConfigManager.feature(PrtsConfigManager.MODSUPPORT,
            "guard-minecolonies-compat-discovery")) {
            return;
        }
        prts$clear();
        try {
            prts$discoverAllItems(level);
        } catch (Throwable thrown) {
            PrtsModSupportStats.count("minecolonies-compat-scan-failures-caught");
            LOGGER.warn("[PRTS-modsupport] compatibility scan failed, the remaining steps still run: {}",
                thrown.toString());
        }
        prts$discoverModCompat();
        prts$discoverCompostRecipes(recipeManager);
        prts$discoverMobs();
        PrtsModSupportStats.count("minecolonies-compat-discoveries-guarded");
        ci.cancel();
    }

    @Redirect(method = "getAllMonsters", at = @At(value = "INVOKE",
        target = "Lorg/apache/logging/log4j/Logger;error(Ljava/lang/String;)V"), remap = false)
    private void prts$quietEmptyMonsterList(Logger logger, String message) {
        if (!PrtsConfigManager.feature(PrtsConfigManager.MODSUPPORT,
            "guard-minecolonies-compat-discovery")) {
            logger.error(message);
            return;
        }
        PrtsModSupportStats.count("minecolonies-empty-monster-lists-suppressed");
    }
}
