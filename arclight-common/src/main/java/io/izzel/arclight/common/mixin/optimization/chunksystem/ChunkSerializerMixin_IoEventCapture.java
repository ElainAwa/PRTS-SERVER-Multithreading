/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.chunksystem;

import io.izzel.arclight.common.optimization.chunksystem.guards.ChunkIoEventCaptureBus;
import net.neoforged.bus.api.IEventBus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import io.izzel.arclight.common.optimization.eventbridge.EventBusStats;

/**
 * IO 反序列化事件捕获注入面（M2.2 四件套 ③，M2.3 修正）。
 *
 * <p>原方案对 {@code net.neoforged.bus.EventBus.post} 做 mixin 注入，
 * 但该类由 bootstrap 层在一切 arclight mixin 配置装配前加载，注入永不
 * 生效（{@code EventBusStats} javadoc 实证结论，M2.3 集成冒烟复核：
 * 捕获计数恒 0 且运行期无 mixin 报错）。
 *
 * <p>修正：{@code ChunkSerializer}（游戏层，mixin 可达）的 {@code read}
 * 内两处 {@code NeoForge.EVENT_BUS} 静态读重定向到
 * {@link ChunkIoEventCaptureBus#wrapIfCapturing}——IO 反序列化捕获作用域内
 * 返回包装总线（{@code post} 入主线程延迟队列），作用域外原样直通。
 * 事件只发射一次且落在主线程，与基线「主线程反序列化期间发射」语义等价。
 */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerMixin_IoEventCapture {

    @Redirect(method = "read",
            at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
                    target = "Lnet/neoforged/neoforge/common/NeoForge;EVENT_BUS:Lnet/neoforged/bus/api/IEventBus;"))
    private static IEventBus prts$captureBus() {
        return ChunkIoEventCaptureBus.wrapIfCapturing(net.neoforged.neoforge.common.NeoForge.EVENT_BUS);
    }

    /**
     * GAP-1 B 段（解压 + 结构解析：{@code CompoundTag} → ProtoChunk）服务时间
     * （M4 插桩 · {@code parallel.chunk-step-telemetry.enabled} · 默认关）。
     * 与 A 段（{@code RegionFileStorage.read}）在调用层前后相继，不重叠；只读计时，零语义变更。
     * 描述符取自 NeoForge 编译产物（NeoForge 为 {@code read} 补了 {@code RegionStorageInfo} 形参）。
     */
    @Inject(method = "read(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/ai/village/poi/PoiManager;Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/chunk/ProtoChunk;",
            at = @At("HEAD"))
    private static void prts$stageBBegin(ServerLevel level, PoiManager poiManager, RegionStorageInfo info,
                                         ChunkPos pos, CompoundTag tag, CallbackInfoReturnable<ProtoChunk> cir) {
        if (!io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        io.izzel.arclight.common.optimization.chunksystem.ChunkStageTiming.begin(
                io.izzel.arclight.common.optimization.chunksystem.ChunkStageTiming.B);
    }

    @Inject(method = "read(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/ai/village/poi/PoiManager;Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/chunk/ProtoChunk;",
            at = @At("RETURN"))
    private static void prts$stageBEnd(ServerLevel level, PoiManager poiManager, RegionStorageInfo info,
                                       ChunkPos pos, CompoundTag tag, CallbackInfoReturnable<ProtoChunk> cir) {
        if (!io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig.chunkStepTelemetryEnabled) {
            return;
        }
        io.izzel.arclight.common.optimization.chunksystem.ChunkStageTiming.endAndRecord(pos.toLong());
    }
}
