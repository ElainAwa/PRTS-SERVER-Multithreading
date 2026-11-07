/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.LongAdder;

/** Reads the optional native payload through its real Java surface without compiling against it:
 * the configured sub-step cap, the resident-buffer cap constant, and the callbacks that really
 * re-enter Java. The counter is closed over the covered callback bases only; the rest is a named gap. */
public final class PrtsNativeReadings {

    public record Snapshot(int substepsCap, long callbackCount) {
    }

    private static final LongAdder CALLBACKS = new LongAdder();
    private static volatile Surface surface;

    private PrtsNativeReadings() {
    }

    /** Counts one covered native-to-Java callback entry. */
    public static void noteCallback() {
        CALLBACKS.increment();
    }

    /** Clears the callback counter at the same gate as the other readout counters. */
    public static void reset() {
        CALLBACKS.reset();
    }

    /** @return the real caps and the covered callback count, or null when the payload is absent */
    public static Snapshot snapshot(Level level) {
        Surface resolved = surface();
        if (level == null || resolved == null) {
            return null;
        }
        try {
            Object system = resolved.get.invoke(null, level);
            if (system == null) {
                return null;
            }
            Object config = resolved.config.invoke(system);
            int substeps = resolved.substeps.getInt(config);
            return new Snapshot(substeps, CALLBACKS.sum());
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Surface surface() {
        Surface current = surface;
        if (current != null) {
            return current;
        }
        synchronized (PrtsNativeReadings.class) {
            if (surface != null) {
                return surface;
            }
            try {
                Class<?> system = Class.forName(
                    "dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem");
                Class<?> config = Class.forName("dev.ryanhcode.sable.physics.config.PhysicsConfigData");
                surface = new Surface(
                    system.getMethod("get", Level.class),
                    system.getMethod("getConfig"),
                    config.getField("substepsPerTick"));
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                surface = null;
            }
        }
        return surface;
    }

    private record Surface(Method get, Method config, Field substeps) {
    }
}
