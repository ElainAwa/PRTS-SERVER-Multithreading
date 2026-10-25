/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import java.util.LinkedHashSet;
import java.util.Set;

/** Records which dimension ids are loaded. A refusal leaves the set unchanged and does not throw. */
public final class PrtsLevelLifecycle {

    /** The result of one load or unload report. */
    public enum Mark {
        LOADED,
        RELOADED,
        UNLOADED,
        REFUSED
    }

    /** Why the last report was refused, or none when the last report changed the set. */
    public enum Refusal {
        NONE,
        MISSING_ID,
        NOT_LOADED
    }

    private static final Set<String> LOADED = new LinkedHashSet<>();

    private static Refusal lastRefusal = Refusal.NONE;

    private PrtsLevelLifecycle() {
    }

    /** Records a load. A blank id is refused. An id that is already loaded is a reload. */
    public static Mark loaded(String worldId) {
        if (worldId == null || worldId.isEmpty()) {
            lastRefusal = Refusal.MISSING_ID;
            return Mark.REFUSED;
        }
        if (!LOADED.add(worldId)) {
            lastRefusal = Refusal.NONE;
            return Mark.RELOADED;
        }
        lastRefusal = Refusal.NONE;
        return Mark.LOADED;
    }

    /** Records an unload. A blank id, or an id that is not loaded, is refused. */
    public static Mark unloaded(String worldId) {
        if (worldId == null || worldId.isEmpty()) {
            lastRefusal = Refusal.MISSING_ID;
            return Mark.REFUSED;
        }
        if (!LOADED.remove(worldId)) {
            lastRefusal = Refusal.NOT_LOADED;
            return Mark.REFUSED;
        }
        lastRefusal = Refusal.NONE;
        return Mark.UNLOADED;
    }

    static boolean isLoaded(String worldId) {
        return LOADED.contains(worldId);
    }

    static Refusal lastRefusal() {
        return lastRefusal;
    }

    static void resetForTests() {
        LOADED.clear();
        lastRefusal = Refusal.NONE;
    }
}
