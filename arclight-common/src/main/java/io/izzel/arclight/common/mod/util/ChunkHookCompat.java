package io.izzel.arclight.common.mod.util;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/** Decides whether Bukkit chunk events apply to a level's chunks. */
public final class ChunkHookCompat {

    private static final Set<String> LOGGED = new LinkedHashSet<>();

    private static Consumer<String> logSink;

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

    /** Runs the chunk-event action only when a Bukkit world exists; a skip is logged once per path. */
    public static boolean runChunkEventHook(Object world, String path, Runnable action) {
        if (shouldSkip(world)) {
            markAndLog(path);
            return false;
        }
        action.run();
        return true;
    }

    /** Runs an entity-event action only when a Bukkit world exists; a skip is logged once per path. */
    public static boolean runEntityEventHook(Object world, String path, Runnable action) {
        if (shouldSkip(world)) {
            markAndLog(path);
            return false;
        }
        action.run();
        return true;
    }

    /** Logs the skip once per chunk path so a missing-world level shows why its events are absent. */
    public static void markAndLog(String path) {
        if (!LOGGED.add(path)) {
            return;
        }
        if (logSink != null) {
            logSink.accept(path);
            return;
        }
        try {
            String kind = path.startsWith("entity") ? "entity event" : "chunk event";
            io.izzel.arclight.common.mod.server.ArclightServer.LOGGER.info(
                "Skipping Bukkit {} {} for a level without a CraftWorld", kind, path);
        } catch (Throwable ignored) {
            // offline unit runs have no initialized server logger
        }
    }

    /** Test-only sink so the once-per-path logging can be observed. */
    static void setLogSinkForTests(Consumer<String> sink) {
        logSink = sink;
    }

    /** Clears the once-per-path state and the test sink. */
    public static void resetForTests() {
        LOGGED.clear();
        logSink = null;
    }
}
