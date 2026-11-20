/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.decision;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.EntityAirChangeEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPoseChangeEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityToggleSwimEvent;
import org.bukkit.event.world.GenericGameEvent;

import java.util.List;
import java.util.function.Supplier;

/** Reads whether the Bukkit listener chain of an event is empty at a decision point. */
public final class EventChainAdmission {

    private static final List<Supplier<HandlerList>> ROW_CHAINS = List.of(
        EntityAirChangeEvent::getHandlerList,
        EntityDamageEvent::getHandlerList,
        EntityTargetEvent::getHandlerList,
        EntityPoseChangeEvent::getHandlerList,
        EntityToggleSwimEvent::getHandlerList,
        EntityCombustEvent::getHandlerList,
        EntityPotionEffectEvent::getHandlerList,
        EntityRemoveEvent::getHandlerList,
        GenericGameEvent::getHandlerList);

    private EventChainAdmission() {
    }

    /** Whether one handler list carries no listener; a missing list counts as empty. */
    public static boolean emptyChain(HandlerList handlers) {
        return handlers == null || handlers.getRegisteredListeners().length == 0;
    }

    /** Whether the current chain of one event instance is empty. */
    public static boolean emptyChain(Event event) {
        return event == null || emptyChain(event.getHandlers());
    }

    /** The admission verdict of the current chain of one event instance. */
    public static EventAdmission.Verdict verdictFor(Event event) {
        int listeners = event == null ? 0 : listenerCount(event.getHandlers());
        if (listeners == 0) {
            return new EventAdmission.Verdict(EventAdmission.Mode.OWNER_SYNC, "empty-chain", 0, 0);
        }
        return new EventAdmission.Verdict(EventAdmission.Mode.LEGACY, "non-empty-chain",
            listeners, 0);
    }

    /** Whether every chain one entity update can reach is empty; false keeps the row on the host. */
    public static boolean rowAdmissible() {
        return allChainsEmpty(ROW_CHAINS);
    }

    /** The same decision over a caller-supplied set of chains, kept separate for a direct test. */
    static boolean allChainsEmpty(List<Supplier<HandlerList>> chains) {
        for (Supplier<HandlerList> chain : chains) {
            if (!emptyChain(chain.get())) {
                return false;
            }
        }
        return true;
    }

    private static int listenerCount(HandlerList handlers) {
        return handlers == null ? 0 : handlers.getRegisteredListeners().length;
    }
}
