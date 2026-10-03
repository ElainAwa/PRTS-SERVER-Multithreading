/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The living-entity fields the whole-tick model reads to pin a row and writes back when the tick
 * changes them: the walk animation and run bookkeeping, the last block position, the applied
 * scale, and the state flags whose pinned value decides whether the model answers at all. Each
 * accessor is a plain field read or write with no behaviour attached.
 */
package io.izzel.arclight.common.prts.fixes.ownership;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(LivingEntity.class)
public interface PrtsLivingEntityTickStateMixin {

    @Accessor("run")
    float prts$run();

    @Accessor("run")
    void prts$setRun(float value);

    @Accessor("oRun")
    float prts$oRun();

    @Accessor("oRun")
    void prts$setORun(float value);

    @Accessor("animStep")
    float prts$animStep();

    @Accessor("animStepO")
    float prts$animStepO();

    @Accessor("animStepO")
    void prts$setAnimStepO(float value);

    @Accessor("animStep")
    void prts$setAnimStep(float value);

    @Accessor("attackAnim")
    float prts$attackAnim();

    @Accessor("swimAmount")
    float prts$swimAmount();

    @Accessor("swimAmountO")
    float prts$swimAmountO();

    @Accessor("appliedScale")
    float prts$appliedScale();

    @Accessor("lastPos")
    BlockPos prts$lastPos();

    @Accessor("lastPos")
    void prts$setLastPos(BlockPos pos);

    @Accessor("lastHurtByPlayerTime")
    int prts$lastHurtByPlayerTime();

    @Accessor("lastHurtByPlayer")
    Player prts$lastHurtByPlayer();

    @Accessor("effectsDirty")
    boolean prts$effectsDirty();

    @Accessor("noJumpDelay")
    int prts$noJumpDelay();

    @Accessor("jumping")
    boolean prts$jumping();

    @Accessor("lerpSteps")
    int prts$lerpSteps();

    @Accessor("lerpHeadSteps")
    int prts$lerpHeadSteps();

    @Accessor("autoSpinAttackTicks")
    int prts$autoSpinAttackTicks();

    @Accessor("fallFlyTicks")
    int prts$fallFlyTicks();
}
