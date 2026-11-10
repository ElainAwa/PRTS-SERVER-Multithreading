/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

/** Internal default-off pass-through tap of the ChunkPipelineWriteTap write entry; disabled it does not inspect or
 * reorder the caller's write. */
public final class ChunkPipelineWriteTap {

    public static final ChunkPipelineWriteTap INSTANCE = new ChunkPipelineWriteTap();

    private WriteTapGate gate;

    public ChunkPipelineWriteTap() {
        this.gate = new WriteTapGate("ChunkPipelineWriteTap", null);
    }

    public boolean enabled() { return gate.enabled(); }

    public void enable(WriteTapGate.Permission permission) {
        if (permission == null) {
            throw new IllegalArgumentException("permission is required");
        }
        this.gate = new WriteTapGate("ChunkPipelineWriteTap", permission);
        this.gate.enable();
    }

    public boolean allows(String worldId, long owner, long expectedVersion) {
        return gate.allows(worldId, owner, expectedVersion);
    }

    /** Context-aware entry; ENFORCE_NA/disabled stays pass-through. */
    public boolean allows(WriteTapContext context, WriteTapGate.ContextPermission permission) {
        return gate.allows(context, permission);
    }
}
