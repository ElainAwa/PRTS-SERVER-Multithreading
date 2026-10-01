/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.neoforge.prts.kernel;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
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
 * method returns. The driver advances the observable kernel and, when the commit switch is enabled,
 * applies deferred writes on the server thread. It occupies no mixin tick-loop anchor.</p>
 *
 * <p>The kernel category owns the listener and the write/wait taps. They are installed only while the
 * category is enabled, and a configuration reload can add or remove them without restarting the
 * process.</p>
 *
 * <p>PRTS category: kernel, NeoForge platform module.</p>
 */
public final class PrtsKernelEvents {

    private static final PrtsKernelEvents INSTANCE = new PrtsKernelEvents();
    private static boolean reloadHookInstalled;
    private static boolean subscribed;

    private PrtsKernelEvents() {
    }

    /**
     * Installs the configuration reload hook and synchronizes the platform subscription once.
     *
     * <p>The hook remains registered while the category is off, but the tick listener and write/wait
     * taps are absent until the category is enabled. A later reload can therefore turn the layer on
     * or off without requiring a process restart.</p>
     */
    public static synchronized void register() {
        if (!reloadHookInstalled) {
            PrtsConfigManager.addReloadListener(PrtsKernelEvents::syncSubscription);
            reloadHookInstalled = true;
        }
        syncSubscription();
    }

    private static synchronized void syncSubscription() {
        boolean wanted = KernelSettings.enabled();
        if (wanted == subscribed) {
            return;
        }
        KernelModule module = KernelModule.instance();
        if (wanted) {
            module.installWritePathTap();
            module.installWaitSiteTap();
            NeoForge.EVENT_BUS.register(INSTANCE);
            subscribed = true;
            return;
        }
        module.removeWritePathTap();
        module.removeWaitSiteTap();
        NeoForge.EVENT_BUS.unregister(INSTANCE);
        subscribed = false;
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
