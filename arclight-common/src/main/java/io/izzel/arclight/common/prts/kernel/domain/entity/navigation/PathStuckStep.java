/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;

/** Pure stuck and timeout state steps of PathNavigation.doStuckDetection. They read only the given
 * frames and never touch a Level, Path or Navigation object. */
public final class PathStuckStep {

    /** The inputs of the stuck check. */
    public record StuckFrame(int tick, int lastStuckCheck, Vec3 lastStuckCheckPos,
                             float mobSpeed, Vec3 tempMobPos) {
    }

    /** The stuck check result; no navigation field is written by this step. */
    public record StuckStep(boolean due, boolean triggered, int lastStuckCheck,
                            Vec3 lastStuckCheckPos) {
    }

    /** The inputs of the timeout check, read from the real state after any stuck stop. */
    public record TimeoutFrame(boolean pathPresent, boolean sameNode, BlockPos nextNodePos,
                               Vec3i timeoutCachedNode, long timeoutTimer,
                               long lastTimeoutCheck, double timeoutLimit, float mobSpeed,
                               Vec3 tempMobPos, long gameTime) {
    }

    /** The timeout check result; the caller applies it in the original order. */
    public record TimeoutStep(boolean branch, boolean triggered, Vec3i timeoutCachedNode,
                              long timeoutTimer, double timeoutLimit, long lastTimeoutCheck) {
    }

    private PathStuckStep() {
    }

    /** Computes the stuck check without applying or stopping anything. */
    public static StuckStep stepStuck(StuckFrame frame) {
        if (frame == null || frame.lastStuckCheckPos() == null || frame.tempMobPos() == null) {
            throw new IllegalArgumentException("null stuck frame field");
        }
        boolean due = frame.tick() - frame.lastStuckCheck() > 100;
        boolean triggered = false;
        if (due) {
            float speed = frame.mobSpeed();
            float normalized = speed >= 1.0F ? speed : speed * speed;
            float threshold = normalized * 100.0F * 0.25F;
            triggered = frame.tempMobPos().distanceToSqr(frame.lastStuckCheckPos())
                < (double) (threshold * threshold);
        }
        return new StuckStep(due, triggered, due ? frame.tick() : frame.lastStuckCheck(),
            due ? frame.tempMobPos() : frame.lastStuckCheckPos());
    }

    /** Computes the timeout check from the state that exists after the stuck step. */
    public static TimeoutStep stepTimeout(TimeoutFrame frame) {
        if (frame == null || frame.timeoutCachedNode() == null || frame.tempMobPos() == null
            || (frame.pathPresent() && frame.nextNodePos() == null)) {
            throw new IllegalArgumentException("null timeout frame field");
        }
        boolean branch = frame.pathPresent();
        boolean triggered = false;
        Vec3i cached = frame.timeoutCachedNode();
        long timer = frame.timeoutTimer();
        double limit = frame.timeoutLimit();
        long lastTimeout = frame.lastTimeoutCheck();
        if (branch) {
            long now = frame.gameTime();
            if (frame.sameNode()) {
                timer += now - lastTimeout;
            } else {
                cached = frame.nextNodePos();
                double distance = frame.tempMobPos().distanceTo(Vec3.atBottomCenterOf(cached));
                limit = frame.mobSpeed() > 0.0F
                    ? distance / (double) frame.mobSpeed() * 20.0D : 0.0D;
            }
            if (limit > 0.0D && (double) timer > limit * 3.0D) {
                triggered = true;
                cached = Vec3i.ZERO;
                timer = 0L;
                limit = 0.0D;
            }
            lastTimeout = now;
        }
        return new TimeoutStep(branch, triggered, cached, timer, limit, lastTimeout);
    }
}