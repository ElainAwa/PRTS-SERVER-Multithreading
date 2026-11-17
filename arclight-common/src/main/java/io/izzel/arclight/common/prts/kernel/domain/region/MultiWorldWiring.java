/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.region;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;

import java.util.LinkedHashMap;
import java.util.Map;

/** The multi-world migration guard: it mirrors the host level lifecycle into a named witness (never
 * the author of the epoch), refuses a dimension change only when the target world is registered and
 * no longer ACTIVE, and admits an unknown target unchanged. With the kernel off every answer is
 * true and no state is built. */
public final class MultiWorldWiring {

    /** The witnessed lifecycle state of one world. */
    public enum State {
        ACTIVE,
        UNLOADING,
        GONE
    }

    private record World(long incarnation, State state) {
    }

    private static final Map<String, World> WORLDS = new LinkedHashMap<>();
    private static final MultiWorldIsolation WITNESS = new MultiWorldIsolation();

    private MultiWorldWiring() {
    }

    /** Called after the host inserted a level; the epoch stays the kernel's own authority. */
    public static synchronized void onLevelLoaded(String worldId) {
        if (!KernelSettings.enabled() || worldId == null) {
            return;
        }
        long incarnation = incarnation(worldId);
        WORLDS.put(worldId, new World(incarnation, State.ACTIVE));
        WITNESS.addWorld(worldId, incarnation);
    }

    /** Called when the host removes a level; the world stops accepting migrations. */
    public static synchronized void onLevelUnloaded(String worldId) {
        if (!KernelSettings.enabled() || worldId == null) {
            return;
        }
        World world = WORLDS.get(worldId);
        if (world != null) {
            WORLDS.put(worldId, new World(world.incarnation(), State.UNLOADING));
            WITNESS.stopNewWork(worldId, world.incarnation());
        }
    }

    /** Called at server stopping: every witnessed world stops accepting migrations. */
    public static synchronized void onServerStopping() {
        if (!KernelSettings.enabled()) {
            return;
        }
        for (Map.Entry<String, World> entry : WORLDS.entrySet()) {
            if (entry.getValue().state() == State.ACTIVE) {
                WORLDS.put(entry.getKey(), new World(entry.getValue().incarnation(),
                    State.UNLOADING));
                WITNESS.stopNewWork(entry.getKey(), entry.getValue().incarnation());
            }
        }
    }

    /** Whether one dimension change may proceed; an unknown target world is admitted unchanged. */
    public static synchronized boolean accept(String sourceWorld, long sourceEpoch,
                                              String targetWorld) {
        if (!KernelSettings.enabled()) {
            return true;
        }
        World target = WORLDS.get(targetWorld);
        if (target == null) {
            return true;
        }
        return target.state() == State.ACTIVE;
    }

    public static synchronized State state(String worldId) {
        World world = WORLDS.get(worldId);
        return world == null ? null : world.state();
    }

    /** Drops the witness state; test and evidence code only. */
    public static synchronized void clear() {
        WORLDS.clear();
    }

    private static long incarnation(String worldId) {
        try {
            return Math.max(0L, KernelModule.instance().guard().worldEpochs().epochOf(worldId));
        } catch (Throwable unavailable) {
            return 0L;
        }
    }
}
