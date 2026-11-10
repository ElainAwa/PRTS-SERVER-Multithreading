/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import java.util.List;

/** The audited write entry points of the kernel. This is a registry, not a new write path: it names
 * where the existing guard/authority are reached so the registry can report which entries are still unwired. */
public final class WriteEntryRegistry {

    /** The write family of one audited entry. */
    public enum Path {
        BLOCK_WORLD,
        CHUNK_PIPELINE,
        ENTITY_ROW,
        BLOCK_ENTITY,
        WORLD_LIFECYCLE,
        INTENT_COMMIT
    }

    /** One audited entry. {@code wired} says whether the guard/authority actually runs there. */
    public record Entry(String id, Path path, String classAndMethod, boolean defaultEnabled,
                        boolean wired) {
        public Entry {
            if (id == null || id.isEmpty() || path == null || classAndMethod == null) {
                throw new IllegalArgumentException("invalid write entry");
            }
        }
    }

    private static final List<Entry> ENTRIES = List.of(
        new Entry("block-world", Path.BLOCK_WORLD,
            "WorldWriteGuard/PrtsWorldWriteTaps", false, true),
        new Entry("chunk-pipeline", Path.CHUNK_PIPELINE,
            "ChunkPipelineWriteTap", false, true),
        new Entry("entity-row", Path.ENTITY_ROW,
            "EntityTickOwnership/OwnershipLease", false, true),
        new Entry("block-entity", Path.BLOCK_ENTITY,
            "BlockEntityWriteTap", false, true),
        new Entry("world-lifecycle", Path.WORLD_LIFECYCLE,
            "WorldLifecycleWriteTap", false, true),
        new Entry("intent-commit", Path.INTENT_COMMIT,
            "IntentQueue/CommitLog", false, true)
    );

    private WriteEntryRegistry() {
    }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static List<Entry> unwired() {
        return ENTRIES.stream().filter(entry -> !entry.wired()).toList();
    }
}
