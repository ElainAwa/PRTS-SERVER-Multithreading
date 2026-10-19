package io.izzel.arclight.common.mod.util;

/** Decides whether Bukkit chunk events apply to a level's chunks. */
public final class ChunkHookCompat {

    private ChunkHookCompat() {
    }

    /** True when the level has no Bukkit world object, so chunk events and population must be skipped. */
    public static boolean shouldSkip(Object world) {
        return world == null;
    }

    /** Runs the action only when a Bukkit world object exists; returns whether it ran. */
    public static boolean runIfWorld(Object world, Runnable action) {
        if (shouldSkip(world)) {
            return false;
        }
        action.run();
        return true;
    }
}
