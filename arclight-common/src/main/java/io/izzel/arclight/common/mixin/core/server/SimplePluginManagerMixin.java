/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mixin.core.server;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.observe.BukkitEventOrderObserver;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.SimplePluginManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reads the real Bukkit fireEvent loop without changing it: the HandlerList snapshot is the
 * expected order and each real RegisteredListener.callEvent is compared in place. The delegate is
 * called exactly once; no listener list, priority, registration, cancel, result or exception path
 * is altered. The reading class is an observer, not a probe: its name only says what it reads. */
@Mixin(value = SimplePluginManager.class, remap = false)
public abstract class SimplePluginManagerMixin {


    /** Behaviour: publishes the registration generation after one plugin listener registration. Why
     * here: every listener registration passes this boundary. Invariant: fireEvent/callEvent and every
     * registration decision are untouched; only the existing epoch counter advances. */
    @Inject(method = "registerEvents", at = @At("RETURN"), require = 1, remap = false)
    private void prts$publishRegistrationEpoch(CallbackInfo ci) {
        KernelModule.instance().registrationEpoch().publish();
    }

    @Inject(method = "registerEvent", at = @At("RETURN"), require = 1, remap = false)
    private void prts$publishEventRegistrationEpoch(CallbackInfo ci) {
        KernelModule.instance().registrationEpoch().publish();
    }

    @Inject(method = "disablePlugin", at = @At("RETURN"), require = 1, remap = false)
    private void prts$publishDisableEpoch(CallbackInfo ci) {
        KernelModule.instance().registrationEpoch().publish();
    }

    @Inject(method = "fireEvent", at = @At("HEAD"), require = 1, remap = false)
    private void prts$beginFire(Event event, CallbackInfo ci) {
        HandlerList handlers = event.getHandlers();
        RegisteredListener[] expected = handlers == null ? null : handlers.getRegisteredListeners();
        BukkitEventOrderObserver.begin(expected, event instanceof RemoteServerCommandEvent);
    }

    @WrapOperation(method = "fireEvent", require = 1,
        at = @At(value = "INVOKE", remap = false,
            target = "Lorg/bukkit/plugin/RegisteredListener;callEvent(Lorg/bukkit/event/Event;)V"))
    private void prts$noteListener(RegisteredListener listener, Event event,
                                   Operation<Void> original) {
        BukkitEventOrderObserver.invokedCurrent(listener);
        original.call(listener, event);
    }

    @Inject(method = "fireEvent", at = @At("RETURN"), require = 1, remap = false)
    private void prts$endFire(Event event, CallbackInfo ci) {
        BukkitEventOrderObserver.end(false);
    }

    /** Behaviour: an empty listener chain is not dispatched here; a non-empty chain keeps the host
     * roundtrip as a fault fallback. Why here: every plugin event passes through this method.
     * Invariant: an empty chain keeps the event's own values; no chain runs on an owner thread. */
    @org.spongepowered.asm.mixin.injection.Inject(method = "callEvent", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void prts$ownerHostEvent(org.bukkit.event.Event event,
                                     org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (io.izzel.arclight.common.prts.kernel.auth.OwnerUnitPermit.eventShape()) {
            if (io.izzel.arclight.common.prts.kernel.decision.EventChainAdmission.emptyChain(event)) {
                ci.cancel();
                return;
            }
            io.izzel.arclight.common.prts.kernel.domain.entity.owner.OwnerHostEventDispatch
                .dispatch(event);
            ci.cancel();
        }
    }
}