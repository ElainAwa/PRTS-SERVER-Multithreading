package io.izzel.arclight.common.mod.util;

import io.izzel.arclight.common.mod.server.ArclightServer;

/**
 * Runs Bukkit-only startup hooks only for a world that has a CraftWorld. A mod-provided dimension
 * has no Bukkit world object, so its early startup hooks are skipped and the skip is logged once
 * per call site instead of failing the whole server start.
 */
public final class WorldHookCompat {

    private static final java.util.Set<String> LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private WorldHookCompat() {
    }

    /**
     * Runs the action when a CraftWorld exists; otherwise skips it and logs the skip once for the
     * calling method. Exceptions from the action are never swallowed.
     */
    public static void enterBukkitHook(boolean hasCraftWorld, Runnable action) {
        if (hasCraftWorld) {
            action.run();
            return;
        }
        String key = callerKey();
        if (LOGGED.add(key)) {
            ArclightServer.LOGGER.info("Skipping Bukkit-only startup hook for a dimension without a CraftWorld ({})", key);
        }
    }

    private static String callerKey() {
        StackTraceElement[] trace = new Throwable().getStackTrace();
        return trace.length > 2 ? trace[2].getClassName() + "#" + trace[2].getMethodName() : "unknown";
    }

    /** Clears the once-per-site dedupe state; tests only. */
    public static void resetForTests() {
        LOGGED.clear();
    }
}
