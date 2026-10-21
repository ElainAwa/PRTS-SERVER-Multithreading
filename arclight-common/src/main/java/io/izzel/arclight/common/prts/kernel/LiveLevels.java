/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** The server levels the kernel can resolve by their dimension location. It is an internal view of
 * the levels the server actually holds; a level that is not in this view is not a claim target. A
 * refresh swaps one snapshot, so a reader never sees the level table before its ambiguity set. */
final class LiveLevels {

    private record Snapshot(Map<String, Object> levels, Set<String> ambiguous) {
    }

    private static volatile Snapshot snapshot = new Snapshot(Map.of(), Set.of());

    private LiveLevels() {
    }

    /** Reads every level the server holds and replaces the view. A world id held by more than one
     * level is removed from the view and named ambiguous instead of choosing one silently. */
    static synchronized void refresh(MinecraftServer server) {
        if (server == null) {
            snapshot = new Snapshot(Map.of(), Set.of());
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
        snapshot = new Snapshot(Map.copyOf(next), Set.copyOf(clashes));
    }

    /** The level one dimension location names, or null while it is absent or ambiguous. */
    static Object level(String worldId) {
        if (worldId == null) {
            return null;
        }
        Snapshot held = snapshot;
        return held.ambiguous().contains(worldId) ? null : held.levels().get(worldId);
    }

    /** The world id one level names, or an empty string while it is unknown or ambiguous. */
    static String worldOf(Object level) {
        if (level == null) {
            return "";
        }
        Snapshot held = snapshot;
        for (Map.Entry<String, Object> entry : held.levels().entrySet()) {
            if (entry.getValue() == level && !held.ambiguous().contains(entry.getKey())) {
                return entry.getKey();
            }
        }
        return "";
    }

    /** One snapshot read of the two sides, for the regression test of the atomic swap. */
    static boolean consistentForTest(String worldId) {
        Snapshot held = snapshot;
        return !held.ambiguous().contains(worldId) || !held.levels().containsKey(worldId);
    }

    static boolean ambiguous(String worldId) {
        return worldId != null && snapshot.ambiguous().contains(worldId);
    }

    static synchronized void installForTest(Map<String, Object> held) {
        installForTest(held, Set.of());
    }

    static synchronized void installForTest(Map<String, Object> held, Set<String> clashes) {
        Set<String> ambiguous = clashes == null ? Set.of() : Set.copyOf(clashes);
        Map<String, Object> levels = held == null ? Map.of() : new LinkedHashMap<>(held);
        for (String clash : ambiguous) {
            levels.remove(clash);
        }
        snapshot = new Snapshot(Map.copyOf(levels), ambiguous);
    }

    static synchronized void clear() {
        snapshot = new Snapshot(Map.of(), Set.of());
    }
}
