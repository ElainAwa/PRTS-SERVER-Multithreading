/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.ownership;

import io.izzel.arclight.common.optimization.ownership.HostApiAttribution;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Predicate;

/**
 * GAP-5 宿主 API 入口归因（M4 插桩 · 独立分支 feat/gap-telemetry · <b>默认关</b>）。
 *
 * <p>入口清单（FIND-g07 冻结的最小完备集，≥1 入口/类，含 {@code Level.getBlockState}／
 * {@code getBlockEntity}／{@code getEntities} 三个靶点）：方法名与描述符取自 NeoForge 编译产物
 * {@code javap}，与既有的 {@code LevelMixin_OwnershipGuard} 同一组签名。</p>
 *
 * <p>调用方 {@code HostApiAttribution.onEnter} 内部先判开关与 {@code sample-every} 采样，
 * 未命中采样的调用只付一次 volatile 读 + 每线程计数器自增（无 StackWalker）。</p>
 */
@Mixin(Level.class)
public abstract class LevelMixin_HostApiAttribution {

    @Inject(method = "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"))
    private void prts$attrGetBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        HostApiAttribution.onEnter(HostApiAttribution.API_GET_BLOCK_STATE);
    }

    @Inject(method = "getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;",
            at = @At("HEAD"))
    private void prts$attrGetBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        HostApiAttribution.onEnter(HostApiAttribution.API_GET_BLOCK_ENTITY);
    }

    @Inject(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
            at = @At("HEAD"))
    private void prts$attrGetEntities(Entity except, AABB aabb, Predicate<? super Entity> predicate,
                                      CallbackInfoReturnable<List<Entity>> cir) {
        HostApiAttribution.onEnter(HostApiAttribution.API_GET_ENTITIES);
    }

    @SuppressWarnings("rawtypes")
    @Inject(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
            at = @At("HEAD"))
    private void prts$attrGetEntitiesTyped(EntityTypeTest<?, ?> typeTest, AABB aabb, Predicate predicate,
                                           CallbackInfoReturnable<List> cir) {
        HostApiAttribution.onEnter(HostApiAttribution.API_GET_ENTITIES);
    }
}
