/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Times every ticker of the block entity list of one level, and owns the bounded DEVICE plan of the
 * same level tick: the plan is opened after the segment timer inside the same HEAD injector, a tick
 * that exceeds its budget waits for this very method's RETURN (same tick, same thread), and the
 * module-level drain is only the fallback. A deferred tick is called exactly once and unchanged; the
 * call is never cancelled, run in parallel or reordered, and the plan never depends on the relative
 * order of two mixin HEAD injectors. */
package io.izzel.arclight.common.prts.fixes.observation;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.observe.BlockEntityAttribution;
import io.izzel.arclight.common.prts.kernel.quota.DeviceTickDeferral;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.support.PrtsBlockEntityCosts;
import io.izzel.arclight.common.prts.support.PrtsCpuClock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(Level.class)
public abstract class PrtsBlockEntityTickCostMixin {

    @Unique
    private long prts$segmentStartedAt;

    @Unique
    private boolean prts$devicePlanAttempted;

    @Unique
    private long prts$deviceTickIndex = -1L;

    @Unique
    private long prts$deviceEpoch;

    @Inject(method = "tickBlockEntities", at = @At("HEAD"))
    private void prts$openSegment(CallbackInfo ci) {
        if (PrtsBlockEntityCosts.installed()) {
            prts$segmentStartedAt = System.nanoTime();
        }
        Level level = (Level) (Object) this;
        prts$devicePlanAttempted = false;
        prts$openDevicePlan(level);
    }

    @Inject(method = "tickBlockEntities", at = @At("RETURN"))
    private void prts$closeSegment(CallbackInfo ci) {
        Level level = (Level) (Object) this;
        prts$closeDevicePlan(level);
        if (!PrtsBlockEntityCosts.installed()) {
            return;
        }
        PrtsBlockEntityCosts.segmentTick(level.dimension().location().toString(),
            System.nanoTime() - prts$segmentStartedAt);
    }

    @WrapOperation(method = "tickBlockEntities", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
    private void prts$timeBlockEntityTick(TickingBlockEntity ticker, Operation<Void> original) {
        Level level = (Level) (Object) this;
        if (!prts$devicePlanAttempted) {
            // The HEAD injector did not reach this invocation: open the plan lazily with the same
            // level/tick/epoch/budget, so the plan never depends on injection order.
            prts$openDevicePlan(level);
        }
        if (DeviceTickDeferral.defer(level, ticker)) {
            // The same tick is called at this method's RETURN, once and unchanged.
            return;
        }
        boolean report = PrtsBlockEntityCosts.installed();
        boolean planActive = DeviceTickDeferral.active(level);
        if (!report && !planActive) {
            original.call(ticker);
            return;
        }
        long startedAt = System.nanoTime();
        long cpuStartedAt = report ? PrtsCpuClock.now() : -1L;
        try {
            original.call(ticker);
        } finally {
            long wallNanos = System.nanoTime() - startedAt;
            if (planActive) {
                DeviceTickDeferral.noteInline(level, wallNanos);
            }
            if (report) {
                String worldId = level.dimension().location().toString();
                long cpuNanos = PrtsCpuClock.since(cpuStartedAt);
                PrtsBlockEntityCosts.blockEntityTick(ticker, level, worldId, wallNanos, cpuNanos);
                BlockEntityAttribution.noteTick(ticker, level, worldId, wallNanos, cpuNanos);
            }
        }
    }

    @Unique
    private void prts$openDevicePlan(Level level) {
        prts$devicePlanAttempted = true;
        if (!KernelSettings.enabled() || !KernelSettings.degradeActions()
            || !KernelSettings.shareTable()) {
            return;
        }
        KernelModule module = KernelModule.instance();
        ShareTable table = module.shares().lastTable();
        if (table == null) {
            return;
        }
        String worldId = level.dimension().location().toString();
        ShareTable.ShareRow row = table.row(worldId, ShareClass.BLOCKENTITY);
        if (row == null || row.shareMs() <= 0.0) {
            return;
        }
        long budgetNanos = Math.round(row.shareMs() * 1_000_000.0);
        long epoch = module.guard().worldEpochs().epochOf(worldId);
        long tickIndex = module.tickIndex();
        if (DeviceTickDeferral.begin(level, worldId, tickIndex, epoch, budgetNanos, true)) {
            prts$deviceTickIndex = tickIndex;
            prts$deviceEpoch = epoch;
        }
    }

    @Unique
    private void prts$closeDevicePlan(Level level) {
        if (prts$deviceTickIndex >= 0L) {
            DeviceTickDeferral.end(level, prts$deviceTickIndex, prts$deviceEpoch);
            prts$deviceTickIndex = -1L;
        }
        if (DeviceTickDeferral.hasWaiting(level)) {
            // The same tick's safe point: this is the RETURN of the very call that deferred them.
            KernelModule module = KernelModule.instance();
            DeviceTickDeferral.drainScope(level, module.guard().worldEpochs()::epochOf);
        }
    }
}
