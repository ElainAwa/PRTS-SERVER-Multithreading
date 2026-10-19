package io.izzel.arclight.common.mod.util;

import io.izzel.arclight.common.mod.server.ArclightServer;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Runs Bukkit-only startup hooks only for a world that has a CraftWorld. A mod-provided dimension
 * has no Bukkit world object, so its startup hooks are skipped and the skip is logged once per
 * call site instead of failing the whole server start. The dedupe set is a plain startup-only
 * collection: these hooks run on the server thread before any world tick starts.
 */
public final class WorldHookCompat {

    private static final Set<String> LOGGED = new LinkedHashSet<>();

    private static Consumer<String> logSink;

    private WorldHookCompat() {
    }

    /**
     * Runs the action when a CraftWorld exists; otherwise skips it and logs the skip once for the
     * given call-site name. Exceptions from the action are never swallowed.
     */
    public static void enterBukkitHook(String site, boolean hasCraftWorld, Runnable action) {
        if (hasCraftWorld) {
            action.run();
            return;
        }
        if (LOGGED.add(site)) {
            logSkip(site);
        }
    }

    private static void logSkip(String site) {
        if (logSink != null) {
            logSink.accept(site);
            return;
        }
        try {
            ArclightServer.LOGGER.info(
                "Skipping Bukkit-only startup hook for a dimension without a CraftWorld ({})", site);
        } catch (Throwable ignored) {
            // Offline unit runs have no initialized server logger; the decision above is unchanged.
        }
    }

    /** Test-only log sink so a unit test can observe one message per call site. */
    static void setLogSinkForTests(Consumer<String> sink) {
        logSink = sink;
    }

    /** Clears the once-per-site state and the test sink. */
    public static void resetForTests() {
        LOGGED.clear();
        logSink = null;
    }
}
