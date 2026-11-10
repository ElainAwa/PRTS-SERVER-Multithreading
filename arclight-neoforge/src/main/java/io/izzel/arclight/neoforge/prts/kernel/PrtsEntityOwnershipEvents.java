/* SPDX-License-Identifier: GPL-3.0-or-later */
/* The platform bindings of the ownership fixture; the verdict of its host entry decides the cancel
 * flag here, so the row counted as skipped is the row that is cancelled. */

package io.izzel.arclight.neoforge.prts.kernel;

import java.util.concurrent.atomic.LongAdder;

import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.dispatch.FaultInjection;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityTickOwnership;
import io.izzel.arclight.common.prts.support.EntityTickBindingPolicy;
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
    private static final LongAdder SERVER_PRE = new LongAdder();
    private static final LongAdder ENTITY_PRE = new LongAdder();
    private static final LongAdder ENTITY_POST = new LongAdder();

    private PrtsEntityOwnershipEvents() {
    }

    /** Binds the real entity-tick entry while the kernel is enabled; the ownership action itself
     * remains behind the explicit fixture property, so this changes no P1 behavior by default. */
    public static synchronized void registerIfDeclared() {
        syncSubscription(KernelSettings.enabled());
        bindingDiagnostic();
    }

    /** Test-only binding diagnostics, gated by a JVM property; no formal output key. */
    public static synchronized void bindingDiagnostic() {
        if (!Boolean.getBoolean("arclight.prts.bindingDiag")) {
            return;
        }
        boolean kernel = KernelSettings.enabled();
        System.out.println("[PRTS] binding diag live=" + EntityTickOwnership.live()
            + " armed=" + EntityTickOwnership.armed()
            + " kernel=" + kernel
            + " shouldBind=" + EntityTickBindingPolicy.shouldBind(kernel)
            + " subscribed=" + subscribed
            + " entityOwnership=" + System.getProperty("arclight.prts.entityOwnership")
            + " entityCensus=" + System.getProperty("arclight.prts.entityCensus"));
    }

    public static synchronized boolean subscribedForDiagnostics() {
        return subscribed;
    }

    /** Follows the kernel subscription on/off. It is the real host entry; an unarmed fixture still
     * returns immediately, so no row is claimed unless the ownership fixture was explicitly armed. */
    public static synchronized void syncSubscription(boolean kernelEnabled) {
        boolean wanted = EntityTickBindingPolicy.shouldBind(kernelEnabled);
        if (wanted == subscribed) {
            return;
        }
        if (wanted) {
            NeoForge.EVENT_BUS.register(INSTANCE);
            subscribed = true;
        } else {
            NeoForge.EVENT_BUS.unregister(INSTANCE);
            subscribed = false;
        }
        bindingDiagnostic();
    }

    @SubscribeEvent
    public void onServerTickPre(ServerTickEvent.Pre event) {
        SERVER_PRE.increment();
        if (Boolean.getBoolean("arclight.prts.perTickDiag") && SERVER_PRE.sum() % 20L == 0L) {
            System.out.println("[PRTS] per-tick server_pre=" + SERVER_PRE.sum()
                + " entity_pre=" + ENTITY_PRE.sum() + " entity_post=" + ENTITY_POST.sum()
                + " ownershipRecords="
                + io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityOwnershipCensus.recordCalls()
                + " " + EntityTickOwnership.planDiagnostic());
        }
        EntityTickOwnership.onServerTickPre(event.getServer());
    }

    /** Cancelling the event skips the original tick of the row; the verdict of the entry, counted
     * right there, decides the flag. */
    @SubscribeEvent
    public void onEntityTickPre(EntityTickEvent.Pre event) {
        ENTITY_PRE.increment();
        int verdict = EntityTickOwnership.onEntityTickPre(event.getEntity());
        if (verdict == EntityTickOwnership.SKIP_HOST_TICK && !FaultInjection.ownershipSkipsIgnored()) {
            event.setCanceled(true);
        }
    }

    /** The host path ran the tick of this row; the independent counter records it. */
    @SubscribeEvent
    public void onEntityTickPost(EntityTickEvent.Post event) {
        ENTITY_POST.increment();
        PrtsHostTickCalls.notePath(event.getEntity());
    }

    @SubscribeEvent
    public void onServerTickPost(ServerTickEvent.Post event) {
        EntityTickOwnership.onServerTickPost();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (Boolean.getBoolean("arclight.prts.perTickDiag")) {
            System.out.println("[PRTS] per-tick final server_pre=" + SERVER_PRE.sum()
                + " entity_pre=" + ENTITY_PRE.sum() + " entity_post=" + ENTITY_POST.sum()
                + " ownershipRecords="
                + io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityOwnershipCensus.recordCalls()
                + " " + EntityTickOwnership.planDiagnostic());
        }
        EntityTickOwnership.shutdown();
    }
}
