/* SPDX-License-Identifier: GPL-3.0-or-later */
/* The platform bindings of the ownership fixture; the verdict of its host entry decides the cancel
 * flag here, so the row counted as skipped is the row that is cancelled. */
package io.izzel.arclight.neoforge.prts.kernel;

import io.izzel.arclight.common.prts.kernel.dispatch.FaultInjection;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityTickOwnership;
import io.izzel.arclight.common.prts.support.PrtsHostTickCalls;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Binds the ownership fixture to the platform tick events; nothing is bound while it is off. */
public final class PrtsEntityOwnershipEvents {

    private static final PrtsEntityOwnershipEvents INSTANCE = new PrtsEntityOwnershipEvents();
    private static boolean subscribed;

    private PrtsEntityOwnershipEvents() {
    }

    /** Subscribes once, and only when this process declared the fixture. */
    public static synchronized void registerIfDeclared() {
        if (subscribed || !EntityTickOwnership.live()) {
            return;
        }
        NeoForge.EVENT_BUS.register(INSTANCE);
        subscribed = true;
    }

    @SubscribeEvent
    public void onServerTickPre(ServerTickEvent.Pre event) {
        EntityTickOwnership.onServerTickPre(event.getServer());
    }

    /** Cancelling the event skips the original tick of the row; the verdict of the entry, counted
     * right there, decides the flag. */
    @SubscribeEvent
    public void onEntityTickPre(EntityTickEvent.Pre event) {
        int verdict = EntityTickOwnership.onEntityTickPre(event.getEntity());
        if (verdict == EntityTickOwnership.SKIP_HOST_TICK && !FaultInjection.ownershipSkipsIgnored()) {
            event.setCanceled(true);
        }
    }

    /** The host path ran the tick of this row; the independent counter records it. */
    @SubscribeEvent
    public void onEntityTickPost(EntityTickEvent.Post event) {
        PrtsHostTickCalls.notePath(event.getEntity());
    }

    @SubscribeEvent
    public void onServerTickPost(ServerTickEvent.Post event) {
        EntityTickOwnership.onServerTickPost();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        EntityTickOwnership.shutdown();
    }
}
