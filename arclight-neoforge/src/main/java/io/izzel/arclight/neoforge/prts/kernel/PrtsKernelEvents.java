/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.neoforge.prts.kernel;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the kernel scaffolding from the platform tick event.
 *
 * <p>{@link ServerTickEvent.Post} is fired at the end of the server tick, immediately before that
 * method returns. The four pieces only observe or meter, so the driver occupies no mixin anchor and
 * no world write path: a kernel that later rewrites the tick loop keeps this layer working, and
 * deleting the layer removes the listener and nothing else.</p>
 *
 * <p>The listeners are subscribed only while the kernel category is enabled, so a server that does
 * not opt in pays nothing at all and never loads the driver. The world identities are read from the
 * level list the event carries; the driver itself never touches a world object.</p>
 *
 * <p>PRTS category: kernel, NeoForge platform module.</p>
 */
public final class PrtsKernelEvents {

    private PrtsKernelEvents() {
    }

    /** Subscribes the driver for this server process; called once, while the category is enabled. */
    public static void register() {
        NeoForge.EVENT_BUS.register(new PrtsKernelEvents());
    }

    /**
     * Advances the kernel by one tick.
     *
     * @param event the platform's end-of-tick event
     */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        List<String> worlds = new ArrayList<>();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            worlds.add(level.dimension().location().toString());
        }
        KernelModule.instance().serverTick(worlds);
    }
}
