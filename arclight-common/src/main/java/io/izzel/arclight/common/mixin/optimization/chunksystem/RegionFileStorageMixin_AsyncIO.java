/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.chunksystem;

import io.izzel.arclight.common.optimization.servercore.ServerCoreConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import io.izzel.arclight.common.optimization.chunksystem.ChunkLoadStats;

/**
 * 异步区块 IO（features.async-chunk-io，默认开）。
 * RegionFileStorage.read 提交专用线程池，行为不变仅转移 IO/CPU 开销。池内重入同步执行防死锁。
 */
@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin_AsyncIO {

    @Shadow protected abstract RegionFile getRegionFile(ChunkPos pos) throws IOException;

    private static final ThreadLocal<Boolean> IN_POOL = ThreadLocal.withInitial(() -> false);
    /** 有界队列 + CallerRuns 拒绝策略：队列满时在调用线程同步读取（自然退化为同步 IO），不会无界堆积 OOM。 */
    private static final int MAX_QUEUE = 256;
    private static volatile ExecutorService POOL;

    private static ExecutorService pool() {
        ExecutorService pool = POOL;
        if (pool == null) {
            synchronized (RegionFileStorageMixin_AsyncIO.class) {
                pool = POOL;
                if (pool == null) {
                    int n = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
                    pool = new ThreadPoolExecutor(n, n, 60L, TimeUnit.SECONDS,
                            new LinkedBlockingQueue<>(MAX_QUEUE),
                            r -> {
                                Thread t = new Thread(r, "PRTS-ChunkIO");
                                t.setDaemon(true);
                                return t;
                            },
                            new ThreadPoolExecutor.CallerRunsPolicy());
                    POOL = pool;
                }
            }
        }
        return pool;
    }

    /**
     * GAP-1 A 段（磁盘 IO／区域文件扇区）服务时间（M4 插桩 · 默认关）。
     * 口径：{@code RegionFileStorage.read} 调用边界（含 NBT 读取，与 B 段在调用层前后相继、不重叠；
     * 边界为近似口径，未核实）。异步 IO 开启时该边界 = 提交 + 等待 + 池内读盘；池内重入不经过本方法，
     * 因此不会与 {@code luminara$asyncRead} 的既有 {@code regionRead} 计数互相叠加成双份。
     */
    @Inject(method = "read", at = @At("HEAD"))
    private void prts$stageABegin(ChunkPos pos, CallbackInfoReturnable<CompoundTag> cir) {
        if (!io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        io.izzel.arclight.common.optimization.chunksystem.ChunkStageTiming.begin(
                io.izzel.arclight.common.optimization.chunksystem.ChunkStageTiming.A);
    }

    @Inject(method = "read", at = @At("RETURN"))
    private void prts$stageAEnd(ChunkPos pos, CallbackInfoReturnable<CompoundTag> cir) {
        if (!io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        io.izzel.arclight.common.optimization.chunksystem.ChunkStageTiming.endAndRecord(pos.toLong());
    }

    @Inject(method = "read", at = @At("HEAD"), cancellable = true)
    private void luminara$asyncRead(ChunkPos pos, CallbackInfoReturnable<CompoundTag> cir) throws IOException {
        if (!ServerCoreConfig.features().asyncChunkIoEnabled()) {
            return;
        }
        if (IN_POOL.get()) {
            return;
        }
        try {
            long start = System.nanoTime();
            CompoundTag result = pool().submit(() -> {
                IN_POOL.set(true);
                try {
                    RegionFile rf = getRegionFile(pos);
                    try (DataInputStream in = rf.getChunkDataInputStream(pos)) {
                        return in == null ? null : NbtIo.read(in);
                    }
                } finally {
                    IN_POOL.set(false);
                }
            }).get();
            io.izzel.arclight.common.optimization.chunksystem.ChunkLoadStats.regionRead(System.nanoTime() - start);
            cir.setReturnValue(result);
            cir.cancel();
        } catch (RejectedExecutionException e) {
            // CallerRunsPolicy 下不应到达，但保留兜底：回退原始同步读取（不 cancel）。
        } catch (Exception e) {
            // 失败则回退到原始同步读取（不 cancel）
        }
    }
}
