/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.neoforge.prts.kernel;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.wiring.KernelWiring;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the kernel scaffolding from the platform tick event; listener and write/wait taps exist only
 * while the category is enabled, and a reload can add or remove them without restarting the process.
 */
public final class PrtsKernelEvents {

    private static final PrtsKernelEvents INSTANCE = new PrtsKernelEvents();
    private static boolean reloadHookInstalled;
    private static boolean subscribed;

    private PrtsKernelEvents() {
    }

    /** Binds the kernel domains, installs the configuration reload hook and synchronizes the
     * platform subscription once. */
    public static synchronized void register() {
        KernelWiring.install();
        PrtsEntityOwnershipEvents.registerIfDeclared();
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

    /** Advances the kernel by one tick. */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        List<String> worlds = new ArrayList<>();
        for (ServerLevel level : event.getServer().getAllLevels()) {
            worlds.add(level.dimension().location().toString());
        }
        KernelModule.instance().serverTick(worlds);
    }
}
