/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.offload;

import java.util.Map;
import java.util.Objects;

/** Test-only P0-shaped job-scoped read view: immutable maps + version; unknown read and every write fail. */
public final class AiReadView {

    public record Version(long worldEpoch, long readSetVersion) {
    }

    public static final class UnknownRead extends RuntimeException {
        public UnknownRead(String key) { super("unknown read: " + key); }
    }

    public static final class WriteRejected extends RuntimeException {
        public WriteRejected(String key) { super("write rejected: " + key); }
    }

    private final Map<String, Object> entities;
    private final Map<String, Object> blocks;
    private final Map<String, Object> pois;
    private final Version version;

    public AiReadView(Map<String, Object> entities, Map<String, Object> blocks,
                      Map<String, Object> pois, Version version) {
        this.entities = Map.copyOf(entities);
        this.blocks = Map.copyOf(blocks);
        this.pois = Map.copyOf(pois);
        this.version = Objects.requireNonNull(version);
    }

    public Object entity(String id) {
        Object value = entities.get(id);
        if (value == null) throw new UnknownRead("entity " + id);
        return value;
    }

    public Object block(String pos) {
        Object value = blocks.get(pos);
        if (value == null) throw new UnknownRead("block " + pos);
        return value;
    }

    public Object poi(String pos) {
        Object value = pois.get(pos);
        if (value == null) throw new UnknownRead("poi " + pos);
        return value;
    }

    public Version version() { return version; }

    public void set(String key, Object value) { throw new WriteRejected(key); }
}
