/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.eventbridge;

import io.izzel.arclight.common.optimization.eventbridge.EventAttributionStats;
import org.bukkit.event.Event;
import org.bukkit.plugin.RegisteredListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GAP-4／GAP-6 每监听器归属埋点（M4 插桩 · 独立分支 feat/gap-telemetry · <b>默认关</b>）。
 *
 * <p>为什么落在 {@code RegisteredListener#callEvent} 而不是 {@code HandlerList}：本版 spigot-api 的
 * {@code HandlerList} 只有 register/unregister/getRegisteredListeners（已 {@code javap} 核实，<b>没有</b>
 * {@code broadcast}），分发循环由服务端实现逐个调用 {@code RegisteredListener.callEvent} ⇒
 * 此处是覆盖率 100% 的单一每监听器钩子（{@code HandlerListMixin_EventBridge} 的注入点是注册变更，不在此处）。</p>
 *
 * <p>归属名 = {@code getPlugin().getName()}（模组经 Arclight 桥接注册的监听器即其插件名）。前置 set／后置 clear，
 * 只读计数；不取消、不改优先级、不改执行顺序。异常逃逸由 {@code EventAttributionStats} 的过期帧清理兜底。</p>
 */
@Mixin(value = RegisteredListener.class, remap = false)
public abstract class RegisteredListenerMixin_EventAttribution {

    @Inject(method = "callEvent", at = @At("HEAD"))
    private void prts$attributionEnter(Event event, CallbackInfo ci) {
        EventAttributionStats.listenerEnter(event, ((RegisteredListener) (Object) this).getPlugin());
    }

    @Inject(method = "callEvent", at = @At("RETURN"))
    private void prts$attributionExit(Event event, CallbackInfo ci) {
        EventAttributionStats.listenerExit();
    }
}
