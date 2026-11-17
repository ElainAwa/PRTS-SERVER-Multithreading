/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.domain.region.MultiWorldWiring;
import io.izzel.arclight.common.prts.kernel.sites.WorldLifecycleOwner;
import io.izzel.arclight.common.prts.kernel.sites.WorldLifecycleWriteTap;

import java.util.List;

/** The narrow bridge from the host's real level insert/remove points to the kernel lifecycle owner.
 * The bridge is inert while the opt-in world-set-readouts switch is off, so a legal server keeps
 * the same behaviour; when on, the owner walks the seven states on the one server thread and counts
 * a refused duplicate creation or unload. No method waits for another thread. */
public final class WorldLifecycleWiring {

    private WorldLifecycleWiring() {
    }

    /** Called after the host inserted one level; the level id is the dimension location. */
    public static void onLevelLoaded(String worldId) {
        MultiWorldWiring.onLevelLoaded(worldId);
        if (!KernelSettings.worldSetReadouts()) {
            return;
        }
        if (!WorldLifecycleWriteTap.INSTANCE.allows(
            new io.izzel.arclight.common.prts.kernel.sites.WriteTapContext(
                worldId, null, 0L, 0L, "world-lifecycle", "world-lifecycle"), null)) {
            return;
        }
        KernelModule module = KernelModule.instance();
        WorldLifecycleOwner owner = module.worldLifecycle();
        owner.bindOwner(Thread.currentThread());
        if (owner.requestCreate(worldId)) {
            // A recreated world leaves the tombstone the close of its previous incarnation left,
            // so the new incarnation may accept intents again.
            module.intents().openWorld(worldId);
            owner.transition(worldId, WorldLifecycleOwner.State.LOADING);
            owner.transition(worldId, WorldLifecycleOwner.State.READY);
        }
    }

    /** The server stopping entry: flush every world without recording a stop session; the
     * owner-gated unload path counts the pending session once it accepts the unload. */
    public static void onServerStopping(List<String> worldIds) {
        MultiWorldWiring.onServerStopping();
        KernelModule module = KernelModule.instance();
        if (worldIds == null) {
            return;
        }
        module.terminateRconDeferrals();
        for (String worldId : worldIds) {
            module.flushWorldAtStop(worldId, false);
        }
    }

    /** Called before/after the host removed one level; the owner refuses an unload it did not hold. */
    public static void onLevelUnloaded(String worldId) {
        MultiWorldWiring.onLevelUnloaded(worldId);
        if (!KernelSettings.worldSetReadouts()) {
            return;
        }
        KernelModule module = KernelModule.instance();
        WorldLifecycleOwner owner = module.worldLifecycle();
        owner.bindOwner(Thread.currentThread());
        if (!owner.requestUnload(worldId)) {
            module.discardPendingUnload(worldId);
            return;
        }
        KernelModule.WorldStopFlush flush = null;
        if (!module.countPendingUnload(worldId)) {
            flush = module.flushWorldAtStop(worldId, true);
        }
        KernelModule.WorldDrain drain = module.worldDrain(worldId);
        if ((flush != null && !flush.complete()) || drain == null || !drain.complete()) {
            // An unfinished drain keeps the world unloading: its epoch, shard and arena slots stay
            // intact, and GONE is refused until a later bounded drain settles them.
            return;
        }
        owner.transition(worldId, WorldLifecycleOwner.State.RECLAIMING);
        owner.transition(worldId, WorldLifecycleOwner.State.GONE, true);
    }
}
