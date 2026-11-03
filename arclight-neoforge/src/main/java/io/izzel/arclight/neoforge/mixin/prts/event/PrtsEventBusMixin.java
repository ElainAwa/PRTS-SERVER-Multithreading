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

/** Reads the dispatch of the platform bus without changing it: the dispatch frame is opened and
 * closed around the two real calls into the listener loop, and each real listener invocation is
 * reported to the bridge. The listener list, priorities, order, filtering, cancellation, results,
 * exceptions and synchronous semantics stay the target's own; a failed dispatch rethrows the
 * original throwable unchanged and only marks the frame failed. */
@Mixin(value = EventBus.class, remap = false)
public abstract class PrtsEventBusMixin {

    private static final String DISPATCH =
        "post(Lnet/neoforged/bus/api/Event;[Lnet/neoforged/bus/api/EventListener;)Lnet/neoforged/bus/api/Event;";

    private static final String POST =
        "post(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;";

    private static final String POST_PHASE =
        "post(Lnet/neoforged/bus/api/EventPriority;Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;";

    @WrapOperation(method = {POST, POST_PHASE}, remap = false, require = 1,
        at = @At(value = "INVOKE", remap = false,
            target = "Lnet/neoforged/bus/EventBus;post(Lnet/neoforged/bus/api/Event;[Lnet/neoforged/bus/api/EventListener;)Lnet/neoforged/bus/api/Event;"))
    private Event prts$observeDispatch(EventBus bus, Event event, EventListener[] listeners,
                                        Operation<Event> original) {
        PrtsEventOrderBridge.beginDispatch(bus, event, listeners);
        boolean completed = false;
        try {
            Event result = original.call(bus, event, listeners);
            completed = true;
            return result;
        } finally {
            PrtsEventOrderBridge.endDispatch(!completed);
        }
    }

    @WrapOperation(method = DISPATCH, remap = false, require = 1,
        at = @At(value = "INVOKE", remap = false,
            target = "Lnet/neoforged/bus/api/EventListener;invoke(Lnet/neoforged/bus/api/Event;)V"))
    private void prts$noteInvocation(EventListener listener, Event event, Operation<Void> original) {
        PrtsEventOrderBridge.noteInvocation(listener);
        original.call(listener, event);
    }
}
