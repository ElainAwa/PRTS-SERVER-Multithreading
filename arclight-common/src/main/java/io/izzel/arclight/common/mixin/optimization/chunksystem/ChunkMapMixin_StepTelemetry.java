/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.chunksystem;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.chunksystem.ChunkStageTiming;
import io.izzel.arclight.common.optimization.chunksystem.ChunkSystemStats;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * 状态步遥测（M1 阶段一仪表化，零语义变化）：测量 {@code ChunkMap.applyStep}
 * 内联耗时并按目标状态分桶，同时统计 FEATURES 段的并发度（H1 锁串行判据）。
 * 调度器关闭时不记录（与原版路径无交互）。
 *
 * <p>GAP-1 端到端埋点（M4 插桩 · 授权键 {@code parallel.chunk-step-telemetry.enabled} · 默认关）：
 * 既有 {@code stepMs} 的守卫<b>原样不动</b>（仍由 {@code chunk-system-scheduler.enabled} 门控，
 * 既有计数器语义不变）；新增的两个 handler 走<b>新键</b>，因此 vanilla A 臂也能出端到端读数
 * （QA FIND-g02：{@code e2eMs} 直方图的唯一写入者被 {@code chunk-system-enabled} 挡住 ⇒ A 臂恒空）。
 * 端到端 = 同一区块首次 {@code applyStep} 进入 → {@code FULL} 步 future 完成（后者见
 * {@code GenerationChunkHolderMixin_CompleteNotify}）。</p>
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin_StepTelemetry {

    @Inject(method = "applyStep", at = @At("HEAD"))
    private void prts$stepBegin(GenerationChunkHolder holder, ChunkStep step,
                                StaticCache2D<GenerationChunkHolder> cache,
                                CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!PRTSFeaturesConfig.chunkSystemSchedulerEnabled) {
            return;
        }
        ChunkSystemStats.stepBegin(step.targetStatus());
    }

    @Inject(method = "applyStep", at = @At("RETURN"))
    private void prts$stepEnd(GenerationChunkHolder holder, ChunkStep step,
                              StaticCache2D<GenerationChunkHolder> cache,
                              CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!PRTSFeaturesConfig.chunkSystemSchedulerEnabled) {
            return;
        }
        ChunkSystemStats.stepEnd(step.targetStatus());
    }

    /**
     * GAP-1 端到端起点（新键门控，两臂共用）：同一区块首次进入 {@code applyStep} 时开始计时；
     * 重复进入（同一区块的多个状态步）不改写起点（{@code putIfAbsent}）。
     */
    @Inject(method = "applyStep", at = @At("HEAD"))
    private void prts$stageE2eEnter(GenerationChunkHolder holder, ChunkStep step,
                                    StaticCache2D<GenerationChunkHolder> cache,
                                    CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.e2eEnter(holder.getPos().toLong());
    }
}
