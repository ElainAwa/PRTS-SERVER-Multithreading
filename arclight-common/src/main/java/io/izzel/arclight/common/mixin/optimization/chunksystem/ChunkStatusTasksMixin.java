/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.chunksystem;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.chunksystem.ChunkStageTiming;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * GAP-1 C/D/E 段<b>服务时间</b>（M4 插桩 · 独立分支 feat/gap-telemetry · <b>默认关</b>）。
 *
 * <p>口径：只夹状态任务方法体的<b>同步区间</b>（HEAD→RETURN），即 E7 §5.1 中 C（生成数值）、
 * D（光照/高度图/位压缩）、E（对象落地）三类工作；异步续段的等待不在本读数内（等待由
 * {@code ChunkStageTiming.waitNanos} 的 queue/lock/barrier 分栏承载）。段号由
 * {@link ChunkStageTiming#stageForStatus} 按 {@code step.targetStatus()} 映射，与
 * {@code ChunkMapMixin_StepTelemetry} 使用同一张表。</p>
 *
 * <p>零语义变更：不 cancel、不改动返回值、不改既有 {@code stepMs} 计数器（后者仍由
 * {@code chunk-system-scheduler.enabled} 门控）。关闭开关时只有一个 volatile 读。</p>
 *
 * <p>方法名与描述符来源：NeoForge 编译产物 {@code javap}（{@code ChunkStatusTasks} 12 个任务方法
 * 均为 {@code static (WorldGenContext, ChunkStep, StaticCache2D, ChunkAccess) → CompletableFuture}）。</p>
 */
@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksMixin {

    /** 12 个任务方法共用的描述符（{@code ChunkStatusTasks} 内同名方法唯一）。 */
    private static final String DESC =
            "(Lnet/minecraft/world/level/chunk/status/WorldGenContext;"
                    + "Lnet/minecraft/world/level/chunk/status/ChunkStep;"
                    + "Lnet/minecraft/util/StaticCache2D;"
                    + "Lnet/minecraft/world/level/chunk/ChunkAccess;)"
                    + "Ljava/util/concurrent/CompletableFuture;";

    @Inject(method = "generateStructureStarts" + DESC, at = @At("HEAD"))
    private static void prts$beginGenerateStructureStarts(WorldGenContext context, ChunkStep step,
                                               StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                               CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        // C 生成结构起点
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.begin(ChunkStageTiming.stageForStatus(step.targetStatus()));
    }

    @Inject(method = "generateStructureStarts" + DESC, at = @At("RETURN"))
    private static void prts$endGenerateStructureStarts(WorldGenContext context, ChunkStep step,
                                             StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                             CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.endAndRecord(chunk.getPos().toLong());
    }

    @Inject(method = "loadStructureStarts" + DESC, at = @At("HEAD"))
    private static void prts$beginLoadStructureStarts(WorldGenContext context, ChunkStep step,
                                               StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                               CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        // C 读盘结构起点
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.begin(ChunkStageTiming.stageForStatus(step.targetStatus()));
    }

    @Inject(method = "loadStructureStarts" + DESC, at = @At("RETURN"))
    private static void prts$endLoadStructureStarts(WorldGenContext context, ChunkStep step,
                                             StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                             CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.endAndRecord(chunk.getPos().toLong());
    }

    @Inject(method = "generateStructureReferences" + DESC, at = @At("HEAD"))
    private static void prts$beginGenerateStructureReferences(WorldGenContext context, ChunkStep step,
                                               StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                               CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        // C 结构引用
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.begin(ChunkStageTiming.stageForStatus(step.targetStatus()));
    }

    @Inject(method = "generateStructureReferences" + DESC, at = @At("RETURN"))
    private static void prts$endGenerateStructureReferences(WorldGenContext context, ChunkStep step,
                                             StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                             CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.endAndRecord(chunk.getPos().toLong());
    }

    @Inject(method = "generateBiomes" + DESC, at = @At("HEAD"))
    private static void prts$beginGenerateBiomes(WorldGenContext context, ChunkStep step,
                                               StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                               CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        // C 群系
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.begin(ChunkStageTiming.stageForStatus(step.targetStatus()));
    }

    @Inject(method = "generateBiomes" + DESC, at = @At("RETURN"))
    private static void prts$endGenerateBiomes(WorldGenContext context, ChunkStep step,
                                             StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                             CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.endAndRecord(chunk.getPos().toLong());
    }

    @Inject(method = "generateNoise" + DESC, at = @At("HEAD"))
    private static void prts$beginGenerateNoise(WorldGenContext context, ChunkStep step,
                                               StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                               CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        // C 噪声/密度
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.begin(ChunkStageTiming.stageForStatus(step.targetStatus()));
    }

    @Inject(method = "generateNoise" + DESC, at = @At("RETURN"))
    private static void prts$endGenerateNoise(WorldGenContext context, ChunkStep step,
                                             StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                             CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.endAndRecord(chunk.getPos().toLong());
    }

    @Inject(method = "generateSurface" + DESC, at = @At("HEAD"))
    private static void prts$beginGenerateSurface(WorldGenContext context, ChunkStep step,
                                               StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                               CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        // C 地表
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.begin(ChunkStageTiming.stageForStatus(step.targetStatus()));
    }

    @Inject(method = "generateSurface" + DESC, at = @At("RETURN"))
    private static void prts$endGenerateSurface(WorldGenContext context, ChunkStep step,
                                             StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                             CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.endAndRecord(chunk.getPos().toLong());
    }

    @Inject(method = "generateCarvers" + DESC, at = @At("HEAD"))
    private static void prts$beginGenerateCarvers(WorldGenContext context, ChunkStep step,
                                               StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                               CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        // C 雕刻
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.begin(ChunkStageTiming.stageForStatus(step.targetStatus()));
    }

    @Inject(method = "generateCarvers" + DESC, at = @At("RETURN"))
    private static void prts$endGenerateCarvers(WorldGenContext context, ChunkStep step,
                                             StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                             CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.endAndRecord(chunk.getPos().toLong());
    }

    @Inject(method = "generateFeatures" + DESC, at = @At("HEAD"))
    private static void prts$beginGenerateFeatures(WorldGenContext context, ChunkStep step,
                                               StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                               CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        // C 特征
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.begin(ChunkStageTiming.stageForStatus(step.targetStatus()));
    }

    @Inject(method = "generateFeatures" + DESC, at = @At("RETURN"))
    private static void prts$endGenerateFeatures(WorldGenContext context, ChunkStep step,
                                             StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                             CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.endAndRecord(chunk.getPos().toLong());
    }

    @Inject(method = "initializeLight" + DESC, at = @At("HEAD"))
    private static void prts$beginInitializeLight(WorldGenContext context, ChunkStep step,
                                               StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                               CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        // D 光照初始化
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.begin(ChunkStageTiming.stageForStatus(step.targetStatus()));
    }

    @Inject(method = "initializeLight" + DESC, at = @At("RETURN"))
    private static void prts$endInitializeLight(WorldGenContext context, ChunkStep step,
                                             StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                             CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.endAndRecord(chunk.getPos().toLong());
    }

    @Inject(method = "light" + DESC, at = @At("HEAD"))
    private static void prts$beginLight(WorldGenContext context, ChunkStep step,
                                               StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                               CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        // D 光照 BFS
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.begin(ChunkStageTiming.stageForStatus(step.targetStatus()));
    }

    @Inject(method = "light" + DESC, at = @At("RETURN"))
    private static void prts$endLight(WorldGenContext context, ChunkStep step,
                                             StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                             CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.endAndRecord(chunk.getPos().toLong());
    }

    @Inject(method = "generateSpawn" + DESC, at = @At("HEAD"))
    private static void prts$beginGenerateSpawn(WorldGenContext context, ChunkStep step,
                                               StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                               CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        // E 刷怪落地
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.begin(ChunkStageTiming.stageForStatus(step.targetStatus()));
    }

    @Inject(method = "generateSpawn" + DESC, at = @At("RETURN"))
    private static void prts$endGenerateSpawn(WorldGenContext context, ChunkStep step,
                                             StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                             CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.endAndRecord(chunk.getPos().toLong());
    }

    @Inject(method = "full" + DESC, at = @At("HEAD"))
    private static void prts$beginFull(WorldGenContext context, ChunkStep step,
                                               StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                               CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        // E 区块对象落地
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.begin(ChunkStageTiming.stageForStatus(step.targetStatus()));
    }

    @Inject(method = "full" + DESC, at = @At("RETURN"))
    private static void prts$endFull(WorldGenContext context, ChunkStep step,
                                             StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                             CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        ChunkStageTiming.endAndRecord(chunk.getPos().toLong());
    }
}
