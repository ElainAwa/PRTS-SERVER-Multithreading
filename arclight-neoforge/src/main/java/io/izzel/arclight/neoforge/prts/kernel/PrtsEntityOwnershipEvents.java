/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The platform bindings of the ownership fixture: the plan point before the worlds tick, the host
 * entry of one entity tick, the close after the worlds ticked, and the stop that ends the pool.
 * The listener exists only when the process declared the fixture, and the cancel flag of the host
 * entry is the whole takeover: the platform skips the original tick of that row.
 */
package io.izzel.arclight.neoforge.prts.kernel;

import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityTickOwnership;
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

    /** The plan point: freeze the eligible rows, issue their tokens and dispatch them. */
    @SubscribeEvent
    public void onServerTickPre(ServerTickEvent.Pre event) {
        EntityTickOwnership.onServerTickPre(event.getServer());
    }

    /** The host entry of one entity tick: cancelling the event skips the original tick of the row,
     * and the decision of the row is counted right here, not derived from a later commit. */
    @SubscribeEvent
    public void onEntityTickPre(EntityTickEvent.Pre event) {
        if (EntityTickOwnership.onEntityTickPre(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** The close of the tick: recycle every lease that never reached its host entry. */
    @SubscribeEvent
    public void onServerTickPost(ServerTickEvent.Post event) {
        EntityTickOwnership.onServerTickPost();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        EntityTickOwnership.shutdown();
    }
}
