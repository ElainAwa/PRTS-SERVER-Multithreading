/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.chunksystem;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.unimi.dsi.fastutil.longs.LongSet;

/** 放宽进服区块发送：批上限 512、初始 32、速率下限由配置控制。 */
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderMixin_ChunkRate {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-ChunkSend");

    /** 初始速率（块/tick）：适度，避免首批发超量。 */
    private static final float START_RATE = 32.0f;

    @Shadow
    @Final
    private LongSet pendingChunks;

    @Shadow
    private float desiredChunksPerTick;

    @Shadow
    private float batchQuota;

    @Shadow
    private int unacknowledgedBatches;

    @Shadow
    private int maxUnacknowledgedBatches;

    @Unique
    private long prts$lastSendLogTick = -1000;

    @Redirect(method = "sendNextChunks",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/server/network/PlayerChunkSender;MAX_CHUNKS_PER_TICK:F"))
    private static float prts$maxBatchSize(float value) {
        return 512.0f;
    }

    @ModifyConstant(method = "onChunkBatchReceivedByClient", constant = @Constant(floatValue = 64.0f))
    private static float prts$maxAckRate(float value) {
        return 512.0f;
    }

    @ModifyConstant(method = "<init>", constant = @Constant(floatValue = 9.0f))
    private static float prts$startRate(float value) {
        return START_RATE;
    }

    @Inject(method = "onChunkBatchReceivedByClient", at = @At("RETURN"))
    private void prts$minDesiredRate(float rate, CallbackInfo ci) {
        float floor = io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig.chunkSendRateFloor;
        if (floor > 0f && this.desiredChunksPerTick < floor) {
            this.desiredChunksPerTick = floor;
        }
    }

    /**
     * GAP-1 F 段（保存 + 网络发送：打包/压缩/加密，按玩家批量）服务时间
     * （M4 插桩 · {@code parallel.chunk-step-telemetry.enabled} · 默认关）。
     * 粒度是「一次 sendNextChunks 调用」，无单块坐标 ⇒ 不参与同区块端到端残差配对。
     */
    @Inject(method = "sendNextChunks", at = @At("HEAD"))
    private void prts$stageFBegin(ServerPlayer player, CallbackInfo ci) {
        if (!io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        io.izzel.arclight.common.optimization.chunksystem.ChunkStageTiming.begin(
                io.izzel.arclight.common.optimization.chunksystem.ChunkStageTiming.F);
    }

    @Inject(method = "sendNextChunks", at = @At("RETURN"))
    private void prts$stageFEnd(ServerPlayer player, CallbackInfo ci) {
        if (!io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        io.izzel.arclight.common.optimization.chunksystem.ChunkStageTiming.endAndRecord(
                io.izzel.arclight.common.optimization.chunksystem.ChunkStageTiming.NO_POS);
    }

    @Inject(method = "sendNextChunks", at = @At("HEAD"))
    private void prts$sendTelemetry(ServerPlayer player, CallbackInfo ci) {
        long tick = player.serverLevel().getGameTime();
        if (tick - this.prts$lastSendLogTick < 200) {
            return;
        }
        this.prts$lastSendLogTick = tick;
        LOGGER.info("[chunk-send] player={} pending={} unacked={}/{} desired={} quota={}",
                player.getGameProfile().getName(), this.pendingChunks.size(),
                this.unacknowledgedBatches, this.maxUnacknowledgedBatches,
                this.desiredChunksPerTick, this.batchQuota);
    }
}
