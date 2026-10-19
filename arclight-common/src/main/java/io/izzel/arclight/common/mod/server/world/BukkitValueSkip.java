package io.izzel.arclight.common.mod.server.world;

/** Startup/shutdown decisions for Bukkit persistent data on levels without a CraftWorld. */
public final class BukkitValueSkip {

    private static final java.util.Set<String> LOGGED = new java.util.LinkedHashSet<>();

    private static java.util.function.Consumer<String> logSink;

    private BukkitValueSkip() {
    }

    /** True when the level has no Bukkit world object and its Bukkit-only data must be skipped. */
    public static boolean shouldSkip(Object world) {
        return world == null;
    }

    /** Records the first skip for a path, so each save/load path logs at most once. */
    public static boolean markAndLog(String path) {
        if (!LOGGED.add(path)) {
            return false;
        }
        if (logSink != null) {
            logSink.accept(path);
            return true;
        }
        try {
            io.izzel.arclight.common.mod.server.ArclightServer.LOGGER.info(
                "Skipping Bukkit persistent data {} for a level without a CraftWorld", path);
        } catch (Throwable ignored) {
            // offline unit runs have no initialized server logger
        }
        return true;
    }

    static void setLogSinkForTests(java.util.function.Consumer<String> sink) {
        logSink = sink;
    }

    public static void resetForTests() {
        LOGGED.clear();
        logSink = null;
    }
}
