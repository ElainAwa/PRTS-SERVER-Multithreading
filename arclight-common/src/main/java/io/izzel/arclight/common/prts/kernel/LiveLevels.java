/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** The server levels the kernel can resolve by their dimension location. It is an internal view of
 * the levels the server actually holds; a level that is not in this view is not a claim target. */
public final class LiveLevels {

    private static volatile Map<String, Object> levels = Map.of();
    private static volatile Set<String> ambiguous = Set.of();

    private LiveLevels() {
    }

    /** Reads every level the server holds and replaces the view. A world id held by more than one
     * level is removed from the view and named ambiguous instead of choosing one silently. */
    public static synchronized void refresh(MinecraftServer server) {
        if (server == null) {
            levels = Map.of();
            ambiguous = Set.of();
            return;
        }
        Map<String, Object> next = new LinkedHashMap<>();
        Set<String> clashes = new LinkedHashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            if (level == null) {
                continue;
            }
            String worldId = level.dimension().location().toString();
            if (next.putIfAbsent(worldId, level) != null) {
                clashes.add(worldId);
            }
        }
        for (String clash : clashes) {
            next.remove(clash);
        }
        levels = Map.copyOf(next);
        ambiguous = Set.copyOf(clashes);
    }

    /** The level one dimension location names, or null while it is absent or ambiguous. */
    public static Object level(String worldId) {
        if (worldId == null || ambiguous.contains(worldId)) {
            return null;
        }
        return levels.get(worldId);
    }

    public static boolean ambiguous(String worldId) {
        return worldId != null && ambiguous.contains(worldId);
    }

    static synchronized void installForTest(Map<String, Object> held) {
        levels = held == null ? Map.of() : Map.copyOf(held);
        ambiguous = Set.of();
    }

    static synchronized void installForTest(Map<String, Object> held, Set<String> clashes) {
        levels = held == null ? Map.of() : Map.copyOf(held);
        ambiguous = clashes == null ? Set.of() : Set.copyOf(clashes);
    }

    static synchronized void clear() {
        levels = Map.of();
        ambiguous = Set.of();
    }
}
