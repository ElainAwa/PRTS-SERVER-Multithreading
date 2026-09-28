/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Keeps a funnel pickup from throwing when the funnel has no filtering behaviour.
 *
 * <p>The mod asks its block entity for the filtering behaviour and calls the answer without a null
 * check. When that lookup answers null (the block entity is not there, or is already gone) the
 * following call throws out of the block tick and takes the server down. The guard skips that one
 * pickup attempt, which the next tick retries anyway.</p>
 *
 * <p>The switch is off by default: the null answer was observed on a server whose funnels were
 * ticked off the main thread, and a stock server has no reading that would justify changing the
 * behaviour of a funnel. The mod is optional, so every lookup here is reflective and a mod build
 * whose internals differ simply leaves the block to its own code.</p>
 */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@LoadIfMod(modid = "create", condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "com.simibubi.create.content.logistics.funnel.FunnelBlock", remap = false)
public abstract class PrtsCreateFunnelPickupGuardMixin {

    private static final String BEHAVIOUR_PACKAGE = "com.simibubi.create.foundation.blockEntity.behaviour.";
    private static final String BEHAVIOUR = BEHAVIOUR_PACKAGE + "BlockEntityBehaviour";
    private static final String BEHAVIOUR_TYPE = BEHAVIOUR_PACKAGE + "BehaviourType";
    private static final String FILTERING = BEHAVIOUR_PACKAGE + "filtering.FilteringBehaviour";

    private static Method behaviourLookup;
    private static Object filteringType;
    private static boolean resolved;

    /**
     * Skips a pickup whose filtering behaviour cannot be read.
     *
     * @param state block state of the funnel
     * @param level level the funnel is in
     * @param pos position of the funnel
     * @param entity entity standing inside the funnel
     * @param ci callback handle
     */
    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true,
        remap = false)
    private void prts$skipPickupWithoutBehaviour(BlockState state, Level level, BlockPos pos, Entity entity,
                                                 CallbackInfo ci) {
        if (!PrtsConfigManager.feature(PrtsConfigManager.MODSUPPORT, "guard-create-funnel-pickup", false)) {
            return;
        }
        try {
            if (!resolved) {
                resolved = true;
                Class<?> behaviour = Class.forName(BEHAVIOUR);
                Field type = Class.forName(FILTERING).getField("TYPE");
                behaviourLookup = behaviour.getMethod("get", BlockGetter.class, BlockPos.class,
                    Class.forName(BEHAVIOUR_TYPE));
                filteringType = type.get(null);
            }
            if (behaviourLookup != null && behaviourLookup.invoke(null, level, pos, filteringType) == null) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // Unrecognised mod build: leave the block to its own code.
        }
    }
}
