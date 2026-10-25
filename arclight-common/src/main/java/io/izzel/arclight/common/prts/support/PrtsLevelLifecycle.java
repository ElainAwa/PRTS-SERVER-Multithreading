/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import java.util.LinkedHashSet;
import java.util.Set;

/** Records dimension ids. Only the server thread that inserts and removes levels may retain a snapshot. */
public final class PrtsLevelLifecycle {

    private static final PrtsLevelLifecycle SERVER = new PrtsLevelLifecycle();

    private static final Snapshot EMPTY = new Snapshot(Mark.UNLOADED, Refusal.NONE, Set.of());

    private volatile Snapshot published = EMPTY;

    /** Returns the record owned by the server thread that inserts and removes levels. */
    public static PrtsLevelLifecycle server() {
        return SERVER;
    }

    /** Describes one retained load or unload. */
    public enum Mark {
        LOADED,
        RELOADED,
        UNLOADED,
        REFUSED
    }

    /** Why a retained report was refused, or none when the report changed the set. */
    public enum Refusal {
        NONE,
        MISSING_ID,
        NOT_LOADED
    }

    /** One published view. The id set does not change after publication. */
    public static final class Snapshot {

        public final Mark mark;
        public final Refusal refusal;
        public final Set<String> loadedIds;

        private Snapshot(Mark mark, Refusal refusal, Set<String> loadedIds) {
            this.mark = mark;
            this.refusal = refusal;
            this.loadedIds = loadedIds;
        }
    }

    /** Builds the next snapshot for a load without publishing it. A blank id is refused. */
    public Snapshot load(String worldId) {
        Set<String> ids = new LinkedHashSet<>(published.loadedIds);
        if (worldId == null || worldId.isEmpty()) {
            return new Snapshot(Mark.REFUSED, Refusal.MISSING_ID, Set.copyOf(ids));
        }
        if (!ids.add(worldId)) {
            return new Snapshot(Mark.RELOADED, Refusal.NONE, Set.copyOf(ids));
        }
        return new Snapshot(Mark.LOADED, Refusal.NONE, Set.copyOf(ids));
    }

    /** Builds the next snapshot for an unload without publishing it. */
    public Snapshot unload(String worldId) {
        Set<String> ids = new LinkedHashSet<>(published.loadedIds);
        if (worldId == null || worldId.isEmpty()) {
            return new Snapshot(Mark.REFUSED, Refusal.MISSING_ID, Set.copyOf(ids));
        }
        if (!ids.remove(worldId)) {
            return new Snapshot(Mark.REFUSED, Refusal.NOT_LOADED, Set.copyOf(ids));
        }
        return new Snapshot(Mark.UNLOADED, Refusal.NONE, Set.copyOf(ids));
    }

    /** Publishes a snapshot built by {@link #load(String)} or {@link #unload(String)}. */
    public void retain(Snapshot snapshot) {
        published = snapshot;
    }

    /** Returns the last published snapshot. A reader may see this snapshot or the one before it. */
    public Snapshot current() {
        return published;
    }
}
