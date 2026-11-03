/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.neoforge.mixin.prts.event;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.izzel.arclight.neoforge.prts.kernel.PrtsEventOrderBridge;
import net.neoforged.bus.EventBus;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reads the dispatch of the platform bus without changing it: the post frame and each real
 * listener invocation are reported to the bridge, while the listener list, priorities, order,
 * filtering, cancellation, results, exceptions and synchronous semantics stay the target's own. */
@Mixin(EventBus.class)
public abstract class PrtsEventBusMixin {

    private static final String POST =
        "post(Lnet/neoforged/bus/api/Event;[Lnet/neoforged/bus/api/EventListener;)Lnet/neoforged/bus/api/Event;";

    @Inject(method = POST, at = @At("HEAD"), require = 1)
    private void prts$openDispatch(Event event, EventListener[] listeners, CallbackInfo ci) {
        PrtsEventOrderBridge.beginDispatch(this, event, listeners);
    }

    @WrapOperation(method = POST, require = 1,
        at = @At(value = "INVOKE",
            target = "Lnet/neoforged/bus/api/EventListener;invoke(Lnet/neoforged/bus/api/Event;)V"))
    private void prts$noteInvocation(EventListener listener, Event event, Operation<Void> original) {
        PrtsEventOrderBridge.noteInvocation(listener);
        original.call(listener, event);
    }

    @Inject(method = POST, at = @At("RETURN"), require = 1)
    private void prts$closeDispatch(Event event, EventListener[] listeners, CallbackInfo ci) {
        PrtsEventOrderBridge.endDispatch(false);
    }

    @Inject(method = POST, at = @At("THROW"), require = 1)
    private void prts$closeFailedDispatch(Event event, EventListener[] listeners, CallbackInfo ci) {
        PrtsEventOrderBridge.endDispatch(true);
    }
}
