/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport.create;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.support.PrtsModSupportStats;
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
 * Skips the funnel pickup when the mod's filtering behaviour cannot be read: the mod calls
 * the null answer without a check and the throw would end the block tick. Off by default, and
 * fully reflective, so a different mod build keeps the mod's own behaviour.
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
                PrtsModSupportStats.count("create-funnel-pickups-skipped");
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // Unrecognised mod build: leave the block to its own code.
        }
    }
}
