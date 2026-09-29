/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport;

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
 * Keeps one failing compatibility scan from taking the remaining scan steps of the colony mod down.
 *
 * <p>That mod discovers what the pack can do in a fixed sequence of steps, and the step that walks
 * the creative tabs reaches into the configuration of every other mod. One mod whose configuration
 * is not loaded yet throws out of that step, which ends the whole sequence: the step that fills the
 * monster list never runs, its set stays empty, and every later query on it logs an error - in a
 * live colony that is thousands of lines a minute. The sequence is therefore run here step by step,
 * with the failing step caught, so the steps behind it still run and the empty state disappears.
 * The error line the empty state produced is dropped as well, since a scan that completed no longer
 * reaches it.</p>
 *
 * <p>Every step is called through an invoker of the mod's own private method: the names are the
 * mod's, the parameter types are the game's, and nothing here is compiled against the mod. A mod
 * build whose sequence or step names moved makes the injection fail loudly at start instead of
 * silently skipping a step. The switch is on by default: this one stops an error, it does not trade
 * work for it.</p>
 */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@LoadIfMod(modid = "minecolonies", condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "com.minecolonies.api.compatibility.CompatibilityManager", remap = false)
public abstract class PrtsMineColoniesDiscoverMixin {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-minecolonies");

    /** Drops the previous scan results. */
    @Invoker("clear")
    protected abstract void prts$clear();

    /**
     * Walks the creative tabs and every item of the pack.
     *
     * @param level level the discovery runs for
     */
    @Invoker("discoverAllItems")
    protected abstract void prts$discoverAllItems(Level level);

    /** Runs the compatibility check of the installed mods. */
    @Invoker("discoverModCompat")
    protected abstract void prts$discoverModCompat();

    /**
     * Reads the compost recipes.
     *
     * @param recipeManager recipe manager of the server
     */
    @Invoker("discoverCompostRecipes")
    protected abstract void prts$discoverCompostRecipes(RecipeManager recipeManager);

    /** Fills the monster list. */
    @Invoker("discoverMobs")
    protected abstract void prts$discoverMobs();

    /**
     * Runs the discovery sequence with the item step guarded.
     *
     * @param recipeManager recipe manager of the server
     * @param level         level the discovery runs for
     * @param ci            callback handle
     */
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

    /**
     * Drops the error line an empty monster list would produce.
     *
     * @param logger  logger of the mod
     * @param message message the mod would log
     */
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
