/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.navigation;

import io.izzel.arclight.common.prts.kernel.domain.entity.navigation.PathStuckStep;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Behaviour: with the internal path-stuck-step switch declared, only the exact NMS
 * GroundPathNavigation and Villager classes (not mod subclasses) plus the current living, non-NoAI,
 * non-passenger, awake Villager slice are replaced by the pure PathStuckStep steps; the target's
 * virtual stop() is called in the original order. Why this point: doStuckDetection is the only
 * owner of those fields and receives the temp mob position. Invariant: reads stay inside their
 * original branches and getters are read only on the branch that uses them, stuck writes precede the
 * post-stop timeout read, the switch is
 * default-off, and the delta is applied exactly once. Failure semantics: helper rejection propagates
 * before any write; any other navigation, mob or subclass keeps the original body. */
@Mixin(PathNavigation.class)
public abstract class PrtsPathStuckStepMixin {

    @Shadow @Final protected Mob mob;
    @Shadow @Final protected Level level;
    @Shadow protected Path path;
    @Shadow protected int tick;
    @Shadow protected int lastStuckCheck;
    @Shadow protected Vec3 lastStuckCheckPos;
    @Shadow protected Vec3i timeoutCachedNode;
    @Shadow protected long timeoutTimer;
    @Shadow protected long lastTimeoutCheck;
    @Shadow protected double timeoutLimit;
    @Shadow private boolean isStuck;
    @Shadow public abstract void stop();

    /** The diagnostic switch; false keeps the original method body and its virtual stop path. */
    private static final String PROPERTY = "arclight.prts.pathStuckStep";

    @Inject(method = "doStuckDetection", at = @At("HEAD"), cancellable = true)
    private void prts$pureStuckStep(Vec3 tempMobPos, CallbackInfo ci) {
        if (!Boolean.getBoolean(PROPERTY)) {
            return;
        }
        if (((Object) this).getClass() != GroundPathNavigation.class) {
            return;
        }
        if (mob.getClass() != Villager.class) {
            return;
        }
        Villager villager = (Villager) mob;
        if (villager.isNoAi() || villager.isPassenger() || villager.isRemoved()
            || !villager.isAlive() || villager.getPose() == Pose.SLEEPING) {
            return;
        }
        if (tick - lastStuckCheck > 100) {
            PathStuckStep.StuckStep stuck = PathStuckStep.stepStuck(
                new PathStuckStep.StuckFrame(tick, lastStuckCheck, lastStuckCheckPos,
                    mob.getSpeed(), tempMobPos));
            if (stuck.triggered()) {
                isStuck = true;
                stop();
            } else {
                isStuck = false;
            }
            lastStuckCheck = stuck.lastStuckCheck();
            lastStuckCheckPos = stuck.lastStuckCheckPos();
        }
        if (path != null && !path.isDone()) {
            BlockPos next = path.getNextNodePos();
            long gameTime = level.getGameTime();
            boolean same = next.equals(timeoutCachedNode);
            float speed = same ? 0.0F : mob.getSpeed();
            PathStuckStep.TimeoutStep timeout = PathStuckStep.stepTimeout(
                new PathStuckStep.TimeoutFrame(true, same, next, timeoutCachedNode, timeoutTimer,
                    lastTimeoutCheck, timeoutLimit, speed, tempMobPos, gameTime));
            if (timeout.triggered()) {
                timeoutCachedNode = Vec3i.ZERO;
                timeoutTimer = 0L;
                timeoutLimit = 0.0D;
                isStuck = false;
                stop();
            } else {
                timeoutCachedNode = timeout.timeoutCachedNode();
                timeoutTimer = timeout.timeoutTimer();
                timeoutLimit = timeout.timeoutLimit();
            }
            lastTimeoutCheck = timeout.lastTimeoutCheck();
        }
        ci.cancel();
    }
}