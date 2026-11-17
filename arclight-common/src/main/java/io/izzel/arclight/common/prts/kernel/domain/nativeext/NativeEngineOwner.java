/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.nativeext;

import java.util.OptionalLong;

/** The presence, unique owner and ABI identity of one external native engine: only one owner site
 * may hold an engine instance, an ABI hash that differs from the pinned one is refused before the
 * JNI entry, and an environment without the native payload reports NOT_LOADED_NA instead of a fake
 * zero. Its CPU tickets come from the shared bounded quota of NativeCpuTickets. */
public final class NativeEngineOwner {

    /** Whether the native payload is loaded at all; an absent payload is N/A, never zero. */
    public enum Presence {
        LOADED,
        NOT_LOADED_NA
    }

    /** The identity of one external engine instance and the ABI it must match. */
    public record EngineIdentity(String worldId, long worldIncarnation, String engineId,
                                 long stepOrdinal) {

        public EngineIdentity {
            if (worldId == null || worldId.isEmpty() || engineId == null || engineId.isEmpty()) {
                throw new IllegalArgumentException("an engine identity names its world and engine");
            }
            if (worldIncarnation < 0L || stepOrdinal < 0L) {
                throw new IllegalArgumentException("an engine identity cannot be negative");
            }
        }

        /** The key the unique ownership is kept under. */
        public String key() {
            return worldId + ":" + worldIncarnation + ":" + engineId;
        }
    }

    /** What one claim did. */
    public enum Claim {
        ACQUIRED,
        ALREADY_OWNED,
        OWNED_BY_OTHER,
        ABI_MISMATCH,
        NOT_LOADED_NA
    }

    /** One claim answer with the owner site that holds the engine. */
    public record ClaimResult(Claim claim, String ownerSite) {
    }

    private final Presence presence;
    private final String abiDescriptorHash;
    private final boolean littleEndian;
    private final int alignment;
    private final java.util.Map<String, String> owners = new java.util.LinkedHashMap<>();
    private long refused;

    public NativeEngineOwner(Presence presence, String abiDescriptorHash, boolean littleEndian,
                             int alignment) {
        this.presence = presence;
        this.abiDescriptorHash = abiDescriptorHash;
        this.littleEndian = littleEndian;
        this.alignment = alignment;
    }

    /** Claims the engine for one owner site after the ABI check; a second owner is refused. */
    public synchronized ClaimResult claim(EngineIdentity identity, String ownerSite,
                                          String abiHash, boolean abiLittleEndian,
                                          int abiAlignment) {
        if (presence != Presence.LOADED) {
            return new ClaimResult(Claim.NOT_LOADED_NA, "");
        }
        if (!abiHash.equals(abiDescriptorHash) || abiLittleEndian != littleEndian
            || abiAlignment < alignment) {
            refused++;
            return new ClaimResult(Claim.ABI_MISMATCH, "");
        }
        String current = owners.get(identity.key());
        if (current == null) {
            owners.put(identity.key(), ownerSite);
            return new ClaimResult(Claim.ACQUIRED, ownerSite);
        }
        if (current.equals(ownerSite)) {
            return new ClaimResult(Claim.ALREADY_OWNED, current);
        }
        refused++;
        return new ClaimResult(Claim.OWNED_BY_OTHER, current);
    }

    /** Releases one engine only for the owner that holds it. */
    public synchronized boolean release(EngineIdentity identity, String ownerSite) {
        if (presence != Presence.LOADED || !ownerSite.equals(owners.get(identity.key()))) {
            return false;
        }
        owners.remove(identity.key());
        return true;
    }

    /** The owner site of one engine, or null when nobody holds it. */
    public synchronized String owner(EngineIdentity identity) {
        return owners.get(identity.key());
    }

    /** The reading snapshot of the native layer: null while the payload is not loaded. */
    public String snapshotNa() {
        return presence == Presence.LOADED ? "loaded" : null;
    }

    /** The CPU quota of the native layer; N/A while the payload is not loaded. */
    public OptionalLong ticketQuotaNa() {
        return presence == Presence.LOADED ? OptionalLong.of(0L) : OptionalLong.empty();
    }

    public synchronized long refused() {
        return refused;
    }
}
